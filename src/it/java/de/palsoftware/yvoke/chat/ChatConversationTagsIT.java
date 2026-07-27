package de.palsoftware.yvoke.chat;

import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.service.ChatConversationService;
import de.palsoftware.yvoke.shared.user.model.User;
import de.palsoftware.yvoke.shared.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=filesystem:docker/db/migration"
})
public class ChatConversationTagsIT {

    @Autowired
    private ChatConversationService chatConversationService;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    public void setUp() {
        // Synchronize a test user in DB
        userRepository.upsert("test-oid-tags", "tags-test@yvoke.com", "Tags Test User");

        // Setup mock authentication context
        OidcUser oidcUser = mock(OidcUser.class);
        when(oidcUser.getClaimAsString("oid")).thenReturn("test-oid-tags");
        when(oidcUser.getClaimAsString("name")).thenReturn("Tags Test User");

        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getPrincipal()).thenReturn(oidcUser);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
    }

    @AfterEach
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void testConversationTaggingWorkflow() {
        // 1. Create a conversation
        Conversation conversation = chatConversationService.createConversation();
        assertThat(conversation).isNotNull();
        assertThat(conversation.tags()).isEmpty();

        UUID convId = conversation.id();

        // 2. Add some tags
        chatConversationService.addTag(convId, "AI");
        chatConversationService.addTag(convId, "Spring");
        // Add a duplicate tag to verify it does not append again
        chatConversationService.addTag(convId, "AI");
        // Add an empty tag to verify it is ignored
        chatConversationService.addTag(convId, "   ");

        // 3. Retrieve conversation and verify tags are present and unique
        Conversation updatedConv = chatConversationService.getConversation(convId).orElseThrow();
        assertThat(updatedConv.tags()).containsExactlyInAnyOrder("AI", "Spring");

        // Verify that findAllTags retrieves these tags
        List<String> allTags = chatConversationService.findAllTags();
        assertThat(allTags).contains("AI", "Spring");

        // 4. Remove a tag
        chatConversationService.removeTag(convId, "AI");

        // 5. Verify tag is removed
        Conversation postRemoveConv = chatConversationService.getConversation(convId).orElseThrow();
        assertThat(postRemoveConv.tags()).containsExactly("Spring");

        // Cleanup
        chatConversationService.deleteConversation(convId);
    }

    @Test
    public void testPublicSharingWorkflow() {
        // 1. User A creates a conversation, tags it "public" and "tutorial"
        Conversation conversation = chatConversationService.createConversation();
        assertThat(conversation).isNotNull();
        UUID convId = conversation.id();

        chatConversationService.addTag(convId, "public");
        chatConversationService.addTag(convId, "tutorial");

        // 2. Switch mock authentication context to User B
        userRepository.upsert("test-oid-userB", "userB@yvoke.com", "User B");
        OidcUser oidcUserB = mock(OidcUser.class);
        when(oidcUserB.getClaimAsString("oid")).thenReturn("test-oid-userB");
        when(oidcUserB.getClaimAsString("name")).thenReturn("User B");

        Authentication authB = mock(Authentication.class);
        when(authB.isAuthenticated()).thenReturn(true);
        when(authB.getPrincipal()).thenReturn(oidcUserB);

        SecurityContext contextB = SecurityContextHolder.createEmptyContext();
        contextB.setAuthentication(authB);
        SecurityContextHolder.setContext(contextB);

        // 3. User B lists conversations and sees User A's public conversation under the "Public Conversations" tag "tutorial"
        List<Conversation> bConversations = chatConversationService.listAllConversations(100, 0);
        Conversation retrievedByB = bConversations.stream()
            .filter(c -> c.id().equals(convId))
            .findFirst()
            .orElseThrow(() -> new AssertionError("User A's public conversation not visible to User B"));

        assertThat(retrievedByB.tags()).containsExactlyInAnyOrder("public", "tutorial");

        // 4. User B can read User A's public conversation
        Optional<Conversation> readByB = chatConversationService.getConversation(convId);
        assertThat(readByB).isPresent();
        assertThat(readByB.get().id()).isEqualTo(convId);

        // 5. User B cannot add a tag or delete User A's public conversation (throws AccessDeniedException)
        assertThrows(AccessDeniedException.class, () -> chatConversationService.addTag(convId, "hacker"));
        assertThrows(AccessDeniedException.class, () -> chatConversationService.deleteConversation(convId));

        // Switch back to User A for cleanup
        setUp();
        chatConversationService.deleteConversation(convId);
    }
}
