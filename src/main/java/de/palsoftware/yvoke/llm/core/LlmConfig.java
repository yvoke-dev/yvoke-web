package de.palsoftware.yvoke.llm.core;

import de.palsoftware.yvoke.llm.core.service.AccountingLlmClient;
import de.palsoftware.yvoke.llm.core.service.CloudflareGeminiLlmClient;
import de.palsoftware.yvoke.llm.core.service.GeminiLlmClient;
import de.palsoftware.yvoke.llm.core.service.LlmClient;
import de.palsoftware.yvoke.llm.core.service.OpenRouterLlmClient;


import com.fasterxml.jackson.databind.ObjectMapper;
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
    public LlmClient llmProviderClient(@Value("${app.ai.provider}") String provider,
        ObjectMapper objectMapper, @Value("${app.ai.openrouter.base-url}") String openRouterBaseUrl,
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
        @Value("${app.ai.cloudflare-gemini.thinking-level}") String cfThinkingLevel) {
        log.info("Configuring LLM client with provider: {}", provider);

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
            return new CloudflareGeminiLlmClient(apiKey, objectMapper, cfEnableThinking,
                cfThinkingLevel, accountId, gatewayId, gatewayToken);
        }

        String apiKey = resolveKey(geminiApiKey, "GEMINI_API_KEY", "GOOGLE_API_KEY");
        warnIfMissing(apiKey, "Gemini", "GEMINI_API_KEY/GOOGLE_API_KEY");
        return new GeminiLlmClient(apiKey, objectMapper, enableThinking, thinkingLevel,
            geminiBaseUrl);
    }

    private static String resolveKey(String configured, String... envVars) {
        if (isUsable(configured)) {
            return configured;
        }
        for (String envVar : envVars) {
            String value = System.getenv(envVar);
            if (isUsable(value)) {
                return value;
            }
        }
        return "";
    }

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
