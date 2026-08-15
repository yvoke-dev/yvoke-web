package de.palsoftware.yvoke.ingest.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import de.palsoftware.yvoke.ingest.core.model.IngestJobKind;
import de.palsoftware.yvoke.rag.retrieval.EmbeddingService;
import de.palsoftware.yvoke.shared.jobengine.model.IngestionJob;
import de.palsoftware.yvoke.shared.jobengine.model.JobContext;
import de.palsoftware.yvoke.shared.jobengine.model.JobCounts;
import de.palsoftware.yvoke.shared.jobengine.model.JobStatus;
import de.palsoftware.yvoke.shared.jobengine.model.JobStep;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.mockito.ArgumentCaptor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Characterization test for the multi-phase {@code CustomIngestService.ingest()} zip pipeline
 * (MNT-07): unzip → discover markdown → parse/chunk → embed → per-document persist → optional KG
 * injection. Establishes the end-to-end behavior (JobCounts + persisted rows) that the phase
 * extraction must preserve. Embeddings are mocked (deterministic 1024-dim zero vectors); the
 * summarizer is never invoked because the fixture markdown does not request summaries.
 *
 * <p>
 * Also pins the fail-fast contract of this path: unparseable frontmatter, an entity that resolves
 * to no document, and an unresolvable edge endpoint each abort the job with a message naming the
 * offenders — none of them may degrade silently.
 */
@SpringBootTest(properties = {"spring.flyway.enabled=true",
    "spring.flyway.locations=filesystem:docker/db/migration", "app.upload-dir=${java.io.tmpdir}"})
public class CustomIngestServiceIT {

    private static final String COLLECTION = "OIM-CUSTOM-INGEST-TEST";
    private static final String VERSION = "9.3";
    private static final String DOC_COUNT_SQL =
        "SELECT count(*) FROM documents d JOIN collections c ON d.collection_id = c.id WHERE c.name = ?";
    private static final String ENTITY_COUNT_SQL =
        "SELECT count(*) FROM entities e JOIN collections c ON e.collection_id = c.id WHERE c.name = ?";

    @Autowired
    private CustomIngestService customIngestService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private EmbeddingService embeddingService;

    @MockitoBean
    private GeneralSummarizer generalSummarizer;

    @TempDir
    Path tempDir;

    private Path zipPath;

    @BeforeEach
    public void setUp() throws Exception {
        cleanup();
        // Collections are never auto-created by ingest; the target must pre-exist.
        jdbcTemplate.update(
            "INSERT INTO collections (id, name) VALUES (?, ?) ON CONFLICT (name) DO NOTHING",
            UUID.randomUUID(), COLLECTION);

        zipPath = writeZip("custom.zip", Map.of("docs/demo.md", markdown("table", "DEMO_TABLE"),
            "docs/other.md", markdown("table", "OTHER_TABLE"), "graph/entities.jsonl", """
                {"type":"table","name":"DEMO_TABLE","description":"the demo table"}
                {"type":"table","name":"OTHER_TABLE","description":"another table"}
                """, "graph/relationships.jsonl",
            "{\"source\":\"table:DEMO_TABLE\",\"type\":\"references\","
                + "\"target\":\"table:OTHER_TABLE\",\"description\":\"demo refs other\"}\n"));

        // Deterministic embedding: one 1024-dim zero vector per input (chunks and entity descs).
        when(embeddingService.embedBatch(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            List<float[]> out = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                out.add(new float[1024]);
            }
            return out;
        });
    }

    @AfterEach
    public void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbcTemplate.update("DELETE FROM collections WHERE name = ?", COLLECTION);
    }

    /**
     * Markdown lives under a subdirectory so the default {@code **}{@code /*.md} glob matches
     * unambiguously. The frontmatter declares the kind-aware identity the graph entity links to.
     */
    private static String markdown(String kind, String name) {
        return """
            ---
            kind: %s
            name: %s
            title: %s
            ---
            # %s

            Content describing %s.
            """.formatted(kind, name, name, name, name);
    }

    private Path writeZip(String zipName, Map<String, String> entries) throws IOException {
        Path zip = tempDir.resolve(zipName);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                putEntry(zos, entry.getKey(), entry.getValue());
            }
        }
        return zip;
    }

    private static void putEntry(ZipOutputStream zos, String name, String content)
        throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private IngestionJob job(Map<String, Object> settings) {
        return job(zipPath, settings);
    }

    private IngestionJob job(Path zip, Map<String, Object> settings) {
        return new IngestionJob(UUID.randomUUID(), IngestJobKind.CUSTOM.getValue(), zip.toString(),
            List.of(VERSION), UUID.randomUUID(), COLLECTION, JobStatus.RUNNING, JobStep.CHUNK, 0, 1,
            null, null, OffsetDateTime.now(), OffsetDateTime.now(), null, settings);
    }

    private JobContext ctxFor(IngestionJob job) {
        return new JobContext() {
            @Override
            public IngestionJob job() {
                return job;
            }

            @Override
            public void report(JobStep step, int progress) {
                // no-op for the test
            }
        };
    }

    @Test
    public void ingestPersistsDocumentsChunksAndGraphFromZip() {
        IngestionJob job = job(Map.of());
        JobCounts counts = customIngestService.ingest(job, ctxFor(job));

        assertThat(counts.docs()).isEqualTo(2);
        assertThat(counts.chunks()).isEqualTo(2);
        assertThat(counts.entities()).isEqualTo(2);
        assertThat(counts.edges()).isEqualTo(1);

        assertThat(count(DOC_COUNT_SQL)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM chunks ch JOIN collections c ON ch.collection_id = c.id WHERE c.name = ?"))
            .isEqualTo(2);
        assertThat(count(ENTITY_COUNT_SQL)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM relationships r JOIN collections c ON r.collection_id = c.id WHERE c.name = ?"))
            .isEqualTo(1);

        List<String> statuses = jdbcTemplate.queryForList(
            "SELECT d.ingestion_status FROM documents d JOIN collections c ON d.collection_id = c.id WHERE c.name = ?",
            String.class, COLLECTION);
        assertThat(statuses).containsOnly("completed");

        // The invariant the whole fail-fast contract exists for: every entity carries its document.
        List<String> documentIds = jdbcTemplate.queryForList(
            "SELECT e.metadata->>'document_id' FROM entities e JOIN collections c ON e.collection_id = c.id WHERE c.name = ?",
            String.class, COLLECTION);
        assertThat(documentIds).hasSize(2).doesNotContainNull();
    }

    /**
     * Renaming a file between two ingests of the same corpus leaves the surviving document carrying
     * TWO full chunk sets. This is characterization, not approval: it is the one gap in the
     * replace-don't-append contract, and it exists because two identity rules disagree.
     *
     * <p>
     * {@code persistDocument} clears the previous version with a DELETE keyed on
     * {@code metadata-&gt;&gt;'source_file'}, and then calls {@code upsertManualDocument}, whose
     * {@code findExisting} deliberately ALSO matches on {@code title} — the manuals rule, so a
     * re-ingest under a new file name is recognised as the same document. Under a rename those two
     * rules point at different rows: the DELETE matches nothing (the stored row still holds the old
     * file name), the title match then adopts that very row, and {@code insertChunks} appends to it
     * — this path calls no {@code deleteContentForDocument}, because the DELETE was supposed to have
     * removed the document entirely.
     *
     * <p>
     * Nothing reports it. The job counts one document and one chunk, exactly as a clean run does;
     * the adopted row silently keeps the OLD {@code source_file}, so the corpus browser and every
     * citation still name a file that is no longer in the zip. The damage is in retrieval: the same
     * passage is returned twice, and the duplicated text shifts the BM25 term statistics of the
     * whole partition a little further with every rename.
     *
     * <p>
     * {@code reIngestingTheSameCorpusReplacesItInsteadOfAppending} cannot see this — it re-runs the
     * SAME zip, so the DELETE always hits and the append never happens.
     * {@code DocumentRepositoryWriteIT.manualUpsertStillMatchesOnTitle} pins the title adoption in
     * isolation at the repository. The interaction between them has no witness, which means both a
     * regression (dropping the title match, splitting every renamed manual into two documents) and
     * a fix (deleting by title too, or clearing the adopted row's chunks) would land unobserved.
     */
    @Test
    public void aRenamedSourceFileWithTheSameTitleAppendsASecondChunkSetToTheSurvivingDocument()
        throws IOException {
        String content = markdown("table", "DEMO_TABLE");

        Path before = writeZip("renamed-before.zip", Map.of("docs/demo.md", content));
        IngestionJob first = job(before, Map.of("enableGraph", false));
        customIngestService.ingest(first, ctxFor(first));
        assertThat(count(DOC_COUNT_SQL)).isEqualTo(1);

        // Same document, same title, new file name - a routine corpus-export rename.
        Path after = writeZip("renamed-after.zip", Map.of("docs/demo-v2.md", content));
        IngestionJob second = job(after, Map.of("enableGraph", false));
        JobCounts counts = customIngestService.ingest(second, ctxFor(second));

        assertThat(counts.docs()).isEqualTo(1);
        assertThat(counts.chunks()).as("the job reports exactly what a clean run reports")
            .isEqualTo(1);
        assertThat(count(DOC_COUNT_SQL))
            .as("the title match adopts the existing row instead of creating a second document")
            .isEqualTo(1);

        String sourceFile = jdbcTemplate.queryForObject(
            "SELECT d.metadata->>'source_file' FROM documents d "
                + "JOIN collections c ON d.collection_id = c.id WHERE c.name = ?",
            String.class, COLLECTION);
        assertThat(sourceFile)
            .as("the adopted row keeps the OLD file name - nothing updates it, so citations name a"
                + " file the corpus no longer contains")
            .isEqualTo("docs/demo.md");

        List<String> chunkTexts = jdbcTemplate.queryForList(
            "SELECT ch.text FROM chunks ch JOIN collections c ON ch.collection_id = c.id "
                + "WHERE c.name = ?",
            String.class, COLLECTION);
        assertThat(chunkTexts)
            .as("the source_file-keyed DELETE missed, so this run APPENDED a second chunk set")
            .hasSize(2);
        assertThat(chunkTexts.get(0)).as("and the two sets are the identical passage, twice")
            .isEqualTo(chunkTexts.get(1));
    }

    /**
     * A re-ingest REPLACES the previous corpus for a {@code source_file} rather than appending to
     * it: each document is persisted in its own transaction that first DELETEs same-{@code
     * source_file} documents in this collection whose tags overlap (or are empty), then upserts and
     * inserts chunks. If it appended instead, every re-run would duplicate every chunk — retrieval
     * would return the same passage several times and BM25 statistics would drift with each run,
     * with the job reporting a perfectly normal doc/chunk count each time.
     *
     * <p>
     * Entities are upserted in place rather than duplicated, and — the part that regressed before —
     * their {@code document_id} must follow the recreated document, since the custom path mints a
     * NEW document uuid on every run.
     */
    @Test
    public void reIngestingTheSameCorpusReplacesItInsteadOfAppending() {
        IngestionJob first = job(Map.of());
        customIngestService.ingest(first, ctxFor(first));

        List<String> firstDocIds = jdbcTemplate.queryForList(
            "SELECT d.id::text FROM documents d JOIN collections c ON d.collection_id = c.id WHERE c.name = ?",
            String.class, COLLECTION);

        IngestionJob second = job(Map.of());
        JobCounts counts = customIngestService.ingest(second, ctxFor(second));

        assertThat(counts.docs()).isEqualTo(2);
        assertThat(count(DOC_COUNT_SQL)).as("re-ingest must replace, never append").isEqualTo(2);
        assertThat(count(
            "SELECT count(*) FROM chunks ch JOIN collections c ON ch.collection_id = c.id WHERE c.name = ?"))
                .as("duplicated chunks would silently corrupt retrieval and BM25 stats")
                .isEqualTo(2);
        assertThat(count(ENTITY_COUNT_SQL)).as("entities are upserted, not duplicated").isEqualTo(2);
        assertThat(count(
            "SELECT count(*) FROM relationships r JOIN collections c ON r.collection_id = c.id WHERE c.name = ?"))
                .isEqualTo(1);

        // The documents really were recreated (new uuids) ...
        List<String> secondDocIds = jdbcTemplate.queryForList(
            "SELECT d.id::text FROM documents d JOIN collections c ON d.collection_id = c.id WHERE c.name = ?",
            String.class, COLLECTION);
        assertThat(secondDocIds).doesNotContainAnyElementsOf(firstDocIds);

        // ... and every surviving entity points at one of the NEW documents, not a deleted one.
        List<String> entityDocIds = jdbcTemplate.queryForList(
            "SELECT e.metadata->>'document_id' FROM entities e JOIN collections c ON e.collection_id = c.id WHERE c.name = ?",
            String.class, COLLECTION);
        assertThat(entityDocIds).hasSize(2).doesNotContainNull()
            .allSatisfy(id -> assertThat(secondDocIds).contains(id));
    }

    /**
     * Zip-slip: a corpus zip is attacker-influenced input (any ROLE_INGEST caller can upload one),
     * so an entry resolving outside the extraction directory must never be written. TWO layers do
     * this and they behave differently, which is worth pinning explicitly:
     *
     * <ul>
     * <li>A {@code ../} traversal is caught by the earlier dot-path filter
     * ({@code name.startsWith(".")} / {@code name.contains("/.")}) and SKIPPED silently — it never
     * reaches the zip-slip check.</li>
     * <li>An ABSOLUTE entry name has no dot to filter on, so it is the vector that actually reaches
     * {@code !entryPath.startsWith(destDir)} and FAILS the job. ({@code destDir.resolve("/etc/x")}
     * returns the absolute path, discarding destDir entirely.)</li>
     * </ul>
     *
     * Removing either layer alone still leaves the other, so both are asserted: the guard that
     * throws, and the filter that silently drops.
     */
    @Test
    public void aZipEntryEscapingTheExtractionDirectoryNeverLandsOutsideIt() throws IOException {
        Path absolute = tempDir.resolve("absolute.zip");
        Path escapeTarget = tempDir.resolve("pwned.md");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(absolute))) {
            putEntry(zos, "docs/ok.md", markdown("table", "OK_TABLE"));
            putEntry(zos, escapeTarget.toAbsolutePath().toString(), "owned");
        }
        IngestionJob absJob = job(absolute, Map.of());
        assertThatThrownBy(() -> customIngestService.ingest(absJob, ctxFor(absJob)))
            .hasStackTraceContaining("zip-slip");
        assertThat(Files.exists(escapeTarget)).as("nothing may be written outside the extract dir")
            .isFalse();
        assertThat(count(DOC_COUNT_SQL)).as("a rejected zip leaves no half-corpus behind").isZero();

        // The traversal form is dropped by the dot-path filter instead — no throw, but also no
        // escape, and the legitimate sibling entry still has to be ingestable.
        Path traversal = tempDir.resolve("traversal.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(traversal))) {
            putEntry(zos, "docs/demo.md", markdown("table", "DEMO_TABLE"));
            putEntry(zos, "../../escaped.md", "owned");
        }
        IngestionJob travJob = job(traversal, Map.of("enableGraph", false));
        customIngestService.ingest(travJob, ctxFor(travJob));
        assertThat(Files.exists(tempDir.getParent().resolve("escaped.md"))).isFalse();
        assertThat(count(DOC_COUNT_SQL)).as("the legitimate entry still ingests").isEqualTo(1);
    }

    /**
     * Every chunk must be persisted with the embedding computed for ITS OWN text.
     *
     * <p>
     * Two independent orderings have to agree and nothing in production ties them together.
     * {@code parseAndChunk} submits one parse task per file to a virtual-thread executor and drains
     * the futures in SUBMISSION order, appending each document's chunk texts to one flat
     * {@code allChunkTexts}; {@code embedAndPersistDocuments} then issues a single
     * {@code embedBatch(allChunkTexts)} and walks the result with one running {@code embeddingIndex}
     * in document order. The only guard is a SIZE check, which passes just as happily when the
     * vectors are shuffled. Draining via a {@code CompletionService} (completion order), or
     * reordering {@code docs} anywhere between parse and persist, would give every chunk a different
     * chunk's vector — no exception, normal job counts, and retrieval quietly returning the wrong
     * passages for every query.
     *
     * <p>
     * Note the scope precisely: on THIS path {@code processFile} ends with
     * {@code List.of(body)} — one document is always exactly one chunk — so the running index
     * advances by one per document and the exposure is document ORDER, not a within-document
     * offset. That is also why the test uses several documents of differing sizes: their parse
     * durations diverge, so completion order is not submission order, and a drain that took
     * whichever future finished first would scramble them. Should the custom path ever start
     * splitting documents, this test keeps holding without modification.
     *
     * <p>
     * The other tests in this class cannot see any of that: their {@code embedBatch} stub returns
     * all-zero vectors, so every chunk's embedding is identical and misalignment is undetectable by
     * construction. Here the vector's first component fingerprints the input text, so the assertion
     * catches misalignment from any cause — reordered futures, a reordered document list, an
     * off-by-one in the index — rather than only the mechanism that happened to be anticipated. The
     * fingerprint is kept under 100_000 so it is an exactly-representable float32 and the read-back
     * comparison is exact.
     */
    @Test
    public void eachChunkKeepsTheEmbeddingComputedForItsOwnText() throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        for (int i = 0; i < DOC_COUNT; i++) {
            entries.put("docs/f" + i + ".md", multiSectionMarkdown("DOC_" + i, i + 1));
        }
        Path multiZip = writeZip("alignment.zip", entries);

        when(embeddingService.embedBatch(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            List<float[]> out = new ArrayList<>(texts.size());
            for (String text : texts) {
                float[] v = new float[1024];
                v[0] = fingerprint(text);
                out.add(v);
            }
            return out;
        });

        IngestionJob job = job(multiZip, Map.of("enableGraph", false));
        JobCounts counts = customIngestService.ingest(job, ctxFor(job));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT ch.text, split_part(ltrim(ch.embedding::text, '['), ',', 1) AS first_component
            FROM chunks ch JOIN collections c ON ch.collection_id = c.id
            WHERE c.name = ?
            """, COLLECTION);

        // Both halves matter: hasSize(DOC_COUNT) proves nothing was dropped (an empty or short
        // result would make the per-row loop below vacuously pass), and matching counts.chunks()
        // proves the number the job REPORTED is the number that actually landed.
        assertThat(rows).hasSize(DOC_COUNT).hasSize(counts.chunks());

        for (Map<String, Object> row : rows) {
            String text = (String) row.get("text");
            float stored = Float.parseFloat((String) row.get("first_component"));
            assertThat(stored)
                .as("chunk was persisted with another chunk's embedding; its text was:%n%s", text)
                .isEqualTo(fingerprint(text));
        }
    }

    /** Enough documents that a completion-ordered drain would land them out of order. */
    private static final int DOC_COUNT = 6;

    /** Small enough to be an exact float32, stable across JVMs for a given String. */
    private static float fingerprint(String text) {
        return Math.abs(text.hashCode() % 100_000);
    }

    /** A document of {@code sections} H2 sections, each with distinct body text. */
    private static String multiSectionMarkdown(String name, int sections) {
        StringBuilder sb = new StringBuilder("""
            ---
            kind: table
            name: %s
            title: %s
            ---
            # %s
            """.formatted(name, name, name));
        for (int s = 0; s < sections; s++) {
            sb.append("\n## Section %d of %s\n\n".formatted(s, name));
            sb.append("Body text for section %d of %s. ".formatted(s, name).repeat(10 + s * 5));
            sb.append('\n');
        }
        return sb.toString();
    }

    @Test
    public void ingestWithGraphDisabledPersistsDocsButSkipsGraph() {
        IngestionJob job = job(Map.of("enableGraph", false));
        JobCounts counts = customIngestService.ingest(job, ctxFor(job));

        assertThat(counts.docs()).isEqualTo(2);
        assertThat(counts.chunks()).isEqualTo(2);
        assertThat(counts.entities()).isZero();
        assertThat(counts.edges()).isZero();

        assertThat(count(DOC_COUNT_SQL)).isEqualTo(2);
        assertThat(count(ENTITY_COUNT_SQL)).isZero();
    }

    /**
     * The real defect, in its real shape: {@code display_name: %Globals...%} starts a plain YAML
     * scalar with the reserved {@code %} indicator, so the whole frontmatter block fails to parse.
     * The document used to fall back to {@code kind='other'} — which then matched no {@code table}
     * entity, leaving the entity with no {@code document_id} across two full re-ingests.
     */
    @Test
    public void unparseableFrontmatterFailsTheJobNamingTheFileAndNeverLandsAsKindOther()
        throws IOException {
        String broken = """
            ---
            kind: table
            name: AERole
            display_name: %Globals.QIM_ProductNameShort% application roles
            ---
            # AERole

            The application roles table.
            """;
        Path zip = writeZip("broken-frontmatter.zip",
            Map.of("docs/aerole.md", broken, "docs/demo.md", markdown("table", "DEMO_TABLE"),
                "graph/entities.jsonl",
                "{\"type\":\"table\",\"name\":\"AERole\",\"description\":\"roles\"}\n"
                    + "{\"type\":\"table\",\"name\":\"DEMO_TABLE\",\"description\":\"demo\"}\n",
                "graph/relationships.jsonl", ""));
        IngestionJob job = job(zip, Map.of());

        assertThatThrownBy(() -> customIngestService.ingest(job, ctxFor(job)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unparseable YAML frontmatter")
            .hasMessageContaining("docs/aerole.md");

        // Nothing was persisted at all — in particular no kind='other' stand-in for the broken doc.
        assertThat(count(DOC_COUNT_SQL)).isZero();
        assertThat(count(
            "SELECT count(*) FROM documents d JOIN collections c ON d.collection_id = c.id WHERE c.name = ? AND d.kind = 'other'"))
                .isZero();
        assertThat(count(ENTITY_COUNT_SQL)).isZero();
    }

    /**
     * The {@code needs_summary} branch that carries NO {@code summarize_headings}: the model must be
     * handed the EXTRACTED sql/vbnet code, and the document that lands must be the prose summary
     * with {@code ## Source} REPLACED — not appended to, not left in place.
     *
     * <p>
     * This is the entire reason the branch exists. The script/procedure corpus is multi-kilobyte
     * T-SQL and VB.NET; storing the raw source as the chunk makes every retrieval hit return code
     * where the agent expects a description, and embedding it puts the vector in the wrong
     * neighbourhood of the space. A regression in either half is invisible: the job reports one
     * document and one chunk exactly as it does today, and the only symptom is the corpus itself.
     *
     * <p>
     * Nothing exercised this path before. {@code SchemaIngestServiceIT}'s procedure fixture uses
     * {@code summarize_headings} (and asserts {@code ## Summary} is ABSENT, which is the OTHER
     * branch), its script fixture requests no summary at all, and every fixture in this class
     * declares neither flag — so {@code extractCodeBlocks} and {@code replaceSourceWithSummary}
     * had no witness whatsoever.
     *
     * <p>
     * The job also names a summarize prompt that is not registered. That resolves to {@code null}
     * and the summarizer then runs with an EMPTY system prompt — this is the one settings key whose
     * unknown value does NOT fall back to {@code default-summarize}, which makes it exactly the
     * behaviour a well-meaning "add a fallback like the others" edit would change. Asserted here so
     * the asymmetry is at least written down in an executable place.
     */
    @Test
    public void aNeedsSummaryDocumentStoresProseAndItsSourceBlockIsReplacedNotKept()
        throws IOException {
        String procedure = """
            ---
            kind: procedure
            name: QBM_PWatchOperation
            title: QBM_PWatchOperation
            needs_summary: true
            ---
            # QBM_PWatchOperation

            ## Source

            ```sql
            CREATE PROCEDURE dbo.QBM_PWatchOperation AS BEGIN SELECT 1 END
            ```
            """;
        Path zip = writeZip("needs-summary.zip", Map.of("docs/proc.md", procedure));
        when(generalSummarizer.summarize(any(), any(), any(), any()))
            .thenReturn("Watches pending operations and reports their state.");

        IngestionJob job = job(zip,
            Map.of("enableGraph", false, "summarizePrompt", "no-such-summarize-prompt"));
        JobCounts counts = customIngestService.ingest(job, ctxFor(job));
        assertThat(counts.docs()).isEqualTo(1);

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(generalSummarizer).summarize(content.capture(), eq("procedure"),
            systemPrompt.capture(), anyString());
        assertThat(content.getValue().strip())
            .as("the model must receive the extracted code block, not the whole markdown document")
            .isEqualTo("CREATE PROCEDURE dbo.QBM_PWatchOperation AS BEGIN SELECT 1 END");
        assertThat(systemPrompt.getValue())
            .as("an unregistered summarizePrompt resolves to null — no default fallback here")
            .isNull();

        String stored = jdbcTemplate.queryForObject(
            "SELECT ch.text FROM chunks ch JOIN collections c ON ch.collection_id = c.id WHERE c.name = ?",
            String.class, COLLECTION);
        assertThat(stored).as("the stored chunk must be prose, and the raw source must be gone")
            .contains("## Summary").contains("Watches pending operations and reports their state.")
            .doesNotContain("## Source").doesNotContain("CREATE PROCEDURE");
    }

    /**
     * The custom path's contract is "fail loudly on loss" — unparseable frontmatter, an entity with
     * no document and an unresolvable edge endpoint each abort the job and name the offenders. There
     * are exactly THREE losses it stays silent about, and this pins two of them so the asymmetry is
     * written down somewhere executable instead of being an accident of which branch returns null.
     *
     * <ul>
     * <li>A file with NO {@code ---} block at all: {@code readMd} returns null before any frontmatter
     * is parsed, so nothing is collected as a failure.</li>
     * <li>A file whose body is empty once frontmatter (and, on the summarize branch, summarisation)
     * is done: {@code chunks} comes out empty and the document is dropped.</li>
     * </ul>
     *
     * <p>
     * (The third, an unreadable file, is the same shape via {@code readMd}'s IOException branch and
     * is left out here because forcing an unreadable file is platform-dependent.)
     *
     * <p>
     * Neither is counted, neither is reported: the job completes green with a docs count that is
     * simply SMALLER, which is invisible unless someone already knows how many files the zip held.
     * The document never appears in the corpus, so every later retrieval and every MCP tool answers
     * "this is not in the corpus" for content that was uploaded. That is exactly the outcome this
     * section forbids everywhere else, so it needs to be a deliberate, visible decision — if a future
     * change starts failing the job on these, this test is where that gets discussed rather than
     * discovered.
     *
     * <p>
     * No fixture in this class or in {@code SchemaIngestServiceIT} has ever contained a
     * frontmatter-less or body-less file, so both branches were unexecuted.
     */
    @Test
    public void aFileWithNoFrontmatterOrNoBodyIsDroppedSilentlyAndUncounted() throws IOException {
        Path zip = writeZip("silent-losses.zip", Map.of("docs/demo.md",
            markdown("table", "DEMO_TABLE"), "docs/plain.md",
            "# Plain\n\nA document with no frontmatter block at all.\n", "docs/empty.md",
            "---\nkind: table\nname: EMPTY_DOC\ntitle: Empty\n---\n"));
        IngestionJob job = job(zip, Map.of("enableGraph", false));

        JobCounts counts = customIngestService.ingest(job, ctxFor(job));

        assertThat(counts.docs())
            .as("two of the three files vanish with no exception and no count of their own")
            .isEqualTo(1);
        assertThat(counts.chunks()).isEqualTo(1);
        assertThat(count(DOC_COUNT_SQL)).isEqualTo(1);

        List<String> sourceFiles = jdbcTemplate.queryForList(
            "SELECT d.metadata->>'source_file' FROM documents d JOIN collections c ON d.collection_id = c.id WHERE c.name = ?",
            String.class, COLLECTION);
        assertThat(sourceFiles).containsExactly("docs/demo.md");
        // In particular the frontmatter-less file must not land as a kind='other' stand-in either:
        // that is the degradation the strict parser exists to prevent on the OTHER branch.
        assertThat(count(
            "SELECT count(*) FROM documents d JOIN collections c ON d.collection_id = c.id WHERE c.name = ? AND d.kind = 'other'"))
                .isZero();
    }

    /** Every entity must be backed by a document of the same (kind, name) — no exception. */
    @Test
    public void entityWithoutAMatchingDocumentFailsTheJobNamingTheEntity() throws IOException {
        Path zip = writeZip("orphan-entity.zip",
            Map.of("docs/demo.md", markdown("table", "DEMO_TABLE"), "graph/entities.jsonl",
                "{\"type\":\"table\",\"name\":\"DEMO_TABLE\",\"description\":\"demo\"}\n"
                    + "{\"type\":\"table\",\"name\":\"QERVPersonAndAERoles\",\"description\":\"orphan\"}\n",
                "graph/relationships.jsonl", ""));
        IngestionJob job = job(zip, Map.of());

        assertThatThrownBy(() -> customIngestService.ingest(job, ctxFor(job)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("1 graph entit(ies) resolved to no document")
            .hasMessageContaining("table:QERVPersonAndAERoles");

        assertThat(count(ENTITY_COUNT_SQL)).isZero();
    }

    /**
     * A corpus zip with no {@code graph/} directory must ingest cleanly at the DEFAULT graph
     * setting.
     *
     * <p>
     * {@code isGraphEnabled} defaults to TRUE when the key is absent, so a plain-markdown zip — a
     * completely normal input, and what the admin form produces when nothing about a graph is
     * chosen — DOES enter {@code injectKnowledgeGraph}. The only thing that stops it is the
     * {@code entitiesPath == null || relsPath == null} guard: without it the very next statement
     * parses a null path. The failure lands AFTER every document and chunk has already been
     * committed (each document is persisted in its own transaction), so the corpus is half-written
     * and the job is marked failed with a stack trace that names neither the zip nor the missing
     * files.
     *
     * <p>
     * Every other graph-enabled fixture in this class ships both jsonl files, and the one zip
     * without them runs with {@code enableGraph=false} — so the early return has never been taken
     * by any test.
     */
    @Test
    public void aZipWithoutGraphFilesIngestsCleanlyAtTheDefaultGraphSetting() throws IOException {
        Path plain =
            writeZip("no-graph.zip", Map.of("docs/demo.md", markdown("table", "DEMO_TABLE")));
        // Deliberately NO enableGraph key: injection is attempted and must no-op, not fail.
        IngestionJob job = job(plain, Map.of());
        JobCounts counts = customIngestService.ingest(job, ctxFor(job));

        assertThat(counts.docs()).isEqualTo(1);
        assertThat(counts.chunks()).isEqualTo(1);
        assertThat(counts.entities()).isZero();
        assertThat(counts.edges()).isZero();
        assertThat(count(DOC_COUNT_SQL)).as("the documents must still be there").isEqualTo(1);
        assertThat(count(ENTITY_COUNT_SQL)).isZero();
    }

    /** An edge endpoint no entity carries is a dropped link; the job must stop and say so. */
    @Test
    public void unknownEdgeEndpointFailsTheJob() throws IOException {
        Path zip = writeZip("unknown-endpoint.zip",
            Map.of("docs/demo.md", markdown("table", "DEMO_TABLE"), "graph/entities.jsonl",
                "{\"type\":\"table\",\"name\":\"DEMO_TABLE\",\"description\":\"demo\"}\n",
                "graph/relationships.jsonl",
                "{\"source\":\"table:DEMO_TABLE\",\"type\":\"references\",\"target\":\"table:GHOST\"}\n"));
        IngestionJob job = job(zip, Map.of());

        assertThatThrownBy(() -> customIngestService.ingest(job, ctxFor(job)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Unknown endpoint")
            .hasMessageContaining("table:GHOST");
    }

    /** A bare endpoint name carried by several kinds cannot pick a homonym; it must fail loudly. */
    @Test
    public void ambiguousBareEdgeEndpointFailsTheJob() throws IOException {
        Path zip = writeZip("ambiguous-endpoint.zip", Map.of("docs/person-table.md",
            markdown("table", "Person"), "docs/person-notification.md",
            markdown("notification", "Person"), "docs/demo.md", markdown("table", "DEMO_TABLE"),
            "graph/entities.jsonl",
            "{\"type\":\"table\",\"name\":\"Person\",\"description\":\"person table\"}\n"
                + "{\"type\":\"notification\",\"name\":\"Person\",\"description\":\"person notification\"}\n"
                + "{\"type\":\"table\",\"name\":\"DEMO_TABLE\",\"description\":\"demo\"}\n",
            "graph/relationships.jsonl",
            "{\"source\":\"Person\",\"type\":\"references\",\"target\":\"table:DEMO_TABLE\"}\n"));
        IngestionJob job = job(zip, Map.of());

        assertThatThrownBy(() -> customIngestService.ingest(job, ctxFor(job)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Ambiguous endpoint")
            .hasMessageContaining("Person (kinds: notification, table)");
    }

    /**
     * Embedding must never happen inside a database transaction.
     *
     * <p>
     * {@code embedBatch} is a multi-second call to a remote provider over the whole chunk set. Held
     * inside a {@code TransactionTemplate} it pins a pooled connection for its entire duration:
     * with {@code app.worker.concurrency} ingests in flight the pool is exhausted by calls that are
     * doing nothing but waiting on HTTP, and Postgres fills with idle-in-transaction sessions. There
     * is no error to read afterwards — unrelated requests simply block on connection acquisition,
     * and nothing points at the ingest that caused it.
     *
     * <p>
     * The separation is expressed only by WHERE the call sits: {@code embedAndPersistDocuments}
     * embeds first and then opens one transaction per document. Moving the embed inside that
     * transaction (or widening the transaction to cover the batch, which looks like a tidy-up)
     * changes nothing any existing assertion can see — the same rows land, the same counts are
     * reported. This test observes the only thing that distinguishes them, from inside the stub the
     * ingest calls: whether a transaction is active at that moment.
     */
    @Test
    public void chunkEmbeddingHappensOutsideAnyDatabaseTransaction() {
        AtomicBoolean embedded = new AtomicBoolean(false);
        AtomicBoolean insideTransaction = new AtomicBoolean(false);
        when(embeddingService.embedBatch(anyList())).thenAnswer(inv -> {
            embedded.set(true);
            insideTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            List<String> texts = inv.getArgument(0);
            List<float[]> out = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                out.add(new float[1024]);
            }
            return out;
        });

        IngestionJob job = job(Map.of("enableGraph", false));
        JobCounts counts = customIngestService.ingest(job, ctxFor(job));

        assertThat(counts.chunks()).isEqualTo(2);
        assertThat(embedded.get()).as("the embedding call must actually have been made").isTrue();
        assertThat(insideTransaction.get())
            .as("a provider round-trip inside a transaction holds a pooled connection open for its"
                + " whole duration — pool exhaustion and idle-in-transaction under any concurrency")
            .isFalse();
    }

    private int count(String sql) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, COLLECTION);
        return n == null ? 0 : n;
    }
}
