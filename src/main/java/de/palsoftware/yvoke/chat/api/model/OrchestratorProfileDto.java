package de.palsoftware.yvoke.chat.api.model;

import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfile;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties.Profile;
import java.util.List;

/**
 * Structure of a multi-agent profile exposed to the desktop client: the profile name, the three
 * playbook roles and whether it is a prototype. Per-role model/thinking is deliberately omitted —
 * the server profiles reference Gemini models, while the desktop binds its own Claude models per
 * role locally.
 *
 * <p>
 * Prototype profiles are sent flagged rather than withheld, exactly as {@link PlaybookDto} sends
 * prototype playbooks: the desktop owns the visibility setting, and a thread already bound to a
 * prototype profile must still resolve it.
 */
public record OrchestratorProfileDto(String name,String orchestratorPlaybook,String reviewerPlaybook,List<String>specialistPlaybooks,boolean prototype){public static OrchestratorProfileDto from(Profile profile){return new OrchestratorProfileDto(profile.name(),profile.orchestratorPlaybook(),profile.reviewerPlaybook(),profile.specialistPlaybooks()!=null?profile.specialistPlaybooks():List.of(),false);}

public static OrchestratorProfileDto fromDbProfile(OrchestratorProfile profile){return new OrchestratorProfileDto(profile.name(),profile.orchestratorPlaybook(),profile.reviewerPlaybook(),profile.specialistPlaybooks()!=null?profile.specialistPlaybooks():List.of(),profile.prototype());}}
