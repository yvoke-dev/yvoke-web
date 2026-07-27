package de.palsoftware.yvoke.chat.api.model;

import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfile;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties.Profile;
import java.util.List;

/**
 * Structure of a multi-agent profile exposed to the desktop client: the profile name and the three
 * playbook roles. Per-role model/thinking is deliberately omitted — the server profiles reference
 * Gemini models, while the desktop binds its own Claude models per role locally.
 */
public record OrchestratorProfileDto(String name,String orchestratorPlaybook,String reviewerPlaybook,List<String>specialistPlaybooks){public static OrchestratorProfileDto from(Profile profile){return new OrchestratorProfileDto(profile.name(),profile.orchestratorPlaybook(),profile.reviewerPlaybook(),profile.specialistPlaybooks()!=null?profile.specialistPlaybooks():List.of());}

public static OrchestratorProfileDto fromDbProfile(OrchestratorProfile profile){return new OrchestratorProfileDto(profile.name(),profile.orchestratorPlaybook(),profile.reviewerPlaybook(),profile.specialistPlaybooks()!=null?profile.specialistPlaybooks():List.of());}}
