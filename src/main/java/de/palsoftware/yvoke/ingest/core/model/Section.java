package de.palsoftware.yvoke.ingest.core.model;

import java.util.List;

public record Section(int depth,String title,List<String>headingPath,String body){

public String toChunkText(){String prefix=headingPath.isEmpty()?"":"> Section path: "+String.join(" > ",headingPath)+"\n\n";return prefix+"#".repeat(depth)+" "+title+"\n\n"+body.stripTrailing()+"\n";}}
