package de.palsoftware.yvoke.llm.core;

import de.palsoftware.yvoke.llm.core.service.AccountingLlmClient;
import de.palsoftware.yvoke.llm.core.service.AzureOpenAiLlmClient;
import de.palsoftware.yvoke.llm.core.service.CloudflareGeminiLlmClient;
import de.palsoftware.yvoke.llm.core.service.GeminiLlmClient;
import de.palsoftware.yvoke.llm.core.service.LlmClient;
import de.palsoftware.yvoke.llm.core.service.OpenRouterLlmClient;
import de.palsoftware.yvoke.shared.security.DevProfiles;


import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

@Configuration
public class LlmConfig {
    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    /**
     * How an environment variable is read. Injected so a test can pin the fail-closed rules without
     * its result depending on the developer's own shell.
     *
     * <p>
     * {@link #resolveKey} falls through to the environment for exactly the values a test must
     * supply as blank or {@code placeholder-…}, so a machine that exports the real
     * {@code CLOUDFLARE_*} variables — the same names a deployment sets — turned two assertions red
     * for a reason that had nothing to do with the code under test, and a machine that exported
     * only some of them made the failure name the wrong setting.
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
     * the accounting along with the provider.
     */
    @Bean
    @Primary
    public LlmClient llmClient(@Qualifier("llmProviderClient") LlmClient llmProviderClient,
        ApplicationEventPublisher eventPublisher) {
        return new AccountingLlmClient(llmProviderClient, eventPublisher);
    }

    @Bean
    public LlmClient llmProviderClient(Environment environment,
        @Value("${app.ai.provider}") String provider, ObjectMapper objectMapper,
        @Value("${app.ai.openrouter.base-url}") String openRouterBaseUrl,
        @Value("${app.ai.openrouter.api-key}") String openRouterApiKey,
        @Value("${app.ai.gemini.api-key}") String geminiApiKey,
        @Value("${app.ai.gemini.enable-thinking}") boolean enableThinking,
        @Value("${app.ai.gemini.thinking-level}") String thinkingLevel,
        @Value("${app.ai.gemini.base-url}") String geminiBaseUrl,
        @Value("${app.ai.cloudflare-gemini.account-id}") String cfAccountId,
        @Value("${app.ai.cloudflare-gemini.gateway-id}") String cfGatewayId,
        @Value("${app.ai.cloudflare-gemini.gateway-token}") String cfGatewayToken,
        @Value("${app.ai.cloudflare-gemini.api-key}") String cfGeminiApiKey,
        @Value("${app.ai.cloudflare-gemini.enable-thinking}") boolean cfEnableThinking,
        @Value("${app.ai.cloudflare-gemini.thinking-level}") String cfThinkingLevel,
        @Value("${app.ai.azure-openai.endpoint}") String azureEndpoint,
        @Value("${app.ai.azure-openai.api-key}") String azureApiKey,
        @Value("${app.ai.azure-openai.enable-thinking}") boolean azureEnableThinking,
        @Value("${app.ai.azure-openai.thinking-level}") String azureThinkingLevel,
        @Value("${app.ai.azure-openai.reasoning-models}") String azureReasoningModels) {
        log.info("Configuring LLM client with provider: {}", provider);

        if ("azure-openai".equalsIgnoreCase(provider)) {
            String apiKey = resolveKey(azureApiKey, "AZURE_OPENAI_API_KEY");
            warnIfMissing(apiKey, "Azure OpenAI", "AZURE_OPENAI_API_KEY");
            // The endpoint is fatal rather than merely warned about: without one the SDK targets
            // the
            // public OpenAI service and forwards the Azure key to it as a bearer token.
            String endpoint = resolveKey(azureEndpoint, "AZURE_OPENAI_ENDPOINT");
            return new AzureOpenAiLlmClient(endpoint, apiKey, objectMapper, azureEnableThinking,
                azureThinkingLevel, azureReasoningModels);
        }

        if ("openrouter".equalsIgnoreCase(provider)) {
            String apiKey = resolveKey(openRouterApiKey, "OPENROUTER_API_KEY");
            warnIfMissing(apiKey, "OpenRouter", "OPENROUTER_API_KEY");
            return new OpenRouterLlmClient(openRouterBaseUrl, apiKey, objectMapper);
        }

        if ("cloudflare-gemini".equalsIgnoreCase(provider)) {
            String apiKey = resolveKey(cfGeminiApiKey, "GEMINI_API_KEY", "GOOGLE_API_KEY");
            warnIfMissing(apiKey, "Cloudflare Gemini", "GEMINI_API_KEY/GOOGLE_API_KEY");
            String accountId = resolveKey(cfAccountId, "CLOUDFLARE_ACCOUNT_ID");
            String gatewayId = resolveKey(cfGatewayId, "CLOUDFLARE_GATEWAY_ID");
            String gatewayToken = resolveKey(cfGatewayToken, "CLOUDFLARE_GATEWAY_TOKEN");
            // Fail closed. These three are formatted straight into the gateway URL and its auth
            // header, so a blank one does not degrade — it produces a syntactically valid URL
            // (".../v1///google-ai-studio") that 404s on every call from the first message, or a
            // silently UNAUTHENTICATED gateway request. This is the default provider, so the
            // operator who gets it wrong is the one setting the app up for the first time, and the
            // only symptom was an error at the end of a chat turn pointing at nothing.
            requireConfigured(environment, "Cloudflare Gemini", "account id",
                "CLOUDFLARE_ACCOUNT_ID / app.ai.cloudflare-gemini.account-id", accountId);
            requireConfigured(environment, "Cloudflare Gemini", "gateway id",
                "CLOUDFLARE_GATEWAY_ID / app.ai.cloudflare-gemini.gateway-id", gatewayId);
            requireConfigured(environment, "Cloudflare Gemini", "gateway token",
                "CLOUDFLARE_GATEWAY_TOKEN / app.ai.cloudflare-gemini.gateway-token", gatewayToken);
            return new CloudflareGeminiLlmClient(apiKey, objectMapper, cfEnableThinking,
                cfThinkingLevel, accountId, gatewayId, gatewayToken);
        }

        String apiKey = resolveKey(geminiApiKey, "GEMINI_API_KEY", "GOOGLE_API_KEY");
        warnIfMissing(apiKey, "Gemini", "GEMINI_API_KEY/GOOGLE_API_KEY");
        return new GeminiLlmClient(apiKey, objectMapper, enableThinking, thinkingLevel,
            geminiBaseUrl);
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

    private static boolean isUsable(String value) {
        return value != null && !value.isBlank() && !value.contains("placeholder");
    }


    /**
     * Fails startup for a setting that has no usable default and no safe degraded mode.
     *
     * <p>
     * Distinct from {@link #warnIfMissing}, which is right for an API key: a missing key produces
     * an unmistakable 401 from the provider naming the problem. These values are interpolated into
     * a URL instead, so a missing one produces a well-formed request to the wrong place — a 404
     * with nothing anywhere connecting it to configuration.
     *
     * <p>
     * Gated on the development profiles for the same reason {@code SecretCipher} and
     * {@code SecurityConfig} are, and it is not optional here: {@code app.ai.provider} <b>defaults
     * to</b> {@code cloudflare-gemini} while the three gateway values default to
     * {@code placeholder-…} literals that {@link #isUsable} treats as absent — so "the operator
     * selected this provider and forgot to configure it" is indistinguishable from "the operator
     * chose nothing at all". Throwing unconditionally therefore refuses to start on the
     * configuration this repository ships, which took down every {@code @SpringBootTest} that does
     * not replace this bean, and CI with them. Outside a dev box the throw is still what we want: a
     * real deployment naming the missing variable at startup beats a 404 on every answer.
     */
    private static void requireConfigured(Environment environment, String providerName, String what,
        String where, String value) {
        if (value != null && !value.isBlank()) {
            return;
        }
        String problem = providerName + " is selected but its " + what + " is not configured (set "
            + where + "). It is formatted into the gateway URL, so "
            + "leaving it blank would send every request to an address that does not exist.";
        if (DevProfiles.anyActive(environment)) {
            log.warn("{} Startup continues because a development profile is active; "
                + "every call through this client will fail.", problem);
            return;
        }
        throw new IllegalStateException(problem);
    }

    private static void warnIfMissing(String apiKey, String providerName, String envVarNames) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("No {} API key configured (set {} or the matching app.ai.* property); "
                + "LLM calls will fail until a key is provided.", providerName, envVarNames);
        }
    }
}
