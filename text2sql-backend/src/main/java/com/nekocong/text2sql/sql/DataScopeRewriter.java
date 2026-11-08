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
     * @param sql   the validated, read-only SQL
     * @param scope the caller's scope
     * @return the enforced SQL (identical to the input for admins)
     * @throws SqlPermissionException when the query cannot be safely scoped
     */
    public String rewrite(String sql, DataScope scope) {
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
        try {
            rewriteSelect(select, scope, new HashSet<>());
            return select.toString();
        } catch (SqlPermissionException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new SqlPermissionException("无法应用数据权限，已拒绝执行", ex);
        }
    }

    private void rewriteSelect(Select select, DataScope scope, Set<String> inheritedCtes) {
        Set<String> ctes = new HashSet<>(inheritedCtes);
        List<WithItem> withItems = select.getWithItemsList();
        if (withItems != null) {
            for (WithItem item : withItems) {
                if (item.getAlias() != null && item.getAlias().getName() != null) {
                    ctes.add(item.getAlias().getName().toLowerCase(Locale.ROOT));
                }
            }
            for (WithItem item : withItems) {
                rewriteSelect(item.getSelect(), scope, ctes);
            }
        }

        switch (select) {
            case PlainSelect plain -> rewritePlainSelect(plain, scope, ctes);
            case SetOperationList setOperation -> {
                for (Select branch : setOperation.getSelects()) {
                    rewriteSelect(branch, scope, ctes);
                }
                rewriteOrderBy(setOperation.getOrderByElements(), scope, ctes);
            }
            case ParenthesedSelect parenthesed -> {
                rewriteSelect(parenthesed.getSelect(), scope, ctes);
                rewriteOrderBy(parenthesed.getOrderByElements(), scope, ctes);
            }
            case Values _ ->
                // A VALUES list can carry scalar subqueries; rejecting it keeps enforcement total.
                    throw new SqlPermissionException("不支持 VALUES 查询，无法应用数据权限");
            default -> throw new SqlPermissionException("不支持该查询结构，无法应用数据权限");
        }
    }

    private void rewritePlainSelect(PlainSelect select, DataScope scope, Set<String> ctes) {
        if (select.getIntoTables() != null && !select.getIntoTables().isEmpty()) {
            throw new SqlPermissionException("不支持 SELECT ... INTO，已拒绝执行");
        }
        Set<String> aliases = new HashSet<>();
        select.setFromItem(rewriteFromItem(select.getFromItem(), scope, ctes, aliases));

        List<Join> joins = select.getJoins();
        if (joins != null) {
            for (Join join : joins) {
                join.setRightItem(rewriteFromItem(join.getRightItem(), scope, ctes, aliases));
                if (join.getOnExpressions() != null) {
                    for (Expression on : join.getOnExpressions()) {
                        rewriteExpression(on, scope, ctes);
                    }
                }
            }
        }

        rewriteExpression(select.getWhere(), scope, ctes);
        rewriteExpression(select.getHaving(), scope, ctes);
        for (SelectItem<?> item : select.getSelectItems()) {
            rewriteExpression(item.getExpression(), scope, ctes);
        }
        if (select.getGroupBy() != null) {
            rewriteExpression(select.getGroupBy().getGroupByExpressionList(), scope, ctes);
        }
        rewriteOrderBy(select.getOrderByElements(), scope, ctes);
    }

    private void rewriteOrderBy(
            List<OrderByElement> orderByElements, DataScope scope, Set<String> ctes) {
        if (orderByElements == null) {
            return;
        }
        for (OrderByElement order : orderByElements) {
            rewriteExpression(order.getExpression(), scope, ctes);
        }
    }

    private FromItem rewriteFromItem(
            FromItem item, DataScope scope, Set<String> ctes, Set<String> aliases) {
        switch (item) {
            case null -> {
                return null;
            }
            case Table table -> {
                return rewriteTable(table, scope, ctes, aliases);
            }
            case ParenthesedSelect parenthesed -> {
                // Pass the parenthesized select itself: its own ORDER BY / WITH list must be visited too.
                rewriteSelect(parenthesed, scope, ctes);
                return item;
            }
            case ParenthesedFromItem parenthesed -> {
                parenthesed.setFromItem(rewriteFromItem(parenthesed.getFromItem(), scope, ctes, aliases));
                if (parenthesed.getJoins() != null) {
                    for (Join join : parenthesed.getJoins()) {
                        join.setRightItem(rewriteFromItem(join.getRightItem(), scope, ctes, aliases));
                        if (join.getOnExpressions() != null) {
                            for (Expression on : join.getOnExpressions()) {
                                rewriteExpression(on, scope, ctes);
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
            Table table, DataScope scope, Set<String> ctes, Set<String> aliases) {
        String name = table.getName() == null ? "" : table.getName().toLowerCase(Locale.ROOT);
        if (ctes.contains(name)) {
            return table;
        }
        List<RowPredicate> predicates = policy.predicates(scope, null, name);

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
        String predicate = renderPredicates(predicates, scope, table);
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
    private static String renderPredicates(
            List<RowPredicate> predicates, DataScope scope, Table table) {
        if (predicates.size() == 1) {
            return renderPredicate(predicates.get(0), scope, table);
        }
        return predicates.stream()
                .map(predicate -> "(" + renderPredicate(predicate, scope, table) + ")")
                .collect(Collectors.joining(" OR "));
    }

    private static String renderPredicate(RowPredicate predicate, DataScope scope, Table table) {
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

    private static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
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

    private void rewriteExpression(Expression expression, DataScope scope, Set<String> ctes) {
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
                        rewriteSelect(subSelect, scope, ctes);
                    }
                });
    }
}
