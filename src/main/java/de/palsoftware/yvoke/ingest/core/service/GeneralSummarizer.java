package de.palsoftware.yvoke.ingest.core.service;

import de.palsoftware.yvoke.llm.core.service.LlmClient;
import de.palsoftware.yvoke.llm.core.context.LlmCallContextHolder;
import de.palsoftware.yvoke.llm.core.model.LlmMessage;
import de.palsoftware.yvoke.llm.core.model.LlmRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class GeneralSummarizer {

    private static final Logger log = LoggerFactory.getLogger(GeneralSummarizer.class);

    private final LlmClient llmClient;
    private final JdbcClient jdbcClient;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final String thinkingLevel;

    public GeneralSummarizer(LlmClient llmClient, JdbcClient jdbcClient, String model,
        double temperature, int maxTokens) {
        this(llmClient, jdbcClient, model, temperature, maxTokens, null);
    }

    @Autowired
    public GeneralSummarizer(LlmClient llmClient, JdbcClient jdbcClient,
        @Value("${app.ai.summarize.model}") String model,
        @Value("${app.ai.summarize.temperature}") double temperature,
        @Value("${app.ai.summarize.max-tokens}") int maxTokens,
        @Value("${app.ai.summarize.thinking-level}") String thinkingLevel) {
        this.llmClient = llmClient;
        this.jdbcClient = jdbcClient;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.thinkingLevel = thinkingLevel;
    }

    public String summarize(String content, String cacheKind, String systemPromptOverride,
        String userMessage) {
        if (content == null || content.strip().isEmpty()) {
            return "";
        }

        String sha = computeSha256(content);
        Optional<String> cached = getCachedSummary(sha, cacheKind);
        if (cached.isPresent()) {
            log.info("Summary cache hit for kind '{}' and SHA '{}'", cacheKind, sha);
            return cached.get();
        }

        log.debug("Summary cache miss for kind '{}' and SHA '{}'", cacheKind, sha);
        log.info(
            "General Summarizer: cache miss for kind '{}' and SHA '{}', calling LLM model '{}'",
            cacheKind, sha, model);

        int maxAttempts = 3;
        String cleaned = "";
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                String rawResponse = callModel(systemPromptOverride, userMessage);
                cleaned = cleanReasoningLeak(rawResponse);
                if (!cleaned.isEmpty()) {
                    break;
                }
                log.warn(
                    "General Summarizer: Attempt {}/{} returned empty summary for kind '{}'. Retrying...",
                    attempt, maxAttempts, cacheKind);
            } catch (Exception e) {
                log.warn(
                    "General Summarizer: Attempt {}/{} failed with exception for kind '{}': {}. Retrying...",
                    attempt, maxAttempts, cacheKind, e.getMessage());
            }
            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (cleaned.isEmpty()) {
            cleaned = "Summary unavailable.";
            log.warn(
                "General Summarizer: All {} attempts to summarize failed. Using fallback summary: \"{}\"",
                maxAttempts, cleaned);
            return cleaned;
        }

        putCachedSummary(sha, cacheKind, cleaned);
        return cleaned;
    }

    private String callModel(String systemPromptOverride, String userMessage) {
        String activePrompt =
            (systemPromptOverride != null && !systemPromptOverride.isBlank()) ? systemPromptOverride
                : "";

        log.info(
            "LLM Request (General summarizer): model={}, temperature={}, maxTokens={}, promptLength={}",
            model, temperature, maxTokens, userMessage.length());

        LlmRequest request = new LlmRequest(model,
            List.of(new LlmMessage("system", activePrompt), new LlmMessage("user", userMessage)),
            temperature, maxTokens, null, thinkingLevel);
        // Summarization used to publish no usage event at all, so its spend never appeared in any
        // cost view. Accounting now happens in AccountingLlmClient; this only has to declare what
        // kind of call it is. Set on the calling thread because summarization fans out over
        // virtual threads, which do not inherit this ThreadLocal.
        String response;
        try {
            LlmCallContextHolder.set(null, null, null, null, "summarization", "summarizer");
            response = llmClient.generate(request).content();
        } finally {
            LlmCallContextHolder.clear();
        }
        log.info("LLM Response (General summarizer): received summary (length={})",
            response.length());
        return response;
    }

    /**
     * Scoped to the caller's kind on purpose: {@code summary_cache} is shared with
     * {@code DocumentKgExtractor}, which stores an extracted-graph JSON blob under
     * {@code kind='kg'}. Keying on {@code source_sha} alone returned that blob as a prose summary
     * for identical content. The table's PK is {@code source_sha} alone, so a foreign-kind row
     * simply produces a miss and the summary is recomputed — a cache miss is the correct trade for
     * not emitting another producer's payload as a summary.
     */
    private Optional<String> getCachedSummary(String sha, String cacheKind) {
        return jdbcClient
            .sql("SELECT summary FROM summary_cache WHERE source_sha = :sha AND kind = :kind")
            .param("sha", sha).param("kind", cacheKind).query(String.class).optional();
    }

    private void putCachedSummary(String sha, String cacheKind, String summary) {
        jdbcClient.sql("""
            INSERT INTO summary_cache (source_sha, kind, summary)
            VALUES (:sha, :kind, :summary)
            ON CONFLICT (source_sha) DO NOTHING
            """).param("sha", sha).param("kind", cacheKind).param("summary", summary).update();
    }

    public static String computeSha256(String text) {
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
            throw new IllegalStateException("SHA-256 digest algorithm is not available", e);
        }
    }

    public static String cleanReasoningLeak(String text) {
        if (text == null) {
            return "";
        }
        text = text.trim();

        // 1. If model wrote a final "Summary:" marker after rambling, use only what comes after the
        // LAST such marker.
        Pattern embeddedSummaryPat =
            Pattern.compile("(?:^|\\n)\\s*(?:\\*\\*\\s*)?summary(?:\\s*\\*\\*)?\\s*[:\\-]\\s*",
                Pattern.CASE_INSENSITIVE);
        Matcher m = embeddedSummaryPat.matcher(text);
        int lastEnd = -1;
        while (m.find()) {
            lastEnd = m.end();
        }
        if (lastEnd != -1) {
            text = text.substring(lastEnd).trim();
        }

        // 2. Strip leak preambles iteratively
        Pattern[] leakPats = new Pattern[] {
            Pattern.compile("^\\s*we need to summarize.*?(?=\\n\\n|\\z)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
            Pattern.compile("^\\s*let me (read|analyze|look at|trace through).*?(?=\\n\\n|\\z)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
            Pattern.compile("^\\s*the function:\\s*.*?(?=\\n\\n|\\z)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL)};

        for (int step = 0; step < 3; step++) {
            String before = text;
            for (Pattern pat : leakPats) {
                Matcher lm = pat.matcher(text);
                if (lm.find()) {
                    text = lm.replaceFirst("").trim();
                }
            }
            if (text.equals(before)) {
                break;
            }
        }

        // 3. Keep only the first paragraph if there are multiple paragraphs
        String[] paragraphs = text.split("\\n\\s*\\n");
        if (paragraphs.length > 0) {
            text = paragraphs[0].trim();
        }

        // 4. Strip leading "Summary:", "Description:", "Reasoning:", etc. if any remains
        text = text.replaceAll(
            "^(?i)(summary|description|reasoning|analysis|thought|thinking)\\s*[:\\-]\\s*", "");

        return text.trim();
    }
}
