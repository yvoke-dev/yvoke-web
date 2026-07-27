package de.palsoftware.yvoke.rag.core.service;

import de.palsoftware.yvoke.document.core.repository.ChunkRepository;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class CitationVerifier {

    private final ChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;

    private static final Pattern BRACKET_PATTERN = Pattern.compile("\\[([^\\]]+)\\]");
    private static final Pattern NUMERIC_REF_PATTERN = Pattern.compile("^\\d{1,2}$");
    // Only the two shapes a real id actually takes: a 36-char uuid, or 32 bare hex chars (the
    // hyphens are stripped before the match). A wider range matched ordinary prose — "facade",
    // "decade", "deadbeef" and any 6-digit number are all valid hex — which classified them as
    // chunk ids and let isFabricated() delete them from the stream. Mirrors thread.js:930 on the
    // client.
    private static final Pattern UUID_PATTERN = Pattern
        .compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern UUID_NO_HYPHENS_PATTERN = Pattern.compile("^[0-9a-fA-F]{32}$");
    // A shortened id, i.e. what ChunkRepository/DocumentRepository.findByIdPrefix resolve (>= 8 hex
    // chars) and what citation-render.js links. Only reachable behind an explicit chunk_id= /
    // document_id= marker, so ordinary hex-looking prose ("[decade]") never lands here.
    private static final Pattern ID_PREFIX_PATTERN = Pattern.compile("^[0-9a-fA-F]{8,31}$");

    private static boolean isValidUuid(String value) {
        if (value == null) {
            return false;
        }
        String clean = value.trim();
        return UUID_PATTERN.matcher(clean).matches()
            || UUID_NO_HYPHENS_PATTERN.matcher(clean).matches();
    }

    /**
     * True for a truncated but well-formed id: resolvable by the UI, not by the existence query.
     */
    private static boolean isIdPrefix(String value) {
        if (value == null) {
            return false;
        }
        return ID_PREFIX_PATTERN.matcher(value.trim().replace("-", "")).matches();
    }

    /** True for the kinds that name a corpus row, i.e. the only ones that can be fabricated. */
    private static boolean isIdKind(String kind) {
        return "chunk".equals(kind) || "document".equals(kind) || KIND_BARE_ID.equals(kind);
    }

    /** Parses a citation value already validated by {@link #isValidUuid} into a UUID. */
    private static UUID toUuid(String value) {
        String clean = value.trim().toLowerCase();
        if (UUID_NO_HYPHENS_PATTERN.matcher(clean).matches()) {
            clean =
                clean.substring(0, 8) + "-" + clean.substring(8, 12) + "-" + clean.substring(12, 16)
                    + "-" + clean.substring(16, 20) + "-" + clean.substring(20);
        }
        return UUID.fromString(clean);
    }

    /** Resolved existence of all chunk/document ids referenced by a batch of citations. */
    /**
     * A bracketed bare uuid, with no {@code chunk_id=}/{@code document_id=} prefix to say which
     * table it refers to. Resolved against both.
     */
    private static final String KIND_BARE_ID = "id";

    private record ResolvedIds(Set<UUID> existingChunks, Set<UUID> existingDocuments) {
        boolean chunkExists(String value) {
            return existingChunks.contains(toUuid(value));
        }

        boolean documentExists(String value) {
            return existingDocuments.contains(toUuid(value));
        }
    }

    /**
     * Collects all valid ids from the parsed citations and checks existence in one query per table.
     */
    private ResolvedIds resolveIds(List<ParsedCitation> parsed) {
        Set<UUID> chunkIds = new HashSet<>();
        Set<UUID> documentIds = new HashSet<>();
        for (ParsedCitation p : parsed) {
            if (!isValidUuid(p.value)) {
                continue;
            }
            if ("chunk".equals(p.kind) || KIND_BARE_ID.equals(p.kind)) {
                chunkIds.add(toUuid(p.value));
            }
            if ("document".equals(p.kind) || KIND_BARE_ID.equals(p.kind)) {
                documentIds.add(toUuid(p.value));
            }
        }
        return new ResolvedIds(chunkRepository.findExistingIds(chunkIds),
            documentRepository.findExistingIds(documentIds));
    }

    public enum CitationStatus {
        REAL, REAL_AMBIG,
        /**
         * The citation could not be checked in the form given — it is neither confirmed nor
         * refuted. Reserved for a truncated id: the UI resolves those by prefix, the existence
         * query needs the full id, and reporting them FABRICATED told the reviewer to strip
         * citations that are almost certainly real.
         */
        UNVERIFIED, FABRICATED
    }

    public static class ParsedCitation {
        public final String raw;
        public final String kind; // "chunk", "document", "unknown"
        public final String value;

        public ParsedCitation(String raw, String kind, String value) {
            this.raw = raw;
            this.kind = kind;
            this.value = value;
        }
    }

    public static class CitationCheckResult {
        private String citation;
        private String kind;
        private CitationStatus status;
        private String detail;

        // Default constructor for Jackson deserialization
        public CitationCheckResult() {}

        public CitationCheckResult(String citation, String kind, CitationStatus status,
            String detail) {
            this.citation = citation;
            this.kind = kind;
            this.status = status;
            this.detail = detail;
        }

        public String getCitation() {
            return citation;
        }

        public String getKind() {
            return kind;
        }

        public CitationStatus getStatus() {
            return status;
        }

        public String getDetail() {
            return detail;
        }

        public void setCitation(String citation) {
            this.citation = citation;
        }

        public void setKind(String kind) {
            this.kind = kind;
        }

        public void setStatus(CitationStatus status) {
            this.status = status;
        }

        public void setDetail(String detail) {
            this.detail = detail;
        }
    }

    public CitationVerifier(ChunkRepository chunkRepository,
        DocumentRepository documentRepository) {
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
    }

    public ParsedCitation parseCitation(String raw) {
        String s = raw.trim();
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1).trim();
        }
        // Numeric reference markers like [1], [42]
        if (NUMERIC_REF_PATTERN.matcher(s).matches()) {
            return new ParsedCitation(raw, "numref", s);
        }
        String low = s.toLowerCase();
        String kind;
        String value;

        if (low.startsWith("chunk_id=")) {
            value = s.substring(9).trim();
            kind = "chunk";
        } else if (low.startsWith("document_id=")) {
            value = s.substring(12).trim();
            kind = "document";
        } else {
            String frag = s.replace("-", "");
            if (UUID_NO_HYPHENS_PATTERN.matcher(frag).matches()) {
                value = frag;
                // A bare uuid does not say which table it belongs to. Assuming "chunk" meant a
                // document id resolved against `chunks` only, found nothing, and was DELETED from
                // the live stream — real text removed from the user's answer on a guess.
                kind = KIND_BARE_ID;
            } else {
                value = s;
                kind = "unknown";
            }
        }
        return new ParsedCitation(raw, kind, value);
    }

    public List<CitationCheckResult> checkCitations(List<String> citations) {
        if (citations == null || citations.isEmpty()) {
            return Collections.emptyList();
        }

        List<ParsedCitation> parsedList = citations.stream().map(this::parseCitation).toList();
        ResolvedIds resolved = resolveIds(parsedList);

        List<CitationCheckResult> results = new ArrayList<>();
        for (ParsedCitation parsed : parsedList) {
            results.add(classify(parsed, resolved));
        }

        return results;
    }

    /**
     * The single classifier. Every path — the reported table, the streaming filter and the text
     * cleaner — derives its verdict from here, so they cannot drift apart. It reports what the
     * citation IS; whether that verdict is strong enough to delete text is decided by
     * {@link #shouldStrip}.
     *
     * <p>
     * Note what this cannot see: existence is resolved by id, chunk text is never loaded, so a REAL
     * verdict says the cited row exists and nothing about whether it supports the claim.
     */
    private CitationCheckResult classify(ParsedCitation parsed, ResolvedIds resolved) {
        String raw = parsed.raw;
        if (KIND_BARE_ID.equals(parsed.kind)) {
            // Either table resolving is enough: the id is real, only its table was unstated.
            if (isValidUuid(parsed.value)) {
                if (resolved.chunkExists(parsed.value)) {
                    return new CitationCheckResult(raw, parsed.kind, CitationStatus.REAL,
                        "1 chunk(s)");
                }
                if (resolved.documentExists(parsed.value)) {
                    return new CitationCheckResult(raw, parsed.kind, CitationStatus.REAL,
                        "1 document(s)");
                }
                return new CitationCheckResult(raw, parsed.kind, CitationStatus.FABRICATED,
                    "0 chunk(s), 0 document(s)");
            }
            return new CitationCheckResult(raw, parsed.kind, CitationStatus.UNVERIFIED,
                "truncated id — send the full id to have it checked");
        }
        if ("chunk".equals(parsed.kind)) {
            if (isValidUuid(parsed.value)) {
                return resolved.chunkExists(parsed.value)
                    ? new CitationCheckResult(raw, parsed.kind, CitationStatus.REAL, "1 chunk(s)")
                    : new CitationCheckResult(raw, parsed.kind, CitationStatus.FABRICATED,
                        "0 chunk(s)");
            }
            if (isIdPrefix(parsed.value)) {
                return new CitationCheckResult(raw, parsed.kind, CitationStatus.UNVERIFIED,
                    "truncated chunk id — send the full id to have it checked");
            }
            return new CitationCheckResult(raw, parsed.kind, CitationStatus.FABRICATED,
                "invalid chunk ID format");
        }
        if ("document".equals(parsed.kind)) {
            if (isValidUuid(parsed.value)) {
                return resolved.documentExists(parsed.value)
                    ? new CitationCheckResult(raw, parsed.kind, CitationStatus.REAL,
                        "1 document(s)")
                    : new CitationCheckResult(raw, parsed.kind, CitationStatus.FABRICATED,
                        "0 document(s)");
            }
            if (isIdPrefix(parsed.value)) {
                return new CitationCheckResult(raw, parsed.kind, CitationStatus.UNVERIFIED,
                    "truncated document id — send the full id to have it checked");
            }
            return new CitationCheckResult(raw, parsed.kind, CitationStatus.FABRICATED,
                "invalid document ID format");
        }
        if ("numref".equals(parsed.kind)) {
            // [1]/[2] are the output format every playbook mandates — pointers into the answer's
            // own ## References section, not source ids. Reporting them FABRICATED told the MAS
            // reviewer to strip correct citations.
            return new CitationCheckResult(raw, parsed.kind, CitationStatus.REAL,
                "numbered reference marker");
        }
        return new CitationCheckResult(raw, parsed.kind, CitationStatus.FABRICATED,
            "unrecognized citation format");
    }

    /**
     * The single deletion rule, shared by the streaming filter and
     * {@link #cleanFabricatedCitations}. Reporting may be thorough; deletion must be conservative,
     * so only a citation that names a corpus row AND is provably bad is removed. Everything else —
     * ordinary bracketed prose, a markdown link label, a mermaid node name, a numbered reference, a
     * truncated id — is text the user wrote or can still resolve, and stays.
     */
    private boolean shouldStrip(ParsedCitation parsed, ResolvedIds resolved) {
        return isIdKind(parsed.kind)
            && classify(parsed, resolved).getStatus() == CitationStatus.FABRICATED;
    }

    public String cleanFabricatedCitations(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        // First pass: parse all bracketed citations so existence can be checked in one
        // query per table instead of one per citation.
        List<ParsedCitation> parsedList = new ArrayList<>();
        Matcher scanner = BRACKET_PATTERN.matcher(text);
        while (scanner.find()) {
            parsedList.add(parseCitation(scanner.group(0)));
        }
        ResolvedIds resolved = resolveIds(parsedList);

        Matcher matcher = BRACKET_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();

        int matchIndex = 0;
        while (matcher.find()) {
            String rawMatch = matcher.group(0); // e.g. "[chunk_id=8f7c1a2b]"
            ParsedCitation parsed = parsedList.get(matchIndex++);

            if (shouldStrip(parsed, resolved)) {
                matcher.appendReplacement(sb, "");
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(rawMatch));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Whether the streaming filter must DELETE this bracketed run from the live answer. Anything
     * this reports true for never reaches the user, so it answers the narrow question "is this
     * provably a bad corpus id?" — see {@link #shouldStrip}. A markdown link label, a mermaid node
     * name, "[Optional]" and a truncated id all return false.
     */
    public boolean isFabricated(String rawCitation) {
        ParsedCitation parsed = parseCitation(rawCitation);
        if (!isIdKind(parsed.kind)) {
            // Ordinary bracket text or a numbered reference: no id to look up, nothing to delete.
            return false;
        }
        List<ParsedCitation> single = List.of(parsed);
        return shouldStrip(parsed, resolveIds(single));
    }
}
