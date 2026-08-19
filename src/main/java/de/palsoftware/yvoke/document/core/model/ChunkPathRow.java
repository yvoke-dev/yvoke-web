package de.palsoftware.yvoke.document.core.model;

import jakarta.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * Lightweight chunk projection for TOC building and section-path matching — carries only the
 * hierarchy columns so the (potentially large) text column never leaves the database.
 *
 * <p>
 * {@code textLength} is {@code length(text)} measured in SQL, which keeps that promise: the table
 * of contents reports how big a section is so an agent can decide whether to read it or navigate
 * further into it, and only the integer crosses the wire.
 */
public record ChunkPathRow(UUID id,List<String>headingPath,@Nullable String heading,@Nullable Integer sortOrder,int textLength){}
