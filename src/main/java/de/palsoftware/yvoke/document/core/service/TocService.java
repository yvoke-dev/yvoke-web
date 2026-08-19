package de.palsoftware.yvoke.document.core.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import de.palsoftware.yvoke.document.core.HierarchyUtils;
import de.palsoftware.yvoke.document.core.model.ChunkPathRow;
import de.palsoftware.yvoke.document.core.model.DocumentRow;
import de.palsoftware.yvoke.document.core.model.TocNode;
import de.palsoftware.yvoke.document.core.repository.ChunkRepository;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import java.sql.Array;
import org.springframework.jdbc.core.simple.JdbcClient;

@Service
public class TocService {

    private static final Logger log = LoggerFactory.getLogger(TocService.class);

    private final ChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;
    private final JdbcClient jdbcClient;

    public TocService(ChunkRepository chunkRepository, DocumentRepository documentRepository,
        JdbcClient jdbcClient) {
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
        this.jdbcClient = jdbcClient;
    }

    public List<TocNode> getToc(String manualSubstring, String collection) {
        return getToc(manualSubstring, collection, null);
    }

    public List<TocNode> getToc(String manualSubstring, String collection,
        @Nullable List<String> tags) {
        if (collection == null || collection.isBlank()) {
            throw new IllegalArgumentException("Collection name cannot be null or empty.");
        }
        if (manualSubstring == null || manualSubstring.isBlank()) {
            throw new IllegalArgumentException("Manual name cannot be null or empty.");
        }

        // 1. Resolve document ID
        DocumentRow doc = ((tags == null || tags.isEmpty())
            ? documentRepository.findByManual(manualSubstring, collection)
            : documentRepository.findByManual(manualSubstring, collection, tags))
            .orElseThrow(() -> new NoSuchElementException("(no manual matching '" + manualSubstring
                + "'" + " in collection " + collection + ")"));
        return getTocForDocument(doc, null);
    }

    public List<TocNode> getToc(UUID docId) {
        return getToc(docId, null);
    }

    /**
     * The table of contents for the two levels below {@code scope}, or for the top two levels of
     * the document when {@code scope} is null or empty.
     *
     * <p>
     * Without a scope the depth cap is absolute, so a section at depth 3 is simply invisible and
     * the only way to see inside a depth-2 section is to read the whole thing. Scoping moves the
     * two-level window down the tree instead, which is what lets an agent navigate to the passage
     * it wants rather than paying for the chapter that contains it.
     */
    public List<TocNode> getToc(UUID docId, @Nullable List<String> scope) {
        if (docId == null) {
            throw new IllegalArgumentException("Document ID cannot be null.");
        }
        DocumentRow doc = documentRepository.findById(docId).orElseThrow(
            () -> new NoSuchElementException("(no document found for id '" + docId + "')"));
        return getTocForDocument(doc, scope);
    }

    private List<TocNode> getTocForDocument(DocumentRow doc, @Nullable List<String> scope) {
        UUID docId = doc.id();

        // 2. Fetch the hierarchy columns of all chunks (the TOC never needs chunk text)
        List<ChunkPathRow> chunks = chunkRepository.findChunkPathsByDocumentId(docId);

        // The depth-2 window is relative to the scope: unscoped it is the document's top two
        // levels, scoped it is the two levels below the given path. Segments are compared with
        // HierarchyUtils.normalizeSegment, the same comparator SectionService resolves a
        // heading_path with, so a path copied out of one tool resolves in the other.
        List<String> scopeNormalized = (scope == null) ? List.of()
            : scope.stream().map(HierarchyUtils::normalizeSegment).toList();
        int base = scopeNormalized.size();

        // 3. Build TOC tree of nodes
        Map<List<String>, TocNodeBuilder> nodes = new HashMap<>();
        for (ChunkPathRow chunk : chunks) {
            List<String> full = HierarchyUtils.getChunkFullPath(chunk);
            if (full.size() <= base) {
                // Nothing below the scope on this branch (and, unscoped, an empty path).
                continue;
            }

            int so = chunk.sortOrder() != null ? chunk.sortOrder() : Integer.MAX_VALUE;

            // Pre-normalize only as far as the window reaches, to avoid O(D^2) normalizations.
            int limit = Math.min(base + 2, full.size());
            List<String> fullNormalized = new ArrayList<>(limit);
            List<String> fullDisplay = new ArrayList<>(limit);
            for (int i = 0; i < limit; i++) {
                String seg = full.get(i);
                fullNormalized.add(HierarchyUtils.normalizeSegment(seg));
                fullDisplay.add(Normalizer.normalize(seg, Normalizer.Form.NFKC).trim());
            }

            if (!fullNormalized.subList(0, base).equals(scopeNormalized)) {
                continue;
            }

            // Paths stay ABSOLUTE below the scope: the agent copies one straight into
            // get_section(heading_path=...), and a relative path would not resolve there.
            for (int i = base + 1; i <= limit; i++) {
                List<String> key = new ArrayList<>(fullNormalized.subList(0, i));
                List<String> displayPath = new ArrayList<>(fullDisplay.subList(0, i));

                TocNodeBuilder builder =
                    nodes.computeIfAbsent(key, k -> new TocNodeBuilder(displayPath, so));
                builder.minSortOrder = Math.min(builder.minSortOrder, so);
                builder.subtreeChunkCount++;
                builder.subtreeCharCount += chunk.textLength();
            }
        }

        if (nodes.isEmpty()) {
            // Naming the scope matters: an agent that mistyped a heading_path must not be able to
            // read this as "the document is empty", and must never be handed the whole-document
            // TOC as a consolation, which would answer a question it did not ask.
            throw new NoSuchElementException(base == 0 ? "(no sections found for this manual)"
                : "(no sections found under '" + String.join(" > ", scope)
                    + "' — check the path against the parent table of contents)");
        }

        // 4. Sort selected nodes by minSortOrder ascending
        List<TocNodeBuilder> selected = new ArrayList<>(nodes.values());
        selected.sort(Comparator.comparingInt(nd -> nd.minSortOrder));

        // 5.5 Fetch section summaries
        Map<List<String>, String> summaryByNormalizedPath = new HashMap<>();
        try {
            jdbcClient.sql(
                "SELECT heading_path, summary FROM section_summaries WHERE document_id = :docId")
                .param("docId", docId).query((rs, rowNum) -> {
                    Array arr = rs.getArray("heading_path");
                    if (arr != null) {
                        String[] pathArray = (String[]) arr.getArray();
                        List<String> path = Arrays.asList(pathArray);
                        List<String> normPath = path.stream().map(HierarchyUtils::normalizeSegment)
                            .collect(Collectors.toList());
                        summaryByNormalizedPath.put(normPath, rs.getString("summary"));
                    }
                    return null;
                }).list();
        } catch (Exception e) {
            // Log fallback without failing the entire TOC retrieval
            log.warn("Failed to fetch section summaries for document {}", docId, e);
        }

        // 6. Transform to TocNode list
        List<TocNode> result = new ArrayList<>();
        for (TocNodeBuilder nd : selected) {
            List<String> nodeNorm =
                nd.path.stream().map(HierarchyUtils::normalizeSegment).collect(Collectors.toList());
            String summary = summaryByNormalizedPath.get(nodeNorm);
            result.add(new TocNode(nd.path, nd.minSortOrder, nd.subtreeChunkCount,
                nd.subtreeCharCount, summary));
        }
        return result;
    }

    private static class TocNodeBuilder {
        final List<String> path;
        int minSortOrder;
        int subtreeChunkCount;
        int subtreeCharCount;

        TocNodeBuilder(List<String> path, int minSortOrder) {
            this.path = path;
            this.minSortOrder = minSortOrder;
            this.subtreeChunkCount = 0;
            this.subtreeCharCount = 0;
        }
    }
}
