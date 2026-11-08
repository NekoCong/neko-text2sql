package com.nekocong.text2sql.access;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Computes the effective data policy for one table from the caller's roles.
 *
 * <p>Merge rule: any {@code DENY} wins; otherwise any {@code ALL} makes the table unrestricted;
 * otherwise the {@code FILTER} predicates are OR-ed. No rule means deny. Invalid rules (unknown
 * attribute, unsafe column, missing column) deny the table rather than granting access.
 */
public final class DataScopePolicyEngine {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * Resolves the policy for one table.
     *
     * @param access the caller's effective access, or {@code null}
     * @param datasourceId the datasource id
     * @param tableName the table name (unqualified)
     * @return the decision
     */
    public PolicyDecision decide(EffectiveAccess access, String datasourceId, String tableName) {
        if (access == null || datasourceId == null || tableName == null) {
            return PolicyDecision.deny();
        }
        if (access.bypassesDataScope()) {
            return PolicyDecision.all();
        }
        List<DataScopeRule> rules = access.dataScopes().stream()
                .filter(rule -> datasourceId.equals(rule.datasourceId()))
                .filter(rule -> tableName.equalsIgnoreCase(rule.tableName()))
                .toList();
        if (rules.isEmpty() || rules.stream().anyMatch(rule -> rule.policy() == DataScopePolicy.DENY)) {
            return PolicyDecision.deny();
        }
        if (rules.stream().anyMatch(rule -> rule.policy() == DataScopePolicy.ALL)) {
            return PolicyDecision.all();
        }

        List<RowPredicate> predicates = new ArrayList<>();
        for (DataScopeRule rule : rules) {
            Optional<RowPredicate> predicate = toPredicate(rule);
            if (predicate.isEmpty()) {
                // A malformed rule denies the table instead of silently widening access.
                return PolicyDecision.deny();
            }
            predicates.add(predicate.get());
        }
        return predicates.isEmpty() ? PolicyDecision.deny() : PolicyDecision.filter(predicates);
    }

    private static Optional<RowPredicate> toPredicate(DataScopeRule rule) {
        if (rule.policy() != DataScopePolicy.FILTER) {
            return Optional.empty();
        }
        String column = rule.columnName();
        if (column == null || !SAFE_IDENTIFIER.matcher(column).matches()) {
            return Optional.empty();
        }
        Optional<PrincipalAttribute> attribute = PrincipalAttribute.fromCode(rule.principalAttribute());
        if (attribute.isEmpty()) {
            return Optional.empty();
        }
        RowPredicate.Operator operator =
                attribute.get() == PrincipalAttribute.CLASS_IDS
                        ? RowPredicate.Operator.IN
                        : RowPredicate.Operator.EQ;
        return Optional.of(new RowPredicate(column.toLowerCase(Locale.ROOT), operator, attribute.get()));
    }

    /**
     * Reports whether a column is a safe, unqualified identifier.
     *
     * @param column the column name
     * @return {@code true} when it can be rendered into SQL
     */
    public static boolean isSafeColumn(String column) {
        return column != null && SAFE_IDENTIFIER.matcher(column).matches();
    }

    /**
     * Reports whether a table is a safe, unqualified identifier.
     *
     * @param table the table name
     * @return {@code true} when it can be rendered into SQL
     */
    public static boolean isSafeTable(String table) {
        return table != null && SAFE_IDENTIFIER.matcher(table).matches();
    }

    /**
     * The set of attributes that may be configured for {@code FILTER} policies.
     *
     * @return the closed catalog
     */
    public static Set<PrincipalAttribute> supportedAttributes() {
        return Set.of(PrincipalAttribute.values());
    }
}
