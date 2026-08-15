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
import java.time.OffsetDateTime;
import org.hamcrest.Matchers;

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
            .andExpect(content().string(Matchers.containsString("Render Test Guide")));
    }

    @Test
    public void documentDetailRendersWithDtoDocumentAndChunks() throws Exception {
        mockMvc.perform(get("/admin/documents/" + documentId).with(admin()))
            .andExpect(status().isOk())
            .andExpect(content().string(Matchers.containsString("Render Test Guide")))
            .andExpect(content().string(Matchers.containsString("Introduction")));
    }

    /**
     * The citation dialog's markup is a CONTRACT with {@code thread.js}, not decoration, and it is
     * unpinned today in two load-bearing places.
     *
     * <p>
     * {@code thread.js} drops the fetched fragment into the dialog with {@code innerHTML} and then
     * runs {@code contentContainer.querySelectorAll('.citation-content-md:not([data-md-rendered])')}
     * to markdown-render the body (thread.js:658). That class is carried by exactly one element —
     * {@code class="citation-expander-body citation-content-md"} — and it looks redundant next to
     * {@code citation-expander-body}, which has its own CSS rules. Drop it, and the dialog renders
     * the section's RAW MARKDOWN: pipes and asterisks and fence markers where a formatted section
     * should be, on the one surface whose entire job is to show a user the source behind a claim.
     * Nothing errors, and the only end-to-end test of this fragment —
     * {@code ChatFeedbackCitationE2EIT.citationMarkerRendersLinkAndOpensResolvableDialog} — asserts
     * plain substrings ({@code containsText("OIM Admin Guide")}) that survive the regression
     * unchanged, because the text is all still there, just unrendered.
     *
     * <p>
     * The error branch has no coverage AT ALL. All eight {@code CitationControllerTest} cases stop
     * at the model: they assert the view name plus the {@code section}/{@code error} attributes and
     * never render a line of HTML, so {@code th:if="${error != null}"} — the branch that shows a
     * user MISSING_PARAMS / UNRESOLVABLE / NOT_FOUND / UNAVAILABLE — is only ever checked as a
     * string in a map. If the two {@code th:if}s ever both evaluate true (or the error div loses its
     * class), the user gets a blank card, or an empty {@code .citation-content-md} that thread.js
     * dutifully markdown-renders into nothing, with the real explanation nowhere on screen.
     *
     * <p>
     * Lives in this class because its {@code @BeforeEach} already seeds the collection + document +
     * chunk the fragment needs and its admin login carries ROLE_USER, so pinning the contract costs
     * no new fixture and no new Spring context.
     */
    @Test
    public void theCitationFragmentCarriesTheClassesThreadJsDependsOn() throws Exception {
        String resolved = mockMvc
            .perform(
                get("/document/citation").param("documentId", documentId.toString()).with(admin()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(resolved).contains("class=\"citation-expander-card\"")
            .contains("class=\"citation-source-info\"")
            .contains("class=\"citation-scope-badge\"")
            // thread.js selects EXACTLY this class to markdown-render the body; without it the
            // dialog shows raw markdown while every text-level assertion still passes.
            .contains("class=\"citation-expander-body citation-content-md\"")
            .contains("Render Test Guide").contains("First chunk body text.");

        String failed = mockMvc.perform(get("/document/citation").with(admin()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(failed).as("the error branch is what a user reads when a citation cannot be "
            + "resolved; it renders in no other test").contains("class=\"citation-error\"")
            .contains("Either chunkId or documentId must be provided.");
        assertThat(failed)
            .as("the branches are mutually exclusive: an error must not also emit an empty body div "
                + "for thread.js to markdown-render into nothing")
            .doesNotContain("citation-content-md");
    }

    /**
     * The "Surfaced by Chat Messages" table is the only place in the product that answers "which
     * conversations did this chunk actually reach?", and every cell of it is a string key looked up
     * out of an untyped {@code Map<String, Object>} produced by
     * {@code ChatAdminQueryRepository.findMessagesSurfacingChunk} — the sole implementation of the
     * {@code ChunkSurfacingMessageLookup} SPI. Nothing types that contract, so the six keys are a
     * hand-maintained agreement between one SQL string and one template, and every way of breaking
     * it is silent: renaming {@code conversation_title} renders every row as "Unnamed Chat",
     * renaming {@code created_at} renders "-" for the timestamp, and only {@code conversation_id}
     * fails loudly (the template calls {@code .toString()} on it, so a rename is a 500).
     *
     * <p>
     * The {@code ORDER BY m.created_at DESC LIMIT 10} is load-bearing too: on a chunk the retrieval
     * lanes return often this decides WHICH ten of hundreds of messages an admin is shown while
     * auditing a bad answer, and "the ten most recent" is the only reading of that table that is
     * not actively misleading. Twelve messages are seeded rather than two so both directions are
     * covered — widening the limit surfaces the two oldest, narrowing it drops the tenth.
     *
     * <p>
     * Nothing existing notices any of this. {@link #chunkDetailRendersWithDtoChunk()} is the only
     * test that renders this page and its {@code setUp} seeds one collection, one document and one
     * chunk and no messages at all — so it exercises the empty-list branch, and the six keys, the
     * join to {@code conversations} and the ordering are executed by nothing in the suite.
     */
    @Test
    public void chunkDetailRendersTheMessagesThatSurfacedTheChunk() throws Exception {
        UUID conversationId = UUID.randomUUID();
        try {
            jdbcTemplate.update(
                "INSERT INTO conversations (id, user_id, title, source) VALUES (?, NULL, ?, 'web')",
                conversationId, "Chunk Surfacing Chat");

            // A fixed mid-day UTC instant: whatever zone the renderer applies, the rendered date
            // still starts "2024-03-0", so the assertion cannot flake on the JVM's default zone.
            OffsetDateTime base = OffsetDateTime.parse("2024-03-05T12:00:00Z");
            for (int i = 0; i < 12; i++) {
                jdbcTemplate.update(
                    "INSERT INTO messages (id, conversation_id, role, content, retrieved_chunk_ids, created_at) "
                        + "VALUES (?, ?, ?, ?, ARRAY[?]::uuid[], ?)",
                    UUID.randomUUID(), conversationId, i % 2 == 0 ? "user" : "assistant",
                    String.format("surfaced-message-%02d", i), chunkId, base.minusMinutes(i));
            }
            // Same conversation, but this message never retrieved the chunk: the ANY() predicate is
            // a filter, not a join to the conversation.
            jdbcTemplate.update(
                "INSERT INTO messages (id, conversation_id, role, content, retrieved_chunk_ids, created_at) "
                    + "VALUES (?, ?, 'user', ?, NULL, ?)",
                UUID.randomUUID(), conversationId, "unrelated-message", base);

            String body = mockMvc.perform(get("/admin/chunks/" + chunkId).with(admin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

            assertThat(body).as("conversation_title, conversation_id, role, content and created_at"
                + " must all reach the template under those exact keys")
                .contains("Chunk Surfacing Chat")
                .contains(conversationId.toString().substring(0, 8)).contains("badge-info")
                .contains("2024-03-0");

            assertThat(body).as("the ten most recent surfacing messages, newest first")
                .contains("surfaced-message-00").contains("surfaced-message-09")
                .doesNotContain("surfaced-message-10").doesNotContain("surfaced-message-11");

            assertThat(body).as("a message that did not retrieve this chunk is not a surfacing")
                .doesNotContain("unrelated-message")
                .doesNotContain("has not been surfaced by any chat retrievals yet");
        } finally {
            // messages cascade with the conversation (fk_messages_conversations ON DELETE CASCADE).
            jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", conversationId);
        }
    }

    /**
     * {@code DocumentRepository.findSectionSummaries} builds an untyped {@code Map<String, Object>}
     * whose four keys are a three-way contract nothing types and nothing else checks: {@code path}
     * is read ONLY by the Java sorter in {@code DocumentAdminViewService.sectionSummaries} (cast
     * blind to {@code List<String>} and fed through {@code HierarchyUtils.normalizeSegment}), while
     * {@code pathStr}, {@code depth} and {@code summary} are read ONLY by
     * {@code admin/document-detail.html}. The two halves fail in opposite ways — {@code depth} is
     * used in ARITHMETIC ({@code (summary.depth - 1) * 1.5} rem of indent), so losing it is a 500 on
     * the page, whereas losing {@code path} is completely silent.
     *
     * <p>
     * The SELECT has no {@code ORDER BY}, deliberately: the intended order is PHYSICAL document
     * order, which only the chunk rows know. So ordering is entirely the caller's job and works only
     * if this method emits a {@code path} the sorter can normalize and find in the chunk-derived
     * {@code pathSortOrders} map. Emit the wrong thing and every row falls back to
     * {@code Integer.MAX_VALUE}, the comparator returns 0 for every pair, {@code List.sort} is
     * stable, and the operator reading a manual's summaries top-to-bottom is silently reading them
     * in whatever order the summarizer happened to INSERT them — which for a re-summarized document
     * is not document order at all.
     *
     * <p>
     * The two {@code section_summaries} rows here are inserted in REVERSE document order precisely
     * so that "sorted correctly" and "returned in insert order" are distinguishable in the rendered
     * HTML.
     *
     * <p>
     * This paragraph used to add that nothing else in the suite executed the sorter, because
     * {@code sectionSummaries} short-circuited to {@code List.of()} for anything that was not
     * {@code hierarchical}/{@code confluence}. That gate is gone — summaries are now fetched for
     * every kind and {@link #sectionSummariesOnAStandardDocumentRenderToo()} covers the standard
     * case — so this test is no longer the sorter's only witness, only its ordering one.
     *
     * <p>
     * Lives in this class because its {@code @BeforeEach} already provides the collection, the admin
     * OIDC login and MockMvc; a second document mints no new Spring context, and it is cleaned up by
     * the existing {@code deleteByCollection} teardown (section_summaries cascade with the document).
     */
    @Test
    public void sectionSummariesOnAHierarchicalDocumentRenderInDocumentOrder() throws Exception {
        UUID hierarchicalId = documentRepository.upsertManualDocument(COLLECTION, "9.3", "manual.md",
            "hierarchical", "Hierarchical Render Manual");
        documentRepository.insertChunks(hierarchicalId, COLLECTION, "9.3", "manual.md",
            "hierarchical",
            List.of(
                new ChunkInsert("Install the prerequisites first.", null, List.of("Installation"),
                    "Prerequisites", 2, 0),
                new ChunkInsert("Restore the backup to roll back.", null, List.of("Rollback"),
                    "Restoring Backups", 2, 1)));

        // Inserted in REVERSE document order (and anti-alphabetically by summary text), so neither
        // "the rows came back in insert order" nor any accidental text sort can be mistaken for
        // "ordered by the chunk position of the heading path".
        jdbcTemplate.update(
            "INSERT INTO section_summaries (id, document_id, heading_path, summary) "
                + "VALUES (?, ?, ARRAY[?, ?]::text[], ?)",
            UUID.randomUUID(), hierarchicalId, "Rollback", "Restoring Backups",
            "Alpha summary: how to restore a backup.");
        jdbcTemplate.update(
            "INSERT INTO section_summaries (id, document_id, heading_path, summary) "
                + "VALUES (?, ?, ARRAY[?]::text[], ?)",
            UUID.randomUUID(), hierarchicalId, "Installation",
            "Zeta summary: what to install first.");

        String body = mockMvc.perform(get("/admin/documents/" + hierarchicalId).with(admin()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        int installation = body.indexOf("Zeta summary: what to install first.");
        int rollback = body.indexOf("Alpha summary: how to restore a backup.");

        assertThat(installation).as("the Section Summaries card renders at all for a hierarchical "
            + "document").isNotNegative();
        assertThat(rollback).as("both summary rows reach the template under the 'summary' key")
            .isNotNegative();
        assertThat(installation).as(
            "ordered by the chunk sort_order of each heading path: Installation is chunk #0 and "
                + "Rollback is chunk #1, while the rows were INSERTED the other way round")
            .isLessThan(rollback);

        assertThat(body.replace("&gt;", ">"))
            .as("pathStr is the ' > '-joined heading path and has exactly one consumer, this card")
            .contains("Rollback > Restoring Backups");

        assertThat(body)
            .as("depth is path.length, and the template does ARITHMETIC with it - (depth - 1) * 1.5"
                + " rem of indent - so a missing or non-numeric depth is a 500, not a blank badge")
            .contains("Depth 1").contains("Depth 2").contains("margin-left: 1.5rem");
    }

    /**
     * A standard document with summaries must render them. The panel used to be gated on
     * {@code document.kind()}, which was a correct proxy only while summarisation ran in the
     * hierarchical ingest alone — a standard manual ingested with the opt-in setting had hundreds
     * of rows in {@code section_summaries} and showed nothing, with no indication why.
     */
    @Test
    public void sectionSummariesOnAStandardDocumentRenderToo() throws Exception {
        jdbcTemplate.update(
            "INSERT INTO section_summaries (id, document_id, heading_path, summary) "
                + "VALUES (?, ?, ARRAY[?]::text[], ?)",
            UUID.randomUUID(), documentId, "Introduction",
            "Standard-document summary that must be visible.");

        String body = mockMvc.perform(get("/admin/documents/" + documentId).with(admin()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).as("a standard document's summaries are real data, not a kind to test for")
            .contains("Standard-document summary that must be visible.");
    }

    /**
     * The panel is collapsed on arrival. A real manual carries hundreds of summaries — the live
     * corpus has one document with 485 — so an expanded panel buries the chunk list and the
     * metadata under a page-length wall of prose.
     *
     * <p>
     * Asserted on the markup rather than through a browser because {@code <details>} needs no
     * JavaScript: closed IS the absence of the {@code open} attribute, so a regression would be a
     * one-word edit that nothing else would catch.
     */
    @Test
    public void theSectionSummariesPanelStartsCollapsed() throws Exception {
        jdbcTemplate.update(
            "INSERT INTO section_summaries (id, document_id, heading_path, summary) "
                + "VALUES (?, ?, ARRAY[?]::text[], ?)",
            UUID.randomUUID(), documentId, "Introduction", "Some summary text.");

        String body = mockMvc.perform(get("/admin/documents/" + documentId).with(admin()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).as("the summaries must sit inside a native disclosure element")
            .contains("<details id=\"section-summaries\"");
        assertThat(body).as("default closed: `open` would restore the wall of text")
            .doesNotContain("<details id=\"section-summaries\" open");
    }


    /**
     * A link to a document or chunk that no longer exists must be a 404 — not a 500 and not a
     * blank page. Stale links are the NORMAL state right after any delete cascade: the corpus
     * browser, the citation cards and every admin bookmark keep pointing at rows a re-ingest or a
     * collection delete removed. So this is one of the most frequently walked paths in the admin
     * UI, and it is the one path nothing executes — every other test in this class renders a row it
     * created in {@code setUp}, and {@code MvcExceptionHandlerTest} exercises
     * {@code ResponseStatusException} against synthetic controllers, never against these two
     * handlers.
     *
     * <p>
     * Losing either {@code orElseThrow} turns the missing row into a template evaluation over a
     * null model attribute — a 500 with a stack trace, or a shell page with empty cards — instead
     * of the 404 the error page exists to render, and an operator then cannot tell "this document
     * was deleted" from "the admin UI is broken". The status is what has to be asserted, because
     * the exception only becomes a real 404 by way of {@code MvcExceptionHandler.handleStatus}
     * re-emitting it through {@code response.sendError}; asserting it here also pins that the
     * global MVC advice does not swallow a GET render into its generic redirect.
     */
    @Test
    public void aStaleLinkToADeletedDocumentOrChunkRendersA404NotA500() throws Exception {
        UUID gone = UUID.randomUUID();

        mockMvc.perform(get("/admin/documents/" + gone).with(admin()))
            .andExpect(status().isNotFound());

        mockMvc.perform(get("/admin/chunks/" + gone).with(admin()))
            .andExpect(status().isNotFound());

        // The rows that ARE there still render, so the 404 is about absence rather than about a
        // page that stopped working.
        mockMvc.perform(get("/admin/documents/" + documentId).with(admin()))
            .andExpect(status().isOk());
        mockMvc.perform(get("/admin/chunks/" + chunkId).with(admin())).andExpect(status().isOk());
    }

    @Test
    public void chunkDetailRendersWithDtoChunk() throws Exception {
        mockMvc.perform(get("/admin/chunks/" + chunkId).with(admin()))
            .andExpect(status().isOk())
            .andExpect(content().string(Matchers.containsString("First chunk body text.")));
    }
}
