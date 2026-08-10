package de.palsoftware.yvoke.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;
import de.palsoftware.yvoke.llm.core.context.LlmCallContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import de.palsoftware.yvoke.llm.core.event.LlmCallLoggedEvent;
import org.springframework.context.ApplicationEventPublisher;
import java.util.ArrayList;
import org.mockito.Mockito;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.HttpServerErrorException;

public class EmbeddingServiceTest {

    private MockRestServiceServer server;
    private EmbeddingService embeddingService;

    @BeforeEach
    public void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        embeddingService = new EmbeddingService(builder, "mock-key-for-skeleton", "voyage-4-large",
            "https://api.voyageai.com/v1");
    }

    @Test
    public void testEmbedSuccess() {
        String jsonResponse = """
            {
              "object": "list",
              "data": [
                {
                  "object": "embedding",
                  "embedding": [0.1, 0.2, 0.3],
                  "index": 0
                }
              ],
              "model": "voyage-4-large",
              "usage": {
                "total_tokens": 4
              }
            }
            """;

        server.expect(requestTo("https://api.voyageai.com/v1/embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer mock-key-for-skeleton"))
            .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

        float[] result = embeddingService.embed("OIM");

        assertThat(result).containsExactly(0.1f, 0.2f, 0.3f);
        server.verify();
    }

    @Test
    public void testEmbedBatchSuccess() {
        String jsonResponse = """
            {
              "object": "list",
              "data": [
                {
                  "object": "embedding",
                  "embedding": [0.4, 0.5],
                  "index": 1
                },
                {
                  "object": "embedding",
                  "embedding": [0.1, 0.2],
                  "index": 0
                }
              ],
              "model": "voyage-4-large",
              "usage": {
                "total_tokens": 10
              }
            }
            """;

        server.expect(requestTo("https://api.voyageai.com/v1/embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

        List<float[]> results = embeddingService.embedBatch(List.of("first", "second"));

        assertThat(results).hasSize(2);
        // Elements should be sorted by index
        assertThat(results.get(0)).containsExactly(0.1f, 0.2f);
        assertThat(results.get(1)).containsExactly(0.4f, 0.5f);
        server.verify();
    }

    @Test
    public void testEmbedInvalidInput() {
        assertThatThrownBy(() -> embeddingService.embed(""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> embeddingService.embed((String) null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> embeddingService.embedBatch(null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> embeddingService.embedBatch(List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testEmbedDocument() {
        String jsonResponse = """
            {
              "object": "list",
              "data": [
                {
                  "object": "embedding",
                  "embedding": [0.7, 0.8],
                  "index": 0
                }
              ],
              "model": "voyage-4-large",
              "usage": {"total_tokens": 2}
            }
            """;

        server.expect(requestTo("https://api.voyageai.com/v1/embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

        float[] result = embeddingService.embed(new Document("doc text"));
        assertThat(result).containsExactly(0.7f, 0.8f);
        server.verify();
    }

    @Test
    public void testEmbedNullDocument() {
        assertThatThrownBy(() -> embeddingService.embed((Document) null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testEmbeddingRequestCall() {
        String jsonResponse = """
            {
              "object": "list",
              "data": [
                {
                  "object": "embedding",
                  "embedding": [0.9, 0.1],
                  "index": 0
                }
              ],
              "model": "voyage-4-large",
              "usage": {"total_tokens": 2}
            }
            """;

        server.expect(requestTo("https://api.voyageai.com/v1/embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

        EmbeddingResponse response =
            embeddingService.call(new EmbeddingRequest(List.of("call text"), null));

        assertThat(response).isNotNull();
        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getOutput()).containsExactly(0.9f, 0.1f);
        server.verify();
    }

    @Test
    public void testEmbedServerError() {
        server.expect(requestTo("https://api.voyageai.com/v1/embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(MockRestResponseCreators.withServerError());

        assertThatThrownBy(() -> embeddingService.embed("error query"))
            .isInstanceOf(HttpServerErrorException.class);
        server.verify();
    }

    @Test
    public void testEmbedEmptyResponseBody() {
        server.expect(requestTo("https://api.voyageai.com/v1/embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> embeddingService.embed("empty query"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Failed to get embeddings from Voyage AI; empty response.");
        server.verify();
    }

    @Test
    public void testEmbedBatchChunkingSplitsLargeBatch() {
        // Create texts exceeding MAX_BATCH_SIZE (128)
        int totalTexts = EmbeddingService.MAX_BATCH_SIZE + 5; // 133 texts
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < totalTexts; i++) {
            texts.add("text_" + i);
        }

        // First batch: 128 items
        StringBuilder batch1Json = new StringBuilder();
        batch1Json.append("{\"object\":\"list\",\"data\":[");
        for (int i = 0; i < EmbeddingService.MAX_BATCH_SIZE; i++) {
            if (i > 0)
                batch1Json.append(",");
            batch1Json.append(String.format(
                "{\"object\":\"embedding\",\"embedding\":[%d.0,%d.1],\"index\":%d}", i, i, i));
        }
        batch1Json.append("],\"model\":\"voyage-4-large\",\"usage\":{\"total_tokens\":100}}");

        // Second batch: 5 items
        StringBuilder batch2Json = new StringBuilder();
        batch2Json.append("{\"object\":\"list\",\"data\":[");
        for (int i = 0; i < 5; i++) {
            if (i > 0)
                batch2Json.append(",");
            batch2Json.append(
                String.format("{\"object\":\"embedding\",\"embedding\":[%d.0,%d.1],\"index\":%d}",
                    200 + i, 200 + i, i));
        }
        batch2Json.append("],\"model\":\"voyage-4-large\",\"usage\":{\"total_tokens\":10}}");

        server.expect(requestTo("https://api.voyageai.com/v1/embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(batch1Json.toString(), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.voyageai.com/v1/embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(batch2Json.toString(), MediaType.APPLICATION_JSON));

        List<float[]> results = embeddingService.embedBatch(texts);

        assertThat(results).hasSize(totalTexts);
        // Verify first batch results
        assertThat(results.get(0)[0]).isEqualTo(0.0f);
        // Verify second batch results start at position 128
        assertThat(results.get(EmbeddingService.MAX_BATCH_SIZE)[0]).isEqualTo(200.0f);
        server.verify();
    }

    /**
     * {@code embedBatch} splits on TWO independent budgets — at most 128 texts, and at most ~400k
     * characters per request. Only the count budget has ever been tested.
     *
     * <p>
     * The character budget is the one that actually fires in this corpus. A hierarchical section or
     * a Confluence page with tables runs to tens of thousands of characters, so a batch reaches the
     * provider's payload/token ceiling long before it reaches 128 items — the count check alone
     * would happily post a single multi-megabyte request. What comes back is not a clean failure
     * this class would surface either: the provider rejects the batch, {@code doEmbedBatch} throws,
     * and the whole ingest job dies at the embed step with a transport error that says nothing
     * about size — for the largest, most valuable documents in the corpus, and only for those, so
     * it reads as "that document is cursed" rather than "the batch was too big".
     *
     * <p>
     * Order is asserted alongside the split because the two are the same guarantee. The caller
     * ({@code persistDocument}) zips the returned vectors against its sections BY INDEX, so a split
     * that returns the batches out of order, or drops the per-response index sort, attaches every
     * vector to the wrong chunk. Nothing fails: the count still matches, the job completes, and the
     * corpus simply retrieves the wrong passages forever. The second response below deliberately
     * lists index 1 before index 0 so the per-batch sort is exercised rather than assumed.
     *
     * <p>
     * {@code testEmbedBatchChunkingSplitsLargeBatch} uses 133 short texts and
     * {@code testEmbedBatchNoChunkingWithinLimit} exactly 128, so both stay far inside the
     * character budget: the entire clause can be deleted and both remain green.
     */
    @Test
    public void embedBatchSplitsOnTheCharacterBudgetNotOnlyOnTheBatchSize() {
        // Four texts — nowhere near MAX_BATCH_SIZE — but 600k characters in total, so the split can
        // only come from the character budget.
        List<String> texts = List.of("a".repeat(150_000), "b".repeat(150_000), "c".repeat(150_000),
            "d".repeat(150_000));

        String firstBatch = "{\"object\":\"list\",\"data\":["
            + "{\"object\":\"embedding\",\"embedding\":[1.0],\"index\":0},"
            + "{\"object\":\"embedding\",\"embedding\":[2.0],\"index\":1}"
            + "],\"model\":\"voyage-4-large\",\"usage\":{\"total_tokens\":100}}";
        // Out of index order on purpose: the response order must not become the result order.
        String secondBatch = "{\"object\":\"list\",\"data\":["
            + "{\"object\":\"embedding\",\"embedding\":[4.0],\"index\":1},"
            + "{\"object\":\"embedding\",\"embedding\":[3.0],\"index\":0}"
            + "],\"model\":\"voyage-4-large\",\"usage\":{\"total_tokens\":100}}";

        server.expect(requestTo("https://api.voyageai.com/v1/embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(firstBatch, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.voyageai.com/v1/embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(secondBatch, MediaType.APPLICATION_JSON));

        List<float[]> results = embeddingService.embedBatch(texts);

        // Two requests, not one: server.verify() fails on an unconsumed expectation.
        server.verify();
        assertThat(results).hasSize(4);
        assertThat(results.get(0)).containsExactly(1.0f);
        assertThat(results.get(1)).containsExactly(2.0f);
        assertThat(results.get(2)).containsExactly(3.0f);
        assertThat(results.get(3)).containsExactly(4.0f);
    }

    @Test
    public void testEmbedBatchNoChunkingWithinLimit() {
        // Exactly MAX_BATCH_SIZE texts — should be a single API call
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < EmbeddingService.MAX_BATCH_SIZE; i++) {
            texts.add("text_" + i);
        }

        StringBuilder json = new StringBuilder();
        json.append("{\"object\":\"list\",\"data\":[");
        for (int i = 0; i < EmbeddingService.MAX_BATCH_SIZE; i++) {
            if (i > 0)
                json.append(",");
            json.append(String
                .format("{\"object\":\"embedding\",\"embedding\":[%d.0],\"index\":%d}", i, i));
        }
        json.append("],\"model\":\"voyage-4-large\",\"usage\":{\"total_tokens\":100}}");

        server.expect(requestTo("https://api.voyageai.com/v1/embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(json.toString(), MediaType.APPLICATION_JSON));

        List<float[]> results = embeddingService.embedBatch(texts);

        assertThat(results).hasSize(EmbeddingService.MAX_BATCH_SIZE);
        server.verify(); // Only 1 request expected
    }

    @Test
    public void testEmbed_publishesModelUsageEvent() {
        ApplicationEventPublisher mockPublisher = Mockito.mock(ApplicationEventPublisher.class);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        EmbeddingService serviceWithPublisher = new EmbeddingService(builder, "mock-key",
            "voyage-4-large", "https://api.voyageai.com/v1", mockPublisher);

        String jsonResponse = """
            {
              "object": "list",
              "data": [
                {
                  "object": "embedding",
                  "embedding": [0.1, 0.2],
                  "index": 0
                }
              ],
              "model": "voyage-4-large",
              "usage": {
                "total_tokens": 12
              }
            }
            """;

        mockServer.expect(requestTo("https://api.voyageai.com/v1/embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

        float[] result = serviceWithPublisher.embed("test search query");

        assertThat(result).containsExactly(0.1f, 0.2f);
        mockServer.verify();

        Mockito.verify(mockPublisher).publishEvent(
            new LlmCallLoggedEvent("embedding", "embedding", "voyage-4-large", 12, 0, 0, 0));
    }

    /**
     * Voyage does not always return a {@code usage} block, and the embedding rows this event
     * creates in {@code llm_call_logs} are what the cost dashboard prices. A call logged with zero
     * tokens is a call the dashboard values at $0 — so a corpus re-embed, which is hundreds of
     * thousands of chunks and one of the larger line items in the month, would silently vanish from
     * every report while the invoice does not. There is no error and no gap in the data to notice:
     * the rows are all there, just free.
     *
     * <p>
     * The estimate is deliberately per-text and floored at one token rather than computed over the
     * concatenated batch, because integer division would otherwise round a batch of short strings
     * (titles, headings, entity names — a large share of what this corpus embeds) down to zero and
     * reproduce the same $0 outcome by a different route. Every sibling test in this file feeds a
     * response that DOES carry {@code usage.total_tokens}, so the fallback branch is never executed
     * by the suite today and could be deleted outright without a single test going red.
     */
    @Test
    public void embeddingUsageFallsBackToACharacterEstimateWhenVoyageOmitsUsage() {
        ApplicationEventPublisher mockPublisher = mock(ApplicationEventPublisher.class);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        EmbeddingService serviceWithPublisher = new EmbeddingService(builder, "mock-key",
            "voyage-4-large", "https://api.voyageai.com/v1", mockPublisher);

        // No "usage" member at all — the shape that triggers the estimate.
        String jsonResponse = """
            {
              "object": "list",
              "data": [
                {"object": "embedding", "embedding": [0.1, 0.2], "index": 0},
                {"object": "embedding", "embedding": [0.3, 0.4], "index": 1}
              ],
              "model": "voyage-4-large"
            }
            """;

        mockServer.expect(requestTo("https://api.voyageai.com/v1/embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

        LlmCallContextHolder.clear();
        List<float[]> results = serviceWithPublisher.embedBatch(List.of("abcdefgh", "ab"));

        assertThat(results).hasSize(2);
        mockServer.verify();

        // "abcdefgh" -> 8 / 4 = 2; "ab" -> max(1, 0) = 1. Three prompt tokens, nothing else billed.
        verify(mockPublisher).publishEvent(
            new LlmCallLoggedEvent("embedding", "embedding", "voyage-4-large", 3, 0, 0, 0));
    }

    @Test
    public void testEmbed_publishesModelUsageEventWithContext() {
        ApplicationEventPublisher mockPublisher = Mockito.mock(ApplicationEventPublisher.class);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        EmbeddingService serviceWithPublisher = new EmbeddingService(builder, "mock-key",
            "voyage-4-large", "https://api.voyageai.com/v1", mockPublisher);

        String jsonResponse = """
            {
              "object": "list",
              "data": [
                {
                  "object": "embedding",
                  "embedding": [0.1, 0.2],
                  "index": 0
                }
              ],
              "model": "voyage-4-large",
              "usage": {
                "total_tokens": 12
              }
            }
            """;

        mockServer.expect(requestTo("https://api.voyageai.com/v1/embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

        UUID convId = UUID.randomUUID();
        UUID msgId = UUID.randomUUID();
        try {
            LlmCallContextHolder.set(convId, msgId, null, null, "chat", "assistant");
            float[] result = serviceWithPublisher.embed("test search query");
            assertThat(result).containsExactly(0.1f, 0.2f);
        } finally {
            LlmCallContextHolder.clear();
        }

        mockServer.verify();

        Mockito.verify(mockPublisher).publishEvent(new LlmCallLoggedEvent(convId, msgId, null, null,
            "embedding", "embedding", "voyage-4-large", 12, 0, 0, 0));
    }
}
