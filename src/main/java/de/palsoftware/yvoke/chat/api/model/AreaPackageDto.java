package de.palsoftware.yvoke.chat.api.model;

import de.palsoftware.yvoke.area.core.AreaWithMembers;
import java.util.List;

public record AreaPackageDto(String name,String title,String description,boolean prototype,String defaultSystemPrompt,String defaultPlaybook,String defaultProfile,List<String>systemPrompts,List<String>collections,List<String>playbooks,List<String>profiles){

public static AreaPackageDto from(AreaWithMembers awm){return new AreaPackageDto(awm.area().name(),awm.area().title(),awm.area().description(),awm.area().prototype(),awm.defaultSystemPrompt(),awm.defaultPlaybook(),awm.defaultProfile(),awm.members().systemPrompts(),awm.members().collections(),awm.members().playbooks(),awm.members().profiles());}}
