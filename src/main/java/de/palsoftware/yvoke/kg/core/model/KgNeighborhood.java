package de.palsoftware.yvoke.kg.core.model;

import jakarta.annotation.Nullable;
import java.util.List;

public record KgNeighborhood(@Nullable KgEntity entity,List<KgRelationship>outgoing,List<KgRelationship>incoming){}
