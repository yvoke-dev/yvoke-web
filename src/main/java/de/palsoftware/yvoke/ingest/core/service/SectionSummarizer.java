package de.palsoftware.yvoke.ingest.core.service;

import de.palsoftware.yvoke.ingest.core.model.Section;


import de.palsoftware.yvoke.rag.prompt.SystemPrompt;
import de.palsoftware.yvoke.rag.prompt.SystemPromptService;
import de.palsoftware.yvoke.shared.jobengine.model.JobContext;
import de.palsoftware.yvoke.shared.jobengine.model.JobStep;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Types;

@Service
public class SectionSummarizer {

    private static final Logger log = LoggerFactory.getLogger(SectionSummarizer.class);

    private final GeneralSummarizer generalSummarizer;
    private final JdbcClient jdbcClient;
    private final JdbcTemplate jdbcTemplate;
    private final int concurrency;
    private final SystemPromptService systemPromptService;
    private final TransactionTemplate transactionTemplate;

    public SectionSummarizer(GeneralSummarizer generalSummarizer, JdbcClient jdbcClient,
        JdbcTemplate jdbcTemplate, @Value("${app.ai.summarize.concurrency}") int concurrency,
        SystemPromptService systemPromptService, PlatformTransactionManager transactionManager) {
        this.generalSummarizer = generalSummarizer;
        this.jdbcClient = jdbcClient;
        this.jdbcTemplate = jdbcTemplate;
        this.concurrency = Math.max(1, concurrency);
        this.systemPromptService = systemPromptService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public static class SectionNode {
        public final List<String> path;
        public String rawBody = "";
        public final List<SectionNode> children = new ArrayList<>();
        public String summary = "";
        public float[] summaryEmbedding = null;

        public SectionNode(List<String> path) {
            this.path = path;
        }
    }

    public void generateSummaries(UUID documentId, List<Section> sections, UUID jobId,
        JobContext ctx) {
        generateSummaries(documentId, sections, jobId, ctx, null);
    }

    public void generateSummaries(UUID documentId, List<Section> sections, UUID jobId,
        JobContext ctx, String systemPromptOverride) {
        if (sections.isEmpty()) {
            return;
        }

        log.info("Starting bottom-up hierarchical summarization for document {}", documentId);

        // 1. Build Node Map of all paths
        Map<List<String>, SectionNode> nodeMap = new LinkedHashMap<>();
        for (Section section : sections) {
            List<String> fullPath = new ArrayList<>(section.headingPath());
            fullPath.add(section.title());

            // Ensure nodes exist for all ancestor prefixes
            for (int i = 1; i <= fullPath.size(); i++) {
                List<String> subPath = new ArrayList<>(fullPath.subList(0, i));
                nodeMap.putIfAbsent(subPath, new SectionNode(subPath));
            }

            SectionNode leafNode = nodeMap.get(fullPath);
            leafNode.rawBody = section.body();
        }

        // 2. Establish parent-child links
        for (SectionNode node : nodeMap.values()) {
            if (node.path.size() > 1) {
                List<String> parentPath =
                    new ArrayList<>(node.path.subList(0, node.path.size() - 1));
                SectionNode parentNode = nodeMap.get(parentPath);
                if (parentNode != null) {
                    parentNode.children.add(node);
                }
            }
        }

        // 3. Group nodes by depth
        Map<Integer, List<SectionNode>> nodesByDepth = new TreeMap<>(Comparator.reverseOrder());
        for (SectionNode node : nodeMap.values()) {
            nodesByDepth.computeIfAbsent(node.path.size(), k -> new ArrayList<>()).add(node);
        }

        int totalNodes = nodeMap.size();
        AtomicInteger completedCount = new AtomicInteger(0);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Semaphore gate = new Semaphore(concurrency);

        // 4. Process bottom-up level-by-level
        for (Map.Entry<Integer, List<SectionNode>> entry : nodesByDepth.entrySet()) {
            int depth = entry.getKey();
            List<SectionNode> levelNodes = entry.getValue();

            log.info("Summarizing {} nodes at depth {}", levelNodes.size(), depth);

            try (
                ExecutorService delegate = Executors
                    .newThreadPerTaskExecutor(Thread.ofVirtual().name("summarize-", 0).factory());
                ExecutorService executor = new DelegatingSecurityContextExecutorService(delegate)) {
                List<Future<?>> futures = new ArrayList<>(levelNodes.size());
                for (SectionNode node : levelNodes) {
                    futures.add(executor.submit(() -> {
                        try {
                            if (cancelled.get() || (jobId != null && isJobCancelled(jobId))) {
                                cancelled.set(true);
                                return;
                            }
                            processNode(node, gate, systemPromptOverride);
                        } catch (Exception e) {
                            log.error("Failed to summarize section node: {}",
                                String.join(" > ", node.path), e);
                            node.summary = "Section summary unavailable.";
                        } finally {
                            int done = completedCount.incrementAndGet();
                            if (ctx != null) {
                                int progress = 75 + (int) ((done * 15.0) / totalNodes);
                                ctx.report(JobStep.EXTRACT, progress, String
                                    .format("Summarized %d of %d sections", done, totalNodes));
                            }
                        }
                    }));
                }

                for (Future<?> f : futures) {
                    f.get();
                }
            } catch (Exception e) {
                throw new IllegalStateException("Summarization processing was interrupted", e);
            }
        }

        if (cancelled.get()) {
            throw new IllegalStateException("Job was cancelled by administrator");
        }

        List<SectionNode> orderedNodes = new ArrayList<>(nodeMap.values());

        // 6. Delete legacy and batch-insert summaries to DB
        log.info("Persisting {} section summaries to database", nodeMap.size());
        transactionTemplate.executeWithoutResult(status -> {
            jdbcClient.sql("DELETE FROM section_summaries WHERE document_id = :documentId")
                .param("documentId", documentId).update();

            String sql =
                """
                    INSERT INTO section_summaries (id, document_id, heading_path, summary, summary_embedding)
                    VALUES (?, ?, ?, ?, ?::vector)
                    """;

            jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    SectionNode node = orderedNodes.get(i);
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, documentId);
                    ps.setArray(3, ps.getConnection().createArrayOf("text", node.path.toArray()));
                    ps.setString(4, node.summary);
                    ps.setNull(5, Types.OTHER);
                }

                @Override
                public int getBatchSize() {
                    return orderedNodes.size();
                }
            });
        });

        log.info("Section summarization completed successfully for document {}", documentId);
    }

    private void processNode(SectionNode node, Semaphore gate, String systemPromptOverride)
        throws InterruptedException {
        String contentToSummarize;
        if (node.children.isEmpty()) {
            // Leaf node: summarize raw body
            contentToSummarize = node.rawBody != null ? node.rawBody.trim() : "";
        } else {
            // Parent node: synthesize children's summaries
            StringBuilder sb = new StringBuilder();
            for (SectionNode child : node.children) {
                sb.append("> ").append(String.join(" > ", child.path)).append("\n");
                sb.append(child.summary).append("\n\n");
            }
            contentToSummarize = sb.toString().trim();
        }

        if (contentToSummarize.isEmpty()) {
            node.summary = "Empty section.";
            return;
        }

        gate.acquire();
        try {
            log.info("Processing node summary: {}", String.join(" > ", node.path));
            String userMsg =
                String.format("Section path: %s\n\n```markdown\n%s\n```\n\nWrite the summary now.",
                    String.join(" > ", node.path), contentToSummarize);

            String resolvedPrompt = systemPromptOverride;
            if (resolvedPrompt == null || resolvedPrompt.isBlank()) {
                resolvedPrompt = systemPromptService.getPrompt("default-summarize")
                    .map(SystemPrompt::systemPrompt).orElse(null);
            }

            node.summary = generalSummarizer.summarize(contentToSummarize, "section_summary",
                resolvedPrompt, userMsg);
        } finally {
            gate.release();
        }
    }

    private boolean isJobCancelled(UUID jobId) {
        if (jobId == null)
            return false;
        String status = jdbcClient.sql("SELECT status FROM ingestion_jobs WHERE id = :id")
            .param("id", jobId).query(String.class).optional().orElse("running");
        return !"running".equals(status);
    }
}
