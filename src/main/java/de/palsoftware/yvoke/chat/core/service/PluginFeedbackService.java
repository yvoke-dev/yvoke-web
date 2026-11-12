package de.palsoftware.yvoke.chat.core.service;

import de.palsoftware.yvoke.area.core.Area;
import de.palsoftware.yvoke.area.core.AreaService;
import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.model.ConversationSetting;
import de.palsoftware.yvoke.chat.core.model.ConversationSource;
import de.palsoftware.yvoke.chat.core.model.Feedback;
import de.palsoftware.yvoke.chat.core.model.Message;
import de.palsoftware.yvoke.chat.core.repository.ConversationRepository;
import de.palsoftware.yvoke.chat.core.repository.FeedbackRepository;
import de.palsoftware.yvoke.chat.core.repository.MessageRepository;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfile;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfileService;
import de.palsoftware.yvoke.rag.core.service.CitationVerifier.CitationCheckResult;
import de.palsoftware.yvoke.rag.core.service.CitationVerifier.CitationStatus;
import de.palsoftware.yvoke.rag.prompt.Playbook;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import de.palsoftware.yvoke.shared.security.AreaAccessContext;
import de.palsoftware.yvoke.shared.user.model.User;
import de.palsoftware.yvoke.shared.user.service.UserService;
import jakarta.annotation.Nullable;
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
    private static final int MAX_METADATA_CHARS = 256;
    private static final int MAX_QUESTION_CHARS = 50_000;
    private static final int MAX_ANSWER_CHARS = 200_000;
    private static final int MAX_COMMENT_CHARS = 4_000;
    private static final int MAX_CITED_IDS = 100;
    private static final Set<String> ALLOWED_MODES = Set.of("single", "multi");

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final FeedbackRepository feedbackRepository;
    private final UserService userService;
    private final AreaService areaService;
    private final @Nullable PlaybookService playbookService;
    private final @Nullable OrchestratorProfileService orchestratorProfileService;
    private final Clock clock;

    @Autowired
    public PluginFeedbackService(ConversationRepository conversationRepository,
        MessageRepository messageRepository, FeedbackRepository feedbackRepository,
        UserService userService, AreaService areaService, @Nullable PlaybookService playbookService,
        @Nullable OrchestratorProfileService orchestratorProfileService) {
        this(conversationRepository, messageRepository, feedbackRepository, userService,
            areaService, playbookService, orchestratorProfileService, Clock.systemUTC());
    }

    public PluginFeedbackService(ConversationRepository conversationRepository,
        MessageRepository messageRepository, FeedbackRepository feedbackRepository,
        UserService userService, AreaService areaService) {
        this(conversationRepository, messageRepository, feedbackRepository, userService,
            areaService, null, null, Clock.systemUTC());
    }

    PluginFeedbackService(ConversationRepository conversationRepository,
        MessageRepository messageRepository, FeedbackRepository feedbackRepository,
        UserService userService, AreaService areaService, Clock clock) {
        this(conversationRepository, messageRepository, feedbackRepository, userService,
            areaService, null, null, clock);
    }

    PluginFeedbackService(ConversationRepository conversationRepository,
        MessageRepository messageRepository, FeedbackRepository feedbackRepository,
        UserService userService, AreaService areaService, @Nullable PlaybookService playbookService,
        @Nullable OrchestratorProfileService orchestratorProfileService, Clock clock) {
        this.conversationRepository = Objects.requireNonNull(conversationRepository);
        this.messageRepository = Objects.requireNonNull(messageRepository);
        this.feedbackRepository = Objects.requireNonNull(feedbackRepository);
        this.userService = Objects.requireNonNull(userService);
        this.areaService = Objects.requireNonNull(areaService);
        this.playbookService = playbookService;
        this.orchestratorProfileService = orchestratorProfileService;
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

        Optional<Set<String>> allowedAreas = AreaAccessContext.allowedUserAreasLower();
        if (allowedAreas.isPresent() && allowedAreas.get().isEmpty()) {
            throw new SecurityException("Access denied: user has no accessible areas.");
        }

        String trimmedTurnId = requireNonBlank(request.turnId(), "turnId");
        requireMaxLength(trimmedTurnId, "turnId", MAX_METADATA_CHARS);

        String trimmedQuestion = requireNonBlank(request.question(), "question");
        requireMaxLength(trimmedQuestion, "question", MAX_QUESTION_CHARS);

        String trimmedAnswer = requireNonBlank(request.answer(), "answer");
        requireMaxLength(trimmedAnswer, "answer", MAX_ANSWER_CHARS);

        if (request.rating() == null || (request.rating() != 1 && request.rating() != -1)) {
            throw new IllegalArgumentException("rating must be 1 or -1.");
        }
        int rating = request.rating();

        String normalizedComment = trimToNull(request.comment());
        if (rating == -1 && normalizedComment == null) {
            throw new IllegalArgumentException(
                "comment is required for negative feedback (rating = -1).");
        }
        requireMaxLength(normalizedComment, "comment", MAX_COMMENT_CHARS);

        String normalizedMode = trimToNull(request.mode());
        if (normalizedMode != null && !ALLOWED_MODES.contains(normalizedMode)) {
            throw new IllegalArgumentException("mode must be 'single' or 'multi' when provided.");
        }

        String normalizedPlaybook = trimToNull(request.playbook());
        requireMaxLength(normalizedPlaybook, "playbook", MAX_METADATA_CHARS);
        Integer rawPlaybookVersion = request.playbookVersion();
        if (rawPlaybookVersion != null && rawPlaybookVersion < 1) {
            throw new IllegalArgumentException("playbookVersion must be >= 1 when provided.");
        }
        Integer normalizedPlaybookVersion = normalizedPlaybook != null ? rawPlaybookVersion : null;

        String normalizedProfile = trimToNull(request.profile());
        requireMaxLength(normalizedProfile, "profile", MAX_METADATA_CHARS);

        String normalizedModel = trimToNull(request.model());
        requireMaxLength(normalizedModel, "model", MAX_METADATA_CHARS);

        String normalizedClient = trimToNull(request.client());
        requireMaxLength(normalizedClient, "client", MAX_METADATA_CHARS);

        String normalizedPluginVersion = trimToNull(request.pluginVersion());
        requireMaxLength(normalizedPluginVersion, "pluginVersion", MAX_METADATA_CHARS);

        List<String> sanitizedCitedIds = sanitizeCitedIds(request.citedIds());

        String resolvedArea =
            resolveAndAuthorizeArea(request.area(), normalizedPlaybook, normalizedProfile);

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
            conversationRepository.createIfAbsent(conversationId, userId,
                deriveTitle(trimmedQuestion), settings, ConversationSource.CLAUDE_PLUGIN.getValue(),
                resolvedArea);
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

    private String resolveAndAuthorizeArea(String rawArea, String normalizedPlaybook,
        String normalizedProfile) {
        Optional<Set<String>> allowed = AreaAccessContext.allowedUserAreasLower();
        if (allowed.isPresent() && allowed.get().isEmpty()) {
            throw new SecurityException("Access denied: user has no accessible areas.");
        }
        String resolvedArea = null;
        String trimmedArea = trimToNull(rawArea);
        if (trimmedArea != null) {
            requireMaxLength(trimmedArea, "area", MAX_METADATA_CHARS);
            resolvedArea = authorizeCanonicalArea(trimmedArea);
        } else if (normalizedPlaybook != null && playbookService != null) {
            Optional<String> playbookArea = playbookService.getPlaybook(normalizedPlaybook)
                .map(Playbook::area).map(PluginFeedbackService::trimToNull);
            if (playbookArea.isPresent()) {
                resolvedArea = authorizeCanonicalArea(playbookArea.get());
            }
        }
        if (resolvedArea == null && normalizedProfile != null
            && orchestratorProfileService != null) {
            Optional<String> profileArea = orchestratorProfileService.getProfile(normalizedProfile)
                .map(OrchestratorProfile::area).map(PluginFeedbackService::trimToNull);
            if (profileArea.isPresent()) {
                resolvedArea = authorizeCanonicalArea(profileArea.get());
            }
        }
        if (resolvedArea == null && allowed.isPresent() && allowed.get().size() == 1) {
            String singleAllowed = allowed.get().iterator().next();
            resolvedArea = areaService.findArea(singleAllowed).map(Area::name).orElse(null);
        }
        if (resolvedArea != null) {
            validatePlaybookAndProfileMatchArea(resolvedArea, normalizedPlaybook,
                normalizedProfile);
        }
        return resolvedArea;
    }

    private void validatePlaybookAndProfileMatchArea(String resolvedArea, String normalizedPlaybook,
        String normalizedProfile) {
        if (playbookService != null && normalizedPlaybook != null) {
            Optional<String> pbArea = playbookService.getPlaybook(normalizedPlaybook)
                .map(Playbook::area).map(PluginFeedbackService::trimToNull);
            if (pbArea.isPresent() && !pbArea.get().trim().equalsIgnoreCase(resolvedArea)) {
                throw new IllegalArgumentException("playbook '" + normalizedPlaybook
                    + "' does not belong to area '" + resolvedArea + "'.");
            }
        }
        if (orchestratorProfileService != null && normalizedProfile != null) {
            Optional<String> profArea = orchestratorProfileService.getProfile(normalizedProfile)
                .map(OrchestratorProfile::area).map(PluginFeedbackService::trimToNull);
            if (profArea.isPresent() && !profArea.get().trim().equalsIgnoreCase(resolvedArea)) {
                throw new IllegalArgumentException("profile '" + normalizedProfile
                    + "' does not belong to area '" + resolvedArea + "'.");
            }
        }
    }

    private String authorizeCanonicalArea(String candidateArea) {
        Optional<Area> found = areaService.findArea(candidateArea);
        if (found.isEmpty() || !AreaAccessContext.canAccessUserArea(found.get().name())) {
            throw new IllegalArgumentException("area '" + candidateArea + "' does not exist.");
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
            messageRepository.saveIfAbsent(userMsg);
        }
        Message assistantMsg = new Message(assistantMessageId, conversationId, "assistant", answer,
            playbook, playbookVersion, List.of(), buildCitationsList(citedIds),
            now.plusNanos(1_000_000), null, null, null, null, null, "done", model);
        messageRepository.saveIfAbsent(assistantMsg);
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
            settings.put(ConversationSetting.PLUGIN_VERSION.getValue(), pluginVersion);
        }
        if (mode != null) {
            settings.put("mode", mode);
        }
        if (profile != null) {
            settings.put("profile", profile);
            settings.put(ConversationSetting.ORCHESTRATOR_PROFILE.getValue(), profile);
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
        if (rawIds.size() > MAX_CITED_IDS) {
            throw new IllegalArgumentException(
                "citedIds must not exceed " + MAX_CITED_IDS + " items.");
        }
        Set<String> deduped = new LinkedHashSet<>();
        for (String raw : rawIds) {
            String trimmed = trimToNull(raw);
            if (trimmed != null) {
                if (trimmed.length() > MAX_METADATA_CHARS) {
                    throw new IllegalArgumentException(
                        "Each citedId must not exceed " + MAX_METADATA_CHARS + " characters.");
                }
                deduped.add(trimmed);
            }
        }
        return List.copyOf(deduped);
    }

    static List<CitationCheckResult> buildCitationsList(List<String> citedIds) {
        if (citedIds == null || citedIds.isEmpty()) {
            return List.of();
        }
        List<CitationCheckResult> results = new ArrayList<>(citedIds.size());
        for (String id : citedIds) {
            String inner = id.trim();
            if (inner.startsWith("[") && inner.endsWith("]") && inner.length() >= 2) {
                inner = inner.substring(1, inner.length() - 1).trim();
            }
            String lower = inner.toLowerCase(Locale.ROOT);
            String raw;
            String type;
            if (lower.startsWith("document_id=")) {
                String idPart = inner.substring("document_id=".length()).trim();
                raw = "[document_id=" + idPart + "]";
                type = "document";
            } else if (lower.startsWith("chunk_id=")) {
                String idPart = inner.substring("chunk_id=".length()).trim();
                raw = "[chunk_id=" + idPart + "]";
                type = "chunk";
            } else {
                raw = "[chunk_id=" + inner + "]";
                type = "chunk";
            }
            results.add(new CitationCheckResult(raw, type, CitationStatus.UNVERIFIED,
                "reported by claude-plugin"));
        }
        return List.copyOf(results);
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

    private static void requireMaxLength(String value, String fieldName, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(
                fieldName + " must not exceed " + maxLength + " characters.");
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
