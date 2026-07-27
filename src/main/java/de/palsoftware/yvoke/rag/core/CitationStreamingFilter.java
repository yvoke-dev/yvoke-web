package de.palsoftware.yvoke.rag.core;

import de.palsoftware.yvoke.rag.core.service.CitationVerifier;


import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CitationStreamingFilter {
    private static final Logger log = LoggerFactory.getLogger(CitationStreamingFilter.class);

    private final StringBuilder buffer = new StringBuilder();
    private boolean inBrackets = false;
    private final CitationVerifier verifier;
    // Per-stream memo of the existence check (PRF-13): models cite the same chunk/document many
    // times
    // in one answer; this collapses those repeats to a single DB probe per distinct citation
    // instead
    // of one probe per occurrence during SSE. The filter is created fresh per turn, so there is no
    // cross-request staleness.
    private final Map<String, Boolean> fabricatedCache = new HashMap<>();

    public CitationStreamingFilter(CitationVerifier verifier) {
        this.verifier = verifier;
    }

    public List<String> processToken(String token) {
        if (token == null || token.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> output = new ArrayList<>();
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '[') {
                if (inBrackets) {
                    // Flush nested bracket content
                    output.add(buffer.toString());
                    buffer.setLength(0);
                }
                inBrackets = true;
                buffer.append(c);
            } else if (c == ']') {
                if (inBrackets) {
                    buffer.append(c);
                    String citation = buffer.toString();
                    if (fabricatedCache.computeIfAbsent(citation, verifier::isFabricated)) {
                        // Fabricated citation is stripped, don't output anything.
                        log.debug("Stripped fabricated citation token: {}", citation);
                    } else {
                        output.add(citation);
                    }
                    buffer.setLength(0);
                    inBrackets = false;
                } else {
                    output.add(String.valueOf(c));
                }
            } else {
                if (inBrackets) {
                    buffer.append(c);
                    if (buffer.length() > 100) {
                        // If buffer is abnormally long, flush it and stop treatment as citation
                        output.add(buffer.toString());
                        buffer.setLength(0);
                        inBrackets = false;
                    }
                } else {
                    output.add(String.valueOf(c));
                }
            }
        }
        return output;
    }

    public List<String> flush() {
        List<String> output = new ArrayList<>();
        if (buffer.length() > 0) {
            output.add(buffer.toString());
            buffer.setLength(0);
        }
        return output;
    }
}
