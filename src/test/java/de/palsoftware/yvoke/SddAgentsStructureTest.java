package de.palsoftware.yvoke;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Validates that all SDD subagent definition templates in {@code .antigravity/agents/} exist and
 * conform to the required subagent schema (name, description, tool parameters, and non-empty system
 * prompt).
 */
class SddAgentsStructureTest {

    private static final Path AGENTS_DIR = Path.of(".antigravity/agents");

    private static final List<String> REQUIRED_AGENTS =
        List.of("sdd_planner.md", "sdd_plan_critic.md", "sdd_task_architect.md",
            "sdd_task_critic.md", "spring_implementer.md", "spring_reviewer.md", "sdd_auditor.md");

    private static final Pattern NAME_PATTERN =
        Pattern.compile("^- \\*\\*Name\\*\\*:\\s*`?([a-z0-9_-]+)`?", Pattern.MULTILINE);
    private static final Pattern DESC_PATTERN =
        Pattern.compile("^- \\*\\*Description\\*\\*:\\s*(.+)$", Pattern.MULTILINE);
    private static final Pattern SYSTEM_PROMPT_PATTERN =
        Pattern.compile("## System Prompt\\s*```(?:markdown)?\\s*(.+?)\\s*```", Pattern.DOTALL);

    @Test
    void allRequiredSddSubagentTemplatesExist() {
        assertThat(AGENTS_DIR).as(".antigravity/agents directory must exist").isDirectory();

        for (String agentFile : REQUIRED_AGENTS) {
            Path path = AGENTS_DIR.resolve(agentFile);
            assertThat(path)
                .as("subagent template '%s' must exist in .antigravity/agents/", agentFile)
                .exists();
        }
    }

    @Test
    void everySubagentTemplateConformsToSchema() throws IOException {
        for (String agentFile : REQUIRED_AGENTS) {
            Path path = AGENTS_DIR.resolve(agentFile);
            String content = Files.readString(path, StandardCharsets.UTF_8);

            Matcher nameMatcher = NAME_PATTERN.matcher(content);
            assertThat(nameMatcher.find())
                .as("%s must declare a valid '**Name**:' field in Role Definition", agentFile)
                .isTrue();

            String expectedBaseName = agentFile.replace(".md", "");
            assertThat(nameMatcher.group(1))
                .as("declared name in %s must match its filename", agentFile)
                .isEqualTo(expectedBaseName);

            Matcher descMatcher = DESC_PATTERN.matcher(content);
            assertThat(descMatcher.find())
                .as("%s must declare a '**Description**:' field in Role Definition", agentFile)
                .isTrue();
            assertThat(descMatcher.group(1).strip())
                .as("description in %s must not be empty", agentFile).isNotEmpty();

            assertThat(content).as("%s must declare tool parameters section", agentFile)
                .contains("## Subagent Definition Tool Parameters").contains("enable_write_tools")
                .contains("enable_mcp_tools").contains("enable_subagent_tools");

            Matcher promptMatcher = SYSTEM_PROMPT_PATTERN.matcher(content);
            assertThat(promptMatcher.find())
                .as("%s must contain a fenced '## System Prompt' block", agentFile).isTrue();
            assertThat(promptMatcher.group(1).strip())
                .as("system prompt in %s must be substantive (>50 characters)", agentFile)
                .hasSizeGreaterThan(50);
        }
    }
}
