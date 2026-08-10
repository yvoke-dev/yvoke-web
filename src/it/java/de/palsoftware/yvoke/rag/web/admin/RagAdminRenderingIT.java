package de.palsoftware.yvoke.rag.web.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.palsoftware.yvoke.rag.retrieval.HybridSearch;
import de.palsoftware.yvoke.rag.retrieval.HybridSearchResult;
import de.palsoftware.yvoke.rag.retrieval.RetrievalLogDetails;
import de.palsoftware.yvoke.rag.retrieval.RetrievalLogRepository;
import de.palsoftware.yvoke.rag.retrieval.RetrievalTelemetryRow;
import de.palsoftware.yvoke.rag.retrieval.SearchWithId;
import de.palsoftware.yvoke.rag.retrieval.TelemetryInfo;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.hamcrest.Matchers;

/**
 * Full Thymeleaf render coverage for the RAG admin pages after the DTO-at-boundary refactor
 * (Wave 3.3). The retrieval collaborators are mocked to return canned domain objects (the real
 * search path hits embedding/rerank services), so the search-result and retrieval-log DTOs — the
 * former nesting a telemetry view, the latter with the derived truncation accessor — are exercised
 * through an actual render; a missing accessor would 500.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "app.security.mock=true")
public class RagAdminRenderingIT {

    private static final String COLLECTION = "OIM-RAGVIEW-TEST";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private HybridSearch hybridSearch;

    @MockitoBean
    private RetrievalLogRepository retrievalLogRepository;

    private MockMvc mockMvc;

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
    }

    @AfterEach
    public void tearDown() {
        jdbcTemplate.update("DELETE FROM collections WHERE name = ?", COLLECTION);
    }

    @Test
    public void searchConsoleRendersSearchResultDtos() throws Exception {
        UUID chunkId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        HybridSearchResult result = new HybridSearchResult(chunkId, UUID.randomUUID(),
            "chunk body text for render", List.of("Introduction"), "Introduction", 1, 0, "9.3",
            "Doc Title", "standard", COLLECTION, Map.of(), 0.9876, new TelemetryInfo(true, false, 5, 0, 1));
        UUID searchId = UUID.randomUUID();
        when(hybridSearch.searchWithId(anyString(), any()))
            .thenReturn(new SearchWithId(List.of(result), searchId));
        // The telemetry panel renders alongside results and reads these keys — supply a realistic
        // row so the render exercises the SearchResultView section under test AND the lane-trace
        // block, whose stage snapshots must satisfy initial.length == sem + ft to produce a trace.
        when(retrievalLogRepository.findTelemetryById(any())).thenReturn(Optional.of(
            new RetrievalTelemetryRow("{\"sem\": 5, \"ft\": 3}",
                "{\"n\": 8, \"both\": 2, \"sem_only\": 3, \"ft_only\": 3}",
                "{\"promotions\": 1, \"top1_changed\": false, \"avg_disp\": 0.5, \"rrf_order\": \"[]\"}",
                1, 1, List.of(chunkId, otherId), List.of(chunkId, otherId), List.of(chunkId))));

        mockMvc.perform(get("/admin/search").param("query", "test").param("collection", COLLECTION)
                .param("tag", "all").param("semantic", "true").param("fulltext", "true").with(admin()))
            .andExpect(status().isOk())
            .andExpect(content().string(Matchers.containsString("chunk body text for render")))
            .andExpect(content().string(Matchers.containsString("Returned Rows by Lane")))
            .andExpect(content().string(Matchers.containsString("fused candidates")));
    }

    @Test
    public void searchConsoleRendersWhenTelemetryAbsent() throws Exception {
        // Regression: when no telemetry row exists, the controller binds an empty map, so the
        // reranker panel's `telemetryRerank.get('top1_changed')` is null. The old
        // `${... ? 'badge-warning' : 'badge-success'}` ternary coerced null to boolean and 500'd
        // the whole page; the null-safe `== true` guard must let results still render.
        HybridSearchResult result = new HybridSearchResult(UUID.randomUUID(), UUID.randomUUID(),
            "results without telemetry", List.of("Introduction"), "Introduction", 1, 0, "9.3",
            "Doc Title", "standard", COLLECTION, Map.of(), 0.5, new TelemetryInfo(false, false, 5, 0, 1));
        when(hybridSearch.searchWithId(anyString(), any()))
            .thenReturn(new SearchWithId(List.of(result), UUID.randomUUID()));
        when(retrievalLogRepository.findTelemetryById(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/admin/search").param("query", "test").param("collection", COLLECTION)
                .param("tag", "all").param("semantic", "true").param("fulltext", "true").with(admin()))
            .andExpect(status().isOk())
            .andExpect(content().string(Matchers.containsString("results without telemetry")));
    }

    @Test
    public void retrievalLogsRenderLogDtos() throws Exception {
        RetrievalLogDetails logRow = new RetrievalLogDetails(UUID.randomUUID(), UUID.randomUUID(),
            COLLECTION, "9.3", "{}", "{}", "{}", Instant.now(), "the retrieval message content", 1,
            "great answer", List.of());
        when(retrievalLogRepository.listLogs(anyInt(), anyInt())).thenReturn(List.of(logRow));
        when(retrievalLogRepository.countLogs()).thenReturn(1L);

        mockMvc.perform(get("/admin/logs").with(admin())).andExpect(status().isOk())
            .andExpect(content().string(
                Matchers.containsString("the retrieval message content")));
    }
}
