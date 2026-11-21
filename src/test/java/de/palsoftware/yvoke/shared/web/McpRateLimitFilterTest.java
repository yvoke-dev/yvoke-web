package de.palsoftware.yvoke.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class McpRateLimitFilterTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void throttlesMcpCallsPerAuthenticatedPrincipalWith429AndRetryAfter() throws Exception {
        SecurityContextHolder.getContext()
            .setAuthentication(new TestingAuthenticationToken("alice-sub", "token", "ROLE_USER"));
        McpRateLimitFilter filter =
            new McpRateLimitFilter(new GenerationRateLimiter(true, 2, 60, () -> 1_000L), 60);

        AtomicInteger passed = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, new MockFilterChain() {
                @Override
                public void doFilter(ServletRequest request, ServletResponse response) {
                    passed.incrementAndGet();
                }
            });
            assertThat(res.getStatus()).isEqualTo(200);
        }
        assertThat(passed.get()).isEqualTo(2);

        // Third call from same principal is rejected with 429 + Retry-After
        MockHttpServletRequest thirdReq = new MockHttpServletRequest("POST", "/mcp");
        MockHttpServletResponse thirdRes = new MockHttpServletResponse();
        filter.doFilter(thirdReq, thirdRes, new MockFilterChain());
        assertThat(thirdRes.getStatus()).isEqualTo(429);
        assertThat(thirdRes.getHeader("Retry-After")).isEqualTo("60");
        assertThat(thirdRes.getContentAsString()).contains("rate limit");

        // Different principal still has its own bucket
        SecurityContextHolder.getContext()
            .setAuthentication(new TestingAuthenticationToken("bob-sub", "token", "ROLE_USER"));
        MockHttpServletRequest bobReq = new MockHttpServletRequest("POST", "/mcp");
        MockHttpServletResponse bobRes = new MockHttpServletResponse();
        filter.doFilter(bobReq, bobRes, new MockFilterChain());
        assertThat(bobRes.getStatus()).isEqualTo(200);
    }
}
