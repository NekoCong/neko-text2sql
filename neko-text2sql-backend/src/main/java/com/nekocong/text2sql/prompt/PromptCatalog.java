package com.nekocong.text2sql.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Loads and renders LLM prompt templates from the classpath {@code prompts/} directory.
 *
 * <p>Prompts live in dedicated files (see {@code resources/prompts/}) rather than inline in
 * configuration or code. Templates use {@code {{name}}} placeholders substituted by
 * {@link #render(String, Map)}. Loaded templates are cached in memory.
 */
@Component
public class PromptCatalog {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /**
     * Renders a prompt template.
     *
     * @param location the classpath location (e.g. {@code prompts/nl2sql-user.md})
     * @param variables the placeholder values
     * @return the rendered prompt
     */
    public String render(String location, Map<String, String> variables) {
        String template = cache.computeIfAbsent(location, PromptCatalog::read);
        String rendered = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}", entry.getValue() == null ? "" : entry.getValue());
        }
        return rendered;
    }

    /**
     * Renders a prompt template with no variables.
     *
     * @param location the classpath location
     * @return the rendered prompt
     */
    public String render(String location) {
        return render(location, Map.of());
    }

    private static String read(String location) {
        try (InputStream input = new ClassPathResource(location).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Prompt not found on classpath: " + location, ex);
        }
    }
}
