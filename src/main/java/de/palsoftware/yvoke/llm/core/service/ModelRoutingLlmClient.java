package de.palsoftware.yvoke.llm.core.service;

import de.palsoftware.yvoke.llm.core.LlmModelRoutes;
import de.palsoftware.yvoke.llm.core.LlmRouteId;
import de.palsoftware.yvoke.llm.core.model.LlmRequest;
import de.palsoftware.yvoke.llm.core.model.LlmResponse;
import de.palsoftware.yvoke.llm.core.model.LlmResponseChunk;
import jakarta.annotation.PreDestroy;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends each request to the provider client its MODEL is mapped to, falling back to the default
 * route for a model nobody mapped.
 *
 * <p>
 * The routing key is the model rather than the caller's role because {@code ..llm..} is a domain
 * package and {@code chat → llm} already exists, so this class cannot see {@code chat}'s
 * orchestrator roles without creating the cycle {@code ArchitectureTest} forbids. The model is also
 * the only per-role value that already survives all the way here — the multi-agent profile resolves
 * a model per role and it arrives as {@link LlmRequest#model()} — and it additionally covers
 * callers that have no role at all ({@code app.ai.kg.model}, {@code app.ai.summarize.model}).
 *
 * <p>
 * Installed AS the {@code llmProviderClient} bean, i.e. BELOW the {@code @Primary}
 * {@link AccountingLlmClient}, so a routed call is still accounted for and nothing can route around
 * the ledger. Keeping the bean's name and type is also what leaves the tests that stub
 * {@code @MockitoBean(name = "llmProviderClient")} working untouched.
 *
 * <p>
 * The map value is a bare {@link LlmClient} deliberately. {@code AccountingLlmClient} is this
 * codebase's own demonstration that a decorator over that interface composes, so adding failover or
 * load balancing later means putting a composite client in the same slot — this class never learns
 * that a value became composite, and needs no configuration knob today to allow it.
 */
public class ModelRoutingLlmClient implements LlmClient, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ModelRoutingLlmClient.class);

    private final LlmClient defaultClient;
    private final LlmModelRoutes routes;
    private final Map<LlmRouteId, LlmClient> clientsByRoute;

    public ModelRoutingLlmClient(LlmClient defaultClient, LlmModelRoutes routes,
        Map<LlmRouteId, LlmClient> clientsByRoute) {
        this.defaultClient = defaultClient;
        this.routes = routes;
        this.clientsByRoute = Map.copyOf(clientsByRoute);

        // A declared route with no client would otherwise surface as a NullPointerException on the
        // first question asked about that model, long after the configuration that caused it.
        Set<LlmRouteId> missing = routes.declaredRoutes();
        missing.removeAll(this.clientsByRoute.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("app.ai.model-routes names route(s) "
                + missing.stream().map(LlmRouteId::wire).sorted().toList()
                + " for which no client was built");
        }
        log.info("Model routing active: {} model(s) mapped across {}", routes.byModel().size(),
            this.clientsByRoute.keySet().stream().map(LlmRouteId::wire).sorted().toList());
    }

    /**
     * Which client would answer for this model. Public so the wiring can be asserted without
     * driving a whole call — {@code LlmConfigTest} needs to prove that the route table actually
     * reached the right client, which is the bug a bean-type assertion alone would miss. Returns
     * the {@link LlmClient} interface, so no caller gains a dependency on a provider class and the
     * accounting-seam rule in {@code ArchitectureTest} is unaffected.
     *
     * <p>
     * A null or blank model is the default's problem to report, not this class's: failing here
     * would turn a provider-side validation error into a routing error and hide what actually went
     * wrong.
     */
    public LlmClient clientFor(String model) {
        return routes.routeFor(model).map(clientsByRoute::get).orElse(defaultClient);
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        return clientFor(request.model()).generate(request);
    }

    @Override
    public void generateStream(LlmRequest request, Consumer<LlmResponseChunk> onChunk) {
        clientFor(request.model()).generateStream(request, onChunk);
    }

    /**
     * This router is the bean, so the clients it wraps are not managed by Spring and nothing else
     * would ever release their connection pools.
     *
     * <p>
     * By identity, not by map entry: the default client is routinely also a route target — default
     * {@code gemini} plus a model pinned to {@code gemini} is the ordinary case — and closing the
     * same client twice is at best a wasted call and at worst an error from an SDK that does not
     * expect it. A delegate that is not {@link AutoCloseable} is skipped rather than refused;
     * {@code OpenRouterLlmClient} is one such.
     */
    @Override
    @PreDestroy
    public void close() {
        Map<LlmClient, Boolean> seen = new IdentityHashMap<>();
        seen.put(defaultClient, Boolean.TRUE);
        clientsByRoute.values().forEach(c -> seen.put(c, Boolean.TRUE));
        for (LlmClient client : seen.keySet()) {
            if (client instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    log.warn("Failed to close provider client {}: {}",
                        client.getClass().getSimpleName(), e.getMessage());
                }
            }
        }
    }
}
