package de.palsoftware.yvoke.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestBodySizeLimitFilterTest {

    @Test
    void rejectsOversizedPayloadByContentLengthHeaderAndByStreamRead() throws Exception {
        RequestBodySizeLimitFilter filter = new RequestBodySizeLimitFilter(64);

        // 1. Content-Length over limit -> rejected before downstream runs
        MockHttpServletRequest req1 =
            new MockHttpServletRequest("POST", "/api/chat/v1/conversations");
        req1.setContent("x".repeat(128).getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        filter.doFilter(req1, res1, new MockFilterChain());
        assertThat(res1.getStatus()).isEqualTo(413);
        assertThat(res1.getContentAsString()).contains("maximum allowed size");

        // 2. Unknown Content-Length (chunked) -> rejected mid-stream while reading
        MockHttpServletRequest req2 = new MockHttpServletRequest("POST", "/mcp") {
            @Override
            public long getContentLengthLong() {
                return -1L;
            }
        };
        req2.setContent("y".repeat(128).getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        FilterChain readingChain = (ServletRequest request, ServletResponse response) -> {
            request.getInputStream().readAllBytes();
        };
        filter.doFilter(req2, res2, readingChain);
        assertThat(res2.getStatus()).isEqualTo(413);

        // 3. Small payload within limit passes
        MockHttpServletRequest req3 = new MockHttpServletRequest("POST", "/mcp");
        req3.setContent("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse res3 = new MockHttpServletResponse();
        filter.doFilter(req3, res3, readingChain);
        assertThat(res3.getStatus()).isEqualTo(200);
    }

    @Test
    void doesNotFilterCorpusMultipartIngestEndpoint() throws Exception {
        RequestBodySizeLimitFilter filter = new RequestBodySizeLimitFilter(64);
        MockHttpServletRequest ingestReq =
            new MockHttpServletRequest("POST", "/api/ingest/v1/upload");
        ingestReq.setContent("z".repeat(256).getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(ingestReq, res, new MockFilterChain());
        assertThat(res.getStatus()).isEqualTo(200);
    }
}
