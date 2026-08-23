package de.palsoftware.yvoke.rag.prompt;

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
        service = new SystemPromptService(repository, "default-chat", appConfigRepository);
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
        SystemPrompt imported = service.importPromptFromMarkdown(exportedMd, "test-prompt");
        assertEquals("test-prompt", imported.name());
        assertEquals(SystemPromptType.CHAT, imported.type());
        assertEquals("System instruction content", imported.systemPrompt());
        assertEquals("Test Description", imported.description());
        verify(repository).upsert("test-prompt", SystemPromptType.CHAT,
            "System instruction content", "Test Description");
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
}
