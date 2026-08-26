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
            prototype: true
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
        assertTrue(pb.prototype());
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
        assertFalse(pb.prototype());
        assertTrue(pb.tools().isEmpty());
        assertFalse(pb.codeExecution());
        assertEquals("# Template text", pb.templateText().trim());
    }

    /**
     * S5.15. Playbooks are authored by hand and imported from other agent toolchains, so the
     * frontmatter reader deliberately accepts more than the one shape {@code toMarkdown} emits:
     * {@code target_agent} has the aliases {@code targetAgent} / {@code role} / {@code type}, and
     * {@code tools} accepts a comma-separated STRING as well as a YAML list — which is what a human
     * writes and what several other playbook formats use.
     *
     * <p>
     * Neither branch is executed by any existing test: all three cases in this class use the
     * canonical {@code target_agent} key and a YAML list, which is exactly the pair
     * {@code toMarkdown} produces, so the round-trip test can only ever exercise the canonical
     * form. Drop the alias chain or the comma split and the import still SUCCEEDS — it just yields
     * {@code targetAgent = "specialist"} and an EMPTY tool list. A playbook with no tools is not an
     * error anywhere: the run is offered nothing, the model answers from its own weights, and the
     * result reads as a normal (slightly confident, entirely ungrounded) answer. An orchestrator
     * silently demoted to a specialist has the same quality: it just stops delegating.
     *
     * <p>
     * The spacing in the tool string is intentional — a hand-written list has ragged spaces around
     * the commas, and an untrimmed entry would never match a registered tool name.
     */
    @Test
    void aliasedTargetAgentKeysAndACommaSeparatedToolStringParseLikeTheCanonicalForm() {
        String viaRole = """
            ---
            name: oim-orchestrator
            role: orchestrator
            tools: call_specialist, ask_clarifying_question ,verify_citations
            ---

            # Orchestrator body
            """;

        Playbook pb = PlaybookMarkdownParser.parseMarkdown(viaRole, "fallback-name");

        assertEquals("orchestrator", pb.targetAgent());
        assertEquals(List.of("call_specialist", "ask_clarifying_question", "verify_citations"),
            pb.tools());

        String viaCamelCase = """
            ---
            name: oim-reviewer
            targetAgent: reviewer
            ---

            # Reviewer body
            """;
        assertEquals("reviewer",
            PlaybookMarkdownParser.parseMarkdown(viaCamelCase, "fallback-name").targetAgent());

        String viaType = """
            ---
            name: oim-orchestrator
            type: orchestrator
            ---

            # Orchestrator body
            """;
        assertEquals("orchestrator",
            PlaybookMarkdownParser.parseMarkdown(viaType, "fallback-name").targetAgent());
    }

    @Test
    void testToMarkdownAndReParseRoundtrip() {
        Playbook original = new Playbook("oim-orchestrator-reviewer", "OIM Orchestrator Reviewer",
            "Reviewer playbook for validation", "# Reviewer Prompt\nCheck citations.",
            List.of("verify_citations", "get_section", "submit_review"), false, "reviewer", true,
            null, null);

        String md = PlaybookMarkdownParser.toMarkdown(original);
        Playbook parsed = PlaybookMarkdownParser.parseMarkdown(md, "oim-orchestrator-reviewer");

        assertEquals(original.name(), parsed.name());
        assertEquals(original.title(), parsed.title());
        assertEquals(original.description(), parsed.description());
        assertEquals(original.targetAgent(), parsed.targetAgent());
        assertEquals(original.prototype(), parsed.prototype());
        assertEquals(original.tools(), parsed.tools());
        assertEquals(original.codeExecution(), parsed.codeExecution());
        assertEquals(original.templateText(), parsed.templateText());
    }
}
