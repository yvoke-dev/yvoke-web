package de.palsoftware.yvoke.ingest.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.eq;

import de.palsoftware.yvoke.llm.core.service.LlmClient;
import de.palsoftware.yvoke.llm.core.model.LlmRequest;
import de.palsoftware.yvoke.llm.core.model.LlmResponse;
import de.palsoftware.yvoke.llm.core.model.LlmUsage;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import de.palsoftware.yvoke.llm.core.context.LlmCallContextHolder;
import de.palsoftware.yvoke.llm.core.context.LlmCallContextHolder.Context;
import java.util.concurrent.atomic.AtomicReference;
import org.mockito.ArgumentCaptor;

class GeneralSummarizerTest {

    @Test
    void testCleanReasoningLeak() {
        // Test basic summary with a preamble leak
        String leakText =
            """
                Let me read and analyze the procedure body.
                We need to summarize what it does.

                This procedure inserts records into ADSAccount when a new user is created. It validates the email parameter.""";
        assertThat(GeneralSummarizer.cleanReasoningLeak(leakText)).isEqualTo(
            "This procedure inserts records into ADSAccount when a new user is created. It validates the email parameter.");

        // Test label pattern
        String labelText = "Reasoning: Calculates the department hierarchy.";
        assertThat(GeneralSummarizer.cleanReasoningLeak(labelText))
            .isEqualTo("Calculates the department hierarchy.");

        // Test normal text
        String normal = "Updates the database configuration flags.";
        assertThat(GeneralSummarizer.cleanReasoningLeak(normal)).isEqualTo(normal);
    }

    /**
     * Two ends of the same contract, neither of which has a witness today.
     *
     * <p>
     * <b>Blank content short-circuits everything.</b> An empty or whitespace-only body returns
     * {@code ""} before the sha is computed, before the cache is probed and before the model is
     * called. This is a bulk path — every section of every hierarchical manual, every summarised
     * object of every custom corpus — and empty section bodies are ordinary there (a heading that
     * only groups its children). Losing the guard spends one LLM round-trip per empty section, on
     * content that cannot produce a summary, and then writes whatever the model hallucinated from
     * nothing into {@code summary_cache} under the sha of the empty string, where every later
     * document with an empty section reads it back as its own summary.
     *
     * <p>
     * <b>Every answer passes through {@code cleanReasoningLeak}.</b> The cleaner is unit-tested as
     * a static helper, but nothing asserts that {@code summarize()} actually applies it to the
     * model's reply — the sibling tests all stub responses that are already clean, so
     * {@code cleaned = rawResponse} keeps every one of them green. The stored value is what
     * {@code get_toc}/{@code get_section} hand to an agent as the description of a section, so a
     * dropped clean step publishes "Let me analyze the procedure body…" as documentation, and
     * caches it for good.
     *
     * <p>
     * The two are asserted together because they are the same rule about the boundary: nothing that
     * is not a summary may leave this method — not a wasted call for content that has none, and not
     * the model's own thinking.
     */
    @SuppressWarnings("unchecked")
    @Test
    void blankContentSkipsTheModelEntirelyAndTheAnswerIsAlwaysLeakCleaned() {
        LlmClient blankLlm = mock(LlmClient.class);
        JdbcClient blankJdbc = mock(JdbcClient.class);

        String blank = new GeneralSummarizer(blankLlm, blankJdbc, "dummy-model", 0.2, 1000)
            .summarize("   \n\t  ", "section_summary", "dummy-prompt", "userMsg");

        assertThat(blank).isEmpty();
        verify(blankLlm, never()).generate(any(LlmRequest.class));
        verify(blankJdbc, never()).sql(anyString());

        LlmClient llmClient = mock(LlmClient.class);
        JdbcClient jdbcClient = mock(JdbcClient.class);
        JdbcClient.StatementSpec selectSpec = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<String> querySpec = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbcClient.sql(startsWith("SELECT"))).thenReturn(selectSpec);
        when(selectSpec.param(anyString(), any())).thenReturn(selectSpec);
        when(selectSpec.query(String.class)).thenReturn(querySpec);
        when(querySpec.optional()).thenReturn(Optional.empty());
        JdbcClient.StatementSpec insertSpec = mock(JdbcClient.StatementSpec.class);
        when(jdbcClient.sql(startsWith("INSERT"))).thenReturn(insertSpec);
        when(insertSpec.param(anyString(), any())).thenReturn(insertSpec);
        when(insertSpec.update()).thenReturn(1);
        when(llmClient.generate(any(LlmRequest.class)))
            .thenReturn(
                new LlmResponse(
                    "Let me analyze the procedure body.\nWe need to summarize what it does.\n\n"
                        + "Creates ADS accounts when a user is created.",
                    new LlmUsage(0, 0, 0, 0, 0)));

        String summary = new GeneralSummarizer(llmClient, jdbcClient, "dummy-model", 0.2, 1000)
            .summarize("CREATE PROCEDURE QBM_Proc...", "procedure", "dummy-prompt", "userMsg");

        assertThat(summary).as("the model's reasoning preamble must never reach the caller")
            .isEqualTo("Creates ADS accounts when a user is created.");
        ArgumentCaptor<Object> cached = ArgumentCaptor.forClass(Object.class);
        verify(insertSpec).param(eq("summary"), cached.capture());
        assertThat(cached.getValue()).as("the leak must not be cached either")
            .isEqualTo("Creates ADS accounts when a user is created.");
    }

    /**
     * {@code summary_cache} is shared by producers that store completely different payloads:
     * {@code DocumentKgExtractor} writes an extracted-graph JSON blob under {@code kind='kg'} (and
     * reads it back with {@code AND kind = 'kg'}), while this summarizer stores prose. The read
     * here keyed on {@code source_sha} ALONE, so identical content that had already been
     * KG-extracted returned the graph JSON as its "summary" — silently, into a document/section
     * summary. The cache read MUST be scoped to the same kind it writes. Note the table's PK is
     * {@code source_sha} alone ({@code ON CONFLICT (source_sha) DO NOTHING}), so one sha holds one
     * row whatever its kind: scoping the read trades a cache miss for correctness, which is the
     * right way round.
     */
    @SuppressWarnings("unchecked")
    @Test
    void theCacheReadIsScopedToTheKindItWrites() {
        LlmClient llmClient = mock(LlmClient.class);
        JdbcClient jdbcClient = mock(JdbcClient.class);
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<String> querySpec = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbcClient.sql(anyString())).thenReturn(spec);
        when(spec.param(anyString(), any())).thenReturn(spec);
        when(spec.query(String.class)).thenReturn(querySpec);
        when(querySpec.optional()).thenReturn(Optional.of("a graph blob masquerading as prose"));

        new GeneralSummarizer(llmClient, jdbcClient, "dummy-model", 0.2, 1000).summarize("SELECT 1",
            "section_summary", "p", "u");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcClient, atLeastOnce()).sql(sql.capture());
        String select = sql.getAllValues().stream().filter(s -> s.startsWith("SELECT summary"))
            .findFirst().orElseThrow();
        assertThat(select)
            .as("the cache read must not be able to return another producer's payload")
            .contains("kind");
    }

    /**
     * The summarizer MUST declare its attribution on the calling thread before it calls the LLM,
     * and MUST clear it afterwards.
     *
     * <p>
     * {@code AccountingLlmClient} reads {@code LlmCallContextHolder} to attribute the
     * {@code llm_call_logs} row it writes. Without the {@code set}, summarization spend is logged
     * under {@code UNKNOWN_SOURCE} — it is not lost from the ledger, but it belongs to no
     * conversation, no message, no user and no recognised source, so it appears in none of the cost
     * explorer's view levels and none of the per-user reports. Summarization is a bulk path (every
     * section of every hierarchical manual), so this is the single largest block of spend an
     * operator can end up unable to see, and there is no error anywhere: the call succeeds, the
     * summary is correct, only the accounting is anonymous.
     *
     * <p>
     * The clear half is not cosmetic either. The holder is a {@link ThreadLocal} and summarization
     * fans out over pooled/virtual threads; a context left set outlives the call and is read by
     * whatever the same thread does next, silently billing an unrelated call as summarization. The
     * existing tests here all stub {@code generate} with a plain {@code thenReturn}, which observes
     * nothing about the thread state at the moment of the call, so neither half of this has a
     * witness — the sibling assertions would pass identically with the attribution deleted.
     */
    @SuppressWarnings("unchecked")
    @Test
    void theSummarizerDeclaresItselfAsSummarizationSpendOnTheCallingThread() {
        LlmCallContextHolder.clear(); // no inherited context from an earlier test on this thread

        LlmClient llmClient = mock(LlmClient.class);
        JdbcClient jdbcClient = mock(JdbcClient.class);

        JdbcClient.StatementSpec selectSpec = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<String> querySpec = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbcClient.sql(startsWith("SELECT"))).thenReturn(selectSpec);
        when(selectSpec.param(anyString(), any())).thenReturn(selectSpec);
        when(selectSpec.query(String.class)).thenReturn(querySpec);
        when(querySpec.optional()).thenReturn(Optional.empty());

        JdbcClient.StatementSpec insertSpec = mock(JdbcClient.StatementSpec.class);
        when(jdbcClient.sql(startsWith("INSERT"))).thenReturn(insertSpec);
        when(insertSpec.param(anyString(), any())).thenReturn(insertSpec);
        when(insertSpec.update()).thenReturn(1);

        // The provider is the only place that can observe the ThreadLocal at call time.
        AtomicReference<Context> observed = new AtomicReference<>();
        when(llmClient.generate(any(LlmRequest.class))).thenAnswer(inv -> {
            observed.set(LlmCallContextHolder.get());
            return new LlmResponse("Generates user accounts.", new LlmUsage(0, 0, 0, 0, 0));
        });

        new GeneralSummarizer(llmClient, jdbcClient, "dummy-model", 0.2, 1000)
            .summarize("CREATE PROCEDURE QBM_Proc...", "procedure", "dummy-prompt", "userMsg");

        assertThat(observed.get())
            .as("the provider saw no attribution at all — this spend logs as UNKNOWN_SOURCE")
            .isNotNull();
        assertThat(observed.get().source()).isEqualTo("summarization");
        assertThat(observed.get().role()).isEqualTo("summarizer");
        assertThat(LlmCallContextHolder.get())
            .as("a ThreadLocal left set misattributes whatever this thread does next").isNull();
    }

    @SuppressWarnings("unchecked")
    @Test
    void testSummarizeCacheHit() {
        LlmClient llmClient = mock(LlmClient.class);
        JdbcClient jdbcClient = mock(JdbcClient.class);

        JdbcClient.StatementSpec statementSpec = mock(JdbcClient.StatementSpec.class);
        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param(anyString(), any())).thenReturn(statementSpec);

        JdbcClient.MappedQuerySpec<String> querySpec = mock(JdbcClient.MappedQuerySpec.class);
        when(statementSpec.query(String.class)).thenReturn(querySpec);
        when(querySpec.optional()).thenReturn(Optional.of("Cached description of proc."));

        GeneralSummarizer summarizer =
            new GeneralSummarizer(llmClient, jdbcClient, "dummy-model", 0.2, 1000);
        String summary = summarizer.summarize("CREATE PROCEDURE QBM_Proc...", "procedure",
            "dummy-prompt", "userMsg");

        assertThat(summary).isEqualTo("Cached description of proc.");
        verify(llmClient, never()).generate(any(LlmRequest.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void testSummarizeCacheMiss() {
        LlmClient llmClient = mock(LlmClient.class);
        JdbcClient jdbcClient = mock(JdbcClient.class);

        // Mock cache lookup returning empty
        JdbcClient.StatementSpec selectSpec = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<String> querySpec = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbcClient.sql(startsWith("SELECT"))).thenReturn(selectSpec);
        when(selectSpec.param(anyString(), any())).thenReturn(selectSpec);
        when(selectSpec.query(String.class)).thenReturn(querySpec);
        when(querySpec.optional()).thenReturn(Optional.empty());

        // Mock cache insert
        JdbcClient.StatementSpec insertSpec = mock(JdbcClient.StatementSpec.class);
        when(jdbcClient.sql(startsWith("INSERT"))).thenReturn(insertSpec);
        when(insertSpec.param(anyString(), any())).thenReturn(insertSpec);
        when(insertSpec.update()).thenReturn(1);

        // Mock LLM response
        when(llmClient.generate(any(LlmRequest.class)))
            .thenReturn(new LlmResponse("Generates user accounts.", new LlmUsage(0, 0, 0, 0, 0)));

        GeneralSummarizer summarizer =
            new GeneralSummarizer(llmClient, jdbcClient, "dummy-model", 0.2, 1000);
        String summary = summarizer.summarize("CREATE PROCEDURE QBM_Proc...", "procedure",
            "dummy-prompt", "userMsg");

        assertThat(summary).isEqualTo("Generates user accounts.");
        verify(llmClient, times(1)).generate(any(LlmRequest.class));
        verify(insertSpec, times(1)).update();
    }

    @SuppressWarnings("unchecked")
    @Test
    void testSummarizeEmptyFallback() {
        LlmClient llmClient = mock(LlmClient.class);
        JdbcClient jdbcClient = mock(JdbcClient.class);

        JdbcClient.StatementSpec selectSpec = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<String> querySpec = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbcClient.sql(startsWith("SELECT"))).thenReturn(selectSpec);
        when(selectSpec.param(anyString(), any())).thenReturn(selectSpec);
        when(selectSpec.query(String.class)).thenReturn(querySpec);
        when(querySpec.optional()).thenReturn(Optional.empty());

        // Return empty summary to trigger retry and fallback
        when(llmClient.generate(any(LlmRequest.class)))
            .thenReturn(new LlmResponse("", new LlmUsage(0, 0, 0, 0, 0)));

        GeneralSummarizer summarizer =
            new GeneralSummarizer(llmClient, jdbcClient, "dummy-model", 0.2, 1000);
        String summary = summarizer.summarize("CREATE PROCEDURE QBM_Proc...", "procedure",
            "dummy-prompt", "userMsg");

        // Verify it returns the fallback summary
        assertThat(summary).isEqualTo("Summary unavailable.");
        // Verify it retried 3 times
        verify(llmClient, times(3)).generate(any(LlmRequest.class));
        // Verify it did NOT cache the fallback
        verify(jdbcClient, never()).sql(startsWith("INSERT"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void testSummarizeExceptionFallback() {
        LlmClient llmClient = mock(LlmClient.class);
        JdbcClient jdbcClient = mock(JdbcClient.class);

        JdbcClient.StatementSpec selectSpec = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<String> querySpec = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbcClient.sql(startsWith("SELECT"))).thenReturn(selectSpec);
        when(selectSpec.param(anyString(), any())).thenReturn(selectSpec);
        when(selectSpec.query(String.class)).thenReturn(querySpec);
        when(querySpec.optional()).thenReturn(Optional.empty());

        // Throw an exception on call to trigger retry and fallback
        when(llmClient.generate(any(LlmRequest.class)))
            .thenThrow(new RuntimeException("API connection timeout"));

        GeneralSummarizer summarizer =
            new GeneralSummarizer(llmClient, jdbcClient, "dummy-model", 0.2, 1000);
        String summary = summarizer.summarize("CREATE PROCEDURE QBM_Proc...", "procedure",
            "dummy-prompt", "userMsg");

        // Verify it returns the fallback summary
        assertThat(summary).isEqualTo("Summary unavailable.");
        // Verify it retried 3 times
        verify(llmClient, times(3)).generate(any(LlmRequest.class));
        // Verify it did NOT cache the fallback
        verify(jdbcClient, never()).sql(startsWith("INSERT"));
    }
}
