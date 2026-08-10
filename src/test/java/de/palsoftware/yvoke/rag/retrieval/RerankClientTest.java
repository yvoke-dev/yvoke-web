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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import de.palsoftware.yvoke.llm.core.event.LlmCallLoggedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.mockito.Mockito;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.HttpServerErrorException;

public class RerankClientTest {

    private MockRestServiceServer server;
    private RerankClient rerankClient;

    @BeforeEach
    public void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        rerankClient = new RerankClient(builder, "mock-key-for-skeleton", "rerank-2.5",
            "https://api.voyageai.com/v1");
    }

    @Test
    public void testRerankSuccess() {
        String jsonResponse = """
            {
              "object": "list",
              "data": [
                {
                  "index": 0,
                  "relevance_score": 0.95
                },
                {
                  "index": 1,
                  "relevance_score": 0.12
                }
              ],
              "model": "rerank-2.5",
              "usage": {
                "total_tokens": 15
              }
            }
            """;

        server.expect(requestTo("https://api.voyageai.com/v1/rerank"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer mock-key-for-skeleton"))
            .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

        List<RerankClient.RerankResult> results =
            rerankClient.rerank("query", List.of("Paris is capital", "Berlin is capital"));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).index()).isEqualTo(0);
        assertThat(results.get(0).relevanceScore()).isEqualTo(0.95);
        assertThat(results.get(1).index()).isEqualTo(1);
        assertThat(results.get(1).relevanceScore()).isEqualTo(0.12);
        server.verify();
    }

    @Test
    public void testRerankEmptyDocuments() {
        List<RerankClient.RerankResult> results = rerankClient.rerank("query", List.of());
        assertThat(results).isEmpty();
    }

    @Test
    public void testRerankInvalidInput() {
        assertThatThrownBy(() -> rerankClient.rerank("", List.of("doc")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rerankClient.rerank(null, List.of("doc")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testRerankServerError() {
        server.expect(requestTo("https://api.voyageai.com/v1/rerank"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(MockRestResponseCreators.withServerError());

        assertThatThrownBy(() -> rerankClient.rerank("query", List.of("doc")))
            .isInstanceOf(HttpServerErrorException.class);
        server.verify();
    }

    @Test
    public void testRerankEmptyResponseBody() {
        server.expect(requestTo("https://api.voyageai.com/v1/rerank"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> rerankClient.rerank("query", List.of("doc")))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining(
                "Failed to get reranking response from Voyage AI; empty response.");
        server.verify();
    }

    /**
     * Rerank is billed on the query <em>and</em> every document it scores, and the pool handed to
     * it is the fused RRF candidate set — routinely tens of chunks of full text, so the documents
     * are three or four orders of magnitude larger than the query. When the provider omits its
     * {@code usage} block (Voyage does not always send one, and a gateway or proxy in front of it
     * may strip it), the estimate is all the ledger will ever have for that call. Estimate from the
     * query alone and every such call lands in {@code llm_call_logs} as a couple of tokens: rerank
     * disappears from the cost dashboard, and the retrieval knob with the highest per-search cost
     * looks free — precisely the number an operator would use to decide whether to leave reranking
     * on. The {@code max(1, ...)} floor matters for the same reason: a zero-token row reads as a
     * call that cost nothing rather than one whose usage was unknown. No existing test reaches this
     * branch — {@code testRerank_publishesModelUsageEvent} responds with an explicit
     * {@code "total_tokens": 15}, so the fallback arithmetic has never executed.
     */
    @Test
    public void rerankUsageFallsBackToACharacterEstimateOverQueryAndDocuments() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer usageLessServer = MockRestServiceServer.bindTo(builder).build();
        RerankClient client = new RerankClient(builder, "mock-key", "rerank-2.5",
            "https://api.voyageai.com/v1", publisher);

        // Deliberately no "usage" block: this is the shape that forces the character estimate.
        String jsonResponse = """
            {
              "object": "list",
              "data": [
                {
                  "index": 0,
                  "relevance_score": 0.9
                }
              ],
              "model": "rerank-2.5"
            }
            """;

        usageLessServer.expect(requestTo("https://api.voyageai.com/v1/rerank"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

        // query 8 chars + two 96-char documents = 200 chars -> max(1, 200 / 4) = 50 tokens.
        // Estimating from the query alone would publish 2, which is the regression this pins.
        List<String> documents = List.of("x".repeat(96), "y".repeat(96));

        assertThat(client.rerank("identity", documents)).hasSize(1);
        usageLessServer.verify();

        verify(publisher)
            .publishEvent(new LlmCallLoggedEvent("rerank", "rerank", "rerank-2.5", 50, 0, 0, 0));
    }

    @Test
    public void testRerank_publishesModelUsageEvent() {
        ApplicationEventPublisher mockPublisher = Mockito.mock(ApplicationEventPublisher.class);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        RerankClient clientWithPublisher = new RerankClient(builder, "mock-key", "rerank-2.5",
            "https://api.voyageai.com/v1", mockPublisher);

        String jsonResponse = """
            {
              "object": "list",
              "data": [
                {
                  "object": "rerank",
                  "relevance_score": 0.9,
                  "index": 0
                }
              ],
              "model": "rerank-2.5",
              "usage": {
                "total_tokens": 15
              }
            }
            """;

        mockServer.expect(requestTo("https://api.voyageai.com/v1/rerank"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

        List<RerankClient.RerankResult> results =
            clientWithPublisher.rerank("query", List.of("doc1"));

        assertThat(results).hasSize(1);
        mockServer.verify();

        Mockito.verify(mockPublisher)
            .publishEvent(new LlmCallLoggedEvent("rerank", "rerank", "rerank-2.5", 15, 0, 0, 0));
    }
}
