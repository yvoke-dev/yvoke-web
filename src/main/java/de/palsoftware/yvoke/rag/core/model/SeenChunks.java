package de.palsoftware.yvoke.rag.core.model;

import java.util.UUID;

/**
 * The ledger of chunks already rendered in full into <b>one</b> agent conversation, so a repeat can
 * be replaced by a reference to the copy the model is still holding.
 *
 * <p>
 * The claim a reference makes — "the full text is earlier in this conversation" — is only true
 * while the ledger's lifetime is a subset of the lifetime of the message list it points into. That
 * is the whole safety property, and it is why this is an interface passed per call rather than
 * state on a bean: {@code AgenticChatContext} is created once per {@code generateAgenticAnswer}
 * alongside the message list it belongs to, and each nested specialist mints its own. A ledger
 * cached on the singleton {@code SearchCorpusTool} would instead span every user on the server.
 */
@FunctionalInterface
public interface SeenChunks {

    /**
     * No conversation to point at — an external MCP client holds its own history, which we cannot
     * see. Every hit renders in full.
     *
     * <p>
     * Stateless by construction, not merely empty: a shared mutable no-op would be a single ledger
     * accumulating across every caller of the server, which is the same leak as a field on the tool
     * bean wearing a different hat.
     */
    SeenChunks NONE = chunkId -> true;

    /**
     * Whether this chunk is being rendered into this conversation for the first time — and records
     * it as rendered.
     *
     * <p>
     * Check and record are deliberately one step. Split into "have I seen it?" plus a later "mark
     * them all seen", the recording drifts away from the rendering it describes: mark too early and
     * the very first result set elides in full, mark too late and a chunk repeated inside one
     * result set renders twice. Neither failure throws, and both look like a working search.
     */
    boolean firstSighting(UUID chunkId);
}
