package de.palsoftware.yvoke.llm.core.service;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.Timeout;
import com.openai.core.http.HttpClient;
import java.lang.reflect.Field;
import okhttp3.OkHttpClient;

/**
 * Reflection helper for asserting the real transport settings wired into an {@link OpenAIClient}.
 *
 * <p>Kept separate so tests can verify the underlying {@link OkHttpClient} and {@link Timeout}
 * directly without duplicating reflection plumbing or colliding on the {@code OkHttpClient} simple
 * name.
 */
final class OpenAiTransportTestSupport {

    private OpenAiTransportTestSupport() {}

    static ClientOptions clientOptionsOf(Object llmClient) {
        try {
            Field clientField = llmClient.getClass().getDeclaredField("client");
            clientField.setAccessible(true);
            OpenAIClient sdkClient = (OpenAIClient) clientField.get(llmClient);
            if (!(sdkClient instanceof OpenAIClientImpl impl)) {
                throw new IllegalStateException("Unexpected OpenAIClient impl: " + sdkClient);
            }
            Field optionsField = OpenAIClientImpl.class.getDeclaredField("clientOptions");
            optionsField.setAccessible(true);
            return (ClientOptions) optionsField.get(impl);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to inspect OpenAI client options", e);
        }
    }

    static Timeout timeoutOf(Object llmClient) {
        return clientOptionsOf(llmClient).timeout();
    }

    static OkHttpClient okHttpClientOf(Object llmClient) {
        try {
            HttpClient current = clientOptionsOf(llmClient).httpClient();
            for (int depth = 0; depth < 16 && current != null; depth++) {
                Field okHttpField = findField(current.getClass(), "okHttpClient");
                if (okHttpField != null) {
                    okHttpField.setAccessible(true);
                    return (OkHttpClient) okHttpField.get(current);
                }
                Field delegateField = findField(current.getClass(), "httpClient");
                if (delegateField == null) {
                    delegateField = findField(current.getClass(), "delegate");
                }
                if (delegateField == null) {
                    break;
                }
                delegateField.setAccessible(true);
                current = (HttpClient) delegateField.get(current);
            }
            throw new IllegalStateException(
                    "Could not locate OkHttpClient inside "
                            + clientOptionsOf(llmClient).httpClient().getClass().getName());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to inspect underlying OkHttpClient", e);
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // continue walking hierarchy
            }
        }
        return null;
    }
}
