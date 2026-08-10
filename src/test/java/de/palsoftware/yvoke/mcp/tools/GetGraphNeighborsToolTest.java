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

    /**
     * Third instance of the same family as {@code search_corpus} and {@code list_documents}: the
     * collection is matched with {@code equalsIgnoreCase}, but every query it then feeds is
     * case-SENSITIVE ({@code c.name = :collection} / {@code c.name IN (:collections)} throughout
     * {@code KgGraphReadRepository}). Forwarding the caller's spelling means a mis-cased collection
     * passes validation and then (a) rejects a perfectly valid {@code relation_type} as "does not
     * exist in collection", and (b) finds no neighbours at all — a confident empty answer for a
     * graph that is right there.
     */
    @Test
    public void theMatchedCollectionIsForwardedInItsStoredSpelling() {
        when(kgRepository.findEntityKindsWithEdgeCounts(anyString(), any(), anyString(), any(),
            anyString())).thenReturn(List.of());
        when(kgRepository.getEntityRelationships(anyString(), any(), any(), anyString(), any(),
            anyString(), anyInt())).thenReturn(new KgNeighborEdges(List.of("table"), List.of()));

        getGraphNeighborsTool.getGraphNeighbors("ADSAccount", "oim - db", "9.3", "fk_to", "both",
            "table");

        verify(kgRepository).relationshipPredicateExists(eq("fk_to"), eq(List.of("OIM - DB")));
        verify(kgRepository).getEntityRelationships(anyString(), any(), any(), eq("OIM - DB"),
            any(), anyString(), anyInt());
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

    /**
     * {@code direction} is an optional parameter whose documented default is {@code both}. Every
     * test in this class passes {@code "both"} explicitly, so the DEFAULT — the value an agent
     * actually gets, since models omit optional params far more often than they set them — has
     * never been executed, and the tool description that advertises it is checked by nothing.
     *
     * <p>
     * Change it to {@code outgoing} or {@code incoming} and every graph question asked without an
     * explicit direction silently returns HALF the neighbourhood: no error, no empty result, just a
     * shorter table that reads as complete. On this corpus that is the difference between "which
     * processes reference this table" and "which tables does this one point at" — an answer that is
     * confidently, invisibly wrong, and the agent has no way to notice because the tool reports
     * success either way.
     *
     * <p>
     * The repository is stubbed to return the two edges ONLY for {@code both} and an empty set for
     * any other direction, so the assertion is on the rendered table rather than on an argument
     * this test supplied.
     */
    @Test
    public void omittingDirectionTraversesBothDirectionsRatherThanHalfTheGraph() {
        when(kgRepository.getEntityRelationships(anyString(), any(), any(), anyString(), any(),
            anyString(), anyInt())).thenReturn(new KgNeighborEdges(List.of("table"), List.of()));

        KgNeighborEdges.Edge outgoing = edge("ADSAccount", "fk_to", "Person", "FK to Person",
            KgNeighborEdges.Direction.OUTGOING, "Person", "table");
        KgNeighborEdges.Edge incoming =
            edge("VI_AdHocProcess", "references_table", "ADSAccount", "Process references table",
                KgNeighborEdges.Direction.INCOMING, "VI_AdHocProcess", "process");
        when(kgRepository.getEntityRelationships(eq("ADSAccount"), isNull(), eq("9.3"),
            eq("OIM - DB"), isNull(), eq("both"), anyInt()))
            .thenReturn(new KgNeighborEdges(List.of("table"), List.of(outgoing, incoming)));

        String output = getGraphNeighborsTool.getGraphNeighbors("ADSAccount", "OIM - DB", "9.3",
            null, null, null);

        assertTrue(output.contains("Person"), output);
        assertTrue(output.contains("VI_AdHocProcess"), output);
        assertTrue(output.contains("outgoing"), output);
        assertTrue(output.contains("incoming"), output);
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

    /**
     * The edge fetch is capped at {@code MAX_EDGES} (200) in SQL, so a hub node's table is a
     * truncated slice of its real neighbourhood. The header is the ONLY thing that says so, and it
     * decides on the boundary: {@code total >= MAX_EDGES} means "a full page came back, assume more
     * exist". Weaken that to {@code >} and the single most-connected node in the graph — the one
     * whose edge count lands exactly on the cap because the cap produced it — is labelled "total:
     * 200", i.e. reported as a COMPLETE neighbourhood. An agent then answers "ADSAccount has no
     * foreign key to X" from an arbitrary 200-row slice, with nothing in the output hinting at the
     * loss, and it is precisely the hub tables (Person, ADSAccount) that questions are asked about.
     *
     * <p>
     * The boundary is invisible to every other test in this file: they all stub one or two edges,
     * so {@code countLabel} has only ever taken its "total: N" branch and the comparison operator
     * has never mattered. The 199-edge half pins the other direction — a genuinely complete result
     * must not be labelled as capped, which is what stops the check from being "fixed" by always
     * claiming more exist and sending the agent into pointless narrowing round-trips.
     */
    @Test
    public void aFullEdgePageIsLabelledAsCappedAndAShortPageIsNot() {
        List<KgNeighborEdges.Edge> fullPage = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            fullPage.add(edge("HubTable", "fk_to", "Child" + i, "FK " + i,
                KgNeighborEdges.Direction.OUTGOING, "Child" + i, "table"));
        }
        when(kgRepository.getEntityRelationships(eq("HubTable"), eq("table"), eq("9.3"),
            eq("OIM - DB"), isNull(), eq("both"), anyInt()))
            .thenReturn(new KgNeighborEdges(List.of("table"), fullPage));

        String capped = getGraphNeighborsTool.getGraphNeighbors("HubTable", "OIM - DB", "9.3", null,
            "both", "table");

        assertTrue(capped.contains("showing first 200"),
            "a full page must be declared capped, got:\n" + capped.lines().findFirst().orElse(""));
        assertFalse(capped.contains("total: 200"),
            "a capped page must never be reported as a complete total:\n"
                + capped.lines().findFirst().orElse(""));

        List<KgNeighborEdges.Edge> shortPage = new ArrayList<>(fullPage.subList(0, 199));
        when(kgRepository.getEntityRelationships(eq("ShortTable"), eq("table"), eq("9.3"),
            eq("OIM - DB"), isNull(), eq("both"), anyInt()))
            .thenReturn(new KgNeighborEdges(List.of("table"), shortPage));

        String uncapped = getGraphNeighborsTool.getGraphNeighbors("ShortTable", "OIM - DB", "9.3",
            null, "both", "table");

        assertTrue(uncapped.contains("total: 199"),
            "one row short of the cap is a complete result, got:\n"
                + uncapped.lines().findFirst().orElse(""));
        assertFalse(uncapped.contains("showing first"),
            "a complete result must not be labelled capped:\n"
                + uncapped.lines().findFirst().orElse(""));
    }

    /**
     * The edge fetch is capped at {@code MAX_EDGES} (200) in SQL, and
     * {@link #aFullEdgePageIsLabelledAsCappedAndAShortPageIsNot} already pins the boundary itself.
     * What is pinned NOWHERE is the second half of that notice: the way OUT of the truncation. This
     * tool has no offset and no page parameter, so re-querying with a narrower
     * {@code relation_type} or {@code direction} is the ONLY mechanism an agent has for ever seeing
     * the edges the cap hid. A truncation warning that omits the remedy tells the agent its answer
     * is incomplete and gives it nothing to do about that, which in practice means it answers
     * anyway from an arbitrary 200-row slice of a hub node — Person, ADSAccount, precisely the
     * nodes questions get asked about — and a missing edge stays indistinguishable from an absent
     * one.
     *
     * <p>
     * Trimming the label to a bare "showing first 200, more exist" is exactly the kind of tidy-up
     * that no existing assertion notices: both checks in the sibling test stop at "showing first
     * 200", and nothing in this repository asserts on the narrowing hint at all.
     */
    @Test
    public void aFullEdgePageIsLabelledAsTruncatedRatherThanPresentedAsComplete() {
        List<KgNeighborEdges.Edge> fullPage = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            fullPage.add(edge("HubNode", "fk_to", "Child" + i, "FK " + i,
                KgNeighborEdges.Direction.OUTGOING, "Child" + i, "table"));
        }
        when(kgRepository.getEntityRelationships(eq("HubNode"), eq("table"), eq("9.3"),
            eq("OIM - DB"), isNull(), eq("both"), anyInt()))
            .thenReturn(new KgNeighborEdges(List.of("table"), fullPage));

        String capped = getGraphNeighborsTool.getGraphNeighbors("HubNode", "OIM - DB", "9.3", null,
            "both", "table");
        String header = capped.lines().findFirst().orElse("");

        assertTrue(header.contains("showing first 200"),
            "a full page must declare itself truncated, got:\n" + header);
        assertTrue(header.contains("narrow with relation_type/direction"),
            "a truncation notice with no way out leaves the agent nothing to do, got:\n" + header);
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
