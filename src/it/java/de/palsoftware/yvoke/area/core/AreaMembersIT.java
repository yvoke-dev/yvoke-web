package de.palsoftware.yvoke.area.core;

import static org.assertj.core.api.Assertions.assertThat;

import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfile;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfileRepository;
import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.repository.CollectionRepository;
import de.palsoftware.yvoke.rag.prompt.Playbook;
import de.palsoftware.yvoke.rag.prompt.PlaybookRepository;
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
}
