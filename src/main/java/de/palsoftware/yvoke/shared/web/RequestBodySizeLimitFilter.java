package de.palsoftware.yvoke.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces a hard byte ceiling on JSON/API request bodies before Jackson deserializes them into
 * heap, preventing OOM from oversized {@code /api/chat/**} or {@code /mcp/**} payloads.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestBodySizeLimitFilter extends OncePerRequestFilter {

    private final long maxBodyBytes;

    public RequestBodySizeLimitFilter(
        @Value("${app.security.max-json-body-bytes}") long maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes > 0 ? maxBodyBytes : 10L * 1024L * 1024L;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return true;
        }
        return !(uri.startsWith("/api/chat") || uri.equals("/mcp") || uri.startsWith("/mcp/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
        FilterChain filterChain) throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength > maxBodyBytes) {
            writePayloadTooLarge(response);
            return;
        }
        BoundedBodyRequestWrapper wrapped = new BoundedBodyRequestWrapper(request, maxBodyBytes);
        try {
            filterChain.doFilter(wrapped, response);
        } catch (PayloadTooLargeException e) {
            if (!response.isCommitted()) {
                writePayloadTooLarge(response);
            }
        } catch (ServletException | RuntimeException e) {
            if (hasPayloadTooLargeCause(e)) {
                if (!response.isCommitted()) {
                    writePayloadTooLarge(response);
                }
                return;
            }
            throw e;
        }
    }

    private void writePayloadTooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"Request body exceeds maximum allowed size of "
            + maxBodyBytes + " bytes.\"}");
    }

    private static boolean hasPayloadTooLargeCause(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof PayloadTooLargeException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static final class PayloadTooLargeException extends RuntimeException {
        private PayloadTooLargeException(long maxBytes) {
            super("Request body exceeds maximum allowed size of " + maxBytes + " bytes.");
        }
    }

    private static final class BoundedBodyRequestWrapper extends HttpServletRequestWrapper {
        private final long maxBytes;

        private BoundedBodyRequestWrapper(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new BoundedServletInputStream(super.getInputStream(), maxBytes);
        }

        @Override
        public BufferedReader getReader() throws IOException {
            return new BufferedReader(
                new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    private static final class BoundedServletInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long maxBytes;
        private long readCount = 0;

        private BoundedServletInputStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b != -1) {
                incrementAndCheck(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = delegate.read(b, off, len);
            if (n > 0) {
                incrementAndCheck(n);
            }
            return n;
        }

        private void incrementAndCheck(int delta) {
            readCount += delta;
            if (readCount > maxBytes) {
                throw new PayloadTooLargeException(maxBytes);
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
