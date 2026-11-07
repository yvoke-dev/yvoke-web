package de.palsoftware.yvoke.rag.prompt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.palsoftware.yvoke.area.TestAreas;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SystemPromptServiceTest {

    private SystemPromptRepository repository;
    private SystemPromptService service;

    @BeforeEach
    void setUp() {
        repository = mock(SystemPromptRepository.class);
        service = new SystemPromptService(repository, TestAreas.withAreas("OIM"));
    }

    @Test
    void testExportAndImportPrompt() {
        SystemPrompt prompt = new SystemPrompt("test-prompt", SystemPromptType.KG,
            "System instruction content", "Test Description");
        when(repository.findByName("test-prompt")).thenReturn(Optional.of(prompt));

        String exportedMd = service.exportPromptToMarkdown("test-prompt");
        assertTrue(exportedMd.contains("name: test-prompt"));
        assertTrue(exportedMd.contains("type: KG"));
        assertTrue(exportedMd.contains("description: Test Description"));
        assertTrue(exportedMd.contains("System instruction content"));

        when(repository.findByName("test-prompt")).thenReturn(Optional.of(prompt));
        SystemPrompt imported = service.importPromptFromMarkdown(exportedMd, "test-prompt", "OIM");
        assertEquals("test-prompt", imported.name());
        assertEquals(SystemPromptType.KG, imported.type());
        assertEquals("System instruction content", imported.systemPrompt());
        assertEquals("Test Description", imported.description());
        verify(repository).upsert("test-prompt", SystemPromptType.KG, "System instruction content",
            "Test Description", "OIM");
    }

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
        when(repository.findByName("oim-kg"))
            .thenReturn(Optional.of(prompt("oim-kg", SystemPromptType.KG)));
        when(repository.findByType(SystemPromptType.SUMMARIZE))
            .thenReturn(List.of(prompt("oim-summarize", SystemPromptType.SUMMARIZE)));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> service.requirePrompt("oim-kg", SystemPromptType.SUMMARIZE));
        assertTrue(e.getMessage().contains("is of type KG"), e.getMessage());
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
        when(repository.findByName("x")).thenReturn(Optional.of(prompt("x", SystemPromptType.KG)));
        assertEquals("x", service.requirePrompt("x", null).name());
        IllegalArgumentException e =
            assertThrows(IllegalArgumentException.class, () -> service.requirePrompt(null, null));
        assertTrue(e.getMessage().contains("(no type given)"), e.getMessage());
    }

    @Test
    void savePromptStoresTheAreasStoredNameAndRefusesAnUnknownOne() {
        service.savePrompt("p", SystemPromptType.KG, "text", null, " oim ");
        verify(repository).upsert("p", SystemPromptType.KG, "text", "", "OIM");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> service.savePrompt("q", SystemPromptType.KG, "text", null, "SAP"));
        assertEquals("Area 'SAP' does not exist.", e.getMessage());
        verify(repository, never()).upsert(eq("q"), any(), any(), any(), any());
    }

    @Test
    void importReadsTheAreaFromTheFrontmatterOverTheFallback() {
        SystemPromptService twoAreas =
            new SystemPromptService(repository, TestAreas.withAreas("OIM", "PingID"));
        twoAreas.importPromptFromMarkdown("""
            ---
            name: p
            type: KG
            area: PingID
            ---
            text
            """, null, "OIM");
        verify(repository).upsert("p", SystemPromptType.KG, "text", "", "PingID");
    }

    @Test
    void importPutsAFileWithoutAnAreaIntoTheFallback() {
        service.importPromptFromMarkdown("""
            ---
            name: p
            type: KG
            ---
            text
            """, null, "OIM");
        verify(repository).upsert("p", SystemPromptType.KG, "text", "", "OIM");
    }

    @Test
    void exportWritesTheArea() {
        when(repository.findByName("p")).thenReturn(Optional.of(
            new SystemPrompt("p", SystemPromptType.KG, "text", "", null, null, false, "PingID")));
        assertTrue(service.exportPromptToMarkdown("p").contains("area: PingID"));
    }

    @Test
    void parseMarkdownRejectsMalformedYamlFrontmatter() {
        assertThrows(IllegalArgumentException.class,
            () -> SystemPromptService.parseMarkdown("---\nname: [unclosed\n---\ntext", "p", "OIM"));
    }

    @Test
    void parseMarkdownRejectsEmptyContentOrMissingFrontmatter() {
        assertThrows(IllegalArgumentException.class,
            () -> SystemPromptService.parseMarkdown(null, "p", "OIM"));
        assertThrows(IllegalArgumentException.class,
            () -> SystemPromptService.parseMarkdown("   ", "p", "OIM"));
        assertThrows(IllegalArgumentException.class, () -> SystemPromptService
            .parseMarkdown("Only body text without frontmatter", "p", "OIM"));
    }

    @Test
    void parseMarkdownRejectsMissingOrEmptyType() {
        assertThrows(IllegalArgumentException.class,
            () -> SystemPromptService.parseMarkdown("---\nname: p\n---\nbody", "p", "OIM"));
        assertThrows(IllegalArgumentException.class, () -> SystemPromptService
            .parseMarkdown("---\nname: p\ntype:  \n---\nbody", "p", "OIM"));
    }

    @Test
    void parseMarkdownRejectsRetiredChatAndUnknownTypes() {
        assertThrows(IllegalArgumentException.class, () -> SystemPromptService
            .parseMarkdown("---\nname: p\ntype: CHAT\n---\nbody", "p", "OIM"));
        assertThrows(IllegalArgumentException.class, () -> SystemPromptService
            .parseMarkdown("---\nname: p\ntype: agentic\n---\nbody", "p", "OIM"));
        assertThrows(IllegalArgumentException.class, () -> SystemPromptService
            .parseMarkdown("---\nname: p\ntype: UNKNOWN\n---\nbody", "p", "OIM"));
    }

    @Test
    void parseMarkdownAcceptsValidIngestionTypes() {
        SystemPrompt kg =
            SystemPromptService.parseMarkdown("---\nname: p\ntype: KG\n---\nbody", "p", "OIM");
        assertEquals(SystemPromptType.KG, kg.type());
        assertEquals("body", kg.systemPrompt());

        SystemPrompt sum = SystemPromptService
            .parseMarkdown("---\nname: p\ntype: SUMMARIZE\n---\nbody", "p", "OIM");
        assertEquals(SystemPromptType.SUMMARIZE, sum.type());
    }
}
