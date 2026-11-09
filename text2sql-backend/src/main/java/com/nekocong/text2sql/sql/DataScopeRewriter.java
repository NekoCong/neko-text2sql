package com.nekocong.text2sql.sql;

import com.nekocong.text2sql.access.DataScopePolicyEngine;
import com.nekocong.text2sql.access.PrincipalAttributes;
import com.nekocong.text2sql.access.PrincipalAttribute;
import com.nekocong.text2sql.access.RowPredicate;
import com.nekocong.text2sql.security.DataScope;
import com.nekocong.text2sql.security.SqlPermissionException;
import com.nekocong.text2sql.security.TablePolicy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Database;
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
            Set<String> protectedTables = protectedTables(scope, datasourceId);
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
     * The lower-cased, unqualified name of a CTE, with quoting removed.
     */
    private static String cteName(WithItem item) {
        String name = item.getAlias() == null ? null : item.getAlias().getName();
        String normalized = normalizeIdentifier(name);
        if (normalized.isEmpty()) {
            throw new SqlPermissionException("公共表表达式缺少名称，无法应用数据权限");
        }
        return normalized;
    }

    /**
     * Normalizes an identifier for comparison: quoting removed, unqualified, lower-cased.
     *
     * <p>Both sides of a CTE-versus-table comparison go through this so that {@code `mine`} matches
     * {@code mine} and {@code campus.student} compares by its base name {@code student}.
     *
     * @param name the raw identifier (may be {@code null})
     * @return the normalized name; empty when nothing usable remains
     */
    private static String normalizeIdentifier(String name) {
        if (name == null) {
            return "";
        }
        String unqualified = name;
        int dot = unqualified.lastIndexOf('.');
        if (dot >= 0) {
            unqualified = unqualified.substring(dot + 1);
        }
        return unqualified.replace("`", "").replace("\"", "").replace("[", "").replace("]", "")
                .trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Reports whether a table reference has no schema/database qualifier.
     *
     * <p>Only such a reference can resolve to a CTE; a qualified one is always a physical table.
     * JSqlParser reports a missing qualifier as {@code null} (schema) or an empty string (database),
     * so both are treated as absent.
     */
    private static boolean isUnqualified(Table table) {
        Database database = table.getDatabase();
        return isBlank(table.getSchemaName())
                && (database == null || isBlank(database.getDatabaseName()));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * The lower-cased table names that must not be shadowed by a CTE for this caller in this
     * datasource.
     *
     * <p>This is every table the policy configures for the datasource — {@code ALL}, {@code FILTER}
     * and {@code DENY} alike — because the rule is "no CTE may be named like a table the policy
     * governs". Using the visible set would let a CTE shadow a {@code DENY}-only table. A bypassing
     * caller gets an empty set (their statement is returned unchanged).
     */
    private Set<String> protectedTables(DataScope scope, String datasourceId) {
        return policy.configuredTables(scope, datasourceId);
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
        // A CTE can only be referenced unqualified. `campus.salary` is always the physical table even
        // when a CTE called `salary` exists: Table.getName() returns just the base name, so matching
        // on it alone would let a qualified reference escape the whitelist and the row filter.
        if (isUnqualified(table) && ctes.contains(normalizeIdentifier(name))) {
            return table;
        }
        // For the policy the name must be the bare identifier: a quoted reference (`mine`) must not be
        // sent to the whitelist with its quoting still attached, which would deny a legal table.
        String policyName = name.replace("`", "").replace("\"", "").replace("[", "").replace("]", "");
        List<RowPredicate> predicates = policy.predicates(scope, datasourceId, policyName);

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
        inner.setWhere(buildPredicate(predicates, scope));
        ParenthesedSelect derived = new ParenthesedSelect();
        derived.setSelect(inner);
        derived.setAlias(new Alias(alias, true));
        return derived;
    }

    /**
     * Builds the row predicate for one table reference.
     *
     * <p>The predicate is rendered to text (identifiers are bare, values are validated literals) and
     * then parsed back into an AST. Re-parsing alone is unsafe: the parser returned a bare column for
     * a value containing {@code \'}, turning "only my rows" into "every row". So the parsed tree is
     * verified against what was rendered, and a mismatch denies the query — fail closed rather than
     * execute an under-filtered statement.
     *
     * <p>Each predicate's value comes from the principal attributes; a missing or empty value denies
     * the query instead of producing a predicate that matches nothing (which would silently return an
     * empty result) or, worse, everything.
     */
    private static Expression buildPredicate(List<RowPredicate> predicates, DataScope scope) {
        List<String> parts = new ArrayList<>(predicates.size());
        for (RowPredicate predicate : predicates) {
            parts.add(renderOne(predicate, scope));
        }
        if (parts.isEmpty()) {
            throw new SqlPermissionException("行过滤条件为空，已拒绝执行");
        }
        String text = parts.size() == 1
                ? parts.get(0)
                : parts.stream().map(part -> "(" + part + ")").collect(Collectors.joining(" OR "));
        Expression parsed;
        try {
            parsed = CCJSqlParserUtil.parseCondExpression(text);
        } catch (Exception ex) {
            throw new SqlPermissionException("无法生成行过滤条件", ex);
        }
        verifyPredicate(parsed, predicates, parts);
        return parsed;
    }

    /**
     * Fails unless the parsed predicate has exactly the shape that was rendered: one {@code IN} over a
     * plain column per expected predicate, with the expected literal count.
     *
     * <p>This is what catches a value whose literal the parser could not read back verbatim (for
     * example a bare-column result), instead of letting the filter silently disappear.
     */
    private static void verifyPredicate(
            Expression parsed, List<RowPredicate> predicates, List<String> parts) {
        List<Expression> operands;
        if (predicates.size() == 1) {
            operands = List.of(parsed);
        } else {
            operands = flattenOr(parsed);
        }
        if (operands.size() != predicates.size()) {
            throw new SqlPermissionException("无法生成行过滤条件，已拒绝执行");
        }
        for (int i = 0; i < predicates.size(); i++) {
            if (!isInOverColumn(operands.get(i))) {
                throw new SqlPermissionException("无法生成行过滤条件，已拒绝执行");
            }
        }
    }

    /** Flattens {@code (a) OR (b) OR (c)} into {@code [a, b, c]}; anything else yields the input. */
    private static List<Expression> flattenOr(Expression expression) {
        if (expression instanceof OrExpression or) {
            List<Expression> flattened = new ArrayList<>();
            flattened.addAll(flattenOr(or.getLeftExpression()));
            flattened.addAll(flattenOr(or.getRightExpression()));
            return flattened;
        }
        if (expression instanceof Parenthesis parenthesis) {
            return flattenOr(parenthesis.getExpression());
        }
        return List.of(expression);
    }

    private static boolean isInOverColumn(Expression expression) {
        if (!(expression instanceof InExpression in)) {
            return false;
        }
        if (!(in.getLeftExpression() instanceof Column)) {
            return false;
        }
        Expression right = in.getRightExpression();
        return right instanceof ExpressionList<?>;
    }

    private static String renderOne(RowPredicate predicate, DataScope scope) {
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
        // The column is a bare identifier (checked above); every value is a validated literal, so no
        // caller-controlled text reaches the predicate unescaped.
        return switch (attribute) {
            case CLASS_IDS -> column + " IN (" + numericList(principal.classIds(), column) + ")";
            case STUDENT_ID -> column + " IN (" + numeric(principal.studentId(), attribute) + ")";
            case STUDENT_NO -> column + " IN (" + stringLiteral(text(principal.studentNo(), attribute)) + ")";
            case USERNAME -> column + " IN (" + stringLiteral(text(principal.username(), attribute)) + ")";
            case USER_ID -> column + " IN (" + stringLiteral(text(principal.userId(), attribute)) + ")";
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
     * Renders a string principal value as a SQL literal.
     *
     * <p>Backslashes are doubled first so a value such as {@code a\'} cannot re-open the literal or
     * smuggle in a second statement on a MySQL-family parser, then quotes are doubled. The result is
     * verified after re-parsing (see {@link #verifyPredicate}) so a value the parser cannot read back
     * verbatim denies the query instead of dropping the filter.
     */
    private static String stringLiteral(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    /**
     * Renders a string value as a quoted SQL literal.
     *
     * @param value the raw value
     * @return the escaped literal
     */
    static String quote(String value) {
        return stringLiteral(value);
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
