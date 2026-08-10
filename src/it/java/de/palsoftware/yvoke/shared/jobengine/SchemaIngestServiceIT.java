package de.palsoftware.yvoke.shared.jobengine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import de.palsoftware.yvoke.ingest.core.model.IngestJobKind;
import de.palsoftware.yvoke.ingest.core.service.GeneralSummarizer;
import de.palsoftware.yvoke.rag.retrieval.EmbeddingService;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueRequest;
import de.palsoftware.yvoke.shared.jobengine.model.IngestionJob;
import de.palsoftware.yvoke.shared.jobengine.model.JobStatus;
import de.palsoftware.yvoke.shared.jobengine.repository.JobRepository;
import de.palsoftware.yvoke.shared.jobengine.service.JobService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SpringBootTest(properties = {"spring.flyway.enabled=true",
    "spring.flyway.locations=filesystem:docker/db/migration", "app.worker.enabled=true",
    "app.worker.concurrency=2", "app.worker.poll-interval=200ms",
    // The UploadPathGuard confines file-backed sourceRefs to app.upload-dir; @TempDir fixtures
    // live under the JVM temp dir, so widen the permitted root to it for this test.
    "app.upload-dir=${java.io.tmpdir}"})
@DirtiesContext
public class SchemaIngestServiceIT {

    private static final String COLLECTION = "OIM-SCHEMA-INGEST-TEST";
    private static final String VERSION = "10.0";

    @Autowired
    private JobService jobService;

    @Autowired
    private JobRepository jobRepository;

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
        UUID collId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO collections (id, name, description, tags) VALUES (?, ?, ?, ?)", collId,
            COLLECTION, "Test collection", new String[] {VERSION});

        // Setup mock Voyage embeddings
        when(embeddingService.embedBatch(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            List<float[]> out = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                out.add(new float[1024]);
            }
            return out;
        });

        // Setup mock summarizer
        when(generalSummarizer.summarize(anyString(), anyString(), any(), anyString()))
            .thenAnswer(inv -> {
                String userMsg = inv.getArgument(3);
                String name = "unknown";
                if (userMsg != null) {
                    Matcher matcher =
                        Pattern.compile("Object name: `([^`]+)`").matcher(userMsg);
                    if (matcher.find()) {
                        name = matcher.group(1);
                    }
                }
                return "Summary of " + name;
            });

        // Create a mock OIM DB extract structure on disk
        Path srcDir = Files.createDirectories(tempDir.resolve("extract_src"));
        Path tablesDir = Files.createDirectories(srcDir.resolve("tables"));
        Path procsDir = Files.createDirectories(srcDir.resolve("procedures"));
        Path scriptsDir = Files.createDirectories(srcDir.resolve("scripts"));
        Path configDir = Files.createDirectories(srcDir.resolve("config"));

        // 0. Create config files. The name MUST be quoted: an unquoted plain scalar containing
        // ": " is not valid YAML, and unparseable frontmatter now aborts the job instead of
        // silently degrading the document to kind='other' (which stranded its config entity
        // without a document_id).
        String configMd = """
            ---
            name: "Config: Common"
            kind: config
            ---
            ## `Common\\MailNotification\\Encrypt\\AuthenticationType`
            Specifies encryption authentication type.
            """;
        Files.writeString(configDir.resolve("Common.md"), configMd, StandardCharsets.UTF_8);

        // 1. Create table files
        String adsAccountMd = """
            ---
            name: ADSAccount
            display_name: Active Directory Account
            kind: table
            approx_row_count: 120
            ---
            # Active Directory Account

            ## Description
            Holds AD account properties.

            ## Relationships (OIM-level / QBMRelation)
            | Child Column | Parent Table | Parent Column | Proxy | Comment |
            |---|---|---|---|---|
            | UID_Person | Person | UID_Person | N | Links to person |
            """;
        Files.writeString(tablesDir.resolve("ADSAccount.md"), adsAccountMd, StandardCharsets.UTF_8);

        String personMd = """
            ---
            name: Person
            display_name: Employee
            kind: table
            ---
            # Employee

            ## Description
            Stores employee identity details.
            """;
        Files.writeString(tablesDir.resolve("Person.md"), personMd, StandardCharsets.UTF_8);

        // 2. Create procedure file
        String procMd = """
            ---
            name: QBM_Proc1
            kind: procedure
            summarize_headings:
              - Source
            ---
            # Populate Proc 1

            ## Source
            ```sql
            CREATE PROCEDURE QBM_Proc1 AS
            BEGIN
                -- Reference table Person
                SELECT * FROM Person;
            END
            ```
            """;
        Files.writeString(procsDir.resolve("QBM_Proc1.md"), procMd, StandardCharsets.UTF_8);

        // 3. Create script file
        String scriptMd = """
            ---
            name: VI_Script1
            kind: script
            ---
            # Script 1

            ## Source
            ```vbnet
            Public Sub VI_Script1()
                ' Call procedure QBM_Proc1 and reference table ADSAccount
                EXEC QBM_Proc1
                SELECT * FROM ADSAccount
            End Sub
            ```
            """;
        Files.writeString(scriptsDir.resolve("VI_Script1.md"), scriptMd, StandardCharsets.UTF_8);

        // 4. Create graph files
        Path graphDir = Files.createDirectories(srcDir.resolve("graph"));
        String entitiesJsonl = """
            {"name": "Config: Common", "type": "config", "description": "Common configuration"}
            {"name": "ADSAccount", "type": "table", "description": "Active Directory Account"}
            {"name": "Person", "type": "table", "description": "Employee"}
            {"name": "QBM_Proc1", "type": "procedure", "description": "Populate Proc 1"}
            {"name": "VI_Script1", "type": "script", "description": "Script 1"}
            """;
        Files.writeString(graphDir.resolve("entities.jsonl"), entitiesJsonl, StandardCharsets.UTF_8);

        String relationshipsJsonl = """
            {"source": "table:ADSAccount", "type": "FK", "target": "table:Person", "description": "Links to person"}
            {"source": "procedure:QBM_Proc1", "type": "CALL", "target": "table:Person", "description": "Reference table Person"}
            {"source": "script:VI_Script1", "type": "CALL", "target": "procedure:QBM_Proc1", "description": "Call procedure QBM_Proc1"}
            {"source": "script:VI_Script1", "type": "CALL", "target": "table:ADSAccount", "description": "Reference table ADSAccount"}
            """;
        Files.writeString(graphDir.resolve("relationships.jsonl"), relationshipsJsonl, StandardCharsets.UTF_8);

        // Package files into a ZIP
        zipPath = tempDir.resolve("db_extract.zip");
        zipDirectory(srcDir, zipPath);
    }

    @AfterEach
    public void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbcTemplate.update("DELETE FROM ingestion_jobs");
        jdbcTemplate.update(
            "DELETE FROM chunks WHERE collection_id IN (SELECT id FROM collections WHERE name = ?)",
            COLLECTION);
        jdbcTemplate.update(
            "DELETE FROM documents WHERE collection_id IN (SELECT id FROM collections WHERE name = ?)",
            COLLECTION);
        jdbcTemplate.update(
            "DELETE FROM relationships WHERE collection_id IN (SELECT id FROM collections WHERE name = ?)",
            COLLECTION);
        jdbcTemplate.update(
            "DELETE FROM entities WHERE collection_id IN (SELECT id FROM collections WHERE name = ?)",
            COLLECTION);
        jdbcTemplate.update("DELETE FROM collections WHERE name = ?", COLLECTION);
    }

    @Test
    public void testSchemaIngestionFlowE2E() {
        // Enqueue the schema ingestion job
        UUID id = jobService.enqueue(new EnqueueRequest(IngestJobKind.CUSTOM.getValue(),
            zipPath.toString(), VERSION, COLLECTION)).jobId();

        // Await job completion
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            IngestionJob job = jobRepository.findById(id).orElseThrow();
            if (job.status() == JobStatus.FAILED) {
                System.out.println("JOB FAILED WITH ERROR: " + job.error());
            }
            assertThat(job.status()).isEqualTo(JobStatus.COMPLETED);
            assertThat(job.progress()).isEqualTo(100);
            assertThat(job.counts()).isNotNull();
            assertThat(job.counts().docs()).isEqualTo(5);
            assertThat(job.counts().chunks()).isEqualTo(5);
            assertThat(job.counts().entities()).isEqualTo(5);
            assertThat(job.counts().edges()).isEqualTo(4);
        });

        // Verify counts in DB
        Integer docCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM documents d JOIN collections c ON d.collection_id = c.id WHERE c.name = ?",
            Integer.class, COLLECTION);
        Integer chunkCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM chunks ch JOIN collections c ON ch.collection_id = c.id WHERE c.name = ?",
            Integer.class, COLLECTION);
        Integer entityCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM entities e JOIN collections c ON e.collection_id = c.id WHERE c.name = ?",
            Integer.class, COLLECTION);
        Integer relCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM relationships r JOIN collections c ON r.collection_id = c.id WHERE c.name = ?",
            Integer.class, COLLECTION);

        assertThat(docCount).isEqualTo(5);
        assertThat(chunkCount).isEqualTo(5);
        assertThat(entityCount).isEqualTo(5);
        assertThat(relCount).isEqualTo(4);

        // Every entity carries the document of the same (kind, name) — the invariant the custom
        // path now refuses to break.
        List<String> entityDocumentIds = jdbcTemplate.queryForList(
            "SELECT e.metadata->>'document_id' FROM entities e JOIN collections c ON e.collection_id = c.id WHERE c.name = ?",
            String.class, COLLECTION);
        assertThat(entityDocumentIds).hasSize(5).doesNotContainNull();

        // Verify that the custom heading summarization was executed and replaced the Source section's body while keeping the heading
        List<String> chunkTexts = jdbcTemplate.queryForList(
            "SELECT ch.text FROM chunks ch JOIN documents d ON ch.document_id = d.id JOIN collections c ON ch.collection_id = c.id WHERE c.name = ? AND d.metadata->>'source_file' = ?",
            String.class, COLLECTION, "procedures/QBM_Proc1.md");
        assertThat(chunkTexts).hasSize(1);
        assertThat(chunkTexts.get(0)).contains("## Source");
        assertThat(chunkTexts.get(0)).contains("Summary of QBM_Proc1");
        assertThat(chunkTexts.get(0)).doesNotContain("CREATE PROCEDURE QBM_Proc1");
        assertThat(chunkTexts.get(0)).doesNotContain("## Summary");

        // Test idempotency: re-running on same collection and version should result in same counts,
        // no duplicates
        UUID secondId = jobService.enqueue(new EnqueueRequest(IngestJobKind.CUSTOM.getValue(),
            zipPath.toString(), VERSION, COLLECTION)).jobId();

        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            IngestionJob job = jobRepository.findById(secondId).orElseThrow();
            if (job.status() == JobStatus.FAILED) {
                System.out.println("SECOND JOB FAILED WITH ERROR: " + job.error());
            }
            assertThat(job.status()).isEqualTo(JobStatus.COMPLETED);
        });

        // DB counts must remain identical (overwrite cleanly, no duplication)
        Integer docCount2 = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM documents d JOIN collections c ON d.collection_id = c.id WHERE c.name = ?",
            Integer.class, COLLECTION);
        Integer chunkCount2 = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM chunks ch JOIN collections c ON ch.collection_id = c.id WHERE c.name = ?",
            Integer.class, COLLECTION);
        Integer entityCount2 = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM entities e JOIN collections c ON e.collection_id = c.id WHERE c.name = ?",
            Integer.class, COLLECTION);
        Integer relCount2 = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM relationships r JOIN collections c ON r.collection_id = c.id WHERE c.name = ?",
            Integer.class, COLLECTION);

        assertThat(docCount2).isEqualTo(5);
        assertThat(chunkCount2).isEqualTo(5);
        assertThat(entityCount2).isEqualTo(5);
        assertThat(relCount2).isEqualTo(4);
    }

    private static void zipDirectory(Path sourceDir, Path zipFile) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            try (var stream = Files.walk(sourceDir)) {
                List<Path> paths = stream.filter(Files::isRegularFile).toList();
                for (Path path : paths) {
                    String relativePath = sourceDir.relativize(path).toString();
                    ZipEntry entry = new ZipEntry(relativePath);
                    zos.putNextEntry(entry);
                    Files.copy(path, zos);
                    zos.closeEntry();
                }
            }
        }
    }
}
