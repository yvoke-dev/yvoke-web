package de.palsoftware.yvoke.shared.config.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Round-trip IT for {@link AppConfigRepository} (get/set/upsert of a config key). Reuses the exact
 * {@code @SpringBootTest} config already cached by the other {@code *RepositoryIT}s (two flyway
 * properties, no mock beans) so it mints no new Spring context.
 */
@SpringBootTest(
    properties = {
      "spring.flyway.enabled=true",
      "spring.flyway.locations=filesystem:docker/db/migration"
    })
public class AppConfigRepositoryIT {

  private static final String PREFIX = "it.appconfig.";

  @Autowired private AppConfigRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  public void setUp() {
    cleanup();
  }

  @AfterEach
  public void tearDown() {
    cleanup();
  }

  private void cleanup() {
    jdbcTemplate.update("DELETE FROM app_config WHERE key LIKE ?", PREFIX + "%");
  }

  @Test
  public void getMissingKeyReturnsDefault() {
    assertThat(repository.get(PREFIX + "absent", "fallback")).isEqualTo("fallback");
  }

  @Test
  public void getNullKeyReturnsDefault() {
    assertThat(repository.get(null, "fallback")).isEqualTo("fallback");
  }

  @Test
  public void setThenGetRoundTripsInsertedValue() {
    String key = PREFIX + "theme";

    repository.set(key, "dark");

    assertThat(repository.get(key, "IGNORED-DEFAULT")).isEqualTo("dark");
  }

  @Test
  public void setUpsertsOnConflictAndBumpsUpdatedAt() {
    String key = PREFIX + "mode";

    repository.set(key, "v1");
    Instant firstTs =
        jdbcTemplate.queryForObject(
            "SELECT updated_at FROM app_config WHERE key = ?", Instant.class, key);

    repository.set(key, "v2");

    assertThat(repository.get(key, "IGNORED-DEFAULT")).isEqualTo("v2");
    Long rows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app_config WHERE key = ?", Long.class, key);
    assertThat(rows).isEqualTo(1L); // upsert, not a second insert
    Instant secondTs =
        jdbcTemplate.queryForObject(
            "SELECT updated_at FROM app_config WHERE key = ?", Instant.class, key);
    assertThat(secondTs).isAfter(firstTs); // distinct transactions => strictly later timestamp
  }

  @Test
  public void setWithNullKeyOrValueIsNoOp() {
    repository.set(null, "x");
    repository.set(PREFIX + "nullval", null);

    assertThat(repository.get(PREFIX + "nullval", "default")).isEqualTo("default");
    Long rows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app_config WHERE key LIKE ?", Long.class, PREFIX + "%");
    assertThat(rows).isEqualTo(0L);
  }
}
