package de.palsoftware.yvoke.ingest.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.ingest.core.model.IngestJobKind;
import de.palsoftware.yvoke.rag.prompt.SystemPrompt;
import de.palsoftware.yvoke.rag.prompt.SystemPromptService;
import de.palsoftware.yvoke.rag.prompt.SystemPromptType;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the kind -> needs-a-summarize-prompt matrix.
 *
 * <p>
 * This mapping was tribal knowledge, and getting it wrong is invisible: a summarizing kind nobody
 * validated ingested a whole collection with no prompt and reported success. The matrix is asserted
 * here so adding a kind that summarizes without listing it fails a test rather than a corpus.
 */
class IngestPromptEnqueueValidatorTest {

    private static final String PROMPT = "oim-summarize";

    private IngestPromptEnqueueValidator validatorWithPrompt() {
        SystemPromptService prompts = mock(SystemPromptService.class);
        when(prompts.requirePrompt(any(), any())).thenReturn(
            new SystemPrompt(PROMPT, SystemPromptType.SUMMARIZE, "Write a summary.", ""));
        return new IngestPromptEnqueueValidator(prompts);
    }

    private static EnqueueRequest req(String kind, Map<String, Object> settings) {
        return new EnqueueRequest(kind, "src.md", "9.3.1", "OIM", settings);
    }

    // ---- the matrix -----------------------------------------------------

    @Test
    void hierarchicalAlwaysNeedsAPrompt() {
        // Unlike `standard`, processSingleHierarchicalFile has no buildSectionSummaries gate.
        assertThat(IngestPromptEnqueueValidator.willSummarize(IngestJobKind.HIERARCHICAL.getValue(),
            Map.of())).isTrue();
    }

    @Test
    void confluencePageImportNeedsAPromptOnlyWhenTheInstanceEnabledSummaries() {
        // The flag is per-instance connector config, snapshotted into the job at crawl time. An
        // instance with summaries off must not have its imports refused for lacking a prompt --
        // which is what happened when this kind was in ALWAYS_SUMMARIZES.
        assertThat(IngestPromptEnqueueValidator
            .willSummarize(IngestJobKind.CONFLUENCE_PAGE_IMPORT.getValue(), Map.of())).isFalse();
        assertThat(IngestPromptEnqueueValidator.willSummarize(
            IngestJobKind.CONFLUENCE_PAGE_IMPORT.getValue(),
            Map.of(DocumentIngestService.SETTING_BUILD_SECTION_SUMMARIES, false))).isFalse();
        assertThat(IngestPromptEnqueueValidator.willSummarize(
            IngestJobKind.CONFLUENCE_PAGE_IMPORT.getValue(),
            Map.of(DocumentIngestService.SETTING_BUILD_SECTION_SUMMARIES, true))).isTrue();
    }

    @Test
    void aConfluenceJobWithSummariesOffIsAcceptedWithNoPrompt() {
        assertThatCode(() -> validatorWithPrompt()
            .validate(req(IngestJobKind.CONFLUENCE_PAGE_IMPORT.getValue() + ":acme", Map.of())))
            .doesNotThrowAnyException();
    }

    @Test
    void customAlwaysNeedsAPrompt() {
        assertThat(
            IngestPromptEnqueueValidator.willSummarize(IngestJobKind.CUSTOM.getValue(), Map.of()))
            .isTrue();
    }

    @Test
    void standardNeedsAPromptOnlyWhenSummariesWereRequested() {
        assertThat(
            IngestPromptEnqueueValidator.willSummarize(IngestJobKind.STANDARD.getValue(), Map.of()))
            .isFalse();
        assertThat(IngestPromptEnqueueValidator.willSummarize(IngestJobKind.STANDARD.getValue(),
            Map.of(DocumentIngestService.SETTING_BUILD_SECTION_SUMMARIES, true))).isTrue();
    }

    @Test
    void kindsThatDoNotSummarizeAreNeverAsked() {
        for (String kind : new String[] {IngestJobKind.JSON_IMPORT.getValue(),
            IngestJobKind.KG_EXTRACT.getValue(), IngestJobKind.CONFLUENCE_IMPORT.getValue()}) {
            // kg-extract summarizes nothing (it needs a KG prompt instead — see the KG tests).
            assertThat(IngestPromptEnqueueValidator.willSummarize(kind,
                Map.of(DocumentIngestService.SETTING_BUILD_SECTION_SUMMARIES, true)))
                .as("kind %s", kind).isFalse();
        }
    }

    @Test
    void theCrawlItselfIsExemptBecauseItsPageJobsAreValidatedIndividually() {
        assertThatCode(() -> validatorWithPrompt()
            .validate(req(IngestJobKind.CONFLUENCE_IMPORT.getValue(), Map.of())))
            .doesNotThrowAnyException();
    }

    // ---- the slug suffix ------------------------------------------------

    @Test
    void confluenceKindsAreMatchedWithoutTheirInstanceSlug() {
        // JobService routes on split(":")[0]; matching the full string here would exempt every
        // real Confluence job (they all carry ":<slug>") from validation entirely.
        assertThat(IngestPromptEnqueueValidator.willSummarize(
            IngestJobKind.CONFLUENCE_PAGE_IMPORT.getValue() + ":acme",
            Map.of(DocumentIngestService.SETTING_BUILD_SECTION_SUMMARIES, true))).isTrue();
    }

    // ---- enforcement ----------------------------------------------------

    @Test
    void rejectsASummarizingJobThatNamesNoPrompt() {
        assertThatThrownBy(() -> validatorWithPrompt()
            .validate(req(IngestJobKind.HIERARCHICAL.getValue(), Map.of())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(IngestPrompts.SETTING_SUMMARIZE_PROMPT);
    }

    @Test
    void rejectsABlankPromptName() {
        assertThatThrownBy(() -> validatorWithPrompt().validate(req(IngestJobKind.CUSTOM.getValue(),
            Map.of(IngestPrompts.SETTING_SUMMARIZE_PROMPT, "   "))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsASummarizingJobThatNamesAResolvablePrompt() {
        assertThatCode(
            () -> validatorWithPrompt().validate(req(IngestJobKind.HIERARCHICAL.getValue(),
                Map.of(IngestPrompts.SETTING_SUMMARIZE_PROMPT, PROMPT))))
            .doesNotThrowAnyException();
    }

    @Test
    void propagatesTheResolutionFailureForAnUnknownPrompt() {
        SystemPromptService prompts = mock(SystemPromptService.class);
        when(prompts.requirePrompt(any(), any()))
            .thenThrow(new IllegalArgumentException("System prompt 'nope' does not exist."));
        assertThatThrownBy(() -> new IngestPromptEnqueueValidator(prompts)
            .validate(req(IngestJobKind.CUSTOM.getValue(),
                Map.of(IngestPrompts.SETTING_SUMMARIZE_PROMPT, "nope"))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nope");
    }

    // ---- KG -------------------------------------------------------------

    @Test
    void kgExtractNeedsAPromptUnconditionally() {
        // No flag to consult: kg-extract is a dedicated kind, so the job's existence IS the
        // request. That is the whole difference from summaries, which ride inside a document
        // ingest and therefore need one.
        assertThat(IngestPromptEnqueueValidator.willExtractKg(IngestJobKind.KG_EXTRACT.getValue()))
            .isTrue();
        assertThat(IngestPromptEnqueueValidator.willExtractKg(IngestJobKind.STANDARD.getValue()))
            .isFalse();
    }

    @Test
    void rejectsAKgJobThatNamesNoPrompt() {
        assertThatThrownBy(() -> validatorWithPrompt()
            .validate(req(IngestJobKind.KG_EXTRACT.getValue(), Map.of())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(IngestPrompts.SETTING_KG_PROMPT);
    }

    @Test
    void acceptsAKgJobThatNamesAPrompt() {
        SystemPromptService prompts = mock(SystemPromptService.class);
        when(prompts.requirePrompt(any(), any()))
            .thenReturn(new SystemPrompt("oim-kg", SystemPromptType.KG, "STRICT JSON.", ""));
        assertThatCode(() -> new IngestPromptEnqueueValidator(prompts)
            .validate(req(IngestJobKind.KG_EXTRACT.getValue(),
                Map.of(IngestPrompts.SETTING_KG_PROMPT, "oim-kg"))))
            .doesNotThrowAnyException();
    }

    @Test
    void aKgJobIsNotAskedForASummarizePrompt() {
        // Regression guard on the shared validator: the two arms must not leak into each other.
        assertThat(IngestPromptEnqueueValidator.willSummarize(IngestJobKind.KG_EXTRACT.getValue(),
            Map.of(DocumentIngestService.SETTING_BUILD_SECTION_SUMMARIES, true))).isFalse();
    }

    @Test
    void aNullKindIsTreatedAsNoKindRatherThanThrowing() {
        // Defensive: bareKind(null) guards the split. A validator that NPEs would turn a malformed
        // enqueue into a 500 instead of the 400 the request deserves.
        assertThat(IngestPromptEnqueueValidator.willSummarize(null, Map.of())).isFalse();
        assertThat(IngestPromptEnqueueValidator.willExtractKg(null)).isFalse();
    }

    @Test
    void passesTheRequestThroughUnchanged() {
        // It is a validator, not a normalizer: the settings it received must reach the job.
        EnqueueRequest in = req(IngestJobKind.HIERARCHICAL.getValue(),
            Map.of(IngestPrompts.SETTING_SUMMARIZE_PROMPT, PROMPT));
        assertThat(validatorWithPrompt().validate(in).settings())
            .containsEntry(IngestPrompts.SETTING_SUMMARIZE_PROMPT, PROMPT);
    }
}
