package de.palsoftware.yvoke.collection.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.tag.core.service.TagService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

/**
 * The tag-options fragment endpoint, which three admin pages share.
 *
 * <p>
 * htmx sends the TRIGGERING element's own name/value on a GET, and the three pages cannot agree on
 * one name: {@code /admin/ingest} and {@code /admin/search} own a select named {@code collection},
 * while the connectors form must name its select {@code targetCollection} so it binds to
 * {@code ConfluenceInstanceForm}. Reading only {@code collection} meant the connectors page
 * received {@code ?targetCollection=…} and resolved NO collection — the tag container swapped in
 * empty and stayed hidden, so every sync of a tagged collection failed on a blank tag and editing
 * an instance silently wiped its stored tag.
 */
class CollectionAdminControllerTest {

    private static final List<String> TAGS = List.of("9.3.1", "10.0");

    private CollectionService collectionService;
    private CollectionAdminController controller;

    @BeforeEach
    void setUp() {
        collectionService = mock(CollectionService.class);
        controller = new CollectionAdminController(collectionService, mock(TagService.class));

        when(collectionService.getCollection("OIM - Docs")).thenReturn(
            Optional.of(new Collection(UUID.randomUUID(), "OIM - Docs", "docs", TAGS, null)));
    }

    private Model optionsFor(String collectionParam, String targetCollectionParam, String url) {
        Model model = new ConcurrentModel();
        controller.getVersionOptions(null, collectionParam, targetCollectionParam, url, model);
        return model;
    }

    @Test
    void theIngestAndSearchPagesKeepSendingTheirOwnParameterName() {
        Model model = optionsFor("OIM - Docs", null, "http://localhost:8080/admin/ingest");

        assertThat(model.getAttribute("tags")).isEqualTo(TAGS);
        assertThat(model.getAttribute("versions")).isEqualTo(TAGS);
    }

    /** The connectors page's select is named for form binding, so it sends targetCollection. */
    @Test
    void theConnectorsFormsParameterNameResolvesTheSameCollection() {
        Model model = optionsFor(null, "OIM - Docs", "http://localhost:8080/admin/connectors");

        assertThat(model.getAttribute("tags")).isEqualTo(TAGS);
        assertThat(model.getAttribute("versions")).isEqualTo(TAGS);
    }

    @Test
    void eachPageGetsItsOwnFragment() {
        Model model = new ConcurrentModel();
        assertThat(controller.getVersionOptions(null, null, "OIM - Docs",
            "http://localhost:8080/admin/connectors", model))
            .isEqualTo("admin/connectors :: tag-container");
        assertThat(controller.getVersionOptions(null, "OIM - Docs", null,
            "http://localhost:8080/admin/search", model))
            .isEqualTo("admin/search :: tag-container");
        assertThat(controller.getVersionOptions(null, "OIM - Docs", null,
            "http://localhost:8080/admin/ingest", model))
            .isEqualTo("admin/ingest :: tag-container");
    }

    /** A path variable still wins, and an unknown collection yields an empty (hidden) container. */
    @Test
    void anUnknownCollectionYieldsNoTagsInsteadOfFailing() {
        Model model = optionsFor("No Such Collection", null, null);

        assertThat(model.getAttribute("tags")).isEqualTo(List.of());
        assertThat(model.getAttribute("versions")).isEqualTo(List.of());
    }

    @Test
    void aPathVariableTakesPrecedenceOverBothQueryParameters() {
        Model model = new ConcurrentModel();
        controller.getVersionOptions("OIM - Docs", "Other", "Other Still", null, model);

        assertThat(model.getAttribute("tags")).isEqualTo(TAGS);
    }

    /**
     * The add-tag datalist offers the vocabulary already declared across collections, derived from
     * {@code collections.tags}. It used to come from a {@code tags} registry table that only the
     * admin forms and the ingest enqueue ever wrote to, so a tag that arrived with an imported
     * collection was never suggested anywhere.
     */
    @Test
    void theCollectionsPageSuggestsEveryTagDeclaredAcrossCollections() {
        when(collectionService.listCollections()).thenReturn(List.of());
        when(collectionService.listAllTags()).thenReturn(List.of("10.0", "9.3.1", "content"));
        Model model = new ConcurrentModel();

        controller.viewCollections(model);

        assertThat(model.getAttribute("allTags")).isEqualTo(List.of("10.0", "9.3.1", "content"));
    }
}
