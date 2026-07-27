package de.palsoftware.yvoke.document.core.model;

import java.util.List;

public record SectionResponse(List<String>headingPath,String documentTitle,String tag,int chunkCount,String scope,String text){}
