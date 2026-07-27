package de.palsoftware.yvoke.collection.core.model;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record Collection(UUID id,String name,String description,List<String>tags,OffsetDateTime createdAt){}
