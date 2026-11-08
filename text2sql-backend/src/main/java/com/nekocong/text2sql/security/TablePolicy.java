package com.nekocong.text2sql.security;

import com.nekocong.text2sql.access.DataScopePolicyEngine;
import com.nekocong.text2sql.access.PolicyDecision;
import com.nekocong.text2sql.access.RowPredicate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The enforcement view of the database-configured data policies.
 *
 * <p>Policies live in the access-control tables ({@code access_data_scope}, one row per role,
 * datasource and table) and are resolved per request by the {@link DataScopePolicyEngine}. This
 * class only adapts that decision to the questions the pipeline, the agent tools and the schema
 * providers ask: is a table visible, which tables are visible, and which row predicates apply.
 *
 * <p>Fail closed: a scope without resolved access, an unknown policy, a missing principal value or
 * an unsafe identifier denies the table rather than widening access. Enforcement never branches on
 * the role name.
 */
@Component
public class TablePolicy {

    private final DataScopePolicyEngine engine;

    /** Creates the policy with the default decision engine. */
    public TablePolicy() {
        this(new DataScopePolicyEngine());
    }

    /**
     * Creates the policy.
     *
     * @param engine the decision engine
     */
    public TablePolicy(DataScopePolicyEngine engine) {
        this.engine = engine;
    }

    /**
     * Reports whether the caller may reference a table.
     *
     * @param scope the caller's scope
     * @param datasourceId the datasource id
     * @param table the referenced table (unqualified)
     * @return {@code true} when the table is unrestricted or filtered, {@code false} when denied
     */
    public boolean allows(DataScope scope, String datasourceId, String table) {
        return !decide(scope, datasourceId, table).denied();
    }

    /**
     * Fails when the table is not visible to the caller.
     *
     * @param scope the caller's scope
     * @param datasourceId the datasource id
     * @param table the referenced table (unqualified)
     * @throws SqlPermissionException when the table is denied
     */
    public void requireAllowed(DataScope scope, String datasourceId, String table) {
        if (!allows(scope, datasourceId, table)) {
            throw new SqlPermissionException("表 " + table + " 不在当前数据范围内");
        }
    }

    /**
     * The tables the caller may reference in a datasource.
     *
     * @param scope the caller's scope
     * @param datasourceId the datasource id
     * @return {@code null} when the caller bypasses data scopes (unrestricted), otherwise the
     *     distinct configured table names whose decision is not {@code DENY}
     */
    public Set<String> visibleTables(DataScope scope, String datasourceId) {
        if (scope == null || scope.access() == null) {
            // No resolved access: nothing is visible. An empty set (not null) is a closed whitelist.
            return Set.of();
        }
        if (scope.bypassesDataScope()) {
            return null;
        }
        String effective = datasourceId(scope, datasourceId);
        if (effective == null) {
            return Set.of();
        }
        Set<String> tables = new LinkedHashSet<>();
        scope.access().dataScopes().stream()
                .filter(rule -> effective.equals(rule.datasourceId()))
                .filter(rule -> DataScopePolicyEngine.isSafeTable(rule.tableName()))
                .map(rule -> rule.tableName().toLowerCase(Locale.ROOT))
                .forEach(table -> {
                    if (!decide(scope, effective, table).denied()) {
                        tables.add(table);
                    }
                });
        return tables;
    }

    /**
     * The row predicates to enforce on one table reference.
     *
     * @param scope the caller's scope
     * @param datasourceId the datasource id
     * @param table the referenced table (unqualified)
     * @return the OR-ed predicates, empty when the table is unrestricted
     * @throws SqlPermissionException when the table is denied
     */
    public List<RowPredicate> predicates(DataScope scope, String datasourceId, String table) {
        PolicyDecision decision = decide(scope, datasourceId, table);
        if (decision.denied()) {
            throw new SqlPermissionException("表 " + table + " 不在当前数据范围内");
        }
        if (decision.filtered()) {
            return decision.predicates();
        }
        return List.of();
    }

    /**
     * Compatibility fallback for callers that do not know the resolved datasource. A {@code null} or
     * blank id means "the default": the single effective grant, if there is exactly one. Anything
     * else denies.
     *
     * <p>Enforcement call sites (the pipeline and the agent tools) pass the datasource they resolved
     * themselves, so a caller granted several datasources is not mistaken for an unscoped one.
     *
     * @param scope the caller's scope
     * @param datasourceId the requested datasource id, or {@code null}
     * @return the datasource id, or {@code null} when it cannot be determined
     */
    public String datasourceId(DataScope scope, String datasourceId) {
        if (scope == null || scope.access() == null) {
            return null;
        }
        if (datasourceId != null && !datasourceId.isBlank()) {
            return datasourceId;
        }
        Set<String> granted = scope.access().datasourceIds();
        // The default is a boundary decision made by DatasourceAccessGuard (which always yields a
        // concrete grant), so here only the "exactly one grant" case is unambiguous.
        return granted.size() == 1 ? granted.iterator().next() : null;
    }

    private PolicyDecision decide(DataScope scope, String datasourceId, String table) {
        if (scope == null || scope.access() == null) {
            return PolicyDecision.deny();
        }
        String name = table == null ? null : table.toLowerCase(Locale.ROOT);
        if (!DataScopePolicyEngine.isSafeTable(name)) {
            return PolicyDecision.deny();
        }
        // An explicitly supplied id is authoritative: it is the datasource the statement runs
        // against. Only fall back to the single-grant guess when the caller supplied none.
        String effective =
                datasourceId != null && !datasourceId.isBlank()
                        ? datasourceId
                        : datasourceId(scope, null);
        return engine.decide(scope.access(), effective, name);
    }
}
