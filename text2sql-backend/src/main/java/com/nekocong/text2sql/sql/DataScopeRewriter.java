package com.nekocong.text2sql.sql;

import com.nekocong.text2sql.access.DataScopePolicyEngine;
import com.nekocong.text2sql.access.PrincipalAttributes;
import com.nekocong.text2sql.access.PrincipalAttribute;
import com.nekocong.text2sql.access.RowPredicate;
import com.nekocong.text2sql.security.DataScope;
import com.nekocong.text2sql.security.SqlPermissionException;
import com.nekocong.text2sql.security.TablePolicy;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.statement.select.WithItem;
import org.springframework.stereotype.Component;

/**
 * Enforces data permission by rewriting a generated read-only query into an equivalent one whose
 * every scoped table reference is filtered for the caller.
 *
 * <p>Each protected table reference is replaced by a derived table carrying the row predicate:
 *
 * <pre>
 *   FROM score sc   ->   FROM (SELECT * FROM score WHERE student_id IN (1)) AS sc
 * </pre>
 *
 * <p>This is the single enforcement point for both the deterministic pipeline and the agent tools.
 * It is fail-closed: a statement that cannot be parsed, contains an unsupported construct, or
 * references a table outside the whitelist is rejected — the original SQL is never executed.
 */
@Component
public class DataScopeRewriter {

    private final TablePolicy policy;

    /**
     * Creates the rewriter.
     *
     * @param policy the table/row policy
     */
    public DataScopeRewriter(TablePolicy policy) {
        this.policy = policy;
    }

    /**
     * Rewrites a statement for the caller's scope.
     *
     * @param sql the validated, read-only SQL
     * @param scope the caller's scope
     * @param datasourceId the datasource the statement will run against
     * @return the enforced SQL (identical to the input for admins)
     * @throws SqlPermissionException when the query cannot be safely scoped
     */
    public String rewrite(String sql, DataScope scope, String datasourceId) {
        if (scope != null && scope.bypassesDataScope()) {
            return sql;
        }
        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (Exception ex) {
            throw new SqlPermissionException("无法解析 SQL，已拒绝执行以保护数据范围", ex);
        }
        if (!(statement instanceof Select select)) {
            throw new SqlPermissionException("只支持单条只读查询");
        }
        List<WithItem> topLevelWithItems = select.getWithItemsList();
        if (topLevelWithItems != null) {
            for (WithItem item : topLevelWithItems) {
                if (item.isRecursive()) {
                    throw new SqlPermissionException("不支持 WITH RECURSIVE 查询，无法应用数据权限");
                }
            }
        }
        try {
            rewriteSelect(select, scope, datasourceId, new HashSet<>());
            return select.toString();
        } catch (SqlPermissionException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new SqlPermissionException("无法应用数据权限，已拒绝执行", ex);
        }
    }

    private void rewriteSelect(
            Select select, DataScope scope, String datasourceId, Set<String> inheritedCtes) {
        // Visible CTE names are scoped like SQL itself: a CTE is visible only to the text after it
        // in the same WITH list, and to the statement the WITH list belongs to. A CTE's own name is
        // never visible inside its own body (that is what RECURSIVE is for, and we reject it).
        Set<String> ctes = new HashSet<>(inheritedCtes);
        List<WithItem> withItems = select.getWithItemsList();
        if (withItems != null) {
            Set<String> protectedTables = inheritedVisibleTables(scope, datasourceId);
            for (WithItem item : withItems) {
                if (item.isRecursive()) {
                    throw new SqlPermissionException("不支持 WITH RECURSIVE 查询，无法应用数据权限");
                }
                String cteName = cteName(item);
                if (protectedTables.contains(cteName)) {
                    throw new SqlPermissionException(
                            "公共表表达式 " + cteName + " 与受保护的表同名，已拒绝执行");
                }
                // Only earlier CTEs are visible while rewriting this body: ctes does not yet hold it.
                rewriteSelect(item.getSelect(), scope, datasourceId, ctes);
                ctes.add(cteName);
            }
        }

        switch (select) {
            case PlainSelect plain -> rewritePlainSelect(plain, scope, datasourceId, ctes);
            case SetOperationList setOperation -> {
                for (Select branch : setOperation.getSelects()) {
                    rewriteSelect(branch, scope, datasourceId, ctes);
                }
                rewriteOrderBy(setOperation.getOrderByElements(), scope, datasourceId, ctes);
            }
            case ParenthesedSelect parenthesed -> {
                rewriteSelect(parenthesed.getSelect(), scope, datasourceId, ctes);
                rewriteOrderBy(parenthesed.getOrderByElements(), scope, datasourceId, ctes);
            }
            case Values _ ->
                // A VALUES list can carry scalar subqueries; rejecting it keeps enforcement total.
                    throw new SqlPermissionException("不支持 VALUES 查询，无法应用数据权限");
            default -> throw new SqlPermissionException("不支持该查询结构，无法应用数据权限");
        }
    }

    /**
     * The lower-cased, unqualified name of a CTE.
     *
     * <p>A WITH item without a name cannot be matched against protected tables and would let its
     * body's references be misclassified, so it is rejected.
     */
    private static String cteName(WithItem item) {
        String name = item.getAlias() == null ? null : item.getAlias().getName();
        if (name == null || name.isBlank()) {
            throw new SqlPermissionException("公共表表达式缺少名称，无法应用数据权限");
        }
        String unqualified = name;
        int dot = unqualified.lastIndexOf('.');
        if (dot >= 0) {
            unqualified = unqualified.substring(dot + 1);
        }
        unqualified = unqualified.replace("`", "").replace("\"", "").trim().toLowerCase(Locale.ROOT);
        if (unqualified.isEmpty()) {
            throw new SqlPermissionException("公共表表达式缺少名称，无法应用数据权限");
        }
        return unqualified;
    }

    /**
     * The lower-cased table names that must not be shadowed by a CTE for this caller in this
     * datasource.
     *
     * <p>This is the policy's {@link TablePolicy#visibleTables(DataScope, String) visible set}, i.e.
     * every configured table that is not denied; a name in it is a protected table whose reference
     * the rewriter would otherwise filter. A {@code null} result (a bypassing caller, whose statement
     * is returned unchanged) is normalized to the empty set.
     */
    private Set<String> inheritedVisibleTables(DataScope scope, String datasourceId) {
        Set<String> visible = policy.visibleTables(scope, datasourceId);
        return visible == null ? Set.of() : visible;
    }

    private void rewritePlainSelect(
            PlainSelect select, DataScope scope, String datasourceId, Set<String> ctes) {
        if (select.getIntoTables() != null && !select.getIntoTables().isEmpty()) {
            throw new SqlPermissionException("不支持 SELECT ... INTO，已拒绝执行");
        }
        Set<String> aliases = new HashSet<>();
        select.setFromItem(rewriteFromItem(select.getFromItem(), scope, datasourceId, ctes, aliases));

        List<Join> joins = select.getJoins();
        if (joins != null) {
            for (Join join : joins) {
                join.setRightItem(rewriteFromItem(join.getRightItem(), scope, datasourceId, ctes, aliases));
                if (join.getOnExpressions() != null) {
                    for (Expression on : join.getOnExpressions()) {
                        rewriteExpression(on, scope, datasourceId, ctes);
                    }
                }
            }
        }

        rewriteExpression(select.getWhere(), scope, datasourceId, ctes);
        rewriteExpression(select.getHaving(), scope, datasourceId, ctes);
        for (SelectItem<?> item : select.getSelectItems()) {
            rewriteExpression(item.getExpression(), scope, datasourceId, ctes);
        }
        if (select.getGroupBy() != null) {
            rewriteExpression(select.getGroupBy().getGroupByExpressionList(), scope, datasourceId, ctes);
        }
        rewriteOrderBy(select.getOrderByElements(), scope, datasourceId, ctes);
    }

    private void rewriteOrderBy(
            List<OrderByElement> orderByElements,
            DataScope scope,
            String datasourceId,
            Set<String> ctes) {
        if (orderByElements == null) {
            return;
        }
        for (OrderByElement order : orderByElements) {
            rewriteExpression(order.getExpression(), scope, datasourceId, ctes);
        }
    }

    private FromItem rewriteFromItem(
            FromItem item,
            DataScope scope,
            String datasourceId,
            Set<String> ctes,
            Set<String> aliases) {
        switch (item) {
            case null -> {
                return null;
            }
            case Table table -> {
                return rewriteTable(table, scope, datasourceId, ctes, aliases);
            }
            case ParenthesedSelect parenthesed -> {
                // Pass the parenthesized select itself: its own ORDER BY / WITH list must be visited too.
                rewriteSelect(parenthesed, scope, datasourceId, ctes);
                return item;
            }
            case ParenthesedFromItem parenthesed -> {
                parenthesed.setFromItem(rewriteFromItem(parenthesed.getFromItem(), scope, datasourceId, ctes, aliases));
                if (parenthesed.getJoins() != null) {
                    for (Join join : parenthesed.getJoins()) {
                        join.setRightItem(rewriteFromItem(join.getRightItem(), scope, datasourceId, ctes, aliases));
                        if (join.getOnExpressions() != null) {
                            for (Expression on : join.getOnExpressions()) {
                                rewriteExpression(on, scope, datasourceId, ctes);
                            }
                        }
                    }
                }
                return item;
            }
            default -> {
            }
        }
        throw new SqlPermissionException("不支持该表引用形式，无法应用数据权限");
    }

    private FromItem rewriteTable(
            Table table, DataScope scope, String datasourceId, Set<String> ctes, Set<String> aliases) {
        String name = table.getName() == null ? "" : table.getName().toLowerCase(Locale.ROOT);
        if (ctes.contains(name)) {
            return table;
        }
        List<RowPredicate> predicates = policy.predicates(scope, datasourceId, name);

        String declaredAlias =
                table.getAlias() != null ? table.getAlias().getName() : name;
        if (declaredAlias == null || declaredAlias.isBlank()) {
            declaredAlias = "t";
        }
        if (predicates.isEmpty()) {
            register(declaredAlias, aliases);
            return table;
        }

        String alias = uniqueAlias(declaredAlias, aliases);
        register(alias, aliases);

        Table source = new Table(table.getFullyQualifiedName());
        source.setAlias(null);
        PlainSelect inner = new PlainSelect();
        inner.addSelectItems(new AllColumns());
        inner.setFromItem(source);
        String predicate = renderPredicates(predicates, scope);
        try {
            inner.setWhere(CCJSqlParserUtil.parseCondExpression(predicate));
        } catch (Exception ex) {
            throw new SqlPermissionException("无法生成行过滤条件", ex);
        }
        ParenthesedSelect derived = new ParenthesedSelect();
        derived.setSelect(inner);
        derived.setAlias(new Alias(alias, true));
        return derived;
    }

    /**
     * Renders the OR of the caller's row predicates.
     *
     * <p>Each predicate's value comes from the principal attributes; a missing or empty value denies
     * the query instead of producing a predicate that matches nothing (which would silently return an
     * empty result) or, worse, everything.
     */
    private static String renderPredicates(List<RowPredicate> predicates, DataScope scope) {
        if (predicates.size() == 1) {
            return renderPredicate(predicates.get(0), scope);
        }
        return predicates.stream()
                .map(predicate -> "(" + renderPredicate(predicate, scope) + ")")
                .collect(Collectors.joining(" OR "));
    }

    private static String renderPredicate(RowPredicate predicate, DataScope scope) {
        String column = predicate.column();
        if (!DataScopePolicyEngine.isSafeColumn(column)) {
            throw new SqlPermissionException("行过滤列不安全，已拒绝执行：" + column);
        }
        PrincipalAttributes principal = scope.access().principal();
        if (principal == null) {
            throw new SqlPermissionException("当前账号缺少数据范围属性，无法应用数据权限");
        }
        PrincipalAttribute attribute = predicate.attribute();
        if (attribute == null) {
            throw new SqlPermissionException("行过滤属性无效，已拒绝执行");
        }
        // The rendered predicate is interpolated verbatim into a parsed condition, so every piece of
        // identifier text in it must be a bare identifier. Values never take part in this check:
        // they are always emitted as a validated literal (numeric or escaped string).
        return switch (attribute) {
            case CLASS_IDS -> column + " IN (" + numericList(principal.classIds(), column) + ")";
            case STUDENT_ID -> column + " IN (" + numeric(principal.studentId(), attribute) + ")";
            case STUDENT_NO -> column + " IN (" + quote(text(principal.studentNo(), attribute)) + ")";
            case USERNAME -> column + " IN (" + quote(text(principal.username(), attribute)) + ")";
            case USER_ID -> column + " IN (" + quote(text(principal.userId(), attribute)) + ")";
        };
    }

    private static String numericList(Set<Long> values, String column) {
        if (values == null || values.isEmpty()) {
            throw new SqlPermissionException("当前账号缺少数据范围值，无法过滤 " + column);
        }
        return values.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static String numeric(Long value, PrincipalAttribute attribute) {
        if (value == null) {
            throw new SqlPermissionException(
                    "当前账号缺少数据范围值（" + attribute.code() + "），无法应用数据权限");
        }
        return String.valueOf(value);
    }

    private static String text(String value, PrincipalAttribute attribute) {
        if (value == null || value.isBlank()) {
            throw new SqlPermissionException(
                    "当前账号缺少数据范围值（" + attribute.code() + "），无法应用数据权限");
        }
        return value;
    }

    /**
     * Renders a string literal. Backslashes are doubled first so a value such as {@code a\'} cannot
     * re-open the literal or smuggle in a second statement on a MySQL-family parser, then quotes are
     * doubled.
     */
    static String quote(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    private static String uniqueAlias(String requested, Set<String> aliases) {
        String candidate = requested;
        int suffix = 0;
        while (aliases.contains(candidate.toLowerCase(Locale.ROOT))) {
            suffix++;
            candidate = requested + "_sp" + suffix;
        }
        return candidate;
    }

    private static void register(String alias, Set<String> aliases) {
        if (alias != null) {
            aliases.add(alias.toLowerCase(Locale.ROOT));
        }
    }

    private void rewriteExpression(Expression expression, DataScope scope, String datasourceId, Set<String> ctes) {
        if (expression == null) {
            return;
        }
        expression.accept(
                new ExpressionVisitorAdapter() {
                    @Override
                    public void visit(Select subSelect) {
                        // ParenthesedSelect.accept(ExpressionVisitor) dispatches to visit(Select);
                        // it does not call the visit(ParenthesedSelect) overload. Pass it through so
                        // its inner select AND its own ORDER BY / WITH list are rewritten.
                        rewriteSelect(subSelect, scope, datasourceId, ctes);
                    }
                });
    }
}
