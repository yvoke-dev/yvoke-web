package de.palsoftware.yvoke.collection.web.admin;

import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import static org.mockito.Mockito.verify;

import static org.mockito.ArgumentMatchers.eq;

import static org.mockito.ArgumentMatchers.any;

import de.palsoftware.yvoke.area.TestAreas;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.tag.core.service.TagService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;

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
    private TagService tagService;
    private CollectionAdminController controller;

    @BeforeEach
    void setUp() {
        collectionService = mock(CollectionService.class);
        tagService = mock(TagService.class);
        controller = new CollectionAdminController(collectionService, tagService,
            TestAreas.withAreas("OIM"));

        SecurityContextHolder.getContext()
            .setAuthentication(new TestingAuthenticationToken("admin", "pass", "ROLE_ADMIN"));

        when(collectionService.getCollection("OIM - Docs")).thenReturn(Optional
            .of(new Collection(UUID.randomUUID(), "OIM - Docs", "docs", TAGS, null, "OIM")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
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

    @Test
    void getVersionOptions_asAreaAdmin_returnsEmptyForCollectionInUnadministeredArea() {
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("user", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_PINGID"));

        Model model = optionsFor("OIM - Docs", null, "http://localhost:8080/admin/ingest");

        assertThat(model.getAttribute("tags")).isEqualTo(List.of());
        assertThat(model.getAttribute("versions")).isEqualTo(List.of());
    }

    @Test
    void viewCollections_asAreaAdmin_scopesSuggestedTagsToAdministeredCollections() {
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("user", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_OIM"));
        when(collectionService.listForAdminUser()).thenReturn(
            List.of(new Collection(UUID.randomUUID(), "OIM - Docs", "docs", TAGS, null, "OIM")));
        when(collectionService.listAllTags())
            .thenReturn(List.of("10.0", "9.3.1", "pingid-secret-tag"));

        Model model = new ConcurrentModel();
        controller.viewCollections(model);

        assertThat(model.getAttribute("allTags")).isEqualTo(List.of("10.0", "9.3.1"));
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
        when(collectionService.listForAdminUser()).thenReturn(List.of());
        when(collectionService.listAllTags()).thenReturn(List.of("10.0", "9.3.1", "content"));
        Model model = new ConcurrentModel();

        controller.viewCollections(model);

        assertThat(model.getAttribute("allTags")).isEqualTo(List.of("10.0", "9.3.1", "content"));
    }

    /** P1-12: the area chosen on the create form and on a card's move form reaches the service. */
    @Test
    void thePostedAreaReachesCreateAndMove() throws Exception {
        when(collectionService.findByName("OIM - Docs")).thenReturn(Optional
            .of(new Collection(UUID.randomUUID(), "OIM - Docs", "docs", TAGS, null, "OIM")));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/admin/collections").param("name", "Ping - Docs").param("area", "PingID"))
            .andExpect(status().is3xxRedirection());
        verify(collectionService).createCollection(eq("Ping - Docs"), any(), eq("PingID"));

        mvc.perform(
            post("/admin/collections/area").param("name", "OIM - Docs").param("area", "PingID"))
            .andExpect(status().is3xxRedirection());
        verify(collectionService).moveCollection("OIM - Docs", "PingID");
    }

    /** The messages name the collection and area as stored, not as typed. */
    @Test
    void theMessagesNameTheStoredNames() throws Exception {
        when(collectionService.findByName("OIM - Docs")).thenReturn(Optional
            .of(new Collection(UUID.randomUUID(), "OIM - Docs", "docs", TAGS, null, "OIM")));
        when(collectionService.createCollection(" Ping - Docs ", null, "pingid")).thenReturn(
            new Collection(UUID.randomUUID(), "Ping - Docs", null, List.of(), null, "PingID"));
        when(collectionService.moveCollection("OIM - Docs", "pingid")).thenReturn("PingID");

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(
            post("/admin/collections").param("name", " Ping - Docs ").param("area", "pingid"))
            .andExpect(
                flash().attribute("success", "Collection 'Ping - Docs' created successfully."));
        mvc.perform(
            post("/admin/collections/area").param("name", "OIM - Docs").param("area", "pingid"))
            .andExpect(
                flash().attribute("success", "Collection 'OIM - Docs' moved to area 'PingID'."));
    }

    @Test
    void createCollection_asAreaAdmin_failsWhenAreaNotAdministered() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("user", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_OIM"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(
            post("/admin/collections").param("name", "Finance - Docs").param("area", "Finance"))
            .andExpect(status().isForbidden());
    }

    @Test
    void createCollection_asAreaAdmin_failsWhenCollectionNameAlreadyExistsInAnotherArea()
        throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("user", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_OIM"));
        when(collectionService.findByName("ExistingColl")).thenReturn(Optional.of(
            new Collection(UUID.randomUUID(), "ExistingColl", null, List.of(), null, "Finance")));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        assertThrows(Exception.class, () -> mvc.perform(
            post("/admin/collections").param("name", "ExistingColl").param("area", "OIM")));
    }

    @Test
    void moveCollection_failsWhenSourceAreaNotAdministered() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("user", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_OIM"));
        when(collectionService.findByName("Finance - Docs")).thenReturn(Optional.of(
            new Collection(UUID.randomUUID(), "Finance - Docs", null, List.of(), null, "Finance")));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(
            post("/admin/collections/area").param("name", "Finance - Docs").param("area", "OIM"))
            .andExpect(status().isForbidden());
    }

    @Test
    void moveCollection_failsWhenTargetAreaNotAdministered() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("user", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_OIM"));
        when(collectionService.findByName("OIM - Docs")).thenReturn(Optional
            .of(new Collection(UUID.randomUUID(), "OIM - Docs", null, List.of(), null, "OIM")));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(
            post("/admin/collections/area").param("name", "OIM - Docs").param("area", "Finance"))
            .andExpect(status().isForbidden());
    }

    @Test
    void moveCollection_succeedsWhenBothAreasAdministered() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("user",
            "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_OIM", "AREA_ADMIN_SECURITY"));
        when(collectionService.findByName("OIM - Docs")).thenReturn(Optional
            .of(new Collection(UUID.randomUUID(), "OIM - Docs", null, List.of(), null, "OIM")));
        when(collectionService.moveCollection("OIM - Docs", "Security")).thenReturn("Security");

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(
            post("/admin/collections/area").param("name", "OIM - Docs").param("area", "Security"))
            .andExpect(status().is3xxRedirection()).andExpect(
                flash().attribute("success", "Collection 'OIM - Docs' moved to area 'Security'."));
    }

    @Test
    void addCollectionTag_failsWhenCollectionNotAdministered() {
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("user", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_OIM"));
        UUID colId = UUID.randomUUID();
        when(collectionService.findByIdForAdmin(colId)).thenReturn(Optional.empty());

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        assertThrows(Exception.class, () -> mvc.perform(post("/admin/collections/add-tag")
            .param("collectionId", colId.toString()).param("tag", "v1")));
    }

    @Test
    void addCollectionTag_succeedsWhenCollectionAdministered() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("user", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_OIM"));
        UUID colId = UUID.randomUUID();
        when(collectionService.findByIdForAdmin(colId)).thenReturn(
            Optional.of(new Collection(colId, "OIM - Docs", null, List.of(), null, "OIM")));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/admin/collections/add-tag").param("collectionId", colId.toString())
            .param("tag", "v1")).andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("success", "Tag added successfully."));

        verify(tagService).addTagToCollection(colId, "v1");
    }
}
