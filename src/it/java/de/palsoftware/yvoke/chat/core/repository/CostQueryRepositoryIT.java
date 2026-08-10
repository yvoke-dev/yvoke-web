package de.palsoftware.yvoke.chat.core.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import de.palsoftware.yvoke.shared.user.repository.UserRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Repository-level characterization for {@link CostQueryRepository}. Reuses the exact context of
 * {@code CostCalculationServiceIT}/{@code CostCalculationTopNIT} (same two flyway properties) so no
 * new Spring context is minted. Asserts on the raw {@code List<Map<String,Object>>} rows the repo
 * returns — pricing (token→cost) is applied in the service and is NOT this class's concern. Pins the
 * explorer item_type CASE, keyset pagination, GROUP BY variants, date filter, and param-binding.
 */
@SpringBootTest(
    properties = {
      "spring.flyway.enabled=true",
      "spring.flyway.locations=filesystem:docker/db/migration"
    })
public class CostQueryRepositoryIT {

  private static final String MODEL = "CQR-IT-MODEL";
  private static final String APOS_MODEL = "CQR-IT-O'Brien"; // literal apostrophe → must be bound
  private static final String TIE_MODEL = "CQR-IT-TIE";
  private static final String PROFILE = "CQR-IT-PROFILE";

  @Autowired private CostQueryRepository repo;
  @Autowired private ConversationRepository conversationRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID userId;
  private UUID convId;
  private UUID runId;
  private final UUID call1 = UUID.randomUUID(); // MESSAGE  @10:00
  private final UUID call2 = UUID.randomUUID(); // MESSAGE  @10:01
  private final UUID call3 = UUID.randomUUID(); // MAS_STEP @10:02
  private static final Instant TS1 = Instant.parse("2026-06-01T10:00:00Z");
  private static final Instant TS2 = Instant.parse("2026-06-01T10:01:00Z");
  private static final Instant TS3 = Instant.parse("2026-06-01T10:02:00Z");

  @BeforeEach
  void setUp() {
    cleanup();
    String oid = "oid-cqr-" + UUID.randomUUID();
    userRepository.upsert(oid, "cqr-it-" + UUID.randomUUID() + "@example.com", "CQR IT User");
    userId = userRepository.findByEntraOid(oid).orElseThrow().id();

    convId = UUID.randomUUID();
    conversationRepository.create(convId, userId, "CQR-IT-CONV", Map.of(), "web");

    runId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO agent_runs (id, conversation_id, profile_name, status) VALUES (?, ?, ?, ?)",
        runId, convId, PROFILE, "completed");

    // Two MESSAGE calls (agent_run_id NULL) + one MAS_STEP call (agent_run_id = runId).
    seedCall(call1, convId, userId, null, "chat", "assistant", MODEL, 1000, 500, 200, 0, TS1);
    seedCall(call2, convId, userId, null, "chat", "assistant", MODEL, 100, 50, 0, 0, TS2);
    seedCall(call3, convId, userId, runId, "orchestration", "specialist", MODEL, 300, 0, 0, 0, TS3);
  }

  @AfterEach
  void tearDown() {
    cleanup();
  }

  /** Raw INSERT with an explicit created_at so ordering/date-filter assertions are deterministic. */
  private void seedCall(
      UUID id,
      UUID conv,
      UUID user,
      UUID agentRun,
      String source,
      String role,
      String model,
      int p,
      int c,
      int ca,
      int t,
      Instant createdAt) {
    jdbcTemplate.update(
        "INSERT INTO llm_call_logs (id, conversation_id, agent_run_id, user_id, source, role, "
            + "model, prompt_tokens, completion_tokens, cached_tokens, thought_tokens, "
            + "total_tokens, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
            + "CAST(? AS timestamptz))",
        id, conv, agentRun, user, source, role, model, p, c, ca, t, p + c + ca + t,
        Timestamp.from(createdAt));
  }

  private void cleanup() {
    jdbcTemplate.update("DELETE FROM llm_call_logs WHERE model LIKE 'CQR-IT%'");
    jdbcTemplate.update("DELETE FROM agent_runs WHERE profile_name = ?", PROFILE);
    jdbcTemplate.update("DELETE FROM conversations WHERE title LIKE 'CQR-IT%'");
    jdbcTemplate.update("DELETE FROM users WHERE display_name = 'CQR IT User'");
  }

  // ---- coercion helpers: SUM(int)/COUNT(*) → Long, COALESCE(int,0) → Integer ----
  private static long num(Object v) {
    return v == null ? 0L : ((Number) v).longValue();
  }

  private static UUID id(Map<String, Object> r, String k) {
    return (UUID) r.get(k);
  }

  private static String str(Map<String, Object> r, String k) {
    return (String) r.get(k);
  }

  private static Map<String, Object> byModel(List<Map<String, Object>> rows, String model) {
    return rows.stream().filter(r -> model.equals(str(r, "model"))).findFirst().orElseThrow();
  }

  private static Map<String, Object> byId(List<Map<String, Object>> rows, UUID itemId) {
    return rows.stream().filter(r -> itemId.equals(r.get("item_id"))).findFirst().orElseThrow();
  }

  @Test
  void rawPerCallRows_carryItemTypeBranchAndDescendingKeysetOrder() {
    List<Map<String, Object>> rows =
        repo.explorerCallRowsPaged(
            null, null, List.of(MODEL), List.of(), List.of(), List.of(), null, null, 100);

    assertThat(rows).extracting(r -> id(r, "item_id")).containsExactly(call3, call2, call1);
    assertThat(str(byId(rows, call3), "item_type")).isEqualTo("MAS_STEP");
    assertThat(str(byId(rows, call1), "item_type")).isEqualTo("MESSAGE");
    assertThat(str(byId(rows, call2), "item_type")).isEqualTo("MESSAGE");
    assertThat(num(byId(rows, call1).get("prompt_tokens"))).isEqualTo(1000L);
    assertThat(str(byId(rows, call3), "mas_profile")).isEqualTo(PROFILE);
  }

  @Test
  void rawKeysetCursor_returnsRowsStrictlyOlderThanCursor() {
    List<Map<String, Object>> p1 =
        repo.explorerCallRowsPaged(
            null, null, List.of(MODEL), List.of(), List.of(), List.of(), null, null, 2);
    assertThat(p1).extracting(r -> id(r, "item_id")).containsExactly(call3, call2);

    List<Map<String, Object>> p2 =
        repo.explorerCallRowsPaged(
            null, null, List.of(MODEL), List.of(), List.of(), List.of(), TS2, call2, 2);
    assertThat(p2).extracting(r -> id(r, "item_id")).containsExactly(call1);
  }

  @Test
  void rawKeysetTiebreaker_coversEqualTimestampRowsWithoutSkipOrDuplicate() {
    Instant tie = Instant.parse("2026-06-01T09:00:00Z");
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    seedCall(a, convId, userId, null, "chat", "assistant", TIE_MODEL, 1, 1, 0, 0, tie);
    seedCall(b, convId, userId, null, "chat", "assistant", TIE_MODEL, 1, 1, 0, 0, tie);

    List<Map<String, Object>> p1 =
        repo.explorerCallRowsPaged(
            null, null, List.of(TIE_MODEL), List.of(), List.of(), List.of(), null, null, 1);
    assertThat(p1).hasSize(1);
    Instant cTs = ((Timestamp) p1.get(0).get("created_at")).toInstant();
    UUID cId = id(p1.get(0), "item_id");

    List<Map<String, Object>> p2 =
        repo.explorerCallRowsPaged(
            null, null, List.of(TIE_MODEL), List.of(), List.of(), List.of(), cTs, cId, 1);
    assertThat(p2).hasSize(1);

    Set<UUID> seen = new HashSet<>();
    p1.forEach(r -> seen.add(id(r, "item_id")));
    p2.forEach(r -> seen.add(id(r, "item_id")));
    assertThat(seen).containsExactlyInAnyOrder(a, b);
  }

  @Test
  void messageViewPerCallRows_areNewestFirstAndCappedByRowCap() {
    List<Map<String, Object>> all =
        repo.explorerCallRows(null, null, List.of(MODEL), List.of(), List.of(), List.of(), 100);
    assertThat(all).extracting(r -> id(r, "item_id")).containsExactly(call3, call2, call1);

    List<Map<String, Object>> capped =
        repo.explorerCallRows(null, null, List.of(MODEL), List.of(), List.of(), List.of(), 2);
    assertThat(capped).hasSize(2);
    assertThat(capped).extracting(r -> id(r, "item_id")).containsExactly(call3, call2);
  }

  @Test
  void conversationView_groupsByModelAndSourceTypeWithSummedTokensAndCounts() {
    List<Map<String, Object>> rows =
        repo.explorerConversationRows(
            null, null, List.of(MODEL), List.of(), List.of(), List.of(), 100);

    Map<String, Object> msg =
        rows.stream()
            .filter(r -> "MESSAGE".equals(str(r, "source_type")))
            .findFirst()
            .orElseThrow();
    Map<String, Object> step =
        rows.stream()
            .filter(r -> "MAS_STEP".equals(str(r, "source_type")))
            .findFirst()
            .orElseThrow();

    assertThat(id(msg, "conv_id")).isEqualTo(convId);
    assertThat(num(msg.get("p_tokens"))).isEqualTo(1100L); // 1000 + 100
    assertThat(num(msg.get("c_tokens"))).isEqualTo(550L); // 500 + 50
    assertThat(num(msg.get("ca_tokens"))).isEqualTo(200L);
    assertThat(num(msg.get("msg_count"))).isEqualTo(2L);
    assertThat(num(msg.get("step_count"))).isEqualTo(0L);

    assertThat(num(step.get("p_tokens"))).isEqualTo(300L);
    assertThat(num(step.get("msg_count"))).isEqualTo(0L);
    assertThat(num(step.get("step_count"))).isEqualTo(1L);
    assertThat(str(step, "mas_profile")).isEqualTo(PROFILE);
  }

  @Test
  void conversationView_isCappedByRowCap() {
    List<Map<String, Object>> capped =
        repo.explorerConversationRows(
            null, null, List.of(MODEL), List.of(), List.of(), List.of(), 1);
    assertThat(capped).hasSize(1); // LIMIT :explorerRowCap
  }

  @Test
  void calculateTokenRows_groupByModel_perScope() {
    Map<String, Object> conv = byModel(repo.conversationModelTokenRows(convId, null, null), MODEL);
    assertThat(num(conv.get("p_tokens"))).isEqualTo(1400L); // 1000+100+300
    assertThat(num(conv.get("c_tokens"))).isEqualTo(550L);
    assertThat(num(conv.get("ca_tokens"))).isEqualTo(200L);

    assertThat(num(byModel(repo.userModelTokenRows(userId, null, null), MODEL).get("p_tokens")))
        .isEqualTo(1400L);

    assertThat(num(byModel(repo.globalModelTokenRows(null, null), MODEL).get("p_tokens")))
        .isEqualTo(1400L);

    assertThat(num(byModel(repo.agentRunModelTokenRows(runId), MODEL).get("p_tokens")))
        .isEqualTo(300L);

    assertThat(
            num(byModel(repo.masProfileModelTokenRows(PROFILE, null, null), MODEL).get("p_tokens")))
        .isEqualTo(300L);
  }

  @Test
  void dateRangeFilter_isHalfOpenAndBindsBounds() {
    LocalDate day = LocalDate.of(2026, 6, 1); // matches TS1..TS3

    assertThat(num(byModel(repo.conversationModelTokenRows(convId, day, day), MODEL).get("p_tokens")))
        .isEqualTo(1400L);

    assertThat(repo.conversationModelTokenRows(convId, day.plusDays(1), null)).isEmpty();
    assertThat(repo.conversationModelTokenRows(convId, null, day.minusDays(1))).isEmpty();
  }

  @Test
  void messageCostRows_returnConversationCallsOldestFirst() {
    List<Map<String, Object>> rows = repo.messageCostRows(convId);
    assertThat(rows).extracting(r -> id(r, "id")).containsExactly(call1, call2, call3); // ASC
    assertThat(str(rows.get(2), "role")).isEqualTo("specialist");
    assertThat(str(rows.get(0), "effective_model")).isEqualTo(MODEL);
  }

  @Test
  void filterValues_areBoundNeverInterpolated() {
    // 1) apostrophe in a real value round-trips because it's a bound param, not spliced SQL
    UUID aposCall = UUID.randomUUID();
    seedCall(aposCall, convId, userId, null, "chat", "assistant", APOS_MODEL, 7, 0, 0, 0, TS1);
    List<Map<String, Object>> matched =
        repo.explorerCallRows(
            null, null, List.of(APOS_MODEL), List.of(), List.of(), List.of(), 100);
    assertThat(matched).extracting(r -> id(r, "item_id")).containsExactly(aposCall);

    // 2) an injection payload as a model filter matches nothing and does not error
    assertThatCode(
            () -> {
              List<Map<String, Object>> inj =
                  repo.explorerCallRows(
                      null,
                      null,
                      List.of("'; DROP TABLE llm_call_logs; --"),
                      List.of(),
                      List.of(),
                      List.of(),
                      100);
              assertThat(inj).isEmpty();
            })
        .doesNotThrowAnyException();

    // 3) classic OR-tautology as a "source" filter also matches nothing
    List<Map<String, Object>> tauto =
        repo.explorerCallRows(
            null, null, List.of(), List.of(), List.of("x' OR '1'='1"), List.of(), 100);
    assertThat(tauto).isEmpty();

    // 4) table survived → the payload never executed
    assertThat(
            num(byModel(repo.conversationModelTokenRows(convId, null, null), MODEL).get("p_tokens")))
        .isEqualTo(1400L);
  }

  /**
   * Deleting a conversation must destroy its content but PRESERVE its cost ledger.
   *
   * <p>
   * The blast radius is defined entirely by foreign keys, which is exactly why nothing else would
   * catch a change to them: {@code llm_call_logs.conversation_id} and {@code .agent_run_id} are ON
   * DELETE SET NULL, while {@code agent_runs.conversation_id} and {@code messages.conversation_id}
   * are ON DELETE CASCADE. So a user deleting a conversation removes the questions, answers and
   * multi-agent trace, and the spend stays in the totals — still attributed to the person who spent
   * it, no longer traceable to the thread.
   *
   * <p>
   * Flipping the llm_call_logs FK to CASCADE is a one-word migration change that looks like tidying
   * up an orphan. It would silently erase spend history every time a user deletes a conversation,
   * and the cost dashboard would simply show a smaller number with nothing to indicate why.
   */
  @Test
  void deletingAConversationKeepsItsCostLedgerAndDropsOnlyTheMasTrace() {
    conversationRepository.delete(convId);

    Integer survivingCalls =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM llm_call_logs WHERE id IN (?, ?, ?)",
            Integer.class, call1, call2, call3);
    assertThat(survivingCalls).as("spend history must outlive the conversation").isEqualTo(3);

    Integer stillLinked =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM llm_call_logs WHERE id IN (?, ?, ?) "
                + "AND (conversation_id IS NOT NULL OR agent_run_id IS NOT NULL)",
            Integer.class, call1, call2, call3);
    assertThat(stillLinked).as("both conversation links must be nulled, not left dangling").isZero();

    Integer userStillAttributed =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM llm_call_logs WHERE id IN (?, ?, ?) AND user_id = ?",
            Integer.class, call1, call2, call3, userId);
    assertThat(userStillAttributed).as("spend stays attributed to who spent it").isEqualTo(3);

    Integer runs =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM agent_runs WHERE id = ?", Integer.class, runId);
    assertThat(runs).as("the multi-agent trace is content and must cascade away").isZero();
  }
}
