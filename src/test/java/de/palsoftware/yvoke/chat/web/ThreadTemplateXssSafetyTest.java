package de.palsoftware.yvoke.chat.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * SEC-02 / SEC-06: guards chat/thread.html against DOM XSS where LLM/corpus/admin-derived text
 * (clarifying-question options, playbook name/title) is interpolated into inline on* handler
 * strings that are then inserted via innerHTML/insertAdjacentHTML (outside DOMPurify).
 *
 * <p>
 * Because escapeHtml() only makes a value safe in HTML *text/attribute* context — the browser
 * decodes entities like {@code &#039;} back to {@code '} when parsing an inline handler attribute —
 * tainted text must never be placed inside an inline handler string. The safe pattern (already used
 * at the top of the template) is to store the value in an escaped {@code data-*} attribute and read
 * it back with getAttribute() from a static handler.
 *
 * <p>
 * This is a source-pattern regression guard (there is no JS runtime in the test stack), in the same
 * style as the existing guards in {@code ChatThreadRenderingIT}. The handler-building JS was
 * extracted from the template into {@code static/js/chat/thread.js} (MNT-02), so the guard scans
 * the template <em>and</em> that script together — the XSS-safety rule holds wherever the JS lives.
 */
public class ThreadTemplateXssSafetyTest {

    private static final Path SCRIPT_DIR = Paths.get("src/main/resources/static/js/chat");

    /**
     * Template + every extracted client script, so the source-pattern guard is location-agnostic.
     */
    private static String content() throws IOException {
        Path templatePath = Paths.get("src/main/resources/templates/chat/thread.html");

        // Walk the directory rather than naming files: the escaping rule applies to every chat
        // script, and a hardcoded list means each newly extracted module silently drops out of this
        // guard exactly when it most needs covering. Vendored bundles are excluded — they are
        // third-party and would only produce noise.
        List<Path> scripts;
        try (Stream<Path> files = Files.list(SCRIPT_DIR)) {
            scripts = files.filter(p -> p.getFileName().toString().endsWith(".js"))
                .filter(p -> !p.getFileName().toString().endsWith(".min.js")).sorted().toList();
        }
        assertThat(scripts)
            .as("chat scripts found under %s — a walk that finds nothing would make "
                + "every assertion below vacuously pass", SCRIPT_DIR)
            .hasSizeGreaterThanOrEqualTo(2);

        StringBuilder combined =
            new StringBuilder(Files.readString(templatePath, StandardCharsets.UTF_8));
        for (Path script : scripts) {
            combined.append('\n').append(Files.readString(script, StandardCharsets.UTF_8));
        }
        return combined.toString();
    }

    @Test
    public void noTaintedValuesInsideInlineEventHandlerStrings() throws IOException {
        String content = content();

        // Playbook name/title must not be interpolated into an inline onclick (SEC-06).
        assertThat(content)
            .as("playbook fields interpolated into an inline selectPlaybook() handler string")
            .doesNotContain("selectPlaybook('${");

        // Clarifying-question option text must not be passed as an inline JS-string arg (SEC-02).
        assertThat(content).as(
            "clarifying option text passed inside an inline submitClarificationAnswer() handler")
            .doesNotContain("submitClarificationAnswer(this, '${");

        // Preflight switch must not inline the playbook name/title (SEC-06).
        assertThat(content)
            .as("playbook name interpolated into an inline switchAndSend() handler string")
            .doesNotContain("switchAndSend('${");
    }

    @Test
    public void usesSafeDataAttributePattern() throws IOException {
        String content = content();

        // Clarifying options carry their answer in an escaped data attribute read via getAttribute.
        assertThat(content).contains("data-answer=\"${escapeHtml(opt)}\"");
        // Autocomplete playbook options resolve name/title from escaped data attributes.
        assertThat(content).contains("data-prompt-name=\"${escapeHtml(p.name)}\"");
        // Citations route through delegation via an escaped data attribute, not an inline handler.
        assertThat(content).contains("data-action=\"toggle-citation\"");
        assertThat(content).contains("data-chunk-id=\"${escapeHtml(");
    }

    /**
     * MNT-19: the chat thread converged on a single interaction paradigm — all behaviour is wired
     * via {@code data-action} + delegated listeners, with <b>zero</b> inline {@code on*=} event
     * handlers in the template or its script. This guards against inline handlers creeping back
     * (which would both reintroduce the two-paradigm smell and reopen the SEC-02/06
     * tainted-value-in-handler risk).
     */
    @Test
    public void noInlineEventHandlersInChatThread() throws IOException {
        String content = content();

        assertThat(content).as("inline onclick handler in chat thread (use data-action delegation)")
            .doesNotContain("onclick=");
        assertThat(content)
            .as("inline onkeydown handler in chat thread (use data-action delegation)")
            .doesNotContain("onkeydown=");
        assertThat(content)
            .as("inline onmouseenter handler in chat thread (use data-action delegation)")
            .doesNotContain("onmouseenter=");
    }
}
