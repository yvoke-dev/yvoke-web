package de.palsoftware.yvoke.shared.text;

import java.util.regex.Pattern;

/**
 * Turns a <em>rendered</em> assistant transcript into the text a model may be replayed.
 *
 * <p>
 * A rendered transcript is what the user watched and what {@code messages.content} stores: answer
 * prose interleaved with chrome the model never said — {@code <think>} blocks holding its own
 * reasoning, and {@code 🔧 Calling tool:} banners. Replaying that verbatim hands the model its
 * private reasoning back as prose it supposedly said aloud, plus markup it may then imitate.
 *
 * <p>
 * This exists because two hand-written strippers disagreed about exactly that:
 * {@code RagService.cleanAssistantContent} removed the banners and left {@code <think>} untouched,
 * while {@code OrchestrationService.stripThinkAndTools} removed {@code <think>} but knew only the
 * {@code 🔧} banner form and did not trim. Each was pinned by its own test, so neither could be
 * changed to match the other — the rule had to move somewhere both could share it.
 *
 * <p>
 * <b>Clarifying-question markup is deliberately NOT removed here.</b> That XML reaches replayed
 * history through the persisted transcript, and the user's follow-up is the bare answer ("Option
 * A") with no restatement of the question — so stripping it would leave the model an answer to a
 * question it can no longer see. The orchestrator removes those tags itself, where the text is an
 * answer being extracted rather than a turn being replayed.
 *
 * <p>
 * Every pattern here is deliberately literal. Widening {@code <think>} to {@code <think\b[^>]*>} or
 * the mojibake banner to a bare {@code ??} would start deleting ordinary prose — the failure mode
 * recorded in CLAUDE.md §6, where a markdown rule with one too-permissive class removed a whole
 * table from a live answer.
 */
public final class AssistantTranscript {

    /** Balanced blocks. Reluctant, so two blocks do not merge into one across the text between. */
    private static final Pattern BALANCED_THINK = Pattern.compile("(?s)<think>.*?</think>");

    /**
     * A block left open by a cancelled generation. {@code ChatMessageService} persists the partial
     * sink buffer on stop, so the stored row can end mid-reasoning and is then replayed.
     */
    private static final Pattern UNCLOSED_THINK = Pattern.compile("(?s)<think>.*$");

    /** A close with no opener, which the browser's normalizeThinkTags also drops. */
    private static final String STRAY_CLOSE = "</think>";

    private static final String WRENCH = "🔧";

    /**
     * The two mojibake renderings of the wrench banner. Kept literal and complete: these are the
     * forms that actually reach replay in the field, and a bare {@code ??} prefix would delete
     * ordinary user prose.
     */
    private static final String[] MOJIBAKE_BANNERS = {"?? *Calling tool:*", "?? Calling tool:"};

    private AssistantTranscript() {}

    /**
     * @param renderedTranscript what the user saw; may be {@code null}
     * @return the same text with reasoning blocks and tool banners removed, trimmed; never
     *         {@code null}
     */
    public static String toModelText(String renderedTranscript) {
        if (renderedTranscript == null || renderedTranscript.isEmpty()) {
            return "";
        }
        String withoutThink = BALANCED_THINK.matcher(renderedTranscript).replaceAll("");
        withoutThink = removeUnclosedThink(withoutThink);
        withoutThink = withoutThink.replace(STRAY_CLOSE, "");

        StringBuilder kept = new StringBuilder();
        for (String line : withoutThink.split("\\r?\\n", -1)) {
            if (isToolBanner(line.trim())) {
                continue;
            }
            if (kept.length() > 0) {
                kept.append('\n');
            }
            kept.append(line);
        }
        return kept.toString().trim();
    }

    /**
     * Truncates at an opener that is never closed — a cancelled generation persists its partial
     * buffer, so a stopped turn ends mid-reasoning.
     *
     * <p>
     * Deliberately conservative: it fires only when NO closing tag follows the opener at all. A
     * malformed close such as {@code </think >} is not our marker and is not matched literally, but
     * treating the block as unterminated would delete every word after it — real answer prose — to
     * repair eight characters of markup. Deletion has to be provable; reporting can be liberal.
     */
    private static String removeUnclosedThink(String text) {
        int opener = text.lastIndexOf("<think>");
        if (opener < 0 || text.indexOf("</think", opener) >= 0) {
            return text;
        }
        return text.substring(0, opener);
    }

    private static boolean isToolBanner(String trimmedLine) {
        if (trimmedLine.startsWith(WRENCH)) {
            return true;
        }
        for (String banner : MOJIBAKE_BANNERS) {
            if (trimmedLine.startsWith(banner)) {
                return true;
            }
        }
        return false;
    }
}
