package de.palsoftware.yvoke.area.core;

import static org.assertj.core.api.Assertions.assertThat;

import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfile;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfileRepository;
import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.repository.CollectionRepository;
import de.palsoftware.yvoke.rag.prompt.Playbook;
import de.palsoftware.yvoke.rag.prompt.PlaybookRepository;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import de.palsoftware.yvoke.rag.prompt.SystemPrompt;
import de.palsoftware.yvoke.rag.prompt.SystemPromptRepository;
import de.palsoftware.yvoke.rag.prompt.SystemPromptType;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Each member repository writes and reads its item's area, and the conflict branch of each upsert
 * refreshes it: a column left out of an {@code ON CONFLICT} list is written once and then frozen,
 * so an item could never be moved to another area, with no error anywhere.
 */
@SpringBootTest(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=filesystem:docker/db/migration"
})
class AreaMembersIT {

    private static final String OTHER = "IT_AREA_MEMBERS";

    @Autowired
    private AreaRepository areaRepository;
    @Autowired
    private PlaybookRepository playbookRepository;
    @Autowired
    private SystemPromptRepository systemPromptRepository;
    @Autowired
    private CollectionRepository collectionRepository;
    @Autowired
    private OrchestratorProfileRepository profileRepository;
    @Autowired
    private PlaybookService playbookService;
    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void createOtherArea() {
        areaRepository.upsert(new Area(OTHER, "Other", null, false, null, null, null, null, null));
    }

    @AfterEach
    void cleanUp() {
        jdbcClient.sql("DELETE FROM playbooks WHERE name LIKE 'it-members-%'").update();
        jdbcClient.sql("DELETE FROM system_prompts WHERE name LIKE 'it-members-%'").update();
        jdbcClient.sql("DELETE FROM collections WHERE name LIKE 'it-members-%'").update();
        jdbcClient.sql("DELETE FROM orchestrator_profiles WHERE name LIKE 'it-members-%'").update();
        areaRepository.delete(OTHER);
    }

    @Test
    void aPlaybooksAreaRoundTripsAndCanBeMoved() {
        playbookRepository.upsert("it-members-pb", "PB", "", "text", List.of(), false,
            "specialist", false, "OIM");
        assertThat(playbookRepository.findByName("it-members-pb")).get()
            .extracting(Playbook::area).isEqualTo("OIM");

        playbookRepository.upsert("it-members-pb", "PB", "", "text", List.of(), false,
            "specialist", false, OTHER);
        assertThat(playbookRepository.findByName("it-members-pb")).get()
            .extracting(Playbook::area).isEqualTo(OTHER);
        assertThat(playbookRepository.findAll()).filteredOn(p -> p.name().equals("it-members-pb"))
            .extracting(Playbook::area).containsExactly(OTHER);
    }

    @Test
    void aSystemPromptsAreaRoundTripsAndCanBeMoved() {
        systemPromptRepository.upsert("it-members-sp", SystemPromptType.CHAT, "text", null, "OIM");
        systemPromptRepository.upsert("it-members-sp", SystemPromptType.CHAT, "text", null, OTHER);

        assertThat(systemPromptRepository.findByName("it-members-sp")).get()
            .extracting(SystemPrompt::area).isEqualTo(OTHER);
        assertThat(systemPromptRepository.findAll())
            .filteredOn(p -> p.name().equals("it-members-sp")).extracting(SystemPrompt::area)
            .containsExactly(OTHER);
        assertThat(systemPromptRepository.findByType(SystemPromptType.CHAT))
            .filteredOn(p -> p.name().equals("it-members-sp")).extracting(SystemPrompt::area)
            .containsExactly(OTHER);
    }

    @Test
    void aCollectionsAreaRoundTripsAndCanBeMoved() {
        Collection created = collectionRepository.create("it-members-col", null, "OIM");
        assertThat(created.area()).isEqualTo("OIM");

        collectionRepository.updateArea("it-members-col", OTHER);

        assertThat(collectionRepository.findByName("it-members-col")).get()
            .extracting(Collection::area).isEqualTo(OTHER);
        assertThat(collectionRepository.findById(created.id())).get()
            .extracting(Collection::area).isEqualTo(OTHER);
        assertThat(collectionRepository.findAll())
            .filteredOn(c -> c.name().equals("it-members-col")).extracting(Collection::area)
            .containsExactly(OTHER);
    }

    @Test
    void aProfilesAreaRoundTripsAndCanBeMoved() {
        profileRepository.upsert(profile("OIM"));
        profileRepository.upsert(profile(OTHER));

        assertThat(profileRepository.findByName("it-members-prof")).get()
            .extracting(OrchestratorProfile::area).isEqualTo(OTHER);
        assertThat(profileRepository.findAll())
            .filteredOn(p -> p.name().equals("it-members-prof"))
            .extracting(OrchestratorProfile::area).containsExactly(OTHER);
    }

    private static OrchestratorProfile profile(String area) {
        return new OrchestratorProfile("it-members-prof", 2, 8, "orch", "rev", List.of(), null,
            null, null, null, null, null, false, null, null, area);
    }

    /**
     * One membership query feeds list_areas, get_system_prompt's area default and the area admin
     * page. Its playbook rule must match list_playbooks (PlaybookService.listSpecializedPlaybooks):
     * orchestrator and reviewer playbooks are left out, any other role or none is pickable.
     */
    @Test
    void membersAreListedPerAreaWithOnlyChatPromptsAndPickablePlaybooks() {
        systemPromptRepository.upsert("it-members-chat", SystemPromptType.CHAT, "t", null, OTHER);
        systemPromptRepository.upsert("it-members-kg", SystemPromptType.KG, "t", null, OTHER);
        collectionRepository.create("it-members-col", "", OTHER);
        playbookRepository.upsert("it-members-spec", "S", "", "t", List.of(), false, "specialist",
            false, OTHER);
        playbookRepository.upsert("it-members-orch", "O", "", "t", List.of(), false,
            "Orchestrator", false, OTHER);
        playbookRepository.upsert("it-members-rev", "R", "", "t", List.of(), false, "reviewer",
            false, OTHER);
        jdbcClient.sql("""
            INSERT INTO playbooks (name, title, template_text, target_agent, area)
            VALUES ('it-members-none', 'N', 't', '', :area)""").param("area", OTHER).update();
        profileRepository.upsert(profile(OTHER));

        AreaMembers members = areaRepository.findAllMembers().get(OTHER);

        assertThat(members.systemPrompts()).containsExactly("it-members-chat");
        assertThat(members.collections()).containsExactly("it-members-col");
        assertThat(members.playbooks()).containsExactly("it-members-none", "it-members-spec");
        assertThat(members.profiles()).containsExactly("it-members-prof");
        assertThat(areaRepository.findAllMembers().getOrDefault("OIM", AreaMembers.NONE).playbooks())
            .doesNotContain("it-members-spec");
    }

    /**
     * The pickable-playbook rule exists twice: in SQL here and in Java in
     * {@code PlaybookService.listSpecializedPlaybooks} ({@code area} may not depend on {@code rag}).
     * This compares the two over every kind of role, so changing one without the other fails.
     */
    @Test
    void thePlaybookMembersMatchWhatListPlaybooksOffers() {
        String[] roles = {"specialist", "Specialist", "orchestrator", "ORCHESTRATOR", "reviewer",
            "Reviewer", "", "custom-role", " reviewer "};
        for (int i = 0; i < roles.length; i++) {
            jdbcClient.sql("""
                INSERT INTO playbooks (name, title, template_text, target_agent, area)
                VALUES (:name, 'T', 't', :role, :area)""").param("name", "it-members-role-" + i)
                .param("role", roles[i]).param("area", OTHER).update();
        }

        List<String> offered = playbookService.listSpecializedPlaybooks().stream()
            .filter(p -> OTHER.equals(p.area())).map(Playbook::name).toList();

        assertThat(areaRepository.findAllMembers().get(OTHER).playbooks())
            .containsExactlyInAnyOrderElementsOf(offered)
            .isNotEmpty();
        assertThat(offered).hasSizeBetween(1, roles.length - 1);
    }
}
