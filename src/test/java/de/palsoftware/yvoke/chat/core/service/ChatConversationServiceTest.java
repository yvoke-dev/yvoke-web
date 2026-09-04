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
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import org.mockito.ArgumentMatchers;

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

    /**
     * What is seeded here IS the contract for a brand-new conversation, and the two halves of it
     * fail in opposite directions.
     *
     * <p>
     * A missing key is fatal on the next send: {@code ChatMessageService.resolveModelToUse} throws
     * {@code IllegalStateException} on a blank model, so dropping the model seed makes every fresh
     * conversation die on its first question with a generic system error the user cannot act on.
     * The other three are read by the UI, and {@code thinking-level} additionally reaches the
     * provider — seed it with anything other than "medium" and every new conversation silently
     * starts on a different thinking budget, changing both answer quality and cost for every user,
     * with nothing on screen to show it happened.
     *
     * <p>
     * An EXTRA key is just as damaging, which is why this asserts on the whole map rather than
     * checking four entries. {@code orchestrator-profile} and {@code chat-prompt} are deliberately
     * absent: {@code prepareAndSubmitAsync} routes to the multi-agent orchestrator whenever
     * {@code orchestrator-profile} is present and non-blank, so seeding it — even with a value that
     * merely looks like a sensible default — silently puts every new conversation into MAS mode,
     * multiplying cost per turn and bypassing the playbook path entirely. Seeding
     * {@code chat-prompt} similarly overrides the system prompt for everyone.
     *
     * <p>
     * {@code testCreateConversation} asserts only that {@code create} was called with
     * {@code anyMap()}, so it passes against any seed at all, correct or not.
     */
    @Test
    public void aNewConversationIsSeededWithExactlyTheDefaultSettings() {
        Conversation created = new Conversation(UUID.randomUUID(), null, "New Conversation",
            Collections.emptyMap(), null, null, Collections.emptyList());
        when(conversationRepository.findById(any())).thenReturn(Optional.of(created));

        chatConversationService.createConversation();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> settingsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(conversationRepository).create(any(UUID.class), eq(null), eq("New Conversation"),
            settingsCaptor.capture());

        // The model is the FIRST entry of app.chat.allowed-models (here the one configured in
        // setUp) — the whitelist's head is the default, so reordering the list changes it.
        assertThat(settingsCaptor.getValue()).containsOnly(entry("model", "gemini-3.1-flash-lite"),
            entry("streaming", false), entry("show-thinking", false),
            entry("show-prototypes", false), entry("thinking-level", "medium"));
    }

    /**
     * With no allowed models configured, creating a conversation must FAIL rather than seed a blank
     * one — and it must fail before the row is written.
     *
     * <p>
     * {@code app.chat.allowed-models} carries {@code @NotEmpty}, but that constraint only binds
     * configuration properties: it is bypassed by any programmatically-constructed
     * {@code ChatProperties} and, more to the point, by a deployment that supplies the key with an
     * empty value. {@code ChatPropertiesTest} pins the annotation and nothing pins this branch, so
     * the guard is unexecuted today.
     *
     * <p>
     * The regression that matters is not deleting the {@code throw} — it is softening the ternary
     * above it to a harmless-looking default ({@code : ""}), which is what someone reaches for when
     * a fresh environment refuses to open a chat. The conversation is then created and stored with
     * a blank {@code model} setting, the page renders normally with an empty model picker, and the
     * failure surfaces one step later and somewhere else: {@code resolveModelToUse} throws on the
     * first question and the user gets the generic system error, on a conversation that already
     * exists and whose title is about to be taken from the message that failed.
     *
     * <p>
     * The {@code never()} on the repository is the half that catches that: an assertion on the
     * exception alone would still pass if the row were written first and the throw came after.
     */
    @Test
    public void anEmptyAllowedModelListRefusesToCreateAConversationAtAll() {
        ChatProperties noModels = new ChatProperties(true, List.of(), true);
        ChatConversationService withoutModels = new ChatConversationService(conversationRepository,
            userService, noModels, tagRepository);

        assertThatThrownBy(withoutModels::createConversation)
            .as("a blank default model must fail here, not on the conversation's first question")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.chat.allowed-models");

        verify(conversationRepository, never()).create(any(), any(), any(), any());
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

    /**
     * All six settings live in ONE jsonb column and every settings endpoint funnels through this
     * merge, so a regression to a plain replace makes each write silently delete the others:
     * posting {@code /thinking-level} would drop {@code model}, and {@code resolveModelToUse} then
     * throws on the blank model so the NEXT generation dies with a generic error the user cannot
     * trace; posting {@code /streaming} would drop {@code orchestrator-profile}, quietly demoting
     * an orchestrated conversation to a plain run. The loss is invisible until the next send — spec
     * § 2 lists "one setting write erasing the others" under MUST NOT happen. Asserting on the FULL
     * captured map is the point: a containsEntry check on the incoming key alone passes against a
     * replace.
     */
    @Test
    public void updateSettingsMergesIntoTheStoredMapInsteadOfReplacingIt() {
        UUID id = UUID.randomUUID();
        Conversation existing = new Conversation(id, null, "Title", Map.of("model",
            "gemini-3.1-flash-lite", "thinking-level", "high", "orchestrator-profile", "oim"), null,
            null, Collections.emptyList());
        when(conversationRepository.findById(id)).thenReturn(Optional.of(existing));

        chatConversationService.updateSettings(id, Map.of("streaming", true));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(conversationRepository).updateSettings(ArgumentMatchers.eq(id), captor.capture());
        assertThat(captor.getValue()).containsOnly(entry("model", "gemini-3.1-flash-lite"),
            entry("thinking-level", "high"), entry("orchestrator-profile", "oim"),
            entry("streaming", true));
    }

    /** On a key collision the incoming value wins, and the untouched keys still survive. */
    @Test
    public void updateSettingsLetsTheIncomingValueWinWithoutDroppingTheRest() {
        UUID id = UUID.randomUUID();
        Conversation existing = new Conversation(id, null, "Title",
            Map.of("model", "gemini-3.1-flash-lite", "thinking-level", "high"), null, null,
            Collections.emptyList());
        when(conversationRepository.findById(id)).thenReturn(Optional.of(existing));

        chatConversationService.updateSettings(id, Map.of("thinking-level", "low"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(conversationRepository).updateSettings(ArgumentMatchers.eq(id), captor.capture());
        assertThat(captor.getValue()).containsOnly(entry("model", "gemini-3.1-flash-lite"),
            entry("thinking-level", "low"));
    }

    /**
     * The auto-title is the only thing that ever names a conversation, and it is derived from text
     * the user typed — i.e. unbounded input written into a column the sidebar renders on every
     * page. The 80-character cap is what keeps that survivable: paste a 4,000-word requirements
     * document as the first question (routine on this corpus) and without the truncation the whole
     * document becomes the title, is stored in {@code conversations.title}, and is re-rendered into
     * every sidebar row and every folder listing — the chat index turns into a wall of text, and
     * the layout breaks for every conversation the user can see, not just that one.
     *
     * <p>
     * The exact split matters as much as the cap: 77 characters plus a three-character ellipsis is
     * what makes the result exactly 80 wide, which is what the sidebar CSS is sized for. Cutting at
     * 80 and appending "..." would yield 83 and quietly overflow; cutting at 77 without the
     * ellipsis would present a truncated sentence as if it were the whole title.
     *
     * <p>
     * Both boundaries are pinned here because the branch is {@code > 80}, not {@code >= 80}: a
     * message of exactly 80 characters must pass through untouched, so an off-by-one that rewrites
     * it as 77 + "..." — losing three characters of a title that already fitted — fails on the
     * second assertion. The leading/trailing whitespace on the long input pins the {@code trim()}
     * that runs first, which is what stops a title from beginning with the blank lines an editor
     * paste brings along.
     */
    @Test
    public void aLongFirstMessageIsTruncatedToSeventySevenCharactersPlusEllipsis() {
        UUID conversationId = UUID.randomUUID();
        String longQuestion = "x".repeat(200);

        // Whitespace is stripped first, so it is the 200 real characters that get cut.
        chatConversationService.autoTitle(conversationId, "  " + longQuestion + "  ");
        // Exactly at the boundary: must be stored verbatim, not truncated.
        String exactly80 = "y".repeat(80);
        chatConversationService.autoTitle(conversationId, exactly80);

        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        verify(conversationRepository, times(2)).updateTitle(eq(conversationId),
            titleCaptor.capture());
        List<String> titles = titleCaptor.getAllValues();
        assertThat(titles.get(0)).hasSize(80).endsWith("...").isEqualTo("x".repeat(77) + "...");
        assertThat(titles.get(1)).isEqualTo(exactly80);
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

    /**
     * The {@code public} tag grants READS to non-owners, never writes.
     *
     * <p>
     * Sharing is one tag away, has no confirmation step and no per-person control, so the read
     * carve-out is deliberately wide — which is exactly why the write side has to be narrow. Every
     * mutating path calls {@code verifyOwnership(id, false)}; passing {@code true} anywhere would
     * let any signed-in colleague retag, re-model, stop or delete a conversation they can merely
     * see, and the owner would have no way to tell it had happened. The same carve-out serves
     * ROLE_ADMIN, so the blast radius is every shared conversation in the system.
     */
    @Test
    public void aPublicViewerCannotChangeTheSettingsOfAConversationTheyOnlyRead() {
        UUID conversationId = UUID.randomUUID();
        User viewer = new User(UUID.randomUUID(), "entra-viewer", "viewer@test.local", "V", null);
        when(userService.getCurrentUser()).thenReturn(Optional.of(viewer));

        // Owned by someone else, and shared — so this viewer may READ it.
        Conversation shared = new Conversation(conversationId, UUID.randomUUID(), "Shared Chat",
            Collections.emptyMap(), null, null, List.of("public"));
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(shared));

        Assertions.assertThrows(AccessDeniedException.class,
            () -> chatConversationService.updateSettings(conversationId, Map.of("k", "v")));
        verify(conversationRepository, never()).updateSettings(any(), any());
    }

    /**
     * A conversation's stored model outlives the whitelist. Retiring a model from
     * {@code app.chat.allowed-models} leaves every conversation pinned to it still naming it, and
     * the two halves of the app then disagreed about what that meant: the picker rendered no
     * matching option so the browser selected the FIRST one, while the send path read the stored
     * value straight out of the settings map. The UI said one model and the answer came from
     * another, with nothing anywhere reporting it — and the obvious repair does not work either,
     * because the picker already displays the default, so choosing it fires no change event and
     * posts nothing. One resolver, three callers, no hand-written fallback left to drift.
     */
    @Test
    public void aRetiredModelFallsBackToTheDefaultRatherThanRunningOnSilently() {
        List<String> allowed = List.of("gemini-3.8-flash", "gpt-5.4-mini");

        assertThat(ChatConversationService.effectiveModel("gpt-5.4-mini", allowed))
            .as("a model still on the whitelist is left exactly as stored")
            .isEqualTo("gpt-5.4-mini");
        assertThat(ChatConversationService.effectiveModel("gemini-3.6-flash", allowed))
            .as("a retired model becomes the default — element 0, the same value a new"
                + " conversation is stamped with")
            .isEqualTo("gemini-3.8-flash");
    }

    /**
     * An EMPTY (or absent) whitelist is a missing opinion, not a statement that every model is
     * forbidden: {@code createConversation} already refuses to run without one, so the only callers
     * that can see an empty list are ones whose configuration never arrived. Substituting a
     * "default" there would mean inventing a model out of nothing.
     */
    @Test
    public void anEmptyWhitelistPassesTheStoredModelThroughUntouched() {
        assertThat(ChatConversationService.effectiveModel("gemini-3.6-flash", List.of()))
            .isEqualTo("gemini-3.6-flash");
        assertThat(ChatConversationService.effectiveModel("gemini-3.6-flash", null))
            .isEqualTo("gemini-3.6-flash");
    }

    /**
     * A missing model is NOT this method's problem — each caller already handles it differently
     * (the send path throws, the preflight substitutes the default), and folding those together
     * here would change two behaviours while fixing a third.
     */
    @Test
    public void aMissingStoredModelIsReturnedUnchangedForTheCallerToHandle() {
        assertThat(ChatConversationService.effectiveModel(null, List.of("gemini-3.8-flash")))
            .isNull();
        assertThat(ChatConversationService.effectiveModel("  ", List.of("gemini-3.8-flash")))
            .isEqualTo("  ");
    }

    @Test
    public void theInstanceOverloadReadsTheConfiguredWhitelist() {
        assertThat(chatConversationService.effectiveModel("gemini-3.1-flash-lite"))
            .isEqualTo("gemini-3.1-flash-lite");
        assertThat(chatConversationService.effectiveModel("retired-model"))
            .isEqualTo("gemini-3.1-flash-lite");
    }
}
