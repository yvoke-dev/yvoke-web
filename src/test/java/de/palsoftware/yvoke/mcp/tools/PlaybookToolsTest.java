package de.palsoftware.yvoke.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.area.TestAreas;
import de.palsoftware.yvoke.rag.prompt.Playbook;
import de.palsoftware.yvoke.rag.prompt.PlaybookRepository;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code list_playbooks} and {@code get_playbook} exist for Claude clients: the Yvoke plugin's mod
 * can call MCP tools but cannot read MCP prompts, so these hand it the playbook library as JSON.
 */
class PlaybookToolsTest {

    private final ObjectMapper json = new ObjectMapper();
    private PlaybookService playbookService;
    private PlaybookRepository playbookRepository;
    private PlaybookTools tools;

    private static final Set<String> META_KEYS = Set.of("name", "title", "description", "tools",
        "codeExecution", "targetAgent", "prototype", "area");

    private static final Playbook FULL = new Playbook("oim-full", "OIM full", "Everything OIM",
        "You are the OIM assistant.", List.of("search_corpus", "get_section"), true, "specialist",
        true, null, null, false, "OIM");
    private static final Playbook BARE = new Playbook("bare", "Bare", null, "Bare text", null,
        false, null, false, null, null, false, "PingID");
    private static final Playbook ORCHESTRATOR = new Playbook("oim-orchestrator", "Lead", "Leads",
        "You lead.", List.of(), false, "orchestrator", false, null, null);

    @BeforeEach
    void setUp() {
        playbookService = mock(PlaybookService.class);
        playbookRepository = mock(PlaybookRepository.class);
        tools = new PlaybookTools(playbookService, playbookRepository,
            TestAreas.withAreas("OIM", "PingID"), json);
    }

    @Test
    void theListCarriesEveryPickablePlaybookWithItsMetadata() throws Exception {
        when(playbookService.listSpecializedPlaybooks()).thenReturn(List.of(FULL, BARE));

        JsonNode list = json.readTree(tools.listPlaybooks(null));

        assertThat(list.isArray()).isTrue();
        assertThat(list).hasSize(2);
        JsonNode full = list.get(0);
        // The plugin parses these keys; pin the exact set so a new field is a deliberate change.
        assertThat(keys(full)).isEqualTo(new TreeSet<>(META_KEYS));
        assertThat(full.get("name").asText()).isEqualTo("oim-full");
        assertThat(full.get("title").asText()).isEqualTo("OIM full");
        assertThat(full.get("description").asText()).isEqualTo("Everything OIM");
        assertThat(json.convertValue(full.get("tools"), List.class))
            .containsExactly("search_corpus", "get_section");
        assertThat(full.get("codeExecution").asBoolean()).isTrue();
        assertThat(full.get("targetAgent").asText()).isEqualTo("specialist");
        assertThat(full.get("prototype").asBoolean()).isTrue();
        assertThat(full.get("area").asText()).isEqualTo("OIM");
        // The list is metadata only; the text costs a get_playbook call.
        assertThat(full.has("text")).isFalse();

        JsonNode bare = list.get(1);
        assertThat(bare.get("tools").isArray()).isTrue();
        assertThat(bare.get("tools")).isEmpty();
        assertThat(bare.get("targetAgent").asText()).isEqualTo("specialist");
        assertThat(bare.get("codeExecution").asBoolean()).isFalse();
        assertThat(bare.get("prototype").asBoolean()).isFalse();
    }

    @Test
    void anEmptyLibraryIsAnEmptyArrayNotAnError() throws Exception {
        when(playbookService.listSpecializedPlaybooks()).thenReturn(List.of());

        assertThat(json.readTree(tools.listPlaybooks(null)).isArray()).isTrue();
        assertThat(json.readTree(tools.listPlaybooks(null))).isEmpty();
    }

    @Test
    void aKnownPlaybookComesBackWithItsTextAndMetadata() throws Exception {
        when(playbookRepository.findByName("oim-full")).thenReturn(Optional.of(FULL));

        JsonNode pb = json.readTree(tools.getPlaybook("oim-full"));

        Set<String> expected = new TreeSet<>(META_KEYS);
        expected.add("text");
        assertThat(keys(pb)).isEqualTo(expected);

        assertThat(pb.get("name").asText()).isEqualTo("oim-full");
        assertThat(pb.get("title").asText()).isEqualTo("OIM full");
        assertThat(pb.get("description").asText()).isEqualTo("Everything OIM");
        assertThat(pb.get("text").asText()).isEqualTo("You are the OIM assistant.");
        assertThat(json.convertValue(pb.get("tools"), List.class)).containsExactly("search_corpus",
            "get_section");
        assertThat(pb.get("codeExecution").asBoolean()).isTrue();
        assertThat(pb.get("targetAgent").asText()).isEqualTo("specialist");
        assertThat(pb.get("prototype").asBoolean()).isTrue();
        assertThat(pb.get("area").asText()).isEqualTo("OIM");
    }

    @Test
    void getReadsTheTableOnEveryCallSoADeletedPlaybookIsGoneAtOnce() throws Exception {
        when(playbookRepository.findByName("oim-full")).thenReturn(Optional.of(FULL),
            Optional.empty());

        assertThat(json.readTree(tools.getPlaybook("oim-full")).get("name").asText())
            .isEqualTo("oim-full");
        assertThat(tools.getPlaybook("oim-full")).startsWith("ERROR:").contains("not found");
        verify(playbookRepository, times(2)).findByName("oim-full");
        // Not the cached PlaybookService.getPlaybook, which would keep a deleted playbook for 60s.
        verifyNoInteractions(playbookService);
    }

    @Test
    void theNameIsTrimmed() throws Exception {
        when(playbookRepository.findByName("oim-full")).thenReturn(Optional.of(FULL));

        assertThat(json.readTree(tools.getPlaybook("  oim-full ")).get("name").asText())
            .isEqualTo("oim-full");
    }

    @Test
    void anyPlaybookCanBeFetchedByNameNotOnlyListedOnes() throws Exception {
        when(playbookRepository.findByName("oim-orchestrator"))
            .thenReturn(Optional.of(ORCHESTRATOR));

        assertThat(json.readTree(tools.getPlaybook("oim-orchestrator")).get("targetAgent").asText())
            .isEqualTo("orchestrator");
    }

    @Test
    void anUnknownNameIsAnErrorBodyNamingIt() {
        when(playbookRepository.findByName("nope")).thenReturn(Optional.empty());

        assertThat(tools.getPlaybook("nope")).startsWith("ERROR:").contains("'nope'")
            .contains("not found");
    }

    @Test
    void aBlankOrMissingNameIsAnErrorBody() {
        assertThat(tools.getPlaybook(null)).startsWith("ERROR:");
        assertThat(tools.getPlaybook("")).startsWith("ERROR:");
        assertThat(tools.getPlaybook("   ")).startsWith("ERROR:");
    }

    @Test
    void anInfrastructureFailureIsAGenericErrorWithNoExceptionDetail() {
        when(playbookService.listSpecializedPlaybooks())
            .thenThrow(new IllegalStateException("jdbc:postgresql://secret-host"));
        when(playbookRepository.findByName("oim-full"))
            .thenThrow(new IllegalStateException("jdbc:postgresql://secret-host"));

        assertThat(tools.listPlaybooks(null)).startsWith("ERROR:").doesNotContain("secret-host");
        assertThat(tools.getPlaybook("oim-full")).startsWith("ERROR:")
            .doesNotContain("secret-host");
    }

    /** The area is matched leniently, then compared with the stored name each playbook holds. */
    @Test
    void anAreaListsOnlyThatAreasPlaybooks() throws Exception {
        when(playbookService.listSpecializedPlaybooks()).thenReturn(List.of(FULL, BARE));

        JsonNode oim = json.readTree(tools.listPlaybooks(" oim "));
        JsonNode ping = json.readTree(tools.listPlaybooks("PingID"));

        assertThat(oim).hasSize(1);
        assertThat(oim.get(0).get("name").asText()).isEqualTo("oim-full");
        assertThat(ping).hasSize(1);
        assertThat(ping.get(0).get("name").asText()).isEqualTo("bare");
        assertThat(json.readTree(tools.listPlaybooks("  "))).hasSize(2);
    }

    @Test
    void anUnknownAreaIsAnEmptyArray() throws Exception {
        when(playbookService.listSpecializedPlaybooks()).thenReturn(List.of(FULL, BARE));

        JsonNode list = json.readTree(tools.listPlaybooks("SAP"));

        assertThat(list.isArray()).isTrue();
        assertThat(list).isEmpty();
    }

    private static Set<String> keys(JsonNode node) {
        Set<String> keys = new TreeSet<>();
        Iterator<String> names = node.fieldNames();
        names.forEachRemaining(keys::add);
        return keys;
    }
}
