package de.palsoftware.yvoke.document.core;
import de.palsoftware.yvoke.document.core.model.ChunkInsert;
import de.palsoftware.yvoke.document.core.model.DocumentDetails;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.locations=filesystem:docker/db/migration"
        })
public class DocumentRepositoryWriteIT {

    private static final String COLLECTION = "OIM-DOCREPO-TEST";
    private static final String VERSION = "9.3";
    private static final String SOURCE = "doc_repo_it_manual.md";

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    public void setUp() {
        cleanup();
        // Collections are no longer auto-created by ingest writes; the target must pre-exist.
        jdbcTemplate.update(
                "INSERT INTO collections (id, name) VALUES (?, ?) ON CONFLICT (name) DO NOTHING",
                UUID.randomUUID(), COLLECTION);
    }

    @AfterEach
    public void tearDown() {
        cleanup();
        jdbcTemplate.update("DELETE FROM collections WHERE name = ?", COLLECTION);
    }

    private void cleanup() {
        jdbcTemplate.update("DELETE FROM chunks WHERE collection_id IN (SELECT id FROM collections WHERE name = ?)", COLLECTION);
        jdbcTemplate.update(
                "DELETE FROM documents WHERE collection_id IN (SELECT id FROM collections WHERE name = ?)", COLLECTION);
    }

    private static float[] embedding() {
        return new float[1024];
    }

    private List<ChunkInsert> twoChunks() {
        return List.of(
                new ChunkInsert("chunk a", embedding(), List.of("A"), "A", 1, 0),
                new ChunkInsert("chunk b", embedding(), List.of("A", "B"), "B", 2, 1));
    }

    @Test
    public void upsertIsIdempotentByKey() {
        UUID first = documentRepository.upsertManualDocument(COLLECTION, VERSION, SOURCE, "manual", "Title");
        UUID second = documentRepository.upsertManualDocument(COLLECTION, VERSION, SOURCE, "manual", "Title");

        assertThat(second).isEqualTo(first);
        Integer docCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM documents d " + "JOIN collections c ON d.collection_id = c.id "
                        + "WHERE c.name = ? AND d.metadata->>'source_file' = ?",
                Integer.class,
                COLLECTION,
                SOURCE);
        assertThat(docCount).isEqualTo(1);
    }

    @Test
    public void reingestReplacesChunksWithoutDuplicates() {
        UUID docId = documentRepository.upsertManualDocument(COLLECTION, VERSION, SOURCE, "manual", "Title");

        // First ingest
        documentRepository.deleteChunksForDocument(docId);
        documentRepository.insertChunks(docId, COLLECTION, VERSION, SOURCE, "manual", twoChunks());
        assertThat(chunkCount(docId)).isEqualTo(2);

        // Re-ingest: same document id, replace chunk set
        UUID again = documentRepository.upsertManualDocument(COLLECTION, VERSION, SOURCE, "manual", "Title");
        assertThat(again).isEqualTo(docId);
        int removed = documentRepository.deleteChunksForDocument(again);
        assertThat(removed).isEqualTo(2);
        documentRepository.insertChunks(again, COLLECTION, VERSION, SOURCE, "manual", twoChunks());

        assertThat(chunkCount(docId)).isEqualTo(2);
    }

    @Test
    public void listDocumentsReportsCorrectChunkCountWithPartitionPruning() {
        // PRF-10: the chunk-count subqueries now include collection_id so the planner can prune to
        // the document's partition. This must not change the reported count.
        UUID docId = documentRepository.upsertManualDocument(COLLECTION, VERSION, SOURCE, "manual", "Title");
        documentRepository.deleteChunksForDocument(docId);
        documentRepository.insertChunks(docId, COLLECTION, VERSION, SOURCE, "manual", twoChunks());

        DocumentDetails details = documentRepository.listDocuments(COLLECTION, 100, 0, null).stream()
            .filter(d -> d.id().equals(docId)).findFirst().orElseThrow();

        assertThat(details.chunkCount()).isEqualTo(2L);
    }

    @Test
    public void confluenceDocumentsWithTheSameTitleStaySeparate() {
        // Two Confluence pages routinely share a title (and a blank title normalises to
        // "Untitled"): keying on the title collapsed them onto ONE row, so the last crawled page
        // destroyed the previous one. The Confluence path keys strictly on source_file.
        UUID first = documentRepository.upsertDocumentBySourceFile(
                COLLECTION, VERSION, "https://wiki/pages/1", "confluence", "Shared Title");
        UUID second = documentRepository.upsertDocumentBySourceFile(
                COLLECTION, VERSION, "https://wiki/pages/2", "confluence", "Shared Title");
        UUID firstAgain = documentRepository.upsertDocumentBySourceFile(
                COLLECTION, VERSION, "https://wiki/pages/1", "confluence", "Renamed Since");

        assertThat(second).isNotEqualTo(first);
        assertThat(firstAgain).isEqualTo(first); // still idempotent on source_file

        Integer docCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM documents d JOIN collections c ON d.collection_id = c.id "
                        + "WHERE c.name = ? AND d.kind = 'confluence'",
                Integer.class,
                COLLECTION);
        assertThat(docCount).isEqualTo(2);
    }

    @Test
    public void confluenceDocumentsWithBlankTitlesStaySeparate() {
        UUID first = documentRepository.upsertDocumentBySourceFile(
                COLLECTION, VERSION, "https://wiki/pages/10", "confluence", "");
        UUID second = documentRepository.upsertDocumentBySourceFile(
                COLLECTION, VERSION, "https://wiki/pages/11", "confluence", null);

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    public void manualUpsertStillMatchesOnTitle() {
        // Unchanged identity semantics for the manuals paths: a re-ingest under a different
        // source_file but the same title still resolves to the same document row.
        UUID first = documentRepository.upsertManualDocument(
                COLLECTION, VERSION, "manual_a.md", "manual", "Same Title");
        UUID second = documentRepository.upsertManualDocument(
                COLLECTION, VERSION, "manual_b.md", "manual", "Same Title");

        assertThat(second).isEqualTo(first);
    }

    // ---------------------------------------------------------------------
    // Wave 3b: document identity is a CONSTRAINT (ux_documents_collection_kind_source_file_tags),
    // not just what the upsert happens to look up.
    // ---------------------------------------------------------------------

    @Test
    public void aSecondDocumentForTheSameSourceFileIsRejected() {
        UUID first = documentRepository.upsertDocumentBySourceFile(
                COLLECTION, VERSION, "https://wiki/pages/77", "confluence", "Page");
        assertThat(first).isNotNull();

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "INSERT INTO documents (id, collection_id, kind, title, ingestion_status, metadata, tags) "
                                + "SELECT ?, id, 'confluence', 'Copy', 'pending', "
                                + "jsonb_build_object('source_file', 'https://wiki/pages/77'), ARRAY[?] "
                                + "FROM collections WHERE name = ?",
                        UUID.randomUUID(),
                        VERSION,
                        COLLECTION))
                .isInstanceOf(DuplicateKeyException.class);
    }

    /**
     * The upsert is SELECT-then-INSERT under READ COMMITTED, so two page-import jobs for one page (a
     * re-triggered crawl draining alongside the first) both see no row. Before ON CONFLICT that
     * meant two documents and two full chunk sets for one URL; with the unique index alone it would
     * mean the loser throwing. The loser must ADOPT the winner's row.
     *
     * <p>The race is forced rather than hoped for: the winner's transaction is held open while the
     * loser runs, so the loser's INSERT blocks on the index until the winner commits.
     */
    @Test
    public void aConcurrentUpsertOfTheSameSourceFileAdoptsTheWinnersRow() throws Exception {
        String sourceFile = "https://wiki/pages/race";
        // Pre-register the tag on the collection: otherwise BOTH threads try to append it and the
        // loser blocks on the collections row instead of ever reaching the document INSERT.
        jdbcTemplate.update(
                "UPDATE collections SET tags = ARRAY[?] WHERE name = ?", VERSION, COLLECTION);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<Future<UUID>> loser = new AtomicReference<>();
        try {
            UUID winner = new TransactionTemplate(transactionManager).execute(status -> {
                UUID id = documentRepository.upsertDocumentBySourceFile(
                        COLLECTION, VERSION, sourceFile, "confluence", "Winner");
                loser.set(pool.submit(() -> documentRepository.upsertDocumentBySourceFile(
                        COLLECTION, VERSION, sourceFile, "confluence", "Loser")));
                try {
                    // Long enough for the loser to reach its INSERT and block on the unique index.
                    Thread.sleep(750);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return id;
            });

            assertThat(loser.get().get(30, TimeUnit.SECONDS)).isEqualTo(winner);
        } finally {
            pool.shutdownNow();
        }

        Integer docCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM documents d JOIN collections c ON d.collection_id = c.id "
                        + "WHERE c.name = ? AND d.metadata->>'source_file' = ?",
                Integer.class,
                COLLECTION,
                sourceFile);
        assertThat(docCount).isEqualTo(1);
    }

    // ---------------------------------------------------------------------
    // tags is part of ux_documents_collection_kind_source_file_tags (V3), so an in-place tag
    // rewrite can now COLLIDE with a sibling row for the same source file. Two versions of one
    // source file in one collection separated only by tag is the documented OIM install-kit shape,
    // so the sibling is ordinary — and a lifecycle tag removal that aborts on a raw 23505 takes the
    // whole cascade down with it.
    // ---------------------------------------------------------------------

    @Test
    public void removeTagAndPurgeOrphansSkipsARewriteThatWouldCollideWithASibling() {
        UUID collectionId = collectionId();
        // The documented shape: one source file, two versions, separated only by tag. A THIRD
        // version of the SAME source file is blocked too, so the number of blocked documents (2)
        // and the number of distinct source files they span (1) genuinely differ.
        UUID both = insertDocument("kit/install.md", "9.3.1", "10.0");
        UUID tenOnly = insertDocument("kit/install.md", "10.0");
        UUID alsoBlocked = insertDocument("kit/install.md", "9.3.1", "11.0");
        UUID elevenOnly = insertDocument("kit/install.md", "11.0");
        // No sibling: this one must still be rewritten, or the guard is over-skipping.
        UUID rewritable = insertDocument("kit/upgrade.md", "9.3.1", "10.0");
        // Sole tag: still deleted outright.
        UUID orphan = insertDocument("kit/legacy.md", "9.3.1");

        ListAppender<ILoggingEvent> warnings = captureRepositoryLog();
        try {
            assertThat(documentRepository.removeTagAndPurgeOrphans(collectionId, "9.3.1"))
                    .isEqualTo(1);
        } finally {
            releaseRepositoryLog(warnings);
        }

        assertThat(tagsOf(orphan)).isNull();
        assertThat(tagsOf(rewritable)).containsExactly("10.0");
        assertThat(tagsOf(tenOnly)).containsExactly("10.0");
        assertThat(tagsOf(elevenOnly)).containsExactly("11.0");
        // Skipped, not rewritten and not deleted: rewriting either would have destroyed the row
        // that already owns the resulting tag set.
        assertThat(tagsOf(both)).containsExactly("9.3.1", "10.0");
        assertThat(tagsOf(alsoBlocked)).containsExactly("9.3.1", "11.0");

        // The warning must count DOCUMENTS, not the deduplicated (and capped) source-file sample:
        // both blocked rows share one source file, so the old size-of-the-sample number reported 1.
        String warning = warnings.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("could not be detached"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no blocked-tag-removal warning was logged"));
        assertThat(warning).contains("from 2 document(s) across 1 source file(s)");
        assertThat(warning).contains("kit/install.md");
    }

    /** Captures WARNs from the repository so the reported counts can be asserted, not just eyeballed. */
    private ListAppender<ILoggingEvent> captureRepositoryLog() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(DocumentRepository.class)).addAppender(appender);
        return appender;
    }

    private void releaseRepositoryLog(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(DocumentRepository.class)).detachAppender(appender);
        appender.stop();
    }

    @Test
    public void updateIngestionStatusPersists() {
        UUID docId = documentRepository.upsertManualDocument(COLLECTION, VERSION, SOURCE, "manual", "Title");
        documentRepository.updateIngestionStatus(docId, "completed");

        String status =
                jdbcTemplate.queryForObject("SELECT ingestion_status FROM documents WHERE id = ?", String.class, docId);
        assertThat(status).isEqualTo("completed");
    }

    private int chunkCount(UUID docId) {
        Integer n = jdbcTemplate.queryForObject("SELECT count(*) FROM chunks WHERE document_id = ?", Integer.class, docId);
        return n == null ? 0 : n;
    }

    private UUID collectionId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM collections WHERE name = ?", UUID.class, COLLECTION);
    }

    private UUID insertDocument(String sourceFile, String... tags) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO documents (id, collection_id, kind, title, metadata, tags) "
                        + "VALUES (?, ?, 'manual', ?, jsonb_build_object('source_file', ?::text), ?::text[])",
                id,
                collectionId(),
                sourceFile,
                sourceFile,
                tags);
        return id;
    }

    /** The row's tags, or {@code null} if the row is gone. */
    private List<String> tagsOf(UUID docId) {
        List<List<String>> rows = jdbcTemplate.query(
                "SELECT tags FROM documents WHERE id = ?",
                (rs, rowNum) -> List.of((String[]) rs.getArray("tags").getArray()),
                docId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
