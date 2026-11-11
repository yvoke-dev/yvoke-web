package de.palsoftware.yvoke.chat.core.service;

import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.model.ConversationSetting;
import de.palsoftware.yvoke.chat.core.model.ConversationSource;
import de.palsoftware.yvoke.chat.core.model.Feedback;
import de.palsoftware.yvoke.chat.core.model.Message;
import de.palsoftware.yvoke.chat.core.repository.ConversationRepository;
import de.palsoftware.yvoke.chat.core.repository.FeedbackRepository;
import de.palsoftware.yvoke.chat.core.repository.MessageRepository;
import de.palsoftware.yvoke.rag.prompt.Playbook;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import de.palsoftware.yvoke.shared.security.AreaAccessContext;
import de.palsoftware.yvoke.shared.user.model.User;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DesktopSyncService {
    private static final Logger log = LoggerFactory.getLogger(DesktopSyncService.class);

    static final String SOURCE_DESKTOP = ConversationSource.DESKTOP.getValue();
    static final String DEFAULT_TITLE = "New Conversation";
    private static final int MAX_AUTO_TITLE_LENGTH = 80;

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final FeedbackRepository feedbackRepository;
    private final PlaybookService playbookService;

    public record NewMessage(String role, String content, Integer promptTokens,
        Integer completionTokens, Integer totalTokens, Integer cachedTokens, Integer thoughtTokens,
        String playbook, Integer playbookVersion) {

        public NewMessage(String role, String content, Integer promptTokens,
            Integer completionTokens, Integer totalTokens, Integer cachedTokens,
            Integer thoughtTokens) {
            this(role, content, promptTokens, completionTokens, totalTokens, cachedTokens,
                thoughtTokens, null, null);
        }
    }

    @Autowired
    public DesktopSyncService(ConversationRepository conversationRepository,
        MessageRepository messageRepository, FeedbackRepository feedbackRepository,
        PlaybookService playbookService) {
        this.conversationRepository = Objects.requireNonNull(conversationRepository);
        this.messageRepository = Objects.requireNonNull(messageRepository);
        this.feedbackRepository = Objects.requireNonNull(feedbackRepository);
        this.playbookService = Objects.requireNonNull(playbookService);
    }

    public Conversation createConversation(User user, String title, Map<String, Object> settings) {
        SanitizedSettings sanitized = sanitizeSettings(settings);
        Map<String, Object> toCreate = sanitized.otherSettings();
        if (sanitized.incomingVersion() != null) {
            toCreate.put(ConversationSetting.DESKTOP_VERSION.getValue(),
                sanitized.incomingVersion());
        }
        UUID id = UUID.randomUUID();
        String effectiveTitle = (title == null || title.isBlank()) ? DEFAULT_TITLE : title.trim();
        conversationRepository.create(id, user.id(), effectiveTitle,
            toCreate.isEmpty() ? Map.of() : toCreate, SOURCE_DESKTOP);
        log.info("Desktop conversation created: id={}, user={}", id, user.id());
        return conversationRepository.findById(id).orElseThrow();
    }

    public List<Conversation> listConversations(User user, int limit, int offset) {
        return conversationRepository.listByUserAndSource(user.id(), SOURCE_DESKTOP, limit, offset,
            AreaAccessContext.allowedUserAreasLower().orElse(null));
    }

    public List<Message> getMessages(UUID conversationId, User user, int limit, int offset) {
        verifyOwnership(conversationId, user);
        return messageRepository.findByConversationId(conversationId, limit, offset);
    }

    public Map<UUID, Feedback> getFeedbackByMessageId(UUID conversationId, User user) {
        verifyOwnership(conversationId, user);
        return feedbackRepository.findByConversationId(conversationId).stream()
            .collect(Collectors.toMap(Feedback::messageId, Function.identity()));
    }

    @Transactional
    public List<UUID> appendMessages(UUID conversationId, User user, List<NewMessage> newMessages) {
        Conversation conversation = verifyOwnership(conversationId, user);
        if (newMessages == null || newMessages.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "messages must not be empty");
        }

        List<UUID> ids = new ArrayList<>(newMessages.size());
        Map<String, Optional<Playbook>> playbooksByName = new HashMap<>();
        String effectiveArea = (conversation.area() != null && !conversation.area().isBlank())
            ? conversation.area().trim()
            : null;
        for (NewMessage m : newMessages) {
            if (m.role() == null || !(m.role().equals("user") || m.role().equals("assistant"))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "role must be 'user' or 'assistant'");
            }
            if (m.content() == null || m.content().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "content must not be blank");
            }
            if (m.playbook() != null && !m.playbook().isBlank()) {
                Playbook pb = playbooksByName
                    .computeIfAbsent(m.playbook().trim(), playbookService::getPlaybook)
                    .orElse(null);
                if (pb != null && pb.area() != null && !pb.area().isBlank()) {
                    if (!AreaAccessContext.canAccessUserArea(pb.area())) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Playbook not found: " + m.playbook());
                    }
                    String pbArea = pb.area().trim();
                    if (effectiveArea != null && !effectiveArea.equalsIgnoreCase(pbArea)) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Playbook area does not match conversation area");
                    }
                    if (effectiveArea == null) {
                        conversationRepository.updateAreaIfAbsent(conversationId, pb.area());
                        effectiveArea = pbArea;
                    }
                }
            }
            UUID id = UUID.randomUUID();
            final Message msgToSave;
            try {
                msgToSave = new Message(id, conversationId, m.role(), m.content(), m.playbook(),
                    m.playbookVersion(), null, null, null, m.promptTokens(), m.completionTokens(),
                    m.totalTokens(), m.cachedTokens(), m.thoughtTokens());
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
            }
            messageRepository.save(msgToSave);
            ids.add(id);
        }

        if (conversation.title() == null || DEFAULT_TITLE.equals(conversation.title())) {
            newMessages.stream().filter(m -> "user".equals(m.role())).findFirst().ifPresent(
                m -> conversationRepository.updateTitle(conversationId, autoTitle(m.content())));
        }
        conversationRepository.touch(conversationId);
        return ids;
    }

    public void updateTitle(UUID conversationId, User user, String title) {
        verifyOwnership(conversationId, user);
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title must not be blank");
        }
        conversationRepository.updateTitle(conversationId, title.trim());
    }

    private record SanitizedSettings(Map<String, Object> otherSettings,
        @Nullable String incomingVersion) {}

    @Transactional
    public void updateConversation(UUID conversationId, User user, String title,
        Map<String, Object> settings) {
        Conversation conversation = verifyOwnership(conversationId, user);
        SanitizedSettings sanitized = sanitizeSettings(settings);
        if (title != null && !title.isBlank()) {
            conversationRepository.updateTitle(conversationId, title.trim());
        }
        String versionKey = ConversationSetting.DESKTOP_VERSION.getValue();
        String existingVersion = Conversation.normalizeVersion(
            conversation.settings() != null ? conversation.settings().get(versionKey) : null);
        String effectiveVersion =
            existingVersion != null ? existingVersion : sanitized.incomingVersion();
        Map<String, Object> other = sanitized.otherSettings();
        if (!other.isEmpty()) {
            if (other.containsKey("thinkingLevel")) {
                other.put("thinking-level", other.remove("thinkingLevel"));
            }
            if (effectiveVersion != null) {
                other.put(versionKey, effectiveVersion);
            }
            conversationRepository.updateSettings(conversationId, other);
        } else if (existingVersion == null && sanitized.incomingVersion() != null) {
            Map<String, Object> merged =
                conversation.settings() != null ? new HashMap<>(conversation.settings())
                    : new HashMap<>();
            merged.put(versionKey, sanitized.incomingVersion());
            conversationRepository.updateSettings(conversationId, merged);
        }
    }

    private static SanitizedSettings sanitizeSettings(@Nullable Map<String, Object> settings) {
        if (settings == null || settings.isEmpty()) {
            return new SanitizedSettings(new HashMap<>(), null);
        }
        Map<String, Object> other = new HashMap<>(settings);
        String key = ConversationSetting.DESKTOP_VERSION.getValue();
        String incomingVersion = null;
        if (other.containsKey(key)) {
            Object raw = other.remove(key);
            if (raw != null) {
                if (!(raw instanceof String str)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Invalid desktopVersion: must be a string");
                }
                if (!str.isBlank()) {
                    String normalized = Conversation.normalizeVersion(str);
                    if (normalized == null) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Invalid desktopVersion: exceeds maximum length");
                    }
                    incomingVersion = normalized;
                }
            }
        }
        return new SanitizedSettings(other, incomingVersion);
    }

    public void deleteConversation(UUID conversationId, User user) {
        verifyOwnership(conversationId, user);
        conversationRepository.delete(conversationId);
        log.info("Desktop conversation deleted: id={}", conversationId);
    }

    public Feedback submitFeedback(UUID messageId, User user, Integer rating, String comment) {
        if (rating == null || (rating != 1 && rating != -1)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rating must be 1 or -1");
        }
        if (rating == -1 && (comment == null || comment.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "comment is required for negative feedback");
        }
        Message message = messageRepository.findById(messageId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Message not found: " + messageId));
        verifyOwnership(message.conversationId(), user);
        feedbackRepository.upsert(messageId, rating,
            comment == null || comment.isBlank() ? null : comment.trim());
        return feedbackRepository.findByMessageId(messageId).orElseThrow();
    }

    private Conversation verifyOwnership(UUID conversationId, User user) {
        Conversation conversation = conversationRepository.findById(conversationId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Conversation not found: " + conversationId));
        if (conversation.area() != null && !conversation.area().isBlank()
            && !AreaAccessContext.canAccessUserArea(conversation.area())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Conversation not found: " + conversationId);
        }
        if (!Objects.equals(conversation.userId(), user.id())) {
            throw new AccessDeniedException("Access denied to conversation: " + conversationId);
        }
        return conversation;
    }

    private static String autoTitle(String firstMessage) {
        String title = firstMessage.trim().replaceAll("\\s+", " ");
        if (title.length() > MAX_AUTO_TITLE_LENGTH) {
            title = title.substring(0, MAX_AUTO_TITLE_LENGTH - 1) + "…";
        }
        return title;
    }
}
