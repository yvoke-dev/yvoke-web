package de.palsoftware.yvoke.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.jsonobject.core.service.JsonObjectService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class GetJsonSchemaToolTest {

    private JsonObjectService jsonObjectService;
    private CollectionService collectionService;
    private GetJsonSchemaTool getJsonSchemaTool;

    @BeforeEach
    public void setUp() {
        jsonObjectService = mock(JsonObjectService.class);
        collectionService = mock(CollectionService.class);
        getJsonSchemaTool =
            new GetJsonSchemaTool(jsonObjectService, collectionService, new ObjectMapper());

        Collection historyCol = new Collection(UUID.randomUUID(), "OIM - DB - History",
            "Historic DB rows", List.of("schema", "content"), null);
        // A collection with NO tags exempts itself from the requirement — the guard is conditional
        // on the collection, which is exactly why it cannot live in the tool's JSON input schema.
        Collection untaggedCol =
            new Collection(UUID.randomUUID(), "OIM - Untagged", "No tags at all", List.of(), null);
        when(collectionService.listCollections()).thenReturn(List.of(historyCol, untaggedCol));
    }

    /**
     * A tag on a JSON collection is not a filter, it is <em>which dataset you are reading</em>:
     * DB-History's {@code schema} and {@code content} tags describe two completely different row
     * shapes stored under one collection. An untagged read therefore has no meaningful answer —
     * {@link JsonObjectService#getSchema} would return whichever row happens to carry a null tag,
     * and the agent would go on to build a {@code query_json_objects} JSONPath filter against
     * fields the rows it queries do not have. Nothing about that failure is visible: the tool
     * returns a perfectly well-formed schema, the query returns zero rows, and the agent reports
     * that the data does not exist.
     *
     * <p>
     * The requirement is conditional on the collection (an untagged collection must still work), so
     * it is deliberately absent from the tool's JSON input schema, which cannot express "required
     * depending on another argument's value". That makes this test the only thing enforcing it —
     * deleting the {@code McpToolUtils.requireTag} block compiles, starts, and answers.
     *
     * <p>
     * The {@code never()} half matters as much as the message: returning the error string while
     * still hitting the service would leave the ambiguous read on the hot path for any caller that
     * ignores the string. A blank tag is checked too, because the tool normalises {@code "   "} to
     * null and a whitespace argument is exactly what an LLM emits for "I have no value here". The
     * untagged-collection call at the end is the control, and it has to come <em>after</em> the
     * {@code never()} verification because it legitimately reaches the service: without it the rule
     * could be "fixed" by demanding a tag unconditionally, which would break every read of a corpus
     * that has no versions at all.
     */
    @Test
    public void testUntaggedCallOnATagScopedCollectionIsRejected() {
        String output = getJsonSchemaTool.getJsonSchema("OIM - DB - History", null);

        assertThat(output).as("expected a hard error, got:%n%s", output).startsWith("Error:");
        assertThat(output).contains("tag-scoped");
        assertThat(output).as("the error must list the tags the caller has to choose between")
            .contains("Valid tags: schema, content");

        String blankTag = getJsonSchemaTool.getJsonSchema("OIM - DB - History", "   ");
        assertThat(blankTag).as("a whitespace tag is not a tag").startsWith("Error:");
        assertThat(blankTag).contains("Valid tags: schema, content");

        verify(jsonObjectService, never()).getSchema(any(), any());

        String untagged = getJsonSchemaTool.getJsonSchema("OIM - Untagged", null);
        assertThat(untagged)
            .as("a collection with no tags must still be readable, got:%n%s", untagged)
            .doesNotStartWith("Error:");
    }
}
