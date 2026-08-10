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

    /** Mirrors app.retrieval.max-limit in application.yml. */
    private static final int MAX_LIMIT = 20;

    private HybridSearch hybridSearch;
    private CollectionService collectionService;
    private SearchCorpusTool searchCorpusTool;

    @BeforeEach
    public void setUp() {
        hybridSearch = mock(HybridSearch.class);
        collectionService = mock(CollectionService.class);
        searchCorpusTool =
            new SearchCorpusTool(hybridSearch, collectionService, DEFAULT_LIMIT, MAX_LIMIT);

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

    /**
     * A validated value must never be forwarded in the CALLER's spelling to a case-sensitive query.
     * The collection name is matched with {@code equalsIgnoreCase} and then deliberately replaced
     * by {@code matchedCol.name()} before it reaches {@code ChunkRepository.resolveCollectionIds}
     * ({@code WHERE name IN (:names)}). The tag has no such adoption step: whatever passes the
     * membership check is what goes into {@code :tag = ANY(tags)}, which is case-sensitive. The
     * exact-match {@code List.contains} is therefore the only thing keeping the two halves
     * consistent, and loosening it to {@code equalsIgnoreCase} without also adopting the stored
     * spelling would recreate the "validate leniently, query strictly" failure this codebase has
     * already shipped twice — {@code search_corpus} matching a collection case-insensitively and
     * then resolving zero ids for it, and {@code list_documents} doing the same with {@code kind}.
     *
     * <p>
     * Both of those returned a confident, well-formed "no results" for data that demonstrably
     * exists, which an agent reads as "the corpus does not contain this" and reports to the user as
     * fact. Nothing throws, nothing logs, and the search that produced the emptiness looks entirely
     * normal — so the assertion that actually protects the behaviour is that the search never runs
     * at all, not merely that a string starting {@code Error:} came back.
     *
     * <p>
     * Note the rejection names the offending tag but does NOT enumerate the collection's valid
     * ones, unlike the untagged-read error from {@code McpToolUtils.requireTag}. That is the
     * behaviour as written today, so the assertions pin the rejection and the un-run search rather
     * than a tag list that the message does not contain.
     */
    @Test
    public void aTagDifferingOnlyInCaseIsRejectedRatherThanForwarded() {
        Collection lowercaseTagged = new Collection(UUID.randomUUID(), "OIM", "OIM Collection",
            List.of("v1"), OffsetDateTime.now());
        when(collectionService.listCollections()).thenReturn(List.of(lowercaseTagged));
        when(hybridSearch.searchWithId(anyString(), any(SearchOptions.class)))
            .thenReturn(new SearchWithId(List.of(), UUID.randomUUID()));

        String output = searchCorpusTool.searchCorpus("test query", "OIM", "V1", null, null);

        assertTrue(output.startsWith("Error:"),
            "a tag the collection does not declare must be a hard error, got:\n" + output);
        assertTrue(output.contains("Tag 'V1' does not exist"),
            "the error must quote the offending tag so the agent can correct it, got:\n" + output);
        assertTrue(output.contains("OIM"),
            "the error must name the collection it was checked against, got:\n" + output);
        verify(hybridSearch, never()).searchWithId(anyString(), any(SearchOptions.class));
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

    /**
     * Every {@code search_corpus} call is a one-shot, first-page, reranked, both-lanes search — and
     * all four of those are the tool's own choices, none of them reachable from the MCP input
     * schema, which exposes only {@code query} / {@code collection} / {@code tag} / {@code limit}.
     * That makes them easy to "simplify" away, and every one of them fails invisibly.
     *
     * <p>
     * Turning rerank off costs the ORDERING only: the RRF-fused list comes back as-is, still a
     * well-formed set of plausible chunks with plausible scores, so an answer built on it looks
     * exactly like a good one — and fusion order is precisely where this codebase has already
     * hidden an asymmetry bug for months, because rerank normally re-sorts over it. A non-zero
     * offset would silently drop the highest-scoring hits: the tool has no paging protocol at all,
     * so page 1 is unreachable once you have skipped it and those chunks are simply gone from the
     * answer. And the two lanes are on only because {@code SearchOptions}' compact constructor
     * defaults a null {@code semantic}/{@code fulltext} to true — passing an explicit {@code false}
     * here would halve recall on a corpus where German prose and English identifiers are retrieved
     * by different lanes, with no error and a shorter, entirely credible result.
     */
    @Test
    public void searchCorpusAlwaysRequestsRerankFromTheFirstPage() {
        ArgumentCaptor<SearchOptions> opts = ArgumentCaptor.forClass(SearchOptions.class);
        when(hybridSearch.searchWithId(anyString(), any(SearchOptions.class)))
            .thenReturn(new SearchWithId(List.of(), UUID.randomUUID()));

        searchCorpusTool.searchCorpus("known issues", "OIM", "9.3", null, null);

        verify(hybridSearch).searchWithId(anyString(), opts.capture());
        SearchOptions captured = opts.getValue();
        assertTrue(captured.rerank(),
            "rerank must be forced on — without it only the ordering changes, so the answer still "
                + "looks right");
        assertEquals(0, captured.offset().intValue(),
            "the tool has no paging protocol, so a non-zero offset drops the top hits for good");
        assertTrue(captured.semantic(), "the semantic lane must stay on");
        assertTrue(captured.fulltext(), "the full-text lane must stay on");
    }

    @Test
    public void testCollectionIsForwardedInItsStoredCasingNotTheCallersSpelling() {
        // The tool accepts a collection case-insensitively (equalsIgnoreCase), but
        // ChunkRepository.resolveCollectionIds matches `WHERE name IN (:names)` — case-SENSITIVE.
        // Forwarding the caller's spelling therefore resolves to zero ids and both lanes return
        // empty, so the tool reports the collection exists and then finds nothing in it.
        // Note the codebase has a third, inconsistent rule: CollectionIdResolver uses LOWER(name).
        ArgumentCaptor<SearchOptions> opts = ArgumentCaptor.forClass(SearchOptions.class);
        when(hybridSearch.searchWithId(anyString(), any(SearchOptions.class)))
            .thenReturn(new SearchWithId(List.of(), null));

        searchCorpusTool.searchCorpus("test query", "oim", "9.3", null, null);

        verify(hybridSearch).searchWithId(anyString(), opts.capture());
        assertEquals(List.of("OIM"), opts.getValue().collections(),
            "must forward the matched Collection.name(), not the caller's spelling");
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


    /**
     * SEC-17: an exception from the retrieval layer must NOT reach the model. This tool's output is
     * appended to the conversation and, in orchestrated mode, gets synthesised into the
     * user-visible answer — so a stack detail, SQL fragment or provider status echoed here can be
     * paraphrased straight past the generic error wording. The diagnosis goes to the log only.
     */
    @Test
    public void aFailureInsideTheSearchLayerReturnsTheGenericToolErrorAndLeaksNoDetail() {
        when(hybridSearch.searchWithId(anyString(), any(SearchOptions.class)))
            .thenThrow(new IllegalStateException("ERROR: relation \"chunks_p_9f3\" does not exist; "
                + "jdbc:postgresql://prod-db:5432/oim password=hunter2"));

        String output = searchCorpusTool.searchCorpus("test query", "OIM", "9.3", null, null);

        assertEquals("ERROR: the 'search_corpus' tool failed to complete the request.", output);
        assertFalse(output.contains("chunks_p_9f3"));
        assertFalse(output.contains("jdbc:postgresql"));
        assertFalse(output.contains("hunter2"));
        assertFalse(output.contains("IllegalStateException"));
    }

    /**
     * A full page is indistinguishable from a complete result set unless the tool says so, and an
     * unlabelled full page gets enumerated by the agent as if it were everything — the Release
     * Notes' "Known issues" is 22 part-chunks against a default limit of 10. The note fires on
     * {@code >=} the EFFECTIVE limit (the caller's when supplied, else the configured default), so
     * both branches need covering; a short page must stay unlabelled or the agent chases pages that
     * do not exist.
     */
    @Test
    public void aFullPageIsLabelledAsCappedAndAShortPageIsNot() {
        HybridSearchResult hit = new HybridSearchResult(UUID.randomUUID(), UUID.randomUUID(),
            "text", List.of("A"), "A", 1, 0, "9.3", "f.md", "manual", "OIM", Collections.emptyMap(),
            0.9, new TelemetryInfo(true, true, 1, 1, 1));

        // Exactly the caller's limit -> capped.
        when(hybridSearch.searchWithId(anyString(), any(SearchOptions.class)))
            .thenReturn(new SearchWithId(List.of(hit, hit), UUID.randomUUID()));
        assertTrue(searchCorpusTool.searchCorpus("q", "OIM", "9.3", 2, null)
            .contains("the result is capped"));

        // One short of it -> not capped.
        assertFalse(searchCorpusTool.searchCorpus("q", "OIM", "9.3", 3, null)
            .contains("the result is capped"));

        // No explicit limit -> compared against the configured default, not the caller's.
        assertFalse(searchCorpusTool.searchCorpus("q", "OIM", "9.3", null, null)
            .contains("the result is capped"));
    }

    /**
     * The caller's number is a request, not the limit that ran: {@code HybridSearch} clamps it to
     * {@code app.retrieval.max-limit}. Comparing the returned count against the caller's original
     * figure calls a genuinely truncated page complete — the worst direction for this notice to be
     * wrong in, because the agent then enumerates a capped page as if it were the whole answer.
     */
    @Test
    public void aPageTruncatedByMaxLimitIsStillLabelledAsCapped() {
        HybridSearchResult hit = new HybridSearchResult(UUID.randomUUID(), UUID.randomUUID(),
            "text", List.of("A"), "A", 1, 0, "9.3", "f.md", "manual", "OIM", Collections.emptyMap(),
            0.9, new TelemetryInfo(true, true, 1, 1, 1));

        when(hybridSearch.searchWithId(anyString(), any(SearchOptions.class)))
            .thenReturn(new SearchWithId(Collections.nCopies(MAX_LIMIT, hit), UUID.randomUUID()));

        assertTrue(searchCorpusTool.searchCorpus("q", "OIM", "9.3", 100, null)
            .contains("the result is capped"));
    }
}
