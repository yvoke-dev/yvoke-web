package de.palsoftware.yvoke.rag.prompt;

import static org.mockito.ArgumentMatchers.eq;

import de.palsoftware.yvoke.area.TestAreas;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.palsoftware.yvoke.shared.config.repository.AppConfigRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SystemPromptServiceTest {

    private SystemPromptRepository repository;
    private AppConfigRepository appConfigRepository;
    private SystemPromptService service;

    @BeforeEach
    void setUp() {
        repository = mock(SystemPromptRepository.class);
        appConfigRepository = mock(AppConfigRepository.class);
        service = new SystemPromptService(repository, "default-chat", appConfigRepository,
            TestAreas.withAreas("OIM"));
    }

    /**
     * S5.1. The active chat prompt is an {@code app_config} row that a fresh deployment has never
     * written, so the fallback handed to the repository is what actually governs every answer until
     * an admin touches the setting. It must be this service's own
     * {@code @Value("${app.ai.rag.default-prompt-name}")} — the same property the shipped prompt is
     * seeded under — and not a literal or a null.
     *
     * <p>
     * Get it wrong and the resolved name matches no row: {@code getPrompt} returns empty and
     * {@code RagService.loadAgenticSystemPrompt} maps that to {@code ""}, so every chat answer runs
     * with an EMPTY system prompt. Nothing throws, nothing logs, the admin screens still show a
     * perfectly normal configuration — the model simply answers with none of the corpus rules,
     * citation discipline or refusal behaviour the prompt carries, which from outside looks like
     * the model got worse.
     *
     * <p>
     * {@code AppConfigRepositoryIT.getMissingKeyReturnsDefault} pins that the repository echoes its
     * default argument when the key is absent; that is the behaviour stubbed here. What is
     * unpinned, and asserted here, is the consequence: which name the service resolves, and that
     * the name still finds a prompt. The second half pins the other direction — a stored row must
     * still beat the configured default, so this cannot be satisfied by ignoring {@code app_config}
     * altogether.
     */
    @Test
    void theActiveChatPromptNameFallsBackToTheConfiguredDefaultAndStillResolvesAPrompt() {
        SystemPrompt shipped = new SystemPrompt("default-chat", SystemPromptType.CHAT,
            "Cite every claim.", "the shipped default");
        when(repository.findByName("default-chat")).thenReturn(Optional.of(shipped));
        // No app_config row: the repository hands back whatever default it was given.
        when(appConfigRepository.get(eq("default-chat-prompt"), anyString()))
            .thenAnswer(inv -> inv.getArgument(1));

        String resolved = service.getDefaultChatPromptName();

        assertEquals("default-chat", resolved);
        assertTrue(service.getPrompt(resolved).isPresent(),
            "the fallback name must resolve to a real prompt, or every answer runs ungoverned");

        // An admin-set row still wins over the configured default.
        when(appConfigRepository.get(eq("default-chat-prompt"), anyString()))
            .thenReturn("oim-agentic");
        assertEquals("oim-agentic", service.getDefaultChatPromptName());
    }

    @Test
    void testExportAndImportPrompt() {
        SystemPrompt prompt = new SystemPrompt("test-prompt", SystemPromptType.CHAT,
            "System instruction content", "Test Description");
        when(repository.findByName("test-prompt")).thenReturn(Optional.of(prompt));

        String exportedMd = service.exportPromptToMarkdown("test-prompt");
        assertTrue(exportedMd.contains("name: test-prompt"));
        assertTrue(exportedMd.contains("type: CHAT"));
        assertTrue(exportedMd.contains("description: Test Description"));
        assertTrue(exportedMd.contains("System instruction content"));

        when(repository.findByName("test-prompt")).thenReturn(Optional.of(prompt));
        SystemPrompt imported = service.importPromptFromMarkdown(exportedMd, "test-prompt", "OIM");
        assertEquals("test-prompt", imported.name());
        assertEquals(SystemPromptType.CHAT, imported.type());
        assertEquals("System instruction content", imported.systemPrompt());
        assertEquals("Test Description", imported.description());
        verify(repository).upsert("test-prompt", SystemPromptType.CHAT,
            "System instruction content", "Test Description", "OIM");
    }

    // ---- requirePrompt --------------------------------------------------
    //
    // The whole point of this method is that it REFUSES, so its refusal paths are the behaviour.
    // The type check especially: prompts share one flat namespace, so nothing else stops a CHAT
    // prompt being selected where a SUMMARIZE one is meant, and that mistake resolves cleanly and
    // then instructs the summarizer to answer questions and cite sources.

    private static SystemPrompt prompt(String name, SystemPromptType type) {
        return new SystemPrompt(name, type, "BODY", "");
    }

    @Test
    void requirePromptReturnsThePromptWhenNameAndTypeMatch() {
        when(repository.findByName("oim-summarize"))
            .thenReturn(Optional.of(prompt("oim-summarize", SystemPromptType.SUMMARIZE)));
        assertEquals("BODY",
            service.requirePrompt("oim-summarize", SystemPromptType.SUMMARIZE).systemPrompt());
    }

    @Test
    void requirePromptRefusesAWrongTypedPrompt() {
        when(repository.findByName("default-chat"))
            .thenReturn(Optional.of(prompt("default-chat", SystemPromptType.CHAT)));
        when(repository.findByType(SystemPromptType.SUMMARIZE))
            .thenReturn(List.of(prompt("oim-summarize", SystemPromptType.SUMMARIZE)));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> service.requirePrompt("default-chat", SystemPromptType.SUMMARIZE));
        assertTrue(e.getMessage().contains("is of type CHAT"), e.getMessage());
        assertTrue(e.getMessage().contains("SUMMARIZE prompt is required"), e.getMessage());
        assertTrue(e.getMessage().contains("oim-summarize"), "must list the valid names");
    }

    @Test
    void requirePromptRefusesAnUnknownName() {
        when(repository.findByName("nope")).thenReturn(Optional.empty());
        when(repository.findByType(SystemPromptType.KG)).thenReturn(List.of());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> service.requirePrompt("nope", SystemPromptType.KG));
        assertTrue(e.getMessage().contains("does not exist"), e.getMessage());
        assertTrue(e.getMessage().contains("(none registered)"),
            "an empty roster must say so rather than trailing an empty list");
    }

    @Test
    void requirePromptRefusesANullOrBlankName() {
        when(repository.findByType(SystemPromptType.SUMMARIZE)).thenReturn(List
            .of(prompt("b", SystemPromptType.SUMMARIZE), prompt("a", SystemPromptType.SUMMARIZE)));
        for (String bad : new String[] {null, "", "   "}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.requirePrompt(bad, SystemPromptType.SUMMARIZE));
            assertTrue(e.getMessage().contains("was specified"), e.getMessage());
            assertTrue(e.getMessage().contains("a, b"), "names are sorted so the text is stable");
        }
    }

    @Test
    void requirePromptWithNoExpectedTypeSkipsTheTypeCheck() {
        // Defensive: a null type must not NPE while building the message.
        when(repository.findByName("x"))
            .thenReturn(Optional.of(prompt("x", SystemPromptType.CHAT)));
        assertEquals("x", service.requirePrompt("x", null).name());
        IllegalArgumentException e =
            assertThrows(IllegalArgumentException.class, () -> service.requirePrompt(null, null));
        assertTrue(e.getMessage().contains("(no type given)"), e.getMessage());
    }

    /**
     * {@code findChatPrompt} is what an MCP client (the Claude plugin) receives as its base
     * instructions. {@code default-chat} (or no name) means the prompt an admin made active,
     * exactly as the web chat resolves it in {@code RagService.loadAgenticSystemPrompt}; any other
     * name is looked up as given. Only CHAT prompts are returned: KG and SUMMARIZE prompts share
     * the namespace and are not base instructions, so handing them out would give every signed-in
     * client the ingest prompts for no use.
     */
    @Test
    void findChatPromptReturnsAStoredChatPromptByName() {
        SystemPrompt prompt =
            new SystemPrompt("custom-chat", SystemPromptType.CHAT, "Be brief.", "");
        when(repository.findByName("custom-chat")).thenReturn(Optional.of(prompt));

        assertEquals(Optional.of(prompt), service.findChatPrompt("custom-chat"));
    }

    /**
     * The shipped {@code default-chat} row exists in every real deployment, so it must NOT win over
     * the admin's choice: an admin who makes another CHAT prompt active changes the web chat, and
     * the plugin has to follow, or the two surfaces answer under different instructions. (The
     * desktop's REST endpoint lets the stored row win; that endpoint is deliberately left as it
     * is.)
     */
    @Test
    void findChatPromptResolvesDefaultChatToTheAdminsActivePromptEvenWhenADefaultChatRowExists() {
        SystemPrompt shipped =
            new SystemPrompt("default-chat", SystemPromptType.CHAT, "Generic rules.", "");
        SystemPrompt active =
            new SystemPrompt("active-chat", SystemPromptType.CHAT, "Cite every claim.", "");
        when(repository.findByName("default-chat")).thenReturn(Optional.of(shipped));
        when(appConfigRepository.get(eq("default-chat-prompt"), anyString()))
            .thenReturn("active-chat");
        when(repository.findByName("active-chat")).thenReturn(Optional.of(active));

        assertEquals(Optional.of(active), service.findChatPrompt("default-chat"));
    }

    @Test
    void findChatPromptTreatsABlankOrMissingNameAsDefaultChat() {
        SystemPrompt active =
            new SystemPrompt("active-chat", SystemPromptType.CHAT, "Cite every claim.", "");
        when(appConfigRepository.get(eq("default-chat-prompt"), anyString()))
            .thenReturn("active-chat");
        when(repository.findByName("active-chat")).thenReturn(Optional.of(active));

        assertEquals(Optional.of(active), service.findChatPrompt(null));
        assertEquals(Optional.of(active), service.findChatPrompt("  "));
    }

    /** A fresh deployment has no app_config row, so the configured default name governs. */
    @Test
    void findChatPromptFallsBackToTheConfiguredDefaultNameWhenNoAdminChoiceIsStored() {
        SystemPrompt shipped =
            new SystemPrompt("default-chat", SystemPromptType.CHAT, "Generic rules.", "");
        when(appConfigRepository.get(eq("default-chat-prompt"), anyString()))
            .thenAnswer(inv -> inv.getArgument(1));
        when(repository.findByName("default-chat")).thenReturn(Optional.of(shipped));

        assertEquals(Optional.of(shipped), service.findChatPrompt(null));
    }

    @Test
    void findChatPromptIsEmptyForAnUnknownName() {
        when(repository.findByName("no-such-prompt")).thenReturn(Optional.empty());

        assertTrue(service.findChatPrompt("no-such-prompt").isEmpty());
    }

    @Test
    void findChatPromptNeverReturnsAnIngestPrompt() {
        when(repository.findByName("kg-extract")).thenReturn(Optional
            .of(new SystemPrompt("kg-extract", SystemPromptType.KG, "Extract entities.", "")));
        when(repository.findByName("summarize")).thenReturn(Optional
            .of(new SystemPrompt("summarize", SystemPromptType.SUMMARIZE, "Summarize.", "")));

        assertTrue(service.findChatPrompt("kg-extract").isEmpty());
        assertTrue(service.findChatPrompt("summarize").isEmpty());
    }

    /**
     * The admin's active default is itself only a name, and nothing stops it naming a non-CHAT
     * prompt; the type rule must hold on that path too, not just for a direct name.
     */
    @Test
    void findChatPromptNeverReturnsAnIngestPromptAsTheActiveDefault() {
        when(appConfigRepository.get(eq("default-chat-prompt"), anyString()))
            .thenReturn("summarize");
        when(repository.findByName("summarize")).thenReturn(Optional
            .of(new SystemPrompt("summarize", SystemPromptType.SUMMARIZE, "Summarize.", "")));

        assertTrue(service.findChatPrompt("default-chat").isEmpty());
    }

    @Test
    void savePromptStoresTheAreasStoredNameAndRefusesAnUnknownOne() {
        service.savePrompt("p", SystemPromptType.CHAT, "text", null, " oim ");
        verify(repository).upsert("p", SystemPromptType.CHAT, "text", "", "OIM");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> service.savePrompt("q", SystemPromptType.CHAT, "text", null, "SAP"));
        assertEquals("Area 'SAP' does not exist.", e.getMessage());
        verify(repository, never()).upsert(eq("q"), any(), any(), any(), any());
    }

    @Test
    void importReadsTheAreaFromTheFrontmatterOverTheFallback() {
        SystemPromptService twoAreas = new SystemPromptService(repository, "default-chat",
            appConfigRepository, TestAreas.withAreas("OIM", "PingID"));
        twoAreas.importPromptFromMarkdown("""
            ---
            name: p
            type: CHAT
            area: PingID
            ---
            text
            """, null, "OIM");
        verify(repository).upsert("p", SystemPromptType.CHAT, "text", "", "PingID");
    }

    @Test
    void importPutsAFileWithoutAnAreaIntoTheFallback() {
        service.importPromptFromMarkdown("""
            ---
            name: p
            ---
            text
            """, null, "OIM");
        verify(repository).upsert("p", SystemPromptType.CHAT, "text", "", "OIM");
    }

    @Test
    void exportWritesTheArea() {
        when(repository.findByName("p")).thenReturn(Optional.of(
            new SystemPrompt("p", SystemPromptType.CHAT, "text", "", null, null, false, "PingID")));
        assertTrue(service.exportPromptToMarkdown("p").contains("area: PingID"));
    }
}
