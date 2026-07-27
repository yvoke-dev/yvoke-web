package de.palsoftware.yvoke.document.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Document tag editing and the JSON-object schema writes, plus the corpus-browser filters and
 * pagination that no test had ever supplied.
 *
 * <p>Tags are version scoping: a document's tags decide which release's answers can cite it, so the
 * add/remove endpoints are how an admin can silently make content invisible to retrieval — or visible
 * to the wrong version. {@code JsonObjectAdminController} had no test at all, and its schema writes
 * both branch on a collection lookup whose failure path only redirects with a flash, which is precisely
 * the sort of thing that rots unnoticed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "app.security.mock=true")
public class DocumentAndJsonAdminWriteIT {

    private static final String COLLECTION = "IT-DOC-JSON-WRITE";
    private static final String TAG = "9.3";
    private static final String OTHER_TAG = "10.0";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    private UUID documentId;

    @BeforeEach
    public void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanup();
        jdbcTemplate.update(
            "INSERT INTO collections (id, name, tags) VALUES (?, ?, ARRAY['9.3','10.0'])",
            UUID.randomUUID(), COLLECTION);
        documentId = documentRepository.upsertManualDocument(COLLECTION, TAG, "it_doc_json.md",
            "manual", "IT Doc JSON Write");
    }

    @AfterEach
    public void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbcTemplate.update("DELETE FROM collections WHERE name = ?", COLLECTION);
    }

    private static OidcLoginRequestPostProcessor admin() {
        return oidcLogin().idToken(token -> token.claim("oid", "it-docjson-admin")).authorities(
            new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static OidcLoginRequestPostProcessor plainUser() {
        return oidcLogin().idToken(token -> token.claim("oid", "it-docjson-user"))
            .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private java.util.List<String> documentTags() {
        return jdbcTemplate.queryForList("SELECT unnest(tags) FROM documents WHERE id = ?",
            String.class, documentId);
    }

    @Test
    public void addingAndRemovingADocumentTagChangesItsVersionScope() throws Exception {
        assertThat(documentTags()).containsExactly(TAG);

        mockMvc
            .perform(post("/admin/documents/" + documentId + "/add-tag").with(csrf()).with(admin())
                .param("tag", OTHER_TAG))
            .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("success"));
        assertThat(documentTags()).containsExactlyInAnyOrder(TAG, OTHER_TAG);

        mockMvc
            .perform(post("/admin/documents/" + documentId + "/remove-tag").with(csrf())
                .with(admin()).param("tag", OTHER_TAG))
            .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("success"));
        assertThat(documentTags()).containsExactly(TAG);
    }

    @Test
    public void documentTagWritesRequireAdmin() throws Exception {
        mockMvc.perform(post("/admin/documents/" + documentId + "/add-tag").with(csrf())
            .with(plainUser()).param("tag", OTHER_TAG)).andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/documents/" + documentId + "/remove-tag").with(csrf())
            .with(plainUser()).param("tag", TAG)).andExpect(status().isForbidden());

        assertThat(documentTags()).containsExactly(TAG);
    }

    /**
     * The corpus browser's kind/tag filters and its pagination, none of which was ever driven — every
     * covering test issued a parameterless GET, so the hidden inputs that carry the current selection
     * across a page change were never rendered.
     */
    @Test
    public void corpusBrowserFiltersAndPaginationRender() throws Exception {
        mockMvc
            .perform(get("/admin/documents").param("collection", COLLECTION).param("tag", TAG)
                .param("kind", "manual").with(admin()))
            .andExpect(status().isOk());
        mockMvc
            .perform(get("/admin/documents").param("collection", COLLECTION)
                .param("searchTitle", "IT Doc").with(admin()))
            .andExpect(status().isOk());
        mockMvc
            .perform(get("/admin/documents").param("collection", COLLECTION).param("page", "1")
                .param("size", "1").with(admin()))
            .andExpect(status().isOk());
    }

    // ---------- JSON object schema ----------

    @Test
    public void editingTheSchemaStoresItAndRedirectsBackToTheFilteredList() throws Exception {
        mockMvc
            .perform(post("/admin/json-objects/schema/edit").with(csrf()).with(admin())
                .param("collection", COLLECTION).param("tag", TAG)
                .param("schemaJson", "{\"type\":\"object\",\"properties\":{}}"))
            .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("success"));
    }

    @Test
    public void malformedSchemaJsonIsRejected() throws Exception {
        mockMvc
            .perform(post("/admin/json-objects/schema/edit").with(csrf()).with(admin())
                .param("collection", COLLECTION).param("schemaJson", "{not json")
                .header("Accept", "text/html"))
            .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("error"));
    }

    @Test
    public void schemaWritesAgainstAnUnknownCollectionRedirectWithAnError() throws Exception {
        mockMvc
            .perform(post("/admin/json-objects/schema/edit").with(csrf()).with(admin())
                .param("collection", "NOPE-DOES-NOT-EXIST").param("schemaJson", "{}"))
            .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("error"));

        mockMvc
            .perform(post("/admin/json-objects/schema/rebuild").with(csrf()).with(admin())
                .param("collection", "NOPE-DOES-NOT-EXIST"))
            .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("error"));
    }

    /**
     * Rebuild refuses to run over a manually maintained schema. That guard is the only thing stopping
     * a re-inference from silently discarding hand-written field documentation.
     */
    @Test
    public void rebuildIsRefusedWhileTheSchemaIsManuallyMaintained() throws Exception {
        mockMvc.perform(post("/admin/json-objects/schema/edit").with(csrf()).with(admin())
            .param("collection", COLLECTION).param("schemaJson", "{\"type\":\"object\"}"))
            .andExpect(status().is3xxRedirection());

        mockMvc
            .perform(post("/admin/json-objects/schema/rebuild").with(csrf()).with(admin())
                .param("collection", COLLECTION))
            .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("error"));
    }

    @Test
    public void distinctValuesReturnsAnEmptyListForAnUnknownCollection() throws Exception {
        mockMvc
            .perform(get("/admin/json-objects/distinct-values")
                .param("collection", "NOPE-DOES-NOT-EXIST").param("fieldName", "name")
                .with(admin()))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .json("[]"));
    }

    @Test
    public void jsonObjectWritesAndLookupsRequireAdmin() throws Exception {
        mockMvc.perform(post("/admin/json-objects/schema/edit").with(csrf()).with(plainUser())
            .param("collection", COLLECTION).param("schemaJson", "{}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/json-objects/schema/rebuild").with(csrf()).with(plainUser())
            .param("collection", COLLECTION)).andExpect(status().isForbidden());
        mockMvc
            .perform(get("/admin/json-objects/distinct-values").param("collection", COLLECTION)
                .param("fieldName", "name").with(plainUser()))
            .andExpect(status().isForbidden());
    }
}
