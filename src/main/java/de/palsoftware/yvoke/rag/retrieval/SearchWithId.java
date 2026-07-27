package de.palsoftware.yvoke.rag.retrieval;

import java.util.List;
import java.util.UUID;

public record SearchWithId(List<HybridSearchResult>results,UUID searchId){}
