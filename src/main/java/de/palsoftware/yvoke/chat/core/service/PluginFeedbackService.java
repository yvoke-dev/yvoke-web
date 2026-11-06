package de.palsoftware.yvoke.chat.core.service;

import de.palsoftware.yvoke.area.core.Area;
import de.palsoftware.yvoke.area.core.AreaService;
import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.model.ConversationSource;
import de.palsoftware.yvoke.chat.core.model.Feedback;
import de.palsoftware.yvoke.chat.core.model.Message;
import de.palsoftware.yvoke.chat.core.repository.ConversationRepository;
import de.palsoftware.yvoke.chat.core.repository.FeedbackRepository;
import de.palsoftware.yvoke.chat.core.repository.MessageRepository;
import de.palsoftware.yvoke.rag.core.service.CitationVerifier.CitationCheckResult;
import de.palsoftware.yvoke.rag.core.service.CitationVerifier.CitationStatus;
import de.palsoftware.yvoke.shared.security.AreaAccessContext;
import de.palsoftware.yvoke.shared.user.model.User;
import de.palsoftware.yvoke.shared.user.service.UserService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists a self-contained plugin turn rating (from {@code submit_feedback}) into
 * {@code conversations}, {@code messages}, and {@code message_feedback}.
 *
 * <p>
 * Deterministic v3 UUIDs scoped to {@code (userId, turnId)} make re-rating the same turn
 * idempotent: the first submission creates the conversation and the user/assistant messages and
 * inserts the feedback row; a subsequent submission for the same {@code turnId} updates the
 * feedback row in place via {@code ON CONFLICT (message_id) DO UPDATE}.
 */
@Service
public class PluginFeedbackService {

    private static final int MAX_TITLE_CHARS = 60;
    private static final Set<String> ALLOWED_MODES = Set.of("single", "multi");
    private static final Pattern UUID_PATTERN = Pattern
        .compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern UUID_NO_HYPHENS_PATTERN = Pattern.compile("^[0-9a-fA-F]{32}$");

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final FeedbackRepository feedbackRepository;
    private final UserService userService;
    private final AreaService areaService;
    private final Clock clock;

    @Autowired
    public PluginFeedbackService(ConversationRepository conversationRepository,
        MessageRepository messageRepository, FeedbackRepository feedbackRepository,
        UserService userService, AreaService areaService) {
        this(conversationRepository, messageRepository, feedbackRepository, userService,
            areaService, Clock.systemUTC());
    }

    PluginFeedbackService(ConversationRepository conversationRepository,
        MessageRepository messageRepository, FeedbackRepository feedbackRepository,
        UserService userService, AreaService areaService, Clock clock) {
        this.conversationRepository = Objects.requireNonNull(conversationRepository);
        this.messageRepository = Objects.requireNonNull(messageRepository);
        this.feedbackRepository = Objects.requireNonNull(feedbackRepository);
        this.userService = Objects.requireNonNull(userService);
        this.areaService = Objects.requireNonNull(areaService);
        this.clock = Objects.requireNonNull(clock);
    }

    public record PluginFeedbackRequest(String turnId, Integer rating, String comment,
        String question, String answer, String area, String mode, String playbook,
        Integer playbookVersion, String profile, List<String> citedIds, String model, String client,
        String pluginVersion) {}

    public record PluginFeedbackResult(UUID feedbackId, UUID messageId, UUID conversationId,
        String turnId, int rating) {}

    @Transactional
    public PluginFeedbackResult submitFeedback(PluginFeedbackRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required.");
        }

        User user = userService.getCurrentUser().orElseThrow(
            () -> new SecurityException("Authenticated user is required to submit feedback."));
        if (user.id() == null) {
            throw new SecurityException("Authenticated user is required to submit feedback.");
        }

        String resolvedArea = resolveAndAuthorizeArea(request.area());

        String trimmedTurnId = requireNonBlank(request.turnId(), "turnId");
        String trimmedQuestion = requireNonBlank(request.question(), "question");
        String trimmedAnswer = requireNonBlank(request.answer(), "answer");

        if (request.rating() == null || (request.rating() != 1 && request.rating() != -1)) {
            throw new IllegalArgumentException("rating must be 1 or -1.");
        }
        int rating = request.rating();

        String normalizedComment = trimToNull(request.comment());
        if (rating == -1 && normalizedComment == null) {
            throw new IllegalArgumentException(
                "comment is required for negative feedback (rating = -1).");
        }

        String normalizedMode = trimToNull(request.mode());
        if (normalizedMode != null && !ALLOWED_MODES.contains(normalizedMode)) {
            throw new IllegalArgumentException("mode must be 'single' or 'multi' when provided.");
        }

        String normalizedPlaybook = trimToNull(request.playbook());
        Integer rawPlaybookVersion = request.playbookVersion();
        if (rawPlaybookVersion != null && rawPlaybookVersion < 1) {
            throw new IllegalArgumentException("playbookVersion must be >= 1 when provided.");
        }
        Integer normalizedPlaybookVersion = normalizedPlaybook != null ? rawPlaybookVersion : null;

        String normalizedProfile = trimToNull(request.profile());
        String normalizedModel = trimToNull(request.model());
        String normalizedClient = trimToNull(request.client());
        String normalizedPluginVersion = trimToNull(request.pluginVersion());
        List<String> sanitizedCitedIds = sanitizeCitedIds(request.citedIds());

        UUID userId = user.id();
        UUID conversationId =
            deterministicUuid("claude-plugin:conv:" + userId + ":" + trimmedTurnId);
        UUID userMessageId =
            deterministicUuid("claude-plugin:msg:user:" + userId + ":" + trimmedTurnId);
        UUID assistantMessageId =
            deterministicUuid("claude-plugin:msg:assistant:" + userId + ":" + trimmedTurnId);

        Optional<Conversation> existingConv = conversationRepository.findById(conversationId);
        Instant now = Instant.now(clock);

        if (existingConv.isEmpty()) {
            Map<String, Object> settings = buildSettings(trimmedTurnId, normalizedClient,
                normalizedPluginVersion, normalizedMode, normalizedProfile, normalizedPlaybook,
                normalizedPlaybookVersion, normalizedModel, sanitizedCitedIds);
            conversationRepository.create(conversationId, userId, deriveTitle(trimmedQuestion),
                settings, ConversationSource.CLAUDE_PLUGIN.getValue(), resolvedArea);
            saveTurnMessages(conversationId, userMessageId, assistantMessageId, trimmedQuestion,
                trimmedAnswer, normalizedPlaybook, normalizedPlaybookVersion, normalizedModel,
                sanitizedCitedIds, now);
        } else {
            Conversation conv = existingConv.get();
            if (!Objects.equals(conv.userId(), userId)) {
                throw new SecurityException("Access denied to conversation: " + conversationId);
            }
            if (messageRepository.findById(assistantMessageId).isEmpty()) {
                saveTurnMessages(conversationId, userMessageId, assistantMessageId, trimmedQuestion,
                    trimmedAnswer, normalizedPlaybook, normalizedPlaybookVersion, normalizedModel,
                    sanitizedCitedIds, now);
            }
            conversationRepository.touch(conversationId);
        }

        feedbackRepository.upsert(assistantMessageId, rating, normalizedComment);
        UUID feedbackId = feedbackRepository.findByMessageId(assistantMessageId).map(Feedback::id)
            .orElseGet(() -> deterministicUuid("claude-plugin:fb:" + userId + ":" + trimmedTurnId));

        return new PluginFeedbackResult(feedbackId, assistantMessageId, conversationId,
            trimmedTurnId, rating);
    }

    private String resolveAndAuthorizeArea(String rawArea) {
        Optional<Set<String>> allowed = AreaAccessContext.allowedUserAreasLower();
        if (allowed.isPresent() && allowed.get().isEmpty()) {
            throw new SecurityException("Access denied: user has no accessible areas.");
        }
        String trimmedArea = trimToNull(rawArea);
        if (trimmedArea == null) {
            return null;
        }
        Optional<Area> found = areaService.findArea(trimmedArea);
        if (found.isEmpty() || !AreaAccessContext.canAccessUserArea(found.get().name())) {
            throw new IllegalArgumentException("area '" + trimmedArea + "' does not exist.");
        }
        return found.get().name();
    }

    private void saveTurnMessages(UUID conversationId, UUID userMessageId, UUID assistantMessageId,
        String question, String answer, String playbook, Integer playbookVersion, String model,
        List<String> citedIds, Instant now) {
        if (messageRepository.findById(userMessageId).isEmpty()) {
            Message userMsg = new Message(userMessageId, conversationId, "user", question, playbook,
                playbookVersion, List.of(), List.of(), now, null, null, null, null, null, "done",
                null);
            messageRepository.save(userMsg);
        }
        Message assistantMsg = new Message(assistantMessageId, conversationId, "assistant", answer,
            playbook, playbookVersion, parseUuidCitedIds(citedIds), buildCitationsList(citedIds),
            now.plusNanos(1_000_000), null, null, null, null, null, "done", model);
        messageRepository.save(assistantMsg);
    }

    static String deriveTitle(String trimmedQuestion) {
        String singleLine = trimmedQuestion.trim().replaceAll("\\s+", " ");
        if (singleLine.length() <= MAX_TITLE_CHARS) {
            return singleLine;
        }
        return singleLine.substring(0, MAX_TITLE_CHARS);
    }

    private static Map<String, Object> buildSettings(String turnId, String client,
        String pluginVersion, String mode, String profile, String playbook, Integer playbookVersion,
        String model, List<String> citedIds) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("turnId", turnId);
        settings.put("client",
            client != null ? client : ConversationSource.CLAUDE_PLUGIN.getValue());
        if (pluginVersion != null) {
            settings.put("pluginVersion", pluginVersion);
        }
        if (mode != null) {
            settings.put("mode", mode);
        }
        if (profile != null) {
            settings.put("profile", profile);
        }
        if (playbook != null) {
            settings.put("playbook", playbook);
        }
        if (playbookVersion != null) {
            settings.put("playbookVersion", playbookVersion);
        }
        if (model != null) {
            settings.put("model", model);
        }
        if (!citedIds.isEmpty()) {
            settings.put("citedIds", citedIds);
        }
        return settings;
    }

    private static List<String> sanitizeCitedIds(List<String> rawIds) {
        if (rawIds == null || rawIds.isEmpty()) {
            return List.of();
        }
        Set<String> deduped = new LinkedHashSet<>();
        for (String raw : rawIds) {
            String trimmed = trimToNull(raw);
            if (trimmed != null) {
                deduped.add(trimmed);
            }
        }
        return List.copyOf(deduped);
    }

    static List<UUID> parseUuidCitedIds(List<String> citedIds) {
        if (citedIds == null || citedIds.isEmpty()) {
            return List.of();
        }
        Set<UUID> uuids = new LinkedHashSet<>();
        for (String raw : citedIds) {
            String candidate = stripCitationDecoration(raw);
            if (UUID_PATTERN.matcher(candidate).matches()) {
                uuids.add(UUID.fromString(candidate.toLowerCase(Locale.ROOT)));
            } else if (UUID_NO_HYPHENS_PATTERN.matcher(candidate).matches()) {
                String lower = candidate.toLowerCase(Locale.ROOT);
                String hyphenated = lower.substring(0, 8) + "-" + lower.substring(8, 12) + "-"
                    + lower.substring(12, 16) + "-" + lower.substring(16, 20) + "-"
                    + lower.substring(20);
                uuids.add(UUID.fromString(hyphenated));
            }
        }
        return List.copyOf(uuids);
    }

    static List<CitationCheckResult> buildCitationsList(List<String> citedIds) {
        if (citedIds == null || citedIds.isEmpty()) {
            return List.of();
        }
        List<CitationCheckResult> results = new ArrayList<>(citedIds.size());
        for (String id : citedIds) {
            String raw = id.startsWith("[") && id.endsWith("]") ? id : "[chunk_id=" + id + "]";
            results.add(new CitationCheckResult(raw, "chunk", CitationStatus.UNVERIFIED,
                "reported by claude-plugin"));
        }
        return List.copyOf(results);
    }

    private static String stripCitationDecoration(String raw) {
        String s = raw.trim();
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1).trim();
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("chunk_id=")) {
            return s.substring("chunk_id=".length()).trim();
        }
        if (lower.startsWith("document_id=")) {
            return s.substring("document_id=".length()).trim();
        }
        return s;
    }

    private static UUID deterministicUuid(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private static String requireNonBlank(String value, String fieldName) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        return trimmed;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
