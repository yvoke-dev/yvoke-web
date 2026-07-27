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
}
