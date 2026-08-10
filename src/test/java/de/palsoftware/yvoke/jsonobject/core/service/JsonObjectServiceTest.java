package de.palsoftware.yvoke.jsonobject.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyInt;

import de.palsoftware.yvoke.jsonobject.core.model.JsonObject;
import de.palsoftware.yvoke.jsonobject.core.repository.JsonObjectRepository;
import de.palsoftware.yvoke.jsonobject.core.repository.JsonSchemaRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import de.palsoftware.yvoke.jsonobject.core.model.JsonSchema;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.OffsetDateTime;
import java.util.HashMap;

class JsonObjectServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void importResolvesExistingObjectsWithASingleBatchedProbe() {
        JsonObjectRepository repo = mock(JsonObjectRepository.class);
        JsonSchemaRepository schemaRepo = mock(JsonSchemaRepository.class);
        JsonSchemaExtractor extractor = mock(JsonSchemaExtractor.class);
        when(extractor.extractSchema(anyList())).thenReturn(Map.of());
        when(schemaRepo.findByCollectionId(any(), any())).thenReturn(Optional.empty());

        UUID collectionId = UUID.randomUUID();
        UUID existingIdForA = UUID.randomUUID();
        // Only "a" already exists in the collection.
        // The probe is tag-scoped (a natural key repeats across product versions), so the service
        // calls the 4-arg overload with this import's tag set.
        when(repo.findIdsByJsonField(eq(collectionId), eq("id"), anyList(), anyList()))
            .thenReturn(Map.of("a", existingIdForA));

        JsonObjectService service = new JsonObjectService(repo, schemaRepo, extractor);
        List<Map<String, Object>> objects =
            List.of(Map.of("id", "a"), Map.of("id", "b"), Map.of("id", "c"));

        service.importObjects(collectionId, "coll", objects, "src.json", List.of(), "id");

        // Exactly ONE probe query for the whole batch — never the per-object probe (PRF-02).
        ArgumentCaptor<List<String>> valuesCaptor = ArgumentCaptor.forClass(List.class);
        verify(repo, times(1)).findIdsByJsonField(eq(collectionId), eq("id"),
            valuesCaptor.capture(), eq(List.of()));
        assertThat(valuesCaptor.getValue()).containsExactlyInAnyOrder("a", "b", "c");
        // "a" -> update (reusing its id); "b","c" -> insert.
        ArgumentCaptor<List<JsonObject>> insertCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<JsonObject>> updateCaptor = ArgumentCaptor.forClass(List.class);
        verify(repo).saveBatch(insertCaptor.capture());
        verify(repo).updateBatch(updateCaptor.capture());
        assertThat(insertCaptor.getValue()).hasSize(2);
        assertThat(updateCaptor.getValue()).hasSize(1);
        assertThat(updateCaptor.getValue().get(0).id()).isEqualTo(existingIdForA);
    }

    @Test
    void importWithoutUniqueFieldInsertsEverythingAndSkipsTheProbe() {
        JsonObjectRepository repo = mock(JsonObjectRepository.class);
        JsonSchemaRepository schemaRepo = mock(JsonSchemaRepository.class);
        JsonSchemaExtractor extractor = mock(JsonSchemaExtractor.class);
        when(extractor.extractSchema(anyList())).thenReturn(Map.of());
        when(schemaRepo.findByCollectionId(any(), any())).thenReturn(Optional.empty());

        JsonObjectService service = new JsonObjectService(repo, schemaRepo, extractor);
        List<Map<String, Object>> objects = List.of(Map.of("id", "a"), Map.of("id", "b"));

        service.importObjects(UUID.randomUUID(), "coll", objects, "src.json", List.of(), null);

        verify(repo, never()).findIdsByJsonField(any(), anyString(), anyList());
        verify(repo).saveBatch(anyList());
    }

    /**
     * The declared schema is a SEPARATE DELIVERABLE from the data. An import merges into the
     * existing row ONLY when {@code source = 'inferred'}; a hand-authored row ({@code 'imported'} /
     * {@code 'manual'}) is frozen and must be left byte-for-byte alone. That declaration is exactly
     * what MCP {@code get_json_schema} hands an agent, so the failure mode is an agent being told a
     * field or enum value does not exist while hundreds of rows carry it — measured once as 4 of 10
     * {@code via} values and 6 of 11 {@code category} values declared. Nothing warns; nothing
     * compares the declaration to the data. Merging a frozen row instead would silently overwrite a
     * curated declaration on the next ingest, which is the opposite failure and just as invisible.
     */
    @Test
    void anImportNeverOverwritesASchemaThatWasNotInferred() {
        for (String frozenSource : new String[] {"manual", "imported"}) {
            JsonObjectRepository repo = mock(JsonObjectRepository.class);
            JsonSchemaRepository schemaRepo = mock(JsonSchemaRepository.class);
            JsonSchemaExtractor extractor = mock(JsonSchemaExtractor.class);
            when(extractor.extractSchema(anyList())).thenReturn(Map.of("newField", "string"));

            UUID collectionId = UUID.randomUUID();
            Map<String, Object> curated = new HashMap<>(Map.of("curated", "declaration"));
            when(schemaRepo.findByCollectionId(any(), any()))
                .thenReturn(Optional.of(new JsonSchema(UUID.randomUUID(), collectionId, "9.3",
                    curated, frozenSource, OffsetDateTime.now())));

            new JsonObjectService(repo, schemaRepo, extractor).importObjects(collectionId, "coll",
                List.of(Map.of("newField", "v")), "src.jsonl", List.of("9.3"), null);

            verify(schemaRepo, never()).upsert(any());
            verify(extractor, never()).mergeSchema(any(), any());
            assertThat(curated).as("the frozen declaration must not be mutated in place")
                .containsExactlyEntriesOf(Map.of("curated", "declaration"));
        }
    }

    /** The complement: an inferred row IS merged, so a genuinely auto-managed schema keeps up. */
    @Test
    void anImportMergesIntoASchemaThatWasInferred() {
        JsonObjectRepository repo = mock(JsonObjectRepository.class);
        JsonSchemaRepository schemaRepo = mock(JsonSchemaRepository.class);
        JsonSchemaExtractor extractor = mock(JsonSchemaExtractor.class);
        when(extractor.extractSchema(anyList())).thenReturn(Map.of("newField", "string"));

        UUID collectionId = UUID.randomUUID();
        when(schemaRepo.findByCollectionId(any(), any()))
            .thenReturn(Optional.of(new JsonSchema(UUID.randomUUID(), collectionId, "9.3",
                new HashMap<>(Map.of("old", "string")), "inferred", OffsetDateTime.now())));

        new JsonObjectService(repo, schemaRepo, extractor).importObjects(collectionId, "coll",
            List.of(Map.of("newField", "v")), "src.jsonl", List.of("9.3"), null);

        verify(extractor).mergeSchema(any(), any());
        verify(schemaRepo).upsert(any());
    }

    /**
     * A schema row is per (collection, tag), so an import carrying two tags has to write the SAME
     * extracted schema to BOTH rows. This is the json_objects half of the tag-scoping invariant the
     * graph tables learned the hard way: one collection deliberately holds two product versions
     * separated only by their tag, and every read is scoped to exactly one of them —
     * {@code getSchema(collectionId, tag)} is a per-tag lookup, and it is what MCP
     * {@code get_json_schema} hands an agent. Write only the first tag's row and the second version
     * has NO declaration at all: the agent asking about 10.0 is told the fields do not exist, while
     * the objects are sitting in the table, correctly tagged, being returned by
     * {@code query_json_objects}. Nothing errors, nothing warns, and nothing anywhere compares the
     * declaration to the data — the measured version of this failure was an agent told a category
     * did not exist when 760 rows carried it.
     *
     * <p>
     * The untagged half pins the other end: a collection with no tags must still get exactly ONE
     * row and it must be keyed by {@code null}, because that null IS the lookup key
     * {@code findByCollectionId(collectionId, null)} uses. Writing zero rows there (the shape an
     * "iterate the tags" rewrite naturally produces for an empty list) leaves an untagged corpus
     * permanently schema-less. Every other test in this file passes a single tag, so the loop has
     * only ever run once and its arity has never been observed.
     */
    @Test
    void aTwoTagImportWritesTheExtractedSchemaToEveryTagsRow() {
        JsonObjectRepository repo = mock(JsonObjectRepository.class);
        JsonSchemaRepository schemaRepo = mock(JsonSchemaRepository.class);
        JsonSchemaExtractor extractor = mock(JsonSchemaExtractor.class);
        Map<String, Object> extracted = Map.of("uid", "string");
        when(extractor.extractSchema(anyList())).thenReturn(extracted);
        when(schemaRepo.findByCollectionId(any(), any())).thenReturn(Optional.empty());

        UUID collectionId = UUID.randomUUID();
        new JsonObjectService(repo, schemaRepo, extractor).importObjects(collectionId,
            "OIM - DB - History", List.of(Map.of("uid", "a")), "history.jsonl",
            List.of("9.3", "10.0"), null);

        ArgumentCaptor<JsonSchema> written = ArgumentCaptor.forClass(JsonSchema.class);
        verify(schemaRepo, times(2)).upsert(written.capture());
        List<JsonSchema> rows = written.getAllValues();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).tag()).isEqualTo("9.3");
        assertThat(rows.get(1).tag()).isEqualTo("10.0");
        assertThat(rows.get(0).schemaData()).isEqualTo(extracted);
        assertThat(rows.get(1).schemaData()).isEqualTo(extracted);
        // Each tag's row is resolved on its own key — a collection-wide probe would fetch 9.3's row
        // and merge 10.0's fields into it.
        verify(schemaRepo).findByCollectionId(collectionId, "9.3");
        verify(schemaRepo).findByCollectionId(collectionId, "10.0");

        // Untagged: exactly one row, keyed by the null the per-tag lookup will search for.
        JsonSchemaRepository untaggedRepo = mock(JsonSchemaRepository.class);
        when(untaggedRepo.findByCollectionId(any(), any())).thenReturn(Optional.empty());
        new JsonObjectService(repo, untaggedRepo, extractor).importObjects(collectionId, "coll",
            List.of(Map.of("uid", "a")), "history.jsonl", List.of(), null);

        ArgumentCaptor<JsonSchema> untagged = ArgumentCaptor.forClass(JsonSchema.class);
        verify(untaggedRepo, times(1)).upsert(untagged.capture());
        assertThat(untagged.getValue().tag()).isNull();
    }

    /**
     * A page and its total must be produced by the SAME predicate. {@code searchObjects} and
     * {@code countSearchObjects} each route one query string through three branches — a query
     * starting with {@code $} goes to the jsonpath predicate (and is TRIMMED), any other non-blank
     * query to the free-text ILIKE predicate (RAW, not trimmed), a null/blank query to the
     * unfiltered collection listing — and nothing ties the two routers together but this test.
     *
     * <p>
     * If they diverge, nothing errors. The admin corpus browser divides the total by the page size
     * to render its page links, so a total computed by a wider predicate than the page paints page
     * links into empty pages, and a narrower one hides rows that exist. The MCP
     * {@code query_json_objects} {@code countOnly} answer is worse: the number is quoted to the
     * agent as fact and becomes an assertion in the user's answer — "3 objects match" for a filter
     * that was never applied.
     *
     * <p>
     * The offset is pinned as {@code page * size} (75, not 3): passing the page index straight
     * through as a row offset is the same offset/limit confusion that {@code listObjectsByOffset}
     * exists to keep apart, and it re-serves page 0 forever.
     *
     * <p>
     * Only the jsonpath branch trims — the free-text branch forwards the RAW string (the proposal
     * for this test assumed both trim; the code does not). That asymmetry is deliberate: the ILIKE
     * pattern is {@code %query%}, so trimming there would silently change which rows match, while a
     * jsonpath with surrounding whitespace would fail the {@code ::jsonpath} cast outright. It is
     * asserted explicitly so a "tidy up, trim everywhere" edit fails here rather than in
     * production.
     *
     * <p>
     * The last block pins the related grouped-count hole: {@code countGroupedObjects} DROPS a
     * non-jsonpath filter to a null path, i.e. it counts the whole collection. That is deliberate
     * but load-bearing — {@code QueryJsonObjectsTool} refuses a free-text filter combined with
     * {@code groupBy} precisely because of it, and if the service started forwarding the free text
     * as a jsonpath instead, the tool's guard would be guarding against the wrong thing and
     * Postgres would reject the cast at runtime.
     */
    @Test
    void aPageAndItsTotalAreComputedByTheSameBranch() {
        UUID collectionId = UUID.randomUUID();
        List<String> tags = List.of("10.0");
        int page = 3;
        int size = 25;
        int expectedOffset = page * size;
        assertThat(expectedOffset).as("the repository takes a ROW offset, not a page index")
            .isEqualTo(75);
        JsonObject row = new JsonObject(UUID.randomUUID(), collectionId, "OIM - DB - History",
            Map.of("category", "admin"), "history.jsonl", tags, null);

        // --- '$' -> jsonpath predicate, on BOTH sides, and trimmed on BOTH sides ---
        JsonObjectRepository pathRepo = mock(JsonObjectRepository.class);
        JsonObjectService pathService = new JsonObjectService(pathRepo,
            mock(JsonSchemaRepository.class), mock(JsonSchemaExtractor.class));
        String pathQuery = "  $.category == \"admin\"  ";
        when(pathRepo.queryByJsonPath(collectionId, pathQuery.trim(), tags, size, expectedOffset))
            .thenReturn(List.of(row));
        when(pathRepo.countByJsonPath(collectionId, pathQuery.trim(), tags)).thenReturn(7L);

        assertThat(pathService.searchObjects(collectionId, pathQuery, tags, page, size))
            .containsExactly(row);
        assertThat(pathService.countSearchObjects(collectionId, pathQuery, tags)).isEqualTo(7L);
        verify(pathRepo).queryByJsonPath(collectionId, pathQuery.trim(), tags, size,
            expectedOffset);
        verify(pathRepo).countByJsonPath(collectionId, pathQuery.trim(), tags);
        verify(pathRepo, never()).search(any(), anyString(), anyList(), anyInt(), anyInt());
        verify(pathRepo, never()).countSearch(any(), anyString(), anyList());
        verify(pathRepo, never()).findByCollectionId(any(), anyList(), anyInt(), anyInt());
        verify(pathRepo, never()).countByCollectionId(any(), anyList());

        // --- free text -> ILIKE predicate on both sides, forwarded RAW (only '$' trims) ---
        JsonObjectRepository textRepo = mock(JsonObjectRepository.class);
        JsonObjectService textService = new JsonObjectService(textRepo,
            mock(JsonSchemaRepository.class), mock(JsonSchemaExtractor.class));
        String textQuery = "  admin  ";
        when(textRepo.search(collectionId, textQuery, tags, size, expectedOffset))
            .thenReturn(List.of(row));
        when(textRepo.countSearch(collectionId, textQuery, tags)).thenReturn(3L);

        assertThat(textService.searchObjects(collectionId, textQuery, tags, page, size))
            .containsExactly(row);
        assertThat(textService.countSearchObjects(collectionId, textQuery, tags)).isEqualTo(3L);
        verify(textRepo).search(eq(collectionId), eq(textQuery), eq(tags), eq(size),
            eq(expectedOffset));
        verify(textRepo).countSearch(eq(collectionId), eq(textQuery), eq(tags));
        verify(textRepo, never()).queryByJsonPath(any(), anyString(), anyList(), anyInt(),
            anyInt());
        verify(textRepo, never()).countByJsonPath(any(), anyString(), anyList());
        verify(textRepo, never()).findByCollectionId(any(), anyList(), anyInt(), anyInt());
        verify(textRepo, never()).countByCollectionId(any(), anyList());

        // --- null / blank -> the unfiltered listing on both sides, same tag scope ---
        JsonObjectRepository blankRepo = mock(JsonObjectRepository.class);
        JsonObjectService blankService = new JsonObjectService(blankRepo,
            mock(JsonSchemaRepository.class), mock(JsonSchemaExtractor.class));
        when(blankRepo.findByCollectionId(collectionId, tags, size, expectedOffset))
            .thenReturn(List.of(row));
        when(blankRepo.countByCollectionId(collectionId, tags)).thenReturn(11L);
        for (String blank : new String[] {null, "   "}) {
            assertThat(blankService.searchObjects(collectionId, blank, tags, page, size))
                .containsExactly(row);
            assertThat(blankService.countSearchObjects(collectionId, blank, tags)).isEqualTo(11L);
        }
        verify(blankRepo, times(2)).findByCollectionId(collectionId, tags, size, expectedOffset);
        verify(blankRepo, times(2)).countByCollectionId(collectionId, tags);
        verify(blankRepo, never()).search(any(), anyString(), anyList(), anyInt(), anyInt());
        verify(blankRepo, never()).countSearch(any(), anyString(), anyList());
        verify(blankRepo, never()).queryByJsonPath(any(), anyString(), anyList(), anyInt(),
            anyInt());
        verify(blankRepo, never()).countByJsonPath(any(), anyString(), anyList());

        // --- grouped count: a non-jsonpath filter is DROPPED to a null path (never forwarded) ---
        JsonObjectRepository groupRepo = mock(JsonObjectRepository.class);
        JsonObjectService groupService = new JsonObjectService(groupRepo,
            mock(JsonSchemaRepository.class), mock(JsonSchemaExtractor.class));
        when(groupRepo.countGroupedByJsonPath(collectionId, null, "role", tags))
            .thenReturn(Map.of("admin", 5L));
        assertThat(groupService.countGroupedObjects(collectionId, "admin", "role", tags))
            .containsEntry("admin", 5L);
        verify(groupRepo).countGroupedByJsonPath(collectionId, null, "role", tags);
        // ...while a real jsonpath IS forwarded, trimmed, exactly as the search branch trims it.
        groupService.countGroupedObjects(collectionId, pathQuery, "role", tags);
        verify(groupRepo).countGroupedByJsonPath(collectionId, pathQuery.trim(), "role", tags);
    }

    /**
     * {@code jsonUniqueField} is a DOT PATH walked through nested maps, and a record whose path
     * does not resolve is inserted rather than matched.
     *
     * <p>
     * The OIM corpora key on nested identities such as {@code customer.id}, so treating the path as
     * a flat key would resolve nothing, probe with an empty value list, and turn every re-import
     * into a full duplicate of the corpus — while the job reports a perfectly normal object count.
     * That is the same silent-duplication failure the unique field exists to prevent.
     *
     * <p>
     * The two negative cases matter as much as the positive one: a record whose mid-path is not a
     * map, and one missing the path entirely, must be EXCLUDED from the probe rather than
     * contributing a null. A null in the probe list would match nothing and, worse, could collide
     * with another unresolvable record. Note the leaf is stringified, so a numeric id probes as
     * text — which is what the SQL comparison expects.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aNestedUniqueFieldPathIsWalkedThroughMapsAndItsNonStringLeafIsProbedAsText() {
        JsonObjectRepository repo = mock(JsonObjectRepository.class);
        JsonSchemaRepository schemaRepo = mock(JsonSchemaRepository.class);
        JsonSchemaExtractor extractor = mock(JsonSchemaExtractor.class);
        when(extractor.extractSchema(anyList())).thenReturn(Map.of());
        when(schemaRepo.findByCollectionId(any(), any())).thenReturn(Optional.empty());
        when(repo.findIdsByJsonField(any(), anyString(), anyList(), anyList()))
            .thenReturn(Map.of());

        UUID collectionId = UUID.randomUUID();
        JsonObjectService service = new JsonObjectService(repo, schemaRepo, extractor);
        List<Map<String, Object>> objects = List.of(Map.of("customer", Map.of("id", 42)), // nested,
                                                                                          // numeric
                                                                                          // leaf
            Map.of("customer", "not-a-map"), // mid-path is not a map
            Map.of("unrelated", "x")); // path absent entirely

        service.importObjects(collectionId, "coll", objects, "src.json", List.of(), "customer.id");

        ArgumentCaptor<List<String>> probed = ArgumentCaptor.forClass(List.class);
        verify(repo).findIdsByJsonField(eq(collectionId), eq("customer.id"), probed.capture(),
            eq(List.of()));
        assertThat(probed.getValue())
            .as("only the resolvable record is probed, and its numeric leaf as text")
            .containsExactly("42");

        // All three are new, so all three insert — the unresolvable two must not be dropped.
        ArgumentCaptor<List<JsonObject>> inserted = ArgumentCaptor.forClass(List.class);
        verify(repo).saveBatch(inserted.capture());
        assertThat(inserted.getValue()).hasSize(3);
    }
}
