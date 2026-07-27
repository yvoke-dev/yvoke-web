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

    @Test
    public void testPlainTopLevelGroupByStillWorks() {
        when(jsonObjectService.countGroupedObjects(any(), any(), eq("role"), any()))
            .thenReturn(java.util.Map.of("admin", 2L, "user", 5L));

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
    private static java.util.Map<String, Long> groups(int n) {
        java.util.Map<String, Long> m = new java.util.LinkedHashMap<>();
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
