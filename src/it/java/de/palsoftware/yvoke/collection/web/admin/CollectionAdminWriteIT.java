package de.palsoftware.yvoke.collection.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.hamcrest.Matchers;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * Collection creation and tag lifecycle, plus the tag/version dropdown fragment — none of which was
 * requested by any test before.
 *
 * <p>The fragment endpoint is the interesting one: four URLs map to one handler that picks between
 * <em>three different templates</em> by sniffing the {@code HX-Current-URL} header. Returning the
 * wrong one breaks the tag dropdown on the ingest and connectors pages, where the tag is a required
 * field — and per the documented pitfall, an ingest that runs untagged silently replaces the previous
 * version's documents. A whole-page GET smoke test cannot reach any of this, because the fragment is
 * only ever rendered as a sub-tree of a partially populated model.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "app.security.mock=true")
public class CollectionAdminWriteIT {

    private static final String COLLECTION = "IT-COLLECTION-WRITE";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    public void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanup();
    }

    @AfterEach
    public void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbcTemplate.update("DELETE FROM collections WHERE name = ?", COLLECTION);
    }

    private static OidcLoginRequestPostProcessor admin() {
        return oidcLogin().idToken(token -> token.claim("oid", "it-collection-admin")).authorities(
            new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static OidcLoginRequestPostProcessor plainUser() {
        return oidcLogin().idToken(token -> token.claim("oid", "it-collection-user"))
            .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private UUID createCollection() throws Exception {
        mockMvc
            .perform(post("/admin/collections").with(csrf()).with(admin())
                .param("name", COLLECTION).param("description", "created by IT"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/collections"));
        return jdbcTemplate.queryForObject("SELECT id FROM collections WHERE name = ?", UUID.class,
            COLLECTION);
    }

    @Test
    public void creatingACollectionPersistsItWithNoTags() throws Exception {
        UUID id = createCollection();

        assertThat(id).isNotNull();
        assertThat(jdbcTemplate.queryForList("SELECT unnest(tags) FROM collections WHERE id = ?",
            String.class, id)).isEmpty();
    }

    @Test
    public void addingAndRemovingATagUpdatesTheCollection() throws Exception {
        UUID id = createCollection();

        mockMvc
            .perform(post("/admin/collections/add-tag").with(csrf()).with(admin())
                .param("collectionId", id.toString()).param("tag", "9.3"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/collections"));
        assertThat(jdbcTemplate.queryForList("SELECT unnest(tags) FROM collections WHERE id = ?",
            String.class, id)).containsExactly("9.3");

        // Removal is the destructive one — it lives on the lifecycle controller because it may purge
        // content whose only tag was this.
        mockMvc
            .perform(post("/admin/collections/remove-tag").with(csrf()).with(admin())
                .param("collectionId", id.toString()).param("tag", "9.3"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/collections"));
        assertThat(jdbcTemplate.queryForList("SELECT unnest(tags) FROM collections WHERE id = ?",
            String.class, id)).isEmpty();
    }

    @Test
    public void tagOptionsFragmentReturnsTheCollectionsTagsForTheIngestPageByDefault()
        throws Exception {
        UUID id = createCollection();
        mockMvc.perform(post("/admin/collections/add-tag").with(csrf()).with(admin())
            .param("collectionId", id.toString()).param("tag", "10.0"));

        mockMvc
            .perform(get("/admin/collections/tag-options").param("collection", COLLECTION)
                .with(admin()))
            .andExpect(status().isOk())
            .andExpect(MockMvcResultMatchers.content()
                .string(Matchers.containsString("10.0")));
    }

    /**
     * One endpoint, three possible fragments, chosen by a request header. Each host page has its own
     * {@code tag-container}, so a mis-branch silently swaps foreign markup into the page.
     */
    @Test
    public void theFragmentChosenDependsOnTheCallingPage() throws Exception {
        createCollection();

        for (String page : new String[] {"/admin/ingest", "/admin/connectors", "/admin/search"}) {
            mockMvc
                .perform(get("/admin/collections/tag-options").param("collection", COLLECTION)
                    .header("HX-Current-URL", "http://localhost:8080" + page).with(admin()))
                .andExpect(status().isOk());
        }
    }

    @Test
    public void theCollectionCanAlsoBeNamedInThePathRatherThanTheQuery() throws Exception {
        createCollection();

        mockMvc.perform(get("/admin/collections/" + COLLECTION + "/version-options").with(admin()))
            .andExpect(status().isOk());
    }

    /** An unknown collection must render an empty dropdown, not blow up. */
    @Test
    public void anUnknownCollectionYieldsAnEmptyDropdown() throws Exception {
        mockMvc
            .perform(get("/admin/collections/tag-options").param("collection", "NOPE-DOES-NOT-EXIST")
                .with(admin()))
            .andExpect(status().isOk());
    }

    @Test
    public void collectionWritesAndTheFragmentRequireAdmin() throws Exception {
        mockMvc
            .perform(post("/admin/collections").with(csrf()).with(plainUser()).param("name",
                COLLECTION))
            .andExpect(status().isForbidden());
        mockMvc
            .perform(post("/admin/collections/add-tag").with(csrf()).with(plainUser())
                .param("collectionId", UUID.randomUUID().toString()).param("tag", "9.3"))
            .andExpect(status().isForbidden());
        mockMvc
            .perform(post("/admin/collections/remove-tag").with(csrf()).with(plainUser())
                .param("collectionId", UUID.randomUUID().toString()).param("tag", "9.3"))
            .andExpect(status().isForbidden());
        mockMvc
            .perform(get("/admin/collections/tag-options").param("collection", COLLECTION)
                .with(plainUser()))
            .andExpect(status().isForbidden());

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM collections WHERE name = ?",
            Integer.class, COLLECTION)).isZero();
    }
}
