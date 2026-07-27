package de.palsoftware.yvoke.ingest.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

import de.palsoftware.yvoke.llm.core.service.LlmClient;
import de.palsoftware.yvoke.llm.core.model.LlmRequest;
import de.palsoftware.yvoke.llm.core.model.LlmResponse;
import de.palsoftware.yvoke.llm.core.model.LlmUsage;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

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
