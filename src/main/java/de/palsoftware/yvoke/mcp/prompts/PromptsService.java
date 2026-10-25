package de.palsoftware.yvoke.mcp.prompts;

import de.palsoftware.yvoke.rag.prompt.Playbook;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import de.palsoftware.yvoke.shared.security.AreaAccessContext;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class PromptsService {

    private final PlaybookService playbookService;

    @Autowired
    public PromptsService(PlaybookService playbookService) {
        this.playbookService = playbookService;
    }

    public record PromptMetadata(String name, String title, String description) {}

    public List<PromptMetadata> getPromptsMetadata() {
        return AreaAccessContext
            .filterByUserArea(playbookService.listAllPlaybooks(), Playbook::area).stream()
            .map(s -> new PromptMetadata(s.name(), s.title(), s.description()))
            .collect(Collectors.toList());
    }

    public String getPromptText(String playbookName) {
        if (playbookName == null || playbookName.isBlank()) {
            return null;
        }
        Optional<Playbook> pb = playbookService.getPlaybook(playbookName.trim());
        if (pb.isEmpty()) {
            return null;
        }
        Playbook playbook = pb.get();
        if (playbook.area() != null && !playbook.area().isBlank()
            && !AreaAccessContext.canAccessUserArea(playbook.area())) {
            return null;
        }
        return playbook.templateText();
    }
}
