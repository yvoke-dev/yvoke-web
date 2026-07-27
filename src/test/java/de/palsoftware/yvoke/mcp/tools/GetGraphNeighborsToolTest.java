package de.palsoftware.yvoke.mcp.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.kg.core.model.KgEntityKindEdgeCount;
import de.palsoftware.yvoke.kg.core.model.KgNeighborEdges;
import de.palsoftware.yvoke.kg.core.repository.KgGraphReadRepository;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class GetGraphNeighborsToolTest {

    private KgGraphReadRepository kgRepository;
    private CollectionService collectionService;
    private GetGraphNeighborsTool getGraphNeighborsTool;

    @BeforeEach
    public void setUp() {
        kgRepository = mock(KgGraphReadRepository.class);
        collectionService = mock(CollectionService.class);
        getGraphNeighborsTool = new GetGraphNeighborsTool(kgRepository, collectionService);

        Collection dbCol = new Collection(UUID.randomUUID(), "OIM - DB", "DB Schema",
            List.of("9.3", "10.0"), null);
        Collection manualsCol = new Collection(UUID.randomUUID(), "OIM - Manuals - KG",
            "Manuals KG", List.of("9.3"), null);
        // A collection with NO tags. Since tag became mandatory for tag-scoped collections, this is
        // the only way to reach the untagged code paths — the kind picker's "(untagged)" rendering
        // and the tag picker itself — which still have to behave for an untagged corpus.
        Collection untaggedCol =
            new Collection(UUID.randomUUID(), "OIM - Untagged", "Untagged KG", List.of(), null);
        when(collectionService.listCollections())
            .thenReturn(List.of(dbCol, manualsCol, untaggedCol));
        when(kgRepository.relationshipPredicateExists(eq("fk_to"), anyList())).thenReturn(true);
    }

    /** Edge as the repository returns it: direction/counterpart already resolved by endpoint id. */
    private static KgNeighborEdges.Edge edge(String subject, String predicate, String object,
        String description, KgNeighborEdges.Direction direction, String counterpart,
        String counterpartKind) {
        return new KgNeighborEdges.Edge(subject, predicate, object, description, direction,
            counterpart, counterpartKind);
    }

    @Test
    public void testGetGraphNeighborsStandard() {
        KgNeighborEdges.Edge outgoing = edge("ADSAccount", "fk_to", "Person", "FK to Person",
            KgNeighborEdges.Direction.OUTGOING, "Person", "table");
        KgNeighborEdges.Edge incoming =
            edge("VI_AdHocProcess", "references_table", "ADSAccount", "Process references table",
                KgNeighborEdges.Direction.INCOMING, "VI_AdHocProcess", "process");

        when(kgRepository.getEntityRelationships(eq("ADSAccount"), isNull(), eq("9.3"),
            eq("OIM - DB"), isNull(), eq("both"), anyInt()))
            .thenReturn(new KgNeighborEdges(List.of("table"), List.of(outgoing, incoming)));

        String output = getGraphNeighborsTool.getGraphNeighbors("ADSAccount", "OIM - DB", "9.3",
            null, "both", null);
        assertTrue(output.contains("Connections for 'ADSAccount'"));
        assertTrue(output.contains("Person"));
        assertTrue(output.contains("VI_AdHocProcess"));
        assertTrue(output.contains("outgoing"));
        assertTrue(output.contains("incoming"));
        // Counterpart kind column is present.
        assertTrue(output.contains("| kind |") || output.contains(" kind "));
        assertTrue(output.contains("process"));
        // No ambiguity note when only one kind matched.
        assertFalse(output.contains("matches multiple kinds"));
    }

    @Test
    public void testKindParameterIsForwardedAsHint() {
        KgNeighborEdges.Edge edge =
            edge("ADS", "HAS_CONNECTOR", "ADSConnector", "module owns connector",
                KgNeighborEdges.Direction.OUTGOING, "ADSConnector", "connector");
        when(kgRepository.getEntityRelationships(eq("ADS"), eq("module"), eq("9.3"), eq("OIM - DB"),
            isNull(), eq("both"), anyInt()))
            .thenReturn(new KgNeighborEdges(List.of("module"), List.of(edge)));

        String output = getGraphNeighborsTool.getGraphNeighbors("ADS", "OIM - DB", "9.3", null,
            "both", "module");
        assertTrue(output.contains("Connections for 'ADS'"));
        assertTrue(output.contains("ADSConnector"));
        assertTrue(output.contains("connector"));
    }

    /**
     * Two DIFFERENT nodes routinely share a name (module 'ADS' ─HAS_CONNECTOR→ connector 'ADS').
     * Deriving the direction by comparing the requested name to subject/object matched both sides
     * and printed 'self' with the node itself as counterpart — 1,062 edges of the live graph,
     * including every HAS_CONNECTOR. The tool must render the repository's resolution verbatim.
     */
    @Test
    public void testHomonymEdgeRendersTheRepositoryResolvedDirectionNotSelf() {
        KgNeighborEdges.Edge incoming = edge("ADS", "HAS_CONNECTOR", "ADS", "module owns connector",
            KgNeighborEdges.Direction.INCOMING, "ADS", "module");
        when(kgRepository.getEntityRelationships(eq("ADS"), eq("connector"), eq("9.3"),
            eq("OIM - DB"), isNull(), eq("both"), anyInt()))
            .thenReturn(new KgNeighborEdges(List.of("connector"), List.of(incoming)));

        String output = getGraphNeighborsTool.getGraphNeighbors("ADS", "OIM - DB", "9.3", null,
            "both", "connector");

        assertTrue(output.contains("| ADS | module | HAS_CONNECTOR | incoming |"),
            "expected the counterpart's own kind and an incoming direction, got:\n" + output);
        assertFalse(output.contains("self"),
            "a name collision is not a self-reference:\n" + output);
    }

    /** A genuine self-reference (subject_id == object_id) is still reported as 'self'. */
    @Test
    public void testGenuineSelfReferenceIsRenderedAsSelf() {
        KgNeighborEdges.Edge self = edge("Person", "fk_to", "Person", "manager FK",
            KgNeighborEdges.Direction.SELF, "Person", "table");
        when(kgRepository.getEntityRelationships(eq("Person"), eq("table"), eq("9.3"),
            eq("OIM - DB"), isNull(), eq("both"), anyInt()))
            .thenReturn(new KgNeighborEdges(List.of("table"), List.of(self)));

        String output = getGraphNeighborsTool.getGraphNeighbors("Person", "OIM - DB", "9.3", null,
            "both", "table");

        assertTrue(output.contains("| Person | table | fk_to | self |"),
            "expected a self row, got:\n" + output);
    }

    /** An endpoint with no entity row (legacy, name-only data) renders as an unknown kind. */
    @Test
    public void testUnknownCounterpartKindRendersAsQuestionMark() {
        KgNeighborEdges.Edge unknown = edge("ADSAccount", "fk_to", "Ghost", "FK",
            KgNeighborEdges.Direction.OUTGOING, "Ghost", null);
        when(kgRepository.getEntityRelationships(eq("ADSAccount"), eq("table"), eq("9.3"),
            eq("OIM - DB"), isNull(), eq("both"), anyInt()))
            .thenReturn(new KgNeighborEdges(List.of("table"), List.of(unknown)));

        String output = getGraphNeighborsTool.getGraphNeighbors("ADSAccount", "OIM - DB", "9.3",
            null, "both", "table");

        assertTrue(output.contains("| Ghost | ? | fk_to | outgoing |"),
            "expected an unknown kind marker, got:\n" + output);
    }

    @Test
    public void testPrefixedEntityNameIsSplitIntoKindHint() {
        KgNeighborEdges.Edge edge = edge("ADSAccount", "fk_to", "Person", "FK",
            KgNeighborEdges.Direction.OUTGOING, "Person", "table");
        // "table:ADSAccount" must resolve to bare name "ADSAccount" with kind hint "table".
        when(kgRepository.getEntityRelationships(eq("ADSAccount"), eq("table"), eq("9.3"),
            eq("OIM - DB"), isNull(), eq("both"), anyInt()))
            .thenReturn(new KgNeighborEdges(List.of("table"), List.of(edge)));

        String output = getGraphNeighborsTool.getGraphNeighbors("table:ADSAccount", "OIM - DB",
            "9.3", null, "both", null);
        assertTrue(output.contains("Connections for 'ADSAccount'"));
        assertTrue(output.contains("Person"));
    }

    @Test
    public void testAmbiguousNameWithoutKindReturnsDisambiguationInsteadOfEdges() {
        when(kgRepository.findEntityKindsWithEdgeCounts(eq("Person"), eq("9.3"), eq("OIM - DB"),
            isNull(), eq("both")))
            .thenReturn(List.of(new KgEntityKindEdgeCount("table", 360, "doc-table"),
                new KgEntityKindEdgeCount("ui_forms", 4, "doc-forms"),
                new KgEntityKindEdgeCount("notification", 1, null)));

        String output = getGraphNeighborsTool.getGraphNeighbors("Person", "OIM - DB", "9.3", null,
            "both", null);

        assertTrue(output.contains("'Person' is ambiguous in OIM - DB (tag=9.3)"),
            "expected an ambiguity header, got:\n" + output);
        assertTrue(output.contains("3 kinds across 3 entities"));
        assertTrue(output.contains("kind=\"table\""));
        assertTrue(output.contains("entity_name=\"table:Person\""));
        assertTrue(output.contains("| kind | tag | edges | document_id |"));
        assertTrue(output.contains("| table | (untagged) | 360 | doc-table |"));
        assertTrue(output.contains("ui_forms"));
        assertTrue(output.contains("notification"));
        // No edge table at all — the expensive merged fetch must not even run.
        assertFalse(output.contains("counterpart"));
        verify(kgRepository, never()).getEntityRelationships(anyString(), any(), any(), anyString(),
            any(), anyString(), anyInt());
    }

    @Test
    public void testDisambiguationDistinguishesTagTwinsOfTheSameKind() {
        // Graph identity is tag-scoped, so findEntityKindsWithEdgeCounts groups by entity id and
        // yields one row per (kind, tag) — NOT one row per kind. Without the tag rendered, the two
        // `table` rows are indistinguishable, which makes every playbook's "take the document_id of
        // the row whose kind you asked about" undecidable.
        when(kgRepository.findEntityKindsWithEdgeCounts(eq("Person"), isNull(),
            eq("OIM - Untagged"), isNull(), eq("both")))
            .thenReturn(List.of(new KgEntityKindEdgeCount("table", 397, "doc-10", "10.0"),
                new KgEntityKindEdgeCount("table", 389, "doc-931", "9.3.1"),
                new KgEntityKindEdgeCount("ui_forms", 1, "doc-forms-10", "10.0"),
                new KgEntityKindEdgeCount("ui_forms", 1, "doc-forms-931", "9.3.1")));

        String output = getGraphNeighborsTool.getGraphNeighbors("Person", "OIM - Untagged", null,
            null, "both", null);

        // The header counts KINDS (what the caller must choose), not entities.
        assertTrue(output.contains("2 kinds across 4 entities"),
            "expected a kind-count header, got:\n" + output);
        assertTrue(output.contains("| kind | tag | edges | document_id |"),
            "expected a tag column, got:\n" + output);
        assertTrue(output.contains("| table | 10.0 | 397 | doc-10 |"),
            "expected the 10.0 table row, got:\n" + output);
        assertTrue(output.contains("| table | 9.3.1 | 389 | doc-931 |"),
            "expected the 9.3.1 table row, got:\n" + output);
        // Re-querying with kind alone would land straight in the tag picker: suggest both at once.
        assertTrue(output.contains("kind=\"table\""), "expected a kind hint, got:\n" + output);
        assertTrue(output.contains("tag=\"10.0\""), "expected a tag hint, got:\n" + output);
    }

    @Test
    public void testDisambiguationRendersUntaggedCandidatesExplicitly() {
        when(kgRepository.findEntityKindsWithEdgeCounts(eq("Person"), isNull(),
            eq("OIM - Untagged"), isNull(), eq("both")))
            .thenReturn(List.of(new KgEntityKindEdgeCount("table", 3, "doc-table"),
                new KgEntityKindEdgeCount("ui_forms", 1, "doc-forms")));

        String output = getGraphNeighborsTool.getGraphNeighbors("Person", "OIM - Untagged", null,
            null, "both", null);

        assertTrue(output.contains("| table | (untagged) | 3 | doc-table |"),
            "expected an explicit (untagged) cell, got:\n" + output);
        // Nothing to pin, so the hint must not fabricate a tag= suggestion.
        assertFalse(output.contains("tag=\"\""), "empty tag hint leaked, got:\n" + output);
    }

    @Test
    public void testDisambiguationSuggestsARealKindEvenWhenTheTopRowHasNone() {
        // A legacy kind-less row can outrank the real ones; suggesting kind="?" would be useless.
        when(kgRepository.findEntityKindsWithEdgeCounts(eq("Person"), isNull(),
            eq("OIM - Untagged"), isNull(), eq("both")))
            .thenReturn(List.of(new KgEntityKindEdgeCount("", 9, null),
                new KgEntityKindEdgeCount("table", 2, "doc-table")));

        String output = getGraphNeighborsTool.getGraphNeighbors("Person", "OIM - Untagged", null,
            null, "both", null);

        assertTrue(output.contains("kind=\"table\""),
            "expected a usable kind hint, got:\n" + output);
        assertTrue(output.contains("| ? | (untagged) | 9 |"));
    }

    @Test
    public void testAmbiguousNameWithKindHintStillReturnsEdges() {
        KgNeighborEdges.Edge edge = edge("Person", "fk_to", "PersonInOrg", "FK",
            KgNeighborEdges.Direction.OUTGOING, "PersonInOrg", "table");
        when(kgRepository.getEntityRelationships(eq("Person"), eq("table"), eq("9.3"),
            eq("OIM - DB"), isNull(), eq("both"), anyInt()))
            .thenReturn(new KgNeighborEdges(List.of("table"), List.of(edge)));

        String output = getGraphNeighborsTool.getGraphNeighbors("Person", "OIM - DB", "9.3", null,
            "both", "table");

        assertTrue(output.contains("Connections for 'Person'"));
        assertTrue(output.contains("PersonInOrg"));
        assertFalse(output.contains("is ambiguous"));
        // A pinned kind skips the disambiguation lookup entirely.
        verify(kgRepository, never()).findEntityKindsWithEdgeCounts(anyString(), any(), anyString(),
            any(), anyString());
    }

    @Test
    public void testUniqueNameReturnsEdgesWithoutDisambiguation() {
        KgNeighborEdges.Edge edge = edge("ADSAccount", "fk_to", "Person", "FK",
            KgNeighborEdges.Direction.OUTGOING, "Person", "table");
        when(kgRepository.findEntityKindsWithEdgeCounts(eq("ADSAccount"), eq("9.3"), eq("OIM - DB"),
            isNull(), eq("both")))
            .thenReturn(List.of(new KgEntityKindEdgeCount("table", 1, "doc-table")));
        when(kgRepository.getEntityRelationships(eq("ADSAccount"), isNull(), eq("9.3"),
            eq("OIM - DB"), isNull(), eq("both"), anyInt()))
            .thenReturn(new KgNeighborEdges(List.of("table"), List.of(edge)));

        String output = getGraphNeighborsTool.getGraphNeighbors("ADSAccount", "OIM - DB", "9.3",
            null, "both", null);

        assertTrue(output.contains("Connections for 'ADSAccount'"));
        assertTrue(output.contains("Person"));
        assertFalse(output.contains("is ambiguous"));
    }

    @Test
    public void testUnknownNameKeepsLegacyNoRelationshipsMessage() {
        when(kgRepository.findEntityKindsWithEdgeCounts(eq("Nope"), eq("9.3"), eq("OIM - DB"),
            isNull(), eq("both"))).thenReturn(List.of());
        when(kgRepository.getEntityRelationships(eq("Nope"), isNull(), eq("9.3"), eq("OIM - DB"),
            isNull(), eq("both"), anyInt())).thenReturn(new KgNeighborEdges(List.of(), List.of()));

        String output =
            getGraphNeighborsTool.getGraphNeighbors("Nope", "OIM - DB", "9.3", null, "both", null);
        assertEquals("(no relationships found for 'Nope' in collection 'OIM - DB' at tag '9.3')",
            output);
    }

    @Test
    public void testGetGraphNeighborsValidationErrors() {
        // 1. Mandatory entity_name
        String out1 =
            getGraphNeighborsTool.getGraphNeighbors("", "OIM - DB", "9.3", null, null, null);
        assertTrue(out1.contains("Error: 'entity_name' parameter is required"));

        // 2. Mandatory collection
        String out2 =
            getGraphNeighborsTool.getGraphNeighbors("ADSAccount", "", "9.3", null, null, null);
        assertTrue(out2.contains("Error: 'collection' parameter is required"));

        // 3. Collection does not exist
        String out3 = getGraphNeighborsTool.getGraphNeighbors("ADSAccount", "Nonexistent", "9.3",
            null, null, null);
        assertTrue(out3.contains("Error: Collection 'Nonexistent' does not exist."));

        // 4. Tag does not exist
        String out4 = getGraphNeighborsTool.getGraphNeighbors("ADSAccount", "OIM - DB", "11.0",
            null, null, null);
        assertTrue(out4.contains("Error: Tag '11.0' does not exist in collection 'OIM - DB'."));

        // 5. Invalid relation type
        String out5 = getGraphNeighborsTool.getGraphNeighbors("ADSAccount", "OIM - DB", "9.3",
            "invalid_relation", null, null);
        assertTrue(out5.contains(
            "Error: Relation type 'invalid_relation' does not exist in collection 'OIM - DB'."));

        // 6. Invalid direction
        String out6 = getGraphNeighborsTool.getGraphNeighbors("ADSAccount", "OIM - DB", "9.3",
            "fk_to", "invalid_direction", null);
        assertTrue(out6.contains(
            "Error: Invalid direction 'invalid_direction'. Allowed values are 'incoming', 'outgoing', or 'both'."));

        // 7. Multiple tags
        String out7 = getGraphNeighborsTool.getGraphNeighbors("ADSAccount", "OIM - DB", "9.3,10.0",
            null, null, null);
        assertTrue(out7.contains("Error: Tag '9.3,10.0' does not exist in collection 'OIM - DB'."));
    }

    // --- parseEntityRef (pure logic) ---

    @Test
    public void parseEntityRefSplitsKindPrefix() {
        GetGraphNeighborsTool.EntityRef ref =
            GetGraphNeighborsTool.parseEntityRef("table:ADSAccount", null);
        assertEquals("table", ref.kind());
        assertEquals("ADSAccount", ref.name());
    }

    @Test
    public void parseEntityRefExplicitKindWinsOverPrefix() {
        GetGraphNeighborsTool.EntityRef ref =
            GetGraphNeighborsTool.parseEntityRef("table:ADSAccount", "process");
        assertEquals("process", ref.kind());
        assertEquals("ADSAccount", ref.name());
    }

    @Test
    public void parseEntityRefLeavesPlainNameIntact() {
        GetGraphNeighborsTool.EntityRef ref =
            GetGraphNeighborsTool.parseEntityRef("ADSAccount", null);
        assertNull(ref.kind());
        assertEquals("ADSAccount", ref.name());
    }

    @Test
    public void parseEntityRefDoesNotTreatSpacedColonAsKind() {
        // A colon inside a descriptive name (with a space in the prefix) is not a kind prefix.
        GetGraphNeighborsTool.EntityRef ref =
            GetGraphNeighborsTool.parseEntityRef("Section path: Alpha", null);
        assertNull(ref.kind());
        assertEquals("Section path: Alpha", ref.name());
    }

    @Test
    public void testUntaggedCallOnATagScopedCollectionIsRejected() {
        String output = getGraphNeighborsTool.getGraphNeighbors("ADSAccount", "OIM - DB", null,
            null, "both", "table");

        assertTrue(output.startsWith("Error:"), "expected a hard error, got:\n" + output);
        assertTrue(output.contains("tag-scoped"));
        assertTrue(output.contains("9.3") && output.contains("10.0"));
        // The expensive lookup must not run at all.
        verify(kgRepository, never()).getEntityRelationships(anyString(), any(), any(), anyString(),
            any(), anyString(), anyInt());
    }

}
