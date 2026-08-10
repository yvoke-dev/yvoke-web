package de.palsoftware.yvoke.chat.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties.Profile;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties.RoleConfig;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties.RoleDefaults;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class OrchestratorProfileServiceTest {

    private OrchestratorProfileRepository repository;
    private OrchestratorProperties properties;
    private OrchestratorProfileService service;

    @BeforeEach
    void setUp() {
        repository = mock(OrchestratorProfileRepository.class);
        properties = new OrchestratorProperties(2, 8,
            new RoleDefaults(new RoleConfig("gemini-3.1-pro", "high"),
                new RoleConfig("gemini-3.1-pro", "high"),
                new RoleConfig("gemini-3.1-flash", "medium")),
            List.of(
                new Profile("OIM", "oim-orch", "oim-rev", List.of("spec-1"), null, null, null)));
        service = new OrchestratorProfileService(repository, properties);
    }

    @Test
    void testListAllProfiles() {
        OrchestratorProfile profile =
            new OrchestratorProfile("TestProfile", 3, 10, "orch-pb", "rev-pb", List.of("spec-pb"),
                "model-a", "high", "model-b", "high", "model-c", "low", null, null);
        when(repository.findAll()).thenReturn(List.of(profile));

        List<OrchestratorProfile> result = service.listAllProfiles();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("TestProfile");
        assertThat(result.get(0).maxReviewRounds()).isEqualTo(3);
        assertThat(result.get(0).maxSpecialistCalls()).isEqualTo(10);
    }

    @Test
    void testResolveProfileFromDb() {
        OrchestratorProfile profile = new OrchestratorProfile("OIM", 4, 12, "oim-orch", "oim-rev",
            List.of("spec-1"), "custom-orch-model", "high", "custom-rev-model", "high",
            "custom-spec-model", "low", null, null);
        when(repository.findByName("OIM")).thenReturn(Optional.of(profile));

        ResolvedProfile resolved = service.resolve("OIM");

        assertThat(resolved.name()).isEqualTo("OIM");
        assertThat(resolved.maxReviewRounds()).isEqualTo(4);
        assertThat(resolved.maxSpecialistCalls()).isEqualTo(12);
        assertThat(resolved.orchestrator().model()).isEqualTo("custom-orch-model");
        assertThat(resolved.reviewer().model()).isEqualTo("custom-rev-model");
        assertThat(resolved.specialist().model()).isEqualTo("custom-spec-model");
    }

    /**
     * The two caps are what stop a run running away, and a zero is not a tighter budget — it is a
     * disabled agent. {@code maxSpecialistCalls=0} makes {@code specialistCalls >= maxCalls} true
     * before the FIRST delegation, so every {@code call_specialist} comes back "budget exhausted"
     * and the orchestrator answers from model weights with no retrieval at all;
     * {@code maxReviewRounds=0} makes {@code round >= maxReviewRounds} true at the first verdict,
     * so a rejected answer is never revised and goes straight out {@code delivered_flagged}. Both
     * runs look entirely normal from outside — steps written, tokens accounted, a status the UI
     * knows — which is why this has to be caught at resolution.
     *
     * <p>
     * And zero is not hypothetical: the columns are plain {@code NOT NULL} ints, an admin form
     * posting an empty number field binds to 0, and an imported profile JSON that simply omits the
     * fields deserializes to 0. The sibling resolve test uses 4 and 12, so the fallback branch is
     * never taken there; a check loosened to {@code >= 0} would keep it green while handing every
     * such profile a dead orchestrator.
     */
    @Test
    void nonPositiveCapsInheritThePropertyDefaults() {
        OrchestratorProfile uncapped = new OrchestratorProfile("OIM", 0, 0, "oim-orch", "oim-rev",
            List.of("spec-1"), "model-a", "high", "model-b", "high", "model-c", "low", null, null);
        when(repository.findByName("OIM")).thenReturn(Optional.of(uncapped));

        ResolvedProfile resolved = service.resolve("OIM");

        assertThat(resolved.maxReviewRounds())
            .as("0 rounds would deliver every rejection flagged without ever revising")
            .isEqualTo(2);
        assertThat(resolved.maxSpecialistCalls())
            .as("0 calls would refuse the first delegation and strip the run of all retrieval")
            .isEqualTo(8);

        OrchestratorProfile negative = new OrchestratorProfile("OIM", -1, -5, "oim-orch", "oim-rev",
            List.of("spec-1"), "model-a", "high", "model-b", "high", "model-c", "low", null, null);
        when(repository.findByName("OIM")).thenReturn(Optional.of(negative));

        ResolvedProfile fromNegative = service.resolve("OIM");

        assertThat(fromNegative.maxReviewRounds()).isEqualTo(2);
        assertThat(fromNegative.maxSpecialistCalls()).isEqualTo(8);
    }

    @Test
    void testSaveAndDeleteProfile() {
        OrchestratorProfile profile = new OrchestratorProfile("NewProfile", 2, 8, "orch", "rev",
            List.of("spec"), null, null, null, null, null, null, null, null);

        service.saveProfile(profile);
        verify(repository).upsert(profile);

        service.deleteProfile("NewProfile");
        verify(repository).delete("NewProfile");
    }

    @Test
    void testExportAndImportProfile() {
        OrchestratorProfile profile = new OrchestratorProfile("ExportImportProfile", 3, 9,
            "orch-pb", "rev-pb", List.of("spec-1", "spec-2"), "model-a", "high", "model-b", "low",
            "model-c", "medium", null, null);
        when(repository.findByName("ExportImportProfile")).thenReturn(Optional.of(profile));

        String json = service.exportProfileToJson("ExportImportProfile");
        assertThat(json).contains("\"name\" : \"ExportImportProfile\"");
        assertThat(json).contains("\"maxReviewRounds\" : 3");
        assertThat(json).contains("\"maxSpecialistCalls\" : 9");

        OrchestratorProfile imported = service.importProfileFromJson(json);
        assertThat(imported.name()).isEqualTo("ExportImportProfile");
        assertThat(imported.maxReviewRounds()).isEqualTo(3);
        assertThat(imported.specialistPlaybooks()).containsExactly("spec-1", "spec-2");
        verify(repository).upsert(any(OrchestratorProfile.class));
    }

    /**
     * An EMPTY STRING per-role model or thinking level must inherit the configured default, exactly
     * as a null does.
     *
     * <p>
     * This is not defensive nulling — empty string is the value the admin form actually produces. A
     * select with no choice made posts {@code ""}, not {@code null}, so the column stores
     * {@code ""} and a null-only check would hand that straight through as the model name. The
     * provider then rejects the call, or worse the empty thinking level silently disables reasoning
     * for that role, and the profile appears configured while behaving nothing like its defaults.
     */
    @Test
    void emptyStringModelAndThinkingLevelInheritTheRoleDefaults() {
        OrchestratorProfile blanks = new OrchestratorProfile("OIM", 4, 12, "oim-orch", "oim-rev",
            List.of("spec-1"), "", "", "", "", "", "", null, null);
        when(repository.findByName("OIM")).thenReturn(Optional.of(blanks));

        ResolvedProfile resolved = service.resolve("OIM");

        assertThat(resolved.orchestrator().model()).isEqualTo("gemini-3.1-pro");
        assertThat(resolved.orchestrator().thinkingLevel()).isEqualTo("high");
        assertThat(resolved.reviewer().model()).isEqualTo("gemini-3.1-pro");
        assertThat(resolved.reviewer().thinkingLevel()).isEqualTo("high");
        assertThat(resolved.specialist().model()).isEqualTo("gemini-3.1-flash");
        assertThat(resolved.specialist().thinkingLevel()).isEqualTo("medium");
    }
}
