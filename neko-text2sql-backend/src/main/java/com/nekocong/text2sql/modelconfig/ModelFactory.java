package com.nekocong.text2sql.modelconfig;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Builds an AgentScope {@link Model} from a user's {@link ModelConfig}.
 *
 * <p>Supports {@code dashscope} and OpenAI-compatible providers ({@code openai}, {@code deepseek},
 * or any custom {@code baseUrl} such as Kimi/GLM/vLLM). Per-user API keys are applied explicitly, so
 * the model does not depend on environment variables.
 */
@Component
public class ModelFactory {

    /**
     * Builds the model.
     *
     * @param config the user's model configuration
     * @return the AgentScope model
     */
    public Model build(ModelConfig config) {
        String provider = config.provider() == null ? "dashscope" : config.provider().toLowerCase(Locale.ROOT);
        if (provider.startsWith("openai") || provider.equals("deepseek") || provider.equals("compatible")) {
            OpenAIChatModel.Builder builder = OpenAIChatModel.builder().modelName(config.model());
            apply(builder, config);
            return builder.build();
        }
        DashScopeChatModel.Builder builder = DashScopeChatModel.builder().modelName(config.model());
        apply(builder, config);
        return builder.build();
    }

    private static void apply(OpenAIChatModel.Builder builder, ModelConfig config) {
        if (StringUtils.hasText(config.apiKey())) {
            builder.apiKey(config.apiKey());
        }
        if (StringUtils.hasText(config.baseUrl())) {
            builder.baseUrl(config.baseUrl());
        }
    }

    private static void apply(DashScopeChatModel.Builder builder, ModelConfig config) {
        if (StringUtils.hasText(config.apiKey())) {
            builder.apiKey(config.apiKey());
        }
        if (StringUtils.hasText(config.baseUrl())) {
            builder.baseUrl(config.baseUrl());
        }
    }
}
