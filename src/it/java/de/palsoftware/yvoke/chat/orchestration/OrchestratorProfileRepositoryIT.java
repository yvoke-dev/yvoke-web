package de.palsoftware.yvoke.chat.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=filesystem:docker/db/migration"
})
public class OrchestratorProfileRepositoryIT {

    @Autowired
    private OrchestratorProfileRepository repository;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void testUpsertAndFindByNameAndDelete() {
        String profileName = "IT_Test_Profile";
        OrchestratorProfile profile = new OrchestratorProfile(
            profileName,
            4,
            12,
            "it-orch-playbook",
            "it-rev-playbook",
            List.of("it-spec-1", "it-spec-2"),
            "gemini-3.1-pro-preview",
            "high",
            "gemini-3.1-pro-preview",
            "high",
            "gemini-3.1-flash-lite",
            "medium",
            false,
            null,
            null
        );

        repository.upsert(profile);

        Optional<OrchestratorProfile> fetched = repository.findByName(profileName);
        assertThat(fetched).isPresent();
        assertThat(fetched.get().maxReviewRounds()).isEqualTo(4);
        assertThat(fetched.get().maxSpecialistCalls()).isEqualTo(12);
        assertThat(fetched.get().orchestratorPlaybook()).isEqualTo("it-orch-playbook");
        assertThat(fetched.get().reviewerPlaybook()).isEqualTo("it-rev-playbook");
        assertThat(fetched.get().specialistPlaybooks()).containsExactly("it-spec-1", "it-spec-2");

        repository.delete(profileName);
        assertThat(repository.findByName(profileName)).isEmpty();
    }

    /**
     * The prototype flag survives a write, a read and — the part worth pinning — a re-write.
     *
     * <p>
     * {@code upsert} is one statement whose {@code ON CONFLICT} branch lists the columns it
     * refreshes by hand, so a column left out of that list is written once on INSERT and then
     * frozen: an admin could mark a profile prototype and never un-mark it, or edit any other
     * field of a prototype profile and silently publish it to every user. Neither shows an error,
     * and {@code findAll}/{@code findByName} both keep returning whatever the first insert put
     * there. So both directions are re-written here, and both are read back.
     */
    @Test
    void thePrototypeFlagRoundTripsAndIsRefreshedByTheUpsertsConflictBranch() {
        String profileName = "IT_Prototype_Profile";
        repository.upsert(new OrchestratorProfile(profileName, 2, 8, "it-orch", "it-rev",
            List.of("it-spec"), null, null, null, null, null, null, true, null, null));

        assertThat(repository.findByName(profileName)).get()
            .extracting(OrchestratorProfile::prototype).isEqualTo(true);
        assertThat(repository.findAll()).filteredOn(p -> profileName.equals(p.name()))
            .extracting(OrchestratorProfile::prototype).containsExactly(true);

        repository.upsert(new OrchestratorProfile(profileName, 2, 8, "it-orch", "it-rev",
            List.of("it-spec"), null, null, null, null, null, null, false, null, null));

        assertThat(repository.findByName(profileName)).get()
            .extracting(OrchestratorProfile::prototype)
            .as("un-marking a prototype must not be frozen out by the ON CONFLICT column list")
            .isEqualTo(false);

        repository.delete(profileName);
    }

    /**
     * A profile written by anything that predates the flag — the exports repo's importer, a
     * hand-run INSERT, a restored dump — is stored as FALSE, not NULL.
     *
     * <p>
     * The Java-side assertion alone would prove nothing: {@code ResultSet.getBoolean} maps SQL NULL
     * to {@code false}, so a nullable column with no default passes it while leaving NULLs in the
     * table. That difference is not cosmetic — SQL's three-valued logic drops NULL rows from both
     * {@code WHERE prototype} and {@code WHERE NOT prototype}, so a profile written by any of those
     * paths would vanish from a filtered admin query while still appearing in the unfiltered list.
     * Hence the assertion on what the DATABASE holds, which is what {@code V9}'s
     * {@code NOT NULL DEFAULT FALSE} is actually for.
     */
    @Test
    void aRowInsertedWithoutTheColumnIsStoredAsFalseRatherThanNull() {
        String profileName = "IT_Legacy_Profile";
        jdbcClient.sql("""
            INSERT INTO orchestrator_profiles (name, orchestrator_playbook, reviewer_playbook)
            VALUES (:name, 'it-orch', 'it-rev')
            """).param("name", profileName).update();

        assertThat(jdbcClient
            .sql("SELECT prototype IS NULL FROM orchestrator_profiles WHERE name = :name")
            .param("name", profileName).query(Boolean.class).single())
            .as("a NULL here is invisible to every WHERE clause that filters on the flag")
            .isFalse();
        assertThat(repository.findByName(profileName)).get()
            .extracting(OrchestratorProfile::prototype).isEqualTo(false);

        repository.delete(profileName);
    }
}
