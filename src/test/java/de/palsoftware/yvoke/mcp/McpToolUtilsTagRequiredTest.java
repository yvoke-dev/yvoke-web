package de.palsoftware.yvoke.mcp;

import static org.junit.jupiter.api.Assertions.*;

import de.palsoftware.yvoke.collection.core.model.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A tag-scoped collection must be queried at one tag. Omitting it is silently wrong in two distinct
 * ways in this corpus: for version tags the same object exists once per release (93.5% of
 * Install-Kit identities), so an untagged read returns duplicates or a zero-edge picker; for
 * dataset tags (DB-History's schema/content) it merges two incompatible row shapes. Enforcing it in
 * one place replaces the same rule restated as prose across eleven playbooks, where it had already
 * drifted.
 */
public class McpToolUtilsTagRequiredTest {

    private static Collection tagged() {
        return new Collection(UUID.randomUUID(), "OIM - Manuals - Standard", "Manuals",
            List.of("9.3.1", "10.0"), null);
    }

    private static Collection untagged() {
        return new Collection(UUID.randomUUID(), "OIM - Customers", "Customers", List.of(), null);
    }

    @Test
    public void testMissingTagOnATaggedCollectionIsRejected() {
        String err = McpToolUtils.requireTag(tagged(), null);

        assertNotNull(err, "expected an error for an untagged call on a tag-scoped collection");
        assertTrue(err.startsWith("Error:"), "expected a tool error string, got: " + err);
        assertTrue(err.contains("OIM - Manuals - Standard"),
            "error must name the collection: " + err);
        // Enumerated so recovery costs zero extra discovery calls.
        assertTrue(err.contains("9.3.1") && err.contains("10.0"),
            "error must list the valid tags, got: " + err);
    }

    @Test
    public void testBlankTagIsTreatedAsMissing() {
        assertNotNull(McpToolUtils.requireTag(tagged(), "   "));
    }

    @Test
    public void testTagSuppliedOnATaggedCollectionIsAccepted() {
        assertNull(McpToolUtils.requireTag(tagged(), "10.0"));
    }

    @Test
    public void testUntaggedCollectionDoesNotRequireATag() {
        // OIM - Customers is the one collection with no tags, and its playbook tells the agent to
        // omit the parameter. The rule is conditional on the collection, so it exempts itself.
        assertNull(McpToolUtils.requireTag(untagged(), null));
    }

    @Test
    public void testNullTagListIsTreatedAsUntagged() {
        Collection nullTags = new Collection(UUID.randomUUID(), "Legacy", "Legacy", null, null);
        assertNull(McpToolUtils.requireTag(nullTags, null));
    }
}
