package de.palsoftware.yvoke;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * {@code playbooks} and {@code system_prompts} hold DB CONTENT, not schema. They are authored,
 * versioned and applied by the external export/import toolkit, which is what lets a prompt be
 * edited, A/B-tested and rolled back without a release — and migrations are the one thing that
 * cannot participate in that: Flyway runs a script exactly once per database and records its
 * checksum, so a seeded row can never be re-applied after an operator edits it, and the script
 * itself can never be corrected without breaking validation on every environment that already ran
 * it.
 *
 * <p>
 * The damage from crossing that line is asymmetric and silent. A seed with a fixed id or name
 * either collides with the toolkit-managed row (the migration fails at startup, so the app will not
 * boot until someone deletes production data by hand) or, with {@code ON CONFLICT DO NOTHING},
 * succeeds and does nothing — which is the worse outcome, because a fresh environment then quietly
 * runs on a stale prompt that nobody remembers exists, and the only symptom is answers that differ
 * between environments for no visible reason. Neither shape is a schema error, so Flyway, the
 * compiler and every integration test report success.
 *
 * <p>
 * There is no other guard. {@code SchemaPresenceIT} asserts that objects EXIST, never that a
 * migration refrained from writing rows, and the ITs run against a database these migrations built,
 * so a seeded row simply becomes part of what every test considers normal. Comments are stripped
 * before matching so prose about seeding (including this rule being explained in a migration) does
 * not trip it.
 */
public class MigrationSeedPolicyTest {

    private static final Path MIGRATION_DIR = Paths.get("docker/db/migration");

    /**
     * Row-writing statements aimed at either content table: {@code INSERT INTO} and {@code COPY},
     * in any casing, schema-qualified or quoted or neither.
     */
    private static final Pattern SEEDS_CONTENT = Pattern.compile(
        "(?:insert\\s+into|copy)\\s+(?:\"?public\"?\\s*\\.\\s*)?\"?(playbooks|system_prompts)\"?\\b",
        Pattern.CASE_INSENSITIVE);

    @Test
    public void noMigrationSeedsPlaybooksOrSystemPrompts() throws IOException {
        assertThat(Files.isDirectory(MIGRATION_DIR))
            .as("migrations must be readable from the module root at %s — a walk that finds "
                + "nothing would make this test vacuously pass", MIGRATION_DIR.toAbsolutePath())
            .isTrue();

        List<Path> migrations;
        try (Stream<Path> files = Files.list(MIGRATION_DIR)) {
            migrations =
                files.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList();
        }
        assertThat(migrations).as("at least the baseline schema migration must be found")
            .isNotEmpty();

        List<String> offenders = new ArrayList<>();
        for (Path migration : migrations) {
            String sql = stripComments(Files.readString(migration, StandardCharsets.UTF_8));
            Matcher matcher = SEEDS_CONTENT.matcher(sql);
            while (matcher.find()) {
                offenders.add(migration.getFileName() + " writes rows into " + matcher.group(1));
            }
        }

        assertThat(offenders)
            .as("playbooks and system_prompts are content owned by the export/import toolkit: a "
                + "migration-seeded row can never be re-applied after an edit, and an "
                + "ON CONFLICT DO NOTHING seed leaves a fresh environment running a stale prompt "
                + "with no error anywhere")
            .isEmpty();
    }

    /** Comments are prose, including prose that quotes the very statement this rule forbids. */
    private static String stripComments(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)--[^\\n]*", " ");
    }
}
