package de.palsoftware.yvoke.mcp.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import de.palsoftware.yvoke.rag.core.service.CitationVerifier;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.tool.annotation.Tool;

public class VerifyCitationsToolTest {

    private CitationVerifier citationVerifier;
    private VerifyCitationsTool verifyCitationsTool;

    @BeforeEach
    public void setUp() {
        citationVerifier = mock(CitationVerifier.class);
        verifyCitationsTool = new VerifyCitationsTool(citationVerifier);
    }

    @Test
    public void testVerifyCitations() {
        CitationVerifier.CitationCheckResult result = new CitationVerifier.CitationCheckResult(
            "[chunk_id=8f7c1a2b-3c4d-5e6f-7a8b-9c0d1e2f3a4b]", "chunk",
            CitationVerifier.CitationStatus.REAL, "1 chunk(s)");

        when(citationVerifier
            .checkCitations(eq(List.of("[chunk_id=8f7c1a2b-3c4d-5e6f-7a8b-9c0d1e2f3a4b]"))))
            .thenReturn(List.of(result));

        String output = verifyCitationsTool
            .verifyCitations(List.of("[chunk_id=8f7c1a2b-3c4d-5e6f-7a8b-9c0d1e2f3a4b]"));
        assertTrue(output.contains("Citation check — 1 cited, 0 FABRICATED"));
        assertTrue(output.contains("[chunk_id=8f7c1a2b-3c4d-5e6f-7a8b-9c0d1e2f3a4b]"));
        assertTrue(output.contains("REAL"));
    }

    @Test
    public void testVerifyCitationsValidation() {
        String outputNull = verifyCitationsTool.verifyCitations(null);
        assertTrue(outputNull.contains("Error: 'citations' parameter is required."));

        String outputEmpty = verifyCitationsTool.verifyCitations(List.of());
        assertTrue(outputEmpty.contains("Error: 'citations' parameter is required."));
    }

    @Test
    public void testSuccessMessageDoesNotClaimTheCitedTextSupportsTheClaim() {
        // The tool resolves ids against `chunks.id` and never loads chunk TEXT, so it cannot see
        // whether the sentence it is attached to is faithful to the source. "All citations resolve
        // to real corpus entries ✅" read as a faithfulness verdict — on a ~40% German corpus an
        // invented English paraphrase carrying a real German chunk id passed as verified.
        CitationVerifier.CitationCheckResult real = new CitationVerifier.CitationCheckResult(
            "[chunk_id=8f7c1a2b-3c4d-5e6f-7a8b-9c0d1e2f3a4b]", "chunk",
            CitationVerifier.CitationStatus.REAL, "1 chunk(s)");
        when(citationVerifier.checkCitations(anyList())).thenReturn(List.of(real));

        String output = verifyCitationsTool
            .verifyCitations(List.of("[chunk_id=8f7c1a2b-3c4d-5e6f-7a8b-9c0d1e2f3a4b]"));

        assertFalse(output.contains("All citations resolve to real corpus entries"));
        assertTrue(output.contains("Every cited id exists in the corpus."));
        assertTrue(output.contains(VerifyCitationsTool.SCOPE_NOTE));
        assertTrue(VerifyCitationsTool.SCOPE_NOTE.contains("does not read the cited text"));
    }

    @Test
    public void testUnverifiedCitationsAreNotCountedAsFabricated() {
        CitationVerifier.CitationCheckResult unverified =
            new CitationVerifier.CitationCheckResult("[chunk_id=8f7c1a2b]", "chunk",
                CitationVerifier.CitationStatus.UNVERIFIED, "truncated chunk id");
        when(citationVerifier.checkCitations(anyList())).thenReturn(List.of(unverified));

        String output = verifyCitationsTool.verifyCitations(List.of("[chunk_id=8f7c1a2b]"));

        assertTrue(output.contains("Citation check — 1 cited, 0 FABRICATED, 1 UNVERIFIED"));
        assertTrue(output.contains("UNVERIFIED means not checked, not wrong"));
        assertFalse(output.contains("Every cited id exists in the corpus."));
    }

    @Test
    public void testFabricatedCitationsKeepTheirForce() {
        CitationVerifier.CitationCheckResult fake = new CitationVerifier.CitationCheckResult(
            "[chunk_id=00000000-0000-0000-0000-000000000000]", "chunk",
            CitationVerifier.CitationStatus.FABRICATED, "0 chunk(s)");
        when(citationVerifier.checkCitations(anyList())).thenReturn(List.of(fake));

        String output = verifyCitationsTool
            .verifyCitations(List.of("[chunk_id=00000000-0000-0000-0000-000000000000]"));

        assertTrue(output.contains("Citation check — 1 cited, 1 FABRICATED"));
        assertTrue(output.contains("**Remove or correct every FABRICATED citation"));
        assertFalse(output.contains("Every cited id exists in the corpus."));
    }

    @Test
    public void testToolDescriptionStatesWhatItDoesNotVerify() throws Exception {
        // The description is the only thing the model reads before trusting the verdict. Both
        // annotations must carry the same text — they were two hand-copied strings.
        Method method = VerifyCitationsTool.class.getMethod("verifyCitations", List.class);
        String mcpDescription = method.getAnnotation(McpTool.class).description();
        String toolDescription = method.getAnnotation(Tool.class).description();

        assertEquals(VerifyCitationsTool.DESCRIPTION, mcpDescription);
        assertEquals(VerifyCitationsTool.DESCRIPTION, toolDescription);
        assertTrue(mcpDescription.contains("does not read the cited text"));
        assertTrue(mcpDescription.contains("UNVERIFIED"));
        // The tool checks ids, not claims; selling it as blanket anti-hallucination is the
        // over-claim itself.
        assertFalse(mcpDescription.contains("anti-hallucination"));
    }

    @Test
    public void testParameterDescriptionDoesNotAdvertiseATruncatedId() {
        // The old example was "[chunk_id=8f7c1a2b]" — a token this very tool then reported
        // FABRICATED with "invalid chunk ID format".
        assertFalse(
            VerifyCitationsTool.CITATIONS_PARAM_DESCRIPTION.contains("[chunk_id=8f7c1a2b]"));
        assertTrue(VerifyCitationsTool.CITATIONS_PARAM_DESCRIPTION.contains("full"));
    }
}
