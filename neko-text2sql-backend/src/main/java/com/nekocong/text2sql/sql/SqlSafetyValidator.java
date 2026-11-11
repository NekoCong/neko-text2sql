package com.nekocong.text2sql.sql;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Enforces that an LLM-generated statement is safe to run: a single, read-only query.
 *
 * <p>Defence in depth (never rely on one layer):
 * <ul>
 *   <li>exactly one statement (no unquoted {@code ;} other than a single trailing terminator),</li>
 *   <li>must start with {@code SELECT} or {@code WITH},</li>
 *   <li>no destructive / DDL / file / procedure keywords anywhere (identifier scan),</li>
 *   <li>combined at execution time with a read-only connection, a row cap and a timeout.</li>
 * </ul>
 *
 * <p>A hand-rolled validator is used deliberately to keep the dependency surface small; it is
 * conservative and errs on the side of rejection.
 */
@Component
public class SqlSafetyValidator {

    /** Keywords that must never appear in an executable statement. */
    private static final Set<String> FORBIDDEN_KEYWORDS =
            Set.of(
                    "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "TRUNCATE", "CREATE", "REPLACE",
                    "MERGE", "UPSERT", "GRANT", "REVOKE", "CALL", "EXEC", "EXECUTE", "RENAME",
                    "ATTACH", "DETACH", "PRAGMA", "VACUUM", "INTO", "LOAD", "IMPORT", "COPY",
                    "COMMENT", "SHUTDOWN", "KILL", "RECONFIGURE", "DENY", "SET");

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * Validates and normalises a generated statement.
     *
     * @param sql the raw SQL (may include a trailing terminator / leading comment)
     * @return the normalised statement ready for execution
     * @throws SqlSafetyException when the statement is not a single read-only query
     */
    public String validateAndNormalize(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new SqlSafetyException("Empty SQL statement");
        }
        String stripped = stripLeadingComments(sql).trim();
        if (stripped.isEmpty()) {
            throw new SqlSafetyException("SQL statement contained only comments");
        }

        String withoutTrailing = stripTrailingTerminator(stripped);
        if (containsUnquotedSemicolon(withoutTrailing)) {
            throw new SqlSafetyException("Multiple SQL statements are not allowed");
        }

        String upper = withoutTrailing.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")) {
            throw new SqlSafetyException(
                    "Only read-only SELECT / WITH queries are allowed");
        }

        Matcher matcher = IDENTIFIER.matcher(withoutTrailing);
        while (matcher.find()) {
            String token = matcher.group().toUpperCase(Locale.ROOT);
            if (FORBIDDEN_KEYWORDS.contains(token)) {
                throw new SqlSafetyException("Forbidden keyword in statement: " + token);
            }
        }
        return withoutTrailing;
    }

    private static String stripLeadingComments(String sql) {
        String current = sql.stripLeading();
        String previous;
        do {
            previous = current;
            if (current.startsWith("--")) {
                int newline = current.indexOf('\n');
                current = (newline < 0) ? "" : current.substring(newline + 1).stripLeading();
            } else if (current.startsWith("/*")) {
                int end = current.indexOf("*/");
                current = (end < 0) ? "" : current.substring(end + 2).stripLeading();
            }
        } while (!current.equals(previous));
        return current;
    }

    private static String stripTrailingTerminator(String sql) {
        String trimmed = sql.stripTrailing();
        if (trimmed.endsWith(";")) {
            return trimmed.substring(0, trimmed.length() - 1).stripTrailing();
        }
        return trimmed;
    }

    private static boolean containsUnquotedSemicolon(String sql) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (c == ';' && !inSingleQuote && !inDoubleQuote) {
                return true;
            }
        }
        return false;
    }
}
