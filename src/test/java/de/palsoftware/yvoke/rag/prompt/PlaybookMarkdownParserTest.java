package de.palsoftware.yvoke.rag.prompt;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybookMarkdownParserTest {

    @Test
    void testParseMarkdownWithFullFrontmatter() {
        String md = """
            ---
            name: oim-orchestrator
            title: OIM Orchestrator
            description: Orchestrator playbook description
            target_agent: orchestrator
            tools:
              - call_specialist
              - ask_clarifying_question
            code_execution: true
            ---

            # You are the OIM Orchestrator
            Welcome template text.
            """;

        Playbook pb = PlaybookMarkdownParser.parseMarkdown(md, "default-name");

        assertEquals("oim-orchestrator", pb.name());
        assertEquals("OIM Orchestrator", pb.title());
        assertEquals("Orchestrator playbook description", pb.description());
        assertEquals("orchestrator", pb.targetAgent());
        assertEquals(List.of("call_specialist", "ask_clarifying_question"), pb.tools());
        assertTrue(pb.codeExecution());
        assertTrue(pb.templateText().contains("# You are the OIM Orchestrator"));
    }

    @Test
    void testParseMarkdownWithMinimalFrontmatter() {
        String md = """
            ---
            name: oim-access-governance
            title: OIM Access Governance
            description: Short description
            ---

            # Template text
            """;

        Playbook pb = PlaybookMarkdownParser.parseMarkdown(md, "oim-access-governance");

        assertEquals("oim-access-governance", pb.name());
        assertEquals("OIM Access Governance", pb.title());
        assertEquals("Short description", pb.description());
        assertEquals("specialist", pb.targetAgent());
        assertTrue(pb.tools().isEmpty());
        assertFalse(pb.codeExecution());
        assertEquals("# Template text", pb.templateText().trim());
    }

    @Test
    void testToMarkdownAndReParseRoundtrip() {
        Playbook original = new Playbook("oim-orchestrator-reviewer", "OIM Orchestrator Reviewer",
            "Reviewer playbook for validation", "# Reviewer Prompt\nCheck citations.",
            List.of("verify_citations", "get_section", "submit_review"), false, "reviewer", null,
            null);

        String md = PlaybookMarkdownParser.toMarkdown(original);
        Playbook parsed = PlaybookMarkdownParser.parseMarkdown(md, "oim-orchestrator-reviewer");

        assertEquals(original.name(), parsed.name());
        assertEquals(original.title(), parsed.title());
        assertEquals(original.description(), parsed.description());
        assertEquals(original.targetAgent(), parsed.targetAgent());
        assertEquals(original.tools(), parsed.tools());
        assertEquals(original.codeExecution(), parsed.codeExecution());
        assertEquals(original.templateText(), parsed.templateText());
    }
}
