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
import jakarta.servlet.http.Part;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.assertj.core.api.Assertions;

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
        Assertions.assertThat(targetCol.tags()).contains("v2.0");
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

    /**
     * An upload over {@code spring.servlet.multipart.max-file-size} must come back <strong>413
     * CONTENT_TOO_LARGE</strong>, not a 500.
     *
     * <p>
     * The whole path is framework code, which is exactly why it is worth pinning: nothing in
     * {@code src/} mentions {@code MaxUploadSizeExceededException}, {@code MultipartException} or
     * {@code resolve-lazily} (repo-wide grep: zero hits), so the correct status here is an emergent
     * property of four independent settings and one Spring version. The container refuses the body
     * and reports it out of {@code getParts()}; because {@code resolve-lazily} is unset, that
     * happens in {@code DispatcherServlet.checkMultipart} BEFORE handler resolution, so
     * {@code StandardMultipartHttpServletRequest.handleParseFailure} converts it to
     * {@code MaxUploadSizeExceededException} — which implements {@code ErrorResponse} with
     * {@code getStatusCode() == 413} — and it surfaces with a <em>null</em> handler. Both app
     * advices are selector-scoped ({@code @ControllerAdvice(annotations = Controller.class)} and
     * {@code @RestControllerAdvice(annotations = RestController.class)}), and
     * {@code HandlerTypePredicate} returns false for a null handler type, so NEITHER can claim it;
     * {@code DefaultHandlerExceptionResolver}'s leading {@code instanceof ErrorResponse} branch
     * calls {@code response.sendError(413, detail)} instead.
     *
     * <p>
     * Every link in that chain is one edit from breaking, and none of them looks like it touches
     * uploads. Setting {@code spring.servlet.multipart.resolve-lazily=true} defers parsing into the
     * handler, at which point the handler is no longer null, {@code ApiExceptionHandler} DOES apply,
     * and its generic {@code Exception} branch turns a routine oversized file into a 500 with a
     * server-error log entry — an operator uploading a 300 MB corpus is told the service is broken
     * rather than that their file is too big, and the platform's own retry logic treats it as
     * retryable. Adding an {@code @ExceptionHandler} for {@code MultipartException} to either advice
     * has the mirror effect. Nothing else in the suite issues a multipart request that fails
     * parsing, so today all of that is unobserved.
     *
     * <p>
     * The refusal is injected rather than uploaded: MockMvc never runs a real container, so the only
     * way to reach this path is to reproduce the shape the container produces — a
     * {@code getParts()} that throws with Tomcat's "…exceeds…size…" wording, which is precisely what
     * {@code handleParseFailure} matches on. That keeps the test at milliseconds instead of pushing
     * 200 MB through it, and it exercises the REAL resolver chain of the REAL application context,
     * both advices included.
     */
    @Test
    public void anOverSizedMultipartUploadIsA413NotAFiveHundred() throws Exception {
        String body = mockMvc
                .perform(post("/api/ingest/v1/upload")
                        .contentType("multipart/form-data")
                        // Tomcat refuses the body itself and surfaces the refusal out of getParts();
                        // handleParseFailure matches on that wording, so the wording IS the fixture.
                        // java.util.Collection is spelled out because this file already imports a
                        // domain type named Collection.
                        .with(request -> {
                            MockHttpServletRequest oversized =
                                    new MockHttpServletRequest(
                                            request.getServletContext(), request.getMethod(),
                                            request.getRequestURI()) {
                                        @Override
                                        public java.util.Collection<Part> getParts() {
                                            throw new IllegalStateException(
                                                    "org.apache.tomcat.util.http.fileupload.impl."
                                                            + "SizeLimitExceededException: the request was "
                                                            + "rejected because its size (629145600) "
                                                            + "exceeds the configured maximum (209715200)");
                                        }
                                    };
                            oversized.setContentType("multipart/form-data");
                            return oversized;
                        })
                        .with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN")))
                .andExpect(status().isContentTooLarge())
                .andReturn().getResponse().getContentAsString();

        // sendError() writes no body. An advice-rendered payload here would mean the exception
        // reached a handler-scoped advice, i.e. that parsing moved into the handler.
        Assertions.assertThat(body)
                .as("413 must come from the framework's sendError path, not from an advice")
                .isEmpty();
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
        Assertions.assertThat(targetCol.tags()).contains("v2.0");
    }
}
