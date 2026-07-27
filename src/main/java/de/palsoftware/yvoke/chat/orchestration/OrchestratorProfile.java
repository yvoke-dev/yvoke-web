package de.palsoftware.yvoke.chat.orchestration;

import java.time.Instant;
import java.util.List;

/**
 * Domain model record representing an orchestrator profile with per-profile settings.
 */
public record OrchestratorProfile(String name,int maxReviewRounds,int maxSpecialistCalls,String orchestratorPlaybook,String reviewerPlaybook,List<String>specialistPlaybooks,String orchestratorModel,String orchestratorThinkingLevel,String reviewerModel,String reviewerThinkingLevel,String specialistModel,String specialistThinkingLevel,Instant createdAt,Instant updatedAt){}
