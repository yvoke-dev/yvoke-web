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
    @Nullable List<ToolCallback> extraTools,
    /**
     * A complete message list to continue from, verbatim — system prompt, tool calls, tool results
     * and all. Unlike {@code history}, which is flattened to user/assistant content strings and so
     * cannot carry a tool-calling conversation, this crosses the call boundary intact. When set it
     * REPLACES the seed that {@code history} and {@code systemPromptOverride} would have built, and
     * {@code query} is appended to it as the next user turn.
     */
    @Nullable List<LlmMessage> priorMessages) {

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
    private List<LlmMessage> priorMessages;

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

    public Builder priorMessages(List<LlmMessage> priorMessages) {
      this.priorMessages = priorMessages;
      return this;
    }

    public AgenticRequest build() {
      return new AgenticRequest(query, modelOverride, history, systemPromptOverride, allowedTools,
          thinkingLevel, codeExecution, extraTools, priorMessages);
    }
  }}
