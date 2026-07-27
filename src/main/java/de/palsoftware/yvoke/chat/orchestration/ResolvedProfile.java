package de.palsoftware.yvoke.chat.orchestration;

import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties.RoleConfig;
import java.util.List;

/**
 * A fully resolved multi-agent profile: playbook names for each role, the resolved model/thinking
 * for each role (profile override merged over global defaults), and the run caps.
 */
public record ResolvedProfile(String name,String orchestratorPlaybook,String reviewerPlaybook,List<String>specialistPlaybooks,RoleConfig orchestrator,RoleConfig reviewer,RoleConfig specialist,int maxReviewRounds,int maxSpecialistCalls){}
