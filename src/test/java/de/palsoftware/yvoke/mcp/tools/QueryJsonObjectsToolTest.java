package de.palsoftware.yvoke.mcp.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.jsonobject.core.model.JsonObject;
import de.palsoftware.yvoke.jsonobject.core.repository.JsonObjectRepository;
import de.palsoftware.yvoke.jsonobject.core.service.JsonObjectService;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class QueryJsonObjectsToolTest {

    private JsonObjectService jsonObjectService;
    private CollectionService collectionService;
    private ObjectMapper objectMapper;
    private QueryJsonObjectsTool queryJsonObjectsTool;

    @BeforeEach
    public void setUp() {
        jsonObjectService = mock(JsonObjectService.class);
        collectionService = mock(CollectionService.class);
        objectMapper = new ObjectMapper();
        queryJsonObjectsTool =
            new QueryJsonObjectsTool(jsonObjectService, collectionService, objectMapper);

        Collection col1 = new Collection(UUID.randomUUID(), "JSON-Col-1", "JSON Col 1",
            List.of("v1", "v2"), null);
        Collection col2 =
            new Collection(UUID.randomUUID(), "JSON-Col-2", "JSON Col 2", List.of("v1"), null);
        when(collectionService.listCollections()).thenReturn(List.of(col1, col2));
    }

    @Test
    public void testQueryJsonObjectsSuccess() {
        UUID objId = UUID.randomUUID();
        JsonObject obj = new JsonObject(objId, UUID.randomUUID(), "JSON-Col-1",
            Map.of("role", "admin"), "user.json", OffsetDateTime.now());
        when(jsonObjectService.listObjectsByOffset(any(), any(), anyInt(), anyInt()))
            .thenReturn(List.of(obj));

        String response = queryJsonObjectsTool.queryJsonObjects("JSON-Col-1", null, "v1", "role",
            null, null, null, null);
        assertTrue(response.contains("Found 1 JSON objects in collection JSON-Col-1"));
        assertTrue(response.contains("collection"));
        assertTrue(response.contains("JSON-Col-1"));
    }

    /**
     * A projected field path that resolves to nothing must render as an EMPTY CELL, and the call as
     * a whole must still succeed. {@code fields} is model-authored: the agent reads a field list
     * out of {@code get_json_schema} and re-types it, so a wrong path is a routine event — and the
     * declaration it copies from is frequently stale against the data (a frozen {@code imported}
     * schema row declared 4 of the 10 {@code via} values that actually existed). Turning a miss
     * into an error marker, or into a throw, means one mistyped column destroys a 500-row answer
     * that was otherwise entirely correct: the model receives
     * {@code "ERROR: the 'query_json_objects' tool
     * failed…"} with nothing to indicate that only one of its columns was wrong, and its repair
     * move is to abandon the query rather than to drop that column.
     *
     * <p>
     * The blank column is a deliberate trade, not an accident — the cost of it is that a typo is
     * silent — and that is exactly why it needs pinning: no other test in this file projects a path
     * that misses, so a "return something diagnostic instead of an empty string" refactor of
     * {@code getNestedValue} would pass the entire suite while changing what every caller sees.
     */
    @Test
    public void aMistypedProjectionFieldRendersAnEmptyCellRatherThanFailingTheCall() {
        JsonObject obj = new JsonObject(UUID.randomUUID(), UUID.randomUUID(), "JSON-Col-1",
            Map.<String, Object>of("customer", Map.of("name", "ACME")), "customers.json",
            OffsetDateTime.now());
        when(jsonObjectService.listObjectsByOffset(any(), any(), anyInt(), anyInt()))
            .thenReturn(List.of(obj));

        String output = queryJsonObjectsTool.queryJsonObjects("JSON-Col-1", null, "v1",
            "customer.name,customer.missing", null, null, null, null);

        assertTrue(output.startsWith("Found 1 JSON objects"),
            "one unresolvable path must not fail the whole call:\n" + output);
        assertFalse(output.contains("Error"),
            "a path that resolves to nothing must not become an error marker:\n" + output);
        assertTrue(output.contains("| customer.name | customer.missing |"),
            "the mistyped path must still get its own column:\n" + output);
        assertTrue(output.contains("| ACME |  |"),
            "expected the resolved value followed by an empty cell:\n" + output);
    }

    /**
     * §12.12: a {@code *} inside a projection segment exists because JSON corpora do not have
     * uniform key casing — an exported record set routinely carries {@code addrHome} beside
     * {@code AddrWork}, and the agent has no way to know which spelling a given row used. That is
     * the whole reason the match is case-insensitive: a wildcard that only matched the casing the
     * agent happened to type would return HALF the keys and render a cell that looks complete,
     * because a projection cell shows what it found and has no way to say what it missed. The agent
     * then reports a partial dataset as the dataset, which on this corpus reads as "the data does
     * not exist" — the same silent-empty failure shape as the collection/kind case-matching bugs.
     * Re-serialising the matches as JSON (rather than emitting one value) is the other half: the
     * cell has to name WHICH keys matched, or a two-key hit is indistinguishable from a one-key
     * hit.
     *
     * <p>
     * Dropping the {@code (?i)} is invisible to every other test here: they all project plain
     * single-segment paths ({@code role}, {@code index}), so the wildcard branch of
     * {@code extractPath} is never entered at all and the whole file stays green.
     */
    @Test
    public void aWildcardProjectionSegmentMatchesKeysCaseInsensitivelyAndSerialisesTheMatches() {
        // The umlaut is written as a unicode escape in BOTH the fixture and the expectation rather
        // than pasted, so the two cannot be desynchronised by an encoding-unaware edit.
        Map<String, Object> data =
            Map.of("addrHome", Map.of("city", "Bonn"), "AddrWork", Map.of("city", "Köln"));
        JsonObject obj = new JsonObject(UUID.randomUUID(), UUID.randomUUID(), "JSON-Col-1", data,
            "people.json", OffsetDateTime.now());
        when(jsonObjectService.listObjectsByOffset(any(), any(), anyInt(), anyInt()))
            .thenReturn(List.of(obj));

        String output = queryJsonObjectsTool.queryJsonObjects("JSON-Col-1", null, "v1",
            "addr*.city", null, null, null, null);

        assertTrue(output.contains("Found 1 JSON objects"),
            "expected the single row to be rendered, got:\n" + output);
        assertTrue(output.contains("| addr*.city |"),
            "the requested path is the column header:\n" + output);
        assertTrue(output.contains("addrHome") && output.contains("Bonn"),
            "the exactly-cased key must match:\n" + output);
        assertTrue(output.contains("AddrWork"),
            "a differently-cased key must match too, and the cell must name it:\n" + output);
        assertTrue(output.contains("Köln"),
            "the differently-cased key's value must reach the caller:\n" + output);
    }

    @Test
    public void testQueryJsonObjectsCollectionDoesNotExist() {
        String response = queryJsonObjectsTool.queryJsonObjects("Nonexistent-Col", null, null,
            "role", null, null, null, null);
        assertTrue(response.contains("Error: Collection 'Nonexistent-Col' does not exist."));
    }

    @Test
    public void testQueryJsonObjectsTagDoesNotExist() {
        String response = queryJsonObjectsTool.queryJsonObjects("JSON-Col-1", null, "invalid-tag",
            "role", null, null, null, null);
        assertTrue(response
            .contains("Error: Tag 'invalid-tag' does not exist in collection 'JSON-Col-1'."));
    }

    @Test
    public void testQueryJsonObjectsSuccessWithMultipleCollections() {
        String response = queryJsonObjectsTool.queryJsonObjects("JSON-Col-1,JSON-Col-2", null, "v1",
            "role", null, null, null, null);
        assertTrue(response.contains("Error: Collection 'JSON-Col-1,JSON-Col-2' does not exist."));
    }

    /**
     * Three properties of the ROW-LISTING path that no test in this file pins, all of them things
     * an agent acts on directly.
     *
     * <p>
     * (1) {@code groupBy} belongs to the {@code countOnly} path alone — its whole validation (the
     * top-level-key charset check, the jsonpath-filter check) lives inside the {@code isCountOnly}
     * branch. Every one of the five groupBy tests here passes {@code countOnly=true}, so if the
     * listing path ever started honouring it, an unvalidated key would reach {@code data->>'…'} on
     * a path where none of those guards run, and a caller who asked for ROWS would silently get a
     * grouped table instead.
     *
     * <p>
     * (2) The next-page hint must appear only when the page came back exactly full. The two paging
     * tests here return exactly {@code limit} rows, so the short-page branch is never taken: emit
     * the hint unconditionally and an agent pages forever past the end of the data, burning one
     * tool call per empty page and eventually giving up on a query that had already succeeded.
     *
     * <p>
     * (3) An empty page is an ordinary result, not a failure. The literal "No JSON objects found"
     * appears in no test source in this repository, yet the distinction is decisive for the caller:
     * a string starting with "Error:" is read as a broken call, and the model's repair move is to
     * abandon the query rather than widen the filter.
     */
    @Test
    public void aListingIgnoresGroupByAndOnlyHintsAtANextPageWhenThePageIsExactlyFull() {
        UUID colId = UUID.randomUUID();

        // (1)+(2): a short page, requested with a groupBy that the countOnly path would REJECT
        // outright ("Customer.name" is nested). On the listing path groupBy must simply be ignored.
        when(jsonObjectService.listObjectsByOffset(any(), any(), anyInt(), anyInt()))
            .thenReturn(List.of(new JsonObject(UUID.randomUUID(), colId, "JSON-Col-1",
                Map.of("role", "admin"), "users.json", OffsetDateTime.now())));

        String shortPage = queryJsonObjectsTool.queryJsonObjects("JSON-Col-1", null, "v1", "role",
            5, 0, null, "Customer.name");

        assertTrue(shortPage.startsWith("Found 1 JSON objects"),
            "a listing must stay a listing when groupBy is passed, got:\n" + shortPage);
        assertFalse(shortPage.contains("grouped by"),
            "groupBy must be ignored outside countOnly, got:\n" + shortPage);
        verify(jsonObjectService, never()).countGroupedObjects(any(), any(), anyString(), any());
        assertFalse(shortPage.contains("To fetch the next page"),
            "1 row of a 5-row page is the end of the data; advising another page sends the agent "
                + "paging forever:\n" + shortPage);
        assertTrue(shortPage.contains("(reached the end of results)"),
            "a short page must say so explicitly:\n" + shortPage);

        // (3): the exactly-full page is the ONLY case that may advise a next offset, and it must
        // advise off+lim rather than lim.
        List<JsonObject> full = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            full.add(new JsonObject(UUID.randomUUID(), colId, "JSON-Col-1", Map.of("role", "admin"),
                "users.json", OffsetDateTime.now()));
        }
        when(jsonObjectService.listObjectsByOffset(any(), any(), anyInt(), anyInt()))
            .thenReturn(full);

        String fullPage = queryJsonObjectsTool.queryJsonObjects("JSON-Col-1", null, "v1", "role", 5,
            10, null, null);

        assertTrue(fullPage.contains("To fetch the next page, use offset=15"),
            "an exactly-full page must advise offset+limit:\n" + fullPage);
        assertFalse(fullPage.contains("reached the end of results"),
            "a full page is not the end of the data:\n" + fullPage);

        // (4): an empty page is an ordinary empty result, never an "Error:".
        when(jsonObjectService.listObjectsByOffset(any(), any(), anyInt(), anyInt()))
            .thenReturn(List.of());

        String empty = queryJsonObjectsTool.queryJsonObjects("JSON-Col-1", null, "v1", "role", 5,
            50, null, null);

        assertFalse(empty.startsWith("Error:"),
            "an empty result must not read as a failed call:\n" + empty);
        assertTrue(
            empty.contains(
                "No JSON objects found matching the criteria in collection " + "JSON-Col-1"),
            "expected the plain empty-result wording, got:\n" + empty);
        assertFalse(empty.contains("To fetch the next page"),
            "there is nothing after an empty page:\n" + empty);
    }

    @Test
    public void testQueryJsonObjectsPaginationInstructions() {
        List<JsonObject> objs = new ArrayList<>();
        UUID colId = UUID.randomUUID();
        for (int i = 0; i < 20; i++) {
            objs.add(new JsonObject(UUID.randomUUID(), colId, "JSON-Col-1", Map.of("index", i),
                "data.json", OffsetDateTime.now()));
        }
        when(jsonObjectService.listObjectsByOffset(any(), any(), anyInt(), anyInt()))
            .thenReturn(objs);

        String response = queryJsonObjectsTool.queryJsonObjects("JSON-Col-1", null, "v1", "index",
            20, 0, null, null);
        assertTrue(response.contains("To fetch the next page, use offset=20"));
    }

    @Test
    public void testQueryJsonObjectsDefaultLimitInstructions() {
        List<JsonObject> objs = new ArrayList<>();
        UUID colId = UUID.randomUUID();
        for (int i = 0; i < 500; i++) {
            objs.add(new JsonObject(UUID.randomUUID(), colId, "JSON-Col-1", Map.of("index", i),
                "data.json", OffsetDateTime.now()));
        }
        when(jsonObjectService.listObjectsByOffset(any(), any(), anyInt(), anyInt()))
            .thenReturn(objs);

        String response = queryJsonObjectsTool.queryJsonObjects("JSON-Col-1", null, "v1", "index",
            null, 0, null, null);
        assertTrue(response.contains("To fetch the next page, use offset=500"));
    }

    @Test
    public void testQueryJsonObjectsFieldsMissing() {
        String response = queryJsonObjectsTool.queryJsonObjects("JSON-Col-1", null, "v1", null,
            null, null, null, null);
        assertTrue(response.contains("Error: 'fields' parameter is required."));
    }

    @Test
    public void testUntaggedCallOnATagScopedCollectionIsRejected() {
        String output = queryJsonObjectsTool.queryJsonObjects("JSON-Col-1", null, null, "role",
            null, null, null, null);

        assertTrue(output.startsWith("Error:"), "expected a hard error, got:\n" + output);
        assertTrue(output.contains("tag-scoped"));
        assertTrue(output.contains("v1") && output.contains("v2"));
    }


    @Test
    public void testNestedGroupByIsRejectedInsteadOfSilentlyMangled() {
        // The repository strips everything outside [a-zA-Z0-9_-] and concatenates the survivor into
        // data->>'…'. "Customer.name" became "Customername", a key no row has, so GROUP BY returned
        // ONE (null) bucket whose count equalled the whole collection — a well-formed table that
        // looks like a real breakdown.
        String output = queryJsonObjectsTool.queryJsonObjects("JSON-Col-1", null, "v1", null, null,
            null, true, "Customer.name");

        assertTrue(output.startsWith("Error:"), "expected a hard error, got:\n" + output);
        assertTrue(output.contains("Customer.name"),
            "error must quote the offending value:\n" + output);
        assertTrue(output.contains("top-level"), "error must say why:\n" + output);
        // Must never reach the DB with a mangled key.
        verify(jsonObjectService, never()).countGroupedObjects(any(), any(), anyString(), any());
    }

    @Test
    public void testGroupByWithSpacesIsRejected() {
        String output = queryJsonObjectsTool.queryJsonObjects("JSON-Col-1", null, "v1", null, null,
            null, true, "Server Edition");

        assertTrue(output.startsWith("Error:"), "expected a hard error, got:\n" + output);
        verify(jsonObjectService, never()).countGroupedObjects(any(), any(), anyString(), any());
    }

    /**
     * A grouped count must never present itself as filtered when the filter was not applied.
     * {@code JsonObjectService.countGroupedObjects} keeps the filter only when it starts with
     * {@code $} and otherwise passes {@code path = null}, so a free-text filter silently counted
     * the WHOLE collection while the header still quoted the filter — the caller reads a
     * collection-wide breakdown as a filtered one, with nothing indicating the difference. (The
     * ungrouped path has no such hole: it falls back to {@code countSearch}.)
     */
    @Test
    public void aNonJsonpathFilterIsNotSilentlyDroppedFromAGroupedCount() {
        when(jsonObjectService.countGroupedObjects(any(), any(), eq("role"), any()))
            .thenReturn(Map.of("admin", 2L, "user", 5L));

        String output = queryJsonObjectsTool.queryJsonObjects("JSON-Col-1", "ADS", "v1", null, null,
            null, true, "role");

        assertTrue(output.startsWith("Error:"),
            "a non-jsonpath filter must be rejected rather than dropped, got:\n" + output);
        assertTrue(output.contains("ADS"), "the error must quote the offending filter:\n" + output);
        verify(jsonObjectService, never()).countGroupedObjects(any(), any(), anyString(), any());
    }

    @Test
    public void testPlainTopLevelGroupByStillWorks() {
        when(jsonObjectService.countGroupedObjects(any(), any(), eq("role"), any()))
            .thenReturn(Map.of("admin", 2L, "user", 5L));

        String output = queryJsonObjectsTool.queryJsonObjects("JSON-Col-1", null, "v1", null, null,
            null, true, "role");

        assertFalse(output.startsWith("Error:"), "a valid groupBy was rejected:\n" + output);
        assertTrue(output.contains("grouped by role"));
        assertTrue(output.contains("admin"));
    }


    @Test
    public void testUnfilteredOffsetIsHonouredExactlyNotRoundedToAPage() {
        // The unfiltered branch used to pass `off / lim` to a PAGE-based method, so integer
        // division rounded any non-multiple offset down: offset=250,limit=500 -> page 0 -> rows
        // 0-499. The tool then advised offset=750, skipping rows 500-749 entirely.
        when(jsonObjectService.listObjectsByOffset(any(), any(), anyInt(), anyInt()))
            .thenReturn(List.of());

        queryJsonObjectsTool.queryJsonObjects("JSON-Col-1", null, "v1", "role", 500, 250, null,
            null);

        verify(jsonObjectService).listObjectsByOffset(any(), any(), eq(500), eq(250));
    }

    @Test
    public void testUnfilteredOffsetOnAnExactMultipleStillWorks() {
        // Guards against "fixing" this by swapping the arguments: 500/500 == 1 == page 1, which
        // also happens to be offset 500, so an exact multiple cannot distinguish the two.
        when(jsonObjectService.listObjectsByOffset(any(), any(), anyInt(), anyInt()))
            .thenReturn(List.of());

        queryJsonObjectsTool.queryJsonObjects("JSON-Col-1", null, "v1", "role", 500, 500, null,
            null);

        verify(jsonObjectService).listObjectsByOffset(any(), any(), eq(500), eq(500));
    }

    @Test
    public void testFilteredPathStillPassesOffsetThrough() {
        when(jsonObjectService.queryObjects(any(), any(), any(), anyInt(), anyInt()))
            .thenReturn(List.of());

        queryJsonObjectsTool.queryJsonObjects("JSON-Col-1", "$.role", "v1", "role", 500, 250, null,
            null);

        verify(jsonObjectService).queryObjects(any(), eq("$.role"), any(), eq(500), eq(250));
    }


    /** n groups, descending by count, as the repository orders them. */
    private static Map<String, Long> groups(int n) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            m.put("g" + i, (long) (n - i));
        }
        return m;
    }

    @Test
    public void testGroupedCountsAboveTheCapAreTrimmedAndLabelled() {
        // 'name' on the DB-History content tag has 4,008 distinct values and is a perfectly valid
        // top-level key, so it passes groupBy validation and used to render as one ~4,000-row
        // table.
        when(jsonObjectService.countGroupedObjects(any(), any(), eq("name"), any()))
            .thenReturn(groups(JsonObjectRepository.MAX_GROUPS + 1));

        String output = queryJsonObjectsTool.queryJsonObjects("JSON-Col-1", null, "v1", null, null,
            null, true, "name");

        assertTrue(output.contains("top " + JsonObjectRepository.MAX_GROUPS),
            "expected a truncation label, got the first 300 chars:\n" + output.substring(0, 300));
        assertTrue(output.contains("more groups exist"),
            "expected the caller to be told there are more, got:\n" + output.substring(0, 300));
        // Trimmed to the cap: the (cap+1)-th group must not be rendered.
        assertFalse(output.contains("| g" + JsonObjectRepository.MAX_GROUPS + " |"),
            "the extra probe row leaked into the table");
    }

    @Test
    public void testGroupedCountsBelowTheCapAreNotLabelledAsTruncated() {
        when(jsonObjectService.countGroupedObjects(any(), any(), eq("op"), any()))
            .thenReturn(groups(6));

        String output = queryJsonObjectsTool.queryJsonObjects("JSON-Col-1", null, "v1", null, null,
            null, true, "op");

        assertFalse(output.contains("more groups exist"),
            "a complete grouping was labelled truncated:\n" + output);
        assertTrue(output.contains("g0"));
    }

}
