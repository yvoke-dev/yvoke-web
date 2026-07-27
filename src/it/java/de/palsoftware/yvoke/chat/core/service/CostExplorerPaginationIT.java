package de.palsoftware.yvoke.chat.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import de.palsoftware.yvoke.chat.core.service.CostCalculationService.FilteredCostExplorerReport;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Slice 3: forward keyset pagination for the RAW cost-explorer view. The page size is pinned low
 * (2) so a handful of seeded rows exercises multiple pages. Verifies the three properties that make
 * keyset pagination correct: (1) paging forward covers every row exactly once in {@code created_at}
 * DESC order and stops (null {@code nextCursor}) at the end; (2) the {@code id} tiebreaker prevents
 * skips/duplicates when timestamps collide; (3) a malformed/blank cursor safely falls back to the
 * first page (the cursor is URL-supplied).
 */
@SpringBootTest(properties = {"spring.flyway.enabled=true",
    "spring.flyway.locations=filesystem:docker/db/migration", "app.chat.cost-explorer.max-rows=2"})
public class CostExplorerPaginationIT {

  private static final String MODEL = "PAGE-IT-MODEL";

  @Autowired
  private CostCalculationService costCalculationService;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @AfterEach
  public void tearDown() {
    jdbcTemplate.update("DELETE FROM llm_call_logs WHERE model = ?", MODEL);
  }

  private FilteredCostExplorerReport rawPage(String cursor) {
    return costCalculationService.getFilteredExplorerReport("RAW", null, null, List.of(MODEL),
        List.of(), List.of(), List.of(), cursor);
  }

  private void seedAtNowMinusMinutes(int minutes) {
    jdbcTemplate.update("INSERT INTO llm_call_logs (id, model, source, prompt_tokens, "
        + "completion_tokens, created_at) VALUES (?, ?, 'chat', 10, 10, now() - make_interval(mins => ?))",
        UUID.randomUUID(), MODEL, minutes);
  }

  @Test
  public void paginatesForwardCoveringEveryRowOnceInOrder() {
    for (int i = 0; i < 5; i++) {
      seedAtNowMinusMinutes(i); // i=0 newest ... i=4 oldest
    }

    FilteredCostExplorerReport p1 = rawPage(null);
    assertThat(p1.messages()).hasSize(2);
    assertThat(p1.nextCursor()).isNotNull();

    FilteredCostExplorerReport p2 = rawPage(p1.nextCursor());
    assertThat(p2.messages()).hasSize(2);
    assertThat(p2.nextCursor()).isNotNull();

    FilteredCostExplorerReport p3 = rawPage(p2.nextCursor());
    assertThat(p3.messages()).hasSize(1);
    assertThat(p3.nextCursor()).isNull();

    // Every row seen exactly once across the three pages.
    Set<UUID> ids = new HashSet<>();
    Stream.of(p1, p2, p3).flatMap(r -> r.messages().stream())
        .forEach(m -> assertThat(ids.add(m.id())).as("no duplicate across pages").isTrue());
    assertThat(ids).hasSize(5);

    // Descending created_at within and across pages.
    assertThat(p1.messages().get(0).createdAt()).isAfter(p1.messages().get(1).createdAt());
    assertThat(p1.messages().get(1).createdAt()).isAfter(p2.messages().get(0).createdAt());
    assertThat(p2.messages().get(1).createdAt()).isAfter(p3.messages().get(0).createdAt());
  }

  @Test
  public void keysetTiebreakerHandlesEqualTimestampsWithoutSkipOrDuplicate() {
    for (int i = 0; i < 4; i++) {
      jdbcTemplate.update("INSERT INTO llm_call_logs (id, model, source, prompt_tokens, "
          + "completion_tokens, created_at) VALUES (?, ?, 'chat', 10, 10, "
          + "TIMESTAMPTZ '2026-01-01 00:00:00+00')", UUID.randomUUID(), MODEL);
    }

    FilteredCostExplorerReport p1 = rawPage(null);
    FilteredCostExplorerReport p2 = rawPage(p1.nextCursor());
    assertThat(p1.messages()).hasSize(2);
    assertThat(p2.messages()).hasSize(2);
    assertThat(p2.nextCursor()).isNull();

    Set<UUID> ids = new HashSet<>();
    Stream.of(p1, p2).flatMap(r -> r.messages().stream()).forEach(m -> ids.add(m.id()));
    assertThat(ids).as("id tiebreaker: all 4 equal-timestamp rows covered, none skipped/duplicated")
        .hasSize(4);
  }

  @Test
  public void malformedOrBlankCursorFallsBackToFirstPage() {
    for (int i = 0; i < 3; i++) {
      seedAtNowMinusMinutes(i);
    }
    assertThat(rawPage("not-a-valid-cursor").messages()).hasSize(2);
    assertThat(rawPage("").messages()).hasSize(2);
    assertThat(rawPage(null).messages()).hasSize(2);
  }
}
