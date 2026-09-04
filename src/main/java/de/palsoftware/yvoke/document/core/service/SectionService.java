package de.palsoftware.yvoke.document.core.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import de.palsoftware.yvoke.document.core.HierarchyUtils;
import de.palsoftware.yvoke.document.core.model.ChunkPathRow;
import de.palsoftware.yvoke.document.core.model.ChunkRow;
import de.palsoftware.yvoke.document.core.model.DocumentKind;
import de.palsoftware.yvoke.document.core.model.DocumentRow;
import de.palsoftware.yvoke.document.core.model.SectionChunks;
import de.palsoftware.yvoke.document.core.model.SectionChunks.SectionChunk;
import de.palsoftware.yvoke.document.core.model.SectionResponse;
import de.palsoftware.yvoke.document.core.repository.ChunkRepository;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import jakarta.annotation.Nullable;

@Service
public class SectionService {

    private final ChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;

    public SectionService(ChunkRepository chunkRepository, DocumentRepository documentRepository) {
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
    }

    public SectionResponse getSection(String collection, String documentName,
        @Nullable String headingPathStr) {
        if (collection == null || collection.isBlank()) {
            throw new IllegalArgumentException("Collection name cannot be null or empty.");
        }
        if (documentName == null || documentName.isBlank()) {
            throw new IllegalArgumentException("Document name cannot be null or empty.");
        }

        DocumentRow doc = documentRepository.findByManual(documentName, collection)
            .orElseThrow(() -> new NoSuchElementException("(no manual matching '" + documentName
                + "'" + " in collection " + collection + ")"));

        List<String> targetPath =
            headingPathStr == null || headingPathStr.isBlank() ? Collections.emptyList()
                : HierarchyUtils.splitHeadingPath(headingPathStr);

        return getSectionForDocument(doc, targetPath,
            headingPathStr == null || headingPathStr.isBlank());
    }

    /**
     * The text of ONE cited passage — the human-facing citation lookup.
     *
     * <p>
     * A citation is the claim "this passage supports this sentence", so the passage it names is the
     * whole of what this returns. It used to return the whole SECTION the passage belongs to, by
     * converting the chunk into a heading path and handing that to {@link #getSectionForDocument}.
     * That is a prefix match, so the answer grew with the breadth of the chunk's heading rather
     * than with anything about the citation: measured on one real answer, a cited passage of 1,357
     * characters came back inside 220 passages and 314,064 characters — 231x the text that was
     * cited. Two of that answer's eight citations expanded past 70 passages.
     *
     * <p>
     * Worse than the volume, the extra text is not evidence. {@code search_corpus} hands a model
     * one chunk at a time, so nothing else in that section was in front of it when it wrote the
     * claim, and showing the neighbours invites confirming a claim from text the model never read.
     * That is the same failure the server's reviewer playbook gives as its reason for refusing
     * {@code get_section} altogether.
     *
     * <p>
     * This includes the sibling parts of a {@code (part N/M)} split. Such a passage does end
     * mid-content, but the model that cited it saw it end there too — if the claim leans on what
     * the cut removed, that is a real weakness in the citation, and quietly padding the passage
     * back out would hide it.
     *
     * <p>
     * The agent-facing sibling {@link #getSectionChunksByChunkId} still expands, deliberately:
     * {@code get_section}'s own description promises that "a chunk_id returns the whole section
     * containing that chunk, not just the chunk", which is the right contract for an agent widening
     * a search hit. The two audiences want opposite things from the same id, which is why they are
     * separate methods with one consumer each.
     *
     * @param chunkIdPrefix a full chunk id, or a prefix of at least 8 hex characters — the
     *        production input shape, since a truncated id is what a shortened citation link carries
     */
    public SectionResponse getChunkContent(String chunkIdPrefix) {
        if (chunkIdPrefix == null || chunkIdPrefix.isBlank()) {
            throw new IllegalArgumentException("Chunk ID prefix cannot be null or empty.");
        }

        // findByIdPrefix INNER JOINs documents, so the row already carries the document's title and
        // tag and a chunk whose document is gone simply does not come back. The second lookup this
        // method used to do for the title could therefore never fail, and is gone with it.
        ChunkRow chunk = chunkRepository.findByIdPrefix(chunkIdPrefix)
            .orElseThrow(() -> new NoSuchElementException(
                "(no chunk with id starting '" + chunkIdPrefix + "')"));

        String documentTitle = chunk.documentTitle() != null ? chunk.documentTitle() : "?";
        String tag = chunk.tag() != null ? chunk.tag() : "?";
        // No "# Section: …" header: it restated the title, tag and scope that the citation panel
        // already renders in its own header row, and titling one passage as a section was the very
        // confusion this method exists to end. `text` is now the passage and nothing else, with the
        // breadcrumb carried structurally in headingPath.
        String text = HierarchyUtils.stripBreadcrumb(chunk.text()).strip();

        List<String> fullPath = HierarchyUtils.getChunkFullPath(chunk);
        List<String> cleanPath = cleanHeadingPath(fullPath, documentTitle);
        text = stripLeadingDocumentTitleHeading(text, documentTitle);

        return new SectionResponse(cleanPath, documentTitle, tag, 1, "this passage only", text);
    }

    private static final Pattern LEADING_HEADING_PATTERN =
        Pattern.compile("^#{1,6}\\s+(.+?)\\s*(?:\\r?\\n|$)");

    static List<String> cleanHeadingPath(List<String> fullPath, String documentTitle) {
        if (fullPath == null || fullPath.isEmpty()) {
            return List.of();
        }
        String normDoc = HierarchyUtils.normalizeSegment(documentTitle);
        List<String> result = new ArrayList<>(fullPath);
        if (!result.isEmpty() && HierarchyUtils.normalizeSegment(result.get(0)).equals(normDoc)) {
            result.remove(0);
        }
        return List.copyOf(result);
    }

    static String stripLeadingDocumentTitleHeading(String text, String documentTitle) {
        if (text == null || text.isBlank() || documentTitle == null || documentTitle.isBlank()) {
            return text == null ? "" : text.strip();
        }
        // lookingAt, not find: the pattern is ^-anchored and not MULTILINE, so find() can only
        // ever match at 0 anyway and the extra start() check read as if it could not.
        Matcher m = LEADING_HEADING_PATTERN.matcher(text);
        if (m.lookingAt()) {
            String headingTitle = HierarchyUtils.stripPart(m.group(1).trim());
            if (HierarchyUtils.normalizeSegment(headingTitle)
                .equals(HierarchyUtils.normalizeSegment(documentTitle))) {
                return text.substring(m.end()).stripLeading();
            }
        }
        return text;
    }

    public SectionResponse getSectionByDocumentId(String documentIdStr,
        @Nullable String headingPathStr) {
        if (documentIdStr == null || documentIdStr.isBlank()) {
            throw new IllegalArgumentException("Document ID cannot be null or empty.");
        }

        UUID docId;
        try {
            docId = UUID.fromString(documentIdStr.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid UUID format: " + documentIdStr);
        }

        DocumentRow doc = documentRepository.findById(docId).orElseThrow(
            () -> new NoSuchElementException("(no document found for id '" + docId + "')"));

        List<String> targetPath =
            headingPathStr == null || headingPathStr.isBlank() ? Collections.emptyList()
                : HierarchyUtils.splitHeadingPath(headingPathStr);

        return getSectionForDocument(doc, targetPath,
            headingPathStr == null || headingPathStr.isBlank());
    }

    /**
     * The agent-facing read of a chunk id: the whole section the chunk belongs to, with its
     * passages kept apart so each can carry its id.
     *
     * <p>
     * Deliberately NOT the same scope as the human-facing {@link #getChunkContent}, which returns
     * only the cited passage. {@code get_section}'s description promises an agent that "a chunk_id
     * returns the whole section containing that chunk, not just the chunk" — widening a search hit
     * is a useful operation for a model and a misleading one for a reader, so the two audiences get
     * different methods rather than a shared one with a flag.
     */
    public SectionChunks getSectionChunksByChunkId(String chunkIdPrefix) {
        if (chunkIdPrefix == null || chunkIdPrefix.isBlank()) {
            throw new IllegalArgumentException("Chunk ID prefix cannot be null or empty.");
        }

        ChunkRow chunk = chunkRepository.findByIdPrefix(chunkIdPrefix)
            .orElseThrow(() -> new NoSuchElementException(
                "(no chunk with id starting '" + chunkIdPrefix + "')"));

        DocumentRow doc = documentRepository.findById(chunk.documentId())
            .orElseThrow(() -> new NoSuchElementException(
                "(no document found for chunk '" + chunkIdPrefix + "')"));

        return toSectionChunks(doc, HierarchyUtils.getChunkFullPath(chunk), false);
    }

    /**
     * The agent-facing view of {@link #getSectionByDocumentId}: the same section, but with its
     * passages kept apart so each can carry its id.
     */
    public SectionChunks getSectionChunksByDocumentId(String documentIdStr,
        @Nullable String headingPathStr) {
        DocumentRow doc = resolveDocument(documentIdStr);
        boolean fullDocument = headingPathStr == null || headingPathStr.isBlank();
        List<String> targetPath = fullDocument ? Collections.emptyList()
            : HierarchyUtils.splitHeadingPath(headingPathStr);
        return toSectionChunks(doc, targetPath, fullDocument);
    }

    private DocumentRow resolveDocument(String documentIdStr) {
        if (documentIdStr == null || documentIdStr.isBlank()) {
            throw new IllegalArgumentException("Document ID cannot be null or empty.");
        }
        UUID docId;
        try {
            docId = UUID.fromString(documentIdStr.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid UUID format: " + documentIdStr);
        }
        return documentRepository.findById(docId).orElseThrow(
            () -> new NoSuchElementException("(no document found for id '" + docId + "')"));
    }

    /**
     * Resolves through the SAME funnel the concatenated view uses, then keeps the passages apart
     * instead of joining them. Sharing {@link #getSectionForDocument}'s resolution is the point:
     * the passages an agent cites and the text a human is shown can never disagree about what the
     * section contains.
     */
    private SectionChunks toSectionChunks(DocumentRow doc, List<String> targetPath,
        boolean fetchFullDocument) {
        Resolved resolved = resolveSection(doc, targetPath, fetchFullDocument);
        List<SectionChunk> chunks = new ArrayList<>(resolved.matched().size());
        for (ChunkRow chunk : resolved.matched()) {
            chunks.add(new SectionChunk(chunk.id(), chunk.documentId(), chunk.heading(),
                HierarchyUtils.stripBreadcrumb(chunk.text()).stripTrailing()));
        }
        return new SectionChunks(targetPath, resolved.documentTitle(), resolved.tag(),
            resolved.scope(), chunks);
    }

    private SectionResponse getSectionForDocument(DocumentRow doc, List<String> targetPath,
        boolean fetchFullDocument) {
        Resolved r = resolveSection(doc, targetPath, fetchFullDocument);
        String sectionName = fetchFullDocument ? "Full Document" : String.join(" > ", targetPath);
        String header = "# Section: " + sectionName + "\n" + "_(document: " + r.documentTitle()
            + "  ·  tag: " + r.tag() + "  ·  " + r.matched().size() + " " + r.unit() + "  ·  "
            + r.scope() + ")_\n";

        StringBuilder sb = new StringBuilder(header).append("\n");
        for (ChunkRow chunk : r.matched()) {
            sb.append(HierarchyUtils.stripBreadcrumb(chunk.text()).stripTrailing()).append("\n");
        }
        return new SectionResponse(targetPath, r.documentTitle(), r.tag(), r.matched().size(),
            r.scope(), sb.toString());
    }

    private Resolved resolveSection(DocumentRow doc, List<String> targetPath,
        boolean fetchFullDocument) {
        // 2+3. Resolve the matching chunks. For a section request, match the target path
        // against a hierarchy-only projection first (path normalization must stay in Java),
        // then fetch full rows for the matches — chunk text of unmatched sections never
        // leaves the database.
        List<ChunkPathRow> pathRows = List.of();
        List<ChunkRow> matched;
        if (fetchFullDocument) {
            matched = new ArrayList<>(chunkRepository.findChunksByDocumentId(doc.id(), null));
        } else {
            pathRows = chunkRepository.findChunkPathsByDocumentId(doc.id());
            List<UUID> matchedIds = new ArrayList<>();
            for (ChunkPathRow chunk : pathRows) {
                List<String> chunkPath = HierarchyUtils.getChunkFullPath(chunk);
                if (HierarchyUtils.isSubpathOf(targetPath, chunkPath)) {
                    matchedIds.add(chunk.id());
                }
            }
            matched = new ArrayList<>(chunkRepository.findChunksByIds(matchedIds));
        }

        if (matched.isEmpty()) {
            // Reconstruct all paths to help user debug mismatch
            List<String> allPaths = new ArrayList<>();
            for (ChunkPathRow chunk : pathRows) {
                List<String> chunkPath = HierarchyUtils.getChunkFullPath(chunk);
                allPaths.add(String.join(" > ", chunkPath));
            }
            // Sort distinct paths
            Set<String> distinctPaths = new TreeSet<>(allPaths);
            List<String> pathSample = new ArrayList<>(distinctPaths);
            int totalPaths = pathSample.size();
            if (pathSample.size() > 40) {
                pathSample = pathSample.subList(0, 40);
            }

            StringBuilder errorMsg = new StringBuilder("(no section matched '")
                .append(String.join(" > ", targetPath)).append(
                    "' in this document. heading_path must be rooted at the top, exactly as shown in a search hit's breadcrumb. Sections in this document:\n");
            for (String p : pathSample) {
                errorMsg.append("  ").append(p).append("\n");
            }
            if (totalPaths > 40) {
                errorMsg.append("  …and ").append(totalPaths - 40).append(" more\n");
            }
            errorMsg.deleteCharAt(errorMsg.length() - 1); // remove trailing newline
            errorMsg.append(")");
            throw new NoSuchElementException(errorMsg.toString());
        }

        // 4. Sort matched chunks by sortOrder ascending
        matched.sort(
            Comparator.comparing(c -> c.sortOrder() != null ? c.sortOrder() : Integer.MAX_VALUE));

        String documentTitle = matched.get(0).documentTitle();
        if (documentTitle == null)
            documentTitle = "?";
        String tag = matched.get(0).tag();
        if (tag == null)
            tag = "?";

        String scope = fetchFullDocument ? "full document" : "with sub-sections";
        String docKind = matched.get(0).kind();
        String unit = (DocumentKind.HIERARCHICAL.getValue().equals(docKind)
            || DocumentKind.CONFLUENCE.getValue().equals(docKind)) ? "section(s)" : "chunk(s)";
        return new Resolved(matched, documentTitle, tag, scope, unit);
    }

    /** One resolved section, before either view decides how to render it. */
    private record Resolved(List<ChunkRow> matched, String documentTitle, String tag, String scope,
        String unit) {}
}
