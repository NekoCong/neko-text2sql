package com.nekocong.text2sql.nl2sql;

/**
 * Observer for queries the permission layer actually ran. The agent path installs one per request so
 * the web layer can surface the enforced SQL and the failure reason without parsing model-facing
 * tool output.
 *
 * <p>Implementations must be thread-safe: the pipeline notifies from a bounded-elastic thread.
 */
public interface ExecutionListener {

    /**
     * Called after a query executed successfully.
     *
     * @param datasourceId the datasource the query ran on
     * @param sql the enforced SQL (after permission rewriting)
     * @param rowCount the number of returned rows
     */
    void onSuccess(String datasourceId, String sql, int rowCount);

    /**
     * Called when a query failed before producing rows (permission, validation or execution).
     *
     * @param datasourceId the datasource the query targeted
     * @param message a user-readable failure reason
     */
    void onFailure(String datasourceId, String message);

    /** @return a listener that ignores everything */
    static ExecutionListener noop() {
        return new ExecutionListener() {
            @Override
            public void onSuccess(String datasourceId, String sql, int rowCount) {
                // nothing to do
            }

            @Override
            public void onFailure(String datasourceId, String message) {
                // nothing to do
            }
        };
    }
}
