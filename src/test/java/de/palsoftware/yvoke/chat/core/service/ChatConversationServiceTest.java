package de.palsoftware.yvoke.chat.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.palsoftware.yvoke.chat.core.ChatProperties;
import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.model.ConversationSidebar;
import de.palsoftware.yvoke.chat.core.repository.ConversationRepository;
import de.palsoftware.yvoke.shared.user.model.User;
import de.palsoftware.yvoke.shared.user.service.UserService;
import de.palsoftware.yvoke.tag.core.repository.TagRepository;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

public class ChatConversationServiceTest {
    private ConversationRepository conversationRepository;
    private UserService userService;
    private TagRepository tagRepository;
    private ChatConversationService chatConversationService;

    @BeforeEach
    public void setUp() {
        conversationRepository = mock(ConversationRepository.class);
        userService = mock(UserService.class);
        tagRepository = mock(TagRepository.class);

        when(userService.getCurrentUser()).thenReturn(Optional.empty());

        ChatProperties chatProperties =
            new ChatProperties(true, List.of("gemini-3.1-flash-lite"), true);

        chatConversationService = new ChatConversationService(conversationRepository, userService,
            chatProperties, tagRepository);
    }

    @Test
    public void testCreateConversation() {
        Conversation mockConv = new Conversation(UUID.randomUUID(), null, "Title",
            Collections.emptyMap(), null, null, Collections.emptyList());
        when(conversationRepository.findById(any())).thenReturn(Optional.of(mockConv));

        Conversation result = chatConversationService.createConversation();

        assertThat(result).isNotNull();
        verify(conversationRepository).create(any(UUID.class), eq(null), eq("New Conversation"),
            anyMap());
    }

    @Test
    public void testGetConversationThrowsAccessDeniedForDifferentUser() {
        UUID conversationId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        UUID currentUserId = UUID.randomUUID();

        User currentUser = new User(currentUserId, "entra-id-123", "me@test.local", "Me", null);
        when(userService.getCurrentUser()).thenReturn(Optional.of(currentUser));

        Conversation otherConv = new Conversation(conversationId, otherUserId, "Secret Chat",
            Collections.emptyMap(), null, null, Collections.emptyList());
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(otherConv));

        Assertions.assertThrows(AccessDeniedException.class, () -> {
            chatConversationService.getConversation(conversationId);
        });
    }

    @Test
    public void testDeleteConversationThrowsAccessDeniedForDifferentUser() {
        UUID conversationId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        UUID currentUserId = UUID.randomUUID();

        User currentUser = new User(currentUserId, "entra-id-123", "me@test.local", "Me", null);
        when(userService.getCurrentUser()).thenReturn(Optional.of(currentUser));

        Conversation otherConv = new Conversation(conversationId, otherUserId, "Secret Chat",
            Collections.emptyMap(), null, null, Collections.emptyList());
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(otherConv));

        Assertions.assertThrows(AccessDeniedException.class, () -> {
            chatConversationService.deleteConversation(conversationId);
        });
        verify(conversationRepository, never()).delete(any());
    }

    @Test
    public void testUpdateModelRejectsModelOutsideAllowList() {
        UUID conversationId = UUID.randomUUID();
        // Owned conversation so the ownership check passes and we reach the whitelist validation.
        Conversation conv = new Conversation(conversationId, null, "Title", Collections.emptyMap(),
            null, null, Collections.emptyList());
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conv));

        // allowedModels is ["gemini-3.1-flash-lite"]; a model outside the whitelist must be refused
        // with a 400 and MUST NOT be persisted (SEC-04).
        ResponseStatusException ex =
            Assertions.assertThrows(ResponseStatusException.class, () -> chatConversationService
                .updateModel(conversationId, "attacker-picked-expensive-model"));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(conversationRepository, never()).updateSettings(any(), any());
    }

    @Test
    public void testUpdateModelDeniedForNonOwnerBeforeValidation() {
        UUID conversationId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        UUID currentUserId = UUID.randomUUID();
        User currentUser = new User(currentUserId, "entra-id-123", "me@test.local", "Me", null);
        when(userService.getCurrentUser()).thenReturn(Optional.of(currentUser));
        Conversation otherConv = new Conversation(conversationId, otherUserId, "Secret",
            Collections.emptyMap(), null, null, Collections.emptyList());
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(otherConv));

        // A non-owner is denied (403) even when the requested model is itself invalid —
        // authorization
        // precedes payload validation.
        Assertions.assertThrows(AccessDeniedException.class,
            () -> chatConversationService.updateModel(conversationId, "not-an-allowed-model"));
        verify(conversationRepository, never()).updateSettings(any(), any());
    }

    @Test
    public void testUpdateModelAcceptsWhitelistedModel() {
        UUID conversationId = UUID.randomUUID();
        Conversation conv = new Conversation(conversationId, null, "Title", Collections.emptyMap(),
            null, null, Collections.emptyList());
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conv));

        chatConversationService.updateModel(conversationId, "gemini-3.1-flash-lite");

        verify(conversationRepository).updateSettings(eq(conversationId),
            argThat((Map<String, Object> m) -> "gemini-3.1-flash-lite".equals(m.get("model"))));
    }

    private Conversation conv(UUID userId, List<String> tags) {
        return new Conversation(UUID.randomUUID(), userId, "Title", Collections.emptyMap(), null,
            null, tags);
    }

    @Test
    public void testBuildSidebarGroupsVisibilityFoldersAndPublicCount() {
        UUID currentUserId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        User currentUser = new User(currentUserId, "entra-id-123", "me@test.local", "Me", null);
        when(userService.getCurrentUser()).thenReturn(Optional.of(currentUser));

        // c1 owned+untagged -> untagged; c2 owned+["Beta","alpha"] -> both folders (multi-add);
        // c3 owned+["public","shared"] -> publicFolders[shared] ("public" stripped);
        // c4 owned+["public"] only -> publicUntagged; c5 other-user+untagged -> publicUntagged;
        // c6 other-user+["x","y"] -> publicFolders[x] and publicFolders[y] (counted twice).
        Conversation c1 = conv(currentUserId, null);
        Conversation c2 = conv(currentUserId, List.of("Beta", "alpha"));
        Conversation c3 = conv(currentUserId, List.of("public", "shared"));
        Conversation c4 = conv(currentUserId, List.of("public"));
        Conversation c5 = conv(otherUserId, null);
        Conversation c6 = conv(otherUserId, List.of("x", "y"));
        List<Conversation> all = List.of(c1, c2, c3, c4, c5, c6);
        when(conversationRepository.listAll(currentUserId, 100, 0)).thenReturn(all);

        ConversationSidebar sidebar = chatConversationService.buildSidebar();

        assertThat(sidebar.currentUserId()).isEqualTo(currentUserId);
        assertThat(sidebar.conversations()).isEqualTo(all);
        // Folder names are the CONVERSATIONS' own tags, derived from the list already loaded above
        // — not a global registry, which used to mix corpus version tags into this datalist and
        // exposed folder names from conversations the caller cannot see.
        assertThat(sidebar.allTags()).containsExactly("alpha", "Beta", "public", "shared", "x",
            "y");
        verifyNoInteractions(tagRepository);

        // Private/owned buckets.
        assertThat(sidebar.untagged()).containsExactly(c1);
        assertThat(sidebar.folders().keySet()).containsExactly("alpha", "Beta"); // case-insensitive
        assertThat(sidebar.folders().get("alpha")).containsExactly(c2);
        assertThat(sidebar.folders().get("Beta")).containsExactly(c2);

        // Public buckets: "public" tag is stripped; not-owned convs are public.
        assertThat(sidebar.publicUntagged()).containsExactlyInAnyOrder(c4, c5);
        assertThat(sidebar.publicFolders().get("shared")).containsExactly(c3);
        assertThat(sidebar.publicFolders().get("x")).containsExactly(c6);
        assertThat(sidebar.publicFolders().get("y")).containsExactly(c6);

        // publicCount counts ENTRIES not distinct conversations: publicUntagged(2) + shared(1)
        // + x(1) + y(1) = 5 (c6 counted twice).
        assertThat(sidebar.publicCount()).isEqualTo(5);
    }

    @Test
    public void testChatDisabledThrowsException() {
        ChatProperties disabledProperties =
            new ChatProperties(false, List.of("gemini-3.1-flash-lite"), true);
        ChatConversationService disabledService = new ChatConversationService(
            conversationRepository, userService, disabledProperties, tagRepository);

        Assertions.assertThrows(IllegalStateException.class, () -> {
            disabledService.createConversation();
        });
    }
}

