package de.palsoftware.yvoke.rag.prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

public class PlaybookMarkdownParser {

    private PlaybookMarkdownParser() {}

    @SuppressWarnings("unchecked")
    public static Playbook parseMarkdown(String mdContent, String fallbackName) {
        if (mdContent == null) {
            mdContent = "";
        }

        String yamlFrontmatter = "";
        String body = mdContent;

        if (mdContent.startsWith("---")) {
            int endIdx = mdContent.indexOf("---", 3);
            if (endIdx > 0) {
                yamlFrontmatter = mdContent.substring(3, endIdx).trim();
                body = mdContent.substring(endIdx + 3).trim();
            }
        }

        String name = fallbackName != null ? fallbackName : "";
        String title = name;
        String description = "";
        String targetAgent = "specialist";
        List<String> tools = new ArrayList<>();
        boolean codeExecution = false;
        boolean prototype = false;

        if (!yamlFrontmatter.isBlank()) {
            try {
                Object loaded = new Yaml().load(yamlFrontmatter);
                if (loaded instanceof Map<?, ?> map) {
                    if (map.get("name") != null) {
                        name = String.valueOf(map.get("name")).trim();
                    }
                    if (map.get("title") != null) {
                        title = String.valueOf(map.get("title")).trim();
                    }
                    if (map.get("description") != null) {
                        description = String.valueOf(map.get("description")).trim();
                    }

                    Object agentObj = map.get("target_agent");
                    if (agentObj == null)
                        agentObj = map.get("targetAgent");
                    if (agentObj == null)
                        agentObj = map.get("role");
                    if (agentObj == null)
                        agentObj = map.get("type");
                    if (agentObj != null) {
                        targetAgent = String.valueOf(agentObj).trim();
                    }

                    Object protoObj = map.get("prototype");
                    if (protoObj instanceof Boolean b) {
                        prototype = b;
                    } else if (protoObj != null) {
                        prototype = Boolean.parseBoolean(String.valueOf(protoObj).trim());
                    }

                    Object toolsObj = map.get("tools");
                    if (toolsObj instanceof List<?> list) {
                        for (Object o : list) {
                            if (o != null)
                                tools.add(String.valueOf(o).trim());
                        }
                    } else if (toolsObj instanceof String str && !str.isBlank()) {
                        for (String t : str.split(",")) {
                            if (!t.isBlank())
                                tools.add(t.trim());
                        }
                    }

                    Object codeExecObj = map.get("code_execution");
                    if (codeExecObj == null)
                        codeExecObj = map.get("codeExecution");
                    if (codeExecObj instanceof Boolean b) {
                        codeExecution = b;
                    } else if (codeExecObj != null) {
                        codeExecution = Boolean.parseBoolean(String.valueOf(codeExecObj));
                    }
                }
            } catch (Exception ignored) {
                // Keep defaults if frontmatter parsing fails
            }
        }

        if (title == null || title.isBlank()) {
            title = name;
        }

        return new Playbook(name, title, description, body, tools, codeExecution, targetAgent,
            prototype, null, null);
    }

    public static String toMarkdown(Playbook playbook) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(playbook.name()).append("\n");
        sb.append("title: ").append(playbook.title()).append("\n");
        if (playbook.description() != null && !playbook.description().isBlank()) {
            sb.append("description: ").append(playbook.description()).append("\n");
        }
        sb.append("target_agent: ")
            .append(playbook.targetAgent() != null ? playbook.targetAgent() : "specialist")
            .append("\n");
        sb.append("prototype: ").append(playbook.prototype()).append("\n");
        if (playbook.tools() != null && !playbook.tools().isEmpty()) {
            sb.append("tools:\n");
            for (String tool : playbook.tools()) {
                sb.append("  - ").append(tool).append("\n");
            }
        } else {
            sb.append("tools: []\n");
        }
        sb.append("code_execution: ").append(playbook.codeExecution()).append("\n");
        sb.append("---\n\n");
        sb.append(playbook.templateText() != null ? playbook.templateText() : "");
        return sb.toString();
    }
}
