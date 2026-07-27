package de.palsoftware.yvoke.mcp.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.rag.retrieval.HybridSearch;
import de.palsoftware.yvoke.rag.retrieval.HybridSearchResult;
import de.palsoftware.yvoke.rag.retrieval.SearchOptions;
import de.palsoftware.yvoke.rag.retrieval.SearchWithId;
import de.palsoftware.yvoke.rag.retrieval.TelemetryInfo;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

public class SearchCorpusToolTest {

    /** Mirrors app.retrieval.default-limit in application.yml. */
    private static final int DEFAULT_LIMIT = 10;

    private HybridSearch hybridSearch;
    private CollectionService collectionService;
    private SearchCorpusTool searchCorpusTool;

    @BeforeEach
    public void setUp() {
        hybridSearch = mock(HybridSearch.class);
        collectionService = mock(CollectionService.class);
        searchCorpusTool = new SearchCorpusTool(hybridSearch, collectionService, DEFAULT_LIMIT);

        // Setup default mock collections
        Collection colOim = new Collection(UUID.randomUUID(), "OIM", "OIM Collection",
            List.of("9.3"), OffsetDateTime.now());
        Collection colOimDb = new Collection(UUID.randomUUID(), "OIM-DB", "OIM-DB Collection",
            List.of("9.3"), OffsetDateTime.now());
        when(collectionService.listCollections()).thenReturn(List.of(colOim, colOimDb));
    }

    @Test
    public void testSearchCorpusSuccess() {
        UUID docId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        HybridSearchResult mockResult =
            new HybridSearchResult(chunkId, docId, "This is chunk text.", List.of("Ch1", "SecA"),
                "SecA", 2, 10, "9.3", "manual.md", "manual", "OIM", Collections.emptyMap(), 0.95,
                new TelemetryInfo(true, true, 10, 10, 1));

        UUID searchId = UUID.randomUUID();
        when(hybridSearch.searchWithId(eq("test query"), any(SearchOptions.class)))
            .thenReturn(new SearchWithId(List.of(mockResult), searchId));

        String output = searchCorpusTool.searchCorpus("test query", "OIM", "9.3", null, null);

        assertTrue(output.contains("Searched **OIM** (tag=9.3) for: _test query_"));
        assertTrue(output.contains("### manual/manual.md  (score=0.950  id=" + chunkId.toString()
            + "  doc_id=" + docId.toString() + ")"));
        assertTrue(output.contains("> Ch1 > SecA"));
        assertTrue(output.contains("This is chunk text."));
    }

    @Test
    public void testSearchCorpusEmpty() {
        UUID searchId = UUID.randomUUID();
        when(hybridSearch.searchWithId(anyString(), any(SearchOptions.class)))
            .thenReturn(new SearchWithId(Collections.emptyList(), searchId));

        String output = searchCorpusTool.searchCorpus("empty query", "OIM-DB", "9.3", null, null);
        assertTrue(output.contains("(no matching chunks)"));
    }

    @Test
    public void testSearchCorpusTrimsCollectionName() {
        UUID searchId = UUID.randomUUID();
        ArgumentCaptor<SearchOptions> optionsCaptor = ArgumentCaptor.forClass(SearchOptions.class);
        when(hybridSearch.searchWithId(anyString(), optionsCaptor.capture()))
            .thenReturn(new SearchWithId(Collections.emptyList(), searchId));

        searchCorpusTool.searchCorpus("test query", " OIM ", "9.3", null, null);

        SearchOptions captured = optionsCaptor.getValue();
        assertEquals(List.of("OIM"), captured.collections());
    }

    @Test
    public void testSearchCorpusValidationErrorCollectionsMissing() {
        String output = searchCorpusTool.searchCorpus("test query", null, null, null, null);
        assertTrue(output.contains("Error: 'collection' parameter is required."));
    }

    @Test
    public void testSearchCorpusValidationErrorCollectionsEmpty() {
        String output = searchCorpusTool.searchCorpus("test query", "", null, null, null);
        assertTrue(output.contains("Error: 'collection' parameter is required."));
    }

    @Test
    public void testSearchCorpusValidationErrorCollectionDoesNotExist() {
        String output =
            searchCorpusTool.searchCorpus("test query", "NonExistentCollection", null, null, null);
        assertTrue(output.contains("Error: Collection 'NonExistentCollection' does not exist."));
    }

    @Test
    public void testSearchCorpusValidationErrorTagDoesNotExistInSpecifiedCollections() {
        String output =
            searchCorpusTool.searchCorpus("test query", "OIM", "invalid-tag", null, null);
        assertTrue(output.contains("Error: Tag 'invalid-tag' does not exist in collection 'OIM'."));
    }

    @Test
    public void testUntaggedCallOnATagScopedCollectionIsRejected() {
        String output = searchCorpusTool.searchCorpus("test query", "OIM", null, null, null);

        assertTrue(output.startsWith("Error:"), "expected a hard error, got:\n" + output);
        assertTrue(output.contains("tag-scoped"), "expected the reason, got:\n" + output);
        assertTrue(output.contains("9.3"), "expected the valid tags listed, got:\n" + output);
    }


    @Test
    public void testExplicitLimitIsForwardedToTheSearchLayer() {
        // Without an exposed limit the tool always passed null, so HybridSearch fell back to
        // app.retrieval.default-limit (10) and enumeration was impossible: the Release Notes'
        // "Known issues" section is 22 part-chunks / 51 issue IDs, of which 10 came back.
        ArgumentCaptor<SearchOptions> opts = ArgumentCaptor.forClass(SearchOptions.class);
        when(hybridSearch.searchWithId(anyString(), any(SearchOptions.class)))
            .thenReturn(new SearchWithId(List.of(), null));

        searchCorpusTool.searchCorpus("known issues", "OIM", "9.3", 50, null);

        verify(hybridSearch).searchWithId(anyString(), opts.capture());
        assertEquals(50, opts.getValue().limit());
    }

    @Test
    public void testOmittedLimitLeavesTheConfiguredDefaultInEffect() {
        // null (not 0, not 10) — HybridSearch is what applies app.retrieval.default-limit, and
        // hardcoding 10 here would silently fork the default from its single source of truth.
        ArgumentCaptor<SearchOptions> opts = ArgumentCaptor.forClass(SearchOptions.class);
        when(hybridSearch.searchWithId(anyString(), any(SearchOptions.class)))
            .thenReturn(new SearchWithId(List.of(), null));

        searchCorpusTool.searchCorpus("known issues", "OIM", "9.3", null, null);

        verify(hybridSearch).searchWithId(anyString(), opts.capture());
        assertNull(opts.getValue().limit());
    }

}
