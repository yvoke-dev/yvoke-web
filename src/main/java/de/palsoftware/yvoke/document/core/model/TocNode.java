package de.palsoftware.yvoke.document.core.model;

import jakarta.annotation.Nullable;
import java.util.List;

public record TocNode(List<String>path,int minSortOrder,int subtreeChunkCount,@Nullable String summary){}
