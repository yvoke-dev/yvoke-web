package de.palsoftware.yvoke.llm.core;

import java.util.regex.Pattern;

/**
 * Renders an LLM failure into text an admin can act on, for the agent-run trace.
 *
 * <p>
 * Why this exists: {@code ApiException.getMessage()} is
 * {@code String.format("%d %s. %s", code, status, message)}, and against the Cloudflare AI Gateway
 * both trailing fields come back empty — OkHttp exposes no HTTP/2 reason phrase, and the SDK only
 * parses Google's own {@code {"error":{"message":…}}} error shape. A real rate-limit failure was
 * therefore persisted as the literal string {@code "429 . "}, and diagnosing it meant reading
 * container logs. The HTTP status is the one field the SDK reliably supplies, so mapping it to a
 * phrase is where the value is. Azure's {@code HttpResponseException} has the same shape of problem
 * from the other direction — no reason phrase at all — and openai-java's is a third shape again.
 * All three are normalised by {@link ProviderFault}, which is the single place a new SDK is taught
 * about.
 *
 * <p>
 * Output of {@link #detail} is persisted and rendered on an admin page. It reads only the throwable
 * — never a request URL, headers, prompt or corpus text — and still redacts credential shapes,
 * because an exception message is arbitrary text from a third party.
 */
public final class LlmFailureSummary {

    /** Guards the persisted column and the admin page against a runaway provider message. */
    static final int MAX_DETAIL_CHARS = 8_000;
    private static final int MAX_FIELD_CHARS = 500;
    private static final int MAX_CAUSE_DEPTH = 5;
    private static final int MAX_APP_FRAMES = 8;
    private static final String APP_PACKAGE = "de.palsoftware.yvoke";
    private static final String TRUNCATION_SUFFIX = "… [truncated]";

    private static final Pattern BEARER = Pattern.compile("(?i)(bearer)\\s+[\\w.\\-~+/]+=*");
    private static final Pattern CREDENTIAL_PARAM = Pattern
        .compile("(?i)\\b(key|api[_-]?key|access[_-]?token|token|password|secret)=[^&\\s\"']+");

    private LlmFailureSummary() {}

    /**
     * One line naming the fault: {@code ClientException: HTTP 429 (rate limit / quota exhausted)}.
     * Never null and never blank, so an admin row cannot come out empty.
     */
    public static String shortLine(Throwable t) {
        if (t == null) {
            return "(no exception recorded)";
        }
        ProviderFault fault = ProviderFault.of(t);
        if (fault != null) {
            return fault.type() + ": HTTP " + fault.code() + " (" + describeCode(fault.code())
                + ")";
        }
        String message = t.getMessage();
        return t.getClass().getSimpleName() + ": "
            + (message == null || message.isBlank() ? "(no message)" : redact(message.strip()));
    }

    /**
     * The multi-line diagnostic block: the short line, the provider's own fields, the retry count
     * {@link LlmRetry} recorded, the cause chain, and the application frames. Capped and redacted.
     */
    public static String detail(Throwable t) {
        if (t == null) {
            return "(no exception recorded)";
        }
        StringBuilder out = new StringBuilder(shortLine(t));

        ProviderFault fault = ProviderFault.of(t);
        if (fault != null) {
            out.append("\nprovider: status=\"").append(field(fault.status()))
                .append("\" message=\"").append(field(fault.message())).append("\" raw=\"")
                .append(field(fault.raw())).append('"');
        }

        // What the provider's headers said, which on a 429 is the only place it says anything
        // useful: the body names neither the exhausted budget nor when to return.
        String rateLimit = ProviderRateLimit.from(t).describe();
        if (!rateLimit.isEmpty()) {
            out.append('\n').append(rateLimit);
        }

        out.append("\nretries: ").append(retryNote(t));

        int depth = 0;
        for (Throwable c = t; c != null && depth < MAX_CAUSE_DEPTH; c = c.getCause(), depth++) {
            out.append("\ncause: ").append(c.getClass().getSimpleName()).append(": ")
                .append(field(c.getMessage()));
            if (c.getCause() == c) {
                break;
            }
        }

        appendFrames(out, t);
        return cap(sanitize(out.toString()));
    }

    /**
     * The application frames, so the admin sees where in the run it died — not the SDK's internals.
     */
    private static void appendFrames(StringBuilder out, Throwable t) {
        StackTraceElement[] frames = t.getStackTrace();
        if (frames == null || frames.length == 0) {
            return;
        }
        out.append("\nat ").append(frames[0]);
        int shown = 0;
        for (StackTraceElement frame : frames) {
            if (shown >= MAX_APP_FRAMES) {
                break;
            }
            if (frame.getClassName().startsWith(APP_PACKAGE)) {
                out.append("\nat ").append(frame);
                shown++;
            }
        }
    }

    private static String retryNote(Throwable t) {
        for (Throwable suppressed : t.getSuppressed()) {
            if (suppressed instanceof LlmRetryExhausted marker) {
                return marker.getMessage();
            }
        }
        for (Throwable c = t.getCause(); c != null; c = c.getCause()) {
            for (Throwable suppressed : c.getSuppressed()) {
                if (suppressed instanceof LlmRetryExhausted marker) {
                    return marker.getMessage();
                }
            }
        }
        return "not recorded";
    }

    /**
     * The codes {@link LlmRetry} treats as transient, plus the ones worth telling an admin apart.
     */
    private static String describeCode(
        int code) {return switch(code){case 400->"malformed request";case 401,403->"provider rejected credentials";case 404->"model or endpoint not found";case 408->"request timeout";case 429->"rate limit / quota exhausted";case 500,502,503,504->"provider unavailable";default->"HTTP "+code;};}

    private static String field(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String redacted = redact(value.strip());
        return redacted.length() <= MAX_FIELD_CHARS ? redacted
            : redacted.substring(0, MAX_FIELD_CHARS) + TRUNCATION_SUFFIX;
    }

    private static String redact(String value) {
        String out = BEARER.matcher(value).replaceAll("$1 ***");
        return CREDENTIAL_PARAM.matcher(out).replaceAll("$1=***");
    }

    /** Strips control characters so the block cannot corrupt a log line or the rendered page. */
    private static String sanitize(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\n' || !Character.isISOControl(ch)) {
                out.append(ch);
            }
        }
        return out.toString();
    }

    private static String cap(String value) {
        if (value.length() <= MAX_DETAIL_CHARS) {
            return value;
        }
        return value.substring(0, MAX_DETAIL_CHARS - TRUNCATION_SUFFIX.length())
            + TRUNCATION_SUFFIX;
    }
}
