package de.palsoftware.yvoke.llm.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.llm.core.service.AccountingLlmClient;
import de.palsoftware.yvoke.llm.core.service.AzureOpenAiResponsesLlmClient;
import de.palsoftware.yvoke.llm.core.service.GeminiLlmClient;
import de.palsoftware.yvoke.llm.core.service.LlmClient;
import de.palsoftware.yvoke.llm.core.service.ModelRoutingLlmClient;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class LlmConfig {
    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    /**
     * How an environment variable is read. Injected so a test can pin the fail-closed rules without
     * its result depending on the developer's own shell.
     *
     * <p>
     * {@link #resolveKey} falls through to the environment for exactly the values a test must
     * supply as blank or {@code placeholder-…}, so a machine that exports the real deployment
     * variables turned assertions red for a reason that had nothing to do with the code under test.
     */
    private final UnaryOperator<String> env;

    public LlmConfig() {
        this(System::getenv);
    }

    LlmConfig(UnaryOperator<String> env) {
        this.env = env;
    }

    /**
     * The accounting seam every caller actually gets. Wrapping here — rather than asking each
     * caller to publish its own usage event — is what makes {@code llm_call_logs} complete by
     * construction; the previous arrangement lost {@code GeneralSummarizer} entirely.
     *
     * <p>
     * Tests that script LLM behaviour must mock the <b>provider</b> bean
     * ({@code @MockitoBean(name = "llmProviderClient")}), not {@code LlmClient}, or they replace
     * the accounting along with the provider. Model routing lives inside that bean, so a routed
     * call is still accounted for and a test that stubs the provider still stubs everything.
     */
    @Bean
    @Primary
    public LlmClient llmClient(@Qualifier("llmProviderClient") LlmClient llmProviderClient,
        ApplicationEventPublisher eventPublisher) {
        return new AccountingLlmClient(llmProviderClient, eventPublisher);
    }

    /**
     * The provider bean: always a {@link ModelRoutingLlmClient}, which sends each request to the
     * client its MODEL is mapped to and falls back to {@code app.ai.provider} — the default route —
     * for a model nobody mapped. With an empty table that is every model, which is the shipped
     * configuration.
     *
     * <p>
     * Only the routes actually named are constructed. Building every client eagerly would reach
     * {@link AzureOpenAiResponsesLlmClient}'s endpoint guard, which throws on the
     * {@code placeholder-…} value this repository ships — taking every {@code @SpringBootTest}
     * context and CI down with it.
     */
    @Bean
    public LlmClient llmProviderClient(@Value("${app.ai.provider}") String provider,
        @Value("${app.ai.model-routes}") String modelRoutes, ObjectMapper objectMapper,
        @Value("${app.ai.gemini.api-key}") String geminiApiKey,
        @Value("${app.ai.gemini.enable-thinking}") boolean enableThinking,
        @Value("${app.ai.gemini.thinking-level}") String thinkingLevel,
        @Value("${app.ai.gemini.base-url}") String geminiBaseUrl,
        @Value("${app.ai.azure-openai.endpoint}") String azureEndpoint,
        @Value("${app.ai.azure-openai.api-key}") String azureApiKey,
        @Value("${app.ai.azure-openai.enable-thinking}") boolean azureEnableThinking,
        @Value("${app.ai.azure-openai.thinking-level}") String azureThinkingLevel,
        @Value("${app.ai.azure-openai.reasoning-models}") String azureReasoningModels) {
        log.info("Configuring LLM client with provider: {}", provider);

        rejectRetired(provider);
        LlmModelRoutes routes = LlmModelRoutes.parse(objectMapper, modelRoutes);

        LlmRouteId defaultRoute = LlmRouteId.fromWire(provider).orElseGet(() -> {
            log.warn("Unrecognised app.ai.provider '{}'; falling back to {}. Valid values: {}.",
                provider, LlmRouteId.GEMINI.wire(), LlmRouteId.wireSpellings());
            return LlmRouteId.GEMINI;
        });

        Set<LlmRouteId> needed = EnumSet.of(defaultRoute);
        needed.addAll(routes.declaredRoutes());

        Map<LlmRouteId, LlmClient> clients = new EnumMap<>(LlmRouteId.class);
        for (LlmRouteId id : needed) {
            clients.put(id, switch (id) {
                case GEMINI -> {
                    String apiKey = resolveKey(geminiApiKey, "GEMINI_API_KEY", "GOOGLE_API_KEY");
                    warnIfMissing(apiKey, "Gemini", "GEMINI_API_KEY/GOOGLE_API_KEY");
                    yield new GeminiLlmClient(apiKey, objectMapper, enableThinking, thinkingLevel,
                        geminiBaseUrl);
                }
                case AZURE_OPENAI_RESPONSES -> {
                    String apiKey = resolveKey(azureApiKey, "AZURE_OPENAI_API_KEY");
                    warnIfMissing(apiKey, "Azure OpenAI Responses", "AZURE_OPENAI_API_KEY");
                    // The endpoint is fatal rather than merely warned about: without one the SDK
                    // targets the public OpenAI service and forwards the Azure key to it as a
                    // bearer token.
                    String endpoint = resolveKey(azureEndpoint, "AZURE_OPENAI_ENDPOINT");
                    yield new AzureOpenAiResponsesLlmClient(endpoint, apiKey, azureEnableThinking,
                        azureThinkingLevel, azureReasoningModels);
                }
            });
        }

        // Always the router, even with nothing mapped. Returning the single client unwrapped was
        // the obvious optimisation and it made app.ai.provider mean two different things — the only
        // client in one configuration, merely the fallback in another — so the bean's TYPE depended
        // on configuration. One code path is worth more than one object: an empty table costs a map
        // lookup per call.
        return new ModelRoutingLlmClient(clients.get(defaultRoute), routes, clients);
    }

    /**
     * Refuses a provider value that used to select a real client.
     *
     * <p>
     * Deliberately NOT folded into the unknown-value fallback above, which returns Gemini on the
     * grounds that a typo must not take a running deployment down. A typo never worked; these three
     * did, so a deployment can still be carrying one — and answering it with Gemini, on a Gemini
     * key, having logged the operator's own spelling back at them, is the silent substitution that
     * fallback's own rationale warns about. No class is deleted: all three remain in the tree with
     * their tests, and their {@code app.ai.*} settings remain in {@code application.yml}, so
     * re-enabling any of them is a branch here and an entry in {@link LlmRouteId}.
     *
     * <p>
     * Case-insensitive, matching the selection. An exact match would let {@code Azure-OpenAI} — the
     * natural spelling in a hand-written manifest — miss this guard and reach the Gemini fallback,
     * which is the outcome the guard exists to prevent.
     */
    private static void rejectRetired(String provider) {
        if ("azure-openai".equalsIgnoreCase(provider)) {
            throw new IllegalStateException("app.ai.provider=azure-openai is no longer wired. That "
                + "client speaks the chat-completions surface, which refuses function tools and "
                + "reasoning_effort together — so every agentic turn silently gave up its thinking "
                + "level. Use azure-openai-responses, which reaches the same deployment through "
                + "the Responses API and takes the same AZURE_OPENAI_* settings.");
        }
        if ("openrouter".equalsIgnoreCase(provider)) {
            throw new IllegalStateException("app.ai.provider=openrouter is no longer wired. Valid "
                + "values: " + LlmRouteId.wireSpellings() + ".");
        }
        if ("cloudflare-gemini".equalsIgnoreCase(provider)) {
            throw new IllegalStateException("app.ai.provider=cloudflare-gemini is no longer wired; "
                + "Gemini is reached directly. Use gemini. Note this gives up the AI Gateway's "
                + "response cache, its cf-aig-metadata attribution and the cost_avoided figures "
                + "derived from cache hits.");
        }
    }

    private String resolveKey(String configured, String... envVars) {
        if (isUsable(configured)) {
            return configured;
        }
        for (String envVar : envVars) {
            String value = env.apply(envVar);
            if (isUsable(value)) {
                return value;
            }
        }
        return "";
    }

    /**
     * Every third-party credential in {@code application.yml} ships a literal {@code placeholder-…}
     * default, so an environment that forgets a variable starts with a fully populated but entirely
     * fictional configuration. This clause is the only line in the codebase that treats such a
     * value as absent — which is both what stops a made-up credential reaching a real third party
     * and what lets {@link #resolveKey} fall through to the environment variable where real
     * deployments actually keep the value.
     */
    private static boolean isUsable(String value) {
        return value != null && !value.isBlank() && !value.contains("placeholder");
    }

    private static void warnIfMissing(String apiKey, String providerName, String envVarNames) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("No {} API key configured (set {} or the matching app.ai.* property); "
                + "LLM calls will fail until a key is provided.", providerName, envVarNames);
        }
    }
}
