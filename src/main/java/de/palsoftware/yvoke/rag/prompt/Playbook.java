package de.palsoftware.yvoke.rag.prompt;

import java.time.Instant;
import java.util.List;

/**
 * A stored playbook. {@code area} is the area it belongs to; {@code null} only on a value not read
 * from the database (for example a Markdown file that names no area).
 */
public record Playbook(String name,String title,String description,String templateText,List<String>tools,boolean codeExecution,String targetAgent,boolean prototype,Instant createdAt,Instant updatedAt,boolean readOnly,String area){

public Playbook(String name,String title,String description,String templateText,List<String>tools,boolean codeExecution,String targetAgent,boolean prototype,Instant createdAt,Instant updatedAt,boolean readOnly){this(name,title,description,templateText,tools,codeExecution,targetAgent,prototype,createdAt,updatedAt,readOnly,null);}

public Playbook(String name,String title,String description,String templateText,List<String>tools,boolean codeExecution,Instant createdAt,Instant updatedAt,boolean readOnly){this(name,title,description,templateText,tools,codeExecution,"specialist",false,createdAt,updatedAt,readOnly);}

public Playbook(String name,String title,String description,String templateText,List<String>tools,boolean codeExecution,Instant createdAt,Instant updatedAt){this(name,title,description,templateText,tools,codeExecution,"specialist",false,createdAt,updatedAt,false);}

public Playbook(String name,String title,String description,String templateText,List<String>tools,boolean codeExecution,String targetAgent,Instant createdAt,Instant updatedAt){this(name,title,description,templateText,tools,codeExecution,targetAgent,false,createdAt,updatedAt,false);}

public Playbook(String name,String title,String description,String templateText,List<String>tools,boolean codeExecution,String targetAgent,boolean prototype,Instant createdAt,Instant updatedAt){this(name,title,description,templateText,tools,codeExecution,targetAgent,prototype,createdAt,updatedAt,false);}}
