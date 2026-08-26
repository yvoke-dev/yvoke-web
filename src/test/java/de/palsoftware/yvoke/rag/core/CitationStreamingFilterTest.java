package de.palsoftware.yvoke.rag.core;

import de.palsoftware.yvoke.document.core.repository.ChunkRepository;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import de.palsoftware.yvoke.rag.core.service.CitationVerifier;


import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

public class CitationStreamingFilterTest {

    private CitationVerifier citationVerifier;
    private CitationStreamingFilter filter;

    @BeforeEach
    public void setUp() {
        citationVerifier = mock(CitationVerifier.class);
        filter = new CitationStreamingFilter(citationVerifier);
    }

    @Test
    public void testEmptyOrNullToken() {
        assertTrue(filter.processToken(null).isEmpty());
        assertTrue(filter.processToken("").isEmpty());
    }

    @Test
    public void testNormalTextNoCitations() {
        assertEquals(List.of("h", "e", "l", "l", "o"), filter.processToken("hello"));
    }

    @Test
    public void testValidCitation() {
        when(citationVerifier.isFabricated("[citation-1]")).thenReturn(false);

        // Process token containing citation start/end
        assertEquals(List.of("t", "e", "x", "t"), filter.processToken("text"));
        assertEquals(List.of("[citation-1]"), filter.processToken("[citation-1]"));
        assertTrue(filter.flush().isEmpty());
    }

    @Test
    public void testRepeatedCitationIsVerifiedOnlyOnce() {
        // PRF-13: the same citation appearing three times in a stream must trigger a single DB
        // probe,
        // not one per occurrence.
        when(citationVerifier.isFabricated("[chunk-abc]")).thenReturn(false);

        assertEquals(List.of("[chunk-abc]"), filter.processToken("[chunk-abc]"));
        assertEquals(List.of("[chunk-abc]"), filter.processToken("[chunk-abc]"));
        assertEquals(List.of("[chunk-abc]"), filter.processToken("[chunk-abc]"));

        verify(citationVerifier, times(1)).isFabricated("[chunk-abc]");
    }

    @Test
    public void testRepeatedFabricatedCitationIsVerifiedOnlyOnceAndStripped() {
        when(citationVerifier.isFabricated("[fake]")).thenReturn(true);

        assertTrue(filter.processToken("[fake]").isEmpty());
        assertTrue(filter.processToken("[fake]").isEmpty());

        verify(citationVerifier, times(1)).isFabricated("[fake]");
    }

    @Test
    public void testFabricatedCitationIsStripped() {
        when(citationVerifier.isFabricated("[fake]")).thenReturn(true);

        assertEquals(List.of(), filter.processToken("[fake]"));
        assertTrue(filter.flush().isEmpty());
    }

    @Test
    public void testSplitCitation() {
        when(citationVerifier.isFabricated("[citation-2]")).thenReturn(false);

        assertEquals(List.of(), filter.processToken("[cit"));
        assertEquals(List.of("[citation-2]"), filter.processToken("ation-2]"));
        assertTrue(filter.flush().isEmpty());
    }

    @Test
    public void testNestedBracketsFlushesInner() {
        // [hello[world]
        assertEquals(List.of("[hello"), filter.processToken("[hello["));
        assertEquals(List.of(), filter.processToken("world"));
        when(citationVerifier.isFabricated("[world]")).thenReturn(false);
        assertEquals(List.of("[world]"), filter.processToken("]"));
    }

    @Test
    public void testAbnormallyLongCitationFlushes() {
        StringBuilder longToken = new StringBuilder("[");
        for (int i = 0; i < 500; i++) {
            longToken.append("a");
        }
        List<String> output = filter.processToken(longToken.toString());
        assertFalse(output.isEmpty());
        assertEquals(1, output.size());
        assertEquals(501, output.get(0).length());
        assertTrue(output.get(0).startsWith("["));
    }

    @Test
    public void testGroupedMultiUuidCitationDoesNotFlushEarly() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();
        String group = "[" + id1 + ", " + id2 + ", " + id3 + "]";
        when(citationVerifier.isFabricated(group)).thenReturn(false);

        List<String> output = filter.processToken(group);
        assertEquals(List.of(group), output);
    }

    @Test
    public void testFlushUnfinishedCitation() {
        assertEquals(List.of(), filter.processToken("[un"));
        assertEquals(List.of("[un"), filter.flush());
    }

    @Test
    public void testNumberedReferencesPassThrough() {
        when(citationVerifier.isFabricated("[1]")).thenReturn(false);
        when(citationVerifier.isFabricated("[42]")).thenReturn(false);

        assertEquals(List.of("[1]"), filter.processToken("[1]"));
        assertEquals(List.of("[42]"), filter.processToken("[42]"));
    }

    /**
     * The tests above mock {@link CitationVerifier}, so they only pin the filter's bracket
     * bookkeeping — never its classification. These run the filter against a REAL verifier (only
     * the repositories are stubbed) so that what actually reaches the user is under test.
     */
    @Nested
    public class WithRealVerifier {

        private static final UUID REAL_CHUNK_ID =
            UUID.fromString("8f7c1a2b-3c4d-5e6f-7a8b-9c0d1e2f3a4b");

        private static final UUID REAL_DOCUMENT_ID =
            UUID.fromString("1a2b3c4d-5e6f-4a8b-9c0d-1e2f3a4b5c6d");

        private CitationStreamingFilter realFilter;

        @BeforeEach
        public void setUp() {
            ChunkRepository chunkRepository = mock(ChunkRepository.class);
            DocumentRepository documentRepository = mock(DocumentRepository.class);
            when(chunkRepository.findExistingIds(anyCollection())).thenAnswer(inv -> {
                Set<UUID> result = new HashSet<>((Collection<UUID>) inv.getArgument(0));
                result.retainAll(Set.of(REAL_CHUNK_ID));
                return result;
            });
            // A real document, not an empty set. This fixture exists because the original bug
            // hid behind a mocked verifier; stubbing documents to empty left the document half of
            // the classifier just as unexercised — and a bare uuid now resolves against BOTH
            // tables, so an always-empty document set would silently cover only half of it.
            when(documentRepository.findExistingIds(anyCollection())).thenAnswer(inv -> {
                Set<UUID> result = new HashSet<>((Collection<UUID>) inv.getArgument(0));
                result.retainAll(Set.of(REAL_DOCUMENT_ID));
                return result;
            });
            realFilter = new CitationStreamingFilter(
                new CitationVerifier(chunkRepository, documentRepository));
        }

        /** Streams the text one character at a time, as an SSE token stream would. */
        private String stream(String text) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                realFilter.processToken(String.valueOf(text.charAt(i))).forEach(out::append);
            }
            realFilter.flush().forEach(out::append);
            return out.toString();
        }

        @Test
        public void testAnExistingDocumentIdSurvivesTheStream() {
            String text = "See [document_id=" + REAL_DOCUMENT_ID + "] for the procedure.";

            assertEquals(text, stream(text));
        }

        /**
         * A bare uuid states no table. Resolving it against chunks alone deleted real document
         * citations mid-sentence from the user's answer.
         */
        @Test
        public void testABareUuidNamingADocumentSurvivesTheStream() {
            String text = "See [" + REAL_DOCUMENT_ID + "] for the procedure.";

            assertEquals(text, stream(text));
        }

        /** A uuid in neither table is still invented, and still removed. */
        @Test
        public void testAUuidInNeitherTableIsStillStripped() {
            String invented = "[" + UUID.fromString("99999999-9999-4999-8999-999999999999") + "]";

            assertFalse(stream("See " + invented + " for the procedure.").contains(invented));
        }

        @Test
        public void testMarkdownLinkLabelSurvives() {
            String text = "See [OIM docs](https://example.com) for details.";
            assertEquals(text, stream(text));
        }

        @Test
        public void testMermaidNodeLabelsSurvive() {
            String text = "flowchart TD\n  A[Start] --> B{Decision}\n  B -->|yes| C[Do X]";
            assertEquals(text, stream(text));
        }

        @Test
        public void testOrdinaryBracketedProseSurvives() {
            String text = "The parameter is [Optional] and the default is [100].";
            assertEquals(text, stream(text));
        }

        @Test
        public void testFileTokenSurvives() {
            // [file=…] is not a citation (those are chunk ids and document ids), but it is not a
            // fabricated one either — it is bracketed prose, and the filter deletes only what is
            // provably a bad id.
            String text = "Defined in [file=ADSAccount.md].";
            assertEquals(text, stream(text));
        }

        @Test
        public void testRealChunkCitationSurvives() {
            String text = "A grounded claim [chunk_id=" + REAL_CHUNK_ID + "].";
            assertEquals(text, stream(text));
        }

        @Test
        public void testHexLikeProseSurvivesTheStream() {
            // Words spelled from hex letters, and plain 6-digit numbers, are not chunk ids.
            String text =
                "Error code [500123] in the [facade] pattern; see [8f7c1a2b] and [decade].";
            assertEquals(text, stream(text));
        }

        @Test
        public void testTruncatedChunkCitationSurvives() {
            // The citation expander resolves a chunk by id PREFIX (>= 8 hex chars), so this form is
            // clickable in the UI — deleting it from the stream threw away a working citation.
            String text = "A grounded claim [chunk_id=8f7c1a2b].";
            assertEquals(text, stream(text));
        }

        @Test
        public void testFabricatedChunkCitationIsStillStripped() {
            String text = "A made-up claim [chunk_id=00000000-0000-0000-0000-000000000000].";
            assertEquals("A made-up claim .", stream(text));
        }

        @Test
        public void testFabricatedDocumentCitationIsStillStripped() {
            String text = "A made-up claim [document_id=11111111-1111-1111-1111-111111111111].";
            assertEquals("A made-up claim .", stream(text));
        }
    }
}
