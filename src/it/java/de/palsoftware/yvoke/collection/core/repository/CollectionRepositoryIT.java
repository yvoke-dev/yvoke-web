package de.palsoftware.yvoke.collection.core.repository;

import static org.assertj.core.api.Assertions.assertThat;

import de.palsoftware.yvoke.collection.core.model.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Pins {@link CollectionRepository}'s SQL and, in particular, its {@code TEXT[]} tags row-mapper
 * ({@code JdbcMappers.arrayToStringList}) against a real Postgres (MNT-22). The mapper is exercised
 * on every read path ({@code findAll}/{@code findByName}/{@code findById}); before this IT it had no
 * direct coverage, so a regression in array unmarshalling (nulls, empty arrays, ordering) would have
 * gone unnoticed.
 */
@SpringBootTest(properties = {"spring.flyway.enabled=true",
    "spring.flyway.locations=filesystem:docker/db/migration"})
public class CollectionRepositoryIT {

    private static final String PREFIX = "OIM-COLLREPO-";

    @Autowired
    private CollectionRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    public void setUp() {
        cleanup();
    }

    @AfterEach
    public void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbcTemplate.update("DELETE FROM collections WHERE name LIKE ?", PREFIX + "%");
    }

    /** Inserts a collection with a literal {@code TEXT[]} so the tags row-mapper has real elements. */
    private UUID insertWithTags(String name, String tagsLiteral) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO collections (id, name, description, created_at, tags) "
            + "VALUES (?, ?, ?, CURRENT_TIMESTAMP, ?::TEXT[])", id, name, "desc for " + name,
            tagsLiteral);
        return id;
    }

    @Test
    public void findByIdMapsMultiElementTagArrayInOrder() {
        UUID id = insertWithTags(PREFIX + "TAGGED", "{alpha,beta,gamma}");

        Collection found = repository.findById(id).orElseThrow();

        assertThat(found.id()).isEqualTo(id);
        assertThat(found.name()).isEqualTo(PREFIX + "TAGGED");
        assertThat(found.tags()).containsExactly("alpha", "beta", "gamma");
        assertThat(found.createdAt()).isNotNull();
    }

    @Test
    public void emptyTagArrayMapsToEmptyList() {
        // create() seeds tags as '{}'::TEXT[]; the mapper must yield an empty list, not a
        // singleton list containing "" and not null.
        Collection created = repository.create(PREFIX + "EMPTY", "  trimmed desc  ");

        assertThat(created.tags()).isEmpty();
        assertThat(created.description()).isEqualTo("trimmed desc");

        Collection reread = repository.findByName(PREFIX + "EMPTY").orElseThrow();
        assertThat(reread.tags()).isEmpty();
    }

    @Test
    public void findByNameIsCaseInsensitive() {
        UUID id = insertWithTags(PREFIX + "CASE", "{one,two}");

        assertThat(repository.findByName(PREFIX + "CASE").map(Collection::id)).contains(id);
        assertThat(repository.findByName((PREFIX + "CASE").toLowerCase()).map(Collection::id))
            .contains(id);
        assertThat(repository.findByName(PREFIX + "MISSING")).isEmpty();
    }

    @Test
    public void findAllReturnsTaggedRowsOrderedByNameAsc() {
        insertWithTags(PREFIX + "ZZZ", "{z1}");
        insertWithTags(PREFIX + "AAA", "{a1,a2}");

        List<Collection> ours = repository.findAll().stream()
            .filter(c -> c.name().startsWith(PREFIX))
            .toList();

        assertThat(ours).extracting(Collection::name)
            .containsExactly(PREFIX + "AAA", PREFIX + "ZZZ");
        assertThat(ours.get(0).tags()).containsExactly("a1", "a2");
        assertThat(ours.get(1).tags()).containsExactly("z1");
    }

    /**
     * The corpus tag vocabulary is DERIVED from {@code collections.tags} — there is no registry.
     *
     * <p>
     * A {@code tags} table existed until V6, and its only writer was
     * {@code TagRepository.getOrCreateTag}: a tag was recorded exactly when it arrived through an
     * admin form or the ingest enqueue. Every other writer sets the {@code TEXT[]} column directly
     * — above all the corpus import ({@code yvoke-exports/lib/objects.py}:
     * {@code INSERT INTO collections … tags = EXCLUDED.tags}) — so an imported collection's tags
     * never reached the registry and the admin dropdowns could not offer them. Live proof: the
     * registry held 2 names while {@code 10.0} and {@code 9.3.1} were on 27 collections and 22k
     * documents. {@code insertWithTags} below is exactly that import write shape.
     */
    @Test
    public void findAllTagNamesSeesTagsWrittenStraightIntoTheArray() {
        insertWithTags(PREFIX + "IMPORTED", "{10.0,9.3.1}");
        insertWithTags(PREFIX + "SHARES-A-TAG", "{9.3.1,content}");
        insertWithTags(PREFIX + "UNTAGGED", "{}");

        List<String> ours = repository.findAllTagNames().stream()
            .filter(t -> List.of("10.0", "9.3.1", "content").contains(t))
            .toList();

        // De-duplicated across collections, sorted case-insensitively, no empty-array artefact.
        assertThat(ours).containsExactly("10.0", "9.3.1", "content");
        assertThat(repository.findAllTagNames()).doesNotContain("");
    }

    @Test
    public void deleteRemovesByCaseInsensitiveName() {
        insertWithTags(PREFIX + "DELME", "{x}");
        assertThat(repository.findByName(PREFIX + "DELME")).isPresent();

        repository.delete((PREFIX + "DELME").toLowerCase());

        assertThat(repository.findByName(PREFIX + "DELME")).isEmpty();
    }
}
