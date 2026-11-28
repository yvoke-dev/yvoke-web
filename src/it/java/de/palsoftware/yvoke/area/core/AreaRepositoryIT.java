package de.palsoftware.yvoke.area.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The areas table and the foreign keys that tie every system prompt, collection, playbook and
 * profile to one area. These rules live in the schema, so only a real database can show them.
 */
@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.locations=filesystem:docker/db/migration"
        })
class AreaRepositoryIT {

    @Autowired private AreaRepository repository;

    @Autowired private AreaService areaService;

    @Autowired private JdbcClient jdbcClient;

    @AfterEach
    void cleanUp() {
        jdbcClient.sql("DELETE FROM system_prompts WHERE name LIKE 'it-area-%'").update();
        jdbcClient.sql("DELETE FROM playbooks WHERE name LIKE 'it-area-%'").update();
        jdbcClient.sql("DROP TRIGGER IF EXISTS it_area_refuse_title ON areas").update();
        jdbcClient.sql("DROP FUNCTION IF EXISTS it_area_refuse_title()").update();
        jdbcClient.sql("DELETE FROM areas WHERE name LIKE 'IT_AREA%'").update();
    }

    @Test
    void theMigrationCreatesTheOimArea() {
        assertThat(repository.findByName("OIM")).get().extracting(Area::title).isEqualTo("OIM");
    }

    @Test
    void anAreaRoundTripsAndIsRefreshedByTheUpsert() {
        insertPlaybook("it-area-pb", "OIM");
        repository.upsert(
                new Area("IT_AREA_A", "Area A", "first", true, "it-area-pb", null, null, null));
        assertThat(repository.findByName("IT_AREA_A"))
                .get()
                .satisfies(a -> {
                    assertThat(a.title()).isEqualTo("Area A");
                    assertThat(a.description()).isEqualTo("first");
                    assertThat(a.prototype()).isTrue();
                    assertThat(a.defaultPlaybook()).isEqualTo("it-area-pb");
                });

        repository.upsert(new Area("IT_AREA_A", "Area A2", null, false, null, null, null, null));
        assertThat(repository.findByName("IT_AREA_A"))
                .get()
                .satisfies(a -> {
                    assertThat(a.title()).isEqualTo("Area A2");
                    assertThat(a.prototype()).isFalse();
                    assertThat(a.defaultPlaybook()).isNull();
                });
    }

    @Test
    void findAllIsSortedByName() {
        repository.upsert(new Area("IT_AREA_B", "B", null, false, null, null, null, null));
        repository.upsert(new Area("IT_AREA_A", "A", null, false, null, null, null, null));
        assertThat(repository.findAll())
                .extracting(Area::name)
                .containsSubsequence("IT_AREA_A", "IT_AREA_B", "OIM");
    }

    @Test
    void renamingAnAreaCarriesOverToItsMembers() {
        repository.upsert(new Area("IT_AREA_OLD", "Old", null, false, null, null, null, null));
        insertPlaybook("it-area-member", "IT_AREA_OLD");

        repository.rename("IT_AREA_OLD", "IT_AREA_NEW");

        assertThat(repository.findByName("IT_AREA_OLD")).isEmpty();
        assertThat(
                        jdbcClient
                                .sql("SELECT area FROM playbooks WHERE name = 'it-area-member'")
                                .query(String.class)
                                .single())
                .isEqualTo("IT_AREA_NEW");
    }

    @Test
    void anAreaWithMembersCannotBeDeleted() {
        repository.upsert(new Area("IT_AREA_USED", "Used", null, false, null, null, null, null));
        insertPlaybook("it-area-used", "IT_AREA_USED");

        assertThatThrownBy(() -> repository.delete("IT_AREA_USED"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(repository.findByName("IT_AREA_USED")).isPresent();
    }

    /**
     * Through the service, which runs its reads read-only: deleting must still write, and the
     * refusal must reach the admin as a named message rather than a database error.
     */
    @Test
    void theServiceDeletesAnEmptyAreaAndNamesTheRefusalForAUsedOne() {
        repository.upsert(new Area("IT_AREA_USED", "Used", null, false, null, null, null, null));
        insertPlaybook("it-area-used", "IT_AREA_USED");
        repository.upsert(new Area("IT_AREA_EMPTY", "Empty", null, false, null, null, null, null));

        assertThatThrownBy(() -> areaService.deleteArea("it_area_used"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Area 'IT_AREA_USED' still has");
        assertThat(repository.findByName("IT_AREA_USED")).isPresent();

        assertThat(areaService.deleteArea("it_area_empty")).isEqualTo("IT_AREA_EMPTY");
        assertThat(repository.findByName("IT_AREA_EMPTY")).isEmpty();
    }

    @Test
    void anEmptyAreaCanBeDeleted() {
        repository.upsert(new Area("IT_AREA_EMPTY", "Empty", null, false, null, null, null, null));
        repository.delete("IT_AREA_EMPTY");
        assertThat(repository.findByName("IT_AREA_EMPTY")).isEmpty();
    }

    @Test
    void deletingTheDefaultPlaybookClearsTheDefault() {
        insertPlaybook("it-area-default", "OIM");
        repository.upsert(
                new Area("IT_AREA_D", "D", null, false, "it-area-default", null, null, null));

        jdbcClient.sql("DELETE FROM playbooks WHERE name = 'it-area-default'").update();

        assertThat(repository.findByName("IT_AREA_D"))
                .get()
                .extracting(Area::defaultPlaybook)
                .isNull();
    }

    @Test
    void aMemberNamingAnUnknownAreaIsRefused() {
        assertThatThrownBy(() -> insertPlaybook("it-area-orphan", "IT_AREA_MISSING"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertPlaybook(String name, String area) {
        jdbcClient
                .sql(
                        """
                        INSERT INTO playbooks (name, title, template_text, area)
                        VALUES (:name, :name, 'text', :area)
                        """)
                .param("name", name)
                .param("area", area)
                .update();
    }

    /**
     * A save that renames runs two statements, the rename and the upsert. If the upsert fails the
     * rename must be undone too, or the area keeps its new name without the rest of the save. A
     * trigger refusing one title makes the second statement fail on demand.
     */
    @Test
    void aSaveWhoseSecondStatementFailsLeavesTheAreaAsItWas() {
        repository.upsert(new Area("IT_AREA_OLD", "Old", null, false, null, null, null, null));
        jdbcClient
                .sql(
                        """
                        CREATE FUNCTION it_area_refuse_title() RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN
                            IF NEW.title = 'it-refused' THEN RAISE EXCEPTION 'refused by test'; END IF;
                            RETURN NEW;
                        END $$\
                        """)
                .update();
        jdbcClient
                .sql(
                        """
                        CREATE TRIGGER it_area_refuse_title BEFORE INSERT OR UPDATE ON areas
                        FOR EACH ROW EXECUTE FUNCTION it_area_refuse_title()\
                        """)
                .update();

        assertThatThrownBy(() ->
                        areaService.saveArea(
                                "IT_AREA_OLD",
                                "IT_AREA_NEW",
                                "it-refused",
                                null,
                                false,
                                null,
                                null))
                .isInstanceOf(DataAccessException.class);

        assertThat(repository.findByName("IT_AREA_OLD"))
                .get()
                .extracting(Area::title)
                .isEqualTo("Old");
        assertThat(repository.findByName("IT_AREA_NEW")).isEmpty();
    }

    @Test
    void findAllMembersExcludesRetiredChatSystemPrompts() {
        repository.upsert(new Area("IT_AREA_P", "Prompts", null, false, null, null, null, null));
        jdbcClient
                .sql(
                        """
                        INSERT INTO system_prompts (name, type, system_prompt, description, area)
                        VALUES ('it-area-kg', 'KG', 'kg body', 'kg', 'IT_AREA_P'),
                               ('it-area-chat', 'CHAT', 'chat body', 'retired', 'IT_AREA_P')
                        """)
                .update();

        AreaMembers members = repository.findAllMembers().get("IT_AREA_P");
        assertThat(members).isNotNull();
        assertThat(members.systemPrompts())
                .containsExactly("it-area-kg")
                .doesNotContain("it-area-chat");
    }

    @Test
    void deleteAreaSucceedsWhenOnlyRetiredChatPromptsRemain() {
        repository.upsert(
                new Area("IT_AREA_CHAT_ONLY", "Legacy", null, false, null, null, null, null));
        jdbcClient
                .sql(
                        """
                        INSERT INTO system_prompts (name, type, system_prompt, description, area)
                        VALUES ('it-area-only-chat', 'CHAT', 'legacy body', 'retired', 'IT_AREA_CHAT_ONLY')
                        """)
                .update();

        assertThat(
                        repository
                                .findAllMembers()
                                .getOrDefault("IT_AREA_CHAT_ONLY", AreaMembers.NONE)
                                .systemPrompts())
                .isEmpty();

        assertThat(areaService.deleteArea("IT_AREA_CHAT_ONLY")).isEqualTo("IT_AREA_CHAT_ONLY");
        assertThat(repository.findByName("IT_AREA_CHAT_ONLY")).isEmpty();
        assertThat(
                        jdbcClient
                                .sql(
                                        "SELECT count(*) FROM system_prompts WHERE name = 'it-area-only-chat'")
                                .query(Integer.class)
                                .single())
                .isZero();
    }

    @Test
    void deleteAreaRollsBackRetiredChatPromptCleanupWhenRealMemberBlocksDelete() {
        repository.upsert(new Area("IT_AREA_MIXED", "Mixed", null, false, null, null, null, null));
        jdbcClient
                .sql(
                        """
                        INSERT INTO system_prompts (name, type, system_prompt, description, area)
                        VALUES ('it-area-mixed-chat', 'CHAT', 'legacy body', 'retired', 'IT_AREA_MIXED')
                        """)
                .update();
        jdbcClient
                .sql(
                        """
                        INSERT INTO playbooks (name, title, template_text, area, system_prompt)
                        VALUES ('it-area-mixed-pb', 'Mixed PB', 'text', 'IT_AREA_MIXED', 'it-area-mixed-chat')
                        """)
                .update();

        assertThatThrownBy(() -> areaService.deleteArea("IT_AREA_MIXED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Area 'IT_AREA_MIXED' still has");

        assertThat(repository.findByName("IT_AREA_MIXED")).isPresent();
        assertThat(
                        jdbcClient
                                .sql(
                                        "SELECT count(*) FROM system_prompts WHERE name = 'it-area-mixed-chat'")
                                .query(Integer.class)
                                .single())
                .isEqualTo(1);
        assertThat(
                        jdbcClient
                                .sql(
                                        "SELECT system_prompt FROM playbooks WHERE name = 'it-area-mixed-pb'")
                                .query(String.class)
                                .single())
                .isEqualTo("it-area-mixed-chat");
    }
}
