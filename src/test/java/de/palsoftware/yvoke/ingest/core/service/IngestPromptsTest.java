package de.palsoftware.yvoke.ingest.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.rag.prompt.SystemPrompt;
import de.palsoftware.yvoke.rag.prompt.SystemPromptService;
import de.palsoftware.yvoke.rag.prompt.SystemPromptType;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IngestPromptsTest {

    private static final String NAME = "oim-summarize";

    private IngestPrompts resolving(String body) {
        SystemPromptService prompts = mock(SystemPromptService.class);
        when(prompts.requirePrompt(eq(NAME), eq(SystemPromptType.SUMMARIZE)))
            .thenReturn(new SystemPrompt(NAME, SystemPromptType.SUMMARIZE, body, ""));
        return new IngestPrompts(prompts);
    }

    @Test
    void readsTheNameFromJobSettings() {
        assertThat(IngestPrompts.summarizePromptName(
            Map.of(IngestPrompts.SETTING_SUMMARIZE_PROMPT, "  " + NAME + "  "))).isEqualTo(NAME);
    }

    @Test
    void treatsAbsentNullAndBlankAlike() {
        assertThat(IngestPrompts.summarizePromptName(null)).isNull();
        assertThat(IngestPrompts.summarizePromptName(Map.of())).isNull();
        assertThat(IngestPrompts
            .summarizePromptName(Map.of(IngestPrompts.SETTING_SUMMARIZE_PROMPT, "   "))).isNull();
    }

    @Test
    void ignoresANonStringValue() {
        // settings is a free-form jsonb bag; a number here must not become the name "42".
        Map<String, Object> settings = new HashMap<>();
        settings.put(IngestPrompts.SETTING_SUMMARIZE_PROMPT, 42);
        assertThat(IngestPrompts.summarizePromptName(settings)).isNull();
    }

    @Test
    void returnsThePromptBodyWhenNamed() {
        assertThat(resolving("Write a summary.").requireSummarizePromptText(
            Map.of(IngestPrompts.SETTING_SUMMARIZE_PROMPT, NAME), "section summaries"))
            .isEqualTo("Write a summary.");
    }

    @Test
    void refusesWhenTheJobNamesNoPrompt() {
        // The old behaviour was to return null here and summarize with no system prompt at all,
        // which is what produced "Here is a summary of the section:".
        assertThatThrownBy(() -> resolving("x").requireSummarizePromptText(Map.of(), "summaries"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(IngestPrompts.SETTING_SUMMARIZE_PROMPT)
            .hasMessageContaining("summaries");
    }

    @Test
    void refusesAnEmptyRegisteredPrompt() {
        assertThatThrownBy(() -> resolving("   ").requireSummarizePromptText(
            Map.of(IngestPrompts.SETTING_SUMMARIZE_PROMPT, NAME), "summaries"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("empty");
    }

    @Test
    void readsTheKgNameFromJobSettings() {
        assertThat(IngestPrompts.kgPromptName(Map.of(IngestPrompts.SETTING_KG_PROMPT, " oim-kg ")))
            .isEqualTo("oim-kg");
        assertThat(IngestPrompts.kgPromptName(Map.of())).isNull();
    }

    @Test
    void refusesKgExtractionWhenTheJobNamesNoPrompt() {
        // The old behaviour was worse than the summarize one: the lookup missed and fell back to
        // the EMPTY STRING, so extraction ran with no schema instruction and its parse failures
        // were counted as chunks containing nothing.
        SystemPromptService prompts = mock(SystemPromptService.class);
        assertThatThrownBy(
            () -> new IngestPrompts(prompts).requireKgPromptText(Map.of(), "a knowledge graph"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(IngestPrompts.SETTING_KG_PROMPT);
    }

    @Test
    void resolvesTheKgPromptAgainstTheKgType() {
        // Type matters: prompts share one namespace, so a SUMMARIZE prompt selected here would
        // otherwise "resolve" and ask the extractor for prose instead of JSON.
        SystemPromptService prompts = mock(SystemPromptService.class);
        when(prompts.requirePrompt(eq("oim-kg"), eq(SystemPromptType.KG)))
            .thenReturn(new SystemPrompt("oim-kg", SystemPromptType.KG, "STRICT JSON.", ""));
        assertThat(new IngestPrompts(prompts).requireKgPromptText(
            Map.of(IngestPrompts.SETTING_KG_PROMPT, "oim-kg"), "a knowledge graph"))
            .isEqualTo("STRICT JSON.");
    }

    @Test
    void namesTheWorkInTheFailureSoAFailedJobSaysWhichStepCouldNotStart() {
        assertThatThrownBy(
            () -> resolving("x").requireSummarizePromptText(null, "section summaries for guide.md"))
            .hasMessageContaining("section summaries for guide.md");
    }
}
