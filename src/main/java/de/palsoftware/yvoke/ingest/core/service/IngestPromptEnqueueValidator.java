package de.palsoftware.yvoke.ingest.core.service;

import de.palsoftware.yvoke.ingest.core.model.IngestJobKind;
import de.palsoftware.yvoke.rag.prompt.SystemPromptService;
import de.palsoftware.yvoke.rag.prompt.SystemPromptType;
import de.palsoftware.yvoke.shared.jobengine.EnqueueValidator;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueRequest;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Rejects, at enqueue, a job that will call an LLM but names no usable prompt for it.
 *
 * <p>
 * Sibling of {@link CollectionTagEnqueueValidator}, and for the same reason: a job referring to
 * something that does not exist should be refused while the operator is still looking at the form,
 * not twenty minutes into an LLM run with a half-ingested document behind it. The execution-time
 * check in {@link IngestPrompts} stays — it answers a different question ("does it still exist
 * now?", since a prompt can be deleted between enqueue and run) and its failure is a failed job
 * rather than a rejected request.
 *
 * <p>
 * Which kinds are checked is a whitelist, not a blacklist. A new kind that summarizes and is
 * forgotten here would silently skip validation; a new kind that does NOT summarize and is
 * forgotten here merely does not get a check it does not need. {@code
 * IngestPromptEnqueueValidatorTest} pins the mapping so the safe direction is also the enforced
 * one.
 *
 * <p>
 * Both prompts live in ONE validator because they are one question — "does this job have the
 * prompts it needs?" — and splitting it would put half the kind matrix in each of two classes, so a
 * new kind would have to be remembered twice.
 */
@Component
public class IngestPromptEnqueueValidator implements EnqueueValidator {

    /**
     * Kinds whose service calls the summarizer unconditionally.
     *
     * <p>
     * {@code hierarchical} is here rather than under the opt-in flag on purpose:
     * {@code processSingleHierarchicalFile} calls {@code generateSummaries} with no
     * {@code buildSectionSummaries} gate at all, unlike the standard path. The asymmetry is real,
     * not an oversight in this list.
     */
    private static final Set<String> ALWAYS_SUMMARIZES =
        Set.of(IngestJobKind.HIERARCHICAL.getValue(), IngestJobKind.CUSTOM.getValue());

    /**
     * Kinds that summarize only when {@code buildSectionSummaries} is set.
     *
     * <p>
     * {@code confluence-page-import} is here rather than under {@link #ALWAYS_SUMMARIZES} because
     * the flag is per-instance connector config, snapshotted into the job at crawl time — a
     * Confluence job with summaries off needs no prompt and must not be refused for lacking one.
     */
    private static final Set<String> SUMMARIZES_ON_REQUEST =
        Set.of(IngestJobKind.STANDARD.getValue(), IngestJobKind.CONFLUENCE_PAGE_IMPORT.getValue());

    /**
     * A crawl summarizes nothing itself — it fans out into page jobs, and each of those is
     * validated on its own enqueue. It is listed so the intent is explicit rather than inferred
     * from the absence of a name.
     */
    private static final Set<String> FANS_OUT = Set.of(IngestJobKind.CONFLUENCE_IMPORT.getValue());

    private final SystemPromptService systemPromptService;

    public IngestPromptEnqueueValidator(SystemPromptService systemPromptService) {
        this.systemPromptService = systemPromptService;
    }

    /**
     * Whether a job of this kind, with these settings, will call the summarizer.
     *
     * <p>
     * Package-private and static so the matrix test can assert on it directly instead of
     * re-deriving the rule.
     */
    static boolean willSummarize(String kind, Map<String, Object> settings) {
        String bare = bareKind(kind);
        if (FANS_OUT.contains(bare)) {
            return false;
        }
        if (ALWAYS_SUMMARIZES.contains(bare)) {
            return true;
        }
        return SUMMARIZES_ON_REQUEST.contains(bare) && Boolean.TRUE
            .equals(settings.get(DocumentIngestService.SETTING_BUILD_SECTION_SUMMARIES));
    }

    /**
     * Strips the {@code :<slug>} suffix Confluence kinds carry. {@code confluence-page-import:acme}
     * is the same kind as {@code confluence-page-import} — {@code JobService} already routes on
     * {@code split(":")[0]}, and matching the full string here would silently exempt every real
     * Confluence job from validation.
     */
    private static String bareKind(String kind) {
        if (kind == null) {
            return "";
        }
        int colon = kind.indexOf(':');
        return (colon < 0 ? kind : kind.substring(0, colon)).trim();
    }

    /**
     * Whether a job of this kind extracts a knowledge graph.
     *
     * <p>
     * No settings are consulted: {@code kg-extract} is a dedicated kind, so the job's existence is
     * the request. That is the whole difference from summaries, which ride along inside a document
     * ingest and therefore need a flag to say whether they were wanted.
     */
    static boolean willExtractKg(String kind) {
        return IngestJobKind.KG_EXTRACT.getValue().equals(bareKind(kind));
    }

    @Override
    public EnqueueRequest validate(EnqueueRequest req) {
        if (willSummarize(req.kind(), req.settings())) {
            require(req, IngestPrompts.summarizePromptName(req.settings()),
                IngestPrompts.SETTING_SUMMARIZE_PROMPT, SystemPromptType.SUMMARIZE,
                "produces summaries");
        }
        if (willExtractKg(req.kind())) {
            require(req, IngestPrompts.kgPromptName(req.settings()),
                IngestPrompts.SETTING_KG_PROMPT, SystemPromptType.KG, "extracts a knowledge graph");
        }
        return req;
    }

    private void require(EnqueueRequest req, String name, String settingKey, SystemPromptType type,
        String because) {
        if (name == null) {
            throw new IllegalArgumentException(
                "A '" + bareKind(req.kind()) + "' job " + because + ", so setting '" + settingKey
                    + "' is required and must name a " + type + " system prompt.");
        }
        // Resolves name AND type, and throws with the valid names listed.
        systemPromptService.requirePrompt(name, type);
    }
}
