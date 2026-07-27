package de.palsoftware.yvoke.ingest.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.document.core.model.ChunkInsert;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import de.palsoftware.yvoke.kg.core.repository.KgWriteRepository;
import de.palsoftware.yvoke.ingest.core.UploadPathGuard;
import de.palsoftware.yvoke.rag.prompt.SystemPromptService;
import de.palsoftware.yvoke.rag.retrieval.EmbeddingService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

class CustomIngestServiceTest {

    private final EmbeddingService embeddingService = mock(EmbeddingService.class);

    private CustomIngestService service() {
        return new CustomIngestService(embeddingService, mock(DocumentRepository.class),
            mock(GeneralSummarizer.class), mock(KgWriteRepository.class), mock(JdbcClient.class),
            mock(PlatformTransactionManager.class), mock(UploadPathGuard.class), 4,
            mock(SystemPromptService.class));
    }

    /** Kind-aware document map covering every entity used below, so the doc invariant is met. */
    private static Map<String, UUID> docIds(String... keys) {
        Map<String, UUID> map = new HashMap<>();
        for (String key : keys) {
            map.put(key, UUID.randomUUID());
        }
        return map;
    }

    @Test
    void buildEntitySpecsKeepsDescriptionsButDoesNotEmbedThem() {
        // entities.embedding is write-only: of the 31 queries that read the entities table,
        // none uses the vector — search_graph_entities ranks by trigram similarity(e.name, …)
        // and the only <=> in the codebase is over chunks. Embedding every description cost a
        // model round-trip per entity (~10.6k per version per ingest of the OIM corpus) for a
        // column nothing consults. The description itself is kept: it is what the MCP tools
        // render, and it was NULL for 100% of rows before the graph carried descriptions.
        List<Map<String, Object>> entities =
            List.of(Map.of("type", "table", "name", "a", "description", "d1"),
                Map.of("type", "table", "name", "b"), // no description
                Map.of("type", "table", "name", "c", "description", "d2"));

        List<KgWriteRepository.EntityUpsert> specs =
            service().buildEntitySpecs(entities, docIds("table:a", "table:b", "table:c"));

        verifyNoInteractions(embeddingService);
        assertThat(specs).hasSize(3);
        assertThat(specs).extracting(KgWriteRepository.EntityUpsert::description)
            .containsExactly("d1", null, "d2");
        assertThat(specs).allSatisfy(s -> assertThat(s.embedding()).isNull());
    }

    @Test
    void buildEntitySpecsMakesNoEmbeddingCallWhenNoDescriptions() {
        List<Map<String, Object>> entities =
            List.of(Map.of("type", "table", "name", "a"), Map.of("type", "table", "name", "b"));

        List<KgWriteRepository.EntityUpsert> specs =
            service().buildEntitySpecs(entities, docIds("table:a", "table:b"));

        verify(embeddingService, times(0)).embedBatch(anyList());
        assertThat(specs).hasSize(2);
        assertThat(specs).allSatisfy(s -> assertThat(s.embedding()).isNull());
    }

    @Test
    void buildEntitySpecsStampsDocumentIdFromMap() {
        UUID docId = UUID.randomUUID();
        List<Map<String, Object>> entities = List.of(Map.of("type", "table", "name", "a"));

        List<KgWriteRepository.EntityUpsert> specs =
            service().buildEntitySpecs(entities, Map.of("table:a", docId));

        assertThat(specs.get(0).metadata()).containsEntry("document_id", docId.toString());
    }

    // --- entity → document invariant (the AERole/%-frontmatter defect) ---

    /**
     * The defect this pins: three Install-Kit table documents whose frontmatter failed to parse
     * degraded to {@code kind='other'}, so their {@code table} entities matched no document and
     * were persisted with no {@code document_id} — twice, unnoticed. Every entity must have a
     * document.
     */
    @Test
    void buildEntitySpecsFailsNamingEveryEntityThatResolvedToNoDocument() {
        CustomIngestService service = service();
        List<Map<String, Object>> entities =
            List.of(Map.of("type", "table", "name", "AERole", "description", "application roles"),
                Map.of("type", "table", "name", "QBMFileRevision"),
                Map.of("type", "table", "name", "Present"));
        Map<String, UUID> docIdMap = docIds("table:present");

        assertThatThrownBy(() -> service.buildEntitySpecs(entities, docIdMap))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("2 graph entit(ies) resolved to no document")
            .hasMessageContaining("table:AERole").hasMessageContaining("table:QBMFileRevision")
            .hasMessageNotContaining("table:Present");

        // It fails before spending an embedding round-trip on a graph that will not be persisted.
        verify(embeddingService, times(0)).embedBatch(anyList());
    }

    /** A broken export yields thousands of offenders; the message must stay readable. */
    @Test
    void buildEntitySpecsFailureMessageCapsTheOffenderListAtTwenty() {
        CustomIngestService service = service();
        List<Map<String, Object>> entities = new ArrayList<>();
        for (int i = 0; i < 37; i++) {
            entities.add(Map.of("type", "table", "name", String.format("T%02d", i)));
        }

        assertThatThrownBy(() -> service.buildEntitySpecs(entities, Map.of()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("37 graph entit(ies) resolved to no document")
            .hasMessageContaining("(20 of 37 shown)").hasMessageContaining("table:T00")
            .hasMessageNotContaining("table:T36");
    }

    /** A same-named entity of another kind is NOT that entity's document. */
    @Test
    void buildEntitySpecsDoesNotAcceptADocumentOfADifferentKind() {
        CustomIngestService service = service();
        List<Map<String, Object>> entities = List.of(Map.of("type", "table", "name", "Person"));
        Map<String, UUID> docIdMap = docIds("notification:person");

        assertThatThrownBy(() -> service.buildEntitySpecs(entities, docIdMap))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("table:Person");
    }

    // --- isGraphEnabled ---

    @Test
    void isGraphEnabledDefaultsTrueAndParsesBooleanOrString() {
        assertThat(CustomIngestService.isGraphEnabled(Map.of())).isTrue(); // default
        assertThat(CustomIngestService.isGraphEnabled(null)).isTrue(); // null settings
        assertThat(CustomIngestService.isGraphEnabled(Map.of("enableGraph", false))).isFalse();
        assertThat(CustomIngestService.isGraphEnabled(Map.of("enableGraph", "false"))).isFalse();
        assertThat(CustomIngestService.isGraphEnabled(Map.of("enableGraph", "true"))).isTrue();
    }

    // --- buildRelationshipSpecs ---

    @Test
    void buildRelationshipSpecsResolvesKeyVariants() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        // Map is keyed "lower(kind):lower(name)" (the kind-aware upsert contract).
        Map<String, UUID> idByKey = Map.of("table:demo_table", id1, "table:other_table", id2);

        List<Map<String, Object>> relationships = List.of(
            // canonical keys + "type:name" prefixes, resolved case-insensitively
            Map.of("source", "table:DEMO_TABLE", "type", "references", "target",
                "table:OTHER_TABLE"),
            // alternate key spellings, no prefix -> resolved by unique name
            Map.of("subject_name", "DEMO_TABLE", "predicate", "links", "object_name",
                "OTHER_TABLE"));

        List<KgWriteRepository.RelationshipUpsert> specs =
            CustomIngestService.buildRelationshipSpecs(relationships, idByKey);

        assertThat(specs).hasSize(2);
        assertThat(specs.get(0).subject()).isEqualTo("DEMO_TABLE");
        assertThat(specs.get(0).predicate()).isEqualTo("references");
        assertThat(specs.get(0).object()).isEqualTo("OTHER_TABLE");
        assertThat(specs.get(0).subjectId()).isEqualTo(id1);
        assertThat(specs.get(0).objectId()).isEqualTo(id2);
        assertThat(specs.get(1).predicate()).isEqualTo("links");
    }

    /** An endpoint no entity carries used to be dropped without a trace. */
    @Test
    void buildRelationshipSpecsFailsOnAnUnknownEndpointNamingIt() {
        Map<String, UUID> idByKey = Map.of("table:other_table", UUID.randomUUID());
        List<Map<String, Object>> relationships = List.of(
            Map.of("source", "table:GHOST", "type", "references", "target", "table:OTHER_TABLE"),
            Map.of("source", "table:OTHER_TABLE", "type", "references", "target", "NOWHERE"));

        assertThatThrownBy(() -> CustomIngestService.buildRelationshipSpecs(relationships, idByKey))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("2 knowledge-graph edge(s) could not be resolved")
            .hasMessageContaining("Unknown endpoint").hasMessageContaining("table:GHOST")
            .hasMessageContaining("NOWHERE");
    }

    /** A record missing an endpoint or the predicate is the same silent edge loss. */
    @Test
    void buildRelationshipSpecsFailsOnAMalformedRecord() {
        Map<String, UUID> idByKey =
            Map.of("table:demo_table", UUID.randomUUID(), "table:other_table", UUID.randomUUID());
        List<Map<String, Object>> relationships =
            List.of(Map.of("source", "table:DEMO_TABLE", "target", "table:OTHER_TABLE"));

        assertThatThrownBy(() -> CustomIngestService.buildRelationshipSpecs(relationships, idByKey))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Malformed edge")
            .hasMessageContaining("table:DEMO_TABLE");
    }

    @Test
    void buildRelationshipSpecsUsesKindPrefixToPickTheRightHomonymEndpoint() {
        // Same name "ADS" exists as both a module and a connector; the "kind:" prefix must select
        // the correct endpoint rather than collapsing the edge into a module→module self-loop.
        UUID moduleAds = UUID.randomUUID();
        UUID connectorAds = UUID.randomUUID();
        Map<String, UUID> idByKey = Map.of("module:ads", moduleAds, "connector:ads", connectorAds);

        List<Map<String, Object>> relationships = List
            .of(Map.of("source", "module:ADS", "type", "HAS_CONNECTOR", "target", "connector:ADS"));

        List<KgWriteRepository.RelationshipUpsert> specs =
            CustomIngestService.buildRelationshipSpecs(relationships, idByKey);

        assertThat(specs).hasSize(1);
        assertThat(specs.get(0).subject()).isEqualTo("ADS");
        assertThat(specs.get(0).object()).isEqualTo("ADS");
        assertThat(specs.get(0).subjectId()).isEqualTo(moduleAds);
        assertThat(specs.get(0).objectId()).isEqualTo(connectorAds);
        // Not a degenerate self-loop: the two endpoints resolve to distinct ids.
        assertThat(specs.get(0).subjectId()).isNotEqualTo(specs.get(0).objectId());
    }

    /**
     * A bare name carried by several kinds cannot identify an endpoint; picking one arbitrarily
     * attaches the edge to the wrong homonym, so the job fails and names the colliding kinds.
     */
    @Test
    void buildRelationshipSpecsFailsOnAnUnprefixedEndpointAmbiguousAcrossKinds() {
        Map<String, UUID> idByKey = Map.of("module:ads", UUID.randomUUID(), "connector:ads",
            UUID.randomUUID(), "table:other", UUID.randomUUID());
        List<Map<String, Object>> relationships =
            List.of(Map.of("source", "ADS", "type", "references", "target", "table:OTHER"));

        assertThatThrownBy(() -> CustomIngestService.buildRelationshipSpecs(relationships, idByKey))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Ambiguous endpoint")
            .hasMessageContaining("ADS (kinds: connector, module)");
    }

    /** A 'kind:' prefix disambiguates the same name and keeps the edge. */
    @Test
    void buildRelationshipSpecsAcceptsAPrefixedEndpointWhoseBareNameWouldBeAmbiguous() {
        UUID moduleAds = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Map<String, UUID> idByKey = Map.of("module:ads", moduleAds, "connector:ads",
            UUID.randomUUID(), "table:other", other);

        List<KgWriteRepository.RelationshipUpsert> specs =
            CustomIngestService.buildRelationshipSpecs(
                List.of(
                    Map.of("source", "module:ADS", "type", "references", "target", "table:OTHER")),
                idByKey);

        assertThat(specs).hasSize(1);
        assertThat(specs.get(0).subjectId()).isEqualTo(moduleAds);
        assertThat(specs.get(0).objectId()).isEqualTo(other);
    }

    // --- toChunkInsert ---

    @Test
    void toChunkInsertParsesHeadingAndDepthElseFallsBackToDocTitle() {
        float[] emb = {0.5f};

        ChunkInsert withHeading =
            CustomIngestService.toChunkInsert("## Sub\n\nbody", emb, "Doc", 3);
        assertThat(withHeading.heading()).isEqualTo("Sub");
        assertThat(withHeading.depth()).isEqualTo(2);
        assertThat(withHeading.sortOrder()).isEqualTo(3);
        assertThat(withHeading.headingPath()).isEmpty();
        assertThat(withHeading.embedding()).isSameAs(emb);

        ChunkInsert noHeading = CustomIngestService.toChunkInsert("just body", emb, "Doc Title", 0);
        assertThat(noHeading.heading()).isEqualTo("Doc Title"); // fallback
        assertThat(noHeading.depth()).isEqualTo(1);
    }

    @Test
    void toChunkInsertExtractsSectionPathPrefix() {
        ChunkInsert ci = CustomIngestService.toChunkInsert(
            "> Section path: Alpha > Beta\n\n# Title\ncontent", new float[] {0f}, "Doc", 1);

        assertThat(ci.headingPath()).containsExactly("Alpha", "Beta");
        assertThat(ci.heading()).isEqualTo("Title");
        assertThat(ci.depth()).isEqualTo(1);
    }
}
