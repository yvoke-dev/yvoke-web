package de.palsoftware.yvoke.mcp;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a tool class in {@code mcp.tools} whose tools are for connected MCP clients only (the
 * Claude plugin and its Chat/Cowork playbook stubs), never for the in-app assistant.
 *
 * <p>
 * {@link McpToolsConfig} publishes such a class through {@code clientOnlyToolCallbacks}, a
 * {@code ToolCallbackProvider} that the MCP server reads and {@code RagService} does not. Without
 * the mark a tool lands in the shared list, whose names the admin playbook editor offers, so any
 * playbook could hand a plugin-control tool to the web model. Sorting by a class-level mark rather
 * than by a list of names means a new plugin-only tool needs no second edit anywhere.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ClientOnlyTools {
}
