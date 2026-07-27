package de.palsoftware.yvoke.ingest.core.model;

import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Map;

public record ParsedMarkdown(Map<String,Object>front,@Nullable String titleH1,List<Section>sections){}
