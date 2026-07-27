package de.palsoftware.yvoke.ingest.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import de.palsoftware.yvoke.rag.prompt.SystemPromptService;
import de.palsoftware.yvoke.rag.retrieval.EmbeddingService;
import de.palsoftware.yvoke.shared.jobengine.model.IngestionJob;
import de.palsoftware.yvoke.shared.jobengine.model.JobContext;
import de.palsoftware.yvoke.shared.jobengine.model.JobCounts;
import de.palsoftware.yvoke.shared.jobengine.model.JobStatus;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

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

    private final UUID documentId = UUID.randomUUID();

    @BeforeEach
    public void setUp() {
        kgRepository = mock(KgWriteRepository.class);
        kgExtractor = mock(DocumentKgExtractor.class);
        chunkRepository = mock(ChunkRepository.class);
        documentRepository = mock(DocumentRepository.class);

        service = new DocumentIngestService(mock(EmbeddingService.class), documentRepository,
            chunkRepository, kgRepository, kgExtractor, mock(KgConsolidator.class),
            mock(JdbcClient.class), mock(PlatformTransactionManager.class),
            mock(SectionSummarizer.class), mock(SystemPromptService.class),
            mock(UploadPathGuard.class));

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
        return new IngestionJob(null, "kg", documentId.toString(), TAGS, COLLECTION,
            JobStatus.RUNNING, null, 0, 0, null, null, OffsetDateTime.now(), null, null);
    }

    private JobCounts runKg(KgExtractionResult extraction) {
        when(kgExtractor.extract(anyList(), any(), any())).thenReturn(extraction);
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
}
