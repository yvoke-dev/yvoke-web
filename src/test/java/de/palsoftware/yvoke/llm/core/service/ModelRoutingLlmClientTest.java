package de.palsoftware.yvoke.llm.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.withSettings;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.llm.core.LlmModelRoutes;
import de.palsoftware.yvoke.llm.core.LlmRouteId;
import de.palsoftware.yvoke.llm.core.model.LlmMessage;
import de.palsoftware.yvoke.llm.core.model.LlmRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Routing sits BELOW the accounting seam — it is the {@code llmProviderClient} bean — so every
 * routed call still passes through {@code AccountingLlmClient} and appears in
 * {@code llm_call_logs}.
 *
 * <p>
 * The map value is a bare {@link LlmClient} on purpose. {@code AccountingLlmClient} is this
 * codebase's own proof that a decorator over that interface composes, so a later failover or
 * load-balancing client drops into the same slot without this class learning anything.
 */
class ModelRoutingLlmClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** {@code {"model": "route", ...}} — the shape of app.ai.model-routes. */
    private static LlmModelRoutes routes(String... modelToRoute) {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < modelToRoute.length; i += 2) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(modelToRoute[i]).append("\":\"").append(modelToRoute[i + 1])
                .append('"');
        }
        return LlmModelRoutes.parse(MAPPER, json.append('}').toString());
    }

    private static LlmRequest requestFor(String model) {
        return new LlmRequest(model, List.of(new LlmMessage("user", "hi")), 0.0, 100, List.of(),
            null);
    }

    private static LlmClient closeableClient() {
        return mock(LlmClient.class, withSettings().extraInterfaces(AutoCloseable.class));
    }

    @Test
    void aRoutedModelReachesItsOwnClientOnBothMethods() {
        LlmClient fallback = mock(LlmClient.class);
        LlmClient azure = mock(LlmClient.class);
        ModelRoutingLlmClient router =
            new ModelRoutingLlmClient(fallback, routes("gpt-5.6-luna", "azure-openai-responses"),
                Map.of(LlmRouteId.AZURE_OPENAI_RESPONSES, azure));

        LlmRequest request = requestFor("gpt-5.6-luna");
        router.generate(request);
        router.generateStream(request, c -> {
        });

        verify(azure).generate(same(request));
        verify(azure).generateStream(same(request), any());
        verifyNoInteractions(fallback);
    }

    @Test
    void anUnroutedModelReachesTheDefaultClientOnBothMethods() {
        LlmClient fallback = mock(LlmClient.class);
        LlmClient azure = mock(LlmClient.class);
        ModelRoutingLlmClient router =
            new ModelRoutingLlmClient(fallback, routes("gpt-5.6-luna", "azure-openai-responses"),
                Map.of(LlmRouteId.AZURE_OPENAI_RESPONSES, azure));

        LlmRequest request = requestFor("gemini-3.6-flash");
        router.generate(request);
        router.generateStream(request, c -> {
        });

        verify(fallback).generate(same(request));
        verify(fallback).generateStream(same(request), any());
        verifyNoInteractions(azure);
    }

    /**
     * The provider needs the caller's exact spelling — an Azure deployment name is operator-chosen
     * free text — so the lookup may be case-insensitive but the forwarded request must not be
     * rewritten. {@code same} rather than {@code equals}: a rebuilt-but-equal request would pass an
     * equality check while proving nothing about what the provider is handed.
     */
    @Test
    void theRequestIsForwardedWithoutBeingRewritten() {
        LlmClient azure = mock(LlmClient.class);
        ModelRoutingLlmClient router = new ModelRoutingLlmClient(mock(LlmClient.class),
            routes("gpt-5.6-luna", "azure-openai-responses"),
            Map.of(LlmRouteId.AZURE_OPENAI_RESPONSES, azure));

        LlmRequest mixedCase = requestFor("GPT-5.6-Luna");
        router.generate(mixedCase);

        verify(azure).generate(same(mixedCase));
        assertThat(mixedCase.model()).isEqualTo("GPT-5.6-Luna");
    }

    @Test
    void aNullOrBlankModelGoesToTheDefaultRatherThanFailing() {
        LlmClient fallback = mock(LlmClient.class);
        ModelRoutingLlmClient router =
            new ModelRoutingLlmClient(fallback, routes("gpt-5.6-luna", "azure-openai-responses"),
                Map.of(LlmRouteId.AZURE_OPENAI_RESPONSES, mock(LlmClient.class)));

        assertThat(router.clientFor(null)).isSameAs(fallback);
        assertThat(router.clientFor("  ")).isSameAs(fallback);
    }

    /** Nothing may be called on a provider client just because the router was built. */
    @Test
    void constructionTouchesNoDelegate() {
        LlmClient fallback = mock(LlmClient.class);
        LlmClient azure = mock(LlmClient.class);

        new ModelRoutingLlmClient(fallback, routes("m", "azure-openai-responses"),
            Map.of(LlmRouteId.AZURE_OPENAI_RESPONSES, azure));

        verifyNoInteractions(fallback, azure);
    }

    /**
     * A declared route with no client is a configuration bug that would otherwise surface as a
     * {@code NullPointerException} on the first question asked about that model.
     */
    @Test
    void aDeclaredRouteWithNoClientFailsAtConstruction() {
        assertThatThrownBy(() -> new ModelRoutingLlmClient(mock(LlmClient.class),
            routes("gpt-5.6-luna", "azure-openai-responses"), Map.of()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("azure-openai-responses");
    }

    /**
     * The router is the bean, so the clients it wraps are not managed by Spring and would never be
     * closed otherwise. The default client is routinely ALSO a route target — default
     * {@code gemini} plus a model pinned to {@code gemini} is the ordinary case — so closing by
     * identity rather than by map entry is what stops one client being closed twice.
     */
    @Test
    void closeReachesEachDistinctDelegateExactlyOnce() throws Exception {
        LlmClient gemini = closeableClient();
        LlmClient azure = closeableClient();
        ModelRoutingLlmClient router =
            new ModelRoutingLlmClient(gemini, routes("a", "gemini", "b", "azure-openai-responses"),
                Map.of(LlmRouteId.GEMINI, gemini, LlmRouteId.AZURE_OPENAI_RESPONSES, azure));

        router.close();

        verify((AutoCloseable) gemini, times(1)).close();
        verify((AutoCloseable) azure, times(1)).close();
    }

    @Test
    void aDelegateThatIsNotCloseableIsSkippedRatherThanFailingShutdown() throws Exception {
        LlmClient notCloseable = mock(LlmClient.class);
        LlmClient azure = closeableClient();
        ModelRoutingLlmClient router =
            new ModelRoutingLlmClient(notCloseable, routes("b", "azure-openai-responses"),
                Map.of(LlmRouteId.AZURE_OPENAI_RESPONSES, azure));

        router.close();

        verify((AutoCloseable) azure).close();
        verify(notCloseable, never()).generate(any());
    }

    @Test
    void anEmptyTableRoutesEverythingToTheDefault() {
        LlmClient fallback = mock(LlmClient.class);
        ModelRoutingLlmClient router = new ModelRoutingLlmClient(fallback, routes(), Map.of());

        assertThat(router.clientFor("anything")).isSameAs(fallback);
    }
}
