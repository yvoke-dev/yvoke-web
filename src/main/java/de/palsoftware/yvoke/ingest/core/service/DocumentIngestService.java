package de.palsoftware.yvoke.ingest.core.service;

import jakarta.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import de.palsoftware.yvoke.document.core.model.ChunkInsert;
import de.palsoftware.yvoke.document.core.model.ChunkKgStatus;
import de.palsoftware.yvoke.document.core.model.ChunkRow;
import de.palsoftware.yvoke.document.core.model.DocumentKind;
import de.palsoftware.yvoke.document.core.repository.ChunkRepository;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import de.palsoftware.yvoke.ingest.core.UploadPathGuard;
import de.palsoftware.yvoke.ingest.core.model.MarkdownTree;
import de.palsoftware.yvoke.ingest.core.model.ParsedMarkdown;
import de.palsoftware.yvoke.ingest.core.model.Section;
import de.palsoftware.yvoke.kg.core.model.KgExtractionResult;
import de.palsoftware.yvoke.kg.core.model.KgExtractionResult.ExtractedEntity;
import de.palsoftware.yvoke.kg.core.model.KgExtractionResult.ExtractedRelationship;
import de.palsoftware.yvoke.kg.core.repository.KgWriteRepository;
import de.palsoftware.yvoke.kg.core.service.DocumentKgExtractor;
import de.palsoftware.yvoke.kg.core.service.KgConsolidator;
import de.palsoftware.yvoke.rag.prompt.SystemPrompt;
import de.palsoftware.yvoke.rag.prompt.SystemPromptService;
import de.palsoftware.yvoke.rag.retrieval.EmbeddingService;
import de.palsoftware.yvoke.shared.jobengine.model.IngestionJob;
import de.palsoftware.yvoke.shared.jobengine.model.JobContext;
import de.palsoftware.yvoke.shared.jobengine.model.JobCounts;
import de.palsoftware.yvoke.shared.jobengine.model.JobStep;

@Service
public class DocumentIngestService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestService.class);

    private static final String KIND_STANDARD = DocumentKind.STANDARD.getValue();

    private final EmbeddingService embeddingService;
    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final KgWriteRepository kgRepository;
    private final DocumentKgExtractor kgExtractor;
    private final KgConsolidator kgConsolidator;
    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;
    private final SectionSummarizer sectionSummarizer;
    private final SystemPromptService systemPromptService;
    private final UploadPathGuard uploadPathGuard;

    public DocumentIngestService(EmbeddingService embeddingService,
        DocumentRepository documentRepository, ChunkRepository chunkRepository,
        KgWriteRepository kgRepository, DocumentKgExtractor kgExtractor,
        KgConsolidator kgConsolidator, JdbcClient jdbcClient,
        PlatformTransactionManager transactionManager, SectionSummarizer sectionSummarizer,
        SystemPromptService systemPromptService, UploadPathGuard uploadPathGuard) {
        this.embeddingService = embeddingService;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.kgRepository = kgRepository;
        this.kgExtractor = kgExtractor;
        this.kgConsolidator = kgConsolidator;
        this.jdbcClient = jdbcClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.sectionSummarizer = sectionSummarizer;
        this.systemPromptService = systemPromptService;
        this.uploadPathGuard = uploadPathGuard;
    }

    public JobCounts ingest(IngestionJob job, JobContext ctx) {
        String collection = job.collection();
        List<String> tags = job.tags();
        String sourceFile = fileName(job.sourceRef());
        Path file = uploadPathGuard.resolve(job.sourceRef());

        if (sourceFile.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return processZipFile(job, ctx, file, collection, tags,
                this::processSingleStandardFile);
        } else {
            String markdown = readSource(job.sourceRef());
            return processSingleStandardFile(job.id(), collection, tags, sourceFile, markdown, ctx,
                job.settings());
        }
    }

    private JobCounts processSingleStandardFile(UUID jobId, String collection, List<String> tags,
        String sourceFile, String markdown, JobContext ctx, Map<String, Object> settings) {
        // 1. chunk
        checkCancellation(jobId);
        ctx.report(JobStep.CHUNK, 5, "Parsing document Markdown structure: " + sourceFile);
        ParsedMarkdown parsed = MarkdownTree.parse(markdown);
        List<Section> sections = MarkdownTree.buildOrderedSections(parsed);
        if (sections.isEmpty()) {
            throw new IllegalStateException("No chunks produced from document: " + sourceFile);
        }
        List<String> chunkTexts = sections.stream().map(Section::toChunkText).toList();
        ctx.report(JobStep.CHUNK, 25,
            String.format("Parsed document into %d chunks", sections.size()));

        // 2. embed (batched)
        checkCancellation(jobId);
        ctx.report(JobStep.EMBED, 30,
            String.format("Generating Voyage embeddings for %d chunks", sections.size()));
        List<float[]> embeddings = embeddingService.embedBatch(chunkTexts);
        if (embeddings.size() != sections.size()) {
            throw new IllegalStateException("Embedding count " + embeddings.size()
                + " does not match chunk count " + sections.size());
        }
        ctx.report(JobStep.EMBED, 55, "Generated all Voyage embeddings");

        // 3. insert (atomic: replace any prior chunk set for the idempotency key)
        checkCancellation(jobId);
        ctx.report(JobStep.INSERT, 60, "Persisting document and chunks to Postgres");
        persistDocument(collection, tags, sourceFile, sections, embeddings, parsed.titleH1());
        ctx.report(JobStep.INSERT, 100, "Ingestion completed successfully for " + sourceFile);

        log.info("Document ingest complete: source={} chunks={}", sourceFile, sections.size());
        return new JobCounts(1, sections.size(), 0, 0, 0);
    }

    public JobCounts processDocumentKg(IngestionJob job, JobContext ctx) {
        UUID documentId = UUID.fromString(job.sourceRef());
        String collection = job.collection();
        List<String> tags = job.tags();

        checkCancellation(job.id());
        ctx.report(JobStep.CHUNK, 5, "Loading stored chunks for document " + documentId);
        List<ChunkRow> chunks = chunkRepository.findChunksByDocumentId(documentId, null);
        if (chunks.isEmpty()) {
            throw new IllegalStateException(
                "Document has no chunks to extract a knowledge graph from: " + documentId);
        }
        List<String> chunkTexts = chunks.stream().map(ChunkRow::text).toList();

        checkCancellation(job.id());
        ctx.report(JobStep.EXTRACT, 20,
            String.format("Extracting knowledge graph from %d chunks (LLM)", chunks.size()));
        String kgPromptName = (String) job.settings().get("kgPrompt");
        String kgPromptText = null;
        if (kgPromptName != null) {
            kgPromptText = systemPromptService.getPrompt(kgPromptName)
                .map(SystemPrompt::systemPrompt).orElse(null);
        }
        KgExtractionResult kg;
        if (kgPromptText != null && !kgPromptText.isBlank()) {
            kg = kgExtractor.extract(chunkTexts, job.id(), ctx, kgPromptText);
        } else {
            kg = kgExtractor.extract(chunkTexts, job.id(), ctx);
        }
        GraphCounts graphCounts = persistGraph(collection, tags, kg);
        persistChunkKgStatuses(chunks, kg);
        ctx.report(JobStep.EXTRACT, 90,
            graphCounts.skippedEntities() == 0 && graphCounts.skippedEdges() == 0
                ? "Knowledge Graph persisted successfully"
                : String.format(
                    "Knowledge Graph persisted; skipped %d kind-less entit(ies) and %d edge(s) with"
                        + " an undeclared or ambiguous endpoint",
                    graphCounts.skippedEntities(), graphCounts.skippedEdges()));

        checkCancellation(job.id());
        ctx.report(JobStep.INJECT, 95, "Running Knowledge Graph consolidation");
        if (tags == null || tags.isEmpty()) {
            kgConsolidator.consolidate(collection, null);
        } else {
            for (String t : tags) {
                kgConsolidator.consolidate(collection, t);
            }
        }

        documentRepository.markKgProcessed(documentId, OffsetDateTime.now().toString(),
            graphCounts.entities(), graphCounts.edges());
        ctx.report(JobStep.INJECT, 100, "Knowledge Graph processing completed successfully");

        log.info(
            "KG processing complete: document={} chunks={} entities={} edges={} skippedChunks={}"
                + " skippedEntities={} skippedEdges={}",
            documentId, chunks.size(), graphCounts.entities(), graphCounts.edges(), kg.skipped(),
            graphCounts.skippedEntities(), graphCounts.skippedEdges());
        // The skipped counts are REPORTED here, whereas the custom (jsonl) ingest path THROWS on
        // the equivalent loss. The corpus that path ingests is a deterministic export we control,
        // and its entities must each map to a document, so any gap is an export defect worth
        // stopping for. Here the graph comes from an LLM whose output is not predictable: a single
        // stray entity name would otherwise fail an entire kg-extract job, so the loss is made
        // visible in the job result instead of fatal.
        return new JobCounts(0, chunks.size(), graphCounts.entities(), graphCounts.edges(), 0,
            graphCounts.skippedEntities(), graphCounts.skippedEdges());
    }

    private void checkCancellation(UUID jobId) {
        if (jobId == null)
            return;
        String status = jdbcClient.sql("SELECT status FROM ingestion_jobs WHERE id = :id")
            .param("id", jobId).query(String.class).optional().orElse("running");
        if (!"running".equals(status)) {
            throw new IllegalStateException("Job was cancelled by administrator");
        }
    }

    private UUID persistDocument(String collection, List<String> tags, String sourceFile,
        List<Section> sections, List<float[]> embeddings, String titleH1) {
        List<ChunkInsert> inserts = new ArrayList<>(sections.size());
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            inserts.add(new ChunkInsert(s.toChunkText(), embeddings.get(i), s.headingPath(),
                s.title(), s.depth(), i)); // sort_order = document-order index
        }

        return transactionTemplate.execute(status -> {
            UUID documentId = documentRepository.upsertManualDocument(collection, tags, sourceFile,
                KIND_STANDARD, titleH1);
            documentRepository.deleteChunksForDocument(documentId);
            documentRepository.insertChunks(documentId, collection, tags, sourceFile, KIND_STANDARD,
                inserts);
            documentRepository.updateIngestionStatus(documentId, "completed");
            return documentId;
        });
    }

    private void persistChunkKgStatuses(List<ChunkRow> orderedChunks, KgExtractionResult kg) {
        List<ChunkKgStatus> updates = new ArrayList<>(kg.chunkStatuses().size());
        for (KgExtractionResult.ChunkStatus s : kg.chunkStatuses()) {
            if (s.index() < 0 || s.index() >= orderedChunks.size()) {
                log.warn("KG chunk status index {} out of range for {} chunks; skipping", s.index(),
                    orderedChunks.size());
                continue;
            }
            updates.add(new ChunkKgStatus(orderedChunks.get(s.index()).id(), s.ok(), s.model()));
        }
        documentRepository.markChunkKgStatuses(updates);
    }

    private GraphCounts persistGraph(String collection, List<String> tags, KgExtractionResult kg) {
        return transactionTemplate.execute(status -> {
            // Only entities the extractor actually DECLARED (with a kind) become nodes, in one
            // batched round-trip. Materializing a kind-less placeholder for an undeclared
            // relationship endpoint used to split one logical entity into a kinded row plus a
            // kind-NULL row that the kind-aware consolidator then refuses to merge; a kind is
            // mandatory now (entities.kind is NOT NULL since V3).
            List<KgWriteRepository.EntityUpsert> entitySpecs =
                new ArrayList<>(kg.entities().size());
            int kindless = 0;
            for (ExtractedEntity e : kg.entities()) {
                if (e.kind() == null || e.kind().isBlank()) {
                    kindless++;
                    continue;
                }
                entitySpecs
                    .add(new KgWriteRepository.EntityUpsert(e.name(), e.kind(), e.description()));
            }
            if (kindless > 0) {
                log.warn("Dropped {} extracted entit(ies) without a kind in collection '{}'",
                    kindless, collection);
            }
            Map<String, UUID> idByKey =
                kgRepository.upsertEntitiesBatch(collection, tags, entitySpecs);

            // LLM-extracted edges carry no endpoint kind, so endpoints resolve by name against the
            // declared entities — and only when exactly one kind carries that name, mirroring the
            // custom-ingest path. The id map is keyed "lower(kind):lower(name)".
            Map<String, List<UUID>> idsByName = new HashMap<>();
            for (Map.Entry<String, UUID> entry : idByKey.entrySet()) {
                String key = entry.getKey();
                String lname = key.substring(key.indexOf(':') + 1);
                idsByName.computeIfAbsent(lname, k -> new ArrayList<>()).add(entry.getValue());
            }

            List<KgWriteRepository.RelationshipUpsert> relSpecs =
                new ArrayList<>(kg.relationships().size());
            int unresolved = 0;
            for (ExtractedRelationship r : kg.relationships()) {
                UUID subjectId = resolveEndpointId(idsByName, r.subject());
                UUID objectId = resolveEndpointId(idsByName, r.object());
                if (subjectId == null || objectId == null) {
                    unresolved++;
                    continue;
                }
                relSpecs.add(new KgWriteRepository.RelationshipUpsert(r.subject(), r.predicate(),
                    r.object(), subjectId, objectId, r.description(), null));
            }
            if (unresolved > 0) {
                log.warn(
                    "Skipped {} extracted relationship(s) in collection '{}' whose endpoints are not"
                        + " declared entities (or are ambiguous across kinds)",
                    unresolved, collection);
            }
            kgRepository.insertRelationshipsBatch(collection, tags, relSpecs);
            return new GraphCounts(idByKey.size(), relSpecs.size(), kindless, unresolved);
        });
    }

    /**
     * Resolves a relationship endpoint name to a declared entity id. Returns null when the name was
     * never declared as an entity, or when several kinds carry it — picking one arbitrarily is what
     * produced edges attached to the wrong homonym.
     */
    @Nullable
    private static UUID resolveEndpointId(Map<String, List<UUID>> idsByName, String name) {
        List<UUID> candidates = idsByName.get(name.toLowerCase(Locale.ROOT));
        return (candidates != null && candidates.size() == 1) ? candidates.get(0) : null;
    }

    private String readSource(String sourceRef) {
        try {
            return new String(Files.readAllBytes(uploadPathGuard.resolve(sourceRef)),
                StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read manual source: " + sourceRef, e);
        }
    }

    public JobCounts ingestHierarchical(IngestionJob job, JobContext ctx) {
        String collection = job.collection();
        List<String> tags = job.tags();
        String sourceFile = fileName(job.sourceRef());
        Path file = uploadPathGuard.resolve(job.sourceRef());

        if (sourceFile.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return processZipFile(job, ctx, file, collection, tags,
                this::processSingleHierarchicalFile);
        } else {
            String markdown = readSource(job.sourceRef());
            return processSingleHierarchicalFile(job.id(), collection, tags, sourceFile, markdown,
                ctx, job.settings());
        }
    }

    private JobCounts processSingleHierarchicalFile(UUID jobId, String collection,
        List<String> tags, String sourceFile, String markdown, JobContext ctx,
        Map<String, Object> settings) {
        // 1. Chunk (bypass splitting)
        checkCancellation(jobId);
        ctx.report(JobStep.CHUNK, 5, "Parsing manual Markdown structure (unsplit): " + sourceFile);
        ParsedMarkdown parsed = MarkdownTree.parse(markdown);
        List<Section> sections = MarkdownTree.filterSections(parsed.sections());
        sections = MarkdownTree.dropEmptyPlaceholders(sections);
        if (sections.isEmpty()) {
            throw new IllegalStateException("No sections produced from manual: " + sourceFile);
        }
        ctx.report(JobStep.CHUNK, 25,
            String.format("Parsed manual into %d unsplit sections", sections.size()));

        // 2. Embed (skipped for hierarchical browsing-only documents)
        ctx.report(JobStep.EMBED, 55, "Skipped Voyage embeddings for hierarchical document");

        // 3. Insert (persist raw section texts without embeddings)
        checkCancellation(jobId);
        ctx.report(JobStep.INSERT, 60,
            "Persisting manual document and unsplit sections to Postgres");
        UUID documentId = persistDocumentHierarchical(collection, tags, sourceFile, sections, null,
            parsed.titleH1());
        ctx.report(JobStep.INSERT, 75, "Sections persisted successfully");

        // 4. Summarize (Bottom-Up recursive summaries + embeddings)
        checkCancellation(jobId);
        ctx.report(JobStep.EXTRACT, 76, "Generating hierarchical section summaries (LLM)");
        String summarizePromptName =
            settings != null ? (String) settings.get("summarizePrompt") : null;
        String summarizePromptText = null;
        if (summarizePromptName != null) {
            summarizePromptText = systemPromptService.getPrompt(summarizePromptName)
                .map(SystemPrompt::systemPrompt).orElse(null);
        }
        sectionSummarizer.generateSummaries(documentId, sections, jobId, ctx, summarizePromptText);
        ctx.report(JobStep.EXTRACT, 100, "Ingestion completed successfully for " + sourceFile);
        log.info("Hierarchical manual ingest complete: source={} sections={}", sourceFile,
            sections.size());

        return new JobCounts(1, sections.size(), 0, 0, 0);
    }

    private UUID persistDocumentHierarchical(String collection, List<String> tags,
        String sourceFile, List<Section> sections, List<float[]> embeddings, String titleH1) {
        List<ChunkInsert> inserts = new ArrayList<>(sections.size());
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            String cleanText =
                "#".repeat(s.depth()) + " " + s.title() + "\n\n" + s.body().stripTrailing() + "\n";
            inserts.add(new ChunkInsert(cleanText, embeddings != null ? embeddings.get(i) : null,
                s.headingPath(), s.title(), s.depth(), i)); // sort_order = document-order index
        }

        return transactionTemplate.execute(status -> {
            UUID documentId = documentRepository.upsertManualDocument(collection, tags, sourceFile,
                de.palsoftware.yvoke.document.core.model.DocumentKind.HIERARCHICAL.getValue(),
                titleH1);
            documentRepository.deleteChunksForDocument(documentId);
            documentRepository.insertChunks(documentId, collection, tags, sourceFile,
                de.palsoftware.yvoke.document.core.model.DocumentKind.HIERARCHICAL.getValue(),
                inserts);
            documentRepository.updateIngestionStatus(documentId, "completed");
            return documentId;
        });
    }

    private String fileName(String sourceRef) {
        return uploadPathGuard.resolve(sourceRef).getFileName().toString();
    }

    @FunctionalInterface
    private interface FileProcessor {
        JobCounts process(UUID jobId, String collection, List<String> tags, String sourceFile,
            String markdown, JobContext ctx, Map<String, Object> settings);
    }

    private JobCounts processZipFile(IngestionJob job, JobContext ctx, Path zipFile,
        String collection, List<String> tags, FileProcessor processor) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("standard_ingest_");
            log.info("Extracting ZIP to temporary directory: {}", tempDir);
            unzip(zipFile, tempDir);

            String documentGlob = job.settings() != null
                ? (String) job.settings().getOrDefault("documentGlob", "**/*.md")
                : "**/*.md";

            Path finalTempDir = tempDir;
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + documentGlob);
            List<Path> markdownFiles = new ArrayList<>();
            try (var stream = Files.walk(finalTempDir)) {
                stream.filter(Files::isRegularFile)
                    .filter(p -> matcher.matches(finalTempDir.relativize(p)))
                    .forEach(markdownFiles::add);
            }

            int totalDocs = 0;
            int totalChunks = 0;
            int totalFiles = markdownFiles.size();
            for (int i = 0; i < totalFiles; i++) {
                Path path = markdownFiles.get(i);
                String relativePath = finalTempDir.relativize(path).toString();
                String markdown = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                try {
                    int startProgress = (int) (((double) i / totalFiles) * 100);
                    int endProgress = (int) (((double) (i + 1) / totalFiles) * 100);
                    JobContext scaledCtx =
                        new ScaledJobContext(ctx, startProgress, endProgress, relativePath);

                    JobCounts counts = processor.process(job.id(), collection, tags, relativePath,
                        markdown, scaledCtx, job.settings());
                    totalDocs += counts.docs();
                    totalChunks += counts.chunks();
                } catch (Exception e) {
                    log.error("Failed to process file in zip: {}", relativePath, e);
                }
            }
            return new JobCounts(totalDocs, totalChunks, 0, 0, 0);

        } catch (IOException e) {
            log.error("Failed to execute zip ingestion", e);
            throw new RuntimeException("Zip ingestion failed", e);
        } finally {
            if (tempDir != null)
                cleanDirectory(tempDir);
        }
    }

    private void unzip(Path zipFile, Path destDir) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.contains("__MACOSX") || name.contains("/.") || name.startsWith("."))
                    continue;
                Path entryPath = destDir.resolve(name).normalize();
                if (!entryPath.startsWith(destDir))
                    throw new IOException("Bad zip entry (zip-slip): " + entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (var is = zip.getInputStream(entry);
                        var os = Files.newOutputStream(entryPath)) {
                        is.transferTo(os);
                    }
                }
            }
        }
    }

    private void cleanDirectory(Path path) {
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private record GraphCounts(int entities, int edges, int skippedEntities, int skippedEdges) {}

    private static class ScaledJobContext implements JobContext {
        private final JobContext delegate;
        private final int startProgress;
        private final int endProgress;
        private final String filePrefix;

        public ScaledJobContext(JobContext delegate, int startProgress, int endProgress,
            String fileName) {
            this.delegate = delegate;
            this.startProgress = startProgress;
            this.endProgress = endProgress;
            this.filePrefix = "[" + fileName + "] ";
        }

        @Override
        public IngestionJob job() {
            return delegate.job();
        }

        @Override
        public void report(JobStep step, int progress) {
            int scaled = startProgress + (int) ((progress / 100.0) * (endProgress - startProgress));
            delegate.report(step, scaled);
        }

        @Override
        public void report(JobStep step, int progress, String message) {
            int scaled = startProgress + (int) ((progress / 100.0) * (endProgress - startProgress));
            String prefixedMessage = message != null ? filePrefix + message : null;
            delegate.report(step, scaled, prefixedMessage);
        }
    }
}
