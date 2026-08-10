package de.palsoftware.yvoke.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import de.palsoftware.yvoke.document.core.repository.ChunkRepository;
import de.palsoftware.yvoke.document.core.model.ChunkRow;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

public class HybridSearchTest {

    private ChunkRepository chunkRepository;
    private EmbeddingService embeddingService;
    private RerankClient rerankClient;
    private RrfFuser rrfFuser;
    private RetrievalTelemetryService telemetryService;
    private JdbcClient jdbcClient;
    private HybridSearch hybridSearch;

    @SuppressWarnings("unchecked")
    @BeforeEach
    public void setUp() {
        chunkRepository = mock(ChunkRepository.class);
        embeddingService = mock(EmbeddingService.class);
        rerankClient = mock(RerankClient.class);
        rrfFuser = mock(RrfFuser.class);
        telemetryService = mock(RetrievalTelemetryService.class);
        jdbcClient = mock(JdbcClient.class);

        JdbcClient.StatementSpec mockSpec = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<Object> mockMqs = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbcClient.sql(anyString())).thenReturn(mockSpec);
        when(mockSpec.param(anyString(), any())).thenReturn(mockSpec);
        when(mockSpec.query(any(Class.class))).thenReturn(mockMqs);
        when(mockMqs.list()).thenReturn(Collections.emptyList());

        hybridSearch = new HybridSearch(chunkRepository, embeddingService, rerankClient, rrfFuser,
            telemetryService, jdbcClient, 8, // defaultLimit
            20, // maxLimit
            2.0, // semanticLimitMultiplier
            3.0 // fulltextLimitMultiplier

        );
    }

    /**
     * {@code app.retrieval.max-limit} is the ceiling on rows returned, and it has to bite at the
     * one place every lane path funnels through — otherwise a caller naming a large limit sizes the
     * pools from it and hands the reranker a request big enough to be rejected, which degrades
     * silently to unreranked RRF order rather than failing.
     */
    @Test
    public void aRequestedLimitAboveMaxLimitIsClampedBeforeItReachesTheRepository() {
        SearchOptions opts = new SearchOptions("OIM", 50, true, false, "1.0", 0);
        when(embeddingService.embed("q")).thenReturn(new float[] {0.1f});
        when(chunkRepository.findSemanticCandidates(anyString(), anyInt(), anyInt(), anyList(),
            anyList(), any())).thenReturn(Collections.emptyList());

        hybridSearch.search("q", opts);

        verify(chunkRepository).findSemanticCandidates(anyString(), eq(20), eq(0), anyList(),
            anyList(), any());
    }

    /**
     * {@code app.retrieval.default-limit} is the number of chunks a caller who names no
     * {@code limit} gets, and this is the only place on the search path that reads it.
     * {@code SearchCorpusTool} reads the SAME property for a different job — it computes
     * {@code min(defaultLimit, maxLimit)} and appends the "the result is capped and more may match"
     * notice when the page comes back that full — so both must derive the same number from the same
     * property. Replace the fallback here with a literal (the tempting "it is 10 anyway"
     * simplification) and at any configured value other than 10 the search returns one count while
     * the tool judges truncation against another: a genuinely capped page is announced as complete,
     * and an agent enumerates it as if it were the whole answer. Nothing throws, nothing logs.
     *
     * <p>
     * That mismatch is invisible in production precisely because {@code application.yml} sets
     * {@code default-limit: 10}, which is the same number {@link SearchOptions} already hardcodes
     * as its floor — property and literal coincide, so no environment and no existing test can tell
     * them apart. This harness deliberately injects {@code defaultLimit = 8} /
     * {@code maxLimit = 20} so that they are distinguishable at all, and the search runs
     * semantic-only because that lane passes {@code opts.limit()} to the repository untouched (the
     * hybrid path would multiply it by a pool multiplier first).
     *
     * <p>
     * The second half pins the caveat, which contradicts {@code SearchCorpusTool}'s own javadoc
     * ("{@code SearchOptions} floors it at 1"): the record's compact constructor rewrites a zero or
     * negative limit to a HARDCODED 10 before {@code HybridSearch} ever sees it, so
     * {@code opts.limit()} is already positive and the configured default is never consulted.
     * {@code app.retrieval.default-limit} therefore governs a NULL limit only.
     * {@code SearchOptionsTest} pins the record's rewrite in isolation; what is asserted here is
     * the consequence — which number actually reaches the repository — and nothing covers that,
     * because every other case in this class passes an explicit positive limit. If the record is
     * ever deliberately changed to route a non-positive limit to the configured default too, this
     * is the assertion that records the decision.
     */
    @Test
    public void aNullLimitFallsBackToTheConfiguredDefaultLimitRatherThanAHardcodedTen() {
        when(embeddingService.embed("q")).thenReturn(new float[] {0.1f});
        when(chunkRepository.findSemanticCandidates(anyString(), anyInt(), anyInt(), anyList(),
            anyList(), any())).thenReturn(Collections.emptyList());

        hybridSearch.search("q", new SearchOptions("OIM", null, true, false, "1.0", 0));

        // 8 is the injected app.retrieval.default-limit, deliberately NOT the 10 that both
        // application.yml and SearchOptions happen to use.
        verify(chunkRepository).findSemanticCandidates(anyString(), eq(8), eq(0), anyList(),
            anyList(), any());

        // A non-positive limit never reaches that fallback: SearchOptions has already rewritten it
        // to its own hardcoded 10, so the configured default is not what runs.
        SearchOptions zero = new SearchOptions("OIM", 0, true, false, "1.0", 0);
        assertThat(zero.limit()).as("the record floors a non-positive limit at 10, not at 1")
            .isEqualTo(10);

        hybridSearch.search("q", zero);

        verify(chunkRepository).findSemanticCandidates(anyString(), eq(10), eq(0), anyList(),
            anyList(), any());
    }

    /**
     * A fixed BM25 cap stops being an over-fetch exactly when it matters most: at a page size of 30
     * the old cap of 30 made the pool equal to the page, so the reranker had nothing to choose
     * from, and above that the pool was smaller than the page. The pool must scale with the request
     * the way the semantic lane already does.
     */
    @Test
    public void theFullTextPoolScalesWithTheRequestedLimitInsteadOfAFixedCap() {
        SearchOptions opts = new SearchOptions("OIM", 5, true, true, "1.0", 0);
        when(embeddingService.embed("q")).thenReturn(new float[] {0.1f});
        when(chunkRepository.findSemanticCandidates(anyString(), anyInt(), anyInt(), anyList(),
            anyList(), any())).thenReturn(Collections.emptyList());
        when(chunkRepository.findFulltextCandidates(anyString(), anyInt(), anyInt(), anyList(),
            anyList(), any())).thenReturn(Collections.emptyList());
        when(rrfFuser.fuse(anyList(), anyList())).thenReturn(new ArrayList<>());

        hybridSearch.search("q", opts);

        // 3.0 x (limit 5 + offset 0)
        verify(chunkRepository).findFulltextCandidates(anyString(), eq(15), eq(0), anyList(),
            anyList(), any());
        // 2.0 x (limit 5 + offset 0) — the semantic lane keeps its own multiplier
        verify(chunkRepository).findSemanticCandidates(anyString(), eq(10), eq(0), anyList(),
            anyList(), any());
    }

    /**
     * S4.2. A blank submission is not a search — the empty box, a stray Enter, a whitespace-only
     * paste — and it has to cost nothing and leave no trace. Both halves are load-bearing. The
     * embedding is a paid network round-trip on every blank Enter, the cheapest possible way to
     * burn Voyage quota. The telemetry row is worse: {@code /admin/logs} and every retrieval figure
     * derived from {@code retrieval_logs} would count empty-query rows as real searches, quietly
     * poisoning the numbers an operator uses to judge the corpus.
     *
     * <p>
     * Nothing catches either today. {@code testSearchInvalidQueryOrOptions} and
     * {@code HybridSearchIT.testEmptyQuerySearch} assert only that the returned list is empty —
     * which stays true if the guard slides below the embed call, because the lanes then simply find
     * nothing. The whole regression is invisible in the result and visible only in the bill and in
     * the log table, so it is asserted here on the collaborators rather than on the rows.
     *
     * <p>
     * Every collaborator is stubbed to SUCCEED, deliberately: a guard that moved would complete the
     * search normally and hand back the same empty list, so this fails on the interaction it is
     * about and not on an incidental NPE.
     *
     * <p>
     * The fresh {@code searchId} is pinned too — it is the handle the admin console reads a search
     * back by, so a fixed or null id for the blank case would collide every blank submission onto
     * one identity.
     */
    @Test
    public void aBlankQueryCostsNoEmbeddingCallAndWritesNoTelemetryRow() {
        SearchOptions opts = new SearchOptions("OIM", 5, true, true, "1.0", 0);
        when(embeddingService.embed(anyString())).thenReturn(new float[] {0.1f});
        when(chunkRepository.findSemanticCandidates(anyString(), anyInt(), anyInt(), anyList(),
            anyList(), any())).thenReturn(Collections.emptyList());
        when(chunkRepository.findFulltextCandidates(anyString(), anyInt(), anyInt(), anyList(),
            anyList(), any())).thenReturn(Collections.emptyList());
        when(rrfFuser.fuse(anyList(), anyList())).thenReturn(new ArrayList<>());

        SearchWithId empty = hybridSearch.searchWithId("", opts);
        SearchWithId whitespace = hybridSearch.searchWithId("   \n\t ", opts);
        SearchWithId nullQuery = hybridSearch.searchWithId(null, opts);

        assertThat(empty.results()).isEmpty();
        assertThat(whitespace.results()).isEmpty();
        assertThat(nullQuery.results()).isEmpty();

        verify(embeddingService, never()).embed(anyString());
        verify(telemetryService, never()).logAndPersistTelemetry(any(), anyString(),
            any(SearchOptions.class), any(), any(), anyList(), anyBoolean(), anyList(), anyList(),
            anyList());

        assertThat(empty.searchId()).as("the admin console reads a search back by this id")
            .isNotNull();
        assertThat(whitespace.searchId()).isNotEqualTo(empty.searchId());
    }

    @Test
    public void testSearchInvalidQueryOrOptions() {
        SearchOptions opts = new SearchOptions("OIM", 5, true, true, "1.0", 0);

        // Blank query returns empty list immediately
        assertThat(hybridSearch.search("", opts)).isEmpty();
        assertThat(hybridSearch.search(null, opts)).isEmpty();

        // Both semantic and full-text disabled throws IllegalArgumentException
        SearchOptions disabledOpts = new SearchOptions("OIM", 5, false, false, "1.0", 0);
        assertThatThrownBy(() -> hybridSearch.search("query", disabledOpts))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * S4.6 + S4.10. The two single-lane paths are the ones with no fusion step, and that changes
     * two things at once: the page is Postgres's job (limit/offset go straight into the SQL, so
     * there is nothing to over-fetch and nothing to slice in memory) and there is no candidate pool
     * for a reranker to reorder, so the reranker must not run at all — even when the caller asked
     * for it, which every caller that does not say otherwise does, because {@link SearchOptions}
     * defaults {@code rerank} to true.
     *
     * <p>
     * Both halves fail silently. Reranking a single lane POSTs every candidate's full text to
     * Voyage on every semantic-only or BM25-only search — real money and real latency on a path
     * whose whole point is to be the cheap one — and the results still come back, merely reordered,
     * so nothing looks wrong. Over-fetching and slicing in memory (or dropping the offset) makes
     * page 2 of a single-lane search repeat or skip rows, again with no error.
     *
     * <p>
     * Neither is covered. There is no {@code verify(rerankClient, never())} anywhere in the suite,
     * and the ITs that pass {@code rerank=false} stub the client to THROW — which
     * {@code applyReranking} swallows into an RRF fallback — so a leaked rerank call would look
     * identical to no rerank call at all. And no single-lane test has ever passed a non-zero
     * offset: this one asks for offset 3 on both lanes, asserts the value reaches the repository
     * untouched, and asserts the row comes back ranked 4 rather than 1, which is the observable
     * consequence of the page having been taken by the database.
     */
    @Test
    public void aSingleLanePagesInPostgresAndNeverReranks() {
        ChunkRow row = new ChunkRow(UUID.randomUUID(), UUID.randomUUID(), "page two row", null,
            null, 1, 0, "1.0", "f.md", "manual", "OIM", Collections.emptyMap(), 0.9);
        when(embeddingService.embed("database")).thenReturn(new float[] {0.1f});
        when(chunkRepository.findSemanticCandidates(anyString(), anyInt(), anyInt(), anyList(),
            anyList(), any())).thenReturn(List.of(row));
        when(chunkRepository.findFulltextCandidates(anyString(), anyInt(), anyInt(), anyList(),
            anyList(), any())).thenReturn(List.of(row));

        // rerank is explicitly ON, which on a single lane must still mean no rerank request.
        List<HybridSearchResult> semantic = hybridSearch.search("database",
            new SearchOptions("OIM", 5, true, false, "1.0", 3, true));
        List<HybridSearchResult> fulltext = hybridSearch.search("database",
            new SearchOptions("OIM", 5, false, true, "1.0", 3, true));

        // The page is taken by Postgres: the requested limit and offset arrive unmodified.
        verify(chunkRepository).findSemanticCandidates(anyString(), eq(5), eq(3), anyList(),
            anyList(), any());
        verify(chunkRepository).findFulltextCandidates(eq("database"), eq(5), eq(3), anyList(),
            anyList(), any());

        // An over-fetch-then-slice would drop this row off the page entirely.
        assertThat(semantic).hasSize(1);
        assertThat(fulltext).hasSize(1);
        assertThat(semantic.get(0).telemetry().rrfRank())
            .as("rank is the row's position in the whole result set, not within the page")
            .isEqualTo(4);
        assertThat(fulltext.get(0).telemetry().rrfRank()).isEqualTo(4);

        verify(rerankClient, never()).rerank(anyString(), anyList());
    }

    @Test
    public void testExecuteSemanticSearchOnly() {
        SearchOptions opts = new SearchOptions("OIM", 5, true, false, "1.0", 0);
        when(embeddingService.embed("database")).thenReturn(new float[] {0.1f});

        UUID id = UUID.randomUUID();
        ChunkRow row = new ChunkRow(id, UUID.randomUUID(), "database connection", null, null, 1, 0,
            "1.0", "f.md", "manual", "OIM", Collections.emptyMap(), 0.99);
        when(chunkRepository.findSemanticCandidates(anyString(), anyInt(), anyInt(),
            eq(List.of("1.0")), eq(List.of("OIM")), any())).thenReturn(List.of(row));

        List<HybridSearchResult> results = hybridSearch.search("database", opts);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo(id);
        assertThat(results.get(0).telemetry().inSem()).isTrue();
        assertThat(results.get(0).telemetry().inFt()).isFalse();

        verify(telemetryService).logAndPersistTelemetry(any(UUID.class), eq("database"), eq(opts),
            eq(1), anyInt(), anyList(), eq(false), anyList(), isNull(), isNull());
    }

    @Test
    public void testExecuteFulltextSearchOnly() {
        SearchOptions opts = new SearchOptions("OIM", 5, false, true, "1.0", 0);

        UUID id = UUID.randomUUID();
        ChunkRow row = new ChunkRow(id, UUID.randomUUID(), "active directory", null, null, 1, 0,
            "1.0", "f.md", "manual", "OIM", Collections.emptyMap(), 5.2);
        when(chunkRepository.findFulltextCandidates(eq("directory"), anyInt(), anyInt(),
            eq(List.of("1.0")), eq(List.of("OIM")), any())).thenReturn(List.of(row));

        List<HybridSearchResult> results = hybridSearch.search("directory", opts);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo(id);
        assertThat(results.get(0).telemetry().inSem()).isFalse();
        assertThat(results.get(0).telemetry().inFt()).isTrue();

        verify(telemetryService).logAndPersistTelemetry(any(UUID.class), eq("directory"), eq(opts),
            anyInt(), eq(1), anyList(), eq(false), anyList(), isNull(), isNull());
    }

    /**
     * S4.10. {@code opts.rerank()} is the one knob a caller has to say "do not pay for reranking on
     * this search" — the admin search form's toggle, the MCP/agentic callers that set it — and it
     * is honoured in exactly one place: the guard around {@code applyReranking} on the hybrid path.
     *
     * <p>
     * Losing that guard costs money on every hybrid search and reports nothing.
     * {@code applyReranking} POSTs the FULL TEXT of every fused candidate to Voyage in a single
     * request, and on success it silently re-sorts the page the caller was about to receive. The
     * rows are still real chunks, the count is unchanged, and the only visible difference is an
     * ordering that reads as ordinary retrieval variance — so an operator who turned reranking off
     * in order to measure the corpus without it measures it WITH it instead, and is billed for the
     * privilege on every query.
     *
     * <p>
     * Nothing in the suite can see it. There is no {@code verify(rerankClient, never())} on the
     * hybrid path anywhere, and the integration tests that pass {@code rerank=false} stub the
     * client to THROW — which {@code applyReranking} catches and degrades into an RRF fallback — so
     * a leaked rerank call there is indistinguishable from no call at all. The sibling
     * {@code aSingleLanePagesInPostgresAndNeverReranks} pins the other half of the rule (a
     * single-lane search never reranks even when asked to); this pins the half about being asked
     * NOT to.
     *
     * <p>
     * The reranker is stubbed to SUCCEED and to invert the fused order, deliberately: a guard that
     * stopped being consulted would then change the returned order too, so this fails on the
     * outcome as well as on the interaction rather than on an incidental null.
     */
    @Test
    public void aHybridSearchThatAsksNotToRerankDoesNotPayForOne() {
        SearchOptions opts = new SearchOptions("OIM", 5, true, true, "1.0", 0, false);
        when(embeddingService.embed("query")).thenReturn(new float[] {0.1f});

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        ChunkRow row1 = new ChunkRow(id1, UUID.randomUUID(), "chunk one", null, null, 1, 0, "1.0",
            "f.md", "manual", "OIM", Collections.emptyMap(), 0.9);
        ChunkRow row2 = new ChunkRow(id2, UUID.randomUUID(), "chunk two", null, null, 1, 1, "1.0",
            "f.md", "manual", "OIM", Collections.emptyMap(), 0.8);

        when(chunkRepository.findSemanticCandidates(anyString(), anyInt(), anyInt(), anyList(),
            anyList(), any())).thenReturn(List.of(row1, row2));
        when(chunkRepository.findFulltextCandidates(anyString(), anyInt(), anyInt(), anyList(),
            anyList(), any())).thenReturn(Collections.emptyList());

        RrfFuser.IntermediateRrfResult fused1 = new RrfFuser.IntermediateRrfResult();
        fused1.setRow(row1);
        fused1.setRrfScore(0.5);
        fused1.setRrfRank(1);
        fused1.setInSem(true);
        fused1.setInFt(false);
        RrfFuser.IntermediateRrfResult fused2 = new RrfFuser.IntermediateRrfResult();
        fused2.setRow(row2);
        fused2.setRrfScore(0.4);
        fused2.setRrfRank(2);
        fused2.setInSem(true);
        fused2.setInFt(false);
        when(rrfFuser.fuse(anyList(), anyList()))
            .thenReturn(new ArrayList<>(List.of(fused1, fused2)));

        // A reranker that would SUCCEED and invert the order, so a leaked call shows up in the
        // result and not only in the verification below.
        when(rerankClient.rerank(anyString(), anyList())).thenReturn(List
            .of(new RerankClient.RerankResult(0, 0.01), new RerankClient.RerankResult(1, 0.99)));

        List<HybridSearchResult> results = hybridSearch.search("query", opts);

        verify(rerankClient, never()).rerank(anyString(), anyList());

        assertThat(results).hasSize(2);
        assertThat(results.get(0).id())
            .as("rerank was declined, so the fused RRF order is what the caller gets")
            .isEqualTo(id1);
        assertThat(results.get(0).score()).isEqualTo(0.5);
        assertThat(results.get(1).id()).isEqualTo(id2);
        assertThat(results.get(1).score()).isEqualTo(0.4);
    }

    /**
     * S4.7. {@code sanitizeQuery} replaces every Tantivy operator with a space, so a query built
     * only from them — "+++", "C++", "**", a lone "?" — sanitizes to the empty string. On the
     * BM25-only path that is correctly the end of the search: there is no query left to run. On the
     * HYBRID path it must not be, because the semantic lane never sees the sanitized string at all
     * — it embeds the ORIGINAL text, and "C++" is a perfectly good embedding.
     *
     * <p>
     * Short-circuiting the hybrid path on an empty sanitized query therefore discards a lane that
     * was about to succeed, and does it in the quietest possible way: the caller gets zero rows for
     * a query the corpus answers, which an agent reads as "the corpus does not contain this" rather
     * than "one lane declined". It also skips fusion, reranking AND the telemetry write, so the
     * search leaves no row in {@code retrieval_logs} — there is nothing for anyone to notice
     * afterwards. On a OneIM corpus full of "C#", "VB.NET" and "%Globals%" this is not a corner
     * case.
     *
     * <p>
     * No existing test uses a query the sanitizer empties: every hybrid test passes ordinary words,
     * so both lanes always run and the empty-BM25 substitution branch is never taken.
     */
    @Test
    public void aSymbolOnlyQueryStillRunsTheSemanticLaneOnTheHybridPath() {
        SearchOptions opts = new SearchOptions("OIM", 5, true, true, "1.0", 0);
        when(embeddingService.embed("+++")).thenReturn(new float[] {0.1f});

        UUID id = UUID.randomUUID();
        ChunkRow row = new ChunkRow(id, UUID.randomUUID(), "only semantic hit", null, null, 1, 0,
            "1.0", "f.md", "manual", "OIM", Collections.emptyMap(), 0.9);
        when(chunkRepository.findSemanticCandidates(anyString(), anyInt(), anyInt(), anyList(),
            anyList(), any())).thenReturn(List.of(row));

        RrfFuser.IntermediateRrfResult fused = new RrfFuser.IntermediateRrfResult();
        fused.setRow(row);
        fused.setRrfScore(0.5);
        fused.setRrfRank(1);
        fused.setInSem(true);
        fused.setInFt(false);
        when(rrfFuser.fuse(anyList(), anyList())).thenReturn(new ArrayList<>(List.of(fused)));
        when(rerankClient.rerank(anyString(), anyList())).thenReturn(Collections.emptyList());

        List<HybridSearchResult> results = hybridSearch.search("+++", opts);

        assertThat(results).as("the semantic lane answered, so the search did too").hasSize(1);
        assertThat(results.get(0).id()).isEqualTo(id);

        // The BM25 lane is SUBSTITUTED, not attempted: no query reaches the repository...
        verify(chunkRepository, never()).findFulltextCandidates(anyString(), anyInt(), anyInt(),
            anyList(), anyList(), any());
        // ...fusion still runs, with an empty full-text side...
        verify(rrfFuser).fuse(eq(List.of(row)), eq(Collections.emptyList()));
        // ...the reranker still sees the ORIGINAL query, not the sanitized one...
        verify(rerankClient).rerank(eq("+++"), eq(List.of("only semantic hit")));
        // ...and the search is still logged, so it exists in retrieval_logs.
        verify(telemetryService).logAndPersistTelemetry(any(UUID.class), eq("+++"),
            any(SearchOptions.class), eq(1), eq(0), anyList(), eq(true), anyList(), anyList(),
            anyList());
    }

    @Test
    public void testExecuteHybridSearchWithRerankSuccess() {
        SearchOptions opts = new SearchOptions("OIM", 5, true, true, "1.0", 0);
        when(embeddingService.embed("query")).thenReturn(new float[] {0.1f});

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        ChunkRow row1 = new ChunkRow(id1, UUID.randomUUID(), "chunk one", null, null, 1, 0, "1.0",
            "f.md", "manual", "OIM", Collections.emptyMap(), 0.9);
        ChunkRow row2 = new ChunkRow(id2, UUID.randomUUID(), "chunk two", null, null, 1, 1, "1.0",
            "f.md", "manual", "OIM", Collections.emptyMap(), 0.8);

        when(chunkRepository.findSemanticCandidates(anyString(), anyInt(), anyInt(), anyList(),
            anyList(), any())).thenReturn(List.of(row1, row2));
        when(chunkRepository.findFulltextCandidates(anyString(), anyInt(), anyInt(), anyList(),
            anyList(), any())).thenReturn(List.of(row2));

        // RRF Fusion Mock
        RrfFuser.IntermediateRrfResult rrfRes1 = new RrfFuser.IntermediateRrfResult();
        rrfRes1.setRow(row1);
        rrfRes1.setRrfScore(0.5);
        rrfRes1.setRrfRank(1);
        rrfRes1.setInSem(true);
        rrfRes1.setInFt(false);
        RrfFuser.IntermediateRrfResult rrfRes2 = new RrfFuser.IntermediateRrfResult();
        rrfRes2.setRow(row2);
        rrfRes2.setRrfScore(0.4);
        rrfRes2.setRrfRank(2);
        rrfRes2.setInSem(true);
        rrfRes2.setInFt(true);

        List<RrfFuser.IntermediateRrfResult> rrfList = new ArrayList<>(List.of(rrfRes1, rrfRes2));
        when(rrfFuser.fuse(anyList(), anyList())).thenReturn(rrfList);

        // Reranker returns reranked scores (index 1 is row2, index 0 is row1)
        // Let's rerank row2 (score 0.99) over row1 (score 0.01)
        List<RerankClient.RerankResult> mockRerank =
            List.of(new RerankClient.RerankResult(0, 0.01), new RerankClient.RerankResult(1, 0.99));
        when(rerankClient.rerank(anyString(), anyList())).thenReturn(mockRerank);

        List<HybridSearchResult> results = hybridSearch.search("query", opts);

        assertThat(results).hasSize(2);
        // Row 2 should now be first (index 0) because of rerank score 0.99
        assertThat(results.get(0).id()).isEqualTo(id2);
        assertThat(results.get(0).score()).isEqualTo(0.99);

        // Row 1 should be second (index 1)
        assertThat(results.get(1).id()).isEqualTo(id1);
        assertThat(results.get(1).score()).isEqualTo(0.01);
    }

    /**
     * The page is sliced AFTER reranking, and that ordering is the entire reason the reranker
     * exists. The two lanes over-fetch on purpose — the semantic pool is
     * {@code semantic-limit-multiplier x (limit + offset)} and the full-text pool its own multiple
     * — so the fused set is deliberately several times larger than the page, and the reranker's job
     * is to decide which of those candidates the caller actually sees.
     *
     * <p>
     * Slice first and the reranker is handed a set it can only reorder, never change: a chunk that
     * RRF put at rank 2 can no longer reach a page of 1 however relevant it is, so the whole
     * over-fetch is paid for (an embedding call, a wider BM25 scan, a rerank request) and thrown
     * away. Nothing fails, nothing logs, and the result set is still the right SIZE with plausible
     * rows in it — the answer is simply grounded in the second-best chunk, which is
     * indistinguishable from ordinary retrieval noise and is exactly the failure a corpus of
     * near-duplicate OneIM pages produces most often.
     *
     * <p>
     * The existing rerank test cannot notice: it asks for {@code limit = 5} with two fused
     * candidates, so the page is larger than the fused set and every candidate is returned
     * whichever side of the slice the rerank happens on. It asserts the ORDER changed, not that
     * membership changed. Only a page smaller than the fused set can tell the two implementations
     * apart.
     */
    @Test
    public void rerankingCanPullACandidateIntoAPageSmallerThanTheFusedSet() {
        SearchOptions opts = new SearchOptions("OIM", 1, true, true, "1.0", 0);
        when(embeddingService.embed("query")).thenReturn(new float[] {0.1f});

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        ChunkRow row1 = new ChunkRow(id1, UUID.randomUUID(), "chunk one", null, null, 1, 0, "1.0",
            "f.md", "manual", "OIM", Collections.emptyMap(), 0.9);
        ChunkRow row2 = new ChunkRow(id2, UUID.randomUUID(), "chunk two", null, null, 1, 1, "1.0",
            "f.md", "manual", "OIM", Collections.emptyMap(), 0.8);

        when(chunkRepository.findSemanticCandidates(anyString(), anyInt(), anyInt(), anyList(),
            anyList(), any())).thenReturn(List.of(row1, row2));
        when(chunkRepository.findFulltextCandidates(anyString(), anyInt(), anyInt(), anyList(),
            anyList(), any())).thenReturn(List.of(row2));

        // Fusion puts row1 first; the page is one row, so row2 is off the page before reranking.
        RrfFuser.IntermediateRrfResult fused1 = new RrfFuser.IntermediateRrfResult();
        fused1.setRow(row1);
        fused1.setRrfScore(0.5);
        fused1.setRrfRank(1);
        fused1.setInSem(true);
        fused1.setInFt(false);
        RrfFuser.IntermediateRrfResult fused2 = new RrfFuser.IntermediateRrfResult();
        fused2.setRow(row2);
        fused2.setRrfScore(0.4);
        fused2.setRrfRank(2);
        fused2.setInSem(true);
        fused2.setInFt(true);
        when(rrfFuser.fuse(anyList(), anyList()))
            .thenReturn(new ArrayList<>(List.of(fused1, fused2)));

        // The reranker disagrees with RRF and puts the off-page candidate on top.
        when(rerankClient.rerank(anyString(), anyList())).thenReturn(List
            .of(new RerankClient.RerankResult(0, 0.01), new RerankClient.RerankResult(1, 0.99)));

        List<HybridSearchResult> results = hybridSearch.search("query", opts);

        // The reranker must have been offered the whole fused set, not just the page.
        verify(rerankClient).rerank(eq("query"), eq(List.of("chunk one", "chunk two")));

        // ...and its verdict decides who is on the page.
        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo(id2);
        assertThat(results.get(0).score()).isEqualTo(0.99);
    }

    /**
     * S4.11. Rerank scores and RRF scores live on completely different scales — a Voyage relevance
     * score runs 0…1, while an RRF score at {@code k = 60} tops out around 0.0164 — and
     * {@code getFinalScore()} feeds BOTH into one comparator. So a reply that scores only SOME of
     * the candidates it was sent does not merely reorder the page: every candidate Voyage omitted
     * keeps its RRF score and sinks below every candidate Voyage scored, however badly it scored
     * it. Here the single scored chunk comes back with 0.05 — a poor relevance score — and still
     * outranks a chunk RRF put first, because 0.05 is three times the best RRF score obtainable.
     *
     * <p>
     * That is the documented behaviour, and it is also exactly the shape a truncated, retried or
     * version-drifted provider reply takes. Nothing reports it: the search returns the right NUMBER
     * of rows, all of them real chunks, and the one that should have led is simply further down —
     * or off the page entirely once the page is smaller than the fused set, which is the normal
     * configuration. On a corpus of near-identical OneIM pages that is indistinguishable from
     * ordinary retrieval noise.
     *
     * <p>
     * No existing test mixes scored and un-scored candidates. {@code testExecuteHybridSearchWith
     * RerankSuccess} scores every candidate, {@code anOutOfRangeRerankIndexIsIgnored…} scores none,
     * and {@code testExecuteHybridSearchRerankFailureFallback} makes the whole call throw. Only a
     * PARTIAL reply separates "the two scales are compared directly" from "reranking merely
     * reorders what RRF produced".
     */
    @Test
    public void aPartiallyScoredRerankReplySinksEveryCandidateItOmitted() {
        SearchOptions opts = new SearchOptions("OIM", 5, true, true, "1.0", 0);
        when(embeddingService.embed("query")).thenReturn(new float[] {0.1f});

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();
        ChunkRow row1 = new ChunkRow(id1, UUID.randomUUID(), "chunk one", null, null, 1, 0, "1.0",
            "f.md", "manual", "OIM", Collections.emptyMap(), 0.9);
        ChunkRow row2 = new ChunkRow(id2, UUID.randomUUID(), "chunk two", null, null, 1, 1, "1.0",
            "f.md", "manual", "OIM", Collections.emptyMap(), 0.8);
        ChunkRow row3 = new ChunkRow(id3, UUID.randomUUID(), "chunk three", null, null, 1, 2, "1.0",
            "f.md", "manual", "OIM", Collections.emptyMap(), 0.7);

        when(chunkRepository.findSemanticCandidates(anyString(), anyInt(), anyInt(), anyList(),
            anyList(), any())).thenReturn(List.of(row1, row2, row3));
        when(chunkRepository.findFulltextCandidates(anyString(), anyInt(), anyInt(), anyList(),
            anyList(), any())).thenReturn(Collections.emptyList());

        // Realistic RRF magnitudes: the whole scale sits three orders of magnitude below 1.
        RrfFuser.IntermediateRrfResult fused1 = new RrfFuser.IntermediateRrfResult();
        fused1.setRow(row1);
        fused1.setRrfScore(0.0164);
        fused1.setRrfRank(1);
        fused1.setInSem(true);
        fused1.setInFt(false);
        RrfFuser.IntermediateRrfResult fused2 = new RrfFuser.IntermediateRrfResult();
        fused2.setRow(row2);
        fused2.setRrfScore(0.0163);
        fused2.setRrfRank(2);
        fused2.setInSem(true);
        fused2.setInFt(false);
        RrfFuser.IntermediateRrfResult fused3 = new RrfFuser.IntermediateRrfResult();
        fused3.setRow(row3);
        fused3.setRrfScore(0.0162);
        fused3.setRrfRank(3);
        fused3.setInSem(true);
        fused3.setInFt(false);
        when(rrfFuser.fuse(anyList(), anyList()))
            .thenReturn(new ArrayList<>(List.of(fused1, fused2, fused3)));

        // Three candidates were sent; the reply scores ONE of them, and scores it poorly.
        when(rerankClient.rerank(anyString(), anyList()))
            .thenReturn(List.of(new RerankClient.RerankResult(2, 0.05)));

        List<HybridSearchResult> results = hybridSearch.search("query", opts);

        assertThat(results).hasSize(3);
        assertThat(results.get(0).id())
            .as("a weakly reranked chunk still outranks the best possible RRF score")
            .isEqualTo(id3);
        assertThat(results.get(0).score()).isEqualTo(0.05);

        // The omitted candidates keep their RRF scores untouched and their relative order.
        assertThat(results.get(1).id()).isEqualTo(id1);
        assertThat(results.get(1).score()).isEqualTo(0.0164);
        assertThat(results.get(2).id()).isEqualTo(id2);
        assertThat(results.get(2).score()).isEqualTo(0.0163);
    }

    /**
     * S4.11. The reranker's indexes arrive over HTTP from a third party and are used as raw list
     * positions on the fused candidate list. The bound is what stands between a Voyage response
     * that has drifted out of step with the request — a truncated batch, a retried call scored
     * against a different list, an API version that starts numbering at one — and an
     * {@link IndexOutOfBoundsException} thrown from OUTSIDE the try/catch that guards the rerank
     * call.
     *
     * <p>
     * That distinction is the whole point. A rerank FAILURE degrades gracefully to RRF order, which
     * {@code testExecuteHybridSearchRerankFailureFallback} pins; a rerank ANOMALY escapes
     * {@code applyReranking}, escapes {@code executeHybridSearch} and reaches the caller, so an MCP
     * tool call, a chat turn or an admin search dies with a stack trace over a scoring detail —
     * after the embedding, both lane queries and the rerank request have all been paid for, and
     * with a perfectly good set of RRF-ordered rows sitting in memory. Reordering is an
     * optimisation; it must never be able to fail the retrieval.
     *
     * <p>
     * Every existing rerank test hands back indexes that exactly match the candidate list it was
     * given, so the bound is never exercised and could be deleted with the suite staying green.
     */
    @Test
    public void anOutOfRangeRerankIndexIsIgnoredRatherThanBlowingUpTheSearch() {
        SearchOptions opts = new SearchOptions("OIM", 5, true, true, "1.0", 0);
        when(embeddingService.embed("query")).thenReturn(new float[] {0.1f});

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        ChunkRow row1 = new ChunkRow(id1, UUID.randomUUID(), "chunk one", null, null, 1, 0, "1.0",
            "f.md", "manual", "OIM", Collections.emptyMap(), 0.9);
        ChunkRow row2 = new ChunkRow(id2, UUID.randomUUID(), "chunk two", null, null, 1, 1, "1.0",
            "f.md", "manual", "OIM", Collections.emptyMap(), 0.8);

        when(chunkRepository.findSemanticCandidates(anyString(), anyInt(), anyInt(), anyList(),
            anyList(), any())).thenReturn(List.of(row1, row2));
        when(chunkRepository.findFulltextCandidates(anyString(), anyInt(), anyInt(), anyList(),
            anyList(), any())).thenReturn(Collections.emptyList());

        RrfFuser.IntermediateRrfResult fused1 = new RrfFuser.IntermediateRrfResult();
        fused1.setRow(row1);
        fused1.setRrfScore(0.5);
        fused1.setRrfRank(1);
        fused1.setInSem(true);
        fused1.setInFt(false);
        RrfFuser.IntermediateRrfResult fused2 = new RrfFuser.IntermediateRrfResult();
        fused2.setRow(row2);
        fused2.setRrfScore(0.4);
        fused2.setRrfRank(2);
        fused2.setInSem(true);
        fused2.setInFt(false);
        when(rrfFuser.fuse(anyList(), anyList()))
            .thenReturn(new ArrayList<>(List.of(fused1, fused2)));

        // Two candidates were sent; the response scores positions that do not exist in that list.
        when(rerankClient.rerank(anyString(), anyList())).thenReturn(List
            .of(new RerankClient.RerankResult(99, 0.99), new RerankClient.RerankResult(-1, 0.98)));

        List<HybridSearchResult> results = hybridSearch.search("query", opts);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).id()).as("nothing was scored, so the RRF order stands")
            .isEqualTo(id1);
        assertThat(results.get(1).id()).isEqualTo(id2);
        assertThat(results.get(0).score()).isEqualTo(0.5);
        assertThat(results.get(1).score()).isEqualTo(0.4);
    }

    @Test
    public void testExecuteHybridSearchRerankFailureFallback() {
        SearchOptions opts = new SearchOptions("OIM", 5, true, true, "1.0", 0);
        when(embeddingService.embed("query")).thenReturn(new float[] {0.1f});

        UUID id = UUID.randomUUID();
        ChunkRow row = new ChunkRow(id, UUID.randomUUID(), "chunk", null, null, 1, 0, "1.0", "f.md",
            "manual", "OIM", Collections.emptyMap(), 0.9);
        when(chunkRepository.findSemanticCandidates(anyString(), anyInt(), anyInt(), anyList(),
            anyList(), any())).thenReturn(List.of(row));
        when(chunkRepository.findFulltextCandidates(anyString(), anyInt(), anyInt(), anyList(),
            anyList(), any())).thenReturn(Collections.emptyList());

        RrfFuser.IntermediateRrfResult rrfRes = new RrfFuser.IntermediateRrfResult();
        rrfRes.setRow(row);
        rrfRes.setRrfScore(0.5);
        rrfRes.setRrfRank(1);
        rrfRes.setInSem(true);
        rrfRes.setInFt(false);
        when(rrfFuser.fuse(anyList(), anyList())).thenReturn(new ArrayList<>(List.of(rrfRes)));

        // Voyage Rerank throws exception
        when(rerankClient.rerank(anyString(), anyList()))
            .thenThrow(new RuntimeException("API Outage"));

        List<HybridSearchResult> results = hybridSearch.search("query", opts);

        // Should fallback to RRF score and complete query successfully
        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo(id);
        assertThat(results.get(0).score()).isEqualTo(0.5); // Fallback RRF score (via getFinalScore)
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testExecuteSearchWithTags() {
        SearchOptions opts =
            new SearchOptions(Collections.emptyList(), 5, true, false, 0, false, List.of("my-tag"));

        JdbcClient.StatementSpec spec1 = mock(JdbcClient.StatementSpec.class);
        JdbcClient.StatementSpec spec2 = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<String> querySpec1 = mock(JdbcClient.MappedQuerySpec.class);
        JdbcClient.MappedQuerySpec<UUID> querySpec2 = mock(JdbcClient.MappedQuerySpec.class);

        when(jdbcClient.sql(contains("FROM collections"))).thenReturn(spec1);
        when(spec1.param(eq("tags"), any())).thenReturn(spec1);
        when(spec1.query(String.class)).thenReturn(querySpec1);
        when(querySpec1.list()).thenReturn(List.of("tag-collection"));

        when(jdbcClient.sql(contains("FROM documents"))).thenReturn(spec2);
        when(spec2.param(eq("tags"), any())).thenReturn(spec2);
        when(spec2.query(UUID.class)).thenReturn(querySpec2);
        UUID targetDocId = UUID.randomUUID();
        when(querySpec2.list()).thenReturn(List.of(targetDocId));

        when(embeddingService.embed("database")).thenReturn(new float[] {0.1f});

        UUID id = UUID.randomUUID();
        ChunkRow row = new ChunkRow(id, targetDocId, "database connection", null, null, 1, 0, "1.0",
            "f.md", "manual", "tag-collection", Collections.emptyMap(), 0.99);
        when(chunkRepository.findSemanticCandidates(anyString(), anyInt(), anyInt(), any(), any(),
            any())).thenReturn(List.of(row));

        List<HybridSearchResult> results = hybridSearch.search("database", opts);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo(id);
        verify(chunkRepository).findSemanticCandidates(anyString(), anyInt(), anyInt(), any(),
            any(), any());
    }
}
