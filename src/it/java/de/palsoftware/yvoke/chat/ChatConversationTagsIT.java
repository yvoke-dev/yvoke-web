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
import java.util.Objects;
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

    /**
     * The chat folder vocabulary is its own namespace, derived from the conversations THIS caller
     * can already see — never from a shared registry. That registry existed until V6 and was wrong
     * in two directions at once: it offered corpus version tags ({@code 9.3.1}, {@code 10.0}) as
     * folder names, and it handed every user every other user's folder names, which are free text
     * an operator types about customers and projects.
     *
     * <p>
     * Nothing pins the derivation today. {@code TagServiceTest} only forbids {@code findAll*} on
     * {@code TagService}/{@code TagRepository}, so re-pointing {@code findAllTags} at any other
     * source — a wider conversation query, or {@code CollectionRepository.findAllTagNames()} —
     * passes the entire suite, and the existing assertion here is a bare
     * {@code contains("AI", "Spring")} by a single user, which every wrong source also satisfies.
     * The failure is silent by construction: an autocomplete that offers too many names looks like
     * a longer list, not like a leak.
     *
     * <p>
     * So the vocabulary is asserted to be EXACTLY the tag set of the conversations the caller can
     * list — no more (user A's private folder name must not reach user B) and no less (B's own
     * folder must be offered to B).
     */
    @Test
    public void theFolderVocabularyIsExactlyTheFoldersOfTheConversationsThisUserCanSee() {
        // User A — authenticated by setUp — files a conversation under a customer-named folder.
        Conversation ownedByA = chatConversationService.createConversation();
        chatConversationService.addTag(ownedByA.id(), "Kunde-Mustermann-Angebot");

        userRepository.upsert("test-oid-folder-b", "folderB@yvoke.com", "Folder B");
        OidcUser oidcUserB = mock(OidcUser.class);
        when(oidcUserB.getClaimAsString("oid")).thenReturn("test-oid-folder-b");
        when(oidcUserB.getClaimAsString("name")).thenReturn("Folder B");
        Authentication authB = mock(Authentication.class);
        when(authB.isAuthenticated()).thenReturn(true);
        when(authB.getPrincipal()).thenReturn(oidcUserB);
        SecurityContext contextB = SecurityContextHolder.createEmptyContext();
        contextB.setAuthentication(authB);
        SecurityContextHolder.setContext(contextB);

        Conversation ownedByB = chatConversationService.createConversation();
        chatConversationService.addTag(ownedByB.id(), "B-Eigener-Ordner");

        List<String> foldersOfferedToB = chatConversationService.findAllTags();
        List<String> tagsOnConversationsBCanSee =
            chatConversationService.listAllConversations(100, 0).stream().map(Conversation::tags)
                .filter(Objects::nonNull).flatMap(List::stream).distinct().toList();

        assertThat(foldersOfferedToB).as("B's own folder has to be offered to B")
            .contains("B-Eigener-Ordner");
        assertThat(foldersOfferedToB)
            .as("a folder name is free text about a customer - not another user's business")
            .doesNotContain("Kunde-Mustermann-Angebot");
        assertThat(foldersOfferedToB)
            .as("the vocabulary is derived from the visible conversations and from nothing else")
            .containsExactlyInAnyOrderElementsOf(tagsOnConversationsBCanSee);

        chatConversationService.deleteConversation(ownedByB.id());
        setUp();
        chatConversationService.deleteConversation(ownedByA.id());
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
