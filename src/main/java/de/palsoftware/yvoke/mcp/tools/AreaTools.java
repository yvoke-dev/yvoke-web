package de.palsoftware.yvoke.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.area.core.AreaService;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfile;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfileRepository;
import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.repository.CollectionRepository;
import de.palsoftware.yvoke.mcp.McpToolUtils;
import de.palsoftware.yvoke.rag.prompt.Playbook;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import de.palsoftware.yvoke.rag.prompt.SystemPrompt;
import de.palsoftware.yvoke.rag.prompt.SystemPromptRepository;
import de.palsoftware.yvoke.rag.prompt.SystemPromptType;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * The areas, for the Yvoke plugin's setup band: each area with its defaults and the names of its
 * members. Read from the tables on every call, so a new area or a moved item shows at once.
 *
 * <p>
 * A default is reported only when it is a member the client can pick: a chat prompt, a playbook
 * {@code list_playbooks} offers, or a profile, in the same area. Anything else would start a
 * session on an item the setup band does not list.
 */
@Component
public class AreaTools {

    private static final String DESCRIPTION =
        "For Yvoke clients only: call this only when a Yvoke playbook skill or the Yvoke plugin "
            + "tells you to. Lists the Yvoke areas (knowledge bases such as OIM) as a JSON array of "
            + "{name, title, description, prototype, defaultSystemPrompt, defaultPlaybook, "
            + "defaultProfile, systemPrompts, collections, playbooks, profiles}, where the last "
            + "four are the names of the area's members. It does not answer questions about the "
            + "knowledge base.";

    /** One area as {@code list_areas} returns it. */
    record AreaDto(String name, String title, String description, boolean prototype,
        String defaultSystemPrompt, String defaultPlaybook, String defaultProfile,
        List<String> systemPrompts, List<String> collections, List<String> playbooks,
        List<String> profiles) {}

    private final AreaService areaService;
    private final PlaybookService playbookService;
    private final SystemPromptRepository systemPromptRepository;
    private final CollectionRepository collectionRepository;
    private final OrchestratorProfileRepository profileRepository;
    private final ObjectMapper objectMapper;

    public AreaTools(AreaService areaService, PlaybookService playbookService,
        SystemPromptRepository systemPromptRepository, CollectionRepository collectionRepository,
        OrchestratorProfileRepository profileRepository, ObjectMapper objectMapper) {
        this.areaService = areaService;
        this.playbookService = playbookService;
        this.systemPromptRepository = systemPromptRepository;
        this.collectionRepository = collectionRepository;
        this.profileRepository = profileRepository;
        this.objectMapper = objectMapper;
    }

    @McpTool(name = "list_areas", description = DESCRIPTION)
    @Tool(name = "list_areas", description = DESCRIPTION)
    public String listAreas() {
        try {
            Map<String, List<String>> prompts =
                byArea(systemPromptRepository.findByType(SystemPromptType.CHAT), SystemPrompt::area,
                    SystemPrompt::name);
            Map<String, List<String>> collections =
                byArea(collectionRepository.findAll(), Collection::area, Collection::name);
            Map<String, List<String>> playbooks =
                byArea(playbookService.listSpecializedPlaybooks(), Playbook::area, Playbook::name);
            Map<String, List<String>> profiles = byArea(profileRepository.findAll(),
                OrchestratorProfile::area, OrchestratorProfile::name);

            List<AreaDto> areas = areaService.listAreas().stream().map(area -> {
                List<String> areaPrompts = prompts.getOrDefault(area.name(), List.of());
                List<String> areaPlaybooks = playbooks.getOrDefault(area.name(), List.of());
                List<String> areaProfiles = profiles.getOrDefault(area.name(), List.of());
                return new AreaDto(area.name(), area.title(), area.description(), area.prototype(),
                    memberOrNull(area.defaultSystemPrompt(), areaPrompts),
                    memberOrNull(area.defaultPlaybook(), areaPlaybooks),
                    memberOrNull(area.defaultProfile(), areaProfiles), areaPrompts,
                    collections.getOrDefault(area.name(), List.of()), areaPlaybooks, areaProfiles);
            }).toList();
            return objectMapper.writeValueAsString(areas);
        } catch (Exception e) {
            return McpToolUtils.toolError("list_areas", e);
        }
    }

    private static <T> Map<String, List<String>> byArea(List<T> items, Function<T, String> area,
        Function<T, String> name) {
        return items.stream().filter(item -> area.apply(item) != null)
            .collect(Collectors.groupingBy(area, Collectors.mapping(name, Collectors.toList())));
    }

    private static String memberOrNull(String name, List<String> members) {
        return name != null && members.contains(name) ? name : null;
    }

}
