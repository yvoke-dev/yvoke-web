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
import java.util.Map;
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
import de.palsoftware.yvoke.collection.core.repository.CollectionRepository;
import de.palsoftware.yvoke.tag.core.repository.TagRepository;
import de.palsoftware.yvoke.document.core.repository.ChunkRepository;

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

    @Autowired
    private CollectionRepository collectionRepository;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private ChunkRepository chunkRepository;

    /** What {@code collections.tags} holds — the one and only source of the corpus tag vocabulary. */
    private List<String> declaredTags() {
        return jdbcTemplate.queryForObject("SELECT tags FROM collections WHERE name = ?",
            (rs, rowNum) -> List.of((String[]) rs.getArray("tags").getArray()), COLLECTION);
    }

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

    /**
     * Document identity includes the canonical TAG SET, so the same {@code source_file} under a
     * second tag is a SECOND document with its own chunks — that is what lets one collection hold
     * two product versions of the same file. If identity were tag-blind, ingesting 10.0 would
     * resolve onto 9.3's row and {@code deleteChunksForDocument} would then destroy 9.3's chunk set
     * and replace it with 10.0's, reporting success the whole way: the older version would simply
     * cease to exist with no error anywhere. (The same-tag duplicate stays rejected — see
     * {@link #aSecondDocumentForTheSameSourceFileIsRejected()}.)
     */
    @Test
    public void theSameSourceFileUnderASecondTagIsItsOwnDocumentWithItsOwnChunks() {
        String sourceFile = "manuals/install.md";

        UUID v93 = documentRepository.upsertManualDocument(COLLECTION, "9.3", sourceFile, "manual",
            "Install Guide");
        documentRepository.insertChunks(v93, COLLECTION, "9.3", sourceFile, "manual", twoChunks());

        UUID v10 = documentRepository.upsertManualDocument(COLLECTION, "10.0", sourceFile, "manual",
            "Install Guide");
        documentRepository.insertChunks(v10, COLLECTION, "10.0", sourceFile, "manual", twoChunks());

        assertThat(v10).as("a different tag is a different document, never the same row")
            .isNotEqualTo(v93);
        assertThat(chunkCount(v93)).as("the first version's chunks must survive the second ingest")
            .isEqualTo(2);
        assertThat(chunkCount(v10)).isEqualTo(2);

        // And re-ingesting one version must not touch the other's chunks.
        documentRepository.deleteChunksForDocument(v10);
        assertThat(chunkCount(v93)).as("re-ingesting 10.0 must not disturb 9.3").isEqualTo(2);
        assertThat(chunkCount(v10)).isZero();
    }

    /**
     * The Confluence version-skip lookup decides whether a crawl re-ingests a page, so it MUST be
     * scoped to the crawl's OWN target. Spec § 9 explicitly supports two connector instances over
     * the same page tree feeding different collections (or different tags), and the connector's
     * {@code target_collection} is a NAME resolved case-insensitively. If this lookup ignored
     * collection or tag, the second instance's very first sync would find the first instance's row,
     * conclude every page was already at this version, and ingest NOTHING — a completely empty
     * collection reported as a successful crawl.
     */
    @Test
    public void confluenceVersionSkipLookupIsScopedToItsOwnCollectionAndTag() {
        String sourceFile = "https://wiki/pages/500";
        UUID docId = documentRepository.upsertDocumentBySourceFile(COLLECTION, "9.3", sourceFile,
            "confluence", "Page 500");
        jdbcTemplate.update(
            "UPDATE documents SET ingestion_status = 'completed', "
                + "metadata = metadata || jsonb_build_object('confluence_page_version', '7') WHERE id = ?",
            docId);

        // Its own scope sees it (case-insensitively on the collection name).
        assertThat(documentRepository.getMetadataAndStatus(COLLECTION.toLowerCase(), "9.3",
            sourceFile, "confluence")).isPresent()
                .get()
                .satisfies(m -> assertThat(m.pageVersionStr()).isEqualTo("7"));

        // A different TAG in the same collection is a different document — no skip.
        assertThat(documentRepository.getMetadataAndStatus(COLLECTION, "10.0", sourceFile,
            "confluence")).as("another version's crawl must not be skipped").isEmpty();

        // A different COLLECTION must not see it either.
        String otherCollection = COLLECTION + "-OTHER";
        jdbcTemplate.update(
            "INSERT INTO collections (id, name) VALUES (?, ?) ON CONFLICT (name) DO NOTHING",
            UUID.randomUUID(), otherCollection);
        try {
            assertThat(documentRepository.getMetadataAndStatus(otherCollection, "9.3", sourceFile,
                "confluence")).as("a second connector instance must ingest, not skip").isEmpty();
        } finally {
            jdbcTemplate.update("DELETE FROM collections WHERE name = ?", otherCollection);
        }
    }

    /**
     * The corpus tag vocabulary is DERIVED from {@code collections.tags} — there is no registry
     * table any more (V6 dropped it precisely because its only writer was the admin form, while
     * every ingest path set the {@code TEXT[]} columns directly and the dropdown quietly held two
     * names while the corpus held 27 collections and 22k tagged documents). That makes
     * {@code collections.tags} load-bearing rather than decorative: it is the ONLY place the admin
     * tag dropdowns, the corpus-browser filter and {@code getVersionOptions} learn that a tag
     * exists. If an ingest write stopped re-declaring its tag, a freshly ingested product version
     * would be unfilterable and unsuggestable everywhere — and the tell is a dropdown that is
     * SHORT rather than wrong, which reads as "there are only these tags", never as "this list is
     * stale". Nothing errors, the job reports a normal count, and no other test looks at the
     * collection row after an ingest write.
     *
     * <p>
     * The second half pins the GUARD, not just the append. {@code insertChunks} resolves the same
     * collection again for the same tag, so without {@code NOT (:tag = ANY(tags))} every chunk
     * batch would push another copy onto the array and the vocabulary would grow one duplicate
     * entry per ingest. The third pins the "non-blank" half: a blank tag is filtered out of the
     * document's own tag set, and an empty-string entry in a filter dropdown is unselectable noise
     * that no ingest can ever match.
     */
    @Test
    public void anIngestWriteDeclaresItsTagOnTheCollectionSoTheDerivedVocabularySeesIt() {
        String tag = "9.9-derived-vocab";
        jdbcTemplate.update("UPDATE collections SET tags = '{}'::text[] WHERE name = ?", COLLECTION);
        assertThat(declaredTags()).as("the collection starts with no declared tags").isEmpty();

        UUID docId =
            documentRepository.upsertManualDocument(COLLECTION, tag, SOURCE, "manual", "Title");

        assertThat(declaredTags())
            .as("the document write must re-declare its tag on the collection")
            .containsExactly(tag);
        assertThat(collectionRepository.findAllTagNames())
            .as("the derived vocabulary is read straight off collections.tags").contains(tag);

        // The chunk write resolves the same collection again: the NOT (:tag = ANY(tags)) guard is
        // what stops a second copy of an already-declared tag being appended per batch.
        documentRepository.insertChunks(docId, COLLECTION, tag, SOURCE, "manual", twoChunks());
        assertThat(declaredTags()).as("an already-declared tag must not be appended twice")
            .containsExactly(tag);

        // A blank tag is declared by nobody — it is not part of the document's tag set either.
        documentRepository.upsertManualDocument(COLLECTION, "   ", "blank_tag.md", "manual",
            "Blank");
        assertThat(declaredTags()).containsExactly(tag);
    }

    /**
     * {@code documents.tags} and {@code chunks.tags} are two independent columns that only AGREE by
     * convention, and the convention holds at ingest and nowhere else.
     *
     * <p>
     * At ingest they mirror because a single caller hands the same tag list to both writes inside
     * one transaction ({@code DocumentIngestService.persistDocument}, {@code CustomIngestService},
     * {@code ConfluenceIngestService} via the single-tag convenience overload) — {@code insertChunks}
     * never reads the document row's stored tags, it stamps the batch's own list onto every chunk.
     * Afterwards nothing reconciles them: {@code TagRepository.addTagToDocument} /
     * {@code removeTagFromDocument} — the admin document-detail buttons — update {@code documents}
     * alone, and {@code DocumentRepository.removeTagAndPurgeOrphans} detaches from {@code documents}
     * alone (its DELETE branch only reaches chunks through the FK cascade).
     *
     * <p>
     * That divergence is not cosmetic, because the two retrieval lanes read DIFFERENT columns:
     * {@code ChunkRepository.findSemanticCandidates} filters on {@code d.tags} while
     * {@code findFulltextCandidates} filters on {@code ch.tags} (the ParadeDB {@code ===} term
     * match). So after an admin retags a document, the vector lane sees the new tag and the BM25
     * lane does not: the same query returns the chunk in one lane and not the other, RRF fuses a
     * half-populated candidate set, and there is no error anywhere. No test asserts either
     * direction — {@code TagRepositoryIT} exercises both tag mutators and never queries chunks.
     */
    @Test
    public void addingATagToADocumentLeavesItsChunksOnTheOldTagSet() {
        UUID docId = documentRepository.upsertManualDocument(COLLECTION, "9.3", "kit/tags.md",
                "manual", "Tag Drift");
        documentRepository.insertChunks(docId, COLLECTION, "9.3", "kit/tags.md", "manual", twoChunks());

        // 1. At ingest the two columns MIRROR: one caller, one tag list, both writes.
        assertThat(tagsOf(docId)).containsExactly("9.3");
        assertThat(chunkTags(docId)).as("insertChunks stamps the batch's tags onto every chunk row")
                .hasSize(2)
                .allSatisfy(tags -> assertThat(tags).containsExactly("9.3"));

        // 2. An admin add-tag rewrites the document only.
        tagRepository.addTagToDocument(docId, "10.0");

        assertThat(tagsOf(docId)).containsExactly("9.3", "10.0");
        assertThat(chunkTags(docId))
                .as("chunks do NOT follow the document — the BM25 lane still filters on the old set")
                .allSatisfy(tags -> assertThat(tags).containsExactly("9.3"));

        // 3. ...and so does an admin remove-tag, which is where it becomes visibly wrong: the
        // chunks now carry a tag their own document no longer has.
        tagRepository.removeTagFromDocument(docId, "9.3");

        assertThat(tagsOf(docId)).containsExactly("10.0");
        assertThat(chunkTags(docId))
                .as("a 10.0 document whose chunks are still 9.3: invisible to the full-text lane at 10.0")
                .allSatisfy(tags -> assertThat(tags).containsExactly("9.3"));
    }

    /** Every chunk's tag array, in document reading order. */
    private List<List<String>> chunkTags(UUID docId) {
        return jdbcTemplate.query(
                "SELECT tags FROM chunks WHERE document_id = ? ORDER BY sort_order",
                (rs, rowNum) -> List.of((String[]) rs.getArray("tags").getArray()),
                docId);
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

    /**
     * The corpus browser's tag filter arrives as a COMMA LIST and it means OR. {@code &&} is array
     * OVERLAP; {@code @>} is array CONTAINMENT, i.e. AND. Both operators are legitimately present
     * in this very repository — {@code findExisting} deliberately uses {@code @>} together with
     * {@code <@} to mean "exactly this tag set", which is document identity — so the two are one
     * paste apart, and the wrong one compiles, runs and returns rows.
     *
     * <p>
     * On this corpus a document belongs to exactly ONE kit version, so under containment the filter
     * that means "show me 9.3 and 10.0" matches documents carrying BOTH tags: zero rows. The
     * operator is shown a confident, error-free empty page for a collection holding thousands of
     * documents and reasonably concludes the ingest failed. {@code countDocuments} repeats the same
     * predicate for the pager, so the two must agree or the page count and the page contradict each
     * other. No existing test passes more than one tag: every other tag assertion in this file uses
     * a single value, which behaves identically under overlap and containment.
     */
    @Test
    public void aTwoTagCorpusFilterReturnsDocumentsCarryingEitherTagNotOnlyBoth() {
        UUID nineThree = insertDocument("kit/overlap-9.3.md", "9.3");
        UUID ten = insertDocument("kit/overlap-10.0.md", "10.0");

        assertThat(documentRepository.listDocuments(COLLECTION, 100, 0, null, "9.3,10.0"))
            .extracting(DocumentDetails::id)
            .as("a comma list is OR — a document carrying EITHER tag must be listed")
            .containsExactlyInAnyOrder(nineThree, ten);
        assertThat(documentRepository.countDocuments(COLLECTION, null, "9.3,10.0"))
            .as("the count must agree with the page, or the pager renders phantom pages")
            .isEqualTo(2);

        // ...and one tag still narrows, so the OR is not simply "the filter was ignored".
        assertThat(documentRepository.listDocuments(COLLECTION, 100, 0, null, "9.3"))
            .extracting(DocumentDetails::id).containsExactly(nineThree);
        assertThat(documentRepository.countDocuments(COLLECTION, null, "9.3")).isEqualTo(1);

        // Whitespace around the comma is an ordinary way to type this into the URL.
        assertThat(documentRepository.countDocuments(COLLECTION, null, " 9.3 , 10.0 ")).isEqualTo(2);
    }

    /**
     * The corpus browser's page order is {@code created_at DESC, id ASC} and BOTH halves are
     * load-bearing. Ingest stamps a whole batch inside ONE transaction, and {@code CURRENT_TIMESTAMP}
     * is the TRANSACTION timestamp, so hundreds of freshly ingested documents routinely share one
     * {@code created_at} to the microsecond. Without the {@code id ASC} tie-break the browser is then
     * {@code ORDER BY <non-unique column>} + {@code LIMIT/OFFSET} — the unspecified-paging hazard that
     * has already bitten {@code messages} (questions rendering below their answers, and that scrambled
     * order feeding LLM history) and {@code json_objects}: the order among tied rows is whatever the
     * plan happens to emit, so a row can appear on two pages and vanish from every other, with no
     * error and nothing on screen to say the listing is incomplete.
     *
     * <p>
     * Nothing else in this file exercises it. Every other test inserts rows one statement at a time —
     * so each row gets its own {@code CURRENT_TIMESTAMP} and no tie ever forms — and asserts set
     * membership or a single row, never a sequence. Here four rows deliberately share one timestamp
     * and are inserted in DESCENDING id order, so the heap order an untied sort returns is the exact
     * REVERSE of the contracted one: dropping the tie-break flips the assertion rather than merely
     * failing to guarantee it.
     *
     * <p>
     * The second half pins the other unasserted clause of the same statement,
     * {@code LOWER(c.name) = LOWER(:collection)}. The collection arrives as a URL parameter an admin
     * pastes from anywhere, and this app deliberately carries three different name-matching rules —
     * exact {@code name IN (:names)} in {@code ChunkRepository}, {@code LOWER()} here, and the
     * case-SENSITIVE {@code c.name = :collection} of the export query — so "how does this call site
     * match names?" has to be asserted per call site. {@code ListDocumentsToolTest} mocks the
     * repository and would not notice; the failure is the silent-empty shape, a confident error-free
     * empty page for a collection holding thousands of documents.
     */
    @Test
    public void theCorpusBrowserPagesInATotalOrderAndMatchesTheCollectionNameCaseInsensitively() {
        String insert =
            "INSERT INTO documents (id, collection_id, kind, title, metadata, tags, created_at) "
                + "VALUES (?, ?, 'manual', ?, jsonb_build_object('source_file', ?::text), ?::text[], ?::timestamptz)";
        UUID collectionId = collectionId();
        String[] tags = new String[] {VERSION};

        // One batch, one created_at, ids inserted in DESCENDING order: heap order (what a sort with
        // no tie-break returns) is the exact reverse of the contracted id ASC order.
        UUID d1 = UUID.fromString("11111111-0000-4000-8000-000000000001");
        UUID d2 = UUID.fromString("22222222-0000-4000-8000-000000000002");
        UUID d3 = UUID.fromString("33333333-0000-4000-8000-000000000003");
        UUID d4 = UUID.fromString("44444444-0000-4000-8000-000000000004");
        String batchStamp = "2026-01-01T10:00:00Z";
        jdbcTemplate.update(insert, d4, collectionId, "kit/order-d.md", "kit/order-d.md", tags,
            batchStamp);
        jdbcTemplate.update(insert, d3, collectionId, "kit/order-c.md", "kit/order-c.md", tags,
            batchStamp);
        jdbcTemplate.update(insert, d2, collectionId, "kit/order-b.md", "kit/order-b.md", tags,
            batchStamp);
        jdbcTemplate.update(insert, d1, collectionId, "kit/order-a.md", "kit/order-a.md", tags,
            batchStamp);
        // A later document, which must sort above the whole tied batch whatever its id is — so the
        // assertion below cannot be satisfied by an id-only order either.
        UUID newer = UUID.fromString("0000aaaa-0000-4000-8000-00000000000f");
        jdbcTemplate.update(insert, newer, collectionId, "kit/order-newest.md",
            "kit/order-newest.md", tags, "2026-01-02T10:00:00Z");

        assertThat(documentRepository.listDocuments(COLLECTION, 100, 0, null))
            .extracting(DocumentDetails::id)
            .as("newest first, then a TOTAL order among the rows sharing one created_at")
            .containsExactly(newer, d1, d2, d3, d4);

        // The pager slices that same order: consecutive pages must partition it exactly — no row
        // repeated, none skipped. That is the property LIMIT/OFFSET cannot have without a total order.
        assertThat(documentRepository.listDocuments(COLLECTION, 2, 0, null))
            .extracting(DocumentDetails::id).containsExactly(newer, d1);
        assertThat(documentRepository.listDocuments(COLLECTION, 2, 2, null))
            .extracting(DocumentDetails::id).containsExactly(d2, d3);
        assertThat(documentRepository.listDocuments(COLLECTION, 2, 4, null))
            .extracting(DocumentDetails::id).containsExactly(d4);

        // Same query, collection name in the other case: this filter is LOWER()-matched, unlike the
        // export endpoint's exact match.
        assertThat(documentRepository.listDocuments(COLLECTION.toLowerCase(), 100, 0, null))
            .extracting(DocumentDetails::id)
            .as("the browser's collection filter is case-insensitive")
            .containsExactly(newer, d1, d2, d3, d4);
        assertThat(documentRepository.countDocuments(COLLECTION.toLowerCase(), null))
            .as("and the pager's count must agree, or the page numbers come from a different query")
            .isEqualTo(5);
    }

    /**
     * The corpus browser's id box and {@code findByIdPrefix} are two DIFFERENT lookups over the
     * same column, and only one of them is a prefix rule. {@code findByIdPrefix} strips the dashes,
     * demands at least 8 hex characters and anchors at the start, because it resolves a citation to
     * exactly one document. The browser filter is a human search box: an operator pastes whatever
     * fragment of an id they have — the tail from a log line, one dashed group copied out of a URL,
     * a chunk id's middle — so it is {@code CAST(d.id AS text) ILIKE '%…%'}, matching anywhere in
     * the DASHED form.
     *
     * <p>
     * Aligning the two ("the id search should behave like the other id search") is a one-token edit
     * that compiles, keeps every existing test green — no other test passes a searchId at all — and
     * turns the box into a filter that silently matches nothing for every fragment that is not a
     * prefix. The operator sees an empty, error-free page for a document they are looking straight
     * at, and reasonably concludes the document is gone rather than that the search is wrong. The
     * negative assertion at the end is what stops the opposite regression: a filter that was
     * dropped entirely would return the row too.
     */
    @Test
    public void aCorpusBrowserIdSearchMatchesAMiddleSegmentOfTheDashedUuid() {
        UUID docId = UUID.fromString("0f9c1a2b-7d3e-4c55-9b81-aa0011223344");
        jdbcTemplate.update(
            "INSERT INTO documents (id, collection_id, kind, title, metadata, tags) "
                + "VALUES (?, ?, 'manual', ?, jsonb_build_object('source_file', ?::text), ?::text[])",
            docId, collectionId(), "kit/id-search.md", "kit/id-search.md",
            new String[] {VERSION});

        // The second dashed group: deliberately not a prefix, and invisible to findByIdPrefix,
        // which removes the dashes and anchors at the start.
        String middleGroup = docId.toString().substring(9, 13);
        assertThat(middleGroup).isEqualTo("7d3e");

        assertThat(documentRepository.listDocuments(COLLECTION, 100, 0, null, null, middleGroup))
            .as("the id filter matches a substring anywhere in the dashed uuid")
            .extracting(DocumentDetails::id).containsExactly(docId);
        assertThat(documentRepository.countDocuments(COLLECTION, null, null, middleGroup))
            .as("the count must agree with the page, or the pager renders phantom pages")
            .isEqualTo(1);

        // ...and it is still a filter: a fragment this id does not carry must match nothing.
        assertThat(documentRepository.listDocuments(COLLECTION, 100, 0, null, null, "ffff"))
            .isEmpty();
        assertThat(documentRepository.countDocuments(COLLECTION, null, null, "ffff")).isZero();
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

    /**
     * §10.7: the adopt branch of {@code upsert} returns the existing id and writes NOTHING. That
     * looks like an oversight — a re-crawl of a renamed Confluence page leaves a stale title in the
     * corpus browser, and "refresh the title while we're here" is a one-line addition — but the
     * branch is not the place for it, for two reasons that only show up under load.
     *
     * <p>
     * First, this is also the CONCURRENCY-LOSER path: when two jobs race for one source file the
     * loser's INSERT is swallowed by {@code ON CONFLICT DO NOTHING} and it falls through to the very
     * same lookup, adopting the WINNER's row (see
     * {@link #aConcurrentUpsertOfTheSameSourceFileAdoptsTheWinnersRow()}). An UPDATE here means the
     * loser rewrites a row the winner is mid-ingest on. Second, the manuals variant resolves
     * identity by TITLE as well as source_file ({@code matchByTitle}), so a title write in this
     * branch can rename a sibling row the caller never named — and since the title is part of that
     * identity, the next upsert then resolves differently than the last one did.
     *
     * <p>
     * Nothing existing would catch the addition: {@code confluenceDocumentsWithTheSameTitleStaySeparate}
     * already re-upserts page 1 as "Renamed Since" but asserts only that the ID is stable, which
     * stays true whether or not the row was rewritten underneath it.
     */
    @Test
    public void reIngestingUnderAChangedTitleAdoptsTheExistingRowWithoutRewritingIt() {
        String sourceFile = "https://wiki/pages/frozen-title";

        UUID first = documentRepository.upsertDocumentBySourceFile(COLLECTION, VERSION, sourceFile,
            "confluence", "Title A");
        UUID second = documentRepository.upsertDocumentBySourceFile(COLLECTION, VERSION, sourceFile,
            "confluence", "Title B");

        assertThat(second).as("identity is the source file, so the second call adopts the row")
            .isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject("SELECT title FROM documents WHERE id = ?",
            String.class, first))
                .as("an adopted row is returned, never rewritten — the title is frozen at insert")
                .isEqualTo("Title A");

        Integer docCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM documents d JOIN collections c ON d.collection_id = c.id "
                + "WHERE c.name = ? AND d.metadata->>'source_file' = ?",
            Integer.class, COLLECTION, sourceFile);
        assertThat(docCount).as("and no second row was created for the new title").isEqualTo(1);
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

    /**
     * These two probes are the ENTIRE evidence base for {@code CitationVerifier.resolveIds}, which
     * asks both tables about a bare bracketed uuid and lets whichever one answers decide REAL vs
     * FABRICATED. That verdict is destructive: {@code CitationVerifier.isFabricated} drives
     * {@code CitationStreamingFilter}, which deletes what it flags from the live SSE stream
     * character by character. So a probe that stopped resolving real ids would strip every citation
     * out of every answer — no exception, no log, just answers whose sources have quietly vanished —
     * and a probe that answered from the WRONG table would confirm a document id as a chunk,
     * linkifying a citation whose popup then dead-ends in {@code CitationController}'s NOT_FOUND
     * branch.
     *
     * <p>
     * The cross-table assertion is the one a mock cannot make, and it is exactly the assertion no
     * existing test makes: {@code CitationVerifierTest} and {@code CitationStreamingFilterTest} both
     * stub {@code findExistingIds} with hand-written answer sets, so they confirm whichever
     * classification the test author chose and this SQL has shipped unexecuted. Here the ids are
     * real rows: a chunk id, its own document's id, and a uuid that was never inserted at all.
     *
     * <p>
     * The empty-input case pins the short-circuit guard rather than the SQL — an empty probe must
     * come back as an empty set (every id unresolved, hence nothing confirmed) and never as a query
     * against an empty array.
     */
    @Test
    public void citationExistenceProbesAreScopedToTheirOwnTableAndDropUnknownIds() {
        UUID docId = documentRepository.upsertManualDocument(COLLECTION, VERSION, SOURCE, "manual", "Title");
        documentRepository.insertChunks(docId, COLLECTION, VERSION, SOURCE, "manual", twoChunks());
        List<UUID> chunkIds = jdbcTemplate.queryForList(
                "SELECT id FROM chunks WHERE document_id = ? ORDER BY sort_order", UUID.class, docId);
        assertThat(chunkIds).hasSize(2);
        UUID chunkId = chunkIds.get(0);
        UUID neverInserted = UUID.randomUUID();

        assertThat(chunkRepository.findExistingIds(List.of(chunkId, docId, neverInserted)))
                .as("the chunk probe answers from chunks ONLY: a real document id is not a chunk, "
                        + "and an invented id must not be confirmed")
                .containsExactly(chunkId);

        assertThat(documentRepository.findExistingIds(List.of(docId, chunkId, neverInserted)))
                .as("and the document probe mirrors it: a real chunk id is not a document")
                .containsExactly(docId);

        // Both chunks resolve, so the probe is a set membership test and not "the first row wins".
        assertThat(chunkRepository.findExistingIds(chunkIds))
                .containsExactlyInAnyOrder(chunkIds.get(0), chunkIds.get(1));

        // Nothing to check resolves nothing — the guard, not the query.
        assertThat(chunkRepository.findExistingIds(List.of())).isEmpty();
        assertThat(documentRepository.findExistingIds(List.of())).isEmpty();
    }

    @Test
    public void updateIngestionStatusPersists() {
        UUID docId = documentRepository.upsertManualDocument(COLLECTION, VERSION, SOURCE, "manual", "Title");
        documentRepository.updateIngestionStatus(docId, "completed");

        String status =
                jdbcTemplate.queryForObject("SELECT ingestion_status FROM documents WHERE id = ?", String.class, docId);
        assertThat(status).isEqualTo("completed");
    }

    /**
     * {@code documents.metadata} has three writers and they MUST all merge.
     *
     * <p>
     * The INSERT in {@code upsertManualDocument} creates it as
     * {@code jsonb_build_object('source_file', …)}; {@code updateMetadataKey} (Confluence page
     * version) and {@code markKgProcessed} (the kg-extract stamp) then both add keys with the
     * {@code ||} merge operator. Turning either {@code ||} into {@code =} is a one-character edit
     * that reads like a simplification, and it destroys {@code source_file} — which is not ordinary
     * data. It is inside the unique index {@code ux_documents_collection_kind_source_file_tags} and
     * IS the {@code ON CONFLICT} arbiter expression, so losing it means: re-ingest's
     * {@code DELETE … WHERE d.metadata->>'source_file' = :sourceFile} stops matching and the next
     * run INSERTs a DUPLICATE document instead of replacing (duplicate chunks, drifting BM25
     * statistics, a perfectly normal-looking job count), and the concurrent-upsert protection the
     * arbiter provides silently stops working.
     *
     * <p>
     * The two merging writers must also not clobber EACH OTHER: a Confluence document that is later
     * kg-extracted would otherwise lose {@code confluence_page_version}, re-enabling an import that
     * the version-skip check in {@code getMetadataAndStatus} exists to prevent. Hence one document
     * carrying all three key groups at once, rather than three isolated tests.
     */
    @Test
    public void metadataWritesMergeSoTheSourceFileThatIsIdentitySurvives() {
        UUID docId = documentRepository.upsertManualDocument(COLLECTION, VERSION, SOURCE, "manual", "Title");

        documentRepository.updateMetadataKey(docId, "confluence_page_version", "7");
        documentRepository.markKgProcessed(docId, "2026-08-07T10:00:00Z", 12, 34);

        Map<String, Object> md = jdbcTemplate.queryForMap(
                "SELECT metadata->>'source_file' AS source_file,"
                        + " metadata->>'confluence_page_version' AS page_version,"
                        + " metadata->>'kg_processed_at' AS kg_at,"
                        + " metadata->>'kg_entities' AS kg_entities,"
                        + " metadata->>'kg_edges' AS kg_edges"
                        + " FROM documents WHERE id = ?",
                docId);

        assertThat(md.get("source_file"))
                .as("source_file is the unique-index / ON CONFLICT arbiter key — losing it duplicates the document on re-ingest")
                .isEqualTo(SOURCE);
        assertThat(md.get("page_version"))
                .as("markKgProcessed must not clobber what updateMetadataKey wrote")
                .isEqualTo("7");
        assertThat(md.get("kg_at")).isEqualTo("2026-08-07T10:00:00Z");
        assertThat(md.get("kg_entities")).isEqualTo("12");
        assertThat(md.get("kg_edges")).isEqualTo("34");

        // The consequence, not just the column: with source_file intact the upsert still resolves
        // to the SAME row rather than inserting a second one.
        assertThat(documentRepository.upsertManualDocument(COLLECTION, VERSION, SOURCE, "manual", "Title"))
                .isEqualTo(docId);
    }

    /**
     * {@code listByCollectionAndTag} is the SQL behind {@code GET /api/document/v1}, the corpus
     * export/diff endpoint, and it has never been executed by any test: its single caller's tests
     * ({@code DocumentApiControllerTest}) mock this repository, so all three of its contractual
     * clauses have shipped unverified. Each fails silently and each corrupts a different consumer.
     *
     * <p>
     * The ORDER BY is {@code metadata->>'source_file' ASC} because the endpoint is UNPAGINATED and its
     * consumer diffs one export against another: any other order — {@code created_at DESC} is the
     * reflex, and it is what every other listing here uses — makes a diff between two exports report
     * churn nobody made, on every document, for as long as it takes someone to distrust the tool. The
     * tag predicate is the version scope: hand a script the wrong product version's documents and it
     * will happily rewrite 9.3 content from a 10.0 export. And {@code c.name = :collection} is exact,
     * the ONE collection lookup in the app that does not {@code LOWER()} — an X-API-Key caller who
     * passes {@code 'oim - db - history'} gets HTTP 200 with {@code []}, the silent-empty shape that
     * reads as "the corpus does not contain this" rather than "you typed the name in the wrong case".
     *
     * <p>
     * The seeded {@code created_at} values deliberately run OPPOSITE to the source-file order, so an
     * order that fell back to the ingest clock comes back exactly reversed instead of coincidentally
     * right.
     */
    @Test
    public void theExportApiListsOneTagsDocumentsOrderedBySourceFileAndOnlyUnderTheExactCollectionName() {
        String insert =
            "INSERT INTO documents (id, collection_id, kind, title, metadata, tags, created_at) "
                + "VALUES (?, ?, 'manual', ?, jsonb_build_object('source_file', ?::text), ?::text[], ?::timestamptz)";
        UUID collectionId = collectionId();
        String[] thisVersion = new String[] {VERSION};

        UUID cMd = UUID.randomUUID();
        UUID aMd = UUID.randomUUID();
        UUID bMd = UUID.randomUUID();
        jdbcTemplate.update(insert, cMd, collectionId, "kit/c.md", "kit/c.md", thisVersion,
            "2026-01-03T00:00:00Z");
        jdbcTemplate.update(insert, aMd, collectionId, "kit/a.md", "kit/a.md", thisVersion,
            "2026-01-02T00:00:00Z");
        jdbcTemplate.update(insert, bMd, collectionId, "kit/b.md", "kit/b.md", thisVersion,
            "2026-01-01T00:00:00Z");
        // Same collection, the OTHER product version — and a source file that sorts FIRST, so a lost
        // tag predicate could not hide at the end of the list.
        UUID otherVersion = UUID.randomUUID();
        jdbcTemplate.update(insert, otherVersion, collectionId, "kit/0-ten.md", "kit/0-ten.md",
            new String[] {"10.0"}, "2026-01-04T00:00:00Z");

        assertThat(documentRepository.listByCollectionAndTag(COLLECTION, VERSION))
            .extracting(DocumentDetails::id)
            .as("one tag's documents, ordered by metadata->>'source_file' ASC — a diff's whole basis")
            .containsExactly(aMd, bMd, cMd);

        assertThat(documentRepository.listByCollectionAndTag(COLLECTION, "10.0"))
            .extracting(DocumentDetails::id)
            .as("the tag predicate scopes the export to exactly one product version")
            .containsExactly(otherVersion);

        // A null tag is the documented "everything in this collection" form, still source-file ordered.
        assertThat(documentRepository.listByCollectionAndTag(COLLECTION, null))
            .extracting(DocumentDetails::id).containsExactly(otherVersion, aMd, bMd, cMd);

        // ...and this is the one collection match in the app that is case-SENSITIVE.
        assertThat(documentRepository.listByCollectionAndTag(COLLECTION.toLowerCase(), VERSION))
            .as("c.name = :collection is exact here: a differently-cased name is 200 + [], not a 404")
            .isEmpty();
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
