package de.palsoftware.yvoke.ingest.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.ingest.core.model.Section;
import de.palsoftware.yvoke.rag.prompt.SystemPrompt;
import de.palsoftware.yvoke.rag.prompt.SystemPromptService;
import de.palsoftware.yvoke.rag.prompt.SystemPromptType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Unit tests for {@link SectionSummarizer}. It delegates every LLM call to
 * {@link GeneralSummarizer} (the mocked seam — NOT {@code LlmClient}) and persists per-section
 * summaries. These pin the behaviors that quietly degrade retrieval if they break: the empty-body
 * short-circuit, the bottom-up parent synthesis, non-fatal per-node failures, and cancellation.
 */
class SectionSummarizerTest {

    private GeneralSummarizer generalSummarizer;
    private JdbcClient jdbcClient;
    private JdbcTemplate jdbcTemplate;
    private SystemPromptService systemPromptService;
    private PlatformTransactionManager txManager;
    private SectionSummarizer summarizer;

    private final UUID docId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        generalSummarizer = mock(GeneralSummarizer.class);
        jdbcClient = mock(JdbcClient.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        systemPromptService = mock(SystemPromptService.class);
        txManager = mock(PlatformTransactionManager.class);
        summarizer = new SectionSummarizer(generalSummarizer, jdbcClient, jdbcTemplate, 1,
            systemPromptService, txManager);
    }

    /** Stubs the DELETE-from-section_summaries chain used in the persistence step. */
    private void stubPersistenceDelete() {
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        when(jdbcClient.sql(anyString())).thenReturn(spec);
        when(spec.param(anyString(), any())).thenReturn(spec);
        when(spec.update()).thenReturn(1);
    }

    @Test
    void emptySectionsDoesNothing() {
        summarizer.generateSummaries(docId, List.of(), null, null, "PROMPT");

        verifyNoInteractions(generalSummarizer, jdbcClient, jdbcTemplate, systemPromptService,
            txManager);
    }

    @Test
    void leafSectionIsSummarizedAndPersisted() {
        stubPersistenceDelete();
        when(generalSummarizer.summarize(anyString(), anyString(), any(), anyString()))
            .thenReturn("Overview summary.");

        Section s = new Section(1, "Overview", List.of(), "This is the body of the overview.");
        summarizer.generateSummaries(docId, List.of(s), null, null, "OVERRIDE_PROMPT");

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> kind = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userMsg = ArgumentCaptor.forClass(String.class);
        verify(generalSummarizer).summarize(content.capture(), kind.capture(), prompt.capture(),
            userMsg.capture());

        assertThat(content.getValue()).isEqualTo("This is the body of the overview.");
        assertThat(kind.getValue()).isEqualTo("section_summary");
        assertThat(prompt.getValue()).isEqualTo("OVERRIDE_PROMPT"); // override wins
        assertThat(userMsg.getValue()).contains("Section path: Overview").contains("```markdown")
            .contains("This is the body of the overview.").contains("Write the summary now.");

        verifyNoInteractions(systemPromptService); // override provided => DB prompt not consulted
        verify(jdbcClient).sql(startsWith("DELETE FROM section_summaries"));
        verify(jdbcTemplate).batchUpdate(startsWith("INSERT INTO section_summaries"),
            any(BatchPreparedStatementSetter.class));
    }

    @Test
    void blankBodyShortCircuitsWithoutLlm() {
        stubPersistenceDelete();

        Section s = new Section(1, "Empty", List.of(), "   "); // trims to "" => "Empty section."
        summarizer.generateSummaries(docId, List.of(s), null, null, "OVERRIDE_PROMPT");

        verify(generalSummarizer, never()).summarize(any(), any(), any(), any());
        verify(jdbcTemplate).batchUpdate(startsWith("INSERT INTO section_summaries"),
            any(BatchPreparedStatementSetter.class));
    }

    /**
     * Replaces {@code nullOverrideResolvesDefaultSummarizePrompt}, which asserted the behaviour
     * that caused the bug: with no prompt supplied, this class looked up
     * {@code "default-summarize"} — a name registered in no deployment — and summarized with
     * whatever that returned, i.e. null. The old test passed because it STUBBED that name into
     * existence, so the one path production actually took (lookup misses, prompt is null, every
     * summary is generated unguided) was the one path never exercised. The prompt is a required
     * argument now, and the absence of one is a failure rather than a silent default.
     */
    @Test
    void refusesToSummarizeWithoutAPrompt() {
        Section s = new Section(1, "Overview", List.of(), "body");

        assertThatThrownBy(() -> summarizer.generateSummaries(docId, List.of(s), null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("summarize system prompt is required");
        assertThatThrownBy(() -> summarizer.generateSummaries(docId, List.of(s), null, null, "  "))
            .isInstanceOf(IllegalArgumentException.class);

        // Refused before any work: no LLM call, and nothing written.
        verify(generalSummarizer, never()).summarize(any(), any(), any(), any());
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void usesTheSuppliedPromptForEveryNode() {
        stubPersistenceDelete();
        when(generalSummarizer.summarize(anyString(), anyString(), any(), anyString()))
            .thenReturn("x");

        Section s = new Section(1, "Overview", List.of(), "body");
        summarizer.generateSummaries(docId, List.of(s), null, null, "SUPPLIED_PROMPT");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(generalSummarizer).summarize(anyString(), anyString(), prompt.capture(),
            anyString());
        assertThat(prompt.getValue()).isEqualTo("SUPPLIED_PROMPT");
        // The prompt arrives resolved; this class no longer looks anything up per node.
        verifyNoInteractions(systemPromptService);
    }

    @Test
    void parentNodeSynthesizesChildSummaries() {
        stubPersistenceDelete();
        when(generalSummarizer.summarize(eq("content A"), anyString(), any(), anyString()))
            .thenReturn("Summary A");
        when(generalSummarizer.summarize(startsWith("> Chapter 1 > Section A"), anyString(), any(),
            anyString())).thenReturn("Chapter summary");

        Section child = new Section(2, "Section A", List.of("Chapter 1"), "content A");
        summarizer.generateSummaries(docId, List.of(child), null, null, "P");

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(generalSummarizer, times(2)) // depth-2 leaf + depth-1 synthesized parent
            .summarize(content.capture(), anyString(), any(), anyString());
        assertThat(content.getAllValues()).anySatisfy(c -> assertThat(c).isEqualTo("content A"));
        assertThat(content.getAllValues()).anySatisfy(c -> assertThat(c).contains("Summary A"));
    }

    @Test
    void summarizeExceptionIsSwallowedAndPersistsFallback() {
        stubPersistenceDelete();
        when(generalSummarizer.summarize(any(), any(), any(), any()))
            .thenThrow(new RuntimeException("boom"));

        Section s = new Section(1, "T", List.of(), "body");
        assertThatCode(() -> summarizer.generateSummaries(docId, List.of(s), null, null, "P"))
            .doesNotThrowAnyException();
        verify(jdbcTemplate).batchUpdate(startsWith("INSERT INTO section_summaries"),
            any(BatchPreparedStatementSetter.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void cancelledJobThrowsBeforePersisting() {
        UUID jobId = UUID.randomUUID();
        JdbcClient.StatementSpec sel = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<String> q = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbcClient.sql(startsWith("SELECT status"))).thenReturn(sel);
        when(sel.param(eq("id"), any())).thenReturn(sel);
        when(sel.query(String.class)).thenReturn(q);
        when(q.optional()).thenReturn(Optional.of("cancelled")); // != "running"

        Section s = new Section(1, "T", List.of(), "body");
        assertThatThrownBy(() -> summarizer.generateSummaries(docId, List.of(s), jobId, null, "P"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("cancelled");

        verify(generalSummarizer, never()).summarize(any(), any(), any(), any());
        verify(jdbcTemplate, never()).batchUpdate(anyString(),
            any(BatchPreparedStatementSetter.class));
    }
}
