package de.palsoftware.yvoke.chat.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=filesystem:docker/db/migration"
})
public class OrchestratorProfileRepositoryIT {

    @Autowired
    private OrchestratorProfileRepository repository;

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
}
