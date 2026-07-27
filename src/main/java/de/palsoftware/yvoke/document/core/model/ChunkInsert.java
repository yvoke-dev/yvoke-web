package de.palsoftware.yvoke.document.core.model;

import java.util.List;

public record ChunkInsert(String text,float[]embedding,List<String>headingPath,String heading,int depth,int sortOrder){}
