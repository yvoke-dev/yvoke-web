package de.palsoftware.yvoke.document.core.model;

import java.util.List;
import java.util.UUID;

/**
 * A resolved section, with its passages kept separate and each one carrying its id.
 *
 * <p>
 * The sibling {@link SectionResponse} carries the same section already concatenated into one
 * string, which is what the citation dialog renders for a human. Agents need the seam: without a
 * per-passage id, an agent that reads a section can only cite the whole document. This is a
 * boundary DTO rather than a list of {@code ChunkRow}s on purpose — {@code ChunkRow} is a JDBC row
 * carrying the embedding and the raw metadata map, and CLAUDE.md keeps those out of the layers
 * above the service.
 *
 * <p>
 * Both views come from one resolution in {@code SectionService}, so the passages an agent cites and
 * the text a human is shown can never disagree about what the section contains.
 */
public record SectionChunks(List<String>headingPath,String documentTitle,String tag,String scope,List<SectionChunk>chunks){

/** One passage of the section: enough to render and cite it, and nothing more. */
public record SectionChunk(UUID id,UUID documentId,String heading,String text){}}
