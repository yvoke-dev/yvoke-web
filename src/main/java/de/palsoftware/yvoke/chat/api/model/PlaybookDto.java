package de.palsoftware.yvoke.chat.api.model;

import de.palsoftware.yvoke.rag.prompt.Playbook;
import java.util.List;

public record PlaybookDto(String name, String title, String description, List<String> tools,
    boolean codeExecution, String targetAgent, boolean prototype, String area, String systemPrompt,
    int version) {

    public PlaybookDto(String name, String title, String description, List<String> tools,
        boolean codeExecution, String targetAgent, boolean prototype, String area,
        String systemPrompt) {
        this(name, title, description, tools, codeExecution, targetAgent, prototype, area,
            systemPrompt, 1);
    }

    public PlaybookDto(String name, String title, String description, List<String> tools,
        boolean codeExecution, String targetAgent, boolean prototype, String area) {
        this(name, title, description, tools, codeExecution, targetAgent, prototype, area, null, 1);
    }

    public static PlaybookDto from(Playbook playbook) {
        String agent = playbook.targetAgent() != null ? playbook.targetAgent() : "specialist";
        return new PlaybookDto(playbook.name(), playbook.title(), playbook.description(),
            playbook.tools() != null ? playbook.tools() : List.of(), playbook.codeExecution(),
            agent, playbook.prototype(), playbook.area(), playbook.systemPrompt(),
            playbook.version());
    }
}
