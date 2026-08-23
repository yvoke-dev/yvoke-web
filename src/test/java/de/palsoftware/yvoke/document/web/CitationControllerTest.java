package de.palsoftware.yvoke.document.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import de.palsoftware.yvoke.document.core.model.SectionResponse;
import de.palsoftware.yvoke.document.core.service.SectionService;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

public class CitationControllerTest {

    private SectionService sectionService;
    private Model model;

    @BeforeEach
    public void setUp() {
        sectionService = mock(SectionService.class);
        model = new ConcurrentModel();
    }

    @Test
    public void testGetCitationWithPositiveLimit() {
        CitationController controller = new CitationController(sectionService);
        SectionResponse mockResponse = new SectionResponse(List.of("Intro"), "file.md", "9.3", 1,
            "heading only", "Sample text");

        when(sectionService.getChunkContent("chunk123")).thenReturn(mockResponse);

        String view = controller.getCitation("chunk123", null, model);

        assertThat(view).isEqualTo("document/fragments/citation-expander");
        assertThat(model.getAttribute("section")).isEqualTo(mockResponse);
        assertThat(model.getAttribute("error")).isNull();
    }

    @Test
    public void testGetCitationWithDisabledLimitNegative() {
        CitationController controller = new CitationController(sectionService);
        SectionResponse mockResponse = new SectionResponse(List.of("Intro"), "file.md", "9.3", 1,
            "heading only", "Sample text");

        when(sectionService.getChunkContent("chunk123")).thenReturn(mockResponse);

        String view = controller.getCitation("chunk123", null, model);

        assertThat(view).isEqualTo("document/fragments/citation-expander");
        assertThat(model.getAttribute("section")).isEqualTo(mockResponse);
        assertThat(model.getAttribute("error")).isNull();
    }

    @Test
    public void testGetCitationWithDisabledLimitZero() {
        CitationController controller = new CitationController(sectionService);
        SectionResponse mockResponse = new SectionResponse(List.of("Intro"), "file.md", "9.3", 1,
            "heading only", "Sample text");

        when(sectionService.getChunkContent("chunk123")).thenReturn(mockResponse);

        String view = controller.getCitation("chunk123", null, model);

        assertThat(view).isEqualTo("document/fragments/citation-expander");
        assertThat(model.getAttribute("section")).isEqualTo(mockResponse);
        assertThat(model.getAttribute("error")).isNull();
    }

    @Test
    public void testGetCitationByDocumentId() {
        CitationController controller = new CitationController(sectionService);
        SectionResponse mockResponse = new SectionResponse(List.of("Intro"), "file.md", "9.3", 1,
            "heading only", "Sample text");

        when(sectionService.getSectionByDocumentId("doc123", null)).thenReturn(mockResponse);

        String view = controller.getCitation(null, "doc123", model);

        assertThat(view).isEqualTo("document/fragments/citation-expander");
        assertThat(model.getAttribute("section")).isEqualTo(mockResponse);
        assertThat(model.getAttribute("error")).isNull();
    }

    @Test
    public void testGetCitationMissingParams() {
        CitationController controller = new CitationController(sectionService);

        String view = controller.getCitation(null, null, model);

        assertThat(view).isEqualTo("document/fragments/citation-expander");
        assertThat(model.getAttribute("section")).isNull();
        assertThat((String) model.getAttribute("error"))
            .contains("Either chunkId or documentId must be provided");
    }

    @Test
    public void testAmbiguousLookupDoesNotLeakDocumentInventory() {
        CitationController controller = new CitationController(sectionService);

        // Any lookup that cannot be narrowed to one document enumerates every match with its
        // full id, kind, title and tags. That inventory must never reach the citation dialog.
        when(sectionService.getSectionByDocumentId("ADSAccount", null))
            .thenThrow(new IllegalArgumentException("""
                multiple documents match — pass document_id for one:
                  6f1d0b0e-2c4a-4f0d-9a1b-2e3c4d5e6f70  [table]  ADSAccount  (9.3.1)
                  8a2e1c1f-3d5b-4a1e-8b2c-3f4d5e6f7081  [entity_model]  ADSAccount  (10.0)"""));

        String view = controller.getCitation(null, "ADSAccount", model);

        assertThat(view).isEqualTo("document/fragments/citation-expander");
        assertThat(model.getAttribute("section")).isNull();
        String error = (String) model.getAttribute("error");
        assertThat(error).isNotNull();
        assertThat(error).doesNotContain("6f1d0b0e-2c4a-4f0d-9a1b-2e3c4d5e6f70");
        assertThat(error).doesNotContain("8a2e1c1f-3d5b-4a1e-8b2c-3f4d5e6f7081");
        assertThat(error).doesNotContain("ADSAccount");
        assertThat(error).doesNotContain("entity_model");
        assertThat(error).doesNotContain("multiple documents match");
    }

    @Test
    public void testMissingDocumentIdYieldsNotFoundMessage() {
        CitationController controller = new CitationController(sectionService);

        // A re-ingested or deleted id is a stale reference, not an outage. SectionService signals
        // that with NoSuchElementException, which must not fall through to the generic handler.
        when(sectionService.getSectionByDocumentId("11111111-2222-3333-4444-555555555555", null))
            .thenThrow(new NoSuchElementException(
                "(no document found for id '11111111-2222-3333-4444-555555555555')"));

        String view = controller.getCitation(null, "11111111-2222-3333-4444-555555555555", model);

        assertThat(view).isEqualTo("document/fragments/citation-expander");
        assertThat(model.getAttribute("section")).isNull();
        String error = (String) model.getAttribute("error");
        assertThat(error).isNotNull();
        assertThat(error).isEqualTo(CitationController.NOT_FOUND_ERROR);
        assertThat(error).isNotEqualTo(CitationController.UNAVAILABLE_ERROR);
        assertThat(error).doesNotContain("11111111-2222-3333-4444-555555555555");
    }

    @Test
    public void testGetCitationServiceExceptionIsNotLeaked() {
        CitationController controller = new CitationController(sectionService);

        // An unexpected (e.g. data-access) failure must not surface its raw message — which can
        // carry SQL / provider / stack detail — into the rendered fragment (SEC-17 / ARC-10).
        when(sectionService.getChunkContent(any()))
            .thenThrow(new RuntimeException("ERROR: relation \"chunks\" does not exist"));

        String view = controller.getCitation("chunk123", null, model);

        assertThat(view).isEqualTo("document/fragments/citation-expander");
        assertThat(model.getAttribute("section")).isNull();
        String error = (String) model.getAttribute("error");
        assertThat(error).isNotNull();
        assertThat(error).doesNotContain("relation");
        assertThat(error).doesNotContain("chunks");
        assertThat(error).doesNotContain("does not exist");
    }
}
