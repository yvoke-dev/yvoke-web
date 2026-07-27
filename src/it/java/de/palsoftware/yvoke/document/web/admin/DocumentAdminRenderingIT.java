package de.palsoftware.yvoke.document.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.palsoftware.yvoke.document.core.model.ChunkInsert;
import de.palsoftware.yvoke.document.core.model.ChunkRow;
import de.palsoftware.yvoke.document.core.repository.ChunkRepository;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import java.util.List;
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
 * Full Thymeleaf render coverage for the document admin pages after the DTO-at-boundary refactor
 * (Wave 3.3). A missing/renamed accessor on the per-view DTOs would throw during template
 * evaluation and surface as a 500 — so an actual 200 render (not just a resolved view name) is the
 * safety net that standalone MockMvc tests cannot provide.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "app.security.mock=true")
public class DocumentAdminRenderingIT {

    private static final String COLLECTION = "OIM-DOCVIEW-TEST";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private ChunkRepository chunkRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private UUID documentId;
    private UUID chunkId;

    private static OidcLoginRequestPostProcessor admin() {
        return oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"),
            new SimpleGrantedAuthority("ROLE_USER"));
    }

    @BeforeEach
    public void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        jdbcTemplate.update(
            "INSERT INTO collections (id, name) VALUES (?, ?) ON CONFLICT (name) DO NOTHING",
            UUID.randomUUID(), COLLECTION);

        documentId = documentRepository.upsertManualDocument(COLLECTION, "9.3", "guide.md",
            "standard", "Render Test Guide");
        documentRepository.insertChunks(documentId, COLLECTION, "9.3", "guide.md", "standard",
            List.of(new ChunkInsert("First chunk body text.", null, List.of("Introduction"),
                "Introduction", 1, 0)));

        chunkId = chunkRepository.findChunksByDocumentId(documentId, null).stream()
            .map(ChunkRow::id).findFirst().orElseThrow();
    }

    @AfterEach
    public void tearDown() {
        documentRepository.deleteByCollection(COLLECTION);
        jdbcTemplate.update("DELETE FROM collections WHERE name = ?", COLLECTION);
    }

    @Test
    public void documentsListRendersWithDtoRows() throws Exception {
        mockMvc.perform(get("/admin/documents").param("collection", COLLECTION).with(admin()))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Render Test Guide")));
    }

    @Test
    public void documentDetailRendersWithDtoDocumentAndChunks() throws Exception {
        mockMvc.perform(get("/admin/documents/" + documentId).with(admin()))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Render Test Guide")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Introduction")));
    }

    @Test
    public void chunkDetailRendersWithDtoChunk() throws Exception {
        mockMvc.perform(get("/admin/chunks/" + chunkId).with(admin()))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("First chunk body text.")));
    }
}
