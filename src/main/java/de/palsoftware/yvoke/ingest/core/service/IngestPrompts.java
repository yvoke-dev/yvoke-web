package de.palsoftware.yvoke.ingest.core.service;

import de.palsoftware.yvoke.rag.prompt.SystemPrompt;
import de.palsoftware.yvoke.rag.prompt.SystemPromptService;
import de.palsoftware.yvoke.rag.prompt.SystemPromptType;
import jakarta.annotation.Nullable;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The one place a job's summarize prompt is named and resolved.
 *
 * <p>
 * It exists because there were three places instead. {@code DocumentIngestService} and
 * {@code CustomIngestService} each carried a private {@code resolveSummarizePrompt} — the same
 * method, written twice, both falling back to the literal {@code "default-summarize"}, a prompt
 * name that has never existed in any deployment. {@code ConfluenceIngestService} carried no
 * plumbing at all and passed {@code null}. All three therefore summarized with NO system prompt
 * whenever the setting was absent, and the job still reported success. That is not a hypothetical:
 * it is why the Confluence collection's section summaries read "Here is a summary of the section:"
 * while every other collection's are clean.
 *
 * <p>
 * The prompt is now a required <b>parameter of the job</b>, carried in {@code
 * ingestion_jobs.settings} under {@link #SETTING_SUMMARIZE_PROMPT} and resolved here. There is
 * deliberately no default: a default is what made the failure silent, and every producer that can
 * start a summarizing job can now set the key (see {@code SummarizePromptEnqueueValidator}, which
 * rejects a job that cannot).
 */
@Component
public class IngestPrompts {

    /**
     * Job-settings key naming the SUMMARIZE prompt. Public because producers write it and consumers
     * read it, and a string literal duplicated across that boundary is how the two halves drift.
     */
    public static final String SETTING_SUMMARIZE_PROMPT = "summarizePrompt";

    /**
     * Job-settings key naming the KG prompt. Same contract as {@link #SETTING_SUMMARIZE_PROMPT}.
     */
    public static final String SETTING_KG_PROMPT = "kgPrompt";

    private final SystemPromptService systemPromptService;

    public IngestPrompts(SystemPromptService systemPromptService) {
        this.systemPromptService = systemPromptService;
    }

    /** The name a job carries, or null when it carries none. Blank is treated as absent. */
    @Nullable
    public static String summarizePromptName(@Nullable Map<String, Object> settings) {
        return name(settings, SETTING_SUMMARIZE_PROMPT);
    }

    /** The KG prompt name a job carries, or null when it carries none. */
    @Nullable
    public static String kgPromptName(@Nullable Map<String, Object> settings) {
        return name(settings, SETTING_KG_PROMPT);
    }

    @Nullable
    private static String name(@Nullable Map<String, Object> settings, String key) {
        if (settings == null) {
            return null;
        }
        Object raw = settings.get(key);
        // settings is a free-form jsonb bag: a number under this key must not become the name "42".
        if (!(raw instanceof String name) || name.isBlank()) {
            return null;
        }
        return name.trim();
    }

    /**
     * The summarize prompt text for a job that is about to summarize.
     *
     * <p>
     * Throws rather than returning null, and the caller is expected to call it BEFORE doing any
     * work: an ingest that summarizes without a prompt does not fail, it succeeds with unusable
     * output, and the only cheap moment to notice is before the first LLM call.
     *
     * @param what names the work in the failure message ("section summaries for <file>"), because
     *        the operator reading a failed job needs to know which step could not start
     */
    public String requireSummarizePromptText(@Nullable Map<String, Object> settings, String what) {
        return requireText(settings, SETTING_SUMMARIZE_PROMPT, SystemPromptType.SUMMARIZE, what);
    }

    /**
     * The KG prompt text for a job that is about to extract a knowledge graph.
     *
     * <p>
     * Unconditional for a {@code kg-extract} job, because the job's own existence is the opt-in:
     * unlike section summaries there is no flag to check — somebody asked for this extraction.
     *
     * <p>
     * Its old failure mode was the worse of the two. {@code DocumentKgExtractor} looked up
     * {@code "default-kg"} — never registered — and fell back to the EMPTY STRING, so an extraction
     * with no prompt selected ran with no system prompt at all. The prompt is what specifies the
     * strict-JSON response shape, so the model was asked for a graph with no schema, and the parse
     * failures that followed read as "this chunk had no entities".
     */
    public String requireKgPromptText(@Nullable Map<String, Object> settings, String what) {
        return requireText(settings, SETTING_KG_PROMPT, SystemPromptType.KG, what);
    }

    private String requireText(@Nullable Map<String, Object> settings, String key,
        SystemPromptType type, String what) {
        String name = name(settings, key);
        if (name == null) {
            throw new IllegalArgumentException(
                "This job is configured to produce " + what + ", but carries no '" + key
                    + "' setting naming the prompt to use." + " Set it when enqueuing the job.");
        }
        SystemPrompt prompt = systemPromptService.requirePrompt(name, type);
        String text = prompt.systemPrompt();
        if (text == null || text.isBlank()) {
            // A registered prompt with an empty body is the same failure as no prompt, and
            // savePrompt already refuses to create one — so this only catches a row edited around
            // the service (a direct import, a hand-run UPDATE).
            throw new IllegalArgumentException("System prompt '" + prompt.name()
                + "' is registered but empty, so it cannot be used for " + what + ".");
        }
        return text;
    }
}
