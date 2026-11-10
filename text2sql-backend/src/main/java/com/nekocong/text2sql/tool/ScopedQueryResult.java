package com.nekocong.text2sql.tool;

import com.nekocong.text2sql.nl2sql.Text2SqlResult;
import com.nekocong.text2sql.security.DataScope;
import com.nekocong.text2sql.security.DataScopeDescriber;

/**
 * The agent-facing envelope of one {@code query_database} call: the pipeline result plus the
 * machine-readable range metadata for <em>this</em> result.
 *
 * <p>Kept separate from {@link Text2SqlResult} so the non-agent HTTP response keeps its shape. The
 * model reads the SQL and rows from {@link #result()} and the range from {@link #dataScope()}, which
 * is what it must cite instead of inferring "the data does not exist" from an empty result.
 *
 * @param result the pipeline outcome (SQL, rows, chart)
 * @param dataScope the caller's range for this result
 */
public record ScopedQueryResult(Text2SqlResult result, ScopeMetadata dataScope) {

    /**
     * Builds the envelope for a result produced under the given scope.
     *
     * @param result the pipeline outcome
     * @param scope the caller's data scope (may be {@code null}, which is reported as restricted)
     * @param describer the shared range describer
     * @return the envelope
     */
    public static ScopedQueryResult of(
            Text2SqlResult result, DataScope scope, DataScopeDescriber describer) {
        return new ScopedQueryResult(result, ScopeMetadata.of(scope, describer));
    }

    /**
     * The range metadata attached to a tool result.
     *
     * @param restricted whether the result was filtered to the caller's range
     * @param role the caller's role code, or {@code null} when unknown
     * @param summary the short human-readable range description
     */
    public record ScopeMetadata(boolean restricted, String role, String summary) {

        /**
         * Projects the metadata from the scope.
         *
         * @param scope the caller's data scope (may be {@code null})
         * @param describer the shared range describer
         * @return the metadata
         */
        public static ScopeMetadata of(DataScope scope, DataScopeDescriber describer) {
            return new ScopeMetadata(
                    describer.restricted(scope), scope == null ? null : scope.role(), describer.summary(scope));
        }
    }
}
