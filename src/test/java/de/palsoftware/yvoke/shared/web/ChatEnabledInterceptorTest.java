package de.palsoftware.yvoke.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for {@link ChatEnabledInterceptor} — the {@code app.chat.enabled} feature gate. The
 * flag is a plain constructor {@code boolean}, so no Spring context is needed.
 */
public class ChatEnabledInterceptorTest {

    @Test
    public void preHandlePassesWhenChatEnabled() throws Exception {
        ChatEnabledInterceptor interceptor = new ChatEnabledInterceptor(true);

        assertThat(interceptor.preHandle(null, null, new Object())).isTrue();
    }

    @Test
    public void preHandleBlocksWith503WhenChatDisabled() {
        ChatEnabledInterceptor interceptor = new ChatEnabledInterceptor(false);

        assertThatThrownBy(() -> interceptor.preHandle(null, null, new Object()))
            .isInstanceOf(ResponseStatusException.class).satisfies(t -> {
                ResponseStatusException e = (ResponseStatusException) t;
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(e.getReason()).isEqualTo("Webchat is disabled.");
            });
    }
}
