package de.palsoftware.yvoke.kg.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.llm.core.context.LlmCallContextHolder;
import de.palsoftware.yvoke.kg.core.model.KgExtractionResult;
import de.palsoftware.yvoke.kg.core.model.KgExtractionResult.ExtractedEntity;
import de.palsoftware.yvoke.kg.core.model.KgExtractionResult.ExtractedRelationship;
import de.palsoftware.yvoke.llm.core.model.LlmResponse;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import de.palsoftware.yvoke.llm.core.service.LlmClient;
import de.palsoftware.yvoke.llm.core.model.LlmMessage;
import de.palsoftware.yvoke.llm.core.model.LlmRequest;
import de.palsoftware.yvoke.shared.jobengine.model.JobContext;
import de.palsoftware.yvoke.shared.jobengine.model.JobStep;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.core.JsonProcessingException;

@Service
public class DocumentKgExtractor {

    private static final Logger log = LoggerFactory.getLogger(DocumentKgExtractor.class);

    static final String RETRY_INSTRUCTION =
        "Your previous response could not be parsed as valid JSON. Respond again with STRICT JSON "
            + "ONLY (no Markdown fences, no prose) matching exactly the required schema: "
            + "{\"entities\":[{\"name\":\"...\",\"kind\":\"...\",\"description\":\"...\"}],"
            + "\"relationships\":[{\"subject\":\"...\",\"predicate\":\"...\",\"object\":\"...\","
            + "\"description\":\"...\"}]}.";

    /**
     * Response schema used to enforce structured JSON output on providers that support it (Gemini).
     * The prompt-based JSON instruction, code-fence stripping and corrective retry remain as a
     * fallback for providers that ignore the schema.
     */
    private static final String RESPONSE_SCHEMA_JSON = "{\"type\":\"object\",\"properties\":{"
        + "\"entities\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{"
        + "\"name\":{\"type\":\"string\"},\"kind\":{\"type\":\"string\"},"
        + "\"description\":{\"type\":\"string\"}},\"required\":[\"name\"]}},"
        + "\"relationships\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{"
        + "\"subject\":{\"type\":\"string\"},\"predicate\":{\"type\":\"string\"},"
        + "\"object\":{\"type\":\"string\"},\"description\":{\"type\":\"string\"}},"
        + "\"required\":[\"subject\",\"predicate\",\"object\"]}}},"
        + "\"required\":[\"entities\",\"relationships\"]}";

    private static final Map<String, Object> RESPONSE_SCHEMA = buildResponseSchema();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> buildResponseSchema() {
        try {
            return new ObjectMapper().readValue(RESPONSE_SCHEMA_JSON, Map.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final JdbcClient jdbcClient;
    private final String model;
    private final int maxTokens;
    private final double temperature;
    private final int concurrency;
    private final int maxAttempts;
    private final String thinkingLevel;

    public DocumentKgExtractor(LlmClient llmClient, ObjectMapper objectMapper,
        JdbcClient jdbcClient, String model, int maxTokens, double temperature, int concurrency,
        int maxAttempts) {
        this(llmClient, objectMapper, jdbcClient, model, maxTokens, temperature, concurrency,
            maxAttempts, null);
    }

    @Autowired
    public DocumentKgExtractor(LlmClient llmClient, ObjectMapper objectMapper,
        JdbcClient jdbcClient, @Value("${app.ai.kg.model}") String model,
        @Value("${app.ai.kg.max-tokens}") int maxTokens,
        @Value("${app.ai.kg.temperature}") double temperature,
        @Value("${app.ai.kg.concurrency}") int concurrency,
        @Value("${app.ai.kg.max-attempts}") int maxAttempts,
        @Value("${app.ai.kg.thinking-level}") String thinkingLevel) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.jdbcClient = jdbcClient;
        this.model = model;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.concurrency = Math.max(1, concurrency);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.thinkingLevel = thinkingLevel;
    }

    /**
     * @param systemPrompt the KG prompt to run with — required, and validated here rather than per
     *        chunk. It used to be an optional "override" resolved inside {@link #callModel}: once
     *        per chunk AND once per retry attempt, falling back to
     *        {@code getPrompt("default-kg").orElse("")} — a prompt name registered in no
     *        deployment. So the fallback always missed and extraction ran with an EMPTY system
     *        prompt. That prompt is what specifies the strict-JSON response shape, so the model was
     *        asked for a graph with no schema; the resulting parse failures were counted as chunks
     *        with nothing in them, which is indistinguishable from a genuinely empty corpus.
     *        <p>
     *        The two null-passing overloads are gone deliberately. One of them was reached whenever
     *        no {@code kgPrompt} was selected, which is how the empty-prompt path was entered in
     *        practice; making the parameter required turns that into a compile error.
     */
    public KgExtractionResult extract(List<String> chunkTexts, UUID jobId, JobContext ctx,
        String systemPrompt) {
        int total = chunkTexts.size();
        if (total == 0) {
            return new KgExtractionResult(List.of(), List.of(), 0);
        }
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException("A KG system prompt is required to extract a"
                + " knowledge graph; none was supplied. It defines the strict-JSON response shape,"
                + " so extracting without it yields unparseable output, not a smaller graph.");
        }

        // Bound the fan-out: each chunk is mined on its own virtual thread, but at most
        // `concurrency` LLM calls are in flight at once (app.ai.kg.concurrency, default 4).
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicInteger completed = new AtomicInteger(0);
        Semaphore gate = new Semaphore(concurrency);

        List<ChunkOutcome> outcomes = new ArrayList<>(total);
        try (
            ExecutorService delegate = Executors
                .newThreadPerTaskExecutor(Thread.ofVirtual().name("kg-extract-", 0).factory());
            ExecutorService executor = new DelegatingSecurityContextExecutorService(delegate)) {
            List<Future<ChunkOutcome>> futures = new ArrayList<>(total);
            for (int i = 0; i < total; i++) {
                final int index = i;
                final String chunkText = chunkTexts.get(i);
                futures.add(executor.submit(() -> processChunk(index, total, chunkText, jobId, ctx,
                    gate, cancelled, completed, systemPrompt)));
            }
            // Collect in submission order so the aggregated graph is deterministic.
            for (Future<ChunkOutcome> future : futures) {
                try {
                    outcomes.add(future.get());
                } catch (ExecutionException e) {
                    throw new IllegalStateException("KG extraction task failed", e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("KG extraction was interrupted", e);
                }
            }
        }

        if (cancelled.get()) {
            log.info("KG extraction for job {} cancelled.", jobId);
            throw new IllegalStateException("Job was cancelled by administrator");
        }

        List<ExtractedEntity> entities = new ArrayList<>();
        List<ExtractedRelationship> relationships = new ArrayList<>();
        List<KgExtractionResult.ChunkStatus> chunkStatuses = new ArrayList<>(outcomes.size());
        int skipped = 0;
        for (ChunkOutcome outcome : outcomes) {
            if (outcome == null) {
                continue;
            }
            entities.addAll(outcome.entities());
            relationships.addAll(outcome.relationships());
            chunkStatuses.add(
                new KgExtractionResult.ChunkStatus(outcome.index(), !outcome.skipped(), model));
            if (outcome.skipped()) {
                skipped++;
            }
        }
        return new KgExtractionResult(entities, relationships, skipped, chunkStatuses);
    }

    private ChunkOutcome processChunk(int index, int total, String chunkText, UUID jobId,
        JobContext ctx, Semaphore gate, AtomicBoolean cancelled, AtomicInteger completed,
        String systemPrompt) throws InterruptedException {
        gate.acquire();
        try {
            if (cancelled.get()) {
                return null;
            }
            if (jobId != null && isJobCancelled(jobId)) {
                cancelled.set(true);
                return null;
            }

            log.info("KG extraction: processing chunk {}/{} ({} chars) for job {}", index + 1,
                total, chunkText.length(), jobId != null ? jobId : "null");

            return extractChunkWithRetry(index, total, chunkText, systemPrompt);
        } finally {
            gate.release();
            int done = completed.incrementAndGet();
            if (ctx != null) {
                int progress = 80 + (int) ((done * 15.0) / total);
                ctx.report(JobStep.EXTRACT, progress,
                    String.format("Processing chunk %d of %d", done, total));
            }
        }
    }

    private ChunkOutcome extractChunkWithRetry(int index, int total, String chunkText,
        String systemPrompt) {
        String sha = computeSha256(chunkText);
        Optional<String> cached = getCachedExtraction(sha);
        if (cached.isPresent()) {
            log.info("KG extraction: cache hit for chunk {}/{} ({})", index + 1, total, sha);
            List<ExtractedEntity> entities = new ArrayList<>();
            List<ExtractedRelationship> relationships = new ArrayList<>();
            try {
                parseInto(cached.get(), entities, relationships);
                return new ChunkOutcome(index, entities, relationships, false);
            } catch (RuntimeException e) {
                log.warn(
                    "KG extraction: cached JSON corrupted for chunk {}/{} ({}); falling back to LLM. cause={}",
                    index + 1, total, sha, e.toString());
            }
        }

        boolean correctiveRetry = false;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String raw;
            try {
                raw = callModel(chunkText, correctiveRetry, systemPrompt);
            } catch (RuntimeException e) {
                correctiveRetry = false;
                if (attempt < maxAttempts) {
                    log.warn(
                        "KG extraction call failed for chunk {}/{} (attempt {}/{}); retrying. cause={}",
                        index + 1, total, attempt, maxAttempts, e.toString());
                    continue;
                }
                log.warn(
                    "KG extraction call failed for chunk {}/{} after {} attempts; skipping. cause={}",
                    index + 1, total, maxAttempts, e.toString());
                return new ChunkOutcome(index, List.of(), List.of(), true);
            }

            List<ExtractedEntity> entities = new ArrayList<>();
            List<ExtractedRelationship> relationships = new ArrayList<>();
            try {
                parseInto(raw, entities, relationships);
                putCachedExtraction(sha, raw);
                return new ChunkOutcome(index, entities, relationships, false);
            } catch (RuntimeException e) {
                correctiveRetry = true;
                if (attempt < maxAttempts) {
                    log.warn(
                        "KG extraction output malformed/truncated at chunk {}/{} (attempt {}/{}); "
                            + "retrying with corrective prompt. cause={}",
                        index + 1, total, attempt, maxAttempts, e.toString());
                    continue;
                }
                log.warn(
                    "KG extraction output malformed/truncated at chunk {}/{} after {} attempts; "
                        + "skipping chunk. cause={}",
                    index + 1, total, maxAttempts, e.toString());
                return new ChunkOutcome(index, List.of(), List.of(), true);
            }
        }
        // Unreachable: the loop always returns within maxAttempts (>= 1).
        return new ChunkOutcome(index, List.of(), List.of(), true);
    }

    private Optional<String> getCachedExtraction(String sha) {
        return jdbcClient
            .sql("SELECT summary FROM summary_cache WHERE source_sha = :sha AND kind = 'kg'")
            .param("sha", sha).query(String.class).optional();
    }

    private void putCachedExtraction(String sha, String rawJson) {
        jdbcClient.sql("""
            INSERT INTO summary_cache (source_sha, kind, summary)
            VALUES (:sha, 'kg', :rawJson)
            ON CONFLICT (source_sha) DO NOTHING
            """).param("sha", sha).param("rawJson", rawJson).update();
    }

    private String computeSha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private record ChunkOutcome(int index, List<ExtractedEntity> entities,
        List<ExtractedRelationship> relationships, boolean skipped) {}

    private boolean isJobCancelled(UUID jobId) {
        if (jobId == null) {
            return false;
        }
        String status = jdbcClient.sql("SELECT status FROM ingestion_jobs WHERE id = :id")
            .param("id", jobId).query(String.class).optional().orElse("running");
        return !"running".equals(status);
    }

    private String callModel(String chunkText, boolean appendCorrective, String systemPrompt) {
        log.info(
            "LLM Request (KG extraction): model={}, temperature={}, maxTokens={}, promptLength={}, corrective={}",
            model, temperature, maxTokens, chunkText.length(), appendCorrective);
        List<LlmMessage> messages = new ArrayList<>(3);
        messages.add(new LlmMessage("system", systemPrompt));
        messages.add(new LlmMessage("user", chunkText));
        if (appendCorrective) {
            messages.add(new LlmMessage("user", RETRY_INSTRUCTION));
        }
        LlmRequest request = new LlmRequest(model, messages, temperature, maxTokens, null,
            thinkingLevel, "application/json", RESPONSE_SCHEMA, null);
        // Extraction fans out over virtual threads and LlmCallContextHolder is a ThreadLocal that
        // DelegatingSecurityContextExecutorService does not carry, so the context has to be set
        // here — on the worker thread that actually makes the call — not around the executor.
        LlmResponse res;
        try {
            LlmCallContextHolder.set(null, null, null, null, "kg_extraction", "kg_extractor");
            res = llmClient.generate(request);
        } finally {
            LlmCallContextHolder.clear();
        }
        String response = res.content();
        log.info("LLM Response (KG extraction): received response (length={})", response.length());

        return response;
    }

    private void parseInto(String raw, List<ExtractedEntity> entities,
        List<ExtractedRelationship> relationships) {
        JsonNode root = parseJson(raw);
        if (!root.isObject() || (!root.has("entities") && !root.has("relationships"))) {
            throw new IllegalArgumentException(
                "KG extraction JSON missing required root fields: entities or relationships");
        }

        JsonNode entitiesNode = root.get("entities");
        if (entitiesNode != null && entitiesNode.isArray()) {
            for (JsonNode e : entitiesNode) {
                String name = text(e, "name");
                if (name == null || name.isBlank()) {
                    continue;
                }
                entities.add(new ExtractedEntity(name.strip(), normalizeKind(text(e, "kind")),
                    text(e, "description")));
            }
        }

        JsonNode relsNode = root.get("relationships");
        if (relsNode != null && relsNode.isArray()) {
            for (JsonNode r : relsNode) {
                String subject = text(r, "subject");
                String predicate = text(r, "predicate");
                String object = text(r, "object");
                if (subject == null || subject.isBlank() || predicate == null || predicate.isBlank()
                    || object == null || object.isBlank()) {
                    continue;
                }
                relationships.add(new ExtractedRelationship(subject.strip(), predicate.strip(),
                    object.strip(), text(r, "description")));
            }
        }
    }

    private JsonNode parseJson(String raw) {
        try {
            return objectMapper.readTree(stripCodeFence(raw));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unparseable KG extraction JSON", e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static String normalizeKind(String kind) {
        if (kind == null) {
            return null;
        }
        String trimmed = kind.strip();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    static String stripCodeFence(String raw) {
        String s = raw == null ? "" : raw.strip();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline >= 0) {
                s = s.substring(firstNewline + 1);
            }
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3);
            }
        }
        return s.strip();
    }
}
