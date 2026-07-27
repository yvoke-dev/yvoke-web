package de.palsoftware.yvoke.ingest.api;

import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=filesystem:docker/db/migration",
        "app.worker.enabled=false",
        "app.security.mock=true"
})
public class IngestApiControllerIT {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CollectionService collectionService;

    private MockMvc mockMvc;

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        cleanup();
    }

    private void cleanup() {
        jdbcTemplate.update("DELETE FROM ingestion_jobs");
        jdbcTemplate.update("DELETE FROM chunks");
        jdbcTemplate.update("DELETE FROM documents");
        jdbcTemplate.update("DELETE FROM collections");
    }

    @Test
    public void testProcessDocumentKgWithDocumentId() throws Exception {
        UUID sourceColId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO collections (id, name, tags) VALUES (?, ?, ARRAY['v1.0'])", sourceColId, "source-col");
        
        UUID docId = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO documents (id, collection_id, kind, title, ingestion_status, metadata)
            VALUES (?, ?, 'manual', 'doc.md', 'completed', '{"source_file": "doc.md", "tag": "v1.0"}')
            """, docId, sourceColId);

        mockMvc.perform(post("/api/ingest/v1/process-kg")
                        .param("documentId", docId.toString())
                        .param("collection", "target-col")
                        .param("tag", "v2.0")
                        .with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id", notNullValue()));

        Collection targetCol = collectionService.getCollection("target-col").orElseThrow();
        org.assertj.core.api.Assertions.assertThat(targetCol.tags()).contains("v2.0");
    }

    /**
     * The second submission of the same document into the same target ADOPTS the job already in
     * flight — it does not create one, and it does not apply this request's settings — so it is a
     * 409 carrying that job's id, exactly as {@code POST /api/jobs/v1} reports a duplicate.
     */
    @Test
    public void testProcessDocumentKgTwiceReportsTheInFlightJobAsAConflict() throws Exception {
        UUID sourceColId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO collections (id, name, tags) VALUES (?, ?, ARRAY['v1.0'])", sourceColId, "source-col");

        UUID docId = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO documents (id, collection_id, kind, title, ingestion_status, metadata)
            VALUES (?, ?, 'manual', 'doc.md', 'completed', '{"source_file": "doc.md", "tag": "v1.0"}')
            """, docId, sourceColId);

        String firstBody = mockMvc.perform(post("/api/ingest/v1/process-kg")
                        .param("documentId", docId.toString())
                        .param("collection", "target-col")
                        .param("tag", "v2.0")
                        .with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN")))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(post("/api/ingest/v1/process-kg")
                        .param("documentId", docId.toString())
                        .param("collection", "target-col")
                        .param("tag", "v2.0")
                        .with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(content().json(firstBody));
    }

    @Test
    public void testProcessDocumentKgWithSourceInfo() throws Exception {
        UUID sourceColId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO collections (id, name, tags) VALUES (?, ?, ARRAY['v1.0'])", sourceColId, "source-col");

        UUID docId = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO documents (id, collection_id, kind, title, ingestion_status, tags, metadata)
            VALUES (?, ?, 'manual', 'doc.md', 'completed', ARRAY['v1.0'], '{"source_file": "doc.md"}')
            """, docId, sourceColId);

        mockMvc.perform(post("/api/ingest/v1/process-kg")
                        .param("sourceFile", "doc.md")
                        .param("sourceCollection", "source-col")
                        .param("sourceTag", "v1.0")
                        .param("collection", "target-col")
                        .param("tag", "v2.0")
                        .with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id", notNullValue()));

        Collection targetCol = collectionService.getCollection("target-col").orElseThrow();
        org.assertj.core.api.Assertions.assertThat(targetCol.tags()).contains("v2.0");
    }
}
