package de.palsoftware.yvoke.llm.core.service;

import com.openai.core.Timeout;
import java.time.Duration;

/**
 * Shared transport timeout configuration for {@code com.openai:openai-java} clients.
 *
 * <p>{@code OpenAIOkHttpClient.Builder.timeout(Duration)} sets {@link Timeout#request()}, which
 * openai-java maps onto OkHttp's {@code callTimeout} — a hard wall-clock ceiling over the entire
 * HTTP call including draining an SSE stream. That kills healthy long generations at 300 s, the
 * exact failure {@link GeminiLlmClient#httpClientBuilder(int)} fixed by disabling {@code
 * callTimeout} and bounding per-socket read/write silence instead.
 *
 * <p>Setting all four components explicitly with {@code request(Duration.ZERO)} passes {@code
 * callTimeout(0)} to OkHttp (meaning no whole-call limit) while keeping TCP/TLS connect bounded at
 * 10 s and socket read/write silence bounded at {@code readTimeout}.
 */
final class OpenAiHttpTimeouts {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(300);

    private OpenAiHttpTimeouts() {}

    static Timeout forReadTimeout(Duration readTimeout) {
        return Timeout.builder()
                .connect(CONNECT_TIMEOUT)
                .read(readTimeout)
                .write(readTimeout)
                .request(Duration.ZERO)
                .build();
    }
}
