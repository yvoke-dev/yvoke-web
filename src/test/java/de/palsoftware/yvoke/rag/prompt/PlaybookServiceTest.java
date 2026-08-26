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
        Playbook dbPlaybook1 =
            new Playbook("db-playbook-1", "DB Playbook 1 Title", "DB 1 Description",
                "Hello from DB 1", List.of(), false, "specialist", false, null, null);
        Playbook dbPlaybook2 =
            new Playbook("db-playbook-2", "DB Playbook 2 Title", "DB 2 Description",
                "Hello from DB 2", List.of(), false, "orchestrator", true, null, null);

        when(playbookRepository.findAll()).thenReturn(List.of(dbPlaybook1, dbPlaybook2));

        List<Playbook> playbooks = playbookService.listAllPlaybooks();
        assertEquals(2, playbooks.size());

        Playbook playbook1 = playbooks.stream().filter(p -> p.name().equals("db-playbook-1"))
            .findFirst().orElseThrow();
        assertEquals("DB Playbook 1 Title", playbook1.title());
        assertEquals("Hello from DB 1", playbook1.templateText());
        assertEquals("specialist", playbook1.targetAgent());
        assertFalse(playbook1.prototype());
        assertFalse(playbook1.readOnly());

        Playbook playbook2 = playbooks.stream().filter(p -> p.name().equals("db-playbook-2"))
            .findFirst().orElseThrow();
        assertEquals("DB Playbook 2 Title", playbook2.title());
        assertEquals("Hello from DB 2", playbook2.templateText());
        assertEquals("orchestrator", playbook2.targetAgent());
        assertTrue(playbook2.prototype());
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
            false, "orchestrator", true);
        verify(playbookRepository).upsert("new-playbook", "New Playbook", "Desc", "Body", List.of(),
            false, "orchestrator", true);
    }

    @Test
    void testExportAndImportPlaybook() {
        Playbook pb = new Playbook("test-export", "Test Export", "Desc", "Body content",
            List.of("tool1"), true, "orchestrator", true, null, null);
        when(playbookRepository.findByName("test-export")).thenReturn(Optional.of(pb));

        String exportedMd = playbookService.exportPlaybookToMarkdown("test-export");
        assertTrue(exportedMd.contains("target_agent: orchestrator"));
        assertTrue(exportedMd.contains("prototype: true"));
        assertTrue(exportedMd.contains("code_execution: true"));

        when(playbookRepository.findByName("test-export")).thenReturn(Optional.of(pb));
        Playbook imported = playbookService.importPlaybookFromMarkdown(exportedMd, "test-export");
        assertEquals("test-export", imported.name());
        assertEquals("orchestrator", imported.targetAgent());
        assertTrue(imported.prototype());
        assertTrue(imported.codeExecution());
    }

    /**
     * S5.14. {@code listSpecializedPlaybooks} is an EXCLUSION filter, not an inclusion one: only
     * {@code orchestrator} and {@code reviewer} are held back, and every other value — a typo, a
     * capitalisation, a role name inherited from another agent toolchain — is treated as a
     * specialist. The exclusion itself is case-insensitive, so {@code Orchestrator} and
     * {@code REVIEWER} are still excluded.
     *
     * <p>
     * That direction is deliberate and fragile. {@code target_agent} arrives from hand-written
     * playbook frontmatter and from the {@code targetAgent}/{@code role}/{@code type} alias chain
     * in {@code PlaybookMarkdownParser}, so odd spellings are normal input, not corruption. Narrow
     * this to "specialist-or-null" — the natural reading of the method name, and a one-line
     * simplification — and a playbook whose {@code target_agent} says {@code Specialist},
     * {@code agent} or {@code specialsit} silently vanishes from the preflight validator's
     * suggestion list and from orchestrator routing. Nothing errors: the list is simply SHORT,
     * which reads as "that playbook was never imported" rather than "the filter dropped it", and
     * the admin screens (which use {@code listAllPlaybooks}) still show it sitting there.
     *
     * <p>
     * {@code testListSpecializedPlaybooks} uses only the four canonical values ({@code specialist}
     * / {@code orchestrator} / {@code reviewer} / {@code null}), so a narrowed filter passes it
     * unchanged — every value it tests lands the same way under both rules.
     */
    @Test
    void anUnrecognisedTargetAgentIsTreatedAsASpecialistAndTheExclusionIsCaseInsensitive() {
        Playbook typo =
            new Playbook("pb-typo", "T", "D", "Body", List.of(), false, "specialsit", null, null);
        Playbook shouty =
            new Playbook("pb-shouty", "T", "D", "Body", List.of(), false, "SPECIALIST", null, null);
        Playbook foreignRole =
            new Playbook("pb-foreign", "T", "D", "Body", List.of(), false, "agent", null, null);
        Playbook orchestratorCased =
            new Playbook("pb-orch", "T", "D", "Body", List.of(), false, "Orchestrator", null, null);
        Playbook reviewerCased =
            new Playbook("pb-rev", "T", "D", "Body", List.of(), false, "REVIEWER", null, null);

        when(playbookRepository.findAll())
            .thenReturn(List.of(typo, shouty, foreignRole, orchestratorCased, reviewerCased));

        List<String> names =
            playbookService.listSpecializedPlaybooks().stream().map(Playbook::name).toList();

        assertEquals(List.of("pb-typo", "pb-shouty", "pb-foreign"), names,
            "everything that is not an orchestrator or a reviewer is a specialist, and the two "
                + "exclusions are matched case-insensitively");
    }

    @Test
    void testDeletePlaybookSuccess() {
        playbookService.deletePlaybook("db-playbook");
        verify(playbookRepository).delete("db-playbook");
    }
}
