package de.palsoftware.yvoke.kg.core.service;

import de.palsoftware.yvoke.kg.core.model.KgExtractionResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.llm.core.service.LlmClient;
import de.palsoftware.yvoke.llm.core.model.LlmMessage;
import de.palsoftware.yvoke.llm.core.model.LlmRequest;
import de.palsoftware.yvoke.llm.core.model.LlmResponse;
import de.palsoftware.yvoke.llm.core.model.LlmUsage;
import de.palsoftware.yvoke.shared.jobengine.model.JobContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import de.palsoftware.yvoke.llm.core.context.LlmCallContextHolder;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public class DocumentKgExtractorTest {

    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * All 20-odd tests in this class feed the extractor canned response STRINGS, so every one of
     * them proves what the parser does with well-formed JSON and none of them observes the request
     * that made the JSON well-formed in the first place. The structured-output contract —
     * {@code responseMimeType = application/json} plus a schema requiring {@code entities[].name}
     * and {@code relationships[].{subject,predicate,object}} — is what forces the model to emit
     * parseable output at all. The prompt text and the code-fence stripping are only a fallback for
     * providers that ignore the schema.
     *
     * <p>
     * Drop either field and the model is free to answer in prose. Every chunk then fails
     * {@code parseInto}, is retried once, fails again, and is counted as skipped — and
     * {@code DocumentIngestService.persistGraph} deliberately does NOT throw on the LLM path, so
     * the kg-extract job finishes GREEN with zero entities and zero edges. The only trace is a
     * {@code skipped} count on the job-detail page. A whole corpus can be "extracted" this way
     * without a single error anywhere.
     *
     * <p>
     * The tools assertion belongs with it: {@code responseMimeType}/{@code responseSchema} are
     * documented as honored only when no tools are supplied, so handing this request a tool list
     * would silently disable the very constraint above.
     *
     * <p>
     * The attribution assertion rides along because it can only be observed here. Extraction fans
     * out over virtual threads and {@code LlmCallContextHolder} is a ThreadLocal that
     * {@code DelegatingSecurityContextExecutorService} does not propagate, so the context has to be
     * set on the worker thread INSIDE the task — capturing it from the stubbed provider is the only
     * vantage point. {@code GeneralSummarizerTest} pins the same rule for the summarizer leaf; this
     * leaf has nothing. Lose it and every KG extraction call lands in {@code llm_call_logs} under
     * {@code source=unknown}, dropping the largest batch spend in the system out of every cost
     * view.
     */
    @Test
    public void kgExtractionIsRequestedAsSchemaConstrainedJsonUnderTheKgExtractionContext()
        throws Exception {
        LlmClient llmClient = mock(LlmClient.class);
        AtomicReference<LlmCallContextHolder.Context> seenAtCallTime = new AtomicReference<>();
        when(llmClient.generate(any(LlmRequest.class))).thenAnswer(invocation -> {
            seenAtCallTime.set(LlmCallContextHolder.get());
            return response("{\"entities\":[],\"relationships\":[]}");
        });

        DocumentKgExtractor extractor = extractor(llmClient, runningJdbc(), 1, 1);
        extractor.extract(List.of("some chunk text"), null, null, "KG_PROMPT");

        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmClient).generate(captor.capture());
        LlmRequest sent = captor.getValue();

        assertThat(sent.responseMimeType())
            .as("without application/json the model may answer in prose, every chunk fails the"
                + " parse, and the job completes green with an empty graph")
            .isEqualTo("application/json");
        assertThat(sent.tools()).as("structured output is only honored when no tools are supplied")
            .isNull();

        String schemaJson = objectMapper.writeValueAsString(sent.responseSchema());
        assertThat(schemaJson)
            .as("an entity without a name cannot become a node, so the schema must require it")
            .contains("\"required\":[\"name\"]");
        assertThat(schemaJson)
            .as("an edge missing any of subject/predicate/object cannot be identified")
            .contains("\"required\":[\"subject\",\"predicate\",\"object\"]");

        LlmCallContextHolder.Context billing = seenAtCallTime.get();
        assertThat(billing)
            .as("the context must be set on the worker thread that makes the call, not around the"
                + " executor — a ThreadLocal is not propagated into virtual threads")
            .isNotNull();
        assertThat(billing.source()).isEqualTo("kg_extraction");
        assertThat(billing.role()).isEqualTo("kg_extractor");
    }

    /**
     * The extraction cache decides, per chunk, whether an LLM call happens at all — and every test
     * in this class blanket-stubs {@code jdbcClient.sql(anyString())} to hand back
     * {@code "running"} (the cancellation probe's value), so the cache SELECT always returns
     * garbage, always fails the parse and always falls through to the model. The cache HIT path has
     * therefore never executed: neither its key nor its scope nor the fact that a hit skips the
     * provider is asserted anywhere.
     *
     * <p>
     * <b>The key is sha256 of the chunk TEXT.</b> Keyed on anything else — a chunk id, a document
     * id, a normalised variant — every re-run of a kg-extract job is a full re-bill of the corpus
     * at {@code app.ai.kg.model} rates, and nothing reports it because the graph that comes out is
     * identical. Chunk text is the right key precisely because identical text across documents and
     * across versions yields the same graph.
     *
     * <p>
     * <b>The read must stay scoped to {@code kind = 'kg'}.</b> {@code summary_cache} is shared with
     * {@code GeneralSummarizer}, which stores PROSE under the caller's kind, and its PK is
     * {@code source_sha} ALONE ({@code ON CONFLICT (source_sha) DO NOTHING}) — so one sha holds one
     * row whatever wrote it. Drop the predicate and a chunk that was summarised first hands the
     * extractor a prose summary as its "cached extraction". That parses as nothing, so it is not
     * even a wrong graph: the chunk is retried, then counted as skipped, and the document is
     * quietly graphed from fewer chunks than it has. The sibling rule is pinned on the summarizer
     * side ({@code GeneralSummarizerTest.theCacheReadIsScopedToTheKindItWrites}); this is the other
     * end of the same shared table, and the two are separate SQL strings that nothing keeps in
     * agreement.
     */
    @SuppressWarnings("unchecked")
    @Test
    public void aCachedExtractionIsReadByChunkShaScopedToTheKgKindAndSkipsTheModel()
        throws Exception {
        String chunkText = "the chunk whose graph was already extracted";
        String expectedSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(chunkText.getBytes(StandardCharsets.UTF_8)));

        LlmClient llmClient = mock(LlmClient.class);
        JdbcClient jdbcClient = mock(JdbcClient.class);
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<String> querySpec = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbcClient.sql(anyString())).thenReturn(spec);
        when(spec.param(anyString(), any())).thenReturn(spec);
        when(spec.query(String.class)).thenReturn(querySpec);
        when(querySpec.optional()).thenReturn(Optional
            .of("{\"entities\":[{\"name\":\"Cached\",\"kind\":\"table\"}],\"relationships\":[]}"));

        // No job id: the only statement this run may issue is the cache read itself.
        KgExtractionResult result = extractor(llmClient, jdbcClient, 1, 2)
            .extract(List.of(chunkText), null, null, "KG_PROMPT");

        assertThat(result.entities()).extracting(KgExtractionResult.ExtractedEntity::name)
            .as("a cache hit must be USED, not merely read").containsExactly("Cached");
        assertThat(result.skipped()).isZero();
        verify(llmClient, never()).generate(any(LlmRequest.class));
        verify(jdbcClient, never()).sql(startsWith("INSERT"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcClient, atLeastOnce()).sql(sql.capture());
        String select = sql.getAllValues().stream().filter(s -> s.startsWith("SELECT summary"))
            .findFirst().orElseThrow();
        assertThat(select)
            .as("summary_cache is shared with the prose summarizer and its PK is source_sha alone —"
                + " an unscoped read returns another producer's payload")
            .contains("kind = 'kg'");
        verify(spec).param("sha", expectedSha);
    }

    private DocumentKgExtractor extractorReturning(String... rawPerChunk) {
        // Build all LlmResponse stubs up front: building them inside the outer
        // when(...).thenReturn(...)
        // nests stubbing and trips Mockito's UnfinishedStubbingException.
        LlmResponse first = response(rawPerChunk[0]);
        LlmResponse[] rest = new LlmResponse[rawPerChunk.length - 1];
        for (int i = 1; i < rawPerChunk.length; i++) {
            rest[i - 1] = response(rawPerChunk[i]);
        }
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.generate(any(LlmRequest.class))).thenReturn(first, rest);
        return extractor(llmClient, runningJdbc(), 4, 2);
    }

    private DocumentKgExtractor extractorMapping(Map<String, String> rawByChunkText) {
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.generate(any(LlmRequest.class))).thenAnswer(invocation -> {
            LlmRequest request = invocation.getArgument(0);
            String chunkText =
                request.messages().stream().filter(msg -> "user".equalsIgnoreCase(msg.role()))
                    .map(LlmMessage::content).findFirst().orElse("");
            return response(rawByChunkText.get(chunkText));
        });
        return extractor(llmClient, runningJdbc(), 4, 2);
    }

    private DocumentKgExtractor extractor(LlmClient llmClient, JdbcClient jdbcClient,
        int concurrency, int maxAttempts) {
        return new DocumentKgExtractor(llmClient, objectMapper, jdbcClient, "kg-model", 4096, 0.0,
            concurrency, maxAttempts);
    }

    private static JdbcClient runningJdbc() {
        return jdbcReturningStatus("running");
    }

    @SuppressWarnings("unchecked")
    private static JdbcClient jdbcReturningStatus(String status) {
        JdbcClient jdbcClient = mock(JdbcClient.class);
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<String> querySpec = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbcClient.sql(anyString())).thenReturn(spec);
        when(spec.param(anyString(), any())).thenReturn(spec);
        when(spec.query(String.class)).thenReturn(querySpec);
        when(querySpec.optional()).thenReturn(Optional.of(status));
        return jdbcClient;
    }

    private static LlmResponse response(String text) {
        return new LlmResponse(text, new LlmUsage(0, 0, 0, 0, 0));
    }

    @Test
    public void parsesEntitiesAndRelationships() {
        DocumentKgExtractor extractor = extractorReturning(
            "{\"entities\":[{\"name\":\"OAuth\",\"kind\":\"module\",\"description\":\"auth\"}],"
                + "\"relationships\":[{\"subject\":\"OAuth\",\"predicate\":\"part_of\",\"object\":\"OIM\",\"description\":\"\"}]}");

        KgExtractionResult result = extractor.extract(List.of("chunk"), null, null, "KG_PROMPT");

        assertThat(result.skipped()).isZero();
        assertThat(result.entities()).singleElement().satisfies(e -> {
            assertThat(e.name()).isEqualTo("OAuth");
            assertThat(e.kind()).isEqualTo("module");
        });
        assertThat(result.relationships()).singleElement().satisfies(r -> {
            assertThat(r.subject()).isEqualTo("OAuth");
            assertThat(r.predicate()).isEqualTo("part_of");
            assertThat(r.object()).isEqualTo("OIM");
        });
    }

    @Test
    public void stripsMarkdownCodeFence() {
        DocumentKgExtractor extractor = extractorReturning(
            "```json\n{\"entities\":[{\"name\":\"SAML\",\"kind\":\"\",\"description\":\"\"}],\"relationships\":[]}\n```");

        KgExtractionResult result = extractor.extract(List.of("chunk"), null, null, "KG_PROMPT");

        assertThat(result.skipped()).isZero();
        assertThat(result.entities()).singleElement()
            .satisfies(e -> assertThat(e.name()).isEqualTo("SAML"));
    }

    @Test
    public void skipsMalformedJsonAndCountsIt() {
        DocumentKgExtractor extractor = extractorMapping(
            Map.of("a", "{\"entities\":[{\"name\":\"Good\"}],\"relationships\":[]}", "b",
                "this is not json at all {truncated", "c",
                "{\"entities\":[{\"name\":\"AlsoGood\"}],\"relationships\":[]}"));

        KgExtractionResult result =
            extractor.extract(List.of("a", "b", "c"), null, null, "KG_PROMPT");

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.entities()).extracting(KgExtractionResult.ExtractedEntity::name)
            .containsExactly("Good", "AlsoGood");
    }

    @Test
    public void dropsEntitiesAndRelationshipsMissingRequiredFields() {
        DocumentKgExtractor extractor =
            extractorReturning("{\"entities\":[{\"name\":\"\"},{\"name\":\"Kept\"}],"
                + "\"relationships\":[{\"subject\":\"A\",\"predicate\":\"\",\"object\":\"B\"},"
                + "{\"subject\":\"A\",\"predicate\":\"rel\",\"object\":\"B\"}]}");

        KgExtractionResult result = extractor.extract(List.of("chunk"), null, null, "KG_PROMPT");

        assertThat(result.skipped()).isZero();
        assertThat(result.entities()).extracting(KgExtractionResult.ExtractedEntity::name)
            .containsExactly("Kept");
        assertThat(result.relationships()).hasSize(1);
        assertThat(result.relationships().get(0).predicate()).isEqualTo("rel");
    }

    @Test
    public void stripCodeFenceHandlesPlainJson() {
        assertThat(DocumentKgExtractor.stripCodeFence("{\"a\":1}")).isEqualTo("{\"a\":1}");
        assertThat(DocumentKgExtractor.stripCodeFence("```\n{\"a\":1}\n```"))
            .isEqualTo("{\"a\":1}");
        assertThat(DocumentKgExtractor.stripCodeFence(null)).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // Empty / boundary input
    // ---------------------------------------------------------------------------------------------

    @Test
    public void emptyChunkListReturnsEmptyResultWithoutCallingModel() {
        LlmClient llmClient = mock(LlmClient.class);
        DocumentKgExtractor extractor = extractor(llmClient, runningJdbc(), 4, 2);

        KgExtractionResult result = extractor.extract(List.of(), null, null, "KG_PROMPT");

        assertThat(result.entities()).isEmpty();
        assertThat(result.relationships()).isEmpty();
        assertThat(result.skipped()).isZero();
        verify(llmClient, never()).generate(any(LlmRequest.class));
    }

    // ---------------------------------------------------------------------------------------------
    // Parallel fan-out: aggregation, ordering, full coverage
    // ---------------------------------------------------------------------------------------------

    @Test
    public void aggregatesAllChunksAndPreservesSubmissionOrder() {
        // One distinct entity per chunk; assert the aggregate is ordered by submission index even
        // though chunks are mined concurrently in non-deterministic completion order.
        int chunkCount = 25;
        List<String> chunks = new ArrayList<>();
        Map<String, String> responses = new HashMap<>();
        for (int i = 0; i < chunkCount; i++) {
            String chunk = "chunk-" + i;
            chunks.add(chunk);
            responses.put(chunk, "{\"entities\":[{\"name\":\"E" + i + "\"}],\"relationships\":[]}");
        }
        DocumentKgExtractor extractor = extractorMapping(responses);

        KgExtractionResult result = extractor.extract(chunks, null, null, "KG_PROMPT");

        assertThat(result.skipped()).isZero();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < chunkCount; i++) {
            expected.add("E" + i);
        }
        assertThat(result.entities()).extracting(KgExtractionResult.ExtractedEntity::name)
            .containsExactlyElementsOf(expected);
    }

    @Test
    public void boundsConcurrentModelCallsToConfiguredLimit() throws Exception {
        int concurrency = 3;
        int chunkCount = 12;
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        CountDownLatch reachedCap = new CountDownLatch(concurrency);
        CountDownLatch release = new CountDownLatch(1);

        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.generate(any(LlmRequest.class))).thenAnswer(invocation -> {
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            reachedCap.countDown();
            // Hold the permit so the semaphore must block any further calls beyond `concurrency`.
            release.await();
            inFlight.decrementAndGet();
            return response("{\"entities\":[{\"name\":\"X\"}],\"relationships\":[]}");
        });
        DocumentKgExtractor extractor = extractor(llmClient, runningJdbc(), concurrency, 1);

        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < chunkCount; i++) {
            chunks.add("c" + i);
        }

        ExecutorService runner = Executors.newSingleThreadExecutor();
        try {
            Future<KgExtractionResult> future =
                runner.submit(() -> extractor.extract(chunks, null, null, "KG_PROMPT"));

            // Exactly `concurrency` calls should enter; the rest are blocked on the semaphore.
            assertThat(reachedCap.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(inFlight.get()).isEqualTo(concurrency);

            release.countDown();
            KgExtractionResult result = future.get(10, TimeUnit.SECONDS);

            assertThat(result.entities()).hasSize(chunkCount);
            assertThat(maxInFlight.get()).isEqualTo(concurrency);
        } finally {
            release.countDown();
            runner.shutdownNow();
        }
    }

    @Test
    public void singleConcurrencyStillProcessesEveryChunk() {
        Map<String, String> responses =
            Map.of("a", "{\"entities\":[{\"name\":\"A\"}],\"relationships\":[]}", "b",
                "{\"entities\":[{\"name\":\"B\"}],\"relationships\":[]}");
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.generate(any(LlmRequest.class))).thenAnswer(invocation -> {
            LlmRequest request = invocation.getArgument(0);
            String chunkText =
                request.messages().stream().filter(msg -> "user".equalsIgnoreCase(msg.role()))
                    .map(LlmMessage::content).findFirst().orElse("");
            return response(responses.get(chunkText));
        });
        DocumentKgExtractor extractor = extractor(llmClient, runningJdbc(), 1, 2);

        KgExtractionResult result = extractor.extract(List.of("a", "b"), null, null, "KG_PROMPT");

        assertThat(result.skipped()).isZero();
        assertThat(result.entities()).extracting(KgExtractionResult.ExtractedEntity::name)
            .containsExactly("A", "B");
    }

    // ---------------------------------------------------------------------------------------------
    // Retry on unexpected responses
    // ---------------------------------------------------------------------------------------------

    @Test
    public void retriesMalformedResponseThenSucceeds() {
        LlmResponse bad = response("not json at all {");
        LlmResponse good =
            response("{\"entities\":[{\"name\":\"Recovered\"}],\"relationships\":[]}");
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.generate(any(LlmRequest.class))).thenReturn(bad, good);
        DocumentKgExtractor extractor = extractor(llmClient, runningJdbc(), 4, 2);

        KgExtractionResult result = extractor.extract(List.of("chunk"), null, null, "KG_PROMPT");

        assertThat(result.skipped()).isZero();
        assertThat(result.entities()).extracting(KgExtractionResult.ExtractedEntity::name)
            .containsExactly("Recovered");
        verify(llmClient, times(2)).generate(any(LlmRequest.class));
    }

    @Test
    public void appendsCorrectiveInstructionOnRetryAfterParseFailure() {
        LlmResponse bad = response("garbage");
        LlmResponse good = response("{\"entities\":[{\"name\":\"Ok\"}],\"relationships\":[]}");
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.generate(any(LlmRequest.class))).thenReturn(bad, good);
        DocumentKgExtractor extractor = extractor(llmClient, runningJdbc(), 4, 2);

        extractor.extract(List.of("the chunk"), null, null, "KG_PROMPT");

        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmClient, times(2)).generate(captor.capture());
        List<LlmRequest> prompts = captor.getAllValues();

        // First attempt: chunk only, no corrective instruction.
        assertThat(userMessages(prompts.get(0))).containsExactly("the chunk");
        // Second attempt: chunk plus the corrective instruction.
        assertThat(userMessages(prompts.get(1))).containsExactly("the chunk",
            DocumentKgExtractor.RETRY_INSTRUCTION);
    }

    @Test
    public void skipsAndCountsAfterRetriesExhausted() {
        LlmResponse bad = response("still not json");
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.generate(any(LlmRequest.class))).thenReturn(bad);
        DocumentKgExtractor extractor = extractor(llmClient, runningJdbc(), 4, 3);

        KgExtractionResult result = extractor.extract(List.of("chunk"), null, null, "KG_PROMPT");

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.entities()).isEmpty();
        verify(llmClient, times(3)).generate(any(LlmRequest.class));
    }

    @Test
    public void retriesTransportFailureThenSucceeds() {
        LlmResponse good =
            response("{\"entities\":[{\"name\":\"AfterRetry\"}],\"relationships\":[]}");
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.generate(any(LlmRequest.class)))
            .thenThrow(new RuntimeException("503 upstream unavailable")).thenReturn(good);
        DocumentKgExtractor extractor = extractor(llmClient, runningJdbc(), 4, 2);

        KgExtractionResult result = extractor.extract(List.of("chunk"), null, null, "KG_PROMPT");

        assertThat(result.skipped()).isZero();
        assertThat(result.entities()).extracting(KgExtractionResult.ExtractedEntity::name)
            .containsExactly("AfterRetry");
        verify(llmClient, times(2)).generate(any(LlmRequest.class));
    }

    @Test
    public void transportFailureRetryDoesNotAppendCorrectiveInstruction() {
        LlmResponse good = response("{\"entities\":[{\"name\":\"Ok\"}],\"relationships\":[]}");
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.generate(any(LlmRequest.class))).thenThrow(new RuntimeException("timeout"))
            .thenReturn(good);
        DocumentKgExtractor extractor = extractor(llmClient, runningJdbc(), 4, 2);

        extractor.extract(List.of("only chunk"), null, null, "KG_PROMPT");

        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmClient, times(2)).generate(captor.capture());
        // The retry after a transport error re-sends the same prompt (no corrective nudge).
        assertThat(userMessages(captor.getAllValues().get(1))).containsExactly("only chunk");
    }

    @Test
    public void maxAttemptsOneDisablesRetry() {
        LlmResponse bad = response("bad");
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.generate(any(LlmRequest.class))).thenReturn(bad);
        DocumentKgExtractor extractor = extractor(llmClient, runningJdbc(), 4, 1);

        KgExtractionResult result = extractor.extract(List.of("chunk"), null, null, "KG_PROMPT");

        assertThat(result.skipped()).isEqualTo(1);
        verify(llmClient, times(1)).generate(any(LlmRequest.class));
    }

    @Test
    public void nonPositiveMaxAttemptsIsClampedToOne() {
        LlmResponse bad = response("bad");
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.generate(any(LlmRequest.class))).thenReturn(bad);
        DocumentKgExtractor extractor = extractor(llmClient, runningJdbc(), 4, 0);

        KgExtractionResult result = extractor.extract(List.of("chunk"), null, null, "KG_PROMPT");

        assertThat(result.skipped()).isEqualTo(1);
        verify(llmClient, times(1)).generate(any(LlmRequest.class));
    }

    @Test
    public void partialFailureAcrossChunksSkipsOnlyTheBadOne() {
        DocumentKgExtractor extractor = extractorMapping(Map.of("good1",
            "{\"entities\":[{\"name\":\"G1\"}],\"relationships\":[]}", "bad", "totally broken",
            "good2", "{\"entities\":[{\"name\":\"G2\"}],\"relationships\":[]}"));

        KgExtractionResult result =
            extractor.extract(List.of("good1", "bad", "good2"), null, null, "KG_PROMPT");

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.entities()).extracting(KgExtractionResult.ExtractedEntity::name)
            .containsExactly("G1", "G2");
    }

    // ---------------------------------------------------------------------------------------------
    // Per-chunk KG status (ok/not-ok) for later retry
    // ---------------------------------------------------------------------------------------------

    @Test
    public void emitsPerChunkStatusWithOkFlagModelAndIndexOrder() {
        DocumentKgExtractor extractor = extractorMapping(
            Map.of("c0", "{\"entities\":[{\"name\":\"G0\"}],\"relationships\":[]}", "c1",
                "totally broken", "c2", "{\"entities\":[{\"name\":\"G2\"}],\"relationships\":[]}"));

        KgExtractionResult result =
            extractor.extract(List.of("c0", "c1", "c2"), null, null, "KG_PROMPT");

        // One status per chunk, ordered by input index, regardless of concurrent completion order.
        assertThat(result.chunkStatuses()).hasSize(3);
        assertThat(result.chunkStatuses()).extracting(KgExtractionResult.ChunkStatus::index)
            .containsExactly(0, 1, 2);
        assertThat(result.chunkStatuses()).extracting(KgExtractionResult.ChunkStatus::ok)
            .containsExactly(true, false, true);
        assertThat(result.chunkStatuses()).extracting(KgExtractionResult.ChunkStatus::model)
            .containsOnly("kg-model");
    }

    @Test
    public void chunkStatusReflectsRecoveryAfterRetry() {
        LlmResponse bad = response("garbage");
        LlmResponse good =
            response("{\"entities\":[{\"name\":\"Recovered\"}],\"relationships\":[]}");
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.generate(any(LlmRequest.class))).thenReturn(bad, good);
        DocumentKgExtractor extractor = extractor(llmClient, runningJdbc(), 4, 2);

        KgExtractionResult result = extractor.extract(List.of("chunk"), null, null, "KG_PROMPT");

        // A chunk that succeeds only after a retry is recorded as ok.
        assertThat(result.chunkStatuses()).singleElement().satisfies(s -> {
            assertThat(s.index()).isZero();
            assertThat(s.ok()).isTrue();
        });
    }

    @Test
    public void chunkStatusIsFalseWhenAllRetriesExhausted() {
        LlmResponse bad = response("still not json");
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.generate(any(LlmRequest.class))).thenReturn(bad);
        DocumentKgExtractor extractor = extractor(llmClient, runningJdbc(), 4, 2);

        KgExtractionResult result = extractor.extract(List.of("chunk"), null, null, "KG_PROMPT");

        assertThat(result.chunkStatuses()).singleElement()
            .satisfies(s -> assertThat(s.ok()).isFalse());
    }

    @Test
    public void emptyInputProducesNoChunkStatuses() {
        LlmClient llmClient = mock(LlmClient.class);
        DocumentKgExtractor extractor = extractor(llmClient, runningJdbc(), 4, 2);

        KgExtractionResult result = extractor.extract(List.of(), null, null, "KG_PROMPT");

        assertThat(result.chunkStatuses()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // Progress reporting and cancellation
    // ---------------------------------------------------------------------------------------------

    @Test
    public void reportsProgressOncePerChunk() {
        DocumentKgExtractor extractor = extractorMapping(Map.of("a",
            "{\"entities\":[],\"relationships\":[]}", "b", "{\"entities\":[],\"relationships\":[]}",
            "c", "{\"entities\":[],\"relationships\":[]}"));
        JobContext ctx = mock(JobContext.class);

        extractor.extract(List.of("a", "b", "c"), JOB_ID, ctx, "KG_PROMPT");

        verify(ctx, times(3)).report(any(), anyInt(), anyString());
    }

    @Test
    public void refusesToExtractWithoutAPrompt() {
        // Replaces the implicit contract of the old no-prompt overload, which resolved
        // "default-kg" -- registered nowhere -- and fell back to "". The prompt defines the
        // strict-JSON response shape, so running without it produces unparseable output that
        // looks exactly like an empty corpus.
        LlmClient llmClient = mock(LlmClient.class);
        DocumentKgExtractor extractor = extractor(llmClient, runningJdbc(), 4, 2);

        assertThatThrownBy(() -> extractor.extract(List.of("a"), null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("KG system prompt is required");
        assertThatThrownBy(() -> extractor.extract(List.of("a"), null, null, "  "))
            .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(llmClient);
    }

    @Test
    public void cancelledJobThrowsBeforeCallingModel() {
        LlmClient llmClient = mock(LlmClient.class);
        DocumentKgExtractor extractor =
            extractor(llmClient, jdbcReturningStatus("cancelled"), 4, 2);
        JobContext ctx = mock(JobContext.class);

        assertThatThrownBy(() -> extractor.extract(List.of("a", "b"), JOB_ID, ctx, "KG_PROMPT"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("cancelled");

        verify(llmClient, never()).generate(any(LlmRequest.class));
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private static List<String> userMessages(LlmRequest request) {
        return request.messages().stream().filter(msg -> "user".equalsIgnoreCase(msg.role()))
            .map(LlmMessage::content).toList();
    }
}
