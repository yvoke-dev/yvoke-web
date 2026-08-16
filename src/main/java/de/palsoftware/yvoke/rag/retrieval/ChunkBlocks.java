package de.palsoftware.yvoke.rag.retrieval;

import de.palsoftware.yvoke.document.core.HierarchyUtils;
import de.palsoftware.yvoke.rag.core.model.SeenChunks;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place the {@code search_corpus} result layout is written <b>and</b> read back.
 *
 * <p>
 * {@link #format} used to live in {@code McpToolUtils}. It moved here because the orchestrator now
 * has to parse its output — the reviewer's evidence is nothing but a concatenation of these
 * renderings — and a format with a producer in one domain and a parser in another is a two-place
 * contract that only a human keeps in sync. Keeping both halves in one class makes the round trip
 * testable in a single assertion, which is the only thing that will ever notice them drifting.
 *
 * <p>
 * The parser is deliberately strict about the header and deliberately generous about everything
 * else: text with no recognisable header is <b>not</b> chunk output, and reporting that honestly
 * (zero blocks, everything as preamble) is what lets callers pass a {@code query_json_objects}
 * table or a tool error through untouched instead of mangling it.
 */
public final class ChunkBlocks {

    /**
     * Anchored on the WHOLE header line, never on the {@code "### "} prefix alone. Chunk bodies are
     * ingested markdown and routinely contain their own {@code ### Heading} lines, so a prefix
     * match shreds one chunk into several fragments — each then carrying the wrong id, or none.
     *
     * <p>
     * The double spaces are load-bearing (they are what {@link #format} writes), and both id groups
     * accept the empty string because {@code format} emits a bare {@code id=} for a null id.
     */
    private static final Pattern HEADER = Pattern
        .compile("^### (?<kind>[^/\\n]*)/(?<title>[^\\n]*?)  \\(score=(?<score>-?\\d+\\.\\d{3})"
            + "  id=(?<id>[0-9a-fA-F-]*)  doc_id=(?<doc>[0-9a-fA-F-]*)\\)$", Pattern.MULTILINE);

    /**
     * The second block shape: what {@code get_section} writes above each passage it returns.
     *
     * <p>
     * A section read has no relevance score and {@link #HEADER} requires one, so the two producers
     * cannot share a pattern. Both are recognised here rather than in two parsers, for the reason
     * this class exists at all: a format and its parser that live apart drift, and CLAUDE.md § 6
     * records that happening three times.
     *
     * <p>
     * It carries {@code doc_id} as well as {@code id} so an answer citing a section by its document
     * still retains the passages. That matters while the specialist playbooks — which currently
     * instruct exactly that — are being updated; without it, the transition would silently empty
     * every section out of the reviewer's evidence.
     */
    private static final Pattern SECTION_MARKER = Pattern.compile(
        "^_\\(id=(?<id>[0-9a-fA-F-]*)  doc_id=(?<doc>[0-9a-fA-F-]*)\\)_$", Pattern.MULTILINE);

    /**
     * A trailing italic parenthetical on its own paragraph — what {@code search_corpus} appends to
     * say the result was capped. It is split off rather than left on the last chunk's body, because
     * a caller that reduces that chunk to a reference would otherwise delete the only signal that
     * the search may not have returned everything.
     *
     * <p>
     * Producer-agnostic on purpose: keying on the notice's exact wording would be a second copy of
     * a string only a human keeps aligned. The worst a generic match can do is relocate a chunk
     * body's own trailing parenthetical to the end of the render — content preserved either way.
     */
    private static final Pattern TRAILING_NOTE = Pattern.compile("\\n\\n_\\([^\\n]*\\)_\\s*$");

    /**
     * Stands in for the body of a chunk this conversation already received in full.
     *
     * <p>
     * Parentheses, never brackets: {@code CitationVerifier} only ever looks inside {@code [...]},
     * so this cannot be mistaken for a citation, and — more to the point — cannot teach the model
     * that bracketed non-citations are a thing it should emit.
     */
    public static final String SHOWN_ABOVE =
        "_(already shown above — full text is earlier in this conversation)_";

    private ChunkBlocks() {}

    /** A rendered result split into what precedes the chunks, the chunks, and what follows. */
    public record Parsed(String preamble, List<Block> blocks, String suffix) {}

    /**
     * One {@code ###} chunk rendering. {@code raw} is the exact substring it occupied, so
     * re-emitting a block unchanged costs nothing and cannot drift from what was parsed.
     */
    public record Block(String raw, String header, String kind, String title,
        @Nullable UUID chunkId, @Nullable UUID documentId, String headingPath, String body) {

        /** The same block with its body replaced — the inverse of parsing, minus the separator. */
        public String withBody(String replacement) {
            StringBuilder sb = new StringBuilder(header);
            if (!headingPath.isEmpty()) {
                sb.append("\n> ").append(headingPath);
            }
            sb.append("\n").append(replacement);
            return sb.toString().stripTrailing();
        }

        /** Whether this block is a reference to a body rendered earlier, rather than a body. */
        public boolean isShownAboveMarker() {
            return SHOWN_ABOVE.equals(body);
        }

        /** Names the chunk well enough to decide whether to fetch it, without quoting any of it. */
        public String manifestLine() {
            StringBuilder sb = new StringBuilder("- ");
            if (chunkId != null) {
                sb.append("chunk_id=").append(chunkId);
            } else if (documentId != null) {
                sb.append("document_id=").append(documentId);
            } else {
                sb.append("(no id)");
            }
            sb.append(" · ").append(kind).append('/').append(title);
            if (!headingPath.isEmpty()) {
                sb.append(" · ").append(headingPath);
            }
            return sb.toString();
        }
    }

    /**
     * Renders search hits for the model, replacing the body of any chunk {@code seen} has already
     * given this conversation with {@link #SHOWN_ABOVE}.
     *
     * <p>
     * The ledger is a required argument and there is no single-argument overload, on purpose: an
     * overload lets a future call site opt out of suppression with nothing in the diff to review. A
     * caller with no conversation says so explicitly with {@link SeenChunks#NONE}.
     */
    public static String format(List<HybridSearchResult> chunks, SeenChunks seen) {
        Objects.requireNonNull(seen, "seen");
        if (chunks == null || chunks.isEmpty()) {
            return "(no matching chunks)";
        }
        List<String> lines = new ArrayList<>();
        for (HybridSearchResult c : chunks) {
            double score = c.score();
            String cid = (c.id() != null) ? c.id().toString() : "";
            String text = (c.text() != null) ? HierarchyUtils.stripBreadcrumb(c.text()).trim() : "";

            String title = c.documentTitle();
            if (title == null) {
                title = "?";
            }

            String kind = c.kind() != null ? c.kind() : "?";

            String headPath = "";
            if (c.headingPath() != null && !c.headingPath().isEmpty()) {
                headPath = String.join(" > ", c.headingPath());
            } else if (c.heading() != null) {
                headPath = c.heading();
            }

            String docId = (c.documentId() != null) ? c.documentId().toString() : "";
            lines.add(String.format(Locale.US, "### %s/%s  (score=%.3f  id=%s  doc_id=%s)", kind,
                title, score, cid, docId));
            if (!headPath.isEmpty()) {
                lines.add("> " + headPath);
            }
            // Check and record are ONE step, per rendered slot. Because the answer to "render the
            // body?" IS the recording, a chunk cannot be marked before it is rendered, and the same
            // chunk appearing twice in one result set needs no special case. A null id identifies
            // nothing, so it is never recorded — recording it would collapse every id-less chunk
            // into the first.
            boolean firstSighting = (c.id() == null) || seen.firstSighting(c.id());
            lines.add(firstSighting ? text : SHOWN_ABOVE);
            lines.add("");
        }
        return String.join("\n", lines).strip();
    }

    /**
     * Splits a rendered tool result into its chunk blocks. Text containing no chunk header comes
     * back as pure preamble with an empty block list — the caller's signal that there is nothing
     * here to reduce.
     */
    public static Parsed parse(String rendered) {
        if (rendered == null || rendered.isEmpty()) {
            return new Parsed(rendered == null ? "" : rendered, List.of(), "");
        }

        // Both producers, in document order. A rendering only ever contains one shape, but
        // merging rather than choosing means neither producer can be forgotten here.
        List<Integer> starts = new ArrayList<>();
        Matcher m = HEADER.matcher(rendered);
        while (m.find()) {
            starts.add(m.start());
        }
        Matcher sm = SECTION_MARKER.matcher(rendered);
        while (sm.find()) {
            starts.add(sm.start());
        }
        Collections.sort(starts);
        if (starts.isEmpty()) {
            return new Parsed(rendered, List.of(), "");
        }

        int contentEnd = rendered.length();
        String suffix = "";
        Matcher note = TRAILING_NOTE.matcher(rendered);
        if (note.find() && note.start() > starts.get(starts.size() - 1)) {
            contentEnd = note.start();
            suffix = rendered.substring(contentEnd);
        }

        List<Block> blocks = new ArrayList<>(starts.size());
        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i);
            int to = (i + 1 < starts.size()) ? starts.get(i + 1) : contentEnd;
            blocks.add(toBlock(rendered.substring(from, to)));
        }
        return new Parsed(rendered.substring(0, starts.get(0)), List.copyOf(blocks), suffix);
    }

    private static Block toBlock(String raw) {
        int nl = raw.indexOf('\n');
        String header = (nl < 0) ? raw : raw.substring(0, nl);
        String rest = (nl < 0) ? "" : raw.substring(nl + 1);

        String headingPath = "";
        if (rest.startsWith("> ")) {
            int hnl = rest.indexOf('\n');
            headingPath = ((hnl < 0) ? rest : rest.substring(0, hnl)).substring(2);
            rest = (hnl < 0) ? "" : rest.substring(hnl + 1);
        }

        Matcher m = HEADER.matcher(header);
        String kind = "?";
        String title = "?";
        UUID chunkId = null;
        UUID documentId = null;
        if (m.find()) {
            kind = m.group("kind");
            title = m.group("title");
            chunkId = toUuid(m.group("id"));
            documentId = toUuid(m.group("doc"));
        } else {
            Matcher sm = SECTION_MARKER.matcher(header);
            if (sm.find()) {
                // A section passage has no kind/title of its own: the section heading names it
                // once, in the preamble, and the passage's own markdown heading follows in body.
                chunkId = toUuid(sm.group("id"));
                documentId = toUuid(sm.group("doc"));
            }
        }
        return new Block(raw, header, kind, title, chunkId, documentId, headingPath, rest.strip());
    }

    @Nullable
    private static UUID toUuid(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
