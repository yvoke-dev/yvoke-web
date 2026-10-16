package de.palsoftware.yvoke.rag.prompt;

import java.time.Instant;
import java.util.List;

/**
 * A stored playbook. {@code area} is the area it belongs to; {@code null} only on a value not read
 * from the database (for example a Markdown file that names no area). {@code systemPrompt} is the
 * name of the system prompt to use for this playbook; {@code null} indicates the default system
 * prompt applies. {@code version} is a positive integer ({@code >= 1}) stamped onto answers and
 * agent steps that run with this playbook.
 */
public record Playbook(String name,String title,String description,String templateText,List<String>tools,boolean codeExecution,String targetAgent,boolean prototype,Instant createdAt,Instant updatedAt,boolean readOnly,String area,String systemPrompt,int version){

public Playbook{if(version<1){throw new IllegalArgumentException("Playbook version must be >= 1.");}if(!isSpecialist(targetAgent)){systemPrompt=null;}}

public boolean isSpecialist(){return isSpecialist(targetAgent);}

public static boolean isSpecialist(String targetAgent){if(targetAgent==null||targetAgent.isBlank()){return true;}return!"orchestrator".equalsIgnoreCase(targetAgent)&&!"reviewer".equalsIgnoreCase(targetAgent);}

public Playbook(String name,String title,String description,String templateText,List<String>tools,boolean codeExecution,String targetAgent,boolean prototype,Instant createdAt,Instant updatedAt,boolean readOnly,String area,String systemPrompt){this(name,title,description,templateText,tools,codeExecution,targetAgent,prototype,createdAt,updatedAt,readOnly,area,systemPrompt,1);}

public Playbook(String name,String title,String description,String templateText,List<String>tools,boolean codeExecution,String targetAgent,boolean prototype,Instant createdAt,Instant updatedAt,boolean readOnly,String area){this(name,title,description,templateText,tools,codeExecution,targetAgent,prototype,createdAt,updatedAt,readOnly,area,null,1);}

public Playbook(String name,String title,String description,String templateText,List<String>tools,boolean codeExecution,String targetAgent,boolean prototype,Instant createdAt,Instant updatedAt,boolean readOnly){this(name,title,description,templateText,tools,codeExecution,targetAgent,prototype,createdAt,updatedAt,readOnly,null,null,1);}

public Playbook(String name,String title,String description,String templateText,List<String>tools,boolean codeExecution,Instant createdAt,Instant updatedAt,boolean readOnly){this(name,title,description,templateText,tools,codeExecution,"specialist",false,createdAt,updatedAt,readOnly,null,null,1);}

public Playbook(String name,String title,String description,String templateText,List<String>tools,boolean codeExecution,Instant createdAt,Instant updatedAt){this(name,title,description,templateText,tools,codeExecution,"specialist",false,createdAt,updatedAt,false,null,null,1);}

public Playbook(String name,String title,String description,String templateText,List<String>tools,boolean codeExecution,String targetAgent,Instant createdAt,Instant updatedAt){this(name,title,description,templateText,tools,codeExecution,targetAgent,false,createdAt,updatedAt,false,null,null,1);}

public Playbook(String name,String title,String description,String templateText,List<String>tools,boolean codeExecution,String targetAgent,boolean prototype,Instant createdAt,Instant updatedAt){this(name,title,description,templateText,tools,codeExecution,targetAgent,prototype,createdAt,updatedAt,false,null,null,1);}}
