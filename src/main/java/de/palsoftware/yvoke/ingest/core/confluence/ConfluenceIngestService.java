package de.palsoftware.yvoke.ingest.core.confluence;

import de.palsoftware.yvoke.document.core.model.ChunkInsert;
import de.palsoftware.yvoke.document.core.model.DocumentKind;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import de.palsoftware.yvoke.ingest.core.model.IngestJobKind;
import de.palsoftware.yvoke.ingest.core.model.Section;
import de.palsoftware.yvoke.ingest.core.service.SectionSummarizer;
import de.palsoftware.yvoke.rag.retrieval.EmbeddingService;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueRequest;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueResult;
import de.palsoftware.yvoke.shared.jobengine.model.IngestionJob;
import de.palsoftware.yvoke.shared.jobengine.model.JobContext;
import de.palsoftware.yvoke.shared.jobengine.model.JobCounts;
import de.palsoftware.yvoke.shared.jobengine.model.JobStatus;
import de.palsoftware.yvoke.shared.jobengine.repository.JobRepository;
import de.palsoftware.yvoke.shared.jobengine.service.JobService;
import de.palsoftware.yvoke.shared.jobengine.model.JobStep;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Crawls a Confluence instance and ingests its pages.
 *
 * <p>
 * RUNBOOK — {@code source_file} identity and the canonical domain. A page's identity is built from
 * the instance's canonical base URL (see {@link ConfluenceDomains}), which now lower-cases the
 * scheme and host and drops a default port; the previous normalization did neither. A corpus
 * ingested BEFORE that change under a mixed-case host or an explicit {@code :443} therefore keys on
 * a different {@code source_file} than a re-ingest would produce, and the re-ingest would mint a
 * second full set of documents instead of updating the first. This deployment has no such corpus
 * (zero {@code kind='confluence'} documents), so nothing is rewritten here. Before the first sync
 * in ANY other environment, check for affected rows with:
 *
 * <pre>
 * SELECT DISTINCT split_part(metadata-&gt;&gt;'source_file', '/spaces/', 1)
 * FROM documents WHERE kind = 'confluence';
 * </pre>
 *
 * Every value returned must already be lower-case and free of {@code :443}; anything else needs a
 * one-off {@code source_file} rewrite before the sync, or the corpus doubles.
 */
@Service
public class ConfluenceIngestService {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceIngestService.class);

    /** Job-settings keys of the target snapshot written at crawl time. Never a credential. */
    static final String SETTING_INSTANCE_ID = "instanceId";
    static final String SETTING_INSTANCE_NAME = "instanceName";
    static final String SETTING_DOMAIN = "domain";
    static final String SETTING_SPACE = "space";
    static final String SETTING_COLLECTION = "collection";
    static final String SETTING_TAG = "tag";
    static final String SETTING_PROCESS_ATTACHMENTS = "processAttachments";

    private final ConfluenceInstanceRepository instanceRepository;
    private final ConfluenceClientService confluenceClient;
    private final ConfluenceConverter confluenceConverter;
    private final EmbeddingService embeddingService;
    private final DocumentRepository documentRepository;
    private final SectionSummarizer sectionSummarizer;
    private final JobRepository jobRepository;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationContext applicationContext;

    public ConfluenceIngestService(ConfluenceInstanceRepository instanceRepository,
        ConfluenceClientService confluenceClient, ConfluenceConverter confluenceConverter,
        EmbeddingService embeddingService, DocumentRepository documentRepository,
        SectionSummarizer sectionSummarizer, JobRepository jobRepository,
        PlatformTransactionManager transactionManager, ApplicationContext applicationContext) {
        this.instanceRepository = instanceRepository;
        this.confluenceClient = confluenceClient;
        this.confluenceConverter = confluenceConverter;
        this.embeddingService = embeddingService;
        this.documentRepository = documentRepository;
        this.sectionSummarizer = sectionSummarizer;
        this.jobRepository = jobRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.applicationContext = applicationContext;
    }

    private JobService getJobService() {
        return applicationContext.getBean(JobService.class);
    }

    /**
     * Crawls one Confluence instance and fans out one page-import job per changed page.
     *
     * <p>
     * The instance is resolved ONCE, here, and the resolved target is snapshotted into every child
     * job (see {@link #enqueuePageImport}). Re-reading the connector configuration when a child job
     * eventually runs is what let two instances cross-contaminate each other's targets: a queue
     * drains over hours, and whichever configuration happened to be live at that moment decided
     * where the page landed.
     */
    public JobCounts ingest(JobContext ctx) {
        UUID jobId = ctx.job() == null ? null : ctx.job().id();
        ConfluenceInstance instance = resolveInstance(ctx.job());
        if (!instance.enabled()) {
            throw new IllegalStateException(
                "Confluence instance '" + instance.name() + "' is disabled.");
        }

        String collection = instance.targetCollection();
        String tag = instance.targetTag();
        String space = instance.space();
        String rootPageId = instance.rootPageId();
        String baseUrl = instance.domain();

        ctx.report(JobStep.CRAWL, 5,
            "Crawling Confluence descendant pages for parent " + rootPageId);

        AtomicInteger docsFound = new AtomicInteger(0);
        AtomicInteger docsQueued = new AtomicInteger(0);
        AtomicInteger docsAlreadyQueued = new AtomicInteger(0);
        /** Pages the queue refused outright — kept apart from "already queued", which is normal. */
        AtomicInteger docsNotQueued = new AtomicInteger(0);

        confluenceClient.crawlAllDescendantPages(instance, batch -> {
            // Once per batch, BEFORE its pages are fanned out. Stopping a crawl has to stop the
            // fan-out: without this the crawl kept paging Confluence and kept enqueuing pages into
            // the queue an operator had just cancelled, and then completed — flipping its own row
            // out of 'cancelled' and freeing the admission slot for a second crawl of the same
            // instance while this one was still running.
            checkCancellation(jobId);
            // ...and once per batch, the row this crawl resolved at start-up. Deleting an instance
            // cancels its QUEUED jobs and leaves RUNNING ones alone, but the running job is usually
            // this crawl — the PRODUCER — which holds `instance` in memory and would immediately
            // refill the queue the delete just emptied, with pages that can then only fail at
            // execution with "Confluence instance ... does not exist".
            checkInstanceStillExists(instance);
            for (Map<String, Object> page : batch) {
                docsFound.incrementAndGet();
                String pageId = (String) page.get("id");
                String title = (String) page.get("title");
                Integer pageVersion = extractPageVersion(page);

                if (isPageUpToDate(collection, tag, sourceFile(baseUrl, space, pageId),
                    pageVersion)) {
                    log.info(
                        "Skipping enqueuing Confluence page import: {} (ID: {}) has not changed (version: {})",
                        title, pageId, pageVersion);
                    continue;
                }

                int currentCount = docsQueued.get();
                ctx.report(JobStep.DISPATCH, 15 + Math.min(75, currentCount),
                    "Queuing page: " + title);

                // A page already queued or running (a re-triggered crawl draining alongside this
                // one) is a normal SKIP, counted and carried on from. It must never abort the
                // crawl: this loop is the batch consumer, so one aborted page would leave the rest
                // of the tree uncrawled and a partial corpus a retry could not reproduce.
                //
                // The same applies to the one case enqueue still THROWS — both of its attempts saw
                // an active job appear and finish — so it is caught here rather than allowed to
                // abandon the remaining page tree. Deliberately IllegalStateException only: an
                // unknown collection or an undeclared tag is an IllegalArgumentException from the
                // enqueue validators, applies to EVERY page, and must keep failing the crawl loudly
                // instead of being swallowed page by page.
                try {
                    if (enqueuePageImport(instance, pageId, title, pageVersion).created()) {
                        docsQueued.incrementAndGet();
                    } else {
                        docsAlreadyQueued.incrementAndGet();
                        log.info("Confluence page import already active for {} (ID: {}); skipping",
                            title, pageId);
                    }
                } catch (IllegalStateException e) {
                    docsNotQueued.incrementAndGet();
                    log.warn("Could not enqueue Confluence page import for {} (ID: {}); skipping it"
                        + " and continuing the crawl", title, pageId, e);
                }
            }
        });

        // Curation drift, measured once per crawl (see #unlabelledPageCount): -1 means "not
        // applicable or unavailable", never 0, which is a real and reassuring answer.
        int unlabelled = unlabelledPageCount(instance, docsFound.get());
        String unlabelledSuffix = unlabelled <= 0 ? ""
            : "; " + unlabelled + " page(s) under the root carry none of the include labels ("
                + instance.includeLabels() + ") and are invisible to the knowledge base";

        if (docsFound.get() == 0) {
            throw new IllegalStateException(
                "No pages found under parent " + rootPageId + unlabelledSuffix);
        }

        ctx.report(JobStep.DISPATCH, 100,
            "Confluence Crawl completed successfully: queued " + docsQueued.get() + " page(s), "
                + docsAlreadyQueued.get() + " already queued, " + docsNotQueued.get()
                + " could not be queued" + unlabelledSuffix);
        log.info(
            "Confluence crawl of instance '{}' complete: found {} pages, queued {} pages for "
                + "ingestion, {} already had an active job, {} could not be queued",
            instance.name(), docsFound.get(), docsQueued.get(), docsAlreadyQueued.get(),
            docsNotQueued.get());

        return new JobCounts(docsQueued.get(), 0, 0, 0, 0);
    }

    /**
     * How many pages under the configured root carry NONE of the instance's include labels.
     *
     * <p>
     * An operator selects pages for the knowledge base by hand-applying a Confluence label, and the
     * crawl's CQL filters on it. A page nobody labels is therefore invisible to the knowledge base
     * forever, and nothing anywhere says so — the drift is normally discovered much later, as a bad
     * answer. One extra CQL count (the same query with the include-label filter dropped) minus the
     * pages this crawl actually found turns that silence into a number on the job.
     *
     * @return the count, or -1 when it does not apply (no include label is configured, so every
     *         page under the root is ingested) or could not be obtained. A failure here degrades to
     *         "unknown" rather than failing the crawl: the corpus is already ingested at this
     *         point, and this is a diagnostic, not content.
     */
    private int unlabelledPageCount(ConfluenceInstance instance, int pagesFound) {
        if (instance.includeLabels() == null || instance.includeLabels().isBlank()) {
            return -1;
        }
        try {
            int all = confluenceClient.countPagesIgnoringIncludeLabels(instance);
            return Math.max(0, all - pagesFound);
        } catch (RuntimeException e) {
            log.warn("Could not count the unlabelled pages under root {} of instance '{}'",
                instance.rootPageId(), instance.name(), e);
            return -1;
        }
    }

    /**
     * Resolves the instance a crawl job belongs to, preferring the id snapshotted into its settings
     * over the slug embedded in its kind.
     *
     * <p>
     * The slug is a freely editable field on the connectors form and a crawl can sit queued for
     * hours, so the slug it was enqueued under is not a stable identifier. If that slug is later
     * taken by a DIFFERENT instance, slug resolution silently hands the crawl the wrong site: it
     * would authenticate with the other site's credentials, walk the other site's page tree, and
     * fan out page jobs into the other site's collection — all under a job the operator started for
     * this one. The id cannot be edited, so it is the identity; the slug survives only as the
     * fallback for jobs queued before the id was snapshotted, and as the human-readable label in
     * the kind.
     */
    private ConfluenceInstance resolveInstance(IngestionJob job) {
        String rawId = job == null ? null : string(job.settings().get(SETTING_INSTANCE_ID));
        if (rawId != null) {
            UUID instanceId;
            try {
                instanceId = UUID.fromString(rawId);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                    "Confluence crawl job carries a malformed instance id; re-run the sync from the "
                        + "connectors page.",
                    e);
            }
            String name = string(job.settings().get(SETTING_INSTANCE_NAME));
            return instanceRepository.findById(instanceId)
                .orElseThrow(() -> new IllegalStateException(
                    "Confluence instance '" + (name != null ? name : instanceId)
                        + "' no longer exists; re-create it on the connectors page and re-run the"
                        + " sync."));
        }
        String slug = instanceSlug(job == null ? null : job.kind());
        return instanceRepository.findBySlug(slug)
            .orElseThrow(() -> new IllegalStateException("Confluence instance '" + slug
                + "' does not exist; configure it on the connectors page."));
    }

    /**
     * The settings a crawl job carries, so its instance survives a later slug rename.
     *
     * <p>
     * Built here rather than in the controller so the writer and {@link #resolveInstance} share one
     * definition of the keys — they drifted apart once already, which is how the crawl ended up
     * being the only Confluence job that did not snapshot its target.
     */
    public static Map<String, Object> crawlSettings(UUID instanceId, String instanceName) {
        Map<String, Object> settings = new HashMap<>();
        settings.put(SETTING_INSTANCE_ID, String.valueOf(instanceId));
        settings.put(SETTING_INSTANCE_NAME, instanceName);
        return settings;
    }

    private static String instanceSlug(String kind) {
        if (kind == null) {
            return ConfluenceInstance.DEFAULT_SLUG;
        }
        String[] parts = kind.split(":", 2);
        return parts.length > 1 && !parts[1].isBlank() ? parts[1].trim()
            : ConfluenceInstance.DEFAULT_SLUG;
    }

    /**
     * A page's {@code source_file} — its identity — is the canonical base URL plus the space and
     * page id.
     */
    private static String sourceFile(String baseUrl, String space, String pageId) {
        return baseUrl.endsWith("/wiki") || baseUrl.contains("/wiki/")
            ? baseUrl + "/spaces/" + space + "/pages/" + pageId
            : baseUrl + "/wiki/spaces/" + space + "/pages/" + pageId;
    }

    private Integer extractPageVersion(Map<String, Object> page) {
        Object versionObj = page.get("version");
        if (versionObj instanceof Map) {
            Object numberObj = ((Map<?, ?>) versionObj).get("number");
            if (numberObj instanceof Number) {
                return ((Number) numberObj).intValue();
            }
        }
        return null;
    }

    private boolean isPageUpToDate(String collection, String tag, String sourceFile,
        Integer pageVersion) {
        if (pageVersion == null) {
            return false;
        }
        Optional<DocumentRepository.DocumentMetadataAndStatus> existing = documentRepository
            .getMetadataAndStatus(collection, tag, sourceFile, DocumentKind.CONFLUENCE.getValue());
        if (existing.isPresent()) {
            DocumentRepository.DocumentMetadataAndStatus statusAndMeta = existing.get();
            if ("completed".equals(statusAndMeta.ingestionStatus())) {
                return pageVersion.equals(parseVersion(statusAndMeta.pageVersionStr()));
            }
        }
        return false;
    }

    private static Integer parseVersion(String pageVersionStr) {
        if (pageVersionStr == null) {
            return null;
        }
        try {
            return Integer.valueOf(pageVersionStr);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Enqueues one page import, carrying a SNAPSHOT of the resolved target so the job is
     * self-contained: it never re-reads the connector configuration, so editing, disabling or
     * deleting the instance while the queue drains cannot move pages into another collection.
     *
     * <p>
     * The kind carries the instance slug ({@code confluence-page-import:<slug>}): the job engine
     * routes on the base kind before the {@code ':'}, and the jobs list renders the kind verbatim,
     * so the originating connector is visible with no template change.
     *
     * <p>
     * SECURITY: {@code ingestion_jobs.settings} is JSONB in the database and is rendered on the job
     * detail page — the API token (and any other secret) must never be put here. Only the instance
     * ID is stored; credentials are resolved from the instance row at execution time.
     */
    private EnqueueResult enqueuePageImport(ConfluenceInstance instance, String pageId,
        String title, Integer pageVersion) {
        Map<String, Object> settings = new HashMap<>();
        settings.put("pageId", pageId);
        settings.put("title", title);
        if (pageVersion != null) {
            settings.put("pageVersion", pageVersion);
        }
        settings.put(SETTING_INSTANCE_ID, String.valueOf(instance.id()));
        settings.put(SETTING_INSTANCE_NAME, instance.name());
        settings.put(SETTING_DOMAIN, instance.domain());
        settings.put(SETTING_SPACE, instance.space());
        settings.put(SETTING_COLLECTION, instance.targetCollection());
        if (instance.targetTag() != null) {
            settings.put(SETTING_TAG, instance.targetTag());
        }
        settings.put(SETTING_PROCESS_ATTACHMENTS, instance.processAttachments());

        return getJobService().enqueue(new EnqueueRequest(
            IngestJobKind.CONFLUENCE_PAGE_IMPORT.getValue() + ":" + instance.slug(),
            "confluence/" + instance.space() + "/" + pageId, instance.targetTag(),
            instance.targetCollection(), settings));
    }

    /**
     * The ingest target of one page job, snapshotted at crawl time.
     *
     * @param instance the row the CREDENTIALS come from — nothing else is read from it
     */
    private record PageTarget(ConfluenceInstance instance, String baseUrl, String space,
        String collection, String tag, boolean processAttachments) {}

    public JobCounts ingestPage(JobContext ctx, String pageId, String title) {
        IngestionJob job = ctx.job();
        UUID jobId = job == null ? null : job.id();
        PageTarget target = targetOf(job);
        String collection = target.collection();
        String tag = target.tag();

        checkCancellation(jobId);

        Integer pageVersion = null;
        if (job != null && job.settings() != null) {
            Object ver = job.settings().get("pageVersion");
            if (ver instanceof Number) {
                pageVersion = ((Number) ver).intValue();
            }
        }

        String sourceFile = sourceFile(target.baseUrl(), target.space(), pageId);

        // Skip if page already exists and is up to date
        if (pageVersion != null && isPageUpToDate(collection, tag, sourceFile, pageVersion)) {
            log.info("Skipping Confluence page: {} (ID: {}) has not changed (version: {})", title,
                pageId, pageVersion);
            ctx.report(JobStep.INJECT, 100, "Skipping page (already up to date): " + title);
            return new JobCounts(1, 0, 0, 0, 0);
        }

        ctx.report(JobStep.CHUNK, 10, "Converting page to markdown: " + title);

        // Get the body. A fetch/transport failure THROWS (see
        // ConfluenceClientService#getPageBodyStorage) so a throttled or unauthorized crawl can
        // never complete green with an empty corpus.
        String xhtml = confluenceClient.getPageBodyStorage(target.instance(), pageId);

        // Between the (slow, remote) fetch and the equally slow embed: a stop issued while this
        // page was in flight takes effect here instead of after the whole page finishes.
        checkCancellation(jobId);

        List<Section> sections = List.of();
        if (xhtml != null && !xhtml.isBlank()) {
            String markdown = confluenceConverter.convertToMarkdown(target.instance(),
                target.processAttachments(), pageId, xhtml);
            // Chunk. ConfluenceSectionBuilder re-roots the page under its title so heading-less
            // bodies, a single leading H1 and pre-heading prose all survive, and applies the shared
            // oversized-section split.
            ctx.report(JobStep.CHUNK, 40, "Chunking markdown sections");
            sections = ConfluenceSectionBuilder.build(title, markdown);
        }

        if (sections.isEmpty()) {
            // A page that genuinely has no content (a hierarchy-only parent, a page holding just a
            // macro) is a SKIP, not a failure. Throwing here left NO documents row behind, and
            // isPageUpToDate only suppresses a page whose row is 'completed' — so the next crawl
            // re-enqueued it and it failed again, forever. Persisting a completed zero-chunk
            // document records confluence_page_version and lets the version-skip do its job; the
            // upsert also clears any chunks the page had before its content was removed.
            log.info("Confluence page has no ingestible content, recording it as an empty document:"
                + " {} (ID: {})", title, pageId);
            ctx.report(JobStep.INJECT, 80, "Recording empty page: " + title);
            persistDocument(collection, tag, sourceFile, List.of(), List.of(), List.of(), title,
                pageVersion);
            ctx.report(JobStep.INJECT, 100, "Skipped page (no ingestible content): " + title);
            return new JobCounts(1, 0, 0, 0, 0);
        }

        // Embed. The embedded text and the stored text are the SAME string: the semantic lane and
        // the BM25 lane must score identical content (they previously differed by the
        // "> Section path: …" breadcrumb, which only the embedding saw). Both now carry that
        // breadcrumb, and it starts at the page title — the densest topical signal on a wiki page,
        // which exists nowhere in the page body.
        ctx.report(JobStep.EMBED, 60, "Embedding " + sections.size() + " sections");
        List<String> chunkTexts = sections.stream().map(Section::toChunkText).toList();
        List<float[]> embeddings = embeddingService.embedBatch(chunkTexts);

        // Last point before the write: a cancelled job leaves the document untouched rather than
        // half-replacing its chunk set.
        checkCancellation(jobId);

        // Persist to Postgres
        ctx.report(JobStep.INJECT, 80, "Persisting document");
        UUID documentId = persistDocument(collection, tag, sourceFile, sections, chunkTexts,
            embeddings, title, pageVersion);

        // Generate hierarchical summaries
        ctx.report(JobStep.INJECT, 90, "Generating section summaries");
        sectionSummarizer.generateSummaries(documentId, sections, ctx.job().id(), ctx, null);

        ctx.report(JobStep.INJECT, 100, "Successfully ingested page: " + title);
        return new JobCounts(1, sections.size(), 0, 0, 0);
    }

    /**
     * Cooperative cancellation, mirroring {@code DocumentIngestService}: a page job that is no
     * longer RUNNING (an admin stopped it, or a bulk cancel swept its kind) aborts at the next
     * checkpoint instead of finishing its fetch/embed/persist cycle. The thrown exception reaches
     * {@code JobService.execute}, whose markFailed leaves an already-cancelled row alone.
     *
     * <p>
     * A missing row (deleted collection cascade) is treated as cancelled — there is nothing left to
     * write to. A null id only happens outside the worker.
     */
    private void checkCancellation(UUID jobId) {
        if (jobId == null) {
            return;
        }
        JobStatus status = jobRepository.findStatusById(jobId).orElse(JobStatus.CANCELLED);
        if (status != JobStatus.RUNNING) {
            throw new IllegalStateException("Job was cancelled by administrator");
        }
    }

    /**
     * Stops a crawl whose instance was deleted while it was running.
     *
     * <p>
     * One {@code SELECT EXISTS} per page batch (50 pages), not per page. Throwing here reaches
     * {@code JobService.execute}, which records the message on the crawl's own job row, so the
     * operator sees why it stopped on the page they deleted from.
     */
    private void checkInstanceStillExists(ConfluenceInstance instance) {
        if (!instanceRepository.existsById(instance.id())) {
            throw new IllegalStateException("Confluence instance '" + instance.name()
                + "' was deleted while this crawl was running; stopping the crawl so it cannot "
                + "queue page imports that can no longer resolve it.");
        }
    }

    /**
     * Reads the page job's target from the JOB, and looks the instance up only for its credentials.
     *
     * <p>
     * A job enqueued before the snapshot existed carries none of these keys; such a job falls back
     * to the instance's current settings, which is exactly the old behaviour and only applies to
     * jobs already queued across the upgrade.
     */
    private PageTarget targetOf(IngestionJob job) {
        Map<String, Object> settings =
            job == null || job.settings() == null ? Map.of() : job.settings();
        ConfluenceInstance instance = resolveCredentials(job, settings);
        if (string(settings.get(SETTING_INSTANCE_ID)) == null) {
            assertJobCollectionStillMatches(job, instance);
            return new PageTarget(instance, instance.domain(), instance.space(),
                instance.targetCollection(), instance.targetTag(), instance.processAttachments());
        }
        String domain = required(settings, SETTING_DOMAIN);
        assertSameSite(instance, domain);
        return new PageTarget(instance, domain, required(settings, SETTING_SPACE),
            required(settings, SETTING_COLLECTION), string(settings.get(SETTING_TAG)),
            booleanValue(settings.get(SETTING_PROCESS_ATTACHMENTS)));
    }

    /**
     * The snapshot decides WHERE the page is written; the live instance row decides WHERE IT IS
     * READ FROM (the body fetch and the attachment/user lookups all go through the instance's
     * client). If the two disagree the job is not recoverable, it is WRONG — so it fails here.
     *
     * <p>
     * Wave 3a ships a single-form connector page, so "connect the other wiki" is "edit the
     * {@code default} instance". With hundreds of page jobs queued, every one of them would then
     * fetch {@code GET https://siteB/rest/api/content/<idFromSiteA>}. Confluence Cloud content ids
     * are small per-site integers, so collisions are ordinary rather than exotic: on a hit, site
     * B's body lands under site A's {@code source_file}, in site A's collection, under site A's
     * crawl-time title, and the job completes GREEN. On a miss it is hundreds of 404s that never
     * mention that the connector moved.
     */
    private static void assertSameSite(ConfluenceInstance instance, String snapshotDomain) {
        String live = ConfluenceDomains.canonicalizeOrKeep(instance.domain());
        String snapshot = ConfluenceDomains.canonicalizeOrKeep(snapshotDomain);
        if (live == null || !live.equalsIgnoreCase(snapshot)) {
            throw new IllegalStateException("Confluence instance '" + instance.name()
                + "' now points at " + live + ", but this page was crawled from " + snapshot
                + ", so its body would be fetched from the wrong site; re-run the sync from the "
                + "connectors page.");
        }
    }

    /**
     * A legacy (pre-snapshot) job carries its own collection — {@code JobRowMapper} reads it, and
     * for such a row it is exactly what
     * {@link de.palsoftware.yvoke.ingest.core.service.CollectionTagEnqueueValidator} normalized at
     * enqueue time. Taking the collection from the LIVE instance instead meant that switching the
     * connector's target while a partially drained queue finished (opening the connectors page is
     * the natural first action after an upgrade) silently mixed two corpora: every remaining job
     * wrote into the NEW collection under the OLD site's {@code source_file}, with no error.
     *
     * <p>
     * The job is REFUSED rather than re-targeted onto its own collection: re-targeting would also
     * have to invent a tag, and for an untagged collection the job's tag is legitimately empty
     * while the connector's is not.
     */
    private static void assertJobCollectionStillMatches(IngestionJob job,
        ConfluenceInstance instance) {
        String jobCollection = job == null ? null : string(job.collection());
        if (jobCollection == null) {
            return;
        }
        String target = string(instance.targetCollection());
        if (!jobCollection.equalsIgnoreCase(target)) {
            throw new IllegalStateException("This Confluence page job targets collection '"
                + jobCollection + "', but instance '" + instance.name() + "' now writes to '"
                + target + "'; re-run the sync from the connectors page.");
        }
    }

    private static String required(Map<String, Object> settings, String key) {
        String value = string(settings.get(key));
        if (value == null) {
            throw new IllegalStateException("Confluence page job is missing its '" + key
                + "' target setting; re-run the sync from the connectors page.");
        }
        return value;
    }

    /** JSONB round-trips a boolean as a Boolean, but a hand-edited job row may hold "true". */
    private static boolean booleanValue(Object value) {
        return value instanceof Boolean flag ? flag
            : value != null && Boolean.parseBoolean(value.toString().trim());
    }

    /**
     * The one thing that is NOT snapshotted: the API token. Secrets never go into
     * {@code ingestion_jobs.settings} (JSONB in the database), so the instance row is read here —
     * by the id recorded at crawl time, so the credentials belong to the site the page came from
     * even if another instance has since been renamed onto the same slug.
     *
     * <p>
     * {@code enabled} is checked here because this is the credential path, and disabling an
     * instance is the only STOP lever an operator has. It used to stop nothing: the crawl refused
     * to start, but the page jobs already queued kept authenticating with the still-stored token
     * and ran to completion — so a token rotated out at Atlassian, or a sync discovered to be aimed
     * at the wrong space, could only be stopped by restarting the application. It is deliberately
     * NOT a target: refusing on it cannot re-target anything.
     */
    private ConfluenceInstance resolveCredentials(IngestionJob job, Map<String, Object> settings) {
        String rawId = string(settings.get(SETTING_INSTANCE_ID));
        ConfluenceInstance instance;
        if (rawId == null) {
            instance = resolveInstance(job);
        } else {
            UUID instanceId;
            try {
                instanceId = UUID.fromString(rawId);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                    "Confluence page job carries a malformed instance id; re-run the sync from the "
                        + "connectors page.",
                    e);
            }
            String name = string(settings.get(SETTING_INSTANCE_NAME));
            instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new IllegalStateException("Confluence instance '"
                    + (name != null ? name : instanceId)
                    + "' no longer exists, so its API token cannot be resolved; re-run the sync "
                    + "from the connectors page."));
        }
        if (!instance.enabled()) {
            throw new IllegalStateException("Confluence instance '" + instance.name()
                + "' is disabled, so its queued page jobs stop here; re-enable it on the connectors"
                + " page and re-run the sync.");
        }
        return instance;
    }

    /** Job settings arrive from JSONB, so a value is whatever Jackson made of it. */
    private static String string(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private UUID persistDocument(String collection, String tag, String sourceFile,
        List<Section> sections, List<String> chunkTexts, List<float[]> embeddings, String title,
        Integer pageVersion) {
        List<ChunkInsert> inserts = new ArrayList<>(sections.size());
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            inserts.add(new ChunkInsert(chunkTexts.get(i), embeddings.get(i), s.headingPath(),
                s.title(), s.depth(), i)); // sort_order
        }

        return transactionTemplate.execute(status -> {
            // Keyed on source_file only: two Confluence pages may share a title (and a blank one
            // normalises to "Untitled"), so title matching would collapse them onto one row.
            UUID documentId = documentRepository.upsertDocumentBySourceFile(collection, tag,
                sourceFile, DocumentKind.CONFLUENCE.getValue(), title);
            if (pageVersion != null) {
                documentRepository.updateMetadataKey(documentId, "confluence_page_version",
                    pageVersion);
            }
            // The delete always runs: a page whose content was removed must lose its old chunks.
            documentRepository.deleteContentForDocument(documentId);
            if (!inserts.isEmpty()) {
                documentRepository.insertChunks(documentId, collection, tag, sourceFile,
                    DocumentKind.CONFLUENCE.getValue(), inserts);
            }
            documentRepository.updateIngestionStatus(documentId, "completed");
            return documentId;
        });
    }
}
