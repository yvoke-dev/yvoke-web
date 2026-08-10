package de.palsoftware.yvoke.mcp.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.kg.core.model.KgEntity;
import de.palsoftware.yvoke.kg.core.repository.KgGraphReadRepository;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;

public class SearchGraphEntitiesToolTest {

    private KgGraphReadRepository kgRepository;
    private CollectionService collectionService;
    private SearchGraphEntitiesTool searchGraphEntitiesTool;

    @BeforeEach
    public void setUp() {
        kgRepository = mock(KgGraphReadRepository.class);
        collectionService = mock(CollectionService.class);
        searchGraphEntitiesTool = new SearchGraphEntitiesTool(kgRepository, collectionService);

        Collection dbCol = new Collection(UUID.randomUUID(), "OIM - DB", "DB Schema",
            List.of("9.3", "10.0"), null);
        when(collectionService.listCollections()).thenReturn(List.of(dbCol));
    }

    /**
     * Two rows that differ ONLY by tag are ONE object in two product versions, and the tool has to
     * say so — the kind-collision note cannot, because for this shape the kind set has size 1 and
     * that check is silent by construction.
     *
     * <p>
     * This is the read-side face of the tag-identity work: identity is (collection, kind, name,
     * tag), the per-version document link lives in {@code entities.metadata->>'document_id'}, and
     * so the two rows carry DIFFERENT document_ids for the same table. Unflagged, a model reads
     * them as two distinct entities, picks whichever sorted first, and cites 9.3's document while
     * answering a question about 10.0 — an answer that is fluent, sourced, and wrong, with nothing
     * in the output to reveal it. Grep confirms the note's literal appears nowhere under src/test
     * or src/it: {@code testDuplicateNamesAcrossKindsEmitNoteAboveTable} builds a KIND collision
     * (all rows tagged "9.3"), so deleting the whole version branch leaves every existing test
     * green.
     *
     * <p>
     * The collection here declares no tags, which is what makes the shape reachable: the tool
     * forces a single tag on a tag-scoped collection, whereas a collection whose {@code tags} array
     * is empty while its entity rows carry tags — the drift the corpus import scripts produce by
     * writing the TEXT[] columns directly — is exempted from that check and returns both versions
     * in one result set.
     */
    @Test
    public void testSameNameAndKindUnderTwoTagsIsFlaggedAsOneObjectInTwoVersions() {
        Collection untagged = new Collection(UUID.randomUUID(), "OIM - Custom",
            "Install kit — versions carried on the rows", List.of(), null);
        when(collectionService.listCollections()).thenReturn(List.of(untagged));

        KgEntity personV93 = new KgEntity(UUID.randomUUID(), "OIM - Custom", "Person", "table",
            "9.3", "Person table", Map.of("document_id", "doc-9-3"), 1.0);
        KgEntity personV10 = new KgEntity(UUID.randomUUID(), "OIM - Custom", "Person", "table",
            "10.0", "Person table", Map.of("document_id", "doc-10-0"), 1.0);
        KgEntity unique = new KgEntity(UUID.randomUUID(), "OIM - Custom", "ADSAccount", "table",
            "9.3", "AD accounts", Map.of("document_id", "doc-ads"), 0.4);
        when(kgRepository.fuzzySearchEntities(eq("Person"), eq(20), eq(null), eq("OIM - Custom"),
            eq(null))).thenReturn(List.of(personV93, personV10, unique));

        String output =
            searchGraphEntitiesTool.searchGraphEntities("Person", "OIM - Custom", null, null, 20);

        assertTrue(output.contains(
            "> Note: these rows are the SAME object in several product versions, each with its own document:"),
            "expected a version note, got:\n" + output);
        assertTrue(output.contains("'Person (table)' → tags 10.0, 9.3"),
            "expected the colliding rows named with BOTH their tags, got:\n" + output);
        assertTrue(output.contains("Pass the 'tag' of the version you mean"),
            "expected the remedy the model is supposed to follow, got:\n" + output);
        // One kind must not be reported as several: that note prescribes a different remedy.
        assertFalse(output.contains("these names exist under several kinds"),
            "a single-kind version collision was reported as a kind collision:\n" + output);
        // A name occurring once is not flagged at all.
        assertFalse(output.contains("'ADSAccount (table)' →"),
            "a unique row was flagged:\n" + output);
        // Both per-version documents must still reach the model, and the note must precede them.
        assertTrue(output.contains("doc-9-3") && output.contains("doc-10-0"),
            "expected both versions' document_ids in the table, got:\n" + output);
        assertTrue(output.indexOf("> Note: these rows are the SAME object") < output
            .indexOf("| entity_name |"), "the note must sit ABOVE the table:\n" + output);
    }

    /** n entities of one kind, named e0..e(n-1) — enough to fill a page. */
    private static List<KgEntity> entities(int n, String kind) {
        List<KgEntity> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new KgEntity(UUID.randomUUID(), "OIM - DB", "e" + i, kind, "9.3", "desc " + i,
                Collections.emptyMap(), 0.5));
        }
        return out;
    }

    @Test
    public void testListAllHeaderDoesNotRenderTheLiteralNullQuery() {
        // The list-all path the playbooks teach passes no query; the header used to interpolate it
        // straight in, printing: Graph Entities matching 'null'.
        when(kgRepository.listEntities(eq("OIM - DB"), eq("9.3"), eq("table"), eq(20), eq(0)))
            .thenReturn(entities(3, "table"));

        String output =
            searchGraphEntitiesTool.searchGraphEntities(null, "OIM - DB", "9.3", "table", 20);

        assertFalse(output.contains("null"), "the literal null leaked into the header:\n" + output);
        assertTrue(output.contains("All graph entities"),
            "expected a list-all header, got:\n" + output);
        assertTrue(output.contains("(kind=table)"), "expected the kind in the header:\n" + output);
    }

    @Test
    public void testResultsHittingTheLimitAreLabelledAsTruncated() {
        // 13 of 23 kinds exceed the cap in this corpus, so a full page is normally a truncated one.
        when(kgRepository.listEntities(eq("OIM - DB"), eq("9.3"), eq("table"), eq(20), eq(0)))
            .thenReturn(entities(20, "table"));

        String output =
            searchGraphEntitiesTool.searchGraphEntities("*", "OIM - DB", "9.3", "table", 20);

        assertTrue(output.contains("showing first 20, more exist"),
            "expected a truncation label, got:\n" + output);
    }

    @Test
    public void testResultsBelowTheLimitAreNotLabelledAsTruncated() {
        when(kgRepository.listEntities(eq("OIM - DB"), eq("9.3"), eq("table"), eq(20), eq(0)))
            .thenReturn(entities(19, "table"));

        String output =
            searchGraphEntitiesTool.searchGraphEntities("*", "OIM - DB", "9.3", "table", 20);

        assertFalse(output.contains("more exist"),
            "a complete result set was labelled truncated:\n" + output);
    }

    @Test
    public void testLimitAboveTheRepositoryCapIsClampedAndStillLabelled() {
        // The repository silently clamps at MAX_LIMIT, so a caller asking for 500 gets 200 back.
        // Testing size()==requestedLimit would miss this: 200 != 500, yet the page IS truncated.
        when(kgRepository.listEntities(eq("OIM - DB"), eq("9.3"), eq("table"),
            eq(KgGraphReadRepository.MAX_LIMIT), eq(0)))
            .thenReturn(entities(KgGraphReadRepository.MAX_LIMIT, "table"));

        String output =
            searchGraphEntitiesTool.searchGraphEntities("*", "OIM - DB", "9.3", "table", 500);

        assertTrue(output.contains("showing first 200, more exist"),
            "expected the clamped cap to be labelled, got:\n" + output);
    }

    @Test
    public void testSearchGraphEntities() {
        KgEntity entity = new KgEntity(UUID.randomUUID(), "OIM - DB", "ADSAccount", "table", "9.3",
            "AD accounts table", Collections.emptyMap(), 0.85);
        when(kgRepository.fuzzySearchEntities(eq("ADSAccount"), eq(20), eq("9.3"), eq("OIM - DB"),
            eq(null))).thenReturn(List.of(entity));

        String output =
            searchGraphEntitiesTool.searchGraphEntities("ADSAccount", "OIM - DB", "9.3", null, 20);
        assertTrue(output.contains("Graph Entities matching 'ADSAccount'"));
        // 'tag' is a column: identity is tag-scoped, so two rows can differ only by product
        // version, and the tag is the only thing that explains their different document_ids.
        assertTrue(output.contains("| entity_name | kind | tag | document_id | description |"));
        assertTrue(output.contains("ADSAccount"));
        assertTrue(output.contains("AD accounts table"));
    }

    @Test
    public void testSearchGraphEntitiesWithCategory() {
        KgEntity entity = new KgEntity(UUID.randomUUID(), "OIM - DB", "ADSAccount", "table", "9.3",
            "AD accounts table", Collections.emptyMap(), 0.85);
        when(kgRepository.fuzzySearchEntities(eq("ADSAccount"), eq(20), eq("9.3"), eq("OIM - DB"),
            eq("table"))).thenReturn(List.of(entity));

        String output = searchGraphEntitiesTool.searchGraphEntities("ADSAccount", "OIM - DB", "9.3",
            "table", 20);
        assertTrue(output.contains("Graph Entities matching 'ADSAccount'"));
        assertTrue(output.contains("kind=table"));
        assertTrue(output.contains("ADSAccount"));
        assertTrue(output.contains("AD accounts table"));
    }

    @Test
    public void testDuplicateNamesAcrossKindsEmitNoteAboveTable() {
        KgEntity personTable = new KgEntity(UUID.randomUUID(), "OIM - DB", "Person", "table", "9.3",
            "Person table", Map.of("document_id", "doc-table"), 1.0);
        KgEntity personForm = new KgEntity(UUID.randomUUID(), "OIM - DB", "Person", "ui_forms",
            "9.3", "Person form", Map.of("document_id", "doc-form"), 1.0);
        KgEntity personModel = new KgEntity(UUID.randomUUID(), "OIM - DB", "person", "entity_model",
            "9.3", "Person model", Map.of("document_id", "doc-model"), 1.0);
        KgEntity other = new KgEntity(UUID.randomUUID(), "OIM - DB", "ADSAccount", "table", "9.3",
            "AD accounts", Collections.emptyMap(), 0.4);
        when(kgRepository.fuzzySearchEntities(eq("Person"), eq(20), eq("9.3"), eq("OIM - DB"),
            eq(null))).thenReturn(List.of(personTable, personForm, personModel, other));

        String output =
            searchGraphEntitiesTool.searchGraphEntities("Person", "OIM - DB", "9.3", null, 20);

        assertTrue(output.contains(
            "> Note: these names exist under several kinds — they are DIFFERENT objects, not duplicates:"));
        // Kinds listed alphabetically, case-insensitive duplicate detection ('person' vs 'Person').
        assertTrue(output.contains("'Person' → entity_model, table, ui_forms"),
            "expected alphabetical kinds for the duplicated name, got:\n" + output);
        assertTrue(output.contains("Pick the row whose 'kind' matches what you asked about"));
        // Unique names are not flagged.
        assertFalse(output.contains("'ADSAccount' →"));
        // The note must sit ABOVE the markdown table so it is read before the rows.
        assertTrue(output.indexOf("> Note: these names exist") < output.indexOf("| entity_name |"));
    }

    @Test
    public void testNoDuplicateNamesEmitsNoNote() {
        KgEntity a = new KgEntity(UUID.randomUUID(), "OIM - DB", "Person", "table", "9.3", "Person",
            Collections.emptyMap(), 1.0);
        KgEntity b = new KgEntity(UUID.randomUUID(), "OIM - DB", "ADSAccount", "table", "9.3",
            "AD accounts", Collections.emptyMap(), 0.4);
        when(kgRepository.fuzzySearchEntities(eq("Person"), eq(20), eq("9.3"), eq("OIM - DB"),
            eq(null))).thenReturn(List.of(a, b));

        String output =
            searchGraphEntitiesTool.searchGraphEntities("Person", "OIM - DB", "9.3", null, 20);
        assertFalse(output.contains("Note: these names exist under several kinds"));
    }

    @Test
    public void testSearchGraphEntitiesMissingCollections() {
        String outputNull =
            searchGraphEntitiesTool.searchGraphEntities("ADSAccount", null, null, null, null);
        assertTrue(outputNull.contains("Error: 'collection' parameter is required."));

        String outputEmpty =
            searchGraphEntitiesTool.searchGraphEntities("ADSAccount", "   ", null, null, null);
        assertTrue(outputEmpty.contains("Error: 'collection' parameter is required."));
    }

    @Test
    public void testSearchGraphEntitiesCollectionDoesNotExist() {
        String output = searchGraphEntitiesTool.searchGraphEntities("ADSAccount", "Nonexistent-Col",
            null, null, null);
        assertTrue(output.contains("Error: Collection 'Nonexistent-Col' does not exist."));
    }

    @Test
    public void testSearchGraphEntitiesTagDoesNotExist() {
        String output = searchGraphEntitiesTool.searchGraphEntities("ADSAccount", "OIM - DB",
            "11.0", null, null);
        assertTrue(output.contains("Error: Tag '11.0' does not exist in collection 'OIM - DB'."));
    }

    @Test
    public void testSearchGraphEntitiesMultipleTagsNotSupported() {
        String output = searchGraphEntitiesTool.searchGraphEntities("ADSAccount", "OIM - DB",
            "9.3,10.0", null, null);
        assertTrue(
            output.contains("Error: Tag '9.3,10.0' does not exist in collection 'OIM - DB'."));
    }

    @Test
    public void testUntaggedCallOnATagScopedCollectionIsRejected() {
        String output =
            searchGraphEntitiesTool.searchGraphEntities("ADSAccount", "OIM - DB", null, null, 20);

        assertTrue(output.startsWith("Error:"), "expected a hard error, got:\n" + output);
        assertTrue(output.contains("tag-scoped"));
        assertTrue(output.contains("9.3") && output.contains("10.0"),
            "expected the valid tags listed, got:\n" + output);
    }

}
