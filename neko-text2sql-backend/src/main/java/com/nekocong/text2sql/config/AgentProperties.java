package com.nekocong.text2sql.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the AgentScope text-to-SQL agent, bound from the {@code text2sql.agent.*}
 * configuration prefix.
 */
@ConfigurationProperties(prefix = "text2sql.agent")
public class AgentProperties {

    /** Logical agent name. */
    private String name = "Text2SqlAgent";

    /**
     * Model identifier resolved by AgentScope's {@code ModelRegistry}, e.g. {@code
     * dashscope:qwen-plus}. The matching API-key environment variable (for example {@code
     * DASHSCOPE_API_KEY}) is read automatically.
     */
    private String model = "dashscope:qwen-plus";

    /** Optional inline system prompt; when blank, {@link #sysPromptFile} is loaded instead. */
    private String sysPrompt = "";

    /** Classpath location of the agent system prompt (its persona), under {@code resources/prompts/}. */
    private String sysPromptFile = "prompts/agent-system.md";

    /** AgentScope workspace directory (persona, knowledge base, subagents, skills). */
    private String workspace = "./agentscope/workspace";

    /** Upper bound on the agent context window, in tokens. */
    private int maxContextTokens = 8000;

    /**
     * Whether to register the multi-agent team (schema_explorer / sql_runner / report_writer) as
     * AgentScope subagents the parent agent may delegate to.
     */
    private boolean subagentsEnabled = true;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getSysPrompt() {
        return sysPrompt;
    }

    public void setSysPrompt(String sysPrompt) {
        this.sysPrompt = sysPrompt;
    }

    public String getSysPromptFile() {
        return sysPromptFile;
    }

    public void setSysPromptFile(String sysPromptFile) {
        this.sysPromptFile = sysPromptFile;
    }

    public String getWorkspace() {
        return workspace;
    }

    public void setWorkspace(String workspace) {
        this.workspace = workspace;
    }

    public int getMaxContextTokens() {
        return maxContextTokens;
    }

    public void setMaxContextTokens(int maxContextTokens) {
        this.maxContextTokens = maxContextTokens;
    }

    public boolean isSubagentsEnabled() {
        return subagentsEnabled;
    }

    public void setSubagentsEnabled(boolean subagentsEnabled) {
        this.subagentsEnabled = subagentsEnabled;
    }
}
