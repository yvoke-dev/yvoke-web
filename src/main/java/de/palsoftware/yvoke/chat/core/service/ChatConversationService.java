package de.palsoftware.yvoke.chat.core.service;

import de.palsoftware.yvoke.chat.core.ChatProperties;
import jakarta.annotation.Nullable;
import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.model.ConversationSetting;
import de.palsoftware.yvoke.chat.core.model.ConversationSidebar;
import de.palsoftware.yvoke.chat.core.repository.ConversationRepository;
import de.palsoftware.yvoke.shared.user.model.User;
import de.palsoftware.yvoke.shared.user.service.UserService;
import de.palsoftware.yvoke.tag.core.repository.TagRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatConversationService {

    private static final Logger log = LoggerFactory.getLogger(ChatConversationService.class);

    /** How many conversations the sidebar loads — and therefore how far folder names are seen. */
    private static final int SIDEBAR_LIMIT = 100;

    private final ConversationRepository conversationRepository;
    private final UserService userService;
    private final ChatProperties chatProperties;
    private final TagRepository tagRepository;

    public ChatConversationService(ConversationRepository conversationRepository,
        UserService userService, ChatProperties chatProperties, TagRepository tagRepository) {
        this.conversationRepository = conversationRepository;
        this.userService = userService;
        this.chatProperties = chatProperties;
        this.tagRepository = tagRepository;
    }

    public List<String> getAllowedModels() {
        return chatProperties.allowedModels() != null ? chatProperties.allowedModels()
            : Collections.emptyList();
    }

    /**
     * The model a conversation actually runs on, given the whitelist in force right now.
     *
     * <p>
     * A conversation's stored model outlives the whitelist: retiring one from
     * {@code app.chat.allowed-models} leaves every conversation pinned to it still naming it. The
     * two halves of the app then disagreed about what that meant — the picker rendered no matching
     * option so the browser selected the FIRST, while the send path read the stored value straight
     * out of the settings map, so the UI said one model and the answer came from another with
     * nothing anywhere reporting the divergence. The obvious repair did not work either: the picker
     * already displayed the default, so choosing it fired no change event and posted nothing, and
     * the conversation could not be moved off the retired model at all.
     *
     * <p>
     * A retired model therefore resolves to the default — element 0, the same value
     * {@link #createConversation()} stamps on a new conversation. Nothing is written back: a page
     * render must not mutate a conversation, and the stale stored value is inert from here on,
     * overwritten by {@link #updateModel} the first time the user picks anything.
     *
     * <p>
     * An EMPTY or absent whitelist passes the stored value through untouched — an empty list is a
     * missing opinion, not a statement that every model is forbidden, and substituting a "default"
     * from it would mean inventing a model out of nothing. A missing stored model is likewise
     * returned unchanged: callers already handle that case, and differently (the send path throws,
     * the preflight substitutes the default), so folding them together here would change two
     * behaviours while fixing a third.
     *
     * <p>
     * Static so the send path can resolve with the REAL rule while taking only the whitelist from
     * its mocked {@code ChatConversationService} — a lenient stub of an instance method would let
     * the test agree with whatever production did.
     */
    public static @Nullable String effectiveModel(@Nullable String storedModel,
        @Nullable List<String> allowedModels) {
        if (allowedModels == null || allowedModels.isEmpty() || storedModel == null
            || storedModel.isBlank() || allowedModels.contains(storedModel)) {
            return storedModel;
        }
        log.warn("Conversation model '{}' is no longer in app.chat.allowed-models; using '{}'",
            storedModel, allowedModels.get(0));
        return allowedModels.get(0);
    }

    /** {@link #effectiveModel(String, List)} against the configured whitelist. */
    public @Nullable String effectiveModel(@Nullable String storedModel) {
        return effectiveModel(storedModel, getAllowedModels());
    }

    public boolean isPlaybookValidationEnabled() {
        return chatProperties.playbookValidationEnabled();
    }

    public Conversation createConversation() {
        checkChatEnabled();
        UUID id = UUID.randomUUID();
        Map<String, Object> settings = new HashMap<>();

        List<String> allowedModels = chatProperties.allowedModels();
        String defaultModel =
            (allowedModels != null && !allowedModels.isEmpty()) ? allowedModels.get(0) : null;
        if (defaultModel == null) {
            throw new IllegalStateException(
                "No allowed models configured under 'app.chat.allowed-models'");
        }
        settings.put(ConversationSetting.MODEL.getValue(), defaultModel);
        settings.put(ConversationSetting.STREAMING.getValue(), false);
        settings.put(ConversationSetting.SHOW_THINKING.getValue(), false);
        settings.put(ConversationSetting.THINKING_LEVEL.getValue(), "medium");
        settings.put(ConversationSetting.SHOW_PROTOTYPES.getValue(), false);

        String title = "New Conversation";
        UUID userId = userService.getCurrentUser().map(User::id).orElse(null);
        conversationRepository.create(id, userId, title, settings);
        return conversationRepository.findById(id).orElseThrow();
    }

    public List<Conversation> listAllConversations(int limit, int offset) {
        checkChatEnabled();
        UUID userId = userService.getCurrentUser().map(User::id).orElse(null);
        return conversationRepository.listAll(userId, limit, offset);
    }

    public Optional<Conversation> getConversation(UUID id) {
        checkChatEnabled();
        Optional<Conversation> conversation = conversationRepository.findById(id);
        conversation.ifPresent(c -> verifyConversationOwnership(c, true));
        return conversation;
    }

    public void updateSettings(UUID id, Map<String, Object> settings) {
        checkChatEnabled();
        Conversation conversation = conversationRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + id));
        verifyConversationOwnership(conversation, false);
        Map<String, Object> merged = new HashMap<>(conversation.settings());
        merged.putAll(settings);
        conversationRepository.updateSettings(id, merged);
    }

    /**
     * Sets the conversation's model, rejecting any value not present in the configured
     * {@code app.chat.allowed-models} whitelist (SEC-04). Validating here — the single user-facing
     * set path — prevents a client from steering the conversation onto an arbitrary/expensive model
     * by posting a crafted {@code model} parameter.
     */
    public void updateModel(UUID id, String model) {
        // Authorize BEFORE validating the payload: a caller who does not own the conversation must
        // be rejected (403) regardless of whether the requested model is valid — otherwise the
        // whitelist check (400) would mask the authorization failure and leak validation behaviour.
        verifyOwnership(id, false);
        String requested = model == null ? "" : model.trim();
        if (!getAllowedModels().contains(requested)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Model is not in the allowed list: " + requested);
        }
        Map<String, Object> settings = new HashMap<>();
        settings.put(ConversationSetting.MODEL.getValue(), requested);
        updateSettings(id, settings);
    }

    public void deleteConversation(UUID id) {
        checkChatEnabled();
        verifyOwnership(id, false);
        conversationRepository.delete(id);
    }

    public void autoTitle(UUID conversationId, String firstMessage) {
        String title = firstMessage.trim();
        if (title.length() > 80) {
            title = title.substring(0, 77) + "...";
        }
        conversationRepository.updateTitle(conversationId, title);
    }

    @Transactional
    public void addTag(UUID conversationId, String tag) {
        verifyOwnership(conversationId, false);
        tagRepository.addTagToConversation(conversationId, tag);
        conversationRepository.touch(conversationId);
    }

    @Transactional
    public void removeTag(UUID conversationId, String tag) {
        verifyOwnership(conversationId, false);
        tagRepository.removeTagFromConversation(conversationId, tag);
        conversationRepository.touch(conversationId);
    }

    /**
     * The chat folder names — the tags on the conversations this caller can see.
     *
     * <p>
     * Its own namespace, deliberately. Until V6 this read a global {@code tags} registry shared
     * with the corpus tags, so the folder autocomplete offered corpus version tags ({@code 9.3.1},
     * {@code 10.0}) as folder names and exposed every other user's folder names alongside them.
     * Deriving from {@code listAllConversations} narrows it to the visible set for free.
     */
    public List<String> findAllTags() {
        return distinctTags(listAllConversations(SIDEBAR_LIMIT, 0));
    }

    private static List<String> distinctTags(List<Conversation> conversations) {
        return conversations.stream().map(Conversation::tags).filter(Objects::nonNull)
            .flatMap(List::stream).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    /**
     * Builds the chat sidebar view model: the conversation list grouped into private/public folders
     * and untagged buckets (MNT-05). A conversation is treated as public when it carries the
     * literal {@code "public"} tag or is not owned by the current user; a private/owned
     * conversation appears under each of its tags, while a public one appears under each of its
     * non-{@code "public"} tags. {@code publicCount} counts folder entries, so a multi-tagged
     * public conversation is counted once per tag (preserved intentionally). Shared by the chat
     * index and the thread view.
     */
    public ConversationSidebar buildSidebar() {
        List<Conversation> conversations = listAllConversations(SIDEBAR_LIMIT, 0);
        UUID currentUserId = userService.getCurrentUser().map(User::id).orElse(null);

        TreeMap<String, List<Conversation>> folders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<Conversation> untagged = new ArrayList<>();
        TreeMap<String, List<Conversation>> publicFolders =
            new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<Conversation> publicUntagged = new ArrayList<>();
        // Derived from the list already loaded above — no second query.
        List<String> allTags = distinctTags(conversations);

        for (Conversation conv : conversations) {
            boolean isPublic = (conv.tags() != null && conv.tags().contains("public"))
                || !Objects.equals(conv.userId(), currentUserId);

            if (isPublic) {
                if (conv.tags() == null || conv.tags().isEmpty()) {
                    publicUntagged.add(conv);
                } else {
                    List<String> otherTags =
                        conv.tags().stream().filter(t -> !"public".equals(t)).toList();
                    if (otherTags.isEmpty()) {
                        publicUntagged.add(conv);
                    } else {
                        for (String tag : otherTags) {
                            publicFolders.computeIfAbsent(tag, k -> new ArrayList<>()).add(conv);
                        }
                    }
                }
            } else {
                if (conv.tags() == null || conv.tags().isEmpty()) {
                    untagged.add(conv);
                } else {
                    for (String tag : conv.tags()) {
                        folders.computeIfAbsent(tag, k -> new ArrayList<>()).add(conv);
                    }
                }
            }
        }

        int publicCount = publicUntagged.size();
        for (List<Conversation> list : publicFolders.values()) {
            publicCount += list.size();
        }

        return new ConversationSidebar(conversations, folders, untagged, publicFolders,
            publicUntagged, publicCount, allTags, currentUserId);
    }

    public void verifyConversationOwnership(Conversation conversation, boolean readOnlyAllowed) {
        UUID currentUserId = userService.getCurrentUser().map(User::id).orElse(null);
        if (Objects.equals(conversation.userId(), currentUserId)) {
            return;
        }
        if (readOnlyAllowed) {
            if (conversation.tags() != null && conversation.tags().contains("public")) {
                return;
            }
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            boolean isAdmin = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
            if (isAdmin) {
                return;
            }
        }
        throw new AccessDeniedException("Access denied to conversation: " + conversation.id());
    }

    public Conversation verifyOwnership(UUID conversationId, boolean readOnlyAllowed) {
        Conversation conversation = conversationRepository.findById(conversationId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Conversation not found: " + conversationId));
        verifyConversationOwnership(conversation, readOnlyAllowed);
        return conversation;
    }

    public void checkChatEnabled() {
        if (!chatProperties.enabled()) {
            throw new IllegalStateException("Webchat is disabled.");
        }
    }
}

