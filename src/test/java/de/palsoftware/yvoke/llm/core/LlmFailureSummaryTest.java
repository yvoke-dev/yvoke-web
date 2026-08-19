package de.palsoftware.yvoke.llm.core;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Optional;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIServiceException;
import com.openai.core.http.Headers;
import com.openai.core.JsonValue;
import static org.assertj.core.api.Assertions.assertThat;

import com.azure.core.exception.HttpResponseException;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpMethod;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.google.genai.errors.ClientException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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

    /**
     * Azure has the same problem from the other side: azure-core exposes no reason phrase at all
     * (OkHttp and the JDK client both drop it), so an unmapped Azure failure would render as the
     * bare exception class name. The status is the informative field, exactly as for Gemini, and
     * both providers must reach the same phrase for the same code.
     */
    @Test
    void shortLine_readsTheStatusOffAnAzureFailureToo() {
        assertThat(LlmFailureSummary.shortLine(azure(429))).contains("429").contains("rate limit");
        assertThat(LlmFailureSummary.shortLine(azure(401))).contains("credentials");
        assertThat(LlmFailureSummary.shortLine(azure(503))).contains("provider unavailable");
    }

    /** The redaction applies whatever produced the text: it is third-party input either way. */
    @Test
    void detail_redactsCredentialsInsideAnAzureMessage() {
        HttpResponseException e = new HttpResponseException(
            "Status code 401, \"{\"error\":\"bad api_key=sk-live-abcdef123456\"}\"",
            mockResponse(401));

        String out = LlmFailureSummary.detail(e);

        assertThat(out).doesNotContain("sk-live-abcdef123456");
        assertThat(out).contains("401");
    }

    private static HttpResponseException azure(int statusCode) {
        return new HttpResponseException("Status code " + statusCode + ", \"\"",
            mockResponse(statusCode));
    }

    private static HttpResponse mockResponse(int statusCode) {
        HttpRequest request = new HttpRequest(HttpMethod.POST, "https://example.openai.azure.com");
        return new HttpResponse(request) {
            @Override
            public int getStatusCode() {
                return statusCode;
            }

            @Override
            @SuppressWarnings("deprecation")
            public String getHeaderValue(String name) {
                return null;
            }

            @Override
            public HttpHeaders getHeaders() {
                return new HttpHeaders();
            }

            @Override
            public Flux<ByteBuffer> getBody() {
                return Flux.empty();
            }

            @Override
            public Mono<byte[]> getBodyAsByteArray() {
                return Mono.empty();
            }

            @Override
            public Mono<String> getBodyAsString() {
                return Mono.empty();
            }

            @Override
            public Mono<String> getBodyAsString(Charset charset) {
                return Mono.empty();
            }
        };
    }

    /**
     * openai-java's exception must be read by the SAME extractor the retry classifier uses.
     *
     * <p>
     * It was not. {@code LlmRetry.providerStatus} was taught this type when the OpenRouter and
     * Responses clients were added; the two readers that produce what an ADMIN sees were not. So a
     * 429 on those clients was correctly retried and then rendered as a bare class name, with the
     * {@code rate-limit:} line — the only place a shared-capacity rejection says anything useful —
     * missing entirely from {@code agent_steps.error}. Three hand-maintained readers of one
     * concept, one of them updated, which is precisely what {@link ProviderRateLimit}'s javadoc
     * warns about.
     */
    @Test
    void anOpenAiServiceExceptionIsRenderedWithItsStatusAndRateLimitHeaders() {
        Throwable fault = openAiRateLimited();

        assertTrue(LlmFailureSummary.shortLine(fault).contains("HTTP 429"),
            "the status is the one field every SDK supplies: "
                + LlmFailureSummary.shortLine(fault));
        assertTrue(LlmFailureSummary.shortLine(fault).contains("rate limit"),
            "and it must be rendered into something an admin can act on");

        String detail = LlmFailureSummary.detail(fault);
        assertTrue(detail.contains("retry-after=30s"),
            "the server's own interval is the only trustworthy number in a 429: " + detail);
        assertTrue(detail.contains("remaining-tokens=0"), detail);
        // The two structured fields, asserted because dropping them is silent: replacing
        // ProviderFault's openai branch with null status and null message renders
        // provider: status="" message="" and leaves the whole suite green — the same block going
        // missing that this test exists to restore.
        assertTrue(detail.contains("status=\"requests\""),
            "the provider's own status token must survive into the persisted trace: " + detail);
        assertTrue(detail.contains("message=\"rate_limit_exceeded\""),
            "and so must the code that names WHICH limit was hit: " + detail);
    }

    /**
     * {@code Optional<String>} reads as "absent is expected". openai-java does not mean that: a
     * field missing from the response body makes the getter <b>throw</b>
     * {@code OpenAIInvalidDataException}, and a plain transport-level 5xx carries no JSON error
     * body at all — so the very failure {@link ProviderFault} exists to describe is the one that
     * blows up while describing it.
     *
     * <p>
     * The blast radius is not the trace but the retry: {@link ProviderFault#at} is called from
     * {@code LlmRetry.isTransient}, i.e. from inside {@code withRetry}'s own catch block, so the
     * throw escapes as a hard error and a retryable 503 dies on attempt 1 under a message naming
     * the wrong problem entirely. This was reached live, by an Azure Responses 503 whose body was
     * {@code {"error":{"message":"upstream busy"}}}.
     */
    @Test
    void aStructuredFieldThatThrowsIsNotAllowedToBecomeTheFailure() {
        Throwable transportError = openAiWithUnsetType();

        ProviderFault fault = ProviderFault.at(transportError);

        assertEquals(503, fault.code(), "the status is readable even when the body is not");
        assertNull(fault.status(), "an unreadable field is absent, not fatal");
        assertTrue(LlmRetry.isTransient(transportError),
            "and the 503 must still be classified retryable rather than escaping as a hard error");
    }

    /** A 5xx with no JSON error body, which is what every transport-level failure looks like. */
    private static OpenAIServiceException openAiWithUnsetType() {
        return new OpenAIServiceException("503: upstream busy", null) {
            @Override
            public int statusCode() {
                return 503;
            }

            @Override
            public Headers headers() {
                return Headers.builder().build();
            }

            @Override
            public JsonValue body() {
                return JsonValue.from(null);
            }

            @Override
            public Optional<String> code() {
                throw new OpenAIInvalidDataException("`code` is not set");
            }

            @Override
            public Optional<String> param() {
                return Optional.empty();
            }

            @Override
            public Optional<String> type() {
                throw new OpenAIInvalidDataException("`type` is not set");
            }
        };
    }

    /** The same headers must reach the backoff, not just the admin page. */
    @Test
    void anOpenAiServiceExceptionHonoursItsRetryAfter() {
        assertEquals(30_000L, ProviderRateLimit.from(openAiRateLimited()).retryAfterMillisClamped(),
            "an invented 15s schedule must not override an interval the server actually stated");
    }

    /** Header lookup must be case-insensitive: HTTP/2 lowercases, mock servers capitalise. */
    @Test
    void openAiHeaderLookupIsCaseInsensitive() {
        assertEquals(30L, ProviderRateLimit.from(openAiRateLimited()).retryAfterSeconds(),
            "the double supplies mixed-case names, as com.sun.net.httpserver does");
    }

    private static OpenAIServiceException openAiRateLimited() {
        Headers headers = Headers.builder().put("Retry-After", "30")
            .put("X-RateLimit-Remaining-Tokens", "0").put("apim-request-id", "abc-123").build();
        return new OpenAIServiceException("429: rate limited", null) {
            @Override
            public int statusCode() {
                return 429;
            }

            @Override
            public Headers headers() {
                return headers;
            }

            @Override
            public JsonValue body() {
                return JsonValue.from(null);
            }

            @Override
            public Optional<String> code() {
                return Optional.of("rate_limit_exceeded");
            }

            @Override
            public Optional<String> param() {
                return Optional.empty();
            }

            @Override
            public Optional<String> type() {
                return Optional.of("requests");
            }
        };
    }
}
