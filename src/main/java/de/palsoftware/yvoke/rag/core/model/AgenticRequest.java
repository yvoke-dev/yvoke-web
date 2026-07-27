package de.palsoftware.yvoke.rag.core.model;

import de.palsoftware.yvoke.llm.core.model.LlmMessage;
import jakarta.annotation.Nullable;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;

public record AgenticRequest(
    String query,
    @Nullable String modelOverride,
    @Nullable List<LlmMessage> history,
    @Nullable String systemPromptOverride,
    @Nullable List<String> allowedTools,
    @Nullable String thinkingLevel,
    boolean codeExecution,
    @Nullable List<ToolCallback> extraTools) {

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String query;
    private String modelOverride;
    private List<LlmMessage> history;
    private String systemPromptOverride;
    private List<String> allowedTools;
    private String thinkingLevel;
    private boolean codeExecution;
    private List<ToolCallback> extraTools;

    public Builder query(String query) {
      this.query = query;
      return this;
    }

    public Builder modelOverride(String modelOverride) {
      this.modelOverride = modelOverride;
      return this;
    }

    public Builder history(List<LlmMessage> history) {
      this.history = history;
      return this;
    }

    public Builder systemPromptOverride(String systemPromptOverride) {
      this.systemPromptOverride = systemPromptOverride;
      return this;
    }

    public Builder allowedTools(List<String> allowedTools) {
      this.allowedTools = allowedTools;
      return this;
    }

    public Builder thinkingLevel(String thinkingLevel) {
      this.thinkingLevel = thinkingLevel;
      return this;
    }

    public Builder codeExecution(boolean codeExecution) {
      this.codeExecution = codeExecution;
      return this;
    }

    public Builder extraTools(List<ToolCallback> extraTools) {
      this.extraTools = extraTools;
      return this;
    }

    public AgenticRequest build() {
      return new AgenticRequest(query, modelOverride, history, systemPromptOverride, allowedTools,
          thinkingLevel, codeExecution, extraTools);
    }
  }}
