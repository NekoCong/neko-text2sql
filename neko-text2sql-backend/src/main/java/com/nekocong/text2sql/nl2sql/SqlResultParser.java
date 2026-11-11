package com.nekocong.text2sql.nl2sql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Tolerantly extracts a {@link SqlGeneration} from raw model output.
 *
 * <p>Preferred shape is the strict JSON object the prompt asks for. Because models occasionally
 * wrap it in prose or a fenced block, progressively looser fallbacks (fenced ```sql block, then the
 * whole text treated as SQL) are applied — the SQL safety validator is the final gate regardless.
 */
@Component
public class SqlResultParser {

    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*}");
    private static final Pattern SQL_FENCE = Pattern.compile("```(?:sql)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper objectMapper;

    /**
     * Creates the parser.
     *
     * @param objectMapper the shared Jackson mapper
     */
    public SqlResultParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parses model output into a SQL generation.
     *
     * @param raw the raw model text
     * @return the parsed generation
     * @throws SqlGenerationException when no SQL could be extracted
     */
    public SqlGeneration parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new SqlGenerationException("Model returned an empty response");
        }
        SqlGeneration fromJson = tryParseJson(raw);
        if (fromJson != null) {
            return fromJson;
        }
        Matcher fence = SQL_FENCE.matcher(raw);
        if (fence.find()) {
            String sql = fence.group(1).trim();
            if (!sql.isBlank()) {
                return new SqlGeneration(sql, stripFences(raw));
            }
        }
        String trimmed = raw.trim();
        if (looksLikeSql(trimmed)) {
            return new SqlGeneration(trimmed, null);
        }
        throw new SqlGenerationException("Could not extract a SQL statement from the model response");
    }

    private SqlGeneration tryParseJson(String raw) {
        Matcher matcher = JSON_OBJECT.matcher(raw);
        if (!matcher.find()) {
            return null;
        }
        // Greedy match spans the first '{' to the last '}'; if that is not valid JSON, try the
        // shortest object instead.
        for (String candidate : new String[] {matcher.group(), shortestObject(raw)}) {
            if (candidate == null) {
                continue;
            }
            try {
                JsonNode node = objectMapper.readTree(candidate);
                String sql = firstNonBlank(
                        text(node, "sql"), text(node, "query"), text(node, "SQL"));
                if (sql != null) {
                    String explanation = firstNonBlank(
                            text(node, "explanation"), text(node, "explain"), text(node, "reason"));
                    return new SqlGeneration(sql, explanation);
                }
            } catch (Exception ignored) {
                // fall through to the next candidate
            }
        }
        return null;
    }

    private static String shortestObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return raw.substring(start, end + 1);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return (text == null || text.isBlank()) ? null : text.trim();
    }

    private static String stripFences(String raw) {
        String stripped = raw.replaceAll("```(?:sql)?", "").replace("```", "").trim();
        return stripped.isBlank() ? null : stripped;
    }

    private static boolean looksLikeSql(String text) {
        String upper = text.toUpperCase();
        return upper.startsWith("SELECT") || upper.startsWith("WITH");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
