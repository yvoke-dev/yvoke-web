package de.palsoftware.yvoke.shared.jobengine.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import java.util.Map;

/**
 * The job engine puts THREE different JSON shapes of the same state on the wire, and the browser
 * consumes all three on one page. This pins the casing contract between them.
 */
class JobWireFormatTest {

    /**
     * The application's mapper is a bare {@code new ObjectMapper()} ({@code RestClientConfig}), so
     * no module of ours normalizes anything. Only {@code OffsetDateTime} needs help here — the bare
     * mapper cannot write java.time at all — and how a timestamp is rendered is not what this test
     * is about.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(
        new SimpleModule().addSerializer(OffsetDateTime.class, ToStringSerializer.instance));

    /**
     * {@code IngestionJob} is not a DTO with a mapper in front of it — the record itself is the
     * response body of {@code GET /api/jobs/v1/{id}} ({@code JobApiController.get} returns it
     * directly). Two of its methods are derived VIEWS of components that are already on that wire:
     * {@code collection()} mirrors {@code collectionName}, and {@code tag()} is a lossy
     * {@code ", "}-join of {@code tags}. They stay off the JSON only because of a Jackson default
     * nobody wrote down: for a record, the accessor-naming strategy treats a method as a property
     * only when its name is a record component name, or when it carries a {@code get}/{@code is}
     * prefix. Neither is true of these two, so they are invisible — until someone renames one to
     * {@code getCollection()} in a "make it a proper bean" cleanup, or adds a
     * {@code @JsonProperty}, at which point the API silently grows a second spelling of data it
     * already publishes.
     *
     * <p>
     * The damage is not cosmetic noise. {@code tag} would be the singular, simpler-looking field
     * sitting next to {@code tags}, so a client will reasonably read it — and a single-tag job
     * makes that look correct forever. Every corpus in this deployment is tag-scoped with two kit
     * versions in one collection, so the first two-tag job yields {@code "10.0, 9.3.1"}: a string
     * that matches no tag, is not a list, and cannot be fed back into any tag-scoped query.
     * {@code collection} next to {@code collectionName} is the same trap one level down — it
     * invites clients to key on the field the server considers derived.
     *
     * <p>
     * Nothing else asserts on this JSON's field set: the sibling test above serialises the same
     * record but only reads {@code status} and {@code step}, and the job ITs assert on the mapped
     * Java object, where {@code collection()} and {@code tag()} are simply methods that work. The
     * key list is asserted exhaustively rather than by absence, so ADDING a component is also a
     * deliberate decision recorded here — which it should be, since this is a published contract.
     */
    @Test
    void theDerivedCollectionAndTagAccessorsAreNotOnTheWire() throws Exception {
        IngestionJob job = new IngestionJob(UUID.randomUUID(), "kg-extract", "ref",
            List.of("10.0", "9.3.1"), UUID.randomUUID(), "OIM", JobStatus.RUNNING, JobStep.EXTRACT,
            42, 1, null, null, OffsetDateTime.now(), null, null);

        // Both derived accessors return real, non-null data — so if Jackson saw them as properties
        // they would appear, and this test would be asserting nothing.
        assertThat(job.collection()).isEqualTo("OIM");
        assertThat(job.tag()).as("and it is LOSSY: a two-tag job collapses to one unusable string")
            .isEqualTo("10.0, 9.3.1");

        Map<String, Object> wire = MAPPER.readValue(MAPPER.writeValueAsString(job),
            new TypeReference<Map<String, Object>>() {});

        assertThat(wire).as("the wire carries the 17 record components and nothing else")
            .containsOnlyKeys("id", "kind", "sourceRef", "tags", "collectionId", "collectionName",
                "status", "step", "progress", "attempts", "error", "counts", "createdAt",
                "startedAt", "finishedAt", "settings", "summary");
        assertThat(wire)
            .as("a get-prefixed alias or a @JsonProperty would publish the same data twice")
            .doesNotContainKeys("collection", "tag");

        // The canonical components a client must use instead are present and unabridged.
        assertThat(wire.get("collectionName")).isEqualTo("OIM");
        assertThat(wire.get("tags")).isEqualTo(List.of("10.0", "9.3.1"));
    }

    /**
     * {@code IngestionJob.status} is an enum with no {@code @JsonValue}, so {@code GET
     * /api/jobs/v1/{id}} serializes it as the enum NAME — {@code "RUNNING"}. The SSE frame is a
     * different type: {@code ProgressEvent.status} is a plain String already converted through
     * {@code JobStatus.dbValue()}, so it is {@code "running"}. Both casings are on the wire
     * simultaneously and neither is a bug.
     *
     * <p>
     * The job-detail page proves it depends on both at once: it seeds its initial state from
     * {@code ${job.status.name()}} (uppercase, server-rendered) and then overwrites it from SSE
     * frames (lowercase). That is why {@code job-status.js} lowercases defensively in
     * {@code getStatusClass}/{@code isTerminalStatus}/{@code isStepCompleted}, and why the inline
     * script still tests {@code data.status === 'CANCELLED' || data.status === 'cancelled'}. So
     * "normalising" the API to the db value is not cosmetic — {@code capitalize(data.status)} would
     * render a shouting badge, and any page or client that compares an API status without
     * lowercasing silently stops matching. Equally, dropping the {@code dbValue()} call on the
     * frame breaks {@code isTerminalStatus}, which is what CLOSES the SSE stream: the emitter stays
     * open and the Stop button stays live on a finished job.
     *
     * <p>
     * {@code JobStep} is the opposite case and is asserted alongside because it is the one that
     * MUST be identical on both shapes: {@code @JsonValue} on its {@code dbValue} component makes
     * it serialize as a bare string rather than a three-field object. The page matches
     * {@code data.step} against {@code ${stepDbValues}} to decide which steps are done, so a step
     * serialized as {@code {"dbValue":"extract","label":…}} matches nothing and the progress UI
     * marks every step incomplete forever — with the job itself completing normally.
     *
     * <p>
     * Nothing else asserts on the JSON of any of these: the job ITs assert on the mapped
     * {@code JobStatus} enum, which is identical whatever the wire format says.
     */
    @Test
    void jobStatusIsUppercaseOnTheJobResourceAndLowercaseOnTheProgressFrame() throws Exception {
        IngestionJob job = new IngestionJob(UUID.randomUUID(), "kg-extract", "ref", "10.0", "OIM",
            JobStatus.RUNNING, JobStep.EXTRACT, 42, 1, null, null, OffsetDateTime.now(), null,
            null);

        JsonNode resource = MAPPER.readTree(MAPPER.writeValueAsString(job));
        JsonNode frame = MAPPER.readTree(MAPPER.writeValueAsString(ProgressEvent.of(job)));

        assertThat(resource.get("status").asText())
            .as("GET /api/jobs/v1/{id} carries the enum name; the page seeds itself from it")
            .isEqualTo("RUNNING");
        assertThat(frame.get("status").asText())
            .as("the SSE frame carries the db value; isTerminalStatus() closes the stream on it")
            .isEqualTo("running");
        assertThat(resource.get("status").asText())
            .as("the two shapes are deliberately not the same string")
            .isNotEqualTo(frame.get("status").asText());

        // JobStep is bare on BOTH shapes — @JsonValue, not an object.
        assertThat(resource.get("step").isTextual()).isTrue();
        assertThat(resource.get("step").asText()).isEqualTo("extract");
        assertThat(frame.get("step").asText()).isEqualTo("extract");
        assertThat(MAPPER.writeValueAsString(JobStep.EXTRACT)).isEqualTo("\"extract\"");
    }
}
