package de.palsoftware.yvoke.ingest.core.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.yaml.snakeyaml.Yaml;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.document.core.model.ChunkInsert;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import de.palsoftware.yvoke.ingest.core.UploadPathGuard;
import de.palsoftware.yvoke.kg.core.repository.KgWriteRepository;
import de.palsoftware.yvoke.rag.prompt.SystemPrompt;
import de.palsoftware.yvoke.rag.prompt.SystemPromptService;
import de.palsoftware.yvoke.rag.retrieval.EmbeddingService;
import de.palsoftware.yvoke.shared.jobengine.model.IngestionJob;
import de.palsoftware.yvoke.shared.jobengine.model.JobContext;
import de.palsoftware.yvoke.shared.jobengine.model.JobCounts;
import de.palsoftware.yvoke.ingest.core.model.MarkdownTree;
import de.palsoftware.yvoke.ingest.core.model.ParsedMarkdown;
import de.palsoftware.yvoke.ingest.core.model.Section;
import de.palsoftware.yvoke.shared.jobengine.model.JobStep;
import java.nio.charset.StandardCharsets;

@Service
public class CustomIngestService {

    private static final Logger log = LoggerFactory.getLogger(CustomIngestService.class);
    private static final Pattern PREFIX_RE =
        Pattern.compile("^>\\s+Section path:\\s*(.+?)\\s*$", Pattern.MULTILINE);
    private static final Pattern HEAD_RE = Pattern.compile("^(#+)\\s+(.+?)$", Pattern.MULTILINE);
    private static final Pattern FRONTMATTER_PATTERN =
        Pattern.compile("^---\\s*\\r?\\n(.*?)\\r?\\n---\\s*\\r?\\n", Pattern.DOTALL);
    private static final Pattern CODE_BLOCK_RE =
        Pattern.compile("```(?:sql|vbnet|vb|tsql)\\r?\\n(.*?)\\r?\\n```",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /**
     * Cap on how many offending items a failure message lists before it collapses into a count. A
     * broken export can produce thousands of them; an unbounded message is unreadable in the job
     * status and unusable in a log line.
     */
    private static final int MAX_REPORTED_ITEMS = 20;

    private record ParsedFile(Map<String, Object> frontmatter, String body) {}

    /**
     * A document whose YAML frontmatter cannot be parsed. Real defect this guards: a
     * {@code display_name} starting with {@code %} (a reserved YAML indicator) made the whole block
     * unparseable, the document silently fell back to {@code kind='other'}, and its graph entity
     * then matched no document at all — undetected across two full re-ingests.
     */
    private static final class FrontmatterParseException extends RuntimeException {
        private FrontmatterParseException(String message) {
            super(message);
        }
    }

    /**
     * Whether to embed graph entity descriptions. Off: {@code entities.embedding} is written but
     * never read — no query in this codebase does a vector search over entities (the only
     * {@code <=>} is in {@code ChunkRepository}), so the round-trip bought nothing. Flip to
     * {@code true} in the same commit that adds entity vector search, not before.
     */
    private static final boolean EMBED_ENTITY_DESCRIPTIONS = false;

    private final EmbeddingService embeddingService;
    private final DocumentRepository documentRepository;
    private final GeneralSummarizer generalSummarizer;
    private final KgWriteRepository kgRepository;
    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;
    private final UploadPathGuard uploadPathGuard;
    private final int concurrency;
    private final ObjectMapper objectMapper;
    private final SystemPromptService systemPromptService;

    public CustomIngestService(EmbeddingService embeddingService,
        DocumentRepository documentRepository, GeneralSummarizer generalSummarizer,
        KgWriteRepository kgRepository, JdbcClient jdbcClient,
        PlatformTransactionManager transactionManager, UploadPathGuard uploadPathGuard,
        @Value("${app.ai.summarize.concurrency}") int concurrency,
        SystemPromptService systemPromptService) {
        this.embeddingService = embeddingService;
        this.documentRepository = documentRepository;
        this.generalSummarizer = generalSummarizer;
        this.kgRepository = kgRepository;
        this.jdbcClient = jdbcClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.uploadPathGuard = uploadPathGuard;
        this.concurrency = Math.max(1, concurrency);
        this.objectMapper = new ObjectMapper();
        this.systemPromptService = systemPromptService;
    }

    private record DocPayload(String sourceFile, String kind, String name, String title,
        List<String> chunkTexts) {}

    /** Result of the concurrent parse/chunk phase: parsed documents and their flattened chunks. */
    private record ParseResult(List<DocPayload> docs, List<String> chunkTexts) {}

    /** Counts returned by the optional knowledge-graph injection phase. */
    private record KgCounts(int entities, int relationships) {}

    public JobCounts ingest(IngestionJob job, JobContext ctx) {
        String collection = job.collection();
        List<String> tags = job.tags();
        Path zipFile = uploadPathGuard.resolve(job.sourceRef());

        log.info("Starting custom ingest job {} for collection={}, tags={}", job.id(), collection,
            tags);

        Path tempDir = null;
        try {
            // tempDir is assigned before any failure point so the finally can always clean it up.
            tempDir = Files.createTempDirectory("custom_ingest_");
            log.info("Extracting ZIP to temporary directory: {}", tempDir);
            unzip(zipFile, tempDir);

            List<Path> markdownFiles = discoverMatchingFiles(tempDir, job.settings());
            log.info("Discovered {} matching files in ZIP to parse", markdownFiles.size());

            String resolvedPrompt = resolveSummarizePrompt(job.settings());
            ParseResult parsed = parseAndChunk(markdownFiles, tempDir, job, ctx, resolvedPrompt);

            // Populated by the persist phase, read by the KG-injection phase.
            Map<String, UUID> docIdMap = new ConcurrentHashMap<>();
            if (parsed.docs().isEmpty()) {
                log.warn("No documents found in custom extract ZIP.");
            } else {
                embedAndPersistDocuments(job, parsed, docIdMap, ctx);
            }

            KgCounts kg = injectKnowledgeGraph(tempDir, job, docIdMap, ctx);

            ctx.report(JobStep.INJECT, 100, "Ingestion completed successfully");
            log.info(
                "Custom ingestion job completed. Docs: {}, Chunks: {}, Entities: {}, Edges: {}",
                parsed.docs().size(), parsed.chunkTexts().size(), kg.entities(),
                kg.relationships());

            return new JobCounts(parsed.docs().size(), parsed.chunkTexts().size(), kg.entities(),
                kg.relationships(), 0);

        } catch (IOException e) {
            log.error("Failed to execute custom ingestion", e);
            throw new RuntimeException("Custom ingestion failed", e);
        } finally {
            if (tempDir != null)
                cleanDirectory(tempDir);
        }
    }

    /** Walks the extracted zip and returns the files matching the {@code documentGlob} setting. */
    private static List<Path> discoverMatchingFiles(Path tempDir, Map<String, Object> settings)
        throws IOException {
        String documentGlob = (String) settings.getOrDefault("documentGlob", "**/*.md");
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + documentGlob);
        List<Path> markdownFiles = new ArrayList<>();
        try (var stream = Files.walk(tempDir)) {
            stream.filter(Files::isRegularFile).filter(p -> matcher.matches(tempDir.relativize(p)))
                .forEach(markdownFiles::add);
        }
        return markdownFiles;
    }

    /**
     * Resolves the configured (or default) summarize system prompt; null when none is registered.
     */
    private String resolveSummarizePrompt(Map<String, Object> settings) {
        String summarizePrompt = settings != null ? (String) settings.get("summarizePrompt") : null;
        if (summarizePrompt == null || summarizePrompt.isBlank()) {
            summarizePrompt = "default-summarize";
        }
        return systemPromptService.getPrompt(summarizePrompt).map(SystemPrompt::systemPrompt)
            .orElse(null);
    }

    /**
     * Parses/summarizes/chunks the matched files concurrently on a security-context-propagating
     * virtual-thread executor. Futures are drained in submission order so {@code docs} and the
     * flattened {@code chunkTexts} stay aligned with the embedding order used downstream. Throws if
     * the job was cancelled once every task has completed.
     */
    private ParseResult parseAndChunk(List<Path> files, Path tempDir, IngestionJob job,
        JobContext ctx, String resolvedPrompt) {
        int totalFiles = files.size();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicInteger completed = new AtomicInteger(0);
        Semaphore gate = new Semaphore(concurrency);
        // Written from the parse workers, drained on this thread once every task has finished.
        List<String> frontmatterFailures = Collections.synchronizedList(new ArrayList<>());

        List<DocPayload> docsToInsert = new ArrayList<>();
        List<String> allChunkTexts = new ArrayList<>();

        try (
            ExecutorService delegate = Executors
                .newThreadPerTaskExecutor(Thread.ofVirtual().name("custom-ingest-", 0).factory());
            ExecutorService executor = new DelegatingSecurityContextExecutorService(delegate)) {
            List<Future<DocPayload>> futures = new ArrayList<>(totalFiles);
            for (Path path : files) {
                futures.add(executor.submit(() -> processFile(path,
                    tempDir.relativize(path).toString(), job.id(), ctx, gate, cancelled, completed,
                    totalFiles, resolvedPrompt, frontmatterFailures)));
            }

            for (Future<DocPayload> future : futures) {
                try {
                    DocPayload payload = future.get();
                    if (payload != null) {
                        docsToInsert.add(payload);
                        allChunkTexts.addAll(payload.chunkTexts());
                    }
                } catch (ExecutionException e) {
                    throw new IllegalStateException("Ingestion parsing failed", e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Ingestion parsing was interrupted", e);
                }
            }
        }

        if (cancelled.get()) {
            throw new IllegalStateException("Job was cancelled by administrator");
        }
        if (!frontmatterFailures.isEmpty()) {
            throw new IllegalStateException("Custom ingest aborted: " + frontmatterFailures.size()
                + " document(s) have unparseable YAML frontmatter and would silently degrade to"
                + " kind='other' (losing the entity→document link): "
                + summarize(frontmatterFailures));
        }

        return new ParseResult(docsToInsert, allChunkTexts);
    }

    /**
     * Batch-embeds every chunk (a single call, outside any DB transaction) then persists each
     * document in its own transaction. A single running embedding index walks {@code allChunkTexts}
     * in document order, so it must stay aligned with how {@link #parseAndChunk} flattened them.
     */
    private void embedAndPersistDocuments(IngestionJob job, ParseResult parsed,
        Map<String, UUID> docIdMap, JobContext ctx) {
        String collection = job.collection();
        List<String> tags = job.tags();
        List<DocPayload> docs = parsed.docs();
        List<String> allChunkTexts = parsed.chunkTexts();

        log.info("Discovered {} documents to ingest yielding {} chunks", docs.size(),
            allChunkTexts.size());
        ctx.report(JobStep.CHUNK, 30,
            String.format("Finished parsing: %d chunks ready", allChunkTexts.size()));

        checkCancellation(job.id());
        ctx.report(JobStep.EMBED, 35,
            String.format("Generating embeddings for %d chunks", allChunkTexts.size()));
        List<float[]> embeddings = embeddingService.embedBatch(allChunkTexts);
        if (embeddings.size() != allChunkTexts.size()) {
            throw new IllegalStateException("Generated embedding size does not match chunk size");
        }
        ctx.report(JobStep.EMBED, 60, "Generated embeddings successfully");

        checkCancellation(job.id());
        ctx.report(JobStep.INSERT, 65, String.format("Persisting %d documents", docs.size()));

        int embeddingIndex = 0;
        for (DocPayload doc : docs) {
            List<ChunkInsert> chunkInserts = new ArrayList<>();
            for (int i = 0; i < doc.chunkTexts().size(); i++) {
                String text = doc.chunkTexts().get(i);
                float[] emb = embeddings.get(embeddingIndex++);
                chunkInserts.add(toChunkInsert(text, emb, doc.title(), i));
            }
            persistDocument(collection, tags, doc, chunkInserts, docIdMap);
        }
        ctx.report(JobStep.INSERT, 80, "Persisted documents successfully");
    }

    /**
     * Parses the section-path prefix and heading/depth out of a chunk into a {@link ChunkInsert}.
     */
    static ChunkInsert toChunkInsert(String text, float[] emb, String docTitle, int index) {
        List<String> headingPath = new ArrayList<>();
        Matcher pm = PREFIX_RE.matcher(text);
        if (pm.find()) {
            for (String p : pm.group(1).split(" > ")) {
                if (!p.trim().isEmpty())
                    headingPath.add(p.trim());
            }
        }

        Matcher hm = HEAD_RE.matcher(text);
        String heading = docTitle;
        int depth = 1;
        if (hm.find()) {
            heading = hm.group(2).trim();
            depth = hm.group(1).length();
        }

        return new ChunkInsert(text, emb, headingPath, heading, depth, index);
    }

    /**
     * Persists one document in a single transaction: delete any prior version for this source file,
     * upsert the document, insert its chunks, mark it completed, and record its id in {@code
     * docIdMap} for the KG phase. Kept as its own transaction (not widened across documents).
     */
    private void persistDocument(String collection, List<String> tags, DocPayload doc,
        List<ChunkInsert> chunkInserts, Map<String, UUID> docIdMap) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcClient
                .sql(
                    """
                        DELETE FROM documents d
                        WHERE d.collection_id = (SELECT id FROM collections WHERE LOWER(name) = LOWER(:collection))
                          AND (d.tags && :tags::text[] OR d.tags = '{}'::text[])
                          AND d.metadata->>'source_file' = :sourceFile
                        """)
                .param("collection", collection).param("tags", tags.toArray(new String[0]))
                .param("sourceFile", doc.sourceFile()).update();

            UUID documentId = documentRepository.upsertManualDocument(collection, tags,
                doc.sourceFile(), doc.kind(), doc.title());
            documentRepository.insertChunks(documentId, collection, tags, doc.sourceFile(),
                doc.kind(), chunkInserts);
            documentRepository.updateIngestionStatus(documentId, "completed");

            // Kind-aware, case-insensitive document key so entity->document linking matches on
            // (kind, name) rather than name alone (homonyms of different kinds map to their own
            // doc).
            docIdMap.put((doc.kind() + ":" + doc.name()).toLowerCase(Locale.ROOT), documentId);
        });
    }

    /**
     * Optionally injects the knowledge graph shipped in the zip. Returns zero counts when the graph
     * is disabled or the entity/relationship files are absent; otherwise batch-upserts entities and
     * relationships (all embedding happens inside {@link #buildEntitySpecs}, outside any DB tx).
     */
    private KgCounts injectKnowledgeGraph(Path tempDir, IngestionJob job,
        Map<String, UUID> docIdMap, JobContext ctx) throws IOException {
        if (!isGraphEnabled(job.settings())) {
            return new KgCounts(0, 0);
        }
        String entitiesFile =
            (String) job.settings().getOrDefault("entitiesFile", "graph/entities.jsonl");
        String relationshipsFile =
            (String) job.settings().getOrDefault("relationshipsFile", "graph/relationships.jsonl");

        Path entitiesPath;
        Path relsPath;
        try (var stream = Files.walk(tempDir)) {
            List<Path> allPaths = stream.toList();
            entitiesPath =
                allPaths.stream().filter(p -> p.endsWith(entitiesFile)).findFirst().orElse(null);
            relsPath = allPaths.stream().filter(p -> p.endsWith(relationshipsFile)).findFirst()
                .orElse(null);
        }

        if (entitiesPath == null || relsPath == null || !Files.exists(entitiesPath)
            || !Files.exists(relsPath)) {
            return new KgCounts(0, 0);
        }

        checkCancellation(job.id());
        ctx.report(JobStep.INJECT, 85, "Injecting optional knowledge graph");

        String collection = job.collection();
        List<String> tags = job.tags();
        List<Map<String, Object>> entities = parseJsonl(entitiesPath);
        List<Map<String, Object>> relationships = parseJsonl(relsPath);

        List<KgWriteRepository.EntityUpsert> entitySpecs = buildEntitySpecs(entities, docIdMap);
        Map<String, UUID> nameToIdMap =
            kgRepository.upsertEntitiesBatch(collection, tags, entitySpecs);
        // Distinct identities actually resolved, not the spec count. `entitySpecs.size()` is
        // one entry per jsonl line with no dedup, so the job reported 9,664 where 9,663 rows
        // existed — and, before identity was tag-scoped, 9,904 where 473 rows were new. A
        // count that cannot fall short of reality cannot report a loss.
        int entitiesInserted = nameToIdMap.size();

        List<KgWriteRepository.RelationshipUpsert> relSpecs =
            buildRelationshipSpecs(relationships, nameToIdMap);
        int relsInserted = kgRepository.insertRelationshipsBatch(collection, tags, relSpecs);
        ctx.report(JobStep.INJECT, 95, "Knowledge graph injected");

        return new KgCounts(entitiesInserted, relsInserted);
    }

    /** Resolves whether KG injection is enabled from settings (Boolean or String); default true. */
    static boolean isGraphEnabled(Map<String, Object> settings) {
        if (settings != null && settings.containsKey("enableGraph")) {
            Object val = settings.get("enableGraph");
            if (val instanceof Boolean b) {
                return b;
            } else if (val instanceof String s) {
                return Boolean.parseBoolean(s);
            }
        }
        return true;
    }

    /**
     * Maps raw relationship records to upsert specs: tolerates {@code source/subject_name},
     * {@code type/predicate}, {@code target/object_name} key variants and resolves each endpoint
     * against the kind-aware {@code idByKey} map (keyed {@code lower(kind):lower(name)}). A
     * {@code "kind:name"} endpoint prefix is used as the endpoint's kind so a same-named entity of
     * the right kind is picked (fixing degenerate self-loops like module ADS → connector ADS). An
     * endpoint with no prefix resolves by name only when exactly one kind carries that name. Bare
     * names are stored as the display subject/object.
     *
     * <p>
     * An endpoint that cannot be resolved (unknown, or a bare name carried by several kinds) and a
     * record missing a source/predicate/target fail the job. Dropping such an edge silently is the
     * same class of defect as an entity without a document: the graph loses a link and nothing says
     * so.
     */
    @SuppressWarnings("unchecked")
    static List<KgWriteRepository.RelationshipUpsert> buildRelationshipSpecs(
        List<Map<String, Object>> relationships, Map<String, UUID> idByKey) {
        // lower(name) -> the id-map keys ("lkind:lname") that carry that name, for unprefixed
        // lookup.
        Map<String, List<String>> keysByName = new HashMap<>();
        for (String key : idByKey.keySet()) {
            String lname = key.substring(key.indexOf(':') + 1);
            keysByName.computeIfAbsent(lname, k -> new ArrayList<>()).add(key);
        }

        Set<String> malformed = new LinkedHashSet<>();
        Set<String> unknown = new LinkedHashSet<>();
        Set<String> ambiguous = new LinkedHashSet<>();

        List<KgWriteRepository.RelationshipUpsert> relSpecs = new ArrayList<>();
        for (Map<String, Object> rel : relationships) {
            String source = (String) rel.getOrDefault("source", rel.get("subject_name"));
            String pred = (String) rel.getOrDefault("type", rel.get("predicate"));
            String target = (String) rel.getOrDefault("target", rel.get("object_name"));
            String desc = (String) rel.get("description");
            Map<String, Object> metadata =
                (Map<String, Object>) rel.getOrDefault("metadata", new HashMap<>());

            if (source == null || target == null || pred == null) {
                malformed.add(source + " -[" + pred + "]-> " + target);
                continue;
            }

            ResolvedEndpoint subj =
                resolveEndpoint(source, idByKey, keysByName, unknown, ambiguous);
            ResolvedEndpoint obj = resolveEndpoint(target, idByKey, keysByName, unknown, ambiguous);
            if (subj != null && obj != null) {
                relSpecs.add(new KgWriteRepository.RelationshipUpsert(subj.name(), pred, obj.name(),
                    subj.id(), obj.id(), desc, metadata));
            }
        }

        failOnUnresolvedEndpoints(malformed, unknown, ambiguous);
        return relSpecs;
    }

    /**
     * Fails the job when any edge could not be turned into a spec, keeping the three causes apart
     * so the export side knows what to fix.
     */
    private static void failOnUnresolvedEndpoints(Set<String> malformed, Set<String> unknown,
        Set<String> ambiguous) {
        if (malformed.isEmpty() && unknown.isEmpty() && ambiguous.isEmpty()) {
            return;
        }
        StringBuilder msg = new StringBuilder(
            "Custom ingest aborted: " + (malformed.size() + unknown.size() + ambiguous.size())
                + " knowledge-graph edge(s) could not be resolved and would be dropped silently.");
        if (!unknown.isEmpty()) {
            msg.append(" Unknown endpoint (no entity with that kind and name): ")
                .append(summarize(unknown)).append('.');
        }
        if (!ambiguous.isEmpty()) {
            msg.append(" Ambiguous endpoint (bare name carried by several kinds; prefix it with"
                + " 'kind:'): ").append(summarize(ambiguous)).append('.');
        }
        if (!malformed.isEmpty()) {
            msg.append(" Malformed edge (missing source, predicate or target): ")
                .append(summarize(malformed)).append('.');
        }
        throw new IllegalStateException(msg.toString());
    }

    private record ResolvedEndpoint(String name, UUID id) {}

    /**
     * Resolves a jsonl edge endpoint ({@code "kind:name"} or a bare {@code "name"}) to an entity
     * id. A prefix is authoritative: it resolves against the exact {@code lower(kind):lower(name)}
     * key. A bare name resolves only when exactly one kind carries it. When it cannot resolve, the
     * raw ref is recorded in {@code unknown} or {@code ambiguous} and null is returned so the
     * caller can report every offender at once. The returned name is always the bare display name.
     */
    @Nullable
    private static ResolvedEndpoint resolveEndpoint(String raw, Map<String, UUID> idByKey,
        Map<String, List<String>> keysByName, Set<String> unknown, Set<String> ambiguous) {
        int idx = raw.indexOf(':');
        boolean prefixed = idx > 0 && idx < raw.length() - 1;
        String bareName = prefixed ? raw.substring(idx + 1) : raw;
        if (prefixed) {
            String kind = raw.substring(0, idx);
            UUID id = idByKey.get(KgWriteRepository.entityKey(kind, bareName));
            if (id == null) {
                unknown.add(raw);
                return null;
            }
            return new ResolvedEndpoint(bareName, id);
        }
        List<String> candidates = keysByName.get(bareName.toLowerCase(Locale.ROOT));
        if (candidates == null || candidates.isEmpty()) {
            unknown.add(raw);
            return null;
        }
        if (candidates.size() > 1) {
            ambiguous.add(
                raw + " (kinds: " + candidates.stream().map(k -> k.substring(0, k.indexOf(':')))
                    .sorted().collect(Collectors.joining(", ")) + ")");
            return null;
        }
        return new ResolvedEndpoint(bareName, idByKey.get(candidates.get(0)));
    }

    /**
     * Builds the entity upsert specs for the optional KG payload, embedding all entity descriptions
     * in a SINGLE batched call (PRF-03) rather than one HTTP round-trip per entity. Entities
     * without a description keep a null embedding.
     *
     * <p>
     * Every entity must resolve to a document of the same {@code (kind, name)} — an entity with no
     * {@code document_id} is unreachable from every document-based tool, so the job fails here
     * rather than persisting a silently crippled graph (and before the embedding round-trip).
     */
    @SuppressWarnings("unchecked")
    List<KgWriteRepository.EntityUpsert> buildEntitySpecs(List<Map<String, Object>> entities,
        Map<String, UUID> docIdMap) {
        List<KgWriteRepository.EntityUpsert> specs = new ArrayList<>();
        List<Integer> specIndexNeedingEmbedding = new ArrayList<>();
        List<String> descriptionsToEmbed = new ArrayList<>();
        List<String> withoutDocument = new ArrayList<>();

        for (Map<String, Object> node : entities) {
            String type = (String) node.get("type");
            String name = (String) node.get("name");
            String desc = (String) node.get("description");
            Map<String, Object> metadata =
                (Map<String, Object>) node.getOrDefault("metadata", new HashMap<>());

            UUID docId = docIdMap.get((type + ":" + name).toLowerCase(Locale.ROOT));
            if (docId == null) {
                withoutDocument.add(type + ":" + name);
            } else {
                metadata.put("document_id", docId.toString());
            }

            specs.add(new KgWriteRepository.EntityUpsert(name, type, desc, null, metadata));
            if (desc != null && !desc.isBlank()) {
                specIndexNeedingEmbedding.add(specs.size() - 1);
                descriptionsToEmbed.add(desc);
            }
        }

        if (!withoutDocument.isEmpty()) {
            throw new IllegalStateException("Custom ingest aborted: " + withoutDocument.size()
                + " graph entit(ies) resolved to no document — every entity must be backed by a"
                + " document with the same (kind, name): " + summarize(withoutDocument));
        }

        // entities.embedding is WRITE-ONLY: 31 queries read the entities table and not one
        // of them uses the vector — search_graph_entities ranks by trigram
        // similarity(e.name, …), and the only <=> in the codebase is over chunks. Embedding
        // every entity description therefore cost one model round-trip per entity (~10.6k
        // per version per ingest of the OIM corpus) for a column nothing consults. The
        // description itself IS kept — it is what the MCP tools render. Restore this block
        // if entity vector search is ever added.
        if (EMBED_ENTITY_DESCRIPTIONS && !descriptionsToEmbed.isEmpty()) {
            List<float[]> embeddings = embeddingService.embedBatch(descriptionsToEmbed);
            for (int i = 0; i < specIndexNeedingEmbedding.size(); i++) {
                int idx = specIndexNeedingEmbedding.get(i);
                KgWriteRepository.EntityUpsert e = specs.get(idx);
                specs.set(idx, new KgWriteRepository.EntityUpsert(e.name(), e.kind(),
                    e.description(), embeddings.get(i), e.metadata()));
            }
        }
        return specs;
    }

    private List<Map<String, Object>> parseJsonl(Path path) throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty())
                    continue;
                result
                    .add(objectMapper.readValue(line, new TypeReference<Map<String, Object>>() {}));
            }
        }
        return result;
    }

    private DocPayload processFile(Path path, String relativePath, UUID jobId, JobContext ctx,
        Semaphore gate, AtomicBoolean cancelled, AtomicInteger completed, int totalFiles,
        String resolvedPrompt, List<String> frontmatterFailures) throws InterruptedException {
        gate.acquire();
        try {
            if (cancelled.get() || isJobCancelled(jobId)) {
                cancelled.set(true);
                return null;
            }

            ParsedFile parsed;
            try {
                parsed = readMd(path);
            } catch (FrontmatterParseException e) {
                // Collected rather than thrown here so the job reports EVERY broken file at once
                // instead of one arbitrary winner of the concurrent parse.
                frontmatterFailures.add(relativePath + " (" + e.getMessage() + ")");
                return null;
            }
            if (parsed == null)
                return null;

            Map<String, Object> fm = parsed.frontmatter();
            String kind = (String) fm.getOrDefault("kind", "other");
            String name =
                (String) fm.getOrDefault("name", path.getFileName().toString().replace(".md", ""));
            String title = (String) fm.getOrDefault("title", name);

            Object sumHeadingsObj = fm.get("summarize_headings");
            List<String> summarizeHeadings = new ArrayList<>();
            if (sumHeadingsObj instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null) {
                        summarizeHeadings.add(item.toString().trim());
                    }
                }
            } else if (sumHeadingsObj instanceof String str) {
                summarizeHeadings.add(str.trim());
            }

            boolean needsSummary =
                Boolean.TRUE.equals(fm.get("needs_summary")) || !summarizeHeadings.isEmpty();

            String body = parsed.body();
            if (needsSummary) {
                if (!summarizeHeadings.isEmpty()) {
                    ParsedMarkdown docTree = MarkdownTree.parse(body);
                    List<Section> sections = docTree.sections();
                    StringBuilder sb = new StringBuilder();
                    if (docTree.titleH1() != null) {
                        sb.append("# ").append(docTree.titleH1()).append("\n\n");
                    }
                    for (Section section : sections) {
                        boolean isMatched = false;
                        for (String targetHeading : summarizeHeadings) {
                            if (section.title().equalsIgnoreCase(targetHeading)) {
                                isMatched = true;
                                break;
                            }
                        }
                        String sectionBody = section.body().trim();
                        if (isMatched && !sectionBody.isEmpty()) {
                            String codeToSummarize = stripCodeBlockMarkers(sectionBody);
                            String userMsg = String.format(
                                "Object name: `%s` (kind: %s, heading: %s)\n\n"
                                    + "Content to summarize:\n%s\n\n"
                                    + "Write the 2-3 sentence summary now.",
                                name, kind, section.title(), codeToSummarize);
                            String summary = generalSummarizer.summarize(codeToSummarize, kind,
                                resolvedPrompt, userMsg);
                            sectionBody = summary.trim();
                        }
                        sb.append("#".repeat(section.depth())).append(" ").append(section.title())
                            .append("\n\n");
                        sb.append(sectionBody).append("\n\n");
                    }
                    body = sb.toString().trim();
                } else {
                    String contentToSummarize = extractCodeBlocks(body);
                    String lang = "script".equalsIgnoreCase(kind) ? "vbnet" : "sql";
                    String userMsg = String.format(
                        "Object name: `%s` (kind: %s, language: %s)\n\n" + "```%s\n%s\n```\n\n"
                            + "Write the 2-3 sentence summary now.",
                        name, kind, lang, lang, contentToSummarize);
                    String summary = generalSummarizer.summarize(contentToSummarize, kind,
                        resolvedPrompt, userMsg);
                    body = replaceSourceWithSummary(body, summary);
                }
            }

            Matcher titleMatcher = Pattern.compile("^#\\s+(.+)$", Pattern.MULTILINE).matcher(body);
            if (titleMatcher.find())
                title = titleMatcher.group(1).trim();

            body = body.trim();
            List<String> chunks = body.isEmpty() ? List.of() : List.of(body);
            if (chunks.isEmpty())
                return null;

            return new DocPayload(relativePath, kind, name, title, chunks);
        } finally {
            gate.release();
            int done = completed.incrementAndGet();
            if (ctx != null) {
                int progress = 10 + (int) ((done * 20.0) / totalFiles);
                ctx.report(JobStep.CHUNK, progress, String.format("Parsing file %d of %d: %s", done,
                    totalFiles, path.getFileName().toString()));
            }
        }
    }

    private void checkCancellation(UUID jobId) {
        if (jobId == null)
            return;
        if (isJobCancelled(jobId)) {
            throw new IllegalStateException("Job was cancelled by administrator");
        }
    }

    private boolean isJobCancelled(UUID jobId) {
        if (jobId == null)
            return false;
        String status = jdbcClient.sql("SELECT status FROM ingestion_jobs WHERE id = :id")
            .param("id", jobId).query(String.class).optional().orElse("running");
        return !"running".equals(status);
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

    private static ParsedFile readMd(Path path) {
        try {
            String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            text = text.replace("\r\n", "\n").replace('\r', '\n');
            text = joinWrappedTableRows(text);
            Matcher m = FRONTMATTER_PATTERN.matcher(text);
            if (m.find()) {
                Map<String, Object> fm = parseFrontmatter(m.group(1));
                String body = text.substring(m.end()).strip();
                return new ParsedFile(fm, body);
            }
        } catch (IOException e) {
            log.warn("Failed to read markdown file: {}", path, e);
        }
        return null;
    }

    /**
     * Joins a markdown table row that was wrapped across several lines.
     *
     * <p>
     * Fenced code blocks are skipped: a T-SQL bitwise-OR continuation line starts with {@code |}
     * and is not a table row, so joining it destroyed the source. Exactly five documents per kit
     * version were mangled — {@code AAD_TIAADRoleEligibility} (32 lines collapsed to 1),
     * {@code ADS_TIADSAccount}, {@code AAD_TIAADRoleAssignment}, {@code ADS_VElementManagerValid},
     * {@code ADS_VGroupMemberValid_Group} — and since all five are summarized, the mangled text was
     * what the summarizer LLM read.
     */
    private static String joinWrappedTableRows(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean inFence = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence;
            } else if (!inFence && trimmed.startsWith("|") && !line.stripTrailing().endsWith("|")) {
                while (i + 1 < lines.length && !line.stripTrailing().endsWith("|")) {
                    i++;
                    line = line.stripTrailing() + " " + lines[i].stripLeading();
                }
            }
            out.append(line);
            if (i < lines.length - 1) {
                out.append("\n");
            }
        }
        return out.toString();
    }

    /**
     * Strict frontmatter parse for the custom path: unparseable (or non-map) YAML throws instead of
     * degrading to empty metadata, because empty metadata silently means {@code kind='other'} and a
     * graph entity that can never find its document.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseFrontmatter(String yamlText) {
        Object loaded;
        try {
            loaded = new Yaml().load(yamlText);
        } catch (RuntimeException e) {
            throw new FrontmatterParseException(firstLine(e.getMessage()));
        }
        if (loaded instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new FrontmatterParseException("frontmatter is not a YAML mapping (got "
            + (loaded == null ? "nothing" : "a scalar") + ")");
    }

    /**
     * Keeps a multi-line SnakeYAML message (which embeds the source excerpt) to one usable line.
     */
    private static String firstLine(@Nullable String message) {
        if (message == null || message.isBlank()) {
            return "invalid YAML";
        }
        return message.lines().findFirst().orElse("invalid YAML").trim();
    }

    /**
     * Renders offending items as a single deterministic, length-capped clause for a failure
     * message.
     */
    private static String summarize(Collection<String> items) {
        List<String> sorted = new ArrayList<>(new LinkedHashSet<>(items));
        Collections.sort(sorted);
        String shown =
            String.join("; ", sorted.subList(0, Math.min(MAX_REPORTED_ITEMS, sorted.size())));
        if (sorted.size() > MAX_REPORTED_ITEMS) {
            return shown + "; … (" + MAX_REPORTED_ITEMS + " of " + sorted.size() + " shown)";
        }
        return shown;
    }

    private static String extractCodeBlocks(String body) {
        Matcher m = CODE_BLOCK_RE.matcher(body);
        List<String> parts = new ArrayList<>();
        while (m.find()) {
            parts.add(m.group(1));
        }
        return String.join("\n", parts);
    }

    private static String replaceSourceWithSummary(String body, String summary) {
        int sourceIndex = body.indexOf("## Source");
        if (sourceIndex == -1) {
            return body + "\n\n## Summary\n" + summary;
        }
        return body.substring(0, sourceIndex) + "## Summary\n" + summary;
    }

    private static String stripCodeBlockMarkers(String body) {
        String trimmed = body.trim();
        if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline != -1 && firstNewline < trimmed.length() - 3) {
                return trimmed.substring(firstNewline + 1, trimmed.length() - 3).trim();
            }
        }
        return body;
    }
}
