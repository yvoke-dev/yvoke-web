package de.palsoftware.yvoke.llm.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.ThinkingLevel;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import de.palsoftware.yvoke.llm.core.model.LlmMessage;
import de.palsoftware.yvoke.llm.core.model.LlmPart;
import de.palsoftware.yvoke.llm.core.model.LlmRequest;
import de.palsoftware.yvoke.llm.core.model.LlmResponse;
import de.palsoftware.yvoke.llm.core.model.LlmResponseChunk;
import de.palsoftware.yvoke.llm.core.model.LlmTool;
import de.palsoftware.yvoke.llm.core.model.LlmUsage;
import de.palsoftware.yvoke.llm.core.model.LlmToolCall;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.ArrayList;
import de.palsoftware.yvoke.llm.core.model.LlmCallFailedException;

@Timeout(60)
class GeminiLlmClientTest {

    // ------------------------------------------------------------------------
    // buildConfig() unit tests
    // ------------------------------------------------------------------------

    @Test
    void testBuildConfigAppliesTemperature() {
        GenerateContentConfig config =
            buildConfig(false, null, new LlmRequest("model", List.of(), 0.5, 100, List.of(), null));

        assertNotNull(config);
        assertTrue(config.temperature().isPresent(), "Temperature must be applied");
        assertEquals(0.5f, config.temperature().get(), "Temperature must match the request value");
        assertEquals(100, config.maxOutputTokens().orElse(0));
    }

    @Test
    void testBuildConfigOmitsNonPositiveMaxTokens() {
        GenerateContentConfig config =
            buildConfig(false, null, new LlmRequest("model", List.of(), 0.0, 0, List.of(), null));

        assertFalse(config.maxOutputTokens().isPresent(),
            "maxOutputTokens must not be sent when the request value is 0");
    }

    @Test
    void testBuildConfigPrefersPerRequestThinkingLevel() {
        GenerateContentConfig config = buildConfig(true, "low",
            new LlmRequest("model", List.of(), 0.0, 100, List.of(), "high"));

        assertTrue(config.thinkingConfig().isPresent(), "Thinking config must be present");
        assertEquals(ThinkingLevel.Known.HIGH,
            config.thinkingConfig().get().thinkingLevel().orElseThrow().knownEnum(),
            "Per-request thinkingLevel must override the client default");
    }

    @Test
    void testBuildConfigFallsBackToClientThinkingLevel() {
        GenerateContentConfig config =
            buildConfig(true, "low", new LlmRequest("model", List.of(), 0.0, 100, List.of(), null));

        assertTrue(config.thinkingConfig().isPresent(), "Thinking config must be present");
        assertEquals(ThinkingLevel.Known.LOW,
            config.thinkingConfig().get().thinkingLevel().orElseThrow().knownEnum(),
            "Falls back to the client-wide thinkingLevel when the request omits it");
    }

    @Test
    void testBuildConfigIgnoresInvalidThinkingLevel() {
        GenerateContentConfig config = buildConfig(true, "bogus",
            new LlmRequest("model", List.of(), 0.0, 100, List.of(), null));

        assertTrue(config.thinkingConfig().isPresent(), "Thinking config must still be present");
        assertFalse(config.thinkingConfig().get().thinkingLevel().isPresent(),
            "An invalid thinkingLevel string must be dropped, not forwarded to the API");
    }

    /**
     * {@code enable-thinking: false} must actually disable thinking.
     *
     * <p>
     * It did not. The whole {@code thinkingConfig} was gated on the flag, so {@code false} sent no
     * thinking field at all — and on a thinking model "say nothing" means "use your own default",
     * which is to think. {@code ThinkingConfig} has three independent fields and only
     * {@code thinkingBudget} turns it off; {@code includeThoughts} governs whether the thoughts
     * come back, and {@code thinkingLevel} how hard.
     *
     * <p>
     * The second half is the sharper one: because the level was set inside the same guard, turning
     * the flag off ALSO discarded the configured level. A summarize or KG-extract call asking for
     * {@code low} therefore fell back to the model's default, which can be higher — so the setting
     * whose purpose is to spend less could make a call reason harder and cost more.
     */
    @Test
    void thinkingDisabledSendsAZeroBudgetRatherThanNoConfigAtAll() {
        GenerateContentConfig config = buildConfig(false, "low",
            new LlmRequest("gemini-3.1-flash", List.of(), 0.0, 100, List.of(), null));

        assertTrue(config.thinkingConfig().isPresent(),
            "sending nothing leaves the model's own default in place, which is to think");
        assertEquals(0, config.thinkingConfig().get().thinkingBudget().orElse(-1),
            "thinkingBudget is the only field that actually disables thinking");
        assertFalse(config.thinkingConfig().get().includeThoughts().orElse(true),
            "and the thoughts must not be streamed back either");
    }

    @Test
    void testBuildConfigSkipsThinkingForNonThinkingModel() {
        GenerateContentConfig config = buildConfig(true, "high",
            new LlmRequest("gemini-2.0-flash", List.of(), 0.0, 100, List.of(), null));

        assertFalse(config.thinkingConfig().isPresent(),
            "Thinking config must not be attached to a non-thinking (legacy) model");
    }

    @Test
    void testBuildConfigAppliesSeed() {
        GenerateContentConfig config = buildConfig(false, null,
            new LlmRequest("model", List.of(), 0.0, 100, List.of(), null, null, null, 42));

        assertTrue(config.seed().isPresent(), "Seed must be applied");
        assertEquals(42, config.seed().get());
    }

    @Test
    void testBuildConfigAppliesStructuredOutput() {
        Map<String, Object> schema =
            Map.of("type", "object", "properties", Map.of("answer", Map.of("type", "string")));
        GenerateContentConfig config = buildConfig(false, null, new LlmRequest("model", List.of(),
            0.0, 100, List.of(), null, "application/json", schema, null));

        assertEquals("application/json", config.responseMimeType().orElse(null));
        assertTrue(config.responseJsonSchema().isPresent(),
            "Raw JSON response schema must be applied (lossless)");
    }

    @Test
    void testBuildConfigIgnoresStructuredOutputWhenToolsPresent() {
        LlmTool tool = new LlmTool("do_thing", "does a thing", Map.of("type", "object"));
        GenerateContentConfig config = buildConfig(false, null, new LlmRequest("model", List.of(),
            0.0, 100, List.of(tool), null, "application/json", null, null));

        assertFalse(config.responseMimeType().isPresent(),
            "Structured output must be ignored when tools are present");
        assertTrue(config.tools().isPresent(), "Tools must still be configured");
    }

    @Test
    void testBuildConfigEnablesCodeExecutionAlone() {
        GenerateContentConfig config = buildConfig(false, null,
            new LlmRequest("model", List.of(), 0.0, 100, List.of(), null, true));

        assertTrue(config.tools().isPresent(), "Tools must be configured");
        assertTrue(config.tools().get().stream().anyMatch(t -> t.codeExecution().isPresent()),
            "A code-execution tool must be present");
        assertFalse(
            config.toolConfig().flatMap(tc -> tc.includeServerSideToolInvocations()).orElse(false),
            "Server-side tool invocations flag is only needed when combined with function tools");
    }

    @Test
    void testBuildConfigCombinesCodeExecutionWithFunctionTools() {
        LlmTool tool = new LlmTool("do_thing", "does a thing", Map.of("type", "object"));
        GenerateContentConfig config = buildConfig(false, null,
            new LlmRequest("model", List.of(), 0.0, 100, List.of(tool), null, true));

        assertTrue(config.tools().isPresent(), "Tools must be configured");
        assertTrue(
            config.tools().get().stream()
                .anyMatch(t -> !t.functionDeclarations().orElse(List.of()).isEmpty()),
            "Function declarations must be present");
        assertTrue(config.tools().get().stream().anyMatch(t -> t.codeExecution().isPresent()),
            "A code-execution tool must be present alongside function tools");
        assertTrue(
            config.toolConfig().flatMap(tc -> tc.includeServerSideToolInvocations()).orElse(false),
            "Combining built-in + function tools must opt into server-side tool invocations");
    }

    // ------------------------------------------------------------------------
    // Mock-server integration tests (exercise the real HTTP wire)
    // ------------------------------------------------------------------------

    @Test
    void testMockServerGenerateContent() throws Exception {
        AtomicReference<String> receivedPath = new AtomicReference<>();
        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicReference<String> receivedAuthHeader = new AtomicReference<>();

        withMockServer(exchange -> {
            receivedPath.set(exchange.getRequestURI().toString());
            receivedAuthHeader.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            receivedBody.set(readBody(exchange));
            respondJson(exchange, 200,
                """
                    {
                      "candidates": [
                        {
                          "content": { "parts": [ { "text": "Hello from mock server" } ], "role": "model" },
                          "finishReason": "STOP"
                        }
                      ],
                      "usageMetadata": {
                        "promptTokenCount": 5, "candidatesTokenCount": 10, "totalTokenCount": 15
                      }
                    }
                    """);
        }, client -> {
            LlmRequest request = new LlmRequest("gemini-1.5-flash",
                List.of(new LlmMessage("user", "Hello")), 0.7, 100, List.of());
            LlmResponse response = client.generate(request);

            assertNotNull(response);
            assertEquals("Hello from mock server", response.content());
            assertEquals(5, response.usage().promptTokens());
            assertEquals(10, response.usage().completionTokens());
            assertEquals(15, response.usage().totalTokens());

            assertTrue(
                receivedPath.get().contains("/v1beta/models/gemini-1.5-flash:generateContent"));
            assertEquals("mock-api-key", receivedAuthHeader.get());
            // The request body carries the fixes end-to-end: the user message and the applied
            // temperature (which was previously dropped by buildConfig).
            assertTrue(receivedBody.get().contains("Hello"),
                "request body must contain the user message");
            assertTrue(receivedBody.get().contains("temperature"),
                "temperature must be serialized onto the wire");
        });
    }

    @Test
    void testMockServerGenerateStream() throws Exception {
        AtomicReference<String> receivedPath = new AtomicReference<>();

        withMockServer(exchange -> {
            receivedPath.set(exchange.getRequestURI().toString());
            respondSse(exchange, """
                {"candidates":[{"content":{"parts":[{"text":"Hello "}]}}]}""",
                """
                    {"candidates":[{"content":{"parts":[{"text":"world!"}],"role":"model"},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":2,"totalTokenCount":7}}""");
        }, client -> {
            List<LlmResponseChunk> chunks = new ArrayList<>();
            client.generateStream(userRequest("Hello"), chunks::add);

            assertNotNull(chunks);
            assertEquals(2, chunks.size());
            assertEquals("Hello ", chunks.get(0).content());
            assertEquals("world!", chunks.get(1).content());
            assertEquals(7, chunks.get(1).usage().totalTokens());

            assertTrue(receivedPath.get()
                .contains("/v1beta/models/gemini-1.5-flash:streamGenerateContent"));
        });
    }

    @Test
    void testMockServerStreamAccumulatesTextPartsWithinChunk() throws Exception {
        // Regression for the parseChunk scalar-overwrite bug: multiple text parts in a SINGLE chunk
        // must be concatenated, not overwritten by the last one.
        withMockServer(exchange -> respondSse(exchange,
            """
                {"candidates":[{"content":{"parts":[{"text":"Hello "},{"text":"world"}],"role":"model"},"finishReason":"STOP"}]}"""),
            client -> {
                List<LlmResponseChunk> chunks = new ArrayList<>();
                client.generateStream(userRequest("Hi"), chunks::add);

                assertNotNull(chunks);
                assertEquals(1, chunks.size());
                assertEquals("Hello world", chunks.get(0).content(),
                    "Multiple text parts in one chunk must be concatenated, not overwritten");
            });
    }

    @Test
    void testMockServerStreamSeparatesThoughtAndText() throws Exception {
        withMockServer(exchange -> respondSse(exchange,
            """
                {"candidates":[{"content":{"parts":[{"thought":true,"text":"reasoning"},{"text":"answer"}],"role":"model"},"finishReason":"STOP"}]}"""),
            client -> {
                List<LlmResponseChunk> chunks = new ArrayList<>();
                client.generateStream(userRequest("Hi"), chunks::add);

                assertNotNull(chunks);
                assertEquals(1, chunks.size());
                assertEquals("reasoning", chunks.get(0).reasoning());
                assertEquals("answer", chunks.get(0).content());
            });
    }

    @Test
    void testMockServerRetryOnTransientFailure() throws Exception {
        AtomicInteger attemptCount = new AtomicInteger(0);

        withMockServer(exchange -> {
            if (attemptCount.incrementAndGet() < 3) {
                exchange.sendResponseHeaders(503, 0);
                exchange.close();
            } else {
                respondJson(exchange, 200, """
                    {"candidates":[{"content":{"parts":[{"text":"Success after retry"}]}}]}""");
            }
        }, client -> {
            LlmResponse response = client.generate(userRequest("Hello"));

            assertNotNull(response);
            assertEquals("Success after retry", response.content());
            assertEquals(3, attemptCount.get(), "Should have completed on the 3rd attempt");
        });
    }

    @Test
    void testMockServerNoRetryOnNonTransientFailure() throws Exception {
        AtomicInteger attemptCount = new AtomicInteger(0);

        withMockServer(exchange -> {
            attemptCount.incrementAndGet();
            exchange.sendResponseHeaders(403, 0); // Forbidden: not transient
            exchange.close();
        }, client -> {
            assertThrows(RuntimeException.class, () -> client.generate(userRequest("Hello")));
            assertEquals(1, attemptCount.get(),
                "A non-transient (403) failure must not be retried");
        });
    }

    @Test
    void testMockServerSafetyBlockThrowsDescriptiveError() throws Exception {
        withMockServer(exchange -> respondJson(exchange, 200, """
            {"candidates":[{"finishReason":"SAFETY"}]}"""), client -> {
            RuntimeException ex =
                assertThrows(RuntimeException.class, () -> client.generate(userRequest("Hello")));
            assertTrue(ex.getMessage().contains("finishReason"),
                "A blocked response must surface the finish reason, not a silent null");
        });
    }

    /**
     * A safety/recitation block is not a free call. The provider read the whole prompt, charged for
     * it, and reported the count in {@code usageMetadata} — on the RAG paths that prompt routinely
     * runs to hundreds of thousands of tokens, so these are the most expensive calls in the system,
     * not the cheapest.
     *
     * <p>
     * The accounting seam is {@code AccountingLlmClient}, which publishes a usage event so the call
     * lands in {@code llm_call_logs}. On the streaming path it observes usage from the chunks it
     * sees; on this non-streaming path the only carrier is the exception, because {@code generate}
     * has nothing to return. Throwing a plain exception here — or passing {@code null} for usage,
     * which reads as a harmless simplification since "the call failed" — leaves the call with no
     * row in {@code llm_call_logs} at all. The damage is silent and cumulative: the tokens are
     * billed by the provider and invisible in every internal view, so the cost dashboard, the
     * per-user report and any budget alarm all under-report by exactly the calls most worth
     * noticing. Note the usage must be read BEFORE the throw, which is the ordering this test
     * really pins.
     *
     * <p>
     * No existing test would notice: {@code testMockServerSafetyBlockThrowsDescriptiveError}
     * asserts only that the message names the finish reason, and it is satisfied by a
     * {@code RuntimeException} carrying no usage at all — it does not even assert the exception
     * type, so the whole {@link LlmCallFailedException} contract could be deleted underneath it.
     */
    @Test
    void aBlockedResponseCarriesTheUsageTheProviderAlreadyBilled() throws Exception {
        withMockServer(exchange -> respondJson(exchange, 200,
            """
                {"candidates":[{"finishReason":"SAFETY"}],"usageMetadata":{"promptTokenCount":1234,"candidatesTokenCount":6,"totalTokenCount":1240}}"""),
            client -> {
                LlmCallFailedException ex = assertThrows(LlmCallFailedException.class,
                    () -> client.generate(userRequest("Hello")));

                assertNotNull(ex.usage(),
                    "a blocked call still consumed tokens; dropping the usage loses the whole "
                        + "llm_call_logs row");
                assertEquals(1234, ex.usage().promptTokens(),
                    "the prompt the provider actually read must be billed");
                assertEquals(1240, ex.usage().totalTokens(),
                    "the total the provider reported must survive the failure");
                assertTrue(ex.getMessage().contains("finishReason=SAFETY"),
                    "the reason must still be surfaced, not swallowed by the usage plumbing");
            });
    }

    /**
     * Tokens fed back into the model by a server-side tool are prompt tokens, and must be billed.
     *
     * <p>
     * {@code GenerateContentResponseUsageMetadata} (v1.60.0) documents
     * {@code toolUsePromptTokenCount} as "the number of tokens in the results from tool executions,
     * which are provided back to the model <b>as input</b>", and defines {@code totalTokenCount} as
     * the sum of {@code promptTokenCount}, {@code candidatesTokenCount},
     * {@code toolUsePromptTokenCount} and {@code thoughtsTokenCount} — so it is disjoint from the
     * prompt count, not contained in it. The client mapped five fields and never this one.
     *
     * <p>
     * The consequence is a silent under-charge on exactly the runs that cost most. It is reachable
     * only with {@code codeExecution=true}, the one server-side tool this app wires, where a code
     * result of thousands of tokens is fed back as input: {@code PricingCalculator} prices only
     * prompt/completion/cached/thought, and {@code CostCalculationService} <i>recomputes</i>
     * {@code totalTokens} from those four for the rendered row — overwriting the persisted
     * {@code total_tokens} that was the only surviving trace of the gap. Nothing compares the two.
     *
     * <p>
     * Folded into {@code promptTokens} rather than given a sixth field: that is how the provider
     * bills it, it is priced identically, and it keeps the invariant asserted below true — which is
     * the property that would have caught this in the first place.
     */
    @Test
    void serverSideToolResultTokensAreBilledAsPromptTokensWhileStreaming() throws Exception {
        withMockServer(exchange -> respondSse(exchange,
            """
                {"candidates":[{"content":{"parts":[{"text":"done"}],"role":"model"},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":20,"thoughtsTokenCount":5,"toolUsePromptTokenCount":12000,"totalTokenCount":12125}}"""),
            client -> {
                List<LlmResponseChunk> chunks = new ArrayList<>();
                client.generateStream(userRequest("Hello"), chunks::add);

                LlmUsage usage = chunks.stream().map(LlmResponseChunk::usage)
                    .filter(Objects::nonNull).reduce((a, b) -> b).orElseThrow();

                assertEquals(12100, usage.promptTokens(),
                    "tool-result tokens are input the provider charges for");
                assertEquals(usage.totalTokens(),
                    usage.promptTokens() + usage.completionTokens() + usage.thoughtTokens(),
                    "the components must sum to the reported total, or the dashboard — which "
                        + "recomputes the total from them — prices the difference at zero");
            });
    }

    /** The blocking reader is a second, independent mapping of the same fields. */
    @Test
    void serverSideToolResultTokensAreBilledAsPromptTokensWhenBlocking() throws Exception {
        withMockServer(exchange -> respondJson(exchange, 200,
            """
                {"candidates":[{"content":{"parts":[{"text":"done"}],"role":"model"},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":20,"thoughtsTokenCount":5,"toolUsePromptTokenCount":12000,"totalTokenCount":12125}}"""),
            client -> {
                LlmUsage usage = client.generate(userRequest("Hello")).usage();

                assertEquals(12100, usage.promptTokens());
                assertEquals(usage.totalTokens(),
                    usage.promptTokens() + usage.completionTokens() + usage.thoughtTokens(),
                    "both readers must agree; they are two hand-written mappings of one contract");
            });
    }

    @Test
    void testMockServerEmptyCandidatesWithPromptFeedbackThrows() throws Exception {
        withMockServer(exchange -> respondJson(exchange, 200, """
            {"candidates":[],"promptFeedback":{"blockReason":"SAFETY"}}"""), client -> {
            RuntimeException ex =
                assertThrows(RuntimeException.class, () -> client.generate(userRequest("Hello")));
            assertTrue(ex.getMessage().contains("no candidates"));
        });
    }

    @Test
    void testMockServerStreamEstablishmentRetry() throws Exception {
        AtomicInteger attemptCount = new AtomicInteger(0);

        withMockServer(exchange -> {
            if (attemptCount.incrementAndGet() < 3) {
                exchange.sendResponseHeaders(503, 0);
                exchange.close();
            } else {
                respondSse(exchange,
                    """
                        {"candidates":[{"content":{"parts":[{"text":"Stream success after retry"}],"role":"model"},"finishReason":"STOP"}]}""");
            }
        }, client -> {
            List<LlmResponseChunk> chunks = new ArrayList<>();
            client.generateStream(userRequest("Hello"), chunks::add);

            assertNotNull(chunks);
            assertEquals(1, chunks.size());
            assertEquals("Stream success after retry", chunks.get(0).content());
            assertEquals(3, attemptCount.get());
        });
    }

    @Test
    void testMockServerStreamFunctionCall() throws Exception {
        withMockServer(exchange -> respondSse(exchange,
            """
                {"candidates":[{"content":{"parts":[{"functionCall":{"name":"search_kb","args":{"query":"test"},"id":"call_999"}}],"role":"model"}}]}"""),
            client -> {
                List<LlmResponseChunk> chunks = new ArrayList<>();
                client.generateStream(userRequest("Hello"), chunks::add);

                assertNotNull(chunks);
                assertEquals(1, chunks.size());
                LlmResponseChunk chunk = chunks.get(0);
                assertNotNull(chunk.toolCallDeltas());
                assertEquals(1, chunk.toolCallDeltas().size());
                assertEquals("search_kb", chunk.toolCallDeltas().get(0).name());
                assertEquals("call_999", chunk.toolCallDeltas().get(0).id());
                assertTrue(chunk.toolCallDeltas().get(0).argumentsDelta().contains("test"));
                assertTrue(chunk.toolCallDeltas().get(0).complete(),
                    "Gemini delivers whole function calls, so the delta must be marked complete");
            });
    }

    @Test
    void testMockServerAbnormalFinishReasonThrowsException() throws Exception {
        withMockServer(exchange -> respondSse(exchange,
            """
                {"candidates":[{"content":{"parts":[{"text":"Abnormal finish"}],"role":"model"},"finishReason":"OTHER"}]}"""),
            client -> {
                RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> client.generateStream(userRequest("Hello"), c -> {
                    }));
                assertTrue(ex.getMessage().contains("finishReason=OTHER"),
                    "An abnormal finish reason during streaming should throw an exception");
            });
    }

    /**
     * An abnormal finish during STREAMING is billed exactly like the blocked non-streaming call
     * that {@link #aBlockedResponseCarriesTheUsageTheProviderAlreadyBilled} pins: the provider read
     * the whole prompt — hundreds of thousands of tokens on the RAG paths — and charged for it.
     *
     * <p>
     * The throw sat ABOVE the {@code usageMetadata} read, and threw a bare {@code RuntimeException}
     * which has nowhere to carry tokens even if the ordering were right. So the counts reached
     * neither a chunk nor the exception, {@code AccountingLlmClient} observed nothing, and its null
     * guard skipped the write: <b>no {@code llm_call_logs} row at all</b> for the most expensive
     * calls in the system. The same file's non-streaming path had already been fixed for exactly
     * this, 180 lines above; this is the streaming half of a rule the codebase had already decided.
     *
     * <p>
     * Note the pre-existing {@code testMockServerAbnormalFinishReasonThrowsException} cannot
     * observe any of this: its fixture carries no {@code usageMetadata}, and it asserts only that
     * the message names the reason — satisfied by an exception carrying no usage at all.
     */
    @Test
    void anAbnormalFinishWhileStreamingCarriesTheUsageTheProviderAlreadyBilled() throws Exception {
        withMockServer(exchange -> respondSse(exchange,
            """
                {"candidates":[{"content":{"parts":[{"text":"partial"}],"role":"model"},"finishReason":"SAFETY"}],"usageMetadata":{"promptTokenCount":216667,"candidatesTokenCount":40,"totalTokenCount":217707,"thoughtsTokenCount":12}}"""),
            client -> {
                LlmCallFailedException ex = assertThrows(LlmCallFailedException.class,
                    () -> client.generateStream(userRequest("Hello"), c -> {
                    }));

                assertNotNull(ex.usage(),
                    "a blocked stream still consumed tokens; dropping the usage loses the whole "
                        + "llm_call_logs row");
                assertEquals(216667, ex.usage().promptTokens(),
                    "the prompt the provider actually read must be billed");
                assertEquals(217707, ex.usage().totalTokens());
                assertTrue(ex.getMessage().contains("finishReason=SAFETY"),
                    "the reason must survive the usage plumbing");
            });
    }

    /**
     * A finish reason this SDK version has never heard of must be abnormal, not benign.
     *
     * <p>
     * {@code FinishReason(String)} (v1.60.0, {@code FinishReason.java:96-107}) falls back
     * <b>unconditionally</b> to {@code Known.FINISH_REASON_UNSPECIFIED} for any unmatched wire
     * value, while {@code toString()} ({@code :116-120}) keeps the raw string. Keying the guard on
     * {@code knownEnum()} therefore cannot tell "the model stopped for a reason we do not know"
     * apart from "unspecified", and the allow-list contains the latter — so every unknown reason
     * was swallowed and the stream logged as completed successfully.
     *
     * <p>
     * {@code TOO_MANY_TOOL_CALLS} is the live instance: absent from the pinned 1.60.0 enum, added
     * in 1.65.0 (commit {@code b18f934}). Today it means the server truncated the turn and the user
     * is handed a partial answer presented as complete; after a routine SDK bump, with no code
     * change at all, the very same response would start throwing instead. Matching the raw string
     * makes the verdict independent of which constants the SDK happens to know, so neither
     * direction can flip underneath us.
     */
    @Test
    void aFinishReasonThisSdkVersionDoesNotKnowIsAbnormalRatherThanBenign() throws Exception {
        withMockServer(exchange -> respondSse(exchange,
            """
                {"candidates":[{"content":{"parts":[{"text":"a partial answer"}],"role":"model"},"finishReason":"TOO_MANY_TOOL_CALLS"}],"usageMetadata":{"promptTokenCount":11,"candidatesTokenCount":4,"totalTokenCount":15}}"""),
            client -> {
                RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> client.generateStream(userRequest("Hello"), c -> {
                    }));
                assertTrue(ex.getMessage().contains("TOO_MANY_TOOL_CALLS"),
                    "the raw reason must be surfaced; collapsing it to FINISH_REASON_UNSPECIFIED "
                        + "is exactly how a truncated turn passes as a complete answer");
            });
    }

    /**
     * The guard rail for the test above: an explicitly sent {@code FINISH_REASON_UNSPECIFIED} is a
     * legitimate value and stays benign. Widening the check must not turn a normal completion into
     * a failure — the mirror-image mistake, and the one that would break every answer rather than
     * none.
     */
    @Test
    void anExplicitlyUnspecifiedFinishReasonStaysBenign() throws Exception {
        withMockServer(exchange -> respondSse(exchange,
            """
                {"candidates":[{"content":{"parts":[{"text":"a complete answer"}],"role":"model"},"finishReason":"FINISH_REASON_UNSPECIFIED"}],"usageMetadata":{"promptTokenCount":11,"candidatesTokenCount":4,"totalTokenCount":15}}"""),
            client -> {
                List<LlmResponseChunk> chunks = new ArrayList<>();
                client.generateStream(userRequest("Hello"), chunks::add);
                assertTrue(chunks.stream().anyMatch(c -> "a complete answer".equals(c.content())),
                    "an unspecified finish reason is a normal completion, not an abnormal one");
            });
    }

    /**
     * A turn that spends its whole budget thinking and says nothing is a failure, not a quiet
     * success — and only the client can tell, because by the time it reaches {@code RagService} the
     * evidence is gone.
     *
     * <p>
     * {@code RagService} deliberately delegates this: its empty-response guard is keyed on the
     * assistant PARTS, and a thought part is a part, so a reasoning-only turn passes it (pinned by
     * {@code RagServiceAgenticTest.aReasoningOnlyTurnWithNoToolCallDoesNotThrow}). The loop then
     * ends with {@code shouldContinue=false}, {@code ChatMessageService} persists the message with
     * status {@code done}, and the UI hides {@code <think>} — so the user is shown a **blank
     * message with no error at all**, and an orchestrated run hands the reviewer an empty draft.
     * {@code AzureOpenAiLlmClient} implements this delegated duty via {@code StreamOutcome}; this
     * client never did.
     *
     * <p>
     * Deliberately NOT retried, unlike the Azure sibling. That client's bounded re-request is safe
     * for one precise reason — "empty" there means nothing whatsoever reached the consumer — and
     * that reason does not hold here: Gemini streams its reasoning, so the {@code <think>} block is
     * already on the user's screen and a second attempt would replay one underneath it.
     *
     * <p>
     * Usage rides the exception for the same reason as
     * {@link #aBlockedResponseCarriesTheUsageTheProviderAlreadyBilled}: these tokens were billed,
     * and throwing without them leaves the call with no {@code llm_call_logs} row at all.
     */
    @Test
    void aThoughtOnlyTurnFailsInsteadOfReportingAnEmptyAnswer() throws Exception {
        withMockServer(exchange -> respondSse(exchange,
            """
                {"candidates":[{"content":{"parts":[{"thought":true,"text":"weighing the options"}],"role":"model"}}]}""",
            """
                {"candidates":[{"content":{"parts":[{"thought":true,"text":" and still weighing"}],"role":"model"},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":137,"candidatesTokenCount":1040,"totalTokenCount":1177,"thoughtsTokenCount":1024}}"""),
            client -> {
                List<LlmResponseChunk> chunks = new ArrayList<>();
                LlmCallFailedException ex = assertThrows(LlmCallFailedException.class,
                    () -> client.generateStream(userRequest("Hello"), chunks::add));

                assertTrue(chunks.stream().allMatch(c -> c.content() == null),
                    "fixture sanity: this turn must carry no answer text, only reasoning");

                assertNotNull(ex.usage(),
                    "a thought-only turn is fully billed; dropping the usage loses the whole "
                        + "llm_call_logs row");
                assertEquals(137, ex.usage().promptTokens());
                assertEquals(1177, ex.usage().totalTokens());
                assertEquals(1024, ex.usage().thoughtTokens(),
                    "the reasoning/answer split is the whole diagnosis and must survive");
                assertTrue(ex.getMessage().contains("finishReason=STOP"),
                    "the finish reason must be named: STOP is what makes this look like success");
            });
    }

    /**
     * The guard rail for the test above: a turn that thinks and then calls a tool has produced
     * something, and that is the shape of nearly every agentic turn. Getting the emptiness rule
     * wrong in this direction would fail every tool-using answer rather than none.
     */
    @Test
    void aThoughtFollowedByAToolCallIsNotAnEmptyTurn() throws Exception {
        withMockServer(exchange -> respondSse(exchange,
            """
                {"candidates":[{"content":{"parts":[{"thought":true,"text":"I should search"}],"role":"model"}}]}""",
            """
                {"candidates":[{"content":{"parts":[{"functionCall":{"id":"call_1","name":"search_corpus","args":{"query":"x"}}}],"role":"model"},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":4,"totalTokenCount":14}}"""),
            client -> {
                List<LlmResponseChunk> chunks = new ArrayList<>();
                client.generateStream(userRequest("Hello"), chunks::add);
                assertTrue(
                    chunks.stream()
                        .anyMatch(c -> c.toolCallDeltas() != null && !c.toolCallDeltas().isEmpty()),
                    "the tool call must reach the caller, not be swallowed as an empty turn");
            });
    }

    @Test
    void testMockServerStreamSdkDeserializationErrorPropagates() throws Exception {
        // A chunk the SDK cannot deserialize must surface as a stream error (not hang / not be
        // swallowed). The error originates in the SDK's ResponseStream iterator, mid-stream.
        withMockServer(exchange -> respondSse(exchange, """
            {"candidates":[]}""", """
            {"candidates":[{"content":{"parts":[{"thought":true,"text":null}]}}]}"""), client -> {
            assertThrows(RuntimeException.class,
                () -> client.generateStream(userRequest("Hello"), c -> {
                }));
        });
    }

    /**
     * {@code generateStream} retries the <em>establishment</em> of the stream and nothing after it.
     * The asymmetry is the point: a failure before the first chunk is emitted is invisible to the
     * caller and safe to repeat, while a failure after it cannot be repeated, because a chunk that
     * has already been handed to {@code onChunk} cannot be un-handed. On the live path that
     * consumer is {@code CitationStreamingFilter} feeding an {@code SseEmitter}, so every replayed
     * chunk has already been written to the user's browser and appended to the message being
     * persisted. Wrapping the consumption loop in the retry — the natural-looking tidy-up, since
     * the retry is right there and "the whole operation" reads better than "just the handshake" —
     * makes a mid-stream 503 duplicate the answer's opening on screen and in
     * {@code messages.content}, and re-uploads the entire prompt into the quota that just refused
     * it.
     *
     * <p>
     * The mid-stream failure is a 503 error event on purpose. {@code LlmRetry.isTransient} treats
     * an {@code ApiException} with code 503 as retryable, so this fixture is one the mutated code
     * <em>would</em> retry — a fixture that happens to be non-transient (a malformed chunk, say)
     * would not distinguish the two implementations at all and the test would pass against both.
     * Asserting the server hit count as well as the chunk count matters for the same reason: the
     * duplicate delivery and the duplicate request are separate halves of the damage.
     *
     * <p>
     * {@code testMockServerStreamEstablishmentRetry} pins the other half of the rule (a 503 before
     * the stream opens IS retried), and the two only mean something together — either one alone is
     * satisfied by an implementation that retries everywhere, or nowhere.
     */
    @Test
    void aMidStreamFailureIsNotRetriedAndDoesNotReplayChunks() throws Exception {
        AtomicInteger requestCount = new AtomicInteger(0);

        withMockServer(exchange -> {
            requestCount.incrementAndGet();
            respondSse(exchange,
                """
                    {"candidates":[{"content":{"parts":[{"text":"first half of the answer"}],"role":"model"}}]}""",
                """
                    {"error":{"code":503,"status":"UNAVAILABLE","message":"backend overloaded mid-stream"}}""");
        }, client -> {
            List<LlmResponseChunk> chunks = new ArrayList<>();

            RuntimeException ex = assertThrows(RuntimeException.class,
                () -> client.generateStream(userRequest("Hello"), chunks::add));

            assertTrue(ex.getMessage().contains("503"),
                "the mid-stream provider error must reach the caller, not be swallowed");
            assertEquals(1, requestCount.get(),
                "a failure AFTER the stream was established must not re-issue the request");
            assertEquals(1, chunks.size(),
                "the chunk already handed to the consumer must not be delivered a second time");
            assertEquals("first half of the answer", chunks.get(0).content());
        });
    }

    @Test
    void testMockServerToolResponseSerializedAsUserRole() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();

        withMockServer(exchange -> {
            receivedBody.set(readBody(exchange));
            respondJson(exchange, 200, """
                {"candidates":[{"content":{"parts":[{"text":"Ok"}]}}]}""");
        }, client -> {
            LlmMessage toolMsg =
                new LlmMessage("tool", "the result", null, null, "call_1", "search_kb");
            LlmRequest request = new LlmRequest("gemini-1.5-flash",
                List.of(new LlmMessage("user", "Hi"), toolMsg), 0.0, 100, List.of());
            client.generate(request);

            String body = receivedBody.get();
            assertTrue(body.contains("functionResponse"),
                "tool message must serialize as a functionResponse part");
            assertTrue(body.contains("search_kb"),
                "functionResponse must carry the real tool name, not the synthetic call id");
            assertFalse(body.contains("\"role\":\"function\""),
                "tool turns must use role 'user', not the non-standard 'function' role");
        });
    }

    @Test
    void testMockServerToolDeclarationPreservesRawSchema() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();

        withMockServer(exchange -> {
            receivedBody.set(readBody(exchange));
            respondJson(exchange, 200, """
                {"candidates":[{"content":{"parts":[{"text":"Ok"}]}}]}""");
        }, client -> {
            LlmTool tool =
                new LlmTool("search_kb", "search the KB", Map.of("type", "object", "properties",
                    Map.of("query", Map.of("type", "string")), "additionalProperties", false));
            LlmRequest request = new LlmRequest("gemini-1.5-flash",
                List.of(new LlmMessage("user", "Hi")), 0.0, 100, List.of(tool));
            client.generate(request);

            String body = receivedBody.get();
            assertTrue(body.contains("search_kb"), "tool declaration must be sent");
            assertTrue(body.contains("additionalProperties"),
                "raw JSON schema must be preserved on the wire (parametersJsonSchema, lossless)");
        });
    }

    @Test
    void testGenerateHandlesMalformedMessageInputs() throws Exception {
        withMockServer(exchange -> respondJson(exchange, 200, """
            {"candidates":[{"content":{"parts":[{"text":"Ok"}]}}]}"""), client -> {
            LlmToolCall invalidTc =
                new LlmToolCall("call_1", "function", "my_tool", "{invalid-json}", null);

            // 1. Invalid tool-call JSON inside a part: client logs & drops the call; request
            // proceeds.
            LlmMessage msg1 = new LlmMessage("user", null,
                List.of(new LlmPart("function_call", null, invalidTc, null)), null, null, null);
            assertEquals("Ok",
                client
                    .generate(
                        new LlmRequest("gemini-1.5-flash", List.of(msg1), 0.0, 100, List.of()))
                    .content());

            // 2. Invalid tool-call JSON with nothing else: contents end up empty -> guard throws.
            LlmMessage msg2 = new LlmMessage("user", null, null, List.of(invalidTc), null, null);
            assertThrows(IllegalArgumentException.class, () -> client
                .generate(new LlmRequest("gemini-1.5-flash", List.of(msg2), 0.0, 100, List.of())));

            // 3. Invalid base64 thought signature: logged & skipped; request still succeeds.
            LlmMessage msg3 = new LlmMessage("user", null,
                List.of(new LlmPart("text", "Hello", null, "!!!invalid-base64!!!")), null, null,
                null);
            assertEquals("Ok",
                client
                    .generate(
                        new LlmRequest("gemini-1.5-flash", List.of(msg3), 0.0, 100, List.of()))
                    .content());
        });
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /** A test body that receives a ready, base-URL-bound client and may throw. */
    @FunctionalInterface
    private interface ClientCallback {
        void accept(GeminiLlmClient client) throws Exception;
    }

    /** Builds config through a throwaway client, ensuring the client is always closed. */
    private static GenerateContentConfig buildConfig(boolean enableThinking, String thinkingLevel,
        LlmRequest request) {
        try (GeminiLlmClient client =
            new GeminiLlmClient("dummy-key", new ObjectMapper(), enableThinking, thinkingLevel)) {
            return client.buildConfig(request);
        }
    }

    /**
     * Starts an ephemeral-port mock HTTP server with the given handler, runs the test body against
     * a client pointed at it, and guarantees both the client and the server are closed afterwards.
     */
    private static final Logger log = LoggerFactory.getLogger(GeminiLlmClientTest.class);

    private static void withMockServer(HttpHandler handler, ClientCallback test) throws Exception {
        withMockServer(handler, GeminiLlmClient.HTTP_TIMEOUT_MS, test);
    }

    /** As above, with a short read timeout so a liveness bound can be proven in milliseconds. */
    private static void withMockServer(HttpHandler handler, int readTimeoutMs, ClientCallback test)
        throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                byte[] bodyBytes;
                try (InputStream is = exchange.getRequestBody()) {
                    bodyBytes = is.readAllBytes();
                }
                exchange.setAttribute("cachedRequestBody", bodyBytes);
                handler.handle(exchange);
            } catch (Exception e) {
                log.error("Error in mock server handler", e);
                byte[] err = (e.getMessage() != null ? e.getMessage() : "Error")
                    .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, err.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(err);
                }
            }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            try (GeminiLlmClient client =
                new GeminiLlmClient("mock-api-key", new ObjectMapper(), false, null, baseUrl, null,
                    ClientOptions.builder()
                        .customHttpClient(GeminiLlmClient.httpClientBuilder(readTimeoutMs).build())
                        .build())) {
                test.accept(client);
            }
        } finally {
            server.stop(0);
        }
    }

    /**
     * A healthy generation must survive for longer than the timeout budget, as long as it keeps
     * producing. This is the constraint the previous configuration got backwards.
     *
     * <p>
     * {@code HttpOptions.timeout()} became an OkHttp {@code callTimeout}, which spans the entire
     * call including draining the SSE body — so it was a hard wall-clock cap on how long an answer
     * could take, not a liveness check. A stream emitting a token a second, never idle, died at
     * 300s with {@code InterruptedIOException: timeout}; {@code RagService} does not catch around
     * {@code generateStream}, so the whole agentic run died and every prior turn's tool results
     * went with it. The bound is now the socket read timeout, which asks the only question worth
     * asking: has anything arrived recently?
     *
     * <p>
     * Total stream time here is roughly 3x the budget while no single gap approaches it, so this
     * test passes only if the bound is per-read and fails if it is per-call.
     */
    @Test
    void aStreamSlowerOverallThanTheBudgetSurvivesWhileItKeepsProducing() throws Exception {
        withMockServer(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                for (String text : List.of("a", "b", "c")) {
                    os.write(("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + text
                        + "\"}],\"role\":\"model\"}}]}\n\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    sleepQuietly(350);
                }
                os.write(("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"!\"}],"
                    + "\"role\":\"model\"},\"finishReason\":\"STOP\"}]}\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            }
        }, 900, client -> {
            StringBuilder seen = new StringBuilder();
            client.generateStream(userRequest("Hello"), chunk -> {
                if (chunk.content() != null) {
                    seen.append(chunk.content());
                }
            });
            assertEquals("abc!", seen.toString(),
                "no single gap came close to the budget, so the whole answer must arrive");
        });
    }

    /** The other half of the same bound: a service that goes silent must not hang the caller. */
    @Test
    @Timeout(30)
    void aServiceThatGoesSilentFailsRatherThanHanging() throws Exception {
        withMockServer(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().flush();
            sleepQuietly(20_000);
        }, 400, client -> assertThrows(RuntimeException.class,
            () -> client.generateStream(userRequest("Hello"), c -> {
            })));
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static LlmRequest userRequest(String text) {
        return new LlmRequest("gemini-1.5-flash", List.of(new LlmMessage("user", text)), 0.0, 100,
            List.of());
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        byte[] cached = (byte[]) exchange.getAttribute("cachedRequestBody");
        if (cached != null) {
            return new String(cached, StandardCharsets.UTF_8);
        }
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void respondJson(HttpExchange exchange, int status, String json)
        throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** Writes each JSON payload as one {@code data: ...} SSE event. */
    private static void respondSse(HttpExchange exchange, String... jsonPayloads)
        throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream os = exchange.getResponseBody()) {
            for (String payload : jsonPayloads) {
                os.write(("data: " + payload.strip() + "\n\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        }
    }
}
