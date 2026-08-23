package de.palsoftware.yvoke.ingest.core.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.document.core.model.ChunkRow;
import de.palsoftware.yvoke.document.core.repository.ChunkRepository;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import de.palsoftware.yvoke.ingest.core.UploadPathGuard;
import de.palsoftware.yvoke.kg.core.model.KgExtractionResult;
import de.palsoftware.yvoke.kg.core.model.KgExtractionResult.ExtractedEntity;
import de.palsoftware.yvoke.kg.core.model.KgExtractionResult.ExtractedRelationship;
import de.palsoftware.yvoke.kg.core.repository.KgWriteRepository;
import de.palsoftware.yvoke.kg.core.repository.KgWriteRepository.EntityUpsert;
import de.palsoftware.yvoke.kg.core.repository.KgWriteRepository.RelationshipUpsert;
import de.palsoftware.yvoke.kg.core.service.DocumentKgExtractor;
import de.palsoftware.yvoke.kg.core.service.KgConsolidator;
import de.palsoftware.yvoke.rag.prompt.SystemPrompt;
import de.palsoftware.yvoke.rag.prompt.SystemPromptType;
import de.palsoftware.yvoke.rag.prompt.SystemPromptService;
import de.palsoftware.yvoke.rag.retrieval.EmbeddingService;
import de.palsoftware.yvoke.shared.jobengine.model.IngestionJob;
import de.palsoftware.yvoke.shared.jobengine.model.JobContext;
import de.palsoftware.yvoke.shared.jobengine.model.JobCounts;
import de.palsoftware.yvoke.shared.jobengine.model.JobStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import de.palsoftware.yvoke.document.core.model.ChunkInsert;
import de.palsoftware.yvoke.ingest.core.model.MarkdownTree;
import de.palsoftware.yvoke.ingest.core.model.Section;
import de.palsoftware.yvoke.document.core.model.ChunkKgStatus;

/**
 * Graph-persistence rules of the LLM-extracted (manual/document) ingest path. Kinds are mandatory
 * (V3 makes {@code entities.kind} NOT NULL), so the extractor's output may only ever create nodes
 * it actually declared with a kind — the old kind-less placeholder endpoints split one logical
 * entity into a kinded row plus a kind-NULL row that the kind-aware consolidator then refused to
 * merge.
 */
public class DocumentIngestServiceTest {

    private static final String COLLECTION = "OIM";
    private static final List<String> TAGS = List.of("9.3");

    private KgWriteRepository kgRepository;
    private DocumentKgExtractor kgExtractor;
    private ChunkRepository chunkRepository;
    private DocumentRepository documentRepository;
    private DocumentIngestService service;
    private UploadPathGuard uploadPathGuard;
    private EmbeddingService embeddingService;
    private KgConsolidator kgConsolidator;
    private SectionSummarizer sectionSummarizer;

    private final UUID documentId = UUID.randomUUID();

    /**
     * An {@link IngestPrompts} that resolves {@link #SUMMARIZE_PROMPT} to a body. The prompt is a
     * required job setting now, so a test that exercises summarization has to supply one — the same
     * contract a real job is held to.
     */
    private static IngestPrompts resolvingPrompts() {
        SystemPromptService prompts = mock(SystemPromptService.class);
        when(prompts.requirePrompt(any(), eq(SystemPromptType.SUMMARIZE))).thenReturn(
            new SystemPrompt(SUMMARIZE_PROMPT, SystemPromptType.SUMMARIZE, "SUMMARIZE.", ""));
        when(prompts.requirePrompt(any(), eq(SystemPromptType.KG)))
            .thenReturn(new SystemPrompt(KG_PROMPT, SystemPromptType.KG, "STRICT JSON.", ""));
        return new IngestPrompts(prompts);
    }

    private static final String SUMMARIZE_PROMPT = "oim-summarize";
    private static final String KG_PROMPT = "oim-kg";

    @BeforeEach
    public void setUp() {
        kgRepository = mock(KgWriteRepository.class);
        kgExtractor = mock(DocumentKgExtractor.class);
        chunkRepository = mock(ChunkRepository.class);
        documentRepository = mock(DocumentRepository.class);

        uploadPathGuard = mock(UploadPathGuard.class);
        embeddingService = mock(EmbeddingService.class);
        kgConsolidator = mock(KgConsolidator.class);
        sectionSummarizer = mock(SectionSummarizer.class);
        service = new DocumentIngestService(embeddingService, documentRepository, chunkRepository,
            kgRepository, kgExtractor, kgConsolidator, mock(JdbcClient.class),
            mock(PlatformTransactionManager.class), sectionSummarizer,
            mock(SystemPromptService.class), uploadPathGuard, resolvingPrompts());

        when(chunkRepository.findChunksByDocumentId(eq(documentId), isNull()))
            .thenReturn(List.of(new ChunkRow(UUID.randomUUID(), documentId, "chunk text", List.of(),
                null, null, 0, null, null, null, COLLECTION, null, 0.0)));
        // Hand back a deterministic id per kind-aware identity key, like the real batch upsert.
        when(kgRepository.upsertEntitiesBatch(anyString(), anyList(), anyList()))
            .thenAnswer(invocation -> {
                List<EntityUpsert> specs = invocation.getArgument(2);
                Map<String, UUID> ids = new HashMap<>();
                for (EntityUpsert spec : specs) {
                    ids.computeIfAbsent(KgWriteRepository.entityKey(spec.kind(), spec.name()),
                        k -> UUID.randomUUID());
                }
                return ids;
            });
    }

    /** A KG job for the seeded document; a null job id short-circuits the cancellation probe. */
    private IngestionJob kgJob() {
        // A kg-extract job carries its prompt: the kind's existence is the request, so the prompt
        // is unconditionally required rather than gated on a flag.
        return new IngestionJob(null, "kg", documentId.toString(), TAGS, UUID.randomUUID(),
            COLLECTION, JobStatus.RUNNING, null, 0, 0, null, null, OffsetDateTime.now(), null, null,
            Map.<String, Object>of(IngestPrompts.SETTING_KG_PROMPT, KG_PROMPT));
    }

    /** The same KG job as {@link #kgJob()}, with an explicit tag set. */
    private IngestionJob kgJobWithTags(List<String> tags) {
        return new IngestionJob(null, "kg", documentId.toString(), tags, UUID.randomUUID(),
            COLLECTION, JobStatus.RUNNING, null, 0, 0, null, null, OffsetDateTime.now(), null, null,
            Map.<String, Object>of(IngestPrompts.SETTING_KG_PROMPT, KG_PROMPT));
    }

    /**
     * Graph identity is {@code (collection, kind, name)}, so a bare name is ambiguous — on the OIM
     * install-kit corpus roughly 945 entity names exist under two to five kinds ({@code Person} is
     * a {@code table}, an {@code entity_model}, {@code ui_forms}, {@code object_methods} AND a
     * {@code notification}). LLM-extracted relationships carry no endpoint kind at all, so
     * {@code resolveEndpointId} has to refuse a name that several kinds answer to, exactly like the
     * custom (jsonl) path does.
     *
     * <p>
     * The existing coverage only ever feeds it an UNDECLARED endpoint
     * ({@code relationshipEndpointThatIsNotADeclaredEntity...}), which fails on the null-map
     * branch; no fixture anywhere declares two entities sharing a name under different kinds, so
     * the {@code candidates.size() == 1} half of the guard has no witness. Relaxing it to "take the
     * first candidate" — which reads like an obvious simplification, since the map is non-empty and
     * a UUID comes back — attaches every homonym edge to an ARBITRARY wrong node. Nothing fails:
     * the job reports a normal edge count, the graph is fully populated, and
     * {@code get_graph_neighbors} confidently returns a {@code notification}'s neighbours for a
     * question about a {@code table}. Because ids are minted per identity key and never revisited,
     * the mis-attachment is permanent until the collection is re-ingested.
     *
     * <p>
     * The loss must also stay VISIBLE: the LLM path deliberately does not throw (one model-invented
     * name must not kill a whole kg-extract job), so {@code skippedEdges} is the only channel
     * through which an operator can see that an edge was dropped. Asserting the count as well as
     * the emptiness stops a "fix" that silently swallows the ambiguity.
     */
    @Test
    public void anEdgeEndpointNamingTwoKindsIsAmbiguousAndIsDroppedRatherThanAttachedToOne() {
        JobCounts counts = runKg(new KgExtractionResult(
            List.of(new ExtractedEntity("Person", "table", "the Person table"),
                new ExtractedEntity("Person", "notification", "the Person notification"),
                new ExtractedEntity("Org", "table", "the Org table")),
            List.of(new ExtractedRelationship("Person", "fk_to", "Org", "which Person?")), 0));

        // Both homonyms are legitimate nodes: identity is (kind, name), so nothing is merged.
        assertThat(capturedEntitySpecs()).extracting(EntityUpsert::name)
            .containsExactlyInAnyOrder("Person", "Person", "Org");
        assertThat(counts.entities()).isEqualTo(3);

        // The edge names a subject that two kinds answer to, so it cannot be identified. Picking
        // either candidate would be a coin flip written permanently into the graph.
        assertThat(capturedRelationshipSpecs())
            .as("an edge whose endpoint is ambiguous across kinds must be dropped, never attached"
                + " to an arbitrary homonym")
            .isEmpty();
        assertThat(counts.edges()).isZero();
        assertThat(counts.skippedEdges())
            .as("the LLM path does not throw, so the job counts are the only place the loss is"
                + " visible to an operator")
            .isEqualTo(1);
    }

    /**
     * Two per-chunk bookkeeping rules of the kg-extract job, neither of which has a witness.
     *
     * <p>
     * <b>An out-of-range chunk-status index is warned and skipped.</b> The extractor's statuses are
     * indexes into the chunk list the service loaded, and the two are produced by different code at
     * different times — a chunk deleted or re-chunked between the load and the write, or any future
     * extractor that renumbers, hands back an index the list no longer has. Without the guard
     * {@code orderedChunks.get(i)} throws {@link IndexOutOfBoundsException} and fails a job whose
     * ENTIRE extraction has already succeeded: {@code persistGraph} ran first, so the graph is
     * committed and the LLM spend is already incurred, and the operator is shown a failed job with
     * an IndexOutOfBounds stack trace and no way to tell that the corpus is in fact fine.
     *
     * <p>
     * <b>The document must be marked kg-processed.</b> {@code markKgProcessed} writes
     * {@code kg_processed_at}/{@code kg_entities}/{@code kg_edges} into {@code documents.metadata}
     * — the only record anywhere that a document was graphed, and what decides whether a re-extract
     * is needed. Deleting that one line changes nothing observable in the job: the counts still
     * come back, the entities and edges are still in the graph. The document merely looks forever
     * un-extracted, so it is re-extracted at full LLM cost every time anyone sweeps the collection.
     *
     * <p>
     * Every other test in this class goes through {@code runKg}, whose extraction carries NO chunk
     * statuses at all, so the guard is unreached — and none of them verifies
     * {@code markKgProcessed}.
     */
    @SuppressWarnings("unchecked")
    @Test
    public void anOutOfRangeChunkStatusIsSkippedAndTheDocumentIsStillMarkedKgProcessed() {
        UUID chunk0 = UUID.randomUUID();
        UUID chunk1 = UUID.randomUUID();
        when(chunkRepository.findChunksByDocumentId(eq(documentId), isNull())).thenReturn(List.of(
            new ChunkRow(chunk0, documentId, "first chunk", List.of(), null, null, 0, null, null,
                null, COLLECTION, null, 0.0),
            new ChunkRow(chunk1, documentId, "second chunk", List.of(), null, null, 1, null, null,
                null, COLLECTION, null, 0.0)));
        when(kgExtractor.extract(anyList(), any(), any(), any())).thenReturn(new KgExtractionResult(
            List.of(new ExtractedEntity("Person", "table", "the table")), List.of(), 0,
            List.of(new KgExtractionResult.ChunkStatus(0, true, "kg-model"),
                new KgExtractionResult.ChunkStatus(7, true, "kg-model"),
                new KgExtractionResult.ChunkStatus(-1, true, "kg-model"),
                new KgExtractionResult.ChunkStatus(1, false, "kg-model"))));

        JobCounts counts = service.processDocumentKg(kgJob(), mock(JobContext.class));

        ArgumentCaptor<List<ChunkKgStatus>> written = ArgumentCaptor.forClass(List.class);
        verify(documentRepository).markChunkKgStatuses(written.capture());
        assertThat(written.getValue())
            .as("a status index outside the chunk range must be dropped, never dereferenced")
            .extracting(ChunkKgStatus::chunkId).containsExactly(chunk0, chunk1);
        assertThat(written.getValue()).extracting(ChunkKgStatus::ok).containsExactly(true, false);

        verify(documentRepository).markKgProcessed(eq(documentId), anyString(), eq(1), eq(0));
        assertThat(counts.entities()).isEqualTo(1);
    }

    private JobCounts runKg(KgExtractionResult extraction) {
        when(kgExtractor.extract(anyList(), any(), any(), any())).thenReturn(extraction);
        return service.processDocumentKg(kgJob(), mock(JobContext.class));
    }

    @SuppressWarnings("unchecked")
    private List<EntityUpsert> capturedEntitySpecs() {
        ArgumentCaptor<List<EntityUpsert>> captor = ArgumentCaptor.forClass(List.class);
        verify(kgRepository).upsertEntitiesBatch(eq(COLLECTION), eq(TAGS), captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<RelationshipUpsert> capturedRelationshipSpecs() {
        ArgumentCaptor<List<RelationshipUpsert>> captor = ArgumentCaptor.forClass(List.class);
        verify(kgRepository).insertRelationshipsBatch(eq(COLLECTION), eq(TAGS), captor.capture());
        return captor.getValue();
    }

    @Test
    public void relationshipEndpointThatIsNotADeclaredEntityCreatesNoKindLessNodeAndIsSkipped() {
        runKg(new KgExtractionResult(List.of(new ExtractedEntity("Person", "table", "the table")),
            List.of(new ExtractedRelationship("Person", "fk_to", "Ghost", "dangling endpoint")),
            0));

        assertThat(capturedEntitySpecs()).extracting(EntityUpsert::name).containsExactly("Person");
        assertThat(capturedEntitySpecs()).allSatisfy(spec -> assertThat(spec.kind()).isNotBlank());
        // The edge cannot be identified without both endpoints, so it is dropped, not invented.
        assertThat(capturedRelationshipSpecs()).isEmpty();
    }

    @Test
    public void relationshipBetweenDeclaredEntitiesKeepsBothResolvedEndpointIds() {
        runKg(new KgExtractionResult(
            List.of(new ExtractedEntity("Person", "table", "the table"),
                new ExtractedEntity("Org", "table", "the org table")),
            List.of(new ExtractedRelationship("Person", "fk_to", "Org", "fk")), 0));

        List<RelationshipUpsert> rels = capturedRelationshipSpecs();
        assertThat(rels).hasSize(1);
        assertThat(rels.get(0).subject()).isEqualTo("Person");
        assertThat(rels.get(0).object()).isEqualTo("Org");
        assertThat(rels.get(0).subjectId()).isNotNull();
        assertThat(rels.get(0).objectId()).isNotNull();
        assertThat(rels.get(0).subjectId()).isNotEqualTo(rels.get(0).objectId());
    }

    @Test
    public void extractedEntityWithoutAKindIsNotPersistedAndItsEdgesAreSkipped() {
        runKg(new KgExtractionResult(
            List.of(new ExtractedEntity("Person", "table", "the table"),
                new ExtractedEntity("Mystery", null, "the model gave no kind")),
            List.of(new ExtractedRelationship("Person", "fk_to", "Mystery", "fk")), 0));

        assertThat(capturedEntitySpecs()).extracting(EntityUpsert::name).containsExactly("Person");
        assertThat(capturedRelationshipSpecs()).isEmpty();
    }

    /**
     * Unlike the custom (jsonl) path — which throws, because its corpus is a deterministic export
     * whose entities must each map to a document — an unpredictable model output may not fail a
     * whole kg-extract job. The loss must still be visible, so it is reported in the job's counts
     * instead of only in a WARN.
     */
    @Test
    public void skippedEntitiesAndEdgesAreReportedInTheJobCountsRatherThanOnlyLogged() {
        JobCounts counts = runKg(new KgExtractionResult(
            List.of(new ExtractedEntity("Person", "table", "the table"),
                new ExtractedEntity("Mystery", null, "the model gave no kind"),
                new ExtractedEntity("Riddle", "  ", "blank kind counts as none")),
            List.of(new ExtractedRelationship("Person", "fk_to", "Mystery", "fk"),
                new ExtractedRelationship("Person", "fk_to", "Ghost", "dangling")),
            0));

        assertThat(counts.entities()).isEqualTo(1);
        assertThat(counts.edges()).isZero();
        assertThat(counts.skippedEntities()).isEqualTo(2);
        assertThat(counts.skippedEdges()).isEqualTo(2);
    }

    /** A clean extraction reports no loss. */
    @Test
    public void aFullyResolvedExtractionReportsZeroSkippedCounts() {
        JobCounts counts = runKg(new KgExtractionResult(
            List.of(new ExtractedEntity("Person", "table", "the table"),
                new ExtractedEntity("Org", "table", "the org table")),
            List.of(new ExtractedRelationship("Person", "fk_to", "Org", "fk")), 0));

        assertThat(counts.skippedEntities()).isZero();
        assertThat(counts.skippedEdges()).isZero();
    }

    /**
     * Consolidation runs ONCE PER TAG on the job, and only falls back to a tag-blind
     * {@code consolidate(collection, null)} when the job carries no tag at all.
     *
     * <p>
     * Graph identity is {@code (collection, kind, lower(name), tag set)} — the tag is part of it —
     * because one collection deliberately holds two product versions separated only by tag.
     * {@code KgConsolidator}'s duplicate-detection query is scoped by
     * {@code :tag = ANY(e.tags) OR CAST(:tag AS text) IS NULL}, so the tag argument is a row
     * FILTER: passing one tag for a two-tag job simply leaves the other version's rows
     * unconsolidated, and its case-variant duplicates ({@code ADSAccount} vs {@code AdsAccount})
     * survive as separate nodes with their edges split between them. Nothing reports it — the job
     * completes, {@code markKgProcessed} records the counts, and the second version's graph is
     * quietly worse than the first's in a way only a graph query notices.
     *
     * <p>
     * The null branch is the dangerous direction and is asserted negatively for that reason:
     * {@code null} switches the row filter OFF entirely, so it consolidates across EVERY tag scope
     * in the collection at once. That is safe only when there is no tag scoping to violate (the
     * untagged job), and catastrophic otherwise — the grouping keeps
     * {@code kg_canonical_tags(e.tags)} in its GROUP BY, but a caller that passes null for a tagged
     * job is asking to merge scopes and hard-DELETE one row per group, cascading its edges away
     * through {@code fk_relationships_subject/object}. "Just consolidate once with the first tag"
     * and "just consolidate once with null" are both natural-looking simplifications of the loop.
     *
     * <p>
     * Every other test in this class asserts on entity/relationship persistence and passes the
     * single-tag {@code TAGS}, for which one call and a loop are indistinguishable.
     */
    @Test
    public void aTwoTagKgExtractConsolidatesEachTagScopeSeparately() {
        when(kgExtractor.extract(anyList(), any(), any(), any())).thenReturn(new KgExtractionResult(
            List.of(new ExtractedEntity("Person", "table", "the table")), List.of(), 0));

        service.processDocumentKg(kgJobWithTags(List.of("9.3", "10.0")), mock(JobContext.class));

        verify(kgConsolidator).consolidate(COLLECTION, "9.3");
        verify(kgConsolidator).consolidate(COLLECTION, "10.0");
        verify(kgConsolidator, never()).consolidate(anyString(), isNull());

        // The untagged job is the ONLY case that may switch the row filter off.
        service.processDocumentKg(kgJobWithTags(List.of()), mock(JobContext.class));

        verify(kgConsolidator).consolidate(COLLECTION, null);
    }

    /**
     * A hierarchical section that exceeds {@link MarkdownTree#CHUNK_BODY_MAX_CHARS} must be stored
     * WHOLE, as one chunk, keeping the author's own heading as its title.
     *
     * <p>
     * The two ingest paths chunk differently on purpose and nothing in the code says so out loud.
     * The standard path calls {@code MarkdownTree.buildOrderedSections} — filter →
     * drop-empty-placeholders → {@code splitOversizedSection}; the hierarchical path calls only the
     * first two, because whole sections ARE its product: {@code get_toc} lists them and
     * {@code get_section} hands one back to an agent as the authoritative text of that heading.
     * Unifying the two into the one helper is the obvious tidy-up — the hierarchical path reads
     * exactly like the standard path with a step missing — and it silently fragments every chapter
     * over 3,500 characters into {@code "… (part 1/7)"} chunks. Nothing errors, the reported chunk
     * count merely goes up, and every later answer is built from a fragment of the section that was
     * asked for.
     *
     * <p>
     * Nothing currently observes this. {@code HierarchicalDocumentJobHandlerIT}'s fixture has three
     * tiny sections, so split and no-split are indistinguishable there, and every unit test in this
     * class either drives the standard path or asserts that ingest throws.
     *
     * <p>
     * The fixture also carries the frontmatter shape that broke the custom path — a
     * {@code display_name} opening with the reserved YAML indicator {@code %} — because
     * {@code MarkdownTree} must SWALLOW that to an empty map, deliberately unlike
     * {@code CustomIngestService}, which throws. Making the shared parser strict would fail every
     * standard/hierarchical/Confluence ingest of such a document (they are common in the OIM
     * corpus); here it has to parse to nothing and leave the body intact.
     */
    @SuppressWarnings("unchecked")
    @Test
    public void aHierarchicalSectionOverTheChunkCapIsStoredWholeInsteadOfSplitIntoParts()
        throws Exception {
        String oversized =
            "Body sentence for the configuration chapter that must exceed the cap. ".repeat(200);
        Path tmp = Files.createTempDirectory("hierarchical-unit");
        Path md = tmp.resolve("manual.md");
        Files.writeString(md, """
            ---
            kind: hierarchical
            display_name: %Globals.QIM_ProductNameShort% administration guide
            ---
            # Administration Guide

            ## Configuring the Connector

            """ + oversized + "\n");
        when(uploadPathGuard.resolve(md.toString())).thenReturn(md);

        // Hierarchical summarizes unconditionally, so the prompt setting is not optional here.
        IngestionJob job = new IngestionJob(null, "hierarchical", md.toString(), TAGS,
            UUID.randomUUID(), COLLECTION, JobStatus.RUNNING, null, 0, 0, null, null,
            OffsetDateTime.now(), null, null,
            Map.<String, Object>of(IngestPrompts.SETTING_SUMMARIZE_PROMPT, SUMMARIZE_PROMPT));
        JobCounts counts = service.ingestHierarchical(job, mock(JobContext.class));

        ArgumentCaptor<List<ChunkInsert>> stored = ArgumentCaptor.forClass(List.class);
        verify(documentRepository).insertChunks(any(), anyString(), anyList(), anyString(),
            anyString(), stored.capture());
        List<ChunkInsert> inserts = stored.getValue();

        assertThat(inserts).as("an oversized hierarchical section must not be fragmented")
            .hasSize(1);
        assertThat(counts.chunks()).isEqualTo(1);
        assertThat(inserts.get(0).text().length())
            .as("this path deliberately allows a chunk past the standard-path cap")
            .isGreaterThan(MarkdownTree.CHUNK_BODY_MAX_CHARS);
        assertThat(inserts.get(0).heading()).isEqualTo("Configuring the Connector");
        assertThat(inserts.get(0).text()).doesNotContain("(part 1/");
    }

    /**
     * The standard path chunks by splitting an oversized section into {@code (part n/m)} pieces,
     * but the summarizer must be handed the sections as they were BEFORE that split — the same list
     * the hierarchical path summarizes.
     *
     * <p>
     * This is a correctness rule, not an optimisation, and it fails in two silent ways at once.
     * {@code GeneralSummarizer} keys its cache on {@code sha256(content)}, and
     * {@code SectionSummarizer.processNode} gives a PARENT node a roll-up built from
     * {@code "> " + child.path + child.summary} — so a {@code (part n/m)} in one child title
     * changes the content hash of every ANCESTOR too, turning a run that should be free (every
     * section body is already in {@code summary_cache} from the hierarchical ingest) into a
     * full-price LLM run over the whole spine. Worse, the rows it then writes carry
     * {@code (part n/m)} inside {@code section_summaries.heading_path}, while {@code TocService}
     * builds its lookup keys through {@code HierarchyUtils.stripPart} — so every summary paid for
     * is written at a path nothing ever reads, and {@code get_toc} stays exactly as empty as
     * before.
     *
     * <p>
     * Nothing else can catch this: the job reports a normal count, the rows exist, and the only
     * visible symptom is a bill plus a TOC that still has no summaries.
     */
    @Test
    public void theStandardIngestSummarizesTheSectionsAsTheyWereBeforeTheOversizedSplit()
        throws Exception {
        String oversized =
            "Body sentence for the configuration chapter that must exceed the cap. ".repeat(200);
        Path tmp = Files.createTempDirectory("standard-summaries-unit");
        Path md = tmp.resolve("manual.md");
        Files.writeString(md, """
            # Administration Guide

            ## Configuring the Connector

            """ + oversized + "\n");
        when(uploadPathGuard.resolve(md.toString())).thenReturn(md);
        when(embeddingService.embedBatch(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> new float[] {0.1f}).toList();
        });
        when(documentRepository.upsertManualDocument(anyString(), anyList(), anyString(),
            anyString(), any())).thenReturn(documentId);

        IngestionJob job = new IngestionJob(null, "standard", md.toString(), TAGS,
            UUID.randomUUID(), COLLECTION, JobStatus.RUNNING, null, 0, 0, null, null,
            OffsetDateTime.now(), null, null, Map.<String, Object>of("buildSectionSummaries", true,
                IngestPrompts.SETTING_SUMMARIZE_PROMPT, SUMMARIZE_PROMPT));
        service.ingest(job, mock(JobContext.class));

        ArgumentCaptor<List<Section>> summarized = ArgumentCaptor.forClass(List.class);
        verify(sectionSummarizer).generateSummaries(eq(documentId), summarized.capture(), any(),
            any(), any());

        assertThat(summarized.getValue())
            .as("a '(part n/m)' title poisons every ancestor's cache key AND writes the summary at"
                + " a heading_path TocService will never look up")
            .allSatisfy(s -> assertThat(s.title()).doesNotContain("(part"));

        // The chunking itself must be unaffected: the split still happens for the stored chunks.
        ArgumentCaptor<List<ChunkInsert>> stored = ArgumentCaptor.forClass(List.class);
        verify(documentRepository).insertChunks(any(), anyString(), anyList(), anyString(),
            anyString(), stored.capture());
        assertThat(stored.getValue()).as("the standard path must still split oversized sections")
            .hasSizeGreaterThan(summarized.getValue().size());
        assertThat(stored.getValue()).anySatisfy(c -> assertThat(c.heading()).contains("(part 1/"));
    }

    /**
     * Summarisation costs an LLM call per uncached section, so the standard path must not do it
     * unless the operator asked. An absent setting is the ordinary case and must mean OFF.
     */
    @Test
    public void theStandardIngestDoesNotSummarizeUnlessTheSettingAsksItTo() throws Exception {
        Path tmp = Files.createTempDirectory("standard-summaries-off-unit");
        Path md = tmp.resolve("manual.md");
        Files.writeString(md, """
            # Administration Guide

            ## Configuring the Connector

            A short body.
            """);
        when(uploadPathGuard.resolve(md.toString())).thenReturn(md);
        when(embeddingService.embedBatch(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> new float[] {0.1f}).toList();
        });
        when(documentRepository.upsertManualDocument(anyString(), anyList(), anyString(),
            anyString(), any())).thenReturn(documentId);

        IngestionJob job = new IngestionJob(null, "standard", md.toString(), TAGS,
            UUID.randomUUID(), COLLECTION, JobStatus.RUNNING, null, 0, 0, null, null,
            OffsetDateTime.now(), null, null, Map.<String, Object>of());
        service.ingest(job, mock(JobContext.class));

        verify(sectionSummarizer, never()).generateSummaries(any(), anyList(), any(), any(), any());
    }


    /**
     * Zip mode swallows EVERY per-file exception, so a zip in which nothing succeeds completes
     * green: no throw, no error on the job, and a normal-looking {@code JobCounts} of zero.
     *
     * <p>
     * This is deliberate — one unparseable file out of two thousand must not discard the whole kit
     * — but it is also the one place in this section where loss is neither counted nor surfaced,
     * and that half has never been executed by any test: no fixture anywhere puts a failing file
     * inside a standard or hierarchical zip. The consequence is the failure mode the rest of §8
     * exists to prevent: an operator uploads a kit, the job reports success, and the corpus is
     * empty. The only trace is a {@code log.error} per file in the container log, which nobody
     * reads when the job says it worked, and {@code docs=0} — a number you have to already suspect
     * to go and check.
     *
     * <p>
     * Pinning it makes the silence explicit rather than accidental: the counts are the ONLY
     * channel, so if a future change starts failing the job (or starts counting the losses) that is
     * a deliberate contract change and this test is where it gets discussed. The negative
     * assertions matter as much as the counts — nothing may be persisted and no embedding may be
     * paid for on a file that produced no sections.
     */
    @Test
    public void aZipInWhichEveryFileFailsCompletesGreenWithZeroDocuments() throws Exception {
        Path tmp = Files.createTempDirectory("zip-allfail-unit");
        Path zip = tmp.resolve("kit.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (String name : new String[] {"docs/a.md", "docs/b.md"}) {
                zos.putNextEntry(new ZipEntry(name));
                // No ATX heading anywhere => "No chunks produced from document", per file.
                zos.write("prose with no heading at all\n".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        when(uploadPathGuard.resolve(zip.toString())).thenReturn(zip);

        JobCounts counts = service.ingest(standardJob(zip.toString()), mock(JobContext.class));

        assertThat(counts.docs())
            .as("an all-failing zip reports success with zero documents — the counts are the only"
                + " signal an operator ever gets")
            .isZero();
        assertThat(counts.chunks()).isZero();
        verify(documentRepository, never()).insertChunks(any(), anyString(), anyList(), anyString(),
            anyString(), anyList());
        verify(embeddingService, never()).embedBatch(anyList());
    }

    /** A standard (non-KG) ingest job pointing at {@code sourceRef}. */
    private IngestionJob standardJob(String sourceRef) {
        return new IngestionJob(null, "standard", sourceRef, TAGS, COLLECTION, JobStatus.RUNNING,
            null, 0, 0, null, null, OffsetDateTime.now(), null, null);
    }

    /**
     * The standard pipeline's own zip extraction must reject a zip-slip entry.
     *
     * <p>
     * {@code CustomIngestService} has this pinned; this path did not, and the two unzip
     * implementations are separate code. An archive is attacker-influenced — any caller of the
     * ingest API supplies one — so an entry that resolves outside the extraction directory would
     * let an upload write anywhere the process can reach.
     *
     * <p>
     * The vector is an ABSOLUTE entry name, not {@code ../}. A relative escape never reaches the
     * zip-slip check: the dot-path filter one line above rejects any name containing {@code /.} or
     * starting with {@code .} first. A test using {@code ../escaped.md} therefore passes whether or
     * not the zip-slip guard exists, which is worse than no test.
     */
    @Test
    public void aZipEntryEscapingTheExtractionDirectoryFailsTheStandardIngest() throws Exception {
        Path tmp = Files.createTempDirectory("zipslip-unit");
        Path zip = tmp.resolve("evil.zip");
        Path escapeTarget = tmp.resolve("pwned.md");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("docs/ok.md"));
            zos.write("# Fine\n\ncontent\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry(escapeTarget.toAbsolutePath().toString()));
            zos.write("owned".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        when(uploadPathGuard.resolve(zip.toString())).thenReturn(zip);

        IngestionJob job = standardJob(zip.toString());
        assertThatThrownBy(() -> service.ingest(job, mock(JobContext.class)))
            .hasStackTraceContaining("zip-slip");
        assertThat(Files.exists(escapeTarget))
            .as("nothing may be written outside the extraction directory").isFalse();
    }

    /**
     * The text stored on a chunk and the text handed to the embedder MUST be the same string.
     *
     * <p>
     * {@code Section.toChunkText()} prepends the heading path ({@code "> Section path: A > B"}) and
     * the ATX heading to the section body, and that enriched form is what {@code persistDocument}
     * writes into {@code chunks.text} — which is also exactly what the BM25 lane tokenizes. If the
     * embedding is computed from anything else (the bare body, a summary, a trimmed variant) the
     * two retrieval lanes score different content for the same row: the vector describes one
     * string, the keyword index another, and RRF then fuses two rankings that no longer refer to
     * the same text. Nothing fails and nothing warns. Counts stay normal and the only production
     * guard — {@code embeddings.size() != sections.size()} — passes happily, because a per-section
     * transform preserves the count. The corpus simply retrieves subtly wrong passages forever.
     *
     * <p>
     * The alignment is expressed twice with nothing tying the two together: once as
     * {@code chunkTexts} for the embed call, once as {@code s.toChunkText()} inside
     * {@code persistDocument}. An edit to either side is invisible to every existing test in this
     * class — they assert on graph persistence, or assert that {@code ingest} THROWS, and none of
     * them reaches the standard chunk-and-persist path. The fixture below deliberately nests a
     * section so the heading-path prefix (the first thing a body-only regression drops) appears in
     * at least one chunk.
     */
    @SuppressWarnings("unchecked")
    @Test
    public void everyStandardChunkIsEmbeddedWithExactlyTheTextThatIsStored() throws Exception {
        Path tmp = Files.createTempDirectory("chunktext-unit");
        Path md = tmp.resolve("aligned.md");
        Files.writeString(md, """
            # Manual Title

            ## Alpha

            alpha body text

            ## Beta

            beta body text

            ### Beta child

            beta child body
            """);
        when(uploadPathGuard.resolve(md.toString())).thenReturn(md);
        when(embeddingService.embedBatch(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> new float[] {1.0f}).toList();
        });

        service.ingest(standardJob(md.toString()), mock(JobContext.class));

        ArgumentCaptor<List<String>> embedded = ArgumentCaptor.forClass(List.class);
        verify(embeddingService).embedBatch(embedded.capture());
        ArgumentCaptor<List<ChunkInsert>> stored = ArgumentCaptor.forClass(List.class);
        verify(documentRepository).insertChunks(any(), anyString(), anyList(), anyString(),
            anyString(), stored.capture());

        List<String> storedTexts = stored.getValue().stream().map(ChunkInsert::text).toList();
        assertThat(storedTexts).hasSize(3);
        assertThat(embedded.getValue())
            .as("the embedded text must be the stored text, element for element and in order")
            .containsExactlyElementsOf(storedTexts);
        // The nested section's stored text carries the heading-path prefix; embedding the bare
        // body would silently drop it while every count stayed identical.
        assertThat(storedTexts.get(2)).contains("> Section path: Beta");
    }

    /**
     * A document that produces no sections MUST fail the job rather than complete with zero chunks.
     *
     * <p>
     * Markdown with no ATX heading yields an empty section list, and an empty section list means
     * the document is unsearchable. Completing would report a normal-looking job — one document,
     * zero chunks — and leave content in the corpus that no query can ever return, with the
     * operator's only signal being a count they would have to know to check. Failing names the
     * file.
     */
    @Test
    public void aDocumentWithNoHeadingsFailsInsteadOfCompletingWithZeroChunks() throws Exception {
        Path tmp = Files.createTempDirectory("nochunks-unit");
        Path md = tmp.resolve("headless.md");
        Files.writeString(md, "just prose, no ATX heading anywhere\n");
        when(uploadPathGuard.resolve(md.toString())).thenReturn(md);

        IngestionJob job = standardJob(md.toString());
        assertThatThrownBy(() -> service.ingest(job, mock(JobContext.class)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("No chunks produced")
            .hasMessageContaining("headless.md");
        verify(documentRepository, never()).insertChunks(any(), anyString(), anyList(), anyString(),
            anyString(), anyList());
    }
}
