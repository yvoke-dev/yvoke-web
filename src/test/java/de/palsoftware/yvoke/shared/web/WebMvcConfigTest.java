package de.palsoftware.yvoke.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;

/**
 * Pins the interceptor path mapping declared by {@link WebMvcConfig}. The registration is pure
 * configuration — nothing validates it, and a wrong pattern produces no error at startup, no
 * warning at runtime and no failing request: the interceptor is simply never invoked for the route
 * it was supposed to guard.
 */
public class WebMvcConfigTest {

    /**
     * {@code /chat/{id}/send} and {@code /chat/{id}/send-async} are two doors into the
     * <em>same</em> generation path — the SSE one and the poll-based one — and the browser chooses
     * between them at runtime. SEC-03 exists because one authenticated principal can otherwise
     * spend unbounded LLM budget; losing the pattern for either door leaves that door completely
     * unlimited while the other still looks guarded, which reads as "rate limiting is on" in every
     * review and every manual test that happens to use the other mode. {@code /api/ingest/**} is
     * the third expensive entry point (it enqueues corpus-wide ingestion jobs) and is reachable by
     * a non-admin {@code ROLE_INGEST} caller.
     *
     * <p>
     * Nothing else in the suite notices a wrong pattern list. {@code RateLimitInterceptorTest}
     * drives the interceptor directly with a hand-built request, so it stays green no matter which
     * routes {@code WebMvcConfig} maps it to; and an interceptor that is never invoked throws
     * nothing, logs nothing and changes no response. Asserting <em>exactly</em> these three
     * patterns (rather than "contains") is deliberate: the opposite failure — widening the include
     * list to something like {@code /chat/**} — would start rate-limiting the 3-second status poll
     * and {@code /stop}, so a user reading a long answer would be throttled out of their own
     * conversation.
     */
    @Test
    public void theRateLimiterCoversBothGenerationEntryPointsAndTheIngestApi() {
        RateLimitInterceptor rateLimitInterceptor =
            new RateLimitInterceptor(new GenerationRateLimiter(true, 20, 60, () -> 0L));
        ChatEnabledInterceptor chatEnabledInterceptor = new ChatEnabledInterceptor(true);
        // The argument resolver is only stashed in a list here; it is never invoked by this test.
        WebMvcConfig config = new WebMvcConfig(chatEnabledInterceptor, rateLimitInterceptor,
            new UserArgumentResolver(null));

        RecordingInterceptorRegistry registry = new RecordingInterceptorRegistry();
        config.addInterceptors(registry);

        MappedInterceptor rateLimitMapping = registry.mappedInterceptorFor(rateLimitInterceptor);
        assertThat(rateLimitMapping.getIncludePathPatterns())
            .as("the rate limiter must cover BOTH generation entry points plus the ingest API, "
                + "and nothing else")
            .containsExactly("/chat/*/send", "/chat/*/send-async", "/api/ingest/**");
        assertThat(rateLimitMapping.getExcludePathPatterns())
            .as("an exclude pattern would silently punch a hole in the include list")
            .isNullOrEmpty();

        // The chat gate is deliberately the broad one: every chat route must refuse to run when
        // app.chat.enabled=false, including the polls the rate limiter stays off.
        MappedInterceptor chatMapping = registry.mappedInterceptorFor(chatEnabledInterceptor);
        assertThat(chatMapping.getIncludePathPatterns()).containsExactly("/chat", "/chat/**");
    }

    /**
     * {@link InterceptorRegistry#getInterceptors()} is {@code protected}, so a subclass is the only
     * way to read back what a
     * {@link org.springframework.web.servlet.config.annotation.WebMvcConfigurer} registered without
     * booting a whole Spring context. Each registration that carries path patterns comes back
     * wrapped in a {@link MappedInterceptor}; one without patterns comes back raw, which is itself
     * a signal worth failing on.
     */
    private static class RecordingInterceptorRegistry extends InterceptorRegistry {

        MappedInterceptor mappedInterceptorFor(Object target) {
            List<Object> registered = getInterceptors();
            return registered.stream().filter(MappedInterceptor.class::isInstance)
                .map(MappedInterceptor.class::cast)
                .filter(mapped -> mapped.getInterceptor() == target).findFirst()
                .orElseThrow(() -> new AssertionError("No path-mapped registration found for "
                    + target.getClass().getSimpleName() + " (registered: " + registered + ")"));
        }
    }
}
