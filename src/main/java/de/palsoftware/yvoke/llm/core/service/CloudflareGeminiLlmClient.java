package de.palsoftware.yvoke.llm.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.ClientOptions;
import java.time.Duration;
import java.util.Map;
import java.util.HashMap;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.Authentication;

public class CloudflareGeminiLlmClient extends GeminiLlmClient {
    public CloudflareGeminiLlmClient(String apiKey, ObjectMapper objectMapper,
        boolean enableThinking, String thinkingLevel, String accountId, String gatewayId,
        String gatewayToken) {
        super(apiKey, objectMapper, enableThinking, thinkingLevel,
            buildCloudflareBaseUrl(accountId, gatewayId), buildHeaders(gatewayToken),
            buildClientOptions(objectMapper));
    }

    // Package-private constructor for testing
    CloudflareGeminiLlmClient(String apiKey, ObjectMapper objectMapper, boolean enableThinking,
        String thinkingLevel, String baseUrl, String gatewayToken) {
        super(apiKey, objectMapper, enableThinking, thinkingLevel, baseUrl,
            buildHeaders(gatewayToken), buildClientOptions(objectMapper));
    }

    private static String buildCloudflareBaseUrl(String accountId, String gatewayId) {
        return String.format("https://gateway.ai.cloudflare.com/v1/%s/%s/google-ai-studio",
            accountId, gatewayId);
    }

    private static Map<String, String> buildHeaders(String gatewayToken) {
        Map<String, String> headers = new HashMap<>();
        if (gatewayToken != null && !gatewayToken.isBlank()) {
            headers.put("cf-aig-authorization", "Bearer " + gatewayToken);
        }
        return headers;
    }

    private static ClientOptions buildClientOptions(ObjectMapper objectMapper) {
        OkHttpClient.Builder httpBuilder = new OkHttpClient.Builder();

        // The SDK configures timeouts only on the OkHttpClient it builds itself; when a custom
        // client is supplied (ApiClient.createHttpClient) it just calls newBuilder(), so
        // HttpOptions.timeout() is dropped and OkHttp's 10s connect/read/write defaults apply —
        // which kills any thinking model that takes >10s to emit its first SSE token. Reproduce
        // what the SDK would have configured: no per-socket timeouts, since SSE streams idle
        // between tokens, bounded by one overall call timeout.
        httpBuilder.connectTimeout(Duration.ZERO);
        httpBuilder.readTimeout(Duration.ZERO);
        httpBuilder.writeTimeout(Duration.ZERO);
        httpBuilder.callTimeout(Duration.ofMillis(HTTP_TIMEOUT_MS));

        httpBuilder.addInterceptor(chain -> {
            Request originalRequest = chain.request();

            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
                String username = auth.getName();
                try {
                    Map<String, String> metadata = Map.of("user", username);
                    String metadataJson = objectMapper.writeValueAsString(metadata);

                    Request newRequest = originalRequest.newBuilder()
                        .header("cf-aig-metadata", metadataJson).build();
                    return chain.proceed(newRequest);
                } catch (Exception e) {
                    return chain.proceed(originalRequest);
                }
            }

            return chain.proceed(originalRequest);
        });

        OkHttpClient httpClient = httpBuilder.build();

        return ClientOptions.builder().customHttpClient(httpClient).build();
    }
}
