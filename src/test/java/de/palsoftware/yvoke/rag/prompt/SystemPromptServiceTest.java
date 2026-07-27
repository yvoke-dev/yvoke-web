package de.palsoftware.yvoke.rag.prompt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.palsoftware.yvoke.shared.config.repository.AppConfigRepository;
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
}
