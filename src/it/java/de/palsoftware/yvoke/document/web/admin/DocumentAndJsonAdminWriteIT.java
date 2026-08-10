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
import de.palsoftware.yvoke.jsonobject.core.service.JsonObjectService;
import java.util.List;
import java.util.Map;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

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

    @Autowired
    private JsonObjectService jsonObjectService;

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

    private List<String> documentTags() {
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
     * The two things the document tag endpoints must get right that no test drives, exercised
     * through the real HTTP surface because both are properties of the request, not of the SQL in
     * isolation.
     *
     * <p>
     * FIRST, a no-op must be SILENT. Both tag statements are guarded UPDATEs, so "changed nothing"
     * is ambiguous: it means either an ordinary no-op (the tag is already there / already absent, or
     * the row is gone) or a genuine collision with a sibling document holding the resulting tag
     * scope. Only the second is an error, and {@code rejectIfSiblingBlocks} is the single place that
     * tells them apart. If it stops distinguishing them, an admin who double-clicks "add tag", or
     * clicks it on a tag the document already carries — the ordinary way to use a datalist that
     * offers exactly the tags in play — gets an error flash or a 500 for an operation that
     * legitimately did nothing. {@code addingAndRemovingADocumentTagChangesItsVersionScope} only
     * ever posts changes that actually apply, and {@code TagRepositoryIT} covers the collision half,
     * so the {@code updated == 0}-and-innocent path has never executed.
     *
     * <p>
     * SECOND, the tag arrives as a raw request parameter and is interpolated into nothing. Both
     * TagRepository literals bind it, and the diagnostic query is one of two fixed strings — so a
     * value carrying a quote and a statement terminator must round-trip byte for byte and the schema
     * must be untouched. Concatenating it instead would be first-order SQL injection on an
     * admin-authenticated surface, and "sanitize the quotes out" is the tempting non-fix: it leaves
     * the tag silently mangled, so the document is scoped to a version nobody can select. The proof
     * that both statements bind is that the same literal REMOVES the tag again; asserting only the
     * insert would pass even if the delete were built by concatenation.
     */
    @Test
    public void aRepeatedTagEditIsASilentNoOpAndAHostileTagValueIsNeverConcatenatedIntoTheSql()
        throws Exception {
        String addUrl = "/admin/documents/" + documentId + "/add-tag";
        String removeUrl = "/admin/documents/" + documentId + "/remove-tag";
        assertThat(documentTags()).containsExactly(TAG);

        // 1. Adding a tag the document already carries: nothing changes, and it is NOT an error.
        mockMvc.perform(post(addUrl).with(csrf()).with(admin()).param("tag", TAG))
            .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("success"))
            .andExpect(flash().attributeCount(1));
        assertThat(documentTags()).containsExactly(TAG);

        // 2. ...and neither is removing one it never carried.
        mockMvc.perform(post(removeUrl).with(csrf()).with(admin()).param("tag", "never-applied"))
            .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("success"))
            .andExpect(flash().attributeCount(1));
        assertThat(documentTags()).containsExactly(TAG);

        // 3. A hostile value survives the round trip verbatim, and the schema is untouched.
        String hostile = "9.9'); DROP TABLE documents; --";
        mockMvc.perform(post(addUrl).with(csrf()).with(admin()).param("tag", hostile))
            .andExpect(status().is3xxRedirection());

        assertThat(documentTags())
            .as("the tag is BOUND, so it is stored exactly as typed — not escaped, not stripped")
            .containsExactlyInAnyOrder(TAG, hostile);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM documents WHERE id = ?",
            Integer.class, documentId)).as("the row — and the table — are still there").isEqualTo(1);

        // 4. The same literal removes it again: the second statement binds it too.
        mockMvc.perform(post(removeUrl).with(csrf()).with(admin()).param("tag", hostile))
            .andExpect(status().is3xxRedirection());
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
            .andExpect(MockMvcResultMatchers.content()
                .json("[]"));
    }

    /**
     * The JSON-object browser's search box takes RAW jsonpath, and the admin filter builder emits it
     * straight into that box, so malformed input is routine traffic on this page — an unquoted string
     * literal is the common one, because {@code @.name == AAD} is what the expression looks like
     * everywhere else in the product. Postgres rejects it while PARSING the {@code ::jsonpath} cast
     * (SQLSTATE 42601), which Spring translates to {@code BadSqlGrammarException}, and the only thing
     * standing between that and {@code MvcExceptionHandler}'s generic 500 is one narrowly-typed catch
     * on this route.
     *
     * <p>
     * Nothing pins it: the literal "Invalid JSON Path" appears nowhere under {@code src/test} or
     * {@code src/it}. Narrow the catch, move it, or let the exception type drift and the admin loses
     * the filter they were mid-way through building, gets no hint that the fix is double quotes, and
     * sees an error page that looks like the corpus is broken rather than the query.
     *
     * <p>
     * The correctly-quoted control request is what makes the empty page meaningful: it proves the same
     * box against the same collection really does match the seeded row, so the second request's zero
     * is the caught parse error and not an empty collection. The retained {@code searchQuery} is
     * asserted because the template re-renders it into the input — losing it is how the admin's work
     * disappears even when the page itself survives.
     */
    @Test
    public void aMalformedJsonPathRendersTheFilterHelpInsteadOfAServerError() throws Exception {
        UUID collectionId = jdbcTemplate.queryForObject("SELECT id FROM collections WHERE name = ?",
            UUID.class, COLLECTION);
        List<Map<String, Object>> objects = List.of(Map.of("name", "AAD", "module", "Connector"));
        jsonObjectService.importObjects(collectionId, COLLECTION, objects, "it-jsonpath.jsonl",
            List.of(TAG));

        // Control: the same box, correctly quoted, really does match the row.
        var ok = mockMvc
            .perform(get("/admin/json-objects").param("collection", COLLECTION)
                .param("search", "$ ? (@.name == \"AAD\")").with(admin()))
            .andExpect(status().isOk()).andReturn().getModelAndView().getModel();
        assertThat(ok.get("error")).as("a well-formed jsonpath must not raise the help card").isNull();
        assertThat(ok.get("totalElements")).isEqualTo(1L);
        assertThat((List<?>) ok.get("jsonObjects")).hasSize(1);

        // What an admin actually types: the same filter with the string literal unquoted.
        var model = mockMvc
            .perform(get("/admin/json-objects").param("collection", COLLECTION)
                .param("search", "$ ? (@.name == AAD)").with(admin()))
            .andExpect(status().isOk()).andReturn().getModelAndView().getModel();

        assertThat(String.valueOf(model.get("error")))
            .as("the page must name the fix — double quotes — instead of becoming a generic 500")
            .contains("Invalid JSON Path").contains("double quotes");
        assertThat(model.get("totalElements"))
            .as("the failed query contributes no rows, and the pager must say so").isEqualTo(0L);
        assertThat((List<?>) model.get("jsonObjects")).isEmpty();
        assertThat(model.get("searchQuery"))
            .as("the filter the admin was building must survive the failed parse")
            .isEqualTo("$ ? (@.name == AAD)");
        assertThat(model.get("selectedCollection")).isEqualTo(COLLECTION);
    }

        /**
     * The corpus browser's field suggestions must be DOTTED paths, and its pager must stay inside
     * the selected tag.
     *
     * <p>{@code JsonObjectAdminController.collectSchemaFieldPaths} is the only producer of the
     * {@code schemaFields} model attribute, and that list is what the admin picks a display field
     * from and what the filter builder offers as rule keys. If the recursion loses its prefix, a
     * nested {@code Customer.id} is offered as a bare {@code id}: the template resolves the chosen
     * display field against the TOP level of each object, finds nothing, and every row in the
     * browser silently renders its UUID instead of a human label — while a filter rule built from
     * the same list targets a key no row has, so it matches nothing. Nothing errors; the page just
     * quietly stops being usable.
     *
     * <p>The same request pins the count half. The identical object exists under both {@code 9.3}
     * and {@code 10.0}, so a pager that dropped the tag filter would report two elements over a
     * one-row page and offer a page 2 that is empty — the classic "the version filter is on but the
     * numbers are from everywhere" failure.
     */
    @Test
    public void theCorpusBrowserSuggestsNestedSchemaPathsAndCountsWithinTheSelectedTag()
        throws Exception {
        UUID collectionId = jdbcTemplate.queryForObject("SELECT id FROM collections WHERE name = ?",
            UUID.class, COLLECTION);
        List<Map<String, Object>> objects =
            List.of(Map.of("name", "Alice", "Customer", Map.of("id", "C1")));
        jsonObjectService.importObjects(collectionId, COLLECTION, objects, "it-9.3.jsonl",
            List.of(TAG));
        jsonObjectService.importObjects(collectionId, COLLECTION, objects, "it-10.0.jsonl",
            List.of(OTHER_TAG));

        var model = mockMvc
            .perform(get("/admin/json-objects").param("collection", COLLECTION).param("tag", TAG)
                .param("search", "Alice").with(admin()))
            .andExpect(status().isOk()).andReturn().getModelAndView().getModel();

        @SuppressWarnings("unchecked")
        List<String> schemaFields = (List<String>) model.get("schemaFields");
        assertThat(schemaFields).contains("Customer", "Customer.id", "name").doesNotContain("id");

        assertThat(model.get("totalElements")).isEqualTo(1L);
        assertThat(model.get("totalPages")).isEqualTo(1);
        assertThat((List<?>) model.get("jsonObjects")).hasSize(1);
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
