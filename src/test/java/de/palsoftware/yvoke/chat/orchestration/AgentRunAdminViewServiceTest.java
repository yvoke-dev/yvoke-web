package de.palsoftware.yvoke.chat.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.chat.orchestration.AgentRunAdminViews.RunDetailPage;
import de.palsoftware.yvoke.chat.orchestration.AgentRunAdminViews.StepView;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentRunAdminViewServiceTest {

    @Test
    void runDetail_mapsStepPlaybookVersionOntoStepView() {
        AgentRunRepository runRepo = mock(AgentRunRepository.class);
        AgentStepRepository stepRepo = mock(AgentStepRepository.class);
        AgentRunAdminViewService service = new AgentRunAdminViewService(runRepo, stepRepo);

        UUID runId = UUID.randomUUID();
        UUID convId = UUID.randomUUID();
        UUID msgId = UUID.randomUUID();

        AgentRun run = new AgentRun(runId, convId, msgId, msgId, "OIM", "done", "{}", 0,
            "{\"approved\":true}", 10, 20, 30, 0, 0, null, Instant.now(), Instant.now());
        AgentStep step1 = new AgentStep(UUID.randomUUID(), runId, 0, "orchestrator", 0,
            "oim-orchestrator", 3, "gemini-2.5-pro", "high", "in", "out", "[]", null, 5, 10, 15, 0,
            0, Instant.now(), "ok", null);

        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(stepRepo.findByRunId(runId)).thenReturn(List.of(step1));

        Optional<RunDetailPage> pageOpt = service.runDetail(runId);
        assertThat(pageOpt).isPresent();
        List<StepView> steps = pageOpt.get().steps();
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).playbookName()).isEqualTo("oim-orchestrator");
        assertThat(steps.get(0).playbookVersion()).isEqualTo(3);
    }
}
