package de.palsoftware.yvoke.chat.api.model;

import de.palsoftware.yvoke.rag.prompt.Playbook;
import java.util.List;

public record PlaybookDto(String name,String title,String description,List<String>tools,boolean codeExecution,String targetAgent,boolean prototype){

public static PlaybookDto from(Playbook playbook){return new PlaybookDto(playbook.name(),playbook.title(),playbook.description(),playbook.tools()!=null?playbook.tools():List.of(),playbook.codeExecution(),playbook.targetAgent()!=null?playbook.targetAgent():"specialist",playbook.prototype());}}
