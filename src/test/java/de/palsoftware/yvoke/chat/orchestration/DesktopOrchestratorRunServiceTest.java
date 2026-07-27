package de.palsoftware.yvoke.chat.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.chat.api.model.OrchestratorRunRequest;
import de.palsoftware.yvoke.chat.api.model.OrchestratorRunRequest.Step;
import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.repository.ConversationRepository;
import de.palsoftware.yvoke.shared.user.model.User;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for {@link DesktopOrchestratorRunService}. The desktop client posts finished MAS runs
 * with a caller-supplied {@code conversationId}; if the ownership check regresses a user could
 * write {@code agent_runs}/{@code agent_steps} against another user's conversation (a write-side
 * IDOR whose contents surface in the admin trace viewer). Every sibling chat service has this test
 * — this newer one was the gap, so these lock in the deny/404/validation branches and the
 * happy-path writes.
 */
class DesktopOrchestratorRunServiceTest {

    private ConversationRepository conversationRepository;
    private AgentRunRepository agentRunRepository;
    private AgentStepRepository agentStepRepository;
    private DesktopOrchestratorRunService service;

    private final UUID currentUserId = UUID.randomUUID();
    private final User currentUser =
        new User(currentUserId, "entra-oid", "user@test.local", "Test User", Instant.now());

    @BeforeEach
    void setUp() {
        conversationRepository = mock(ConversationRepository.class);
        agentRunRepository = mock(AgentRunRepository.class);
        agentStepRepository = mock(AgentStepRepository.class);
        service = new DesktopOrchestratorRunService(conversationRepository, agentRunRepository,
            agentStepRepository);
    }

    private void ownsConversation(UUID id) {
        when(conversationRepository.findById(id)).thenReturn(Optional.of(new Conversation(id,
            currentUserId, "Mine", Map.of(), Instant.now(), Instant.now(), List.of())));
    }

    private void foreignConversation(UUID id) {
        when(conversationRepository.findById(id)).thenReturn(Optional.of(new Conversation(id,
            UUID.randomUUID(), "Theirs", Map.of(), Instant.now(), Instant.now(), List.of())));
    }

    /**
     * A request that passes validation: non-null conversationId + profileName, everything else
     * null.
     */
    private static OrchestratorRunRequest requestFor(UUID conversationId, List<Step> steps) {
        return new OrchestratorRunRequest(conversationId, UUID.randomUUID(), "oim-profile", null,
            null, null, null, null, null, null, null, null, null, steps);
    }

    private static void assertStatus(Throwable t, HttpStatus status) {
        assertThat(t).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) t).getStatusCode()).isEqualTo(status);
    }

    // --- validation --------------------------------------------------------------

    @Test
    void nullRequestIsRejected() {
        assertThatThrownBy(() -> service.record(currentUser, null))
            .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        verifyNoInteractions(conversationRepository, agentRunRepository, agentStepRepository);
    }

    @Test
    void missingConversationIdIsRejected() {
        OrchestratorRunRequest req = requestFor(null, List.of());

        assertThatThrownBy(() -> service.record(currentUser, req))
            .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        verifyNoInteractions(conversationRepository, agentRunRepository, agentStepRepository);
    }

    @Test
    void blankProfileNameIsRejected() {
        OrchestratorRunRequest req = new OrchestratorRunRequest(UUID.randomUUID(), null, "  ", null,
            null, null, null, null, null, null, null, null, null, List.of());

        assertThatThrownBy(() -> service.record(currentUser, req))
            .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        verifyNoInteractions(conversationRepository, agentRunRepository, agentStepRepository);
    }

    // --- ownership (the security-critical branch) --------------------------------

    @Test
    void unknownConversationYields404() {
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.record(currentUser, requestFor(conversationId, List.of())))
            .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
        verifyNoInteractions(agentRunRepository, agentStepRepository);
    }

    @Test
    void foreignConversationIsDeniedAndPersistsNothing() {
        UUID conversationId = UUID.randomUUID();
        foreignConversation(conversationId);
        OrchestratorRunRequest req = requestFor(conversationId, List.of(new Step(0, "specialist", 0,
            "pb", "m", "high", "in", "out", null, null, 1, 2, 3, 0, 0)));

        assertThatThrownBy(() -> service.record(currentUser, req))
            .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(agentRunRepository, agentStepRepository);
    }

    // --- happy path --------------------------------------------------------------

    @Test
    void ownedRunPersistsRunAndReturnsItsId() {
        UUID conversationId = UUID.randomUUID();
        ownsConversation(conversationId);
        UUID messageId = UUID.randomUUID();
        OrchestratorRunRequest req = new OrchestratorRunRequest(conversationId, messageId,
            "oim-profile", "done", Map.of("k", "v"), 2, Map.of("approved", true), 100, 50, 150, 10,
            5, null, List.of());

        UUID runId = service.record(currentUser, req);

        assertThat(runId).isNotNull();
        verify(agentRunRepository).create(eq(runId), eq(conversationId), eq("oim-profile"),
            eq(Map.of("k", "v")));
        verify(agentRunRepository).finish(eq(runId), eq(messageId), eq("done"), eq(2),
            eq(Map.of("approved", true)), eq(100), eq(50), eq(150), eq(10), eq(5), isNull());
        verifyNoInteractions(agentStepRepository);
    }

    @Test
    void nullStatusAndNullStepsUseDefaults() {
        UUID conversationId = UUID.randomUUID();
        ownsConversation(conversationId);

        // status null -> "done"; steps null -> no step inserts (no NPE on the null list).
        service.record(currentUser, requestFor(conversationId, null));

        verify(agentRunRepository).finish(any(), any(), eq("done"), anyInt(), any(), anyInt(),
            anyInt(), anyInt(), anyInt(), anyInt(), any());
        verifyNoInteractions(agentStepRepository);
    }

    @Test
    void stepsWithNullFieldsGetAutoSequenceAndDefaults() {
        UUID conversationId = UUID.randomUUID();
        ownsConversation(conversationId);
        // Two steps with null seq/role/round -> autoSeq 0,1 ; role "specialist" ; round 0.
        Step s0 = new Step(null, null, null, "pb0", "m", "high", "in0", "out0", null, null, null,
            null, null, null, null);
        Step s1 = new Step(null, null, null, "pb1", "m", "high", "in1", "out1", null, null, null,
            null, null, null, null);

        UUID runId = service.record(currentUser, requestFor(conversationId, List.of(s0, s1)));

        verify(agentStepRepository).insert(any(UUID.class), eq(runId), eq(0), eq("specialist"),
            eq(0), eq("pb0"), any(), any(), eq("in0"), eq("out0"), any(), any(), anyInt(), anyInt(),
            anyInt(), anyInt(), anyInt());
        verify(agentStepRepository).insert(any(UUID.class), eq(runId), eq(1), eq("specialist"),
            eq(0), eq("pb1"), any(), any(), eq("in1"), eq("out1"), any(), any(), anyInt(), anyInt(),
            anyInt(), anyInt(), anyInt());
    }
}
