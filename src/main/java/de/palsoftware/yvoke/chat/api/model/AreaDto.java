package de.palsoftware.yvoke.chat.api.model;

import de.palsoftware.yvoke.area.core.Area;
import de.palsoftware.yvoke.area.core.AreaWithMembers;

public record AreaDto(String name,String title,String description,boolean prototype,String defaultSystemPrompt,String defaultPlaybook,String defaultProfile){

public static AreaDto from(Area area){return new AreaDto(area.name(),area.title(),area.description(),area.prototype(),area.defaultSystemPrompt(),area.defaultPlaybook(),area.defaultProfile());}

public static AreaDto from(AreaWithMembers awm){return new AreaDto(awm.area().name(),awm.area().title(),awm.area().description(),awm.area().prototype(),awm.defaultSystemPrompt(),awm.defaultPlaybook(),awm.defaultProfile());}}
