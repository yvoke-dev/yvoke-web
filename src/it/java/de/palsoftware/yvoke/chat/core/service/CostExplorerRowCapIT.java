package de.palsoftware.yvoke.chat.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import de.palsoftware.yvoke.chat.core.service.CostCalculationService.FilteredCostExplorerReport;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PRF-01: the per-call cost-explorer view must never fetch the whole {@code llm_call_logs} table. It
 * is capped at {@code app.chat.cost-explorer.max-rows}; here we set the cap low and seed more rows to
 * prove the fetch is bounded.
 */
@SpringBootTest(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=filesystem:docker/db/migration",
    "app.chat.cost-explorer.max-rows=3"
})
public class CostExplorerRowCapIT {

    private static final String MODEL = "CAP-IT-MODEL";

    @Autowired
    private CostCalculationService costCalculationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    public void tearDown() {
        jdbcTemplate.update("DELETE FROM llm_call_logs WHERE model = ?", MODEL);
    }

    @Test
    public void rawViewIsCappedAtConfiguredMaxRows() {
        for (int i = 0; i < 10; i++) {
            jdbcTemplate.update(
                "INSERT INTO llm_call_logs (id, model, source, prompt_tokens, completion_tokens, created_at) "
                    + "VALUES (?, ?, 'chat', 10, 10, now())",
                UUID.randomUUID(), MODEL);
        }

        FilteredCostExplorerReport report = costCalculationService.getFilteredExplorerReport(
            "RAW", null, null, List.of(MODEL), List.of(), List.of());

        // 10 rows seeded, cap is 3 → the view returns at most the cap, never the full set.
        assertThat(report.messages()).hasSize(3);
    }

    /**
     * The conversation query applies its row cap with a LIMIT. Without an ORDER BY, Postgres is
     * free to return ANY rowCap of the grouped rows, so a wide date range showed an arbitrary
     * subset that could differ between two identical requests. Ordering on a total key makes the
     * truncation both reproducible and useful (most recent kept).
     */
    @Test
    public void conversationViewTruncatesDeterministicallyAndKeepsTheMostRecent() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, entra_oid, email, display_name) VALUES (?, ?, ?, ?)", userId,
            "cap-it-" + userId, userId + "@example.com", "Cap IT User");

        // Six conversations, ascending updated_at; the cap is 3, so the three newest must win.
        List<UUID> newest = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            UUID convId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO conversations (id, user_id, title, updated_at) "
                    + "VALUES (?, ?, ?, now() - make_interval(mins => ?))",
                convId, userId, "cap-conv-" + i, 6 - i);
            jdbcTemplate.update(
                "INSERT INTO llm_call_logs (id, conversation_id, user_id, model, source, "
                    + "prompt_tokens, completion_tokens, created_at) "
                    + "VALUES (?, ?, ?, ?, 'chat', 10, 10, now())",
                UUID.randomUUID(), convId, userId, MODEL);
            newest.add(convId);
        }
        List<UUID> expected = newest.subList(3, 6);

        FilteredCostExplorerReport first = costCalculationService.getFilteredExplorerReport(
            "CONVERSATION", null, null, List.of(MODEL), List.of(), List.of());
        FilteredCostExplorerReport second = costCalculationService.getFilteredExplorerReport(
            "CONVERSATION", null, null, List.of(MODEL), List.of(), List.of());

        assertThat(first.conversations()).hasSize(3);
        assertThat(first.conversations()).extracting(r -> r.conversationId())
            .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(second.conversations()).extracting(r -> r.conversationId())
            .containsExactlyInAnyOrderElementsOf(expected);

        jdbcTemplate.update("DELETE FROM llm_call_logs WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM conversations WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }
}
