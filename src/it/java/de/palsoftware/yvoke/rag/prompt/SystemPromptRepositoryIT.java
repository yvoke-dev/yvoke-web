package de.palsoftware.yvoke.rag.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code system_prompts.type} has TWO producers that disagree on case, and the read side has to
 * tolerate both.
 *
 * <p>The corpus tooling in {@code yvoke-exports} writes the type upper-cased — its own code carries
 * the comment "the stored values are KG/SUMMARIZE" — and that importer is what actually populated
 * every environment. {@code SystemPromptType.dbValue()} returned {@code name().toLowerCase()}, and
 * {@code findByType} compared it with a case-SENSITIVE {@code =}, so every imported prompt was
 * invisible to Java.
 *
 * <p>Nothing failed. The admin ingest page rendered an empty prompt dropdown and, from that
 * emptiness, concluded "No system prompts of type SUMMARIZE are configured. Please create one in
 * the System Prompts tab first." — an error message that is confidently wrong, pointing the
 * operator at a tab where the prompt is plainly listed. It also silently disabled prompt selection
 * for KG.
 *
 * <p>This has to be an integration test: a mocked {@code JdbcClient} returns whatever it is stubbed
 * with and cannot express PostgreSQL's case sensitivity, so a unit test would pass against the bug.
 * The sibling {@link SystemPromptTypeTest} pins the lenient parsing; this pins the query.
 */
@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.locations=filesystem:docker/db/migration"
        })
public class SystemPromptRepositoryIT {

    private static final String UPPER = "IT-UPPER-SUMMARIZE";
    private static final String LOWER = "IT-LOWER-SUMMARIZE";
    private static final String PADDED = "IT-PADDED-KG";
    private static final String SAVED = "IT-SAVED-SUMMARIZE";

    @Autowired private SystemPromptRepository repository;

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
        jdbcTemplate.update(
                "DELETE FROM system_prompts WHERE name IN (?, ?, ?, ?)",
                UPPER,
                LOWER,
                PADDED,
                SAVED);
    }

    /** The shape the corpus importer actually writes, and the one that was invisible. */
    @Test
    public void aPromptImportedWithAnUppercaseTypeIsFound() {
        jdbcTemplate.update(
                "INSERT INTO system_prompts (name, type, system_prompt, description) "
                        + "VALUES (?, 'SUMMARIZE', 'body', 'imported by the corpus tooling')",
                UPPER);

        List<SystemPrompt> found = repository.findByType(SystemPromptType.SUMMARIZE);

        assertThat(found)
                .extracting(SystemPrompt::name)
                .as(
                        "the exports importer upper-cases the type; a case-sensitive '=' hides every "
                                + "prompt it wrote, and the admin page then claims none are configured")
                .contains(UPPER);
    }

    /** Rows written by an older Java build are lower-cased; both spellings must resolve. */
    @Test
    public void aPromptStoredWithALowercaseTypeIsAlsoFound() {
        jdbcTemplate.update(
                "INSERT INTO system_prompts (name, type, system_prompt, description) "
                        + "VALUES (?, 'summarize', 'body', 'written by an older build')",
                LOWER);

        assertThat(repository.findByType(SystemPromptType.SUMMARIZE))
                .extracting(SystemPrompt::name)
                .contains(LOWER);
    }

    @Test
    public void aPromptStoredWithSurroundingWhitespaceIsFound() {
        jdbcTemplate.update(
                "INSERT INTO system_prompts (name, type, system_prompt, description) "
                        + "VALUES (?, ' kg ', 'body', 'padded type')",
                PADDED);

        assertThat(repository.findByType(SystemPromptType.KG))
                .extracting(SystemPrompt::name)
                .contains(PADDED);
    }

    /** Whatever case {@code upsert} chooses, its own read path must find the row back. */
    @Test
    public void aPromptSavedThroughTheRepositoryIsFoundByItsType() {
        repository.upsert(SAVED, SystemPromptType.SUMMARIZE, "body", "round trip", "OIM");

        assertThat(repository.findByType(SystemPromptType.SUMMARIZE))
                .extracting(SystemPrompt::name)
                .contains(SAVED);
    }

    /** Case tolerance must not blur the types into each other. */
    @Test
    public void theTypeFilterStillSeparatesKindsOfPrompt() {
        jdbcTemplate.update(
                "INSERT INTO system_prompts (name, type, system_prompt, description) "
                        + "VALUES (?, 'SUMMARIZE', 'body', 'x')",
                UPPER);

        assertThat(repository.findByType(SystemPromptType.KG))
                .extracting(SystemPrompt::name)
                .doesNotContain(UPPER);
    }

    @Test
    public void retiredChatRowsRemainIgnoredByAllQueriesDuringNonDestructiveRollout() {
        // V17 intentionally stays non-destructive for rolling-deploy/rollback safety from 1.3.0,
        // so legacy CHAT rows can still coexist in DB. Every read path — findAll, findByName, and
        // findByType — must ignore them instead of throwing IllegalArgumentException in
        // SystemPromptType.fromString.
        jdbcTemplate.update(
                "INSERT INTO system_prompts (name, type, system_prompt, description) "
                        + "VALUES (?, ' kg ', 'body', 'padded type')",
                PADDED);
        jdbcTemplate.update(
                "INSERT INTO system_prompts (name, type, system_prompt, description) "
                        + "VALUES ('IT-LEGACY-CHAT', 'CHAT', 'body', 'x') ON CONFLICT (name) DO NOTHING");
        try {
            assertThat(repository.findByType(SystemPromptType.SUMMARIZE))
                    .extracting(SystemPrompt::name)
                    .doesNotContain("IT-LEGACY-CHAT");
            assertThat(repository.findByType(SystemPromptType.KG))
                    .extracting(SystemPrompt::name)
                    .contains(PADDED)
                    .doesNotContain("IT-LEGACY-CHAT");
            assertThat(repository.findAll())
                    .extracting(SystemPrompt::name)
                    .contains(PADDED)
                    .doesNotContain("IT-LEGACY-CHAT");
            assertThat(repository.findByName("IT-LEGACY-CHAT")).isEmpty();
            assertThat(repository.findByName(PADDED))
                    .get()
                    .extracting(SystemPrompt::type)
                    .isEqualTo(SystemPromptType.KG);
        } finally {
            jdbcTemplate.update("DELETE FROM system_prompts WHERE name = 'IT-LEGACY-CHAT'");
        }
    }

    @Test
    public void upsertRefusesToOverwriteRetiredChatRow() {
        jdbcTemplate.update(
                "INSERT INTO system_prompts (name, type, system_prompt, description, area) "
                        + "VALUES ('IT-LEGACY-CHAT', 'CHAT', 'legacy chat body', 'retired', 'OIM') "
                        + "ON CONFLICT (name) DO NOTHING");
        try {
            assertThatThrownBy(() ->
                            repository.upsert(
                                    "IT-LEGACY-CHAT",
                                    SystemPromptType.KG,
                                    "hijacked body",
                                    "hijacked",
                                    "OIM"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("retired");

            var row =
                    jdbcTemplate.queryForMap(
                            "SELECT type, system_prompt, area FROM system_prompts WHERE name = 'IT-LEGACY-CHAT'");
            assertThat(row.get("type")).isEqualTo("CHAT");
            assertThat(row.get("system_prompt")).isEqualTo("legacy chat body");
            assertThat(row.get("area")).isEqualTo("OIM");
        } finally {
            jdbcTemplate.update("DELETE FROM system_prompts WHERE name = 'IT-LEGACY-CHAT'");
        }
    }
}
