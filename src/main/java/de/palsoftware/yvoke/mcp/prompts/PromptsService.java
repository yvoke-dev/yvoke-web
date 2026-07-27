package de.palsoftware.yvoke.mcp.prompts;

import de.palsoftware.yvoke.rag.prompt.Playbook;
import de.palsoftware.yvoke.rag.prompt.PlaybookRepository;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class PromptsService {

    private final PlaybookService playbookService;

    public PromptsService(PlaybookRepository playbookRepository) {
        this(new PlaybookService(playbookRepository, null));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PromptsService(PlaybookService playbookService) {
        this.playbookService = playbookService;
    }

    public record PromptMetadata(String name, String title, String description) {}

    public List<PromptMetadata> getPromptsMetadata() {
        return playbookService.listAllPlaybooks().stream()
            .map(s -> new PromptMetadata(s.name(), s.title(), s.description()))
            .collect(Collectors.toList());
    }

    public String getPromptText(String playbookName) {
        if (playbookName == null || playbookName.isBlank()) {
            return null;
        }
        return playbookService.getPlaybook(playbookName.trim()).map(Playbook::templateText)
            .orElse(null);
    }
}
