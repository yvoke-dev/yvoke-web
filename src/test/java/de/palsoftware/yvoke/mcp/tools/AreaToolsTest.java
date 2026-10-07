package de.palsoftware.yvoke.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.area.core.Area;
import de.palsoftware.yvoke.area.core.AreaMembers;
import de.palsoftware.yvoke.area.core.AreaRepository;
import de.palsoftware.yvoke.area.core.AreaService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code list_areas} hands the Yvoke plugin's setup band everything it offers: each area, its
 * defaults, and the names of its members.
 */
class AreaToolsTest {

    private final ObjectMapper json = new ObjectMapper();
    private AreaRepository areaRepository;
    private AreaTools tools;

    @BeforeEach
    void setUp() {
        areaRepository = mock(AreaRepository.class);
        when(areaRepository.findAll()).thenReturn(List.of());
        when(areaRepository.findAllMembers()).thenReturn(Map.of());
        tools = new AreaTools(new AreaService(areaRepository), json);
    }

    @Test
    void noAreasIsAnEmptyArray() throws Exception {
        assertThat(json.readTree(tools.listAreas()).isArray()).isTrue();
        assertThat(json.readTree(tools.listAreas())).isEmpty();
    }

    @Test
    void oneAreaCarriesItsFieldsDefaultsAndMembers() throws Exception {
        when(areaRepository.findAll()).thenReturn(List.of(new Area("OIM", "Oracle Identity",
            "OIM docs", true, "oim-chat", "oim-full", "OIM", null, null)));
        when(areaRepository.findAllMembers())
            .thenReturn(Map.of("OIM", new AreaMembers(List.of("oim-chat"), List.of("OIM - Docs"),
                List.of("oim-full"), List.of("OIM"))));

        JsonNode area = single(json.readTree(tools.listAreas()));

        assertThat(fieldNames(area)).containsExactlyInAnyOrder("name", "title", "description",
            "prototype", "defaultSystemPrompt", "defaultPlaybook", "defaultProfile",
            "systemPrompts", "collections", "playbooks", "profiles");
        assertThat(area.get("name").asText()).isEqualTo("OIM");
        assertThat(area.get("title").asText()).isEqualTo("Oracle Identity");
        assertThat(area.get("description").asText()).isEqualTo("OIM docs");
        assertThat(area.get("prototype").asBoolean()).isTrue();
        assertThat(area.get("defaultSystemPrompt").asText()).isEqualTo("oim-chat");
        assertThat(area.get("defaultPlaybook").asText()).isEqualTo("oim-full");
        assertThat(area.get("defaultProfile").asText()).isEqualTo("OIM");
        assertThat(texts(area.get("systemPrompts"))).containsExactly("oim-chat");
        assertThat(texts(area.get("collections"))).containsExactly("OIM - Docs");
        assertThat(texts(area.get("playbooks"))).containsExactly("oim-full");
        assertThat(texts(area.get("profiles"))).containsExactly("OIM");
    }

    @Test
    void twoAreasEachListOnlyTheirOwnMembers() throws Exception {
        when(areaRepository.findAll()).thenReturn(List.of(area("OIM"), area("PingID")));
        when(areaRepository.findAllMembers()).thenReturn(Map.of("OIM",
            new AreaMembers(List.of("oim-chat"), List.of("OIM - Docs"), List.of("oim-full"),
                List.of("OIM")),
            "PingID", new AreaMembers(List.of("ping-chat"), List.of("Ping - Docs"),
                List.of("ping-full"), List.of("Ping"))));

        JsonNode list = json.readTree(tools.listAreas());

        assertThat(list).hasSize(2);
        JsonNode oim = list.get(0);
        JsonNode ping = list.get(1);
        assertThat(oim.get("name").asText()).isEqualTo("OIM");
        assertThat(ping.get("name").asText()).isEqualTo("PingID");
        assertThat(texts(oim.get("systemPrompts"))).containsExactly("oim-chat");
        assertThat(texts(oim.get("collections"))).containsExactly("OIM - Docs");
        assertThat(texts(oim.get("playbooks"))).containsExactly("oim-full");
        assertThat(texts(oim.get("profiles"))).containsExactly("OIM");
        assertThat(texts(ping.get("systemPrompts"))).containsExactly("ping-chat");
        assertThat(texts(ping.get("collections"))).containsExactly("Ping - Docs");
        assertThat(texts(ping.get("playbooks"))).containsExactly("ping-full");
        assertThat(texts(ping.get("profiles"))).containsExactly("Ping");
    }

    @Test
    void anUnsetDefaultIsNullAndAnAreaWithNoMembersHasEmptyLists() throws Exception {
        when(areaRepository.findAll()).thenReturn(List.of(area("OIM")));

        JsonNode area = single(json.readTree(tools.listAreas()));

        assertThat(area.get("defaultSystemPrompt").isNull()).isTrue();
        assertThat(area.get("defaultPlaybook").isNull()).isTrue();
        assertThat(area.get("defaultProfile").isNull()).isTrue();
        assertThat(area.get("description").isNull()).isTrue();
        assertThat(area.get("playbooks").isArray()).isTrue();
        assertThat(area.get("playbooks")).isEmpty();
    }

    /**
     * A default the client cannot pick is no default: a playbook the list leaves out (an
     * orchestrator's) or an item since moved to another area would start a session the setup band
     * does not offer.
     */
    @Test
    void aDefaultThatIsNotAPickableMemberOfTheAreaIsNull() throws Exception {
        when(areaRepository.findAll()).thenReturn(List.of(new Area("OIM", "OIM", null, false,
            "ping-chat", "oim-orchestrator", "Ping", null, null), area("PingID")));
        when(areaRepository.findAllMembers()).thenReturn(Map.of("PingID",
            new AreaMembers(List.of("ping-chat"), List.of(), List.of(), List.of("Ping"))));

        JsonNode oim = json.readTree(tools.listAreas()).get(0);

        assertThat(oim.get("defaultSystemPrompt").isNull()).isTrue();
        assertThat(oim.get("defaultPlaybook").isNull()).isTrue();
        assertThat(oim.get("defaultProfile").isNull()).isTrue();
    }

    @Test
    void aFailureIsTheGenericError() {
        when(areaRepository.findAll()).thenThrow(new IllegalStateException("db down: secret"));

        assertThat(tools.listAreas())
            .isEqualTo("ERROR: the 'list_areas' tool failed to complete the request.");
    }

    private static JsonNode single(JsonNode list) {
        assertThat(list).hasSize(1);
        return list.get(0);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static Area area(String name) {
        return new Area(name, name, null, false, null, null, null, null, null);
    }
}
