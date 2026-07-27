package de.palsoftware.yvoke.llm.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.genai.errors.ClientException;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

/**
 * Tests for the diagnostic renderer that turns a provider exception into something an admin can act
 * on.
 *
 * <p>
 * The motivating incident: an orchestrated run died and {@code agent_runs.error} recorded the
 * literal string {@code "429 . "} — the whole message. That is {@code ApiException.getMessage()} =
 * {@code String.format("%d %s. %s", code, status, message)} with both trailing fields empty, which
 * is what the Cloudflare AI Gateway produces (no HTTP/2 reason phrase, and a gateway-shaped body
 * the SDK's Google-specific parser cannot read). Diagnosing it meant reading container logs, which
 * is exactly what this class exists to prevent.
 */
class LlmFailureSummaryTest {

    private static final char NUL = (char) 0;
    private static final char BEL = (char) 7;

    /** The incident's exception, reproduced exactly. */
    private static ClientException incidentException() {
        return new ClientException(429, "", "");
    }

    @Test
    void shortLine_namesTheFaultInsteadOfEmittingTheEmptySdkMessage() {
        String out = LlmFailureSummary.shortLine(incidentException());

        assertThat(out).isNotEqualTo("429 . ");
        assertThat(out).contains("ClientException").contains("429").contains("rate limit");
    }

    @Test
    void shortLine_mapsTheStatusCodesThatActuallyOccur() {
        assertThat(LlmFailureSummary.shortLine(new ClientException(408, "", "")))
            .contains("timeout");
        assertThat(LlmFailureSummary.shortLine(new ClientException(401, "", "")))
            .contains("credentials");
        assertThat(LlmFailureSummary.shortLine(new ClientException(404, "", "")))
            .contains("not found");
    }

    @Test
    void shortLine_findsTheApiExceptionThroughACauseChain() {
        Exception wrapped = new IllegalStateException("agent failed", incidentException());

        assertThat(LlmFailureSummary.shortLine(wrapped)).contains("429").contains("rate limit");
    }

    /** A null-message failure must still produce a row an admin can read, never a blank cell. */
    @Test
    void shortLine_survivesAnExceptionWithNoMessage() {
        String out = LlmFailureSummary.shortLine(new NullPointerException());

        assertThat(out).isNotBlank().contains("NullPointerException");
    }

    @Test
    void shortLine_neverReturnsNullForNullInput() {
        assertThat(LlmFailureSummary.shortLine(null)).isNotBlank();
    }

    @Test
    void detail_carriesTheProviderFieldsAndACauseChain() {
        Exception wrapped =
            new IllegalStateException("orchestrator turn failed", incidentException());

        String out = LlmFailureSummary.detail(wrapped);

        assertThat(out).contains("429");
        assertThat(out).contains("provider:");
        assertThat(out).contains("IllegalStateException");
        assertThat(out).contains("orchestrator turn failed");
    }

    @Test
    void detail_reportsTheAttemptCountWhenLlmRetryRecordedOne() {
        ClientException e = incidentException();
        e.addSuppressed(new LlmRetryExhausted("Gemini.generateStream", 3, 3, true));

        assertThat(LlmFailureSummary.detail(e)).contains("3/3");
    }

    @Test
    void detail_saysSoWhenNoAttemptCountWasRecorded() {
        assertThat(LlmFailureSummary.detail(incidentException())).contains("not recorded");
    }

    // --- Redaction. This block is persisted and rendered on an admin page; a bearer token or an
    // api key that reached an exception message must not survive the trip. ---

    @Test
    void detail_redactsBearerTokens() {
        String out = LlmFailureSummary
            .detail(new RuntimeException("request failed: Authorization: Bearer sk-abc123XYZ"));

        assertThat(out).doesNotContain("sk-abc123XYZ");
        assertThat(out).contains("Bearer ***");
    }

    @Test
    void detail_redactsCredentialQueryParameters() {
        String out = LlmFailureSummary.detail(new RuntimeException(
            "GET https://host/v1?key=AIzaTOPSECRET&api_key=sEcOnDkEy&access_token=tHiRdKeY failed"));

        assertThat(out).doesNotContain("AIzaTOPSECRET").doesNotContain("sEcOnDkEy")
            .doesNotContain("tHiRdKeY");
    }

    @Test
    void detail_capsRunawayMessages() {
        String out = LlmFailureSummary.detail(new RuntimeException("x".repeat(50_000)));

        assertThat(out.length()).isLessThanOrEqualTo(8_000);
        assertThat(out).endsWith("… [truncated]");
    }

    @Test
    void detail_stripsControlCharactersButKeepsNewlines() {
        String messy = "a" + NUL + "b" + BEL + "c";

        String out = LlmFailureSummary.detail(new RuntimeException(messy));

        assertThat(out).doesNotContain(String.valueOf(NUL));
        assertThat(out).doesNotContain(String.valueOf(BEL));
        assertThat(out).contains("\n");
    }

    @Test
    void detail_neverReturnsNullForNullInput() {
        assertThat(LlmFailureSummary.detail(null)).isNotBlank();
    }

    /**
     * A cancellation is not a fault. It still renders, because the caller decides what to persist,
     * but it must not be dressed up as a provider error.
     */
    @Test
    void shortLine_describesCancellationPlainly() {
        assertThat(LlmFailureSummary.shortLine(new CancellationException("stopped")))
            .contains("CancellationException");
    }
}
