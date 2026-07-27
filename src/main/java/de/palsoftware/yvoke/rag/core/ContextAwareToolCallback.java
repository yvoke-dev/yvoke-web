package de.palsoftware.yvoke.rag.core;

import de.palsoftware.yvoke.rag.core.model.AgenticChatContext;


import org.springframework.ai.tool.ToolCallback;

public interface ContextAwareToolCallback extends ToolCallback {
    String callWithContext(String jsonArguments, AgenticChatContext context);
}
