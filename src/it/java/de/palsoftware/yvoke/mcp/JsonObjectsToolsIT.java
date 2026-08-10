package de.palsoftware.yvoke.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import de.palsoftware.yvoke.jsonobject.core.model.JsonObject;
import de.palsoftware.yvoke.jsonobject.core.repository.JsonObjectRepository;
import de.palsoftware.yvoke.collection.core.repository.CollectionRepository;
import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.mcp.tools.QueryJsonObjectsTool;
import de.palsoftware.yvoke.mcp.tools.GetJsonSchemaTool;
import de.palsoftware.yvoke.tag.core.repository.TagRepository;
import de.palsoftware.yvoke.jsonobject.core.model.JsonSchema;
import de.palsoftware.yvoke.jsonobject.core.repository.JsonSchemaRepository;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.ApplicationContext;
import de.palsoftware.yvoke.rag.core.service.RagService;

@SpringBootTest(properties = {"spring.flyway.enabled=true",
    "spring.flyway.locations=filesystem:docker/db/migration"})
public class JsonObjectsToolsIT {

    private static final String COLLECTION_NAME = "OIM-JSON-TOOLS-TEST";

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private JsonObjectRepository jsonObjectRepository;

    @Autowired
    private CollectionRepository collectionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private QueryJsonObjectsTool queryTool;

    @Autowired
    private GetJsonSchemaTool schemaTool;

    @Autowired
    private JsonSchemaRepository jsonSchemaRepository;

    private UUID collectionId;

    @BeforeEach
    public void setUp() {
        cleanup();
        collectionRepository.create(COLLECTION_NAME, "Test Collection");
        collectionId =
            collectionRepository.findByName(COLLECTION_NAME).map(Collection::id).orElseThrow();

        Map<String, Object> data = Map.of("name", "Alice", "role", "admin");
        JsonObject obj = new JsonObject(UUID.randomUUID(), collectionId, COLLECTION_NAME, data,
            "users.json", OffsetDateTime.now());
        jsonObjectRepository.save(obj);
    }

    @AfterEach
    public void tearDown() {
        cleanup();
    }

    /**
     * The {@code mcpToolCallbacks} bean is ONE catalogue serving two consumers: the MCP server and
     * {@link RagService}, which is the in-app chat agent, the orchestrator and every specialist.
     * That sharing is the reason a fix in {@code mcp/tools} reaches both at once — and it is held
     * up by nothing more than a {@code List<ToolCallback>} constructor parameter.
     *
     * <p>
     * Spring resolves a collection injection point against individual {@code ToolCallback} beans
     * FIRST and only falls back to the {@code List<ToolCallback>} bean when none exist, so the day
     * anything contributes a single {@code ToolCallback} bean — spring-ai, or a well-meaning
     * {@code @Component} on one of the per-run callbacks — {@code RagService} silently receives
     * that instead. Narrowing or reordering the parameter has the same effect. The failure is
     * invisible from every angle that is currently tested: {@code everyDocumentedToolIsPresentInTheRegisteredCallbackList}
     * reads the bean directly and stays green, {@code McpServerEndpointsIT} still lists ten tools
     * over JSON-RPC, and the app still answers — it just answers without retrieval, because the
     * agent's registry is empty or partial, which reads as a quality regression rather than a
     * wiring bug.
     *
     * <p>
     * The instance identity assertion is the load-bearing half: equal NAMES would also hold for a
     * separately built list, which would then drift from the MCP one silently.
     */
    @Test
    public void theInAppChatAgentIsFedTheSameToolCallbackInstancesAsTheMcpServer() {
        List<?> callbacks = applicationContext.getBean("mcpToolCallbacks", List.class);
        RagService ragService = applicationContext.getBean(RagService.class);

        List<String> mcpNames =
            callbacks.stream().map(c -> ((ToolCallback) c).getToolDefinition().name()).toList();

        assertThat(ragService.getToolRegistry().keySet())
            .as("the agentic catalogue and the MCP catalogue are the same list")
            .containsExactlyInAnyOrderElementsOf(mcpNames);

        for (Object c : callbacks) {
            ToolCallback callback = (ToolCallback) c;
            assertThat(ragService.getToolRegistry().get(callback.getToolDefinition().name()))
                .as("the agent must hold the SAME %s instance the MCP server serves",
                    callback.getToolDefinition().name())
                .isSameAs(callback);
        }
    }

    private void cleanup() {
        jdbcTemplate.update("DELETE FROM json_objects");
        jdbcTemplate.update("DELETE FROM json_schemas");
        jdbcTemplate.update("DELETE FROM collections WHERE name = ?", COLLECTION_NAME);
    }

    @Test
    public void testQueryTool() {
        String response = queryTool.queryJsonObjects(COLLECTION_NAME, "$.role ? (@ == \"admin\")",
            null, "name,role", 10, 0, null, null);

        assertThat(response).contains("Alice");
        assertThat(response).contains(COLLECTION_NAME);
    }

    @Test
    public void testQueryToolWithTags() {
        Map<String, Object> data = Map.of("name", "Bob", "role", "user");
        JsonObject obj = new JsonObject(UUID.randomUUID(), collectionId, COLLECTION_NAME, data,
            "users.json", List.of("release"), OffsetDateTime.now());
        jsonObjectRepository.save(obj);

        tagRepository.addTagToCollection(collectionId, "release");

        String response =
            queryTool.queryJsonObjects(COLLECTION_NAME, null, "release", "name", 10, 0, null, null);
        assertThat(response).contains("Bob");
        assertThat(response).doesNotContain("Alice");
    }

    /**
     * Tool registration in {@code McpToolsConfig} cannot fail loudly: every branch is wrapped in a
     * {@code try/catch} that logs and continues, and a name collision is dropped at DEBUG level
     * ({@code registeredNames.add(name)} returning false). So a tool whose bean stops being
     * discovered — a dropped {@code @Component}, a constructor that throws, a class moved out of
     * {@code de.palsoftware.yvoke.mcp.tools} (the package the scanner is pointed at), or a second
     * tool claiming an existing name — simply is not in the catalogue. The application starts
     * normally, every other tool works, and the only symptom is an agent that never calls the
     * missing capability: "this manual has no table of contents" rather than "get_toc is gone".
     * That is indistinguishable from a corpus gap, which is how it would survive a release.
     *
     * <p>
     * Nothing else asserts the catalogue's membership. {@code McpToolCatalogueParityTest} compares
     * the two annotation catalogues on each class but never asks which classes are registered, and
     * {@code McpServerEndpointsIT.testListToolsRpc} checks only {@code search_corpus} — one of the
     * two callbacks added by hand BEFORE the scanning loop, so it passes even if the loop registers
     * nothing at all. The exact-match assertion is deliberate in both directions: a duplicate would
     * mean one registration silently lost the race, and an unexpected extra name means a tool
     * reached the agents without reaching this list.
     *
     * <p>
     * The bean is fetched by NAME rather than injected as {@code List<ToolCallback>}: a collection
     * injection point is resolved against individual {@code ToolCallback} beans first and only
     * falls back to the {@code List<ToolCallback>} bean when none exist, so it would quietly start
     * meaning something else the day spring-ai contributes a single {@code ToolCallback} bean.
     */
    @Test
    public void everyDocumentedToolIsPresentInTheRegisteredCallbackList() {
        List<?> callbacks = applicationContext.getBean("mcpToolCallbacks", List.class);

        List<String> names = callbacks.stream()
            .map(c -> ((ToolCallback) c).getToolDefinition().name()).toList();

        assertThat(names).containsExactlyInAnyOrder("search_corpus", "ask_clarifying_question",
            "get_toc", "get_section", "list_documents", "get_graph_neighbors",
            "search_graph_entities", "get_json_schema", "query_json_objects", "verify_citations");
    }

    @Test
    public void testGetSchemaTool() {
        Map<String, Object> schemaData = Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string")));
        JsonSchema schema = new JsonSchema(UUID.randomUUID(), collectionId, null, schemaData, "manual", OffsetDateTime.now());
        jsonSchemaRepository.upsert(schema);

        String response = schemaTool.getJsonSchema(COLLECTION_NAME, null);
        assertThat(response).contains("properties");
        assertThat(response).contains("name");

        String emptyResponse = schemaTool.getJsonSchema("non-existent-collection", null);
        assertThat(emptyResponse).contains("Error");
    }
}
