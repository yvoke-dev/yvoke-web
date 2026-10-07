package de.palsoftware.yvoke.collection.core.model;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A collection of documents. {@code area} is the area it belongs to; {@code null} only on a value
 * not read from the database.
 */
public record Collection(UUID id,String name,String description,List<String>tags,OffsetDateTime createdAt,String area){public Collection(UUID id,String name,String description,List<String>tags,OffsetDateTime createdAt){this(id,name,description,tags,createdAt,null);}}
