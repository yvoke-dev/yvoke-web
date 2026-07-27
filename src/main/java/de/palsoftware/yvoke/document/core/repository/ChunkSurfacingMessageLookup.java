package de.palsoftware.yvoke.document.core.repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SPI allowing presentation controllers or services in the document domain to query for chat
 * messages that referenced/surfaced specific document chunks, without depending directly on the
 * chat package.
 */
public interface ChunkSurfacingMessageLookup {
    List<Map<String, Object>> findMessagesSurfacingChunk(UUID chunkId);
}
