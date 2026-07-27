package de.palsoftware.yvoke.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

class RateLimitInterceptorTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void allowsRequestWhenUnderLimit() {
        GenerationRateLimiter limiter = mock(GenerationRateLimiter.class);
        when(limiter.tryAcquire("user:alice")).thenReturn(true);
        authenticate("alice");

        RateLimitInterceptor interceptor = new RateLimitInterceptor(limiter);
        boolean proceed = interceptor.preHandle(new MockHttpServletRequest(),
            new MockHttpServletResponse(), new Object());

        assertThat(proceed).isTrue();
    }

    @Test
    void rejectsWith429WhenOverLimit() {
        GenerationRateLimiter limiter = mock(GenerationRateLimiter.class);
        when(limiter.tryAcquire("user:alice")).thenReturn(false);
        authenticate("alice");

        RateLimitInterceptor interceptor = new RateLimitInterceptor(limiter);

        assertThatThrownBy(() -> interceptor.preHandle(new MockHttpServletRequest(),
            new MockHttpServletResponse(), new Object()))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    void fallsBackToClientIpWhenUnauthenticated() {
        GenerationRateLimiter limiter = mock(GenerationRateLimiter.class);
        when(limiter.tryAcquire("ip:203.0.113.7")).thenReturn(false);
        SecurityContextHolder.clearContext();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        RateLimitInterceptor interceptor = new RateLimitInterceptor(limiter);

        assertThatThrownBy(
            () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
            .isInstanceOf(ResponseStatusException.class);
    }

    private static void authenticate(String name) {
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken(name, "n/a",
                AuthorityUtils.createAuthorityList("ROLE_USER")));
    }
}
