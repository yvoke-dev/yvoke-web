package de.palsoftware.yvoke.ingest.worker;

import de.palsoftware.yvoke.ingest.core.model.IngestJobKind;
import de.palsoftware.yvoke.shared.jobengine.model.*;
import de.palsoftware.yvoke.shared.jobengine.service.*;
import de.palsoftware.yvoke.shared.jobengine.repository.*;
import de.palsoftware.yvoke.document.core.model.*;
import de.palsoftware.yvoke.document.core.service.*;
import de.palsoftware.yvoke.rag.retrieval.EmbeddingService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import de.palsoftware.yvoke.llm.core.model.*;
import de.palsoftware.yvoke.llm.core.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;


@SpringBootTest(properties = {"spring.flyway.enabled=true",
    "spring.flyway.locations=filesystem:docker/db/migration", "app.worker.enabled=true",
    "app.worker.concurrency=2", "app.worker.poll-interval=200ms",
    "app.ai.summarize.model=test-model",
    // UploadPathGuard confines file-backed sourceRefs to app.upload-dir; @TempDir lives under
    // tmpdir.
    "app.upload-dir=${java.io.tmpdir}"})
@DirtiesContext
public class HierarchicalDocumentJobHandlerIT {

    private static final String COLLECTION = "OIM-HIERARCHICAL-HANDLER-TEST";
    private static final String VERSION = "9.3";

    private static final String MARKDOWN = """
        # OIM Authentication Guide

        ## Introduction
        This is the introduction section body text.

        ## OAuth Authentication
        OAuth details and sub-sections follow here.

        ### OAuth Flow
        This details the OAuth redirect flow steps.
        """;

    @Autowired
    private JobService jobService;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TocService tocService;

    @Autowired
    private SectionService sectionService;

    @MockitoBean
    private EmbeddingService embeddingService;

    @MockitoBean(name = "llmProviderClient")
    private LlmClient llmClient;

    @TempDir
    Path tempDir;

    private Path manualPath;

    @BeforeEach
    public void setUp() throws Exception {
        cleanup();
        UUID collId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO collections (id, name, description, tags) VALUES (?, ?, ?, ?)", collId,
            COLLECTION, "Hierarchical test collection", new String[] {VERSION});

        manualPath = tempDir.resolve("hierarchical_guide.md");
        Files.writeString(manualPath, MARKDOWN, StandardCharsets.UTF_8);

        when(embeddingService.embedBatch(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            List<float[]> out = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                out.add(new float[1024]);
            }
            return out;
        });

        when(llmClient.generate(any(LlmRequest.class))).thenAnswer(invocation -> {
            LlmRequest request = invocation.getArgument(0);
            String userMsg =
                request.messages().stream().filter(msg -> "user".equalsIgnoreCase(msg.role()))
                    .map(LlmMessage::content).findFirst().orElse("");

            // Generate a deterministic mock summary based on the section path
            String pathStr = "Unknown section";
            if (userMsg.contains("Section path:")) {
                int start = userMsg.indexOf("Section path:") + 13;
                int end = userMsg.indexOf("\n\n", start);
                if (end != -1) {
                    pathStr = userMsg.substring(start, end).trim();
                }
            }
            String mockSummary = "Summary for " + pathStr + ".";
            return new LlmResponse(mockSummary, new LlmUsage(0, 0, 0, 0, 0));
        });
    }

    @AfterEach
    public void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbcTemplate.update("DELETE FROM ingestion_jobs");
        jdbcTemplate.update("DELETE FROM section_summaries");
        jdbcTemplate.update(
            "DELETE FROM chunks WHERE collection_id IN (SELECT id FROM collections WHERE name = ?)",
            COLLECTION);
        jdbcTemplate.update(
            "DELETE FROM documents WHERE collection_id IN (SELECT id FROM collections WHERE name = ?)",
            COLLECTION);
        jdbcTemplate.update("DELETE FROM collections WHERE name = ?", COLLECTION);
    }

    @Test
    public void hierarchicalJobRunsToCompletionAndSavesSummaries() {
        UUID id = jobService.enqueue(new EnqueueRequest(IngestJobKind.HIERARCHICAL.getValue(),
            manualPath.toString(), VERSION, COLLECTION)).jobId();

        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            IngestionJob job = jobRepository.findById(id).orElseThrow();
            if (job.status() == JobStatus.FAILED) {
                System.err.println("!!! JOB FAILED WITH ERROR: " + job.error());
            }
            assertThat(job.status()).isEqualTo(JobStatus.COMPLETED);
        });

        // 1. Assert document of kind 'hierarchical' exists
        Map<String, Object> doc = jdbcTemplate.queryForMap(
            "SELECT d.id, d.kind, d.title FROM documents d "
                + "JOIN collections c ON d.collection_id = c.id " + "WHERE c.name = ? LIMIT 1",
            COLLECTION);
        assertThat(doc.get("kind")).isEqualTo("hierarchical");
        UUID docId = (UUID) doc.get("id");

        // 2. Assert chunks are present and clean (no breadcrumbs)
        List<Map<String, Object>> chunks = jdbcTemplate.queryForList(
            "SELECT text, heading, sort_order FROM chunks WHERE document_id = ? ORDER BY sort_order",
            docId);

        assertThat(chunks).hasSize(3); // Introduction, OAuth Authentication, OAuth Flow
        assertThat((String) chunks.get(0).get("text"))
            .contains("## Introduction\n\nThis is the introduction section body text.");
        assertThat((String) chunks.get(0).get("text")).doesNotContain("Section path:"); // pure,
                                                                                        // metadata-driven

        // 3. Assert summaries are persisted for leaf and parent paths
        List<Map<String, Object>> summaries = jdbcTemplate.queryForList(
            "SELECT heading_path, summary FROM section_summaries WHERE document_id = ?", docId);

        // Introduction, OAuth Authentication, OAuth Flow, and OAuth Authentication parent node
        assertThat(summaries).hasSize(3);

        // Verify we can fetch TOC with summaries
        List<TocNode> toc = tocService.getToc("OIM Authentication Guide", COLLECTION);
        assertThat(toc).isNotEmpty();
        assertThat(toc.get(0).summary()).contains("Summary for Introduction");

        // Verify get_section retrieves cleanly without breadcrumbs
        SectionResponse section =
            sectionService.getSection(COLLECTION, "OIM Authentication Guide", "Introduction");
        assertThat(section.text())
            .contains("## Introduction\n\nThis is the introduction section body text.");
        assertThat(section.text()).doesNotContain("Section path:");
    }
}
