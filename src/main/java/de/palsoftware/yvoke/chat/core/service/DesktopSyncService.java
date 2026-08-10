package de.palsoftware.yvoke.chat.core.service;

import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.model.Feedback;
import de.palsoftware.yvoke.chat.core.model.Message;
import de.palsoftware.yvoke.chat.core.repository.ConversationRepository;
import de.palsoftware.yvoke.chat.core.repository.FeedbackRepository;
import de.palsoftware.yvoke.chat.core.repository.MessageRepository;


import de.palsoftware.yvoke.shared.user.model.User;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.HashMap;

@Service
public class DesktopSyncService {
    private static final Logger log = LoggerFactory.getLogger(DesktopSyncService.class);

    static final String SOURCE_DESKTOP = "desktop";
    static final String DEFAULT_TITLE = "New Conversation";
    private static final int MAX_AUTO_TITLE_LENGTH = 80;

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final FeedbackRepository feedbackRepository;

    public record NewMessage(String role, String content, Integer promptTokens,
        Integer completionTokens, Integer totalTokens, Integer cachedTokens,
        Integer thoughtTokens) {}

    public DesktopSyncService(ConversationRepository conversationRepository,
        MessageRepository messageRepository, FeedbackRepository feedbackRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.feedbackRepository = feedbackRepository;
    }

    public Conversation createConversation(User user, String title, Map<String, Object> settings) {
        UUID id = UUID.randomUUID();
        String effectiveTitle = (title == null || title.isBlank()) ? DEFAULT_TITLE : title.trim();
        conversationRepository.create(id, user.id(), effectiveTitle,
            settings == null ? Map.of() : settings, SOURCE_DESKTOP);
        log.info("Desktop conversation created: id={}, user={}", id, user.id());
        return conversationRepository.findById(id).orElseThrow();
    }

    public List<Conversation> listConversations(User user, int limit, int offset) {
        return conversationRepository.listByUserAndSource(user.id(), SOURCE_DESKTOP, limit, offset);
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
        for (NewMessage m : newMessages) {
            if (m.role() == null || !(m.role().equals("user") || m.role().equals("assistant"))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "role must be 'user' or 'assistant'");
            }
            if (m.content() == null || m.content().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "content must not be blank");
            }
            UUID id = UUID.randomUUID();
            messageRepository.save(new Message(id, conversationId, m.role(), m.content(), null,
                null, null, m.promptTokens(), m.completionTokens(), m.totalTokens(),
                m.cachedTokens(), m.thoughtTokens()));
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

    @Transactional
    public void updateConversation(UUID conversationId, User user, String title,
        Map<String, Object> settings) {
        verifyOwnership(conversationId, user);
        if (title != null && !title.isBlank()) {
            conversationRepository.updateTitle(conversationId, title.trim());
        }
        if (settings != null && !settings.isEmpty()) {
            Map<String, Object> mapped = new HashMap<>(settings);
            if (mapped.containsKey("thinkingLevel")) {
                mapped.put("thinking-level", mapped.remove("thinkingLevel"));
            }
            if (mapped.containsKey("chatPrompt")) {
                mapped.put("chat-prompt", mapped.remove("chatPrompt"));
            }
            conversationRepository.updateSettings(conversationId, mapped);
        }
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
