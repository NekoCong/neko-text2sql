package com.nekocong.text2sql.nl2sql;

/**
 * A SQL statement generated for a question, plus the model's short natural-language explanation.
 *
 * @param sql the generated SQL (not yet validated)
 * @param explanation a short explanation of the query, or {@code null}
 */
public record SqlGeneration(String sql, String explanation) {}
