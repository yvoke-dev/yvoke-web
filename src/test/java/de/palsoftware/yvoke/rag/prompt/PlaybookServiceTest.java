package de.palsoftware.yvoke.rag.prompt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.modelcontextprotocol.server.McpSyncServer;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlaybookServiceTest {

    private PlaybookRepository playbookRepository;
    private McpSyncServer mcpSyncServer;
    private PlaybookService playbookService;

    @BeforeEach
    void setUp() {
        playbookRepository = mock(PlaybookRepository.class);
        mcpSyncServer = mock(McpSyncServer.class);
        playbookService = new PlaybookService(playbookRepository, mcpSyncServer);
    }

    @Test
    void testListAllPlaybooks() {
        Playbook dbPlaybook1 = new Playbook("db-playbook-1", "DB Playbook 1 Title",
            "DB 1 Description", "Hello from DB 1", List.of(), false, "specialist", null, null);
        Playbook dbPlaybook2 = new Playbook("db-playbook-2", "DB Playbook 2 Title",
            "DB 2 Description", "Hello from DB 2", List.of(), false, "orchestrator", null, null);

        when(playbookRepository.findAll()).thenReturn(List.of(dbPlaybook1, dbPlaybook2));

        List<Playbook> playbooks = playbookService.listAllPlaybooks();
        assertEquals(2, playbooks.size());

        Playbook playbook1 = playbooks.stream().filter(p -> p.name().equals("db-playbook-1"))
            .findFirst().orElseThrow();
        assertEquals("DB Playbook 1 Title", playbook1.title());
        assertEquals("Hello from DB 1", playbook1.templateText());
        assertEquals("specialist", playbook1.targetAgent());
        assertFalse(playbook1.readOnly());

        Playbook playbook2 = playbooks.stream().filter(p -> p.name().equals("db-playbook-2"))
            .findFirst().orElseThrow();
        assertEquals("DB Playbook 2 Title", playbook2.title());
        assertEquals("Hello from DB 2", playbook2.templateText());
        assertEquals("orchestrator", playbook2.targetAgent());
        assertFalse(playbook2.readOnly());
    }

    @Test
    void testListSpecializedPlaybooks() {
        Playbook pb1 = new Playbook("pb1", "Title 1", "Desc", "Body", List.of(), false,
            "specialist", null, null);
        Playbook pb2 = new Playbook("pb2", "Title 2", "Desc", "Body", List.of(), false,
            "orchestrator", null, null);
        Playbook pb3 = new Playbook("pb3", "Title 3", "Desc", "Body", List.of(), false, "reviewer",
            null, null);
        Playbook pb4 =
            new Playbook("pb4", "Title 4", "Desc", "Body", List.of(), false, null, null, null);

        when(playbookRepository.findAll()).thenReturn(List.of(pb1, pb2, pb3, pb4));

        List<Playbook> specPlaybooks = playbookService.listSpecializedPlaybooks();
        assertEquals(2, specPlaybooks.size());
        assertTrue(specPlaybooks.stream().anyMatch(p -> p.name().equals("pb1")));
        assertTrue(specPlaybooks.stream().anyMatch(p -> p.name().equals("pb4")));
    }

    @Test
    void testGetPlaybook() {
        Playbook dbPlaybook = new Playbook("db-only", "DB Only Title", "DB Desc", "DB Body",
            List.of(), false, "reviewer", null, null);
        when(playbookRepository.findByName("db-only")).thenReturn(Optional.of(dbPlaybook));

        Optional<Playbook> playbookOpt = playbookService.getPlaybook("db-only");
        assertTrue(playbookOpt.isPresent());
        assertEquals("DB Only Title", playbookOpt.get().title());
        assertEquals("reviewer", playbookOpt.get().targetAgent());
        assertFalse(playbookOpt.get().readOnly());
    }

    @Test
    void testSavePlaybookSuccess() {
        playbookService.savePlaybook("new-playbook", "New Playbook", "Desc", "Body", List.of(),
            false, "orchestrator");
        verify(playbookRepository).upsert("new-playbook", "New Playbook", "Desc", "Body", List.of(),
            false, "orchestrator");
    }

    @Test
    void testExportAndImportPlaybook() {
        Playbook pb = new Playbook("test-export", "Test Export", "Desc", "Body content",
            List.of("tool1"), true, "orchestrator", null, null);
        when(playbookRepository.findByName("test-export")).thenReturn(Optional.of(pb));

        String exportedMd = playbookService.exportPlaybookToMarkdown("test-export");
        assertTrue(exportedMd.contains("target_agent: orchestrator"));
        assertTrue(exportedMd.contains("code_execution: true"));

        when(playbookRepository.findByName("test-export")).thenReturn(Optional.of(pb));
        Playbook imported = playbookService.importPlaybookFromMarkdown(exportedMd, "test-export");
        assertEquals("test-export", imported.name());
        assertEquals("orchestrator", imported.targetAgent());
        assertTrue(imported.codeExecution());
    }

    @Test
    void testDeletePlaybookSuccess() {
        playbookService.deletePlaybook("db-playbook");
        verify(playbookRepository).delete("db-playbook");
    }
}
