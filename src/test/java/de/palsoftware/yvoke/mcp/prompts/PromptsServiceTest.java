package de.palsoftware.yvoke.mcp.prompts;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.palsoftware.yvoke.rag.prompt.Playbook;
import de.palsoftware.yvoke.rag.prompt.PlaybookRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class PromptsServiceTest {

    private PlaybookRepository playbookRepository;
    private PromptsService prompts;

    @BeforeEach
    public void setUp() {
        playbookRepository = mock(PlaybookRepository.class);
        prompts = new PromptsService(playbookRepository);
    }

    @Test
    public void testGetPromptsMetadata() {
        Playbook playbook1 =
            new Playbook("name-1", "Title 1", "Desc 1", "Template 1", List.of(), false, null, null);
        Playbook playbook2 =
            new Playbook("name-2", "Title 2", "Desc 2", "Template 2", List.of(), false, null, null);
        when(playbookRepository.findAll()).thenReturn(List.of(playbook1, playbook2));

        List<PromptsService.PromptMetadata> metadataList = prompts.getPromptsMetadata();
        assertEquals(2, metadataList.size());
        assertEquals("name-1", metadataList.get(0).name());
        assertEquals("Title 1", metadataList.get(0).title());
        assertEquals("Desc 1", metadataList.get(0).description());
        assertEquals("name-2", metadataList.get(1).name());
        assertEquals("Title 2", metadataList.get(1).title());
        assertEquals("Desc 2", metadataList.get(1).description());
    }

    @Test
    public void testGetPromptText() {
        Playbook playbook =
            new Playbook("name-1", "Title 1", "Desc 1", "Template 1", List.of(), false, null, null);
        when(playbookRepository.findByName("name-1")).thenReturn(Optional.of(playbook));
        when(playbookRepository.findByName("unknown")).thenReturn(Optional.empty());

        assertEquals("Template 1", prompts.getPromptText("name-1"));
        assertEquals("Template 1", prompts.getPromptText(" name-1 ")); // trimmed
        assertNull(prompts.getPromptText("unknown"));
        assertNull(prompts.getPromptText(null));
        assertNull(prompts.getPromptText(""));
    }
}
