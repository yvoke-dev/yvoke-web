package de.palsoftware.yvoke.ingest.core.confluence;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.document.core.model.ChunkInsert;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository.DocumentMetadataAndStatus;
import de.palsoftware.yvoke.ingest.core.model.IngestJobKind;
import de.palsoftware.yvoke.ingest.core.model.MarkdownTree;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class ConfluenceIngestServiceTest {

    private static final UUID INSTANCE_ID = UUID.randomUUID();
    private static final String SLUG = "icc-wiki";
    private static final String CRAWL_KIND =
        IngestJobKind.CONFLUENCE_IMPORT.getValue() + ":" + SLUG;
    private static final String PAGE_KIND =
        IngestJobKind.CONFLUENCE_PAGE_IMPORT.getValue() + ":" + SLUG;
    private static final String SOURCE_FILE = "https://example.com/wiki/spaces/SPACE/pages/page-1";

    private ConfluenceInstanceRepository instanceRepository;
    private ConfluenceClientService confluenceClient;
    private ConfluenceConverter confluenceConverter;
    private EmbeddingService embeddingService;
    private DocumentRepository documentRepository;
    private SectionSummarizer sectionSummarizer;
    private ApplicationContext applicationContext;
    private JobService jobService;
    private JobRepository jobRepository;
    private ConfluenceIngestService service;

    private ConfluenceInstance instance;

    private final PlatformTransactionManager transactionManager = new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition)
            throws TransactionException {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) throws TransactionException {}

        @Override
        public void rollback(TransactionStatus status) throws TransactionException {}
    };

    @BeforeEach
    void setUp() {
        instanceRepository = mock(ConfluenceInstanceRepository.class);
        confluenceClient = mock(ConfluenceClientService.class);
        confluenceConverter = mock(ConfluenceConverter.class);
        embeddingService = mock(EmbeddingService.class);
        documentRepository = mock(DocumentRepository.class);
        sectionSummarizer = mock(SectionSummarizer.class);
        applicationContext = mock(ApplicationContext.class);
        jobService = mock(JobService.class);
        jobRepository = mock(JobRepository.class);

        when(applicationContext.getBean(JobService.class)).thenReturn(jobService);
        // Enqueue is idempotent per unit of work: the default is "this call created the job".
        when(jobService.enqueue(any()))
            .thenAnswer(invocation -> EnqueueResult.created(UUID.randomUUID()));
        // Cooperative cancellation reads the live status; RUNNING is "carry on".
        when(jobRepository.findStatusById(any())).thenReturn(Optional.of(JobStatus.RUNNING));

        configureTag("v1");

        // One embedding per input keeps the stub honest when a page splits into several chunks.
        when(embeddingService.embedBatch(any())).thenAnswer(invocation -> {
            List<?> inputs = invocation.getArgument(0);
            return inputs.stream().map(in -> new float[1024]).toList();
        });

        service = new ConfluenceIngestService(instanceRepository, confluenceClient,
            confluenceConverter, embeddingService, documentRepository, sectionSummarizer,
            jobRepository, transactionManager, applicationContext);
    }

    private void configureTag(String tag) {
        configureInstance(tag, "");
    }

    private void configureInstance(String tag, String includeLabels) {
        instance = new ConfluenceInstance(INSTANCE_ID, "iCC Wiki", SLUG, "https://example.com/wiki",
            "svc@example.com", "enc:token", "keyA", "SPACE", "123", includeLabels, "", "coll", tag,
            false, true, null, null);
        when(instanceRepository.findBySlug(SLUG)).thenReturn(Optional.of(instance));
        when(instanceRepository.findById(INSTANCE_ID)).thenReturn(Optional.of(instance));
        when(instanceRepository.existsById(INSTANCE_ID)).thenReturn(true);
    }

    /** Every progress message the crawl reported, in order. */
    private static List<String> reportedMessages(JobContext ctx) {
        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        verify(ctx, atLeastOnce()).report(any(), anyInt(), messages.capture());
        return messages.getAllValues();
    }

    private JobContext crawlJobContext() {
        return crawlJobContext(CRAWL_KIND, crawlSettings(INSTANCE_ID));
    }

    private JobContext crawlJobContext(String kind, Map<String, Object> settings) {
        JobContext ctx = mock(JobContext.class);
        IngestionJob job = mock(IngestionJob.class);
        when(ctx.job()).thenReturn(job);
        when(job.id()).thenReturn(UUID.randomUUID());
        when(job.kind()).thenReturn(kind);
        when(job.settings()).thenReturn(settings);
        return ctx;
    }

    /** Exactly the settings the connectors page snapshots into a crawl job. */
    private Map<String, Object> crawlSettings(UUID instanceId) {
        Map<String, Object> settings = new HashMap<>();
        settings.put("instanceId", instanceId.toString());
        settings.put("instanceName", "iCC Wiki");
        return settings;
    }

    /** Exactly the settings {@code ingest} snapshots into a page job. */
    private Map<String, Object> pageSettings(Integer pageVersion) {
        Map<String, Object> settings = new HashMap<>();
        settings.put("pageId", "page-1");
        settings.put("title", "Page 1");
        if (pageVersion != null) {
            settings.put("pageVersion", pageVersion);
        }
        settings.put("instanceId", INSTANCE_ID.toString());
        settings.put("instanceName", instance.name());
        settings.put("domain", instance.domain());
        settings.put("space", instance.space());
        settings.put("collection", instance.targetCollection());
        if (instance.targetTag() != null) {
            settings.put("tag", instance.targetTag());
        }
        settings.put("processAttachments", instance.processAttachments());
        return settings;
    }

    private JobContext pageJobContext(Integer pageVersion) {
        return pageJobContext(pageSettings(pageVersion));
    }

    private JobContext pageJobContext(Map<String, Object> settings) {
        JobContext ctx = mock(JobContext.class);
        IngestionJob job = mock(IngestionJob.class);
        when(ctx.job()).thenReturn(job);
        when(job.id()).thenReturn(UUID.randomUUID());
        when(job.kind()).thenReturn(PAGE_KIND);
        when(job.settings()).thenReturn(settings);
        return ctx;
    }

    @SafeVarargs
    private void crawlReturns(Map<String, Object>... pages) {
        doAnswer(invocation -> {
            Consumer<List<Map<String, Object>>> consumer = invocation.getArgument(1);
            consumer.accept(List.of(pages));
            return null;
        }).when(confluenceClient).crawlAllDescendantPages(eq(instance), any());
    }

    /** A paged crawl: the consumer is handed one batch per call, as the real client does. */
    @SafeVarargs
    private void crawlReturnsBatches(List<Map<String, Object>>... batches) {
        doAnswer(invocation -> {
            Consumer<List<Map<String, Object>>> consumer = invocation.getArgument(1);
            for (List<Map<String, Object>> batch : batches) {
                consumer.accept(batch);
            }
            return null;
        }).when(confluenceClient).crawlAllDescendantPages(eq(instance), any());
    }

    private static Map<String, Object> page(String id, String title, int version) {
        return Map.of("id", id, "title", title, "version", Map.of("number", version));
    }

    @Test
    void crawlResolvesItsInstanceBySnapshottedIdNotTheMutableSlugInItsKind() {
        // The slug is a freely editable field on the connectors form, and a crawl can sit queued
        // for hours. If the slug it was enqueued under is later taken by a DIFFERENT Confluence
        // site, resolving by slug at execution time runs the crawl against that other site — with
        // this instance's credentials — and lands its pages in that site's collection.
        ConfluenceInstance impostor = new ConfluenceInstance(UUID.randomUUID(), "Other Wiki", SLUG,
            "https://other.example.com/wiki", "other@example.com", "enc:other", "keyB", "OTHER",
            "999", "", "", "other-coll", null, false, true, null, null);
        when(instanceRepository.findBySlug(SLUG)).thenReturn(Optional.of(impostor));

        crawlReturns(page("page-1", "Page 1", 4));

        service.ingest(crawlJobContext());

        // Crawled the instance the job was created for, never the one that now holds the slug.
        verify(confluenceClient).crawlAllDescendantPages(eq(instance), any());
        verify(confluenceClient, never()).crawlAllDescendantPages(eq(impostor), any());
    }

    @Test
    void crawlEnqueuedBeforeInstanceIdsWereSnapshottedStillResolvesBySlug() {
        // Back-compat: a crawl queued across the upgrade carries no instanceId, and slug resolution
        // is the only thing it has. It must keep working rather than failing the job.
        crawlReturns(page("page-1", "Page 1", 4));

        service.ingest(crawlJobContext(CRAWL_KIND, new HashMap<>()));

        verify(confluenceClient).crawlAllDescendantPages(eq(instance), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testIngestExtractsVersionAndEnqueues() {
        crawlReturns(page("page-1", "Page 1", 4));

        JobCounts counts = service.ingest(crawlJobContext());
        assertThat(counts.docs()).isEqualTo(1);

        ArgumentCaptor<EnqueueRequest> requestCaptor =
            ArgumentCaptor.forClass(EnqueueRequest.class);
        verify(jobService).enqueue(requestCaptor.capture());

        EnqueueRequest request = requestCaptor.getValue();
        assertThat(request.kind()).isEqualTo(PAGE_KIND);
        assertThat(request.settings().get("pageId")).isEqualTo("page-1");
        assertThat(request.settings().get("pageVersion")).isEqualTo(4);
    }

    /**
     * Pages are selected for the knowledge base by a hand-applied Confluence label, so a page
     * nobody labels is invisible to it forever with no signal anywhere. The crawl now counts them
     * and says so on its own job, where curation drift is visible instead of being discovered later
     * as a bad answer.
     */
    @Test
    void aCrawlReportsHowManyPagesUnderTheRootCarryNoneOfTheIncludeLabels() {
        configureInstance("v1", "kb-public");
        crawlReturns(page("page-1", "Page 1", 4));
        when(confluenceClient.countPagesIgnoringIncludeLabels(instance)).thenReturn(9);

        JobContext ctx = crawlJobContext();
        service.ingest(ctx);

        assertThat(reportedMessages(ctx))
            .anyMatch(message -> message.contains("8 page(s)") && message.contains("kb-public"));
    }

    /** The count is one extra request per crawl, never one per page. */
    @Test
    void theUnlabelledPageCountCostsExactlyOneExtraCall() {
        configureInstance("v1", "kb-public");
        crawlReturns(page("page-1", "Page 1", 4), page("page-2", "Page 2", 4));
        when(confluenceClient.countPagesIgnoringIncludeLabels(instance)).thenReturn(2);

        service.ingest(crawlJobContext());

        verify(confluenceClient, times(1)).countPagesIgnoringIncludeLabels(instance);
    }

    /**
     * Without an include label every page under the root is ingested, so there is nothing to count.
     */
    @Test
    void withoutAnIncludeLabelNoExtraCountIsRequested() {
        crawlReturns(page("page-1", "Page 1", 4));

        JobContext ctx = crawlJobContext();
        service.ingest(ctx);

        verify(confluenceClient, never()).countPagesIgnoringIncludeLabels(any());
        assertThat(reportedMessages(ctx)).noneMatch(message -> message.contains("include labels"));
    }

    /**
     * The count is a diagnostic, not content: the corpus is already ingested by the time it runs,
     * so a failing count degrades to "unknown" instead of failing a completed crawl.
     */
    @Test
    void aFailingUnlabelledCountDoesNotFailTheCrawl() {
        configureInstance("v1", "kb-public");
        crawlReturns(page("page-1", "Page 1", 4));
        when(confluenceClient.countPagesIgnoringIncludeLabels(instance))
            .thenThrow(new IllegalStateException("Confluence rate-limited the count"));

        JobCounts counts = service.ingest(crawlJobContext());

        assertThat(counts.docs()).isEqualTo(1);
    }

    /**
     * The emptiest crawl is exactly where the count matters most: "no pages found" plus "but 12 of
     * them carry none of your labels" is a diagnosis, while "no pages found" alone is a mystery.
     */
    @Test
    void anEmptyCrawlNamesTheUnlabelledPagesInItsFailure() {
        configureInstance("v1", "kb-public");
        crawlReturns();
        when(confluenceClient.countPagesIgnoringIncludeLabels(instance)).thenReturn(12);

        assertThatThrownBy(() -> service.ingest(crawlJobContext()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("No pages found")
            .hasMessageContaining("12 page(s)");
    }

    /**
     * The version-skip has exactly one positive clause — the stored row is {@code completed} AND
     * its recorded version parses to the crawled one — and its three negative clauses have no
     * witness anywhere: every other case stubs a {@code completed} row, so a non-{@code completed}
     * status, an unparseable stored version and a crawl result carrying no version at all all fall
     * through the unstubbed {@code Optional.empty()} branch and prove nothing about the predicate.
     *
     * <p>
     * Each one is a silent corpus failure if it ever starts skipping. A row that is not
     * {@code completed} is the record of an ingest that ABORTED — suppressing it makes the
     * half-written page permanently unrepairable, because no later crawl will ever offer it again.
     * An unparseable or absent version means "we do not know which revision is stored", and
     * skipping on unknown freezes the page at whatever revision it happened to hold while
     * Confluence moves on. Neither surfaces: the crawl logs them as ordinary skips and the job goes
     * green. The last page is the control — genuinely up to date, and still skipped — so the test
     * cannot pass by simply never skipping.
     */
    @Test
    void aPageIsReIngestedUnlessItsStoredRowIsCompletedWithAParsableMatchingVersion() {
        String pageUrl = "https://example.com/wiki/spaces/SPACE/pages/";
        assertThat(SOURCE_FILE).isEqualTo(pageUrl + "page-1");

        crawlReturns(page("page-1", "Aborted mid-ingest", 4), page("page-2", "Failed ingest", 4),
            page("page-3", "Garbled stored version", 4),
            Map.<String, Object>of("id", "page-4", "title", "Crawled without a version"),
            page("page-5", "Genuinely unchanged", 4));

        // 'pending'/'failed' = the previous ingest never finished; the page must be offered again.
        when(documentRepository.getMetadataAndStatus(eq("coll"), eq("v1"), eq(pageUrl + "page-1"),
            eq("confluence")))
            .thenReturn(Optional.of(new DocumentMetadataAndStatus("pending", "4")));
        when(documentRepository.getMetadataAndStatus(eq("coll"), eq("v1"), eq(pageUrl + "page-2"),
            eq("confluence")))
            .thenReturn(Optional.of(new DocumentMetadataAndStatus("failed", "4")));
        // Completed, but the stored version is not a number, so nothing is known about it.
        when(documentRepository.getMetadataAndStatus(eq("coll"), eq("v1"), eq(pageUrl + "page-3"),
            eq("confluence")))
            .thenReturn(Optional.of(new DocumentMetadataAndStatus("completed", "4.1-SNAPSHOT")));
        // Completed at 4 — but the crawl result reports no version, so equality is unknowable.
        when(documentRepository.getMetadataAndStatus(eq("coll"), eq("v1"), eq(pageUrl + "page-4"),
            eq("confluence")))
            .thenReturn(Optional.of(new DocumentMetadataAndStatus("completed", "4")));
        when(documentRepository.getMetadataAndStatus(eq("coll"), eq("v1"), eq(pageUrl + "page-5"),
            eq("confluence")))
            .thenReturn(Optional.of(new DocumentMetadataAndStatus("completed", "4")));

        JobCounts counts = service.ingest(crawlJobContext());

        ArgumentCaptor<EnqueueRequest> enqueued = ArgumentCaptor.forClass(EnqueueRequest.class);
        verify(jobService, times(4)).enqueue(enqueued.capture());
        assertThat(enqueued.getAllValues().stream().map(r -> r.settings().get("pageId")).toList())
            .containsExactly("page-1", "page-2", "page-3", "page-4");
        assertThat(counts.docs()).isEqualTo(4);
    }

    @Test
    void testIngestSkipsEnqueuingIfVersionMatches() {
        crawlReturns(page("page-1", "Page 1", 4));

        when(documentRepository.getMetadataAndStatus(eq("coll"), eq("v1"), eq(SOURCE_FILE),
            eq("confluence")))
            .thenReturn(Optional.of(new DocumentMetadataAndStatus("completed", "4")));

        JobCounts counts = service.ingest(crawlJobContext());
        assertThat(counts.docs()).isEqualTo(0); // 0 queued

        verify(jobService, never()).enqueue(any());
    }

    /**
     * Admission control makes a duplicate enqueue an ordinary outcome, and this loop is the crawl's
     * BATCH CONSUMER: if an already-queued page aborted it, the rest of the page tree would never
     * be crawled and the corpus would be silently partial. The page is counted as a skip and the
     * crawl carries on.
     */
    @Test
    void anUndeclaredTagFailsTheWholeCrawlInsteadOfBeingCountedAsAPerPageSkip() {
        // The crawl loop catches IllegalStateException ONLY — a page already queued is a normal,
        // counted skip and must not abandon the rest of the page tree. An IllegalArgumentException
        // from the enqueue validators (unknown collection, or a tag the collection does not
        // declare) is categorically different: it applies to EVERY page, so swallowing it per page
        // would turn a misconfigured connector into a crawl that "succeeds" having queued nothing
        // — the exact silent-empty outcome the validator exists to prevent. Widening this catch to
        // Exception or RuntimeException reintroduces that.
        crawlReturns(page("page-1", "Page 1", 4), page("page-2", "Page 2", 4));
        when(jobService.enqueue(any())).thenThrow(
            new IllegalArgumentException("Tag '9.9' is not declared on collection 'OIM'"));

        assertThatThrownBy(() -> service.ingest(crawlJobContext()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not declared");

        // It must abort on the FIRST page, not plough through the whole tree.
        verify(jobService, times(1)).enqueue(any());
    }

    @Test
    void testIngestKeepsCrawlingWhenAPageIsAlreadyQueued() {
        crawlReturns(page("page-1", "Page 1", 4), page("page-2", "Page 2", 4));
        when(jobService.enqueue(any())).thenReturn(EnqueueResult.adopted(UUID.randomUUID()),
            EnqueueResult.created(UUID.randomUUID()));

        JobCounts counts = service.ingest(crawlJobContext());

        // Both pages were offered to the queue; only the second one produced a new job.
        verify(jobService, times(2)).enqueue(any());
        assertThat(counts.docs()).isEqualTo(1);
    }

    /**
     * Stopping a CRAWL has to stop the fan-out, not just the crawl's own row. Without a check in
     * the batch consumer the sequence was: the operator stops the crawl (row 'cancelled', SSE
     * closed, UI says cancelled), the crawl keeps paging Confluence and keeps enqueuing pages into
     * the very queue that is being drained, and when it finally finishes its row flips back to
     * 'completed' — freeing the admission slot for a second crawl of the same instance while the
     * first one is still running, which is precisely the "two workers on one unit of work" anomaly
     * V3 refuses to migrate over.
     */
    @Test
    void crawlStopsFanningOutPagesWhenItIsCancelled() {
        crawlReturnsBatches(List.of(page("page-1", "Page 1", 4)),
            List.of(page("page-2", "Page 2", 4), page("page-3", "Page 3", 4)));
        // Running when the first batch arrives, stopped by the time the second one does.
        when(jobRepository.findStatusById(any())).thenReturn(Optional.of(JobStatus.RUNNING),
            Optional.of(JobStatus.CANCELLED));

        assertThatThrownBy(() -> service.ingest(crawlJobContext()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("cancelled");

        // Only the first batch was fanned out; the second batch's pages never reached the queue.
        verify(jobService, times(1)).enqueue(any());
    }

    /**
     * Deleting an instance cancels its QUEUED jobs and deliberately leaves RUNNING ones alone — but
     * the job still running is usually the crawl, and the crawl is the PRODUCER. It holds the
     * instance it resolved at start-up in memory, so it kept refilling the queue the delete had
     * just emptied, and every one of those pages then failed at execution with "Confluence instance
     * … does not exist": exactly the wall of red the delete path exists to prevent, while the
     * confirm dialog promised otherwise. One existence check per batch (not per page) stops the
     * producer.
     */
    @Test
    void crawlStopsFanningOutPagesWhenItsInstanceIsDeletedMidCrawl() {
        crawlReturnsBatches(List.of(page("page-1", "Page 1", 4)),
            List.of(page("page-2", "Page 2", 4), page("page-3", "Page 3", 4)));
        // Present when the first batch arrives, deleted by the time the second one does.
        when(instanceRepository.existsById(INSTANCE_ID)).thenReturn(true, false);

        assertThatThrownBy(() -> service.ingest(crawlJobContext()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("iCC Wiki")
            .hasMessageContaining("deleted");

        // Only the first batch was fanned out; the second batch's pages never reached the queue.
        verify(jobService, times(1)).enqueue(any());
    }

    /** One lookup per batch, never one per page — a full-space crawl pages 50 at a time. */
    @Test
    void theInstanceExistenceCheckCostsOneLookupPerBatch() {
        crawlReturnsBatches(List.of(page("page-1", "Page 1", 4), page("page-2", "Page 2", 4)),
            List.of(page("page-3", "Page 3", 4)));

        service.ingest(crawlJobContext());

        verify(instanceRepository, times(2)).existsById(INSTANCE_ID);
    }

    /**
     * The crawl must survive the one case {@code JobRepository.enqueue} still throws: both attempts
     * saw an active job appear and finish. Letting it out of the batch consumer would abandon the
     * rest of the page tree — the exact failure the adopt-or-create work exists to prevent.
     */
    @Test
    void crawlCountsAnUnenqueueablePageAsASkipAndKeepsGoing() {
        crawlReturns(page("page-1", "Page 1", 4), page("page-2", "Page 2", 4));
        when(jobService.enqueue(any()))
            .thenThrow(new IllegalStateException(
                "Could not enqueue job (kind=confluence-page-import): an active job for the same"
                    + " work kept appearing and finishing; retry."))
            .thenReturn(EnqueueResult.created(UUID.randomUUID()));

        JobCounts counts = service.ingest(crawlJobContext());

        verify(jobService, times(2)).enqueue(any());
        assertThat(counts.docs()).isEqualTo(1);
    }

    /**
     * Two halves of the page-job snapshot that no test has ever looked at, and both fail silently.
     *
     * <p>
     * {@code sourceRef} is not a label: it is one column of
     * {@code ux_ingestion_jobs_active_work (kind, source_ref, collection_id, tags)}, the partial
     * unique index that makes {@code JobService.enqueue} ADOPT an in-flight job instead of creating
     * a second one. A re-triggered crawl draining alongside the first is an ordinary event here, so
     * if the shape of this key changes — dropping the space, say, or switching to the page title —
     * the two crawls stop colliding and every page is imported TWICE concurrently, each run
     * deleting and re-inserting the other's chunks under one document id. Nothing errors; the two
     * jobs both report success.
     * {@code crawlSnapshotsTheResolvedTargetIntoEveryPageJobAndNeverTheToken} captures this very
     * request and asserts every settings key, but never {@code sourceRef()}.
     *
     * <p>
     * The second half is {@code required()}. Every {@code pageJobContext} fixture in this class
     * supplies domain/space/collection, so its throw has never executed once. It has to throw,
     * because the branch next door — a job with NO {@code instanceId} at all — deliberately falls
     * back to the LIVE instance row: a null-tolerant {@code required()} would send a partially
     * snapshotted job down that path and write a queued crawl's pages into whatever collection the
     * connector happens to point at now. That is exactly the cross-collection contamination the
     * snapshot exists to prevent, and it is invisible — the job completes green, in the wrong
     * corpus.
     */
    @Test
    void aPageJobIsKeyedOnSpaceAndPageAndRefusesToRunOnAPartialSnapshot() {
        crawlReturns(page("page-1", "Page 1", 4));

        service.ingest(crawlJobContext());

        ArgumentCaptor<EnqueueRequest> captor = ArgumentCaptor.forClass(EnqueueRequest.class);
        verify(jobService).enqueue(captor.capture());
        assertThat(captor.getValue().sourceRef())
            .as("the admission key of ux_ingestion_jobs_active_work: one job per space + page")
            .isEqualTo("confluence/SPACE/page-1");

        // A snapshot that lost any one of its three target keys must FAIL, never quietly fall
        // through to the live instance's current collection.
        for (String key : List.of("domain", "space", "collection")) {
            Map<String, Object> partial = pageSettings(4);
            partial.remove(key);
            JobContext ctx = pageJobContext(partial);

            assertThatThrownBy(() -> service.ingestPage(ctx, "page-1", "Page 1"))
                .as("a page job missing its '%s' snapshot must fail loudly", key)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(key)
                .hasMessageContaining("re-run the sync");
        }

        // ...and nothing was fetched or written on any of those three attempts.
        verify(confluenceClient, never()).getPageBodyStorage(any(), anyString());
        verify(documentRepository, never()).upsertDocumentBySourceFile(any(),
            nullable(String.class), any(), any(), any());
    }

    /**
     * Wave 3b: a running page job must notice a stop between its (remote, slow) fetch and its
     * write, instead of finishing the page. Without this an operator who bulk-cancels a repointed
     * connector still waits out every in-flight page.
     */
    @Test
    void ingestPageAbortsBeforeWritingWhenTheJobIsCancelled() {
        JobContext ctx = pageJobContext(5);
        when(confluenceClient.getPageBodyStorage(instance, "page-1"))
            .thenReturn("<h1>Page 1 Content</h1>");
        when(confluenceConverter.convertToMarkdown(eq(instance), anyBoolean(), eq("page-1"),
            anyString())).thenReturn("# Page 1 Content\n## Section 1\nSome body text.");
        // Running when the job starts, cancelled by the time the body has been fetched.
        when(jobRepository.findStatusById(any())).thenReturn(Optional.of(JobStatus.RUNNING),
            Optional.of(JobStatus.CANCELLED));

        assertThatThrownBy(() -> service.ingestPage(ctx, "page-1", "Page 1"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("cancelled");

        verify(confluenceClient).getPageBodyStorage(instance, "page-1");
        verify(documentRepository, never()).upsertDocumentBySourceFile(any(),
            nullable(String.class), any(), any(), any());
        verify(documentRepository, never()).insertChunks(any(), any(), nullable(String.class),
            any(), any(), any());
    }

    /** A job row that no longer exists cannot be written to either — treated as cancelled. */
    @Test
    void ingestPageAbortsWhenItsJobRowIsGone() {
        JobContext ctx = pageJobContext(5);
        when(jobRepository.findStatusById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ingestPage(ctx, "page-1", "Page 1"))
            .isInstanceOf(IllegalStateException.class);

        verify(confluenceClient, never()).getPageBodyStorage(any(), anyString());
    }

    @Test
    void testIngestPageSkipsIfVersionMatchesAndCompleted() {
        JobContext ctx = pageJobContext(4);

        when(documentRepository.getMetadataAndStatus(eq("coll"), eq("v1"), eq(SOURCE_FILE),
            eq("confluence")))
            .thenReturn(Optional.of(new DocumentMetadataAndStatus("completed", "4")));

        JobCounts counts = service.ingestPage(ctx, "page-1", "Page 1");
        assertThat(counts.docs()).isEqualTo(1);
        assertThat(counts.chunks()).isEqualTo(0);

        verify(confluenceClient, never()).getPageBodyStorage(any(), anyString());
        verify(embeddingService, never()).embedBatch(any());
    }

    @Test
    void testIngestPagePerformsImportIfVersionDiffers() {
        JobContext ctx = pageJobContext(5);

        when(documentRepository.getMetadataAndStatus(eq("coll"), eq("v1"), eq(SOURCE_FILE),
            eq("confluence")))
            .thenReturn(Optional.of(new DocumentMetadataAndStatus("completed", "4")));

        when(confluenceClient.getPageBodyStorage(instance, "page-1"))
            .thenReturn("<h1>Page 1 Content</h1>");
        when(confluenceConverter.convertToMarkdown(eq(instance), anyBoolean(), eq("page-1"),
            anyString())).thenReturn("# Page 1 Content\n## Section 1\nSome body text.");

        UUID docId = UUID.randomUUID();
        when(documentRepository.upsertDocumentBySourceFile(any(), nullable(String.class), any(),
            any(), any())).thenReturn(docId);

        JobCounts counts = service.ingestPage(ctx, "page-1", "Page 1");
        assertThat(counts.docs()).isEqualTo(1);
        assertThat(counts.chunks()).isGreaterThan(0);

        verify(documentRepository).updateMetadataKey(eq(docId), eq("confluence_page_version"),
            eq(5));
        verify(sectionSummarizer).generateSummaries(eq(docId), any(), any(), eq(ctx), any());
    }

    // ---------------------------------------------------------------------
    // Wave 3a: a page job must be SELF-CONTAINED. ingestPage used to re-read the singleton
    // connector configuration for collection/tag/space at execution time, so a queue that drains
    // over hours landed its pages wherever the configuration happened to point at that moment —
    // and with two instances connected, one crawl's pages could land in the other's collection.
    // ---------------------------------------------------------------------

    @Test
    void ingestPageUsesTheJobSnapshotEvenAfterTheInstanceIsRetargeted() {
        JobContext ctx = pageJobContext(7);

        // The connector is edited while the job waits in the queue: different collection, tag,
        // space and attachment policy. The SITE stays the same — repointing the connector at
        // another site is refused outright (see the cross-site tests below), because the body
        // would then be fetched from a site the snapshot knows nothing about.
        ConfluenceInstance retargeted = new ConfluenceInstance(INSTANCE_ID, "iCC Wiki", SLUG,
            "https://example.com/wiki", "svc@example.com", "enc:token", "keyA", "OTHER", "999", "",
            "", "other-coll", "v9", true, true, null, null);
        when(instanceRepository.findById(INSTANCE_ID)).thenReturn(Optional.of(retargeted));

        when(confluenceClient.getPageBodyStorage(retargeted, "page-1")).thenReturn("<p>x</p>");
        when(confluenceConverter.convertToMarkdown(eq(retargeted), eq(false), eq("page-1"),
            anyString())).thenReturn("Body prose.");
        when(documentRepository.upsertDocumentBySourceFile(any(), nullable(String.class), any(),
            any(), any())).thenReturn(UUID.randomUUID());

        service.ingestPage(ctx, "page-1", "Page 1");

        // Target: the snapshot, not the edited row.
        verify(documentRepository).upsertDocumentBySourceFile(eq("coll"), eq("v1"), eq(SOURCE_FILE),
            eq("confluence"), eq("Page 1"));
        // Credentials: resolved by the id recorded at crawl time — the ONLY thing read live.
        verify(instanceRepository).findById(INSTANCE_ID);
        verify(instanceRepository, never()).findBySlug(anyString());
        verify(instanceRepository, never()).findAll();
    }

    @Test
    @SuppressWarnings("unchecked")
    void crawlSnapshotsTheResolvedTargetIntoEveryPageJobAndNeverTheToken() {
        crawlReturns(page("page-1", "Page 1", 4));

        service.ingest(crawlJobContext());

        ArgumentCaptor<EnqueueRequest> captor = ArgumentCaptor.forClass(EnqueueRequest.class);
        verify(jobService).enqueue(captor.capture());
        Map<String, Object> settings = captor.getValue().settings();

        assertThat(settings).containsEntry("instanceId", INSTANCE_ID.toString())
            .containsEntry("instanceName", "iCC Wiki")
            .containsEntry("domain", "https://example.com/wiki").containsEntry("space", "SPACE")
            .containsEntry("collection", "coll").containsEntry("tag", "v1")
            .containsEntry("processAttachments", false);
        // ingestion_jobs.settings is JSONB in the database and is rendered on the job detail page:
        // no credential may ever be written into it.
        assertThat(settings.keySet()).noneMatch(key -> key.toLowerCase().contains("token")
            || key.toLowerCase().contains("secret") || key.toLowerCase().contains("password"));
        assertThat(settings.values())
            .noneMatch(value -> value != null && value.toString().contains(instance.apiTokenEnc()));
    }

    @Test
    void pageJobKindCarriesTheInstanceSlugSoTheJobsListShowsWhereItCameFrom() {
        crawlReturns(page("page-1", "Page 1", 4));

        service.ingest(crawlJobContext());

        ArgumentCaptor<EnqueueRequest> captor = ArgumentCaptor.forClass(EnqueueRequest.class);
        verify(jobService).enqueue(captor.capture());
        assertThat(captor.getValue().kind()).isEqualTo("confluence-page-import:icc-wiki");
        // The engine routes on the base kind (JobServiceTest pins the split).
        assertThat(captor.getValue().kind().split(":")[0])
            .isEqualTo(IngestJobKind.CONFLUENCE_PAGE_IMPORT.getValue());
    }

    @Test
    void crawlResolvesItsInstanceByTheSnapshottedIdAndNeverByTheSlug() {
        // Replaces an earlier test that asserted findBySlug: the slug in the kind is a label the
        // operator can edit, so it is no longer the identity. The slug path still exists, but only
        // as the fallback for jobs queued before ids were snapshotted (pinned separately).
        crawlReturns(page("page-1", "Page 1", 4));

        service.ingest(crawlJobContext());

        verify(instanceRepository).findById(INSTANCE_ID);
        verify(instanceRepository, never()).findBySlug(any());
    }

    @Test
    void crawlOfAJobWithoutAnInstanceSuffixFallsBackToTheDefaultInstance() {
        JobContext ctx = mock(JobContext.class);
        IngestionJob job = mock(IngestionJob.class);
        when(ctx.job()).thenReturn(job);
        when(job.kind()).thenReturn(IngestJobKind.CONFLUENCE_IMPORT.getValue());
        when(instanceRepository.findBySlug("default")).thenReturn(Optional.of(instance));
        crawlReturns(page("page-1", "Page 1", 4));

        service.ingest(ctx);

        verify(instanceRepository).findBySlug("default");
    }

    @Test
    void crawlOfAnUnknownInstanceFailsWithAMessageNamingIt() {
        JobContext ctx = mock(JobContext.class);
        IngestionJob job = mock(IngestionJob.class);
        when(ctx.job()).thenReturn(job);
        when(job.kind()).thenReturn("confluence-import:gone");
        when(instanceRepository.findBySlug("gone")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ingest(ctx)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("gone");
    }

    @Test
    void crawlOfADisabledInstanceFails() {
        ConfluenceInstance disabled = new ConfluenceInstance(INSTANCE_ID, "iCC Wiki", SLUG,
            "https://example.com/wiki", "svc@example.com", "enc:token", "keyA", "SPACE", "123", "",
            "", "coll", "v1", false, false, null, null);
        when(instanceRepository.findBySlug(SLUG)).thenReturn(Optional.of(disabled));
        // The crawl resolves by the snapshotted id, so that is the lookup that must yield the
        // disabled row for this test to exercise the check at all.
        when(instanceRepository.findById(INSTANCE_ID)).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> service.ingest(crawlJobContext()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("iCC Wiki")
            .hasMessageContaining("disabled");
        verify(confluenceClient, never()).crawlAllDescendantPages(any(), any());
    }

    @Test
    void pageJobWhoseInstanceWasDeletedFailsWithAnActionableMessage() {
        JobContext ctx = pageJobContext(4);
        when(instanceRepository.findById(INSTANCE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ingestPage(ctx, "page-1", "Page 1"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("iCC Wiki")
            .hasMessageContaining("re-run the sync");
    }

    // ---------------------------------------------------------------------
    // The snapshot decided WHERE to write but the live row still decided WHAT to read: the body was
    // fetched with `target.instance()`, i.e. the domain and token the connector points at NOW. With
    // one form and two wikis, "connect the other wiki" is "edit the default instance": every queued
    // job then fetched `GET https://siteB/rest/api/content/<idFromSiteA>` — and Confluence Cloud
    // content ids are small per-site integers, so a collision stores site B's body under site A's
    // source_file, in site A's collection, with site A's title, GREEN.
    // ---------------------------------------------------------------------

    @Test
    void pageJobWhoseInstanceWasRepointedAtAnotherSiteFailsAndWritesNothing() {
        JobContext ctx = pageJobContext(7);

        ConfluenceInstance otherSite = new ConfluenceInstance(INSTANCE_ID, "iCC Wiki", SLUG,
            "https://other-wiki.example.com/wiki", "svc@example.com", "enc:token", "keyA", "SPACE",
            "123", "", "", "coll", "v1", false, true, null, null);
        when(instanceRepository.findById(INSTANCE_ID)).thenReturn(Optional.of(otherSite));

        assertThatThrownBy(() -> service.ingestPage(ctx, "page-1", "Page 1"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("iCC Wiki")
            .hasMessageContaining("https://example.com/wiki")
            .hasMessageContaining("https://other-wiki.example.com/wiki")
            .hasMessageContaining("re-run the sync");

        verify(confluenceClient, never()).getPageBodyStorage(any(), anyString());
        verify(confluenceConverter, never()).convertToMarkdown(any(), anyBoolean(), anyString(),
            anyString());
        verify(documentRepository, never()).upsertDocumentBySourceFile(any(),
            nullable(String.class), any(), any(), any());
    }

    /** The comparison is over canonical domains, so casing and a trailing slash are not a move. */
    @Test
    void aDomainDifferingOnlyInCasingOrTrailingSlashIsStillTheSameSite() {
        Map<String, Object> settings = pageSettings(4);
        settings.put("domain", "HTTPS://Example.COM/wiki/");
        JobContext ctx = pageJobContext(settings);
        when(documentRepository.getMetadataAndStatus(eq("coll"), eq("v1"), eq(SOURCE_FILE),
            eq("confluence")))
            .thenReturn(Optional.of(new DocumentMetadataAndStatus("completed", "4")));

        assertThat(service.ingestPage(ctx, "page-1", "Page 1").docs()).isEqualTo(1);
    }

    // ---------------------------------------------------------------------
    // `enabled` is the only stop lever an operator has (a rotated-out service token, a sync aimed
    // at the wrong space) and it stopped nothing: the crawl refused to START, but the hundreds of
    // page jobs already queued kept authenticating with the stored credential and ran to
    // completion. Nothing writes JobStatus.cancelled, so a restart was the only other remedy.
    // ---------------------------------------------------------------------

    @Test
    void pageJobOfADisabledInstanceStopsInsteadOfDrainingTheQueue() {
        JobContext ctx = pageJobContext(4);
        ConfluenceInstance disabled = new ConfluenceInstance(INSTANCE_ID, "iCC Wiki", SLUG,
            "https://example.com/wiki", "svc@example.com", "enc:token", "keyA", "SPACE", "123", "",
            "", "coll", "v1", false, false, null, null);
        when(instanceRepository.findById(INSTANCE_ID)).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> service.ingestPage(ctx, "page-1", "Page 1"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("iCC Wiki")
            .hasMessageContaining("disabled").hasMessageContaining("re-enable");

        verify(confluenceClient, never()).getPageBodyStorage(any(), anyString());
        verify(documentRepository, never()).upsertDocumentBySourceFile(any(),
            nullable(String.class), any(), any(), any());
    }

    /**
     * A job enqueued before the snapshot existed (already queued across the upgrade) still has to
     * run: it falls back to the instance named by its kind.
     */
    @Test
    void legacyPageJobWithoutASnapshotFallsBackToTheInstance() {
        JobContext ctx = pageJobContext(
            new HashMap<>(Map.of("pageId", "page-1", "title", "Page 1", "pageVersion", 4)));
        when(ctx.job().collection()).thenReturn("coll");
        when(documentRepository.getMetadataAndStatus(eq("coll"), eq("v1"), eq(SOURCE_FILE),
            eq("confluence")))
            .thenReturn(Optional.of(new DocumentMetadataAndStatus("completed", "4")));

        JobCounts counts = service.ingestPage(ctx, "page-1", "Page 1");

        assertThat(counts.docs()).isEqualTo(1);
        verify(instanceRepository).findBySlug(SLUG);
    }

    /**
     * A legacy job carries its own collection (JobRowMapper reads it; the enqueue validator
     * normalized it). Taking the collection from the LIVE instance meant that switching the
     * connector's target while a pre-upgrade queue drained silently mixed two corpora: the
     * remaining jobs wrote into the NEW collection under the OLD site's source_file. Refusing —
     * rather than re-targeting — also leaves tag semantics alone for untagged collections, where
     * the job's tag is legitimately empty while the connector's is not.
     */
    @Test
    void legacyPageJobWhoseCollectionNoLongerMatchesTheInstanceIsRefused() {
        JobContext ctx = pageJobContext(
            new HashMap<>(Map.of("pageId", "page-1", "title", "Page 1", "pageVersion", 4)));
        when(ctx.job().collection()).thenReturn("old-coll");

        assertThatThrownBy(() -> service.ingestPage(ctx, "page-1", "Page 1"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("old-coll")
            .hasMessageContaining("coll").hasMessageContaining("re-run the sync");

        verify(confluenceClient, never()).getPageBodyStorage(any(), anyString());
        verify(documentRepository, never()).upsertDocumentBySourceFile(any(),
            nullable(String.class), any(), any(), any());
    }

    @Test
    void pageJobPassesTheSnapshottedAttachmentPolicyToTheConverter() {
        Map<String, Object> settings = pageSettings(4);
        settings.put("processAttachments", true);
        JobContext ctx = pageJobContext(settings);

        when(confluenceClient.getPageBodyStorage(instance, "page-1")).thenReturn("<p>x</p>");
        when(confluenceConverter.convertToMarkdown(eq(instance), eq(true), eq("page-1"),
            anyString())).thenReturn("Body prose.");
        when(documentRepository.upsertDocumentBySourceFile(any(), nullable(String.class), any(),
            any(), any())).thenReturn(UUID.randomUUID());

        service.ingestPage(ctx, "page-1", "Page 1");

        verify(confluenceConverter).convertToMarkdown(eq(instance), eq(true), eq("page-1"),
            anyString());
    }

    // ---------------------------------------------------------------------
    // Item 1: pages that produced no sections used to complete green with 0 chunks.
    // ---------------------------------------------------------------------

    @Test
    void pageWithoutAnyHeadingStillProducesChunks() {
        JobContext ctx = pageJobContext((Integer) null);
        when(confluenceClient.getPageBodyStorage(instance, "page-1")).thenReturn("<p>prose</p>");
        when(confluenceConverter.convertToMarkdown(eq(instance), anyBoolean(), eq("page-1"),
            anyString())).thenReturn("Plain prose with no heading whatsoever.");
        when(documentRepository.upsertDocumentBySourceFile(any(), nullable(String.class), any(),
            any(), any())).thenReturn(UUID.randomUUID());

        JobCounts counts = service.ingestPage(ctx, "page-1", "Prose Page");

        assertThat(counts.docs()).isEqualTo(1);
        assertThat(counts.chunks()).isEqualTo(1);
    }

    @Test
    void pageWhoseOnlyHeadingIsALeadingH1StillProducesChunks() {
        JobContext ctx = pageJobContext((Integer) null);
        when(confluenceClient.getPageBodyStorage(instance, "page-1"))
            .thenReturn("<h1>Overview</h1>");
        when(confluenceConverter.convertToMarkdown(eq(instance), anyBoolean(), eq("page-1"),
            anyString())).thenReturn("# Overview\n\nThe only body text on the page.");
        when(documentRepository.upsertDocumentBySourceFile(any(), nullable(String.class), any(),
            any(), any())).thenReturn(UUID.randomUUID());

        JobCounts counts = service.ingestPage(ctx, "page-1", "Overview Page");

        assertThat(counts.chunks()).isGreaterThan(0);
    }

    // ---------------------------------------------------------------------
    // A page that genuinely has no content is a SKIP, not a failure: throwing before any
    // `documents` row existed meant the next crawl re-enqueued it (isPageUpToDate only skips a
    // page whose row is 'completed'), so ~30 hierarchy-only parent pages failed forever and kept
    // the job list permanently red. A fetch/transport failure keeps failing loudly.
    // ---------------------------------------------------------------------

    @Test
    void pageWithNoIngestibleContentIsRecordedAsACompletedZeroChunkDocument() {
        JobContext ctx = pageJobContext(3);
        when(confluenceClient.getPageBodyStorage(instance, "page-1")).thenReturn("<p>&nbsp;</p>");
        when(confluenceConverter.convertToMarkdown(eq(instance), anyBoolean(), eq("page-1"),
            anyString())).thenReturn("   \n\n");
        UUID docId = UUID.randomUUID();
        when(documentRepository.upsertDocumentBySourceFile(any(), nullable(String.class), any(),
            any(), any())).thenReturn(docId);

        JobCounts counts = service.ingestPage(ctx, "page-1", "Empty Page");

        assertThat(counts.docs()).isEqualTo(1);
        assertThat(counts.chunks()).isZero();
        verify(documentRepository).updateMetadataKey(eq(docId), eq("confluence_page_version"),
            eq(3));
        verify(documentRepository).updateIngestionStatus(eq(docId), eq("completed"));
        // Nothing to embed, nothing to summarize — and no stale chunks left behind.
        verify(embeddingService, never()).embedBatch(any());
        verify(sectionSummarizer, never()).generateSummaries(any(), any(), any(), any(), any());
        verify(documentRepository).deleteContentForDocument(eq(docId));
        verify(documentRepository, never()).insertChunks(any(UUID.class), anyString(),
            nullable(String.class), anyString(), anyString(), any());
    }

    /**
     * The regression that proves the permanent re-failure loop is gone: after the empty page has
     * been ingested once, the next crawl's version-skip suppresses it instead of re-enqueuing it.
     */
    @Test
    void emptyPageIsSkippedByTheNextCrawlInsteadOfBeingReEnqueuedForever() {
        JobContext ctx = pageJobContext(3);
        when(confluenceClient.getPageBodyStorage(instance, "page-1")).thenReturn("");
        when(documentRepository.upsertDocumentBySourceFile(any(), nullable(String.class), any(),
            any(), any())).thenReturn(UUID.randomUUID());

        assertThat(service.ingestPage(ctx, "page-1", "Parent Page").docs()).isEqualTo(1);

        // Second crawl: the row written above is 'completed' at version 3.
        when(documentRepository.getMetadataAndStatus(eq("coll"), eq("v1"), eq(SOURCE_FILE),
            eq("confluence")))
            .thenReturn(Optional.of(new DocumentMetadataAndStatus("completed", "3")));
        crawlReturns(page("page-1", "Parent Page", 3));

        JobCounts crawl = service.ingest(crawlJobContext());

        assertThat(crawl.docs()).isZero();
        verify(jobService, never()).enqueue(any());
    }

    @Test
    void aFetchFailureStillFailsTheJobLoudly() {
        JobContext ctx = pageJobContext(3);
        when(confluenceClient.getPageBodyStorage(instance, "page-1"))
            .thenThrow(new IllegalStateException("Confluence rate-limited the body fetch for"
                + " page page-1 (HTTP 429) after 3 attempts"));

        assertThatThrownBy(() -> service.ingestPage(ctx, "page-1", "Throttled Page"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("page-1");

        // No placeholder row: a transport failure must NOT be recorded as an empty page, or the
        // version-skip would suppress the page on every subsequent crawl.
        verify(documentRepository, never()).upsertDocumentBySourceFile(any(),
            nullable(String.class), any(), any(), any());
    }

    // ---------------------------------------------------------------------
    // Item 2: the embedded text and the stored text must be the same string, and must
    // carry the page title.
    // ---------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void embeddedTextEqualsStoredTextAndCarriesThePageTitle() {
        JobContext ctx = pageJobContext((Integer) null);
        when(confluenceClient.getPageBodyStorage(instance, "page-1")).thenReturn("<h2>Setup</h2>");
        when(confluenceConverter.convertToMarkdown(eq(instance), anyBoolean(), eq("page-1"),
            anyString())).thenReturn("Intro line.\n\n## Setup\n\nInstall the agent.");
        when(documentRepository.upsertDocumentBySourceFile(any(), nullable(String.class), any(),
            any(), any())).thenReturn(UUID.randomUUID());

        service.ingestPage(ctx, "page-1", "Kerberos SSO");

        ArgumentCaptor<List<String>> embedCaptor = ArgumentCaptor.forClass(List.class);
        verify(embeddingService).embedBatch(embedCaptor.capture());
        ArgumentCaptor<List<ChunkInsert>> chunkCaptor = ArgumentCaptor.forClass(List.class);
        verify(documentRepository).insertChunks(any(UUID.class), anyString(),
            nullable(String.class), anyString(), anyString(), chunkCaptor.capture());

        List<String> embedded = embedCaptor.getValue();
        List<String> stored = chunkCaptor.getValue().stream().map(ChunkInsert::text).toList();

        assertThat(stored).isEqualTo(embedded);
        assertThat(embedded).isNotEmpty();
        assertThat(embedded).allSatisfy(text -> assertThat(text).contains("Kerberos SSO"));
    }

    // ---------------------------------------------------------------------
    // Item 3: oversized sections must be split (Tika can push ~100k chars into one section).
    // ---------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void oversizedSectionIsSplitIntoBoundedChunks() {
        JobContext ctx = pageJobContext((Integer) null);
        String paragraph = "z".repeat(2_000);
        when(confluenceClient.getPageBodyStorage(instance, "page-1")).thenReturn("<h2>Big</h2>");
        when(confluenceConverter.convertToMarkdown(eq(instance), anyBoolean(), eq("page-1"),
            anyString())).thenReturn("## Big\n\n" + (paragraph + "\n\n").repeat(8));
        when(documentRepository.upsertDocumentBySourceFile(any(), nullable(String.class), any(),
            any(), any())).thenReturn(UUID.randomUUID());

        JobCounts counts = service.ingestPage(ctx, "page-1", "Big Page");

        assertThat(counts.chunks()).isGreaterThan(1);
        ArgumentCaptor<List<ChunkInsert>> chunkCaptor = ArgumentCaptor.forClass(List.class);
        verify(documentRepository).insertChunks(any(UUID.class), anyString(),
            nullable(String.class), anyString(), anyString(), chunkCaptor.capture());
        for (ChunkInsert chunk : chunkCaptor.getValue()) {
            // heading + breadcrumb overhead is bounded; the body itself must respect the cap.
            assertThat(chunk.text().length()).isLessThan(MarkdownTree.CHUNK_BODY_MAX_CHARS + 500);
        }
    }

    // ---------------------------------------------------------------------
    // Item 4: Confluence documents key strictly on source_file (two pages may share a title).
    // ---------------------------------------------------------------------

    @Test
    void confluenceDocumentsAreKeyedOnSourceFileOnly() {
        JobContext ctx = pageJobContext((Integer) null);
        when(confluenceClient.getPageBodyStorage(instance, "page-1")).thenReturn("<p>x</p>");
        when(confluenceConverter.convertToMarkdown(eq(instance), anyBoolean(), eq("page-1"),
            anyString())).thenReturn("Body prose.");
        when(documentRepository.upsertDocumentBySourceFile(any(), nullable(String.class), any(),
            any(), any())).thenReturn(UUID.randomUUID());

        service.ingestPage(ctx, "page-1", "Shared Title");

        verify(documentRepository).upsertDocumentBySourceFile(eq("coll"), nullable(String.class),
            eq(SOURCE_FILE), eq("confluence"), eq("Shared Title"));
        verify(documentRepository, never()).upsertManualDocument(anyString(),
            nullable(String.class), anyString(), anyString(), nullable(String.class));
    }

    // ---------------------------------------------------------------------
    // Item 5: a blank connector tag means "no tag", not the tag "".
    // ---------------------------------------------------------------------

    @Test
    void blankTagIsPassedAsNoTagToTheVersionSkipLookup() {
        configureTag("");
        JobContext ctx = pageJobContext(7);
        when(documentRepository.getMetadataAndStatus(eq("coll"), isNull(), eq(SOURCE_FILE),
            eq("confluence")))
            .thenReturn(Optional.of(new DocumentMetadataAndStatus("completed", "7")));

        JobCounts counts = service.ingestPage(ctx, "page-1", "Page 1");

        assertThat(counts.docs()).isEqualTo(1);
        assertThat(counts.chunks()).isEqualTo(0);
        verify(confluenceClient, never()).getPageBodyStorage(any(), anyString());
    }

    @Test
    void blankTagEnqueuesPageImportsWithoutABlankTag() {
        configureTag("");
        crawlReturns(page("page-1", "Page 1", 4));

        service.ingest(crawlJobContext());

        ArgumentCaptor<EnqueueRequest> requestCaptor =
            ArgumentCaptor.forClass(EnqueueRequest.class);
        verify(jobService).enqueue(requestCaptor.capture());
        assertThat(requestCaptor.getValue().tags()).isEmpty();
        assertThat(requestCaptor.getValue().tag()).isNull();
        assertThat(requestCaptor.getValue().settings()).doesNotContainKey("tag");
    }
}
