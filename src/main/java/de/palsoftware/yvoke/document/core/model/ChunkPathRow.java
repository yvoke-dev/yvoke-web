package de.palsoftware.yvoke.document.core.model;

import jakarta.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * Lightweight chunk projection for TOC building and section-path matching — carries only the
 * hierarchy columns so the (potentially large) text column never leaves the database.
 */
public record ChunkPathRow(UUID id,List<String>headingPath,@Nullable String heading,@Nullable Integer sortOrder){}
