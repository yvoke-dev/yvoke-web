package de.palsoftware.yvoke.rag.prompt;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertEquals(1, pb.version());
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
        assertEquals(original.version(), parsed.version());
        assertEquals(original.templateText(), parsed.templateText());
    }

    /** The yvoke-exports files carry each playbook's area, so it must survive a round trip. */
    @Test
    void theAreaIsReadFromTheFrontmatterAndWrittenBack() {
        Playbook parsed = PlaybookMarkdownParser.parseMarkdown("""
            ---
            name: pb
            title: PB
            area: PingID
            ---
            Body
            """, null);
        assertEquals("PingID", parsed.area());

        String md = PlaybookMarkdownParser.toMarkdown(parsed);
        assertTrue(md.contains("area: PingID"), md);
        assertEquals("PingID", PlaybookMarkdownParser.parseMarkdown(md, null).area());
    }

    @Test
    void aFileWithoutAnAreaParsesWithNone() {
        Playbook parsed = PlaybookMarkdownParser.parseMarkdown("""
            ---
            name: pb
            title: PB
            ---
            Body
            """, null);
        assertNull(parsed.area());
    }

    @Test
    void theSystemPromptIsReadFromTheFrontmatterAndWrittenBack() {
        Playbook parsed = PlaybookMarkdownParser.parseMarkdown("""
            ---
            name: pb
            title: PB
            system_prompt: coding-chat
            ---
            Body
            """, null);
        assertEquals("coding-chat", parsed.systemPrompt());

        String md = PlaybookMarkdownParser.toMarkdown(parsed);
        assertTrue(md.contains("system_prompt: coding-chat"), md);
        assertEquals("coding-chat", PlaybookMarkdownParser.parseMarkdown(md, null).systemPrompt());
    }

    @Test
    void aliasedSystemPromptCamelCaseIsSupported() {
        Playbook parsed = PlaybookMarkdownParser.parseMarkdown("""
            ---
            name: pb
            title: PB
            systemPrompt: special-chat
            ---
            Body
            """, null);
        assertEquals("special-chat", parsed.systemPrompt());
    }

    @Test
    void aFileWithoutASystemPromptParsesWithNull() {
        Playbook parsed = PlaybookMarkdownParser.parseMarkdown("""
            ---
            name: pb
            title: PB
            ---
            Body
            """, null);
        assertNull(parsed.systemPrompt());
    }

    @Test
    void parseMarkdownRejectsSystemPromptForOrchestratorAndReviewer() {
        assertThrows(IllegalArgumentException.class, () -> PlaybookMarkdownParser.parseMarkdown("""
            ---
            name: oim-orchestrator
            target_agent: orchestrator
            system_prompt: prompt-ignored
            ---
            Body
            """, "fallback"));

        assertThrows(IllegalArgumentException.class, () -> PlaybookMarkdownParser.parseMarkdown("""
            ---
            name: oim-reviewer
            target_agent: reviewer
            system_prompt: prompt-ignored
            ---
            Body
            """, "fallback"));
    }

    @Test
    void toMarkdownExcludesSystemPromptWhenTargetAgentIsNotSpecialist() {
        Playbook orch = new Playbook("oim-orchestrator", "OIM Orch", "Desc", "Body", List.of(),
            false, "orchestrator", false, null, null, false, "OIM", "prompt-ignored");
        assertNull(orch.systemPrompt());
        String md = PlaybookMarkdownParser.toMarkdown(orch);
        assertFalse(md.contains("system_prompt:"));

        Playbook rev = new Playbook("oim-reviewer", "OIM Rev", "Desc", "Body", List.of(), false,
            "reviewer", false, null, null, false, "OIM", "prompt-ignored");
        assertNull(rev.systemPrompt());
        assertFalse(PlaybookMarkdownParser.toMarkdown(rev).contains("system_prompt:"));
    }

    @Test
    void versionDefaultsToOneWhenOmittedAndRoundTripsWhenSet() {
        Playbook parsed = PlaybookMarkdownParser.parseMarkdown("""
            ---
            name: oim-ask
            title: OIM Ask
            description: Ask OIM
            version: 2
            ---
            Body
            """, "oim-ask");
        assertEquals(2, parsed.version());

        String md = PlaybookMarkdownParser.toMarkdown(parsed);
        assertTrue(md.contains("description: Ask OIM\nversion: 2\ntarget_agent: specialist"), md);
        assertEquals(2, PlaybookMarkdownParser.parseMarkdown(md, "oim-ask").version());
    }

    @Test
    void parseMarkdownRejectsInvalidVersionValuesEvenThoughFrontmatterLoadUsesTryCatch() {
        for (String bad : List.of("0", "-1", "abc", "1.5", "true", "false", "null")) {
            String md = """
                ---
                name: pb
                title: PB
                version: %s
                ---
                Body
                """.formatted(bad);
            assertThrows(IllegalArgumentException.class,
                () -> PlaybookMarkdownParser.parseMarkdown(md, "pb"),
                "Expected IllegalArgumentException for version: " + bad);
        }
    }
}

