package de.palsoftware.yvoke.shared.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The shared rule for turning a rendered assistant transcript into the text a model may be
 * replayed. Two hand-maintained strippers used to disagree about {@code <think>} — see the
 * behaviour each one pinned in {@code RagServiceAgenticTest} and {@code OrchestrationServiceTest}.
 */
class AssistantTranscriptTest {

    @Test
    void aBalancedThinkBlockIsRemoved() {
        assertThat(AssistantTranscript.toModelText("<think>internal</think>\nreal text"))
            .isEqualTo("real text");
    }

    @Test
    void aThinkBlockSpanningNewlinesIsRemoved() {
        assertThat(AssistantTranscript.toModelText("<think>line one\nline two</think>\nanswer"))
            .isEqualTo("answer");
    }

    @Test
    void aTrailingUnclosedThinkRemovesToTheEndOfTheString() {
        // ChatMessageService persists the partial sink buffer when a generation is cancelled, so a
        // stopped turn leaves a DB row that ends inside an open block and is then replayed.
        assertThat(AssistantTranscript.toModelText("answer so far\n<think>cut off mid thou"))
            .isEqualTo("answer so far");
    }

    @Test
    void aStrayClosingTagIsRemovedButItsProseIsKept() {
        // Mirrors normalizeThinkTags in thread-text.js, which drops a close with no opener.
        assertThat(AssistantTranscript.toModelText("before</think>after")).isEqualTo("beforeafter");
    }

    @Test
    void allFourToolBannerPrefixesAreStripped() {
        // The wrench is written as a surrogate pair so an encoding-unaware edit cannot mangle the
        // expectation invisibly. The "??" forms are the mojibake that actually reaches replay.
        String transcript =
            "🔧 *Calling tool:* a()\nreal text\n" + "🔧 Calling tool: b()\nsecond text\n"
                + "?? *Calling tool:* c()\nthird text\n" + "?? Calling tool: d()\nfourth text";

        assertThat(AssistantTranscript.toModelText(transcript))
            .isEqualTo("real text\nsecond text\nthird text\nfourth text");
    }

    @Test
    void anyWrenchPrefixedLineIsStrippedNotJustTheCallingToolForms() {
        // OrchestrationService's rule was the broad one; keeping it is what makes this a union.
        assertThat(AssistantTranscript.toModelText("🔧 something else\nkept")).isEqualTo("kept");
    }

    @Test
    void clarifyingQuestionMarkupIsDeliberatelyLeftAlone() {
        // Stripping it here would delete the question from replayed history while the user's
        // follow-up is only the bare answer ("Option A"), leaving the model an answer to a question
        // it can no longer see. The orchestrator strips these itself, for its own answer text.
        String xml = "<clarifying-question>\n<question>Which kit?</question>\n"
            + "<option>9.3.1</option>\n</clarifying-question>";

        assertThat(AssistantTranscript.toModelText(xml)).isEqualTo(xml);
    }

    @Test
    void theResultIsTrimmed() {
        assertThat(AssistantTranscript.toModelText("  \n<think>x</think>\nanswer\n  "))
            .isEqualTo("answer");
    }

    @Test
    void nullAndEmptyBecomeAnEmptyString() {
        assertThat(AssistantTranscript.toModelText(null)).isEmpty();
        assertThat(AssistantTranscript.toModelText("")).isEmpty();
    }

    @Test
    void textWithNoMarkersPassesThroughByteIdentical() {
        String plain = "A normal answer [8f7c1a2b] with brackets and a < less-than.";

        assertThat(AssistantTranscript.toModelText(plain)).isEqualTo(plain);
    }

    // ---------------------------------------------------------- over-match guards
    //
    // Each guard uses an input the rule WOULD otherwise fire on. CLAUDE.md §6: a guard test whose
    // input the rule could never match proves nothing — that is how the markdown normaliser
    // deleted a whole table for two revisions.

    @Test
    void aThinkingTagIsNotMistakenForAThinkTag() {
        assertThat(AssistantTranscript.toModelText("<thinking>kept</thinking>"))
            .isEqualTo("<thinking>kept</thinking>");
    }

    @Test
    void aThinkTagCarryingAttributesIsNotMatched() {
        // The input the widened form `<think\b[^>]*>` WOULD delete. An earlier version of this
        // guard used "< think>", which neither the literal nor the widened pattern matches — so it
        // passed against the widened rule and proved nothing.
        // The literal close tag is still removed (it IS our marker, and the browser drops a stray
        // one too) — what must survive is every word of prose.
        assertThat(AssistantTranscript.toModelText("<think class=\"x\">kept</think>"))
            .contains("kept").contains("<think class=\"x\">");
    }

    @Test
    void aClosingTagWithInnerSpaceIsNotMatched() {
        assertThat(AssistantTranscript.toModelText("<think>a</think >b"))
            .isEqualTo("<think>a</think >b");
    }

    @Test
    void aBareQuestionMarkPrefixIsNotTreatedAsAToolBanner() {
        // "??" alone would delete ordinary prose; only the two literal Calling-tool forms go.
        assertThat(AssistantTranscript.toModelText("?? did you mean this\nkept"))
            .isEqualTo("?? did you mean this\nkept");
    }

    @Test
    void bracketedTextIsNeverTouched() {
        assertThat(AssistantTranscript.toModelText("Error code [500123] occurred."))
            .isEqualTo("Error code [500123] occurred.");
    }
}
