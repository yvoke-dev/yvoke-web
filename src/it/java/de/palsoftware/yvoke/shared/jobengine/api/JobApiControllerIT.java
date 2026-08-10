package de.palsoftware.yvoke.shared.jobengine.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.util.Map;
import org.springframework.http.MediaType;

/**
 * {@code GET /api/jobs/v1/{id}} over a REAL row. Deliberately reuses {@code IngestApiControllerIT}'s
 * context configuration verbatim (same four properties, same order, no mock beans) so no new Spring
 * context is minted — the {@code src/it} suite sits close to its TestContext cache limit and an
 * evicted {@code RANDOM_PORT} context poisons an unrelated IT.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=filesystem:docker/db/migration",
        "app.worker.enabled=false",
        "app.security.mock=true"
})
public class JobApiControllerIT {

    private static final String COLLECTION = "JOB-API-IT-COLLECTION";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private UUID collectionId;
    private UUID jobId;

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        jdbcTemplate.update("DELETE FROM collections WHERE name = ?", COLLECTION);
        collectionId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO collections (id, name) VALUES (?, ?)", collectionId,
            COLLECTION);

        // Inserted terminal, not queued: this context runs with the worker disabled, but a queued
        // row would still be a claimable job for anything else sharing the container.
        jobId = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO ingestion_jobs
                (id, kind, source_ref, tags, collection_id, status, step, progress, attempts, created_at)
            VALUES
                (?, 'kg-extract', 'kit/install.md', ARRAY['10.0'], ?, 'completed', 'extract', 100, 1,
                 CURRENT_TIMESTAMP)
            """, jobId, collectionId);
    }

    @AfterEach
    public void tearDown() {
        // ON DELETE CASCADE takes the job row with it.
        jdbcTemplate.update("DELETE FROM collections WHERE id = ?", collectionId);
    }

    /**
     * The one assertion that the job API's JSON boundary works at all, against a real row.
     *
     * <p>
     * Two Jackson generations are on the classpath — {@code com.fasterxml.jackson} 2 and
     * {@code tools.jackson} 3 — and which one converts MVC responses is decided by autoconfiguration
     * conditions, not by anything in this repository: Boot's Jackson 2 converter activates only when
     * {@code spring.http.converters.preferred-json-mapper=jackson2} or when the Jackson 3
     * customizer is missing, and neither holds today. That matters because the application's only
     * hand-built mapper ({@code RestClientConfig}) is a bare {@code new ObjectMapper()} with no
     * java.time module, which cannot serialize {@code OffsetDateTime} at all — so if the boundary
     * ever falls back to it, every field of {@code IngestionJob} is fine and {@code createdAt}
     * alone takes the whole response down with a 500. The job resource is polled by the admin
     * job-detail page and by the corpus/eval scripts, so the failure is total and it is a
     * configuration change, not a code change, that causes it.
     *
     * <p>
     * {@code JobWireFormatTest} cannot see any of this: it builds its own {@code ObjectMapper} and
     * registers a {@code ToStringSerializer} for {@code OffsetDateTime} precisely to sidestep the
     * timestamp question, so it proves the casing contract and nothing about the wire. The status
     * assertion is repeated here for the same reason — {@code "COMPLETED"} is asserted against the
     * REAL converter, and it is what the job-detail page seeds itself from before SSE frames
     * (lowercase, a different shape) start overwriting it. And {@code SecurityGatingIT} only ever
     * requests a random UUID, so this route has never returned 200 in any test.
     */
    @Test
    public void getJobResourceRendersARealRowWithAnIso8601CreatedAt() throws Exception {
        String body = mockMvc
            .perform(get("/api/jobs/v1/" + jobId)
                .with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(jobId.toString()))
            .andExpect(jsonPath("$.kind").value("kg-extract"))
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.step").value("extract"))
            .andExpect(jsonPath("$.createdAt").isString())
            .andExpect(jsonPath("$.createdAt", matchesPattern(
                "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})")))
            .andReturn().getResponse().getContentAsString();

        assertThat(body)
            .as("a mapper with no java.time support renders the timestamp as a bean, not a string")
            .doesNotContain("dayOfMonth").doesNotContain("\"nano\"");
    }

    /**
     * The enqueue wire format — {@code POST /api/jobs/v1} with
     * {@code {"kind":…,"sourceRef":…,"tags":[…],"collection":…}} — is the machine clients'
     * whole contract, and it is bound by {@code com.fasterxml.jackson.annotation} annotations on a
     * static factory of a record ({@code EnqueueRequest.create}), resolved by whichever of the two
     * Jackson generations on the classpath autoconfiguration picks: databind is {@code tools.jackson}
     * 3 today (see this class's other javadoc), with the annotations still coming from the 2.x
     * {@code jackson-annotations} artifact. Nothing tests any of that. {@code EnqueueRequestTest}
     * calls {@code EnqueueRequest.create(...)} as a plain static method — which binds no JSON and
     * would pass with every annotation deleted — and no test in the repository POSTs a body to this
     * route at all ({@code SecurityGatingIT} only ever GETs a random id).
     *
     * <p>
     * The {@code tags} array form is an {@code @JsonAlias} for the {@code tag} property, and it is
     * not cosmetic: the corpus export scripts send the array form. If it stops binding, the request
     * arrives with no tag. Against an UNTAGGED collection that is silent and catastrophic — the
     * documented "ingest tags are silently dropped" failure, where the second product version's
     * ingest reuses the first's {@code source_file} keys and REPLACES it while the job reports a
     * perfectly normal count. That is why this test declares tags on the fixture collection first:
     * it converts the silent form into a loud one ({@code CollectionTagEnqueueValidator} rejects a
     * tagless request for a tagged collection with a 400), and then still asserts the persisted
     * {@code tags} column rather than trusting the 202.
     *
     * <p>
     * The kind is deliberately one no {@code JobHandler} claims: the row is enqueued {@code queued}
     * and this context runs with the worker disabled, but sibling IT contexts sharing the same
     * Postgres container run with {@code app.worker.enabled=true}, and an unknown kind is failed by
     * {@code JobService.execute} without executing anything. It also makes the row unambiguous
     * against the {@code kg-extract} fixture {@code setup()} inserts for the same collection.
     */
    @Test
    public void theEnqueueBodyBindsSourceRefAndTheTagsAliasThroughTheRealJsonConverter()
        throws Exception {
        String kind = "job-api-it-wire-format";
        String sourceRef = "kit/10.0/enqueue-wire-format.md";
        String tag = "10.0";

        // The alias only matters for a collection that actually scopes by tag.
        jdbcTemplate.update("UPDATE collections SET tags = ARRAY[?] WHERE id = ?", tag,
            collectionId);

        String payload = """
            {"kind":"%s","sourceRef":"%s","tags":["%s"],"collection":"%s"}
            """.formatted(kind, sourceRef, tag, COLLECTION);

        String body = mockMvc
            .perform(post("/api/jobs/v1").contentType(MediaType.APPLICATION_JSON).content(payload)
                .with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN")))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").isString()).andReturn()
            .getResponse().getContentAsString();

        Map<String, Object> row = jdbcTemplate.queryForMap("""
            SELECT id, source_ref, array_to_string(tags, ',') AS tag_list
            FROM ingestion_jobs
            WHERE collection_id = ? AND kind = ?
            """, collectionId, kind);

        assertThat(row.get("source_ref"))
            .as("sourceRef is the field name every machine client sends; it must bind verbatim")
            .isEqualTo(sourceRef);
        assertThat(row.get("tag_list"))
            .as("the array form 'tags' must bind, or the job runs UNTAGGED and overwrites the "
                + "other version's documents while reporting a normal count")
            .isEqualTo(tag);
        assertThat(body).as("the 202 body hands back the id of the row just written")
            .contains(row.get("id").toString());
    }
}
