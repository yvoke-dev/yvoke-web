package de.palsoftware.yvoke.jsonobject.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.jsonobject.core.model.JsonObject;
import de.palsoftware.yvoke.jsonobject.core.service.JsonObjectService;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

public class JsonObjectAdminControllerTest {

    private JsonObjectService jsonObjectService;
    private CollectionService collectionService;
    private ObjectMapper objectMapper;
    private JsonObjectAdminController controller;
    private Model model;

    @BeforeEach
    public void setUp() {
        jsonObjectService = mock(JsonObjectService.class);
        collectionService = mock(CollectionService.class);
        objectMapper = new ObjectMapper();
        controller =
            new JsonObjectAdminController(jsonObjectService, collectionService, objectMapper);
        model = new ConcurrentModel();

        SecurityContextHolder.getContext()
            .setAuthentication(new TestingAuthenticationToken("admin", "pass", "ROLE_ADMIN"));
    }

    @AfterEach
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void listObjects_superadmin_listsAdministeredCollections() {
        Collection c1 = new Collection(UUID.randomUUID(), "C1", "desc", List.of(),
            OffsetDateTime.now(), "Area1");
        when(collectionService.listForAdminUser()).thenReturn(List.of(c1));
        when(collectionService.getCollection("C1")).thenReturn(Optional.of(c1));
        when(jsonObjectService.listObjects(eq(c1.id()), any(), anyInt(), anyInt()))
            .thenReturn(Collections.emptyList());
        when(jsonObjectService.countObjects(eq(c1.id()), any())).thenReturn(0L);

        String view = controller.listObjects(null, null, null, null, null, 0, 20, model);

        assertThat(view).isEqualTo("admin/json-objects");
        assertThat(model.getAttribute("selectedCollection")).isEqualTo("C1");
    }

    @Test
    public void listObjects_areaAdmin_unauthorizedCollection_throws404() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "area_admin", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_OIM"));
        Collection finCol = new Collection(UUID.randomUUID(), "Finance", "desc", List.of(),
            OffsetDateTime.now(), "Finance");
        when(collectionService.getCollection("Finance")).thenReturn(Optional.of(finCol));

        assertThrows(ResponseStatusException.class,
            () -> controller.listObjects("Finance", null, null, null, null, 0, 20, model));
    }

    @Test
    public void listObjects_areaAdmin_noAdministeredCollections_returnsEmpty() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "area_admin", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_OIM"));
        when(collectionService.listForAdminUser()).thenReturn(Collections.emptyList());

        String view = controller.listObjects(null, null, null, null, null, 0, 20, model);

        assertThat(view).isEqualTo("admin/json-objects");
        assertThat(model.getAttribute("selectedCollection")).isNull();
    }

    @Test
    public void listObjects_selectedObject_unauthorizedCollection_throws404() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "area_admin", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_OIM"));
        UUID objId = UUID.randomUUID();
        UUID finColId = UUID.randomUUID();
        Collection oimCol = new Collection(UUID.randomUUID(), "OIM", "desc", List.of(),
            OffsetDateTime.now(), "OIM");
        when(collectionService.listForAdminUser()).thenReturn(List.of(oimCol));
        when(collectionService.getCollection("OIM")).thenReturn(Optional.of(oimCol));
        when(jsonObjectService.listObjects(eq(oimCol.id()), any(), anyInt(), anyInt()))
            .thenReturn(Collections.emptyList());

        JsonObject obj = new JsonObject(objId, finColId, "Finance", Map.of("foo", "bar"),
            "file.json", OffsetDateTime.now());
        when(jsonObjectService.getObject(objId)).thenReturn(Optional.of(obj));
        when(collectionService.findByIdForAdmin(finColId)).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class,
            () -> controller.listObjects("OIM", null, null, objId, null, 0, 20, model));
    }

    @Test
    public void getDistinctValues_unauthorizedCollection_returnsEmptyList() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "area_admin", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_OIM"));
        Collection finCol = new Collection(UUID.randomUUID(), "Finance", "desc", List.of(),
            OffsetDateTime.now(), "Finance");
        when(collectionService.getCollection("Finance")).thenReturn(Optional.of(finCol));

        List<String> result = controller.getDistinctValues("Finance", "field");

        assertThat(result).isEmpty();
    }

    @Test
    public void getDistinctValues_authorizedCollection_returnsValues() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "area_admin", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_OIM"));
        UUID id = UUID.randomUUID();
        Collection oimCol =
            new Collection(id, "OIM", "desc", List.of(), OffsetDateTime.now(), "OIM");
        when(collectionService.getCollection("OIM")).thenReturn(Optional.of(oimCol));
        when(jsonObjectService.getDistinctValues(id, "field")).thenReturn(List.of("val1", "val2"));

        List<String> result = controller.getDistinctValues("OIM", "field");

        assertThat(result).containsExactly("val1", "val2");
    }

    @Test
    public void editSchema_unauthorizedCollection_redirectsWithError() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "area_admin", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_OIM"));
        Collection finCol = new Collection(UUID.randomUUID(), "Finance", "desc", List.of(),
            OffsetDateTime.now(), "Finance");
        when(collectionService.getCollection("Finance")).thenReturn(Optional.of(finCol));

        RedirectAttributes redirect = new RedirectAttributesModelMap();
        String view = controller.editSchema("Finance", null, "{}", redirect);

        assertThat(view).isEqualTo("redirect:/admin/json-objects");
        assertThat(redirect.getFlashAttributes()).containsKey("error");
    }

    @Test
    public void rebuildSchema_unauthorizedCollection_redirectsWithError() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "area_admin", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_OIM"));
        Collection finCol = new Collection(UUID.randomUUID(), "Finance", "desc", List.of(),
            OffsetDateTime.now(), "Finance");
        when(collectionService.getCollection("Finance")).thenReturn(Optional.of(finCol));

        RedirectAttributes redirect = new RedirectAttributesModelMap();
        String view = controller.rebuildSchema("Finance", null, redirect);

        assertThat(view).isEqualTo("redirect:/admin/json-objects");
        assertThat(redirect.getFlashAttributes()).containsKey("error");
    }
}
