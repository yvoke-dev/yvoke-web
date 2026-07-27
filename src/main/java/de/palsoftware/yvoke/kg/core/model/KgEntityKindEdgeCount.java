package de.palsoftware.yvoke.kg.core.model;

import jakarta.annotation.Nullable;

/**
 * One candidate identity of an ambiguous entity name: the {@code kind} that carries the name, how
 * many edges that specific node has under the caller's tag/relation/direction filters, the source
 * document the node was extracted from, and the {@code tags} scope it belongs to. Used to
 * disambiguate a name that exists under several kinds (e.g. "Person" as a table, a form and a
 * notification) before any edges are fetched.
 *
 * <p>
 * Since graph identity became tag-scoped, one name can also be ambiguous WITHOUT spanning several
 * kinds — the same (kind, name) exists once per product version. {@code tags} is what tells those
 * two apart, so a caller can be told to pin a tag rather than a kind.
 */
public record KgEntityKindEdgeCount(String kind,long edgeCount,@Nullable String documentId,@Nullable String tags){

/** Legacy 3-arg form for callers that predate tag-scoped identity. */
public KgEntityKindEdgeCount(String kind,long edgeCount,@Nullable String documentId){this(kind,edgeCount,documentId,null);}}
