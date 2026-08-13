package de.palsoftware.yvoke;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The custom Postgres image (pgvector + ParadeDB pg_search) is built from one Dockerfile and
 * referenced by a handful of files that each spell its version out by hand — today the compose
 * file, {@code ci.yml}, {@code release.yml}, the CNPG cluster manifest, the Testcontainers
 * initializer for the integration suite, and this repository's own steering doc.
 *
 * <p>
 * It is the third release image and the odd one out: {@code yvoke-app} and
 * {@code yvoke-db-migration} are versioned by the git tag and published by
 * {@code docker-build-publish.yml}, while this one is versioned by what it CONTAINS — the Postgres
 * major and the pg_search release — and so moves only when one of those is bumped. That is why it
 * has its own workflow rather than riding the release.
 *
 * <p>
 * Two of those versions live in {@code docker/postgres/Dockerfile} and nowhere else authoritative:
 * the base image tag supplies the Postgres major, and {@code ARG PG_SEARCH_VERSION} selects the
 * .deb that gets installed. Every other file merely restates them inside an image tag. Nothing tied
 * the copies together, and the drift is silent in the direction that matters most: bump
 * {@code PG_SEARCH_VERSION} without touching {@code cluster.yaml} and the workflow publishes a new
 * image under the OLD tag — refused if the tag exists, and if it does not, the cluster keeps
 * pulling a tag whose name no longer describes its contents.
 *
 * <p>
 * The local and registry references deliberately use different repositories — {@code compose}/CI
 * build {@code yvoke/pgvector-pg_search} natively and never pull it, the cluster pulls
 * {@code edipal/yvoke-postgres-pg_search} — and they even spell the tag differently
 * ({@code pg16-0.24.0} vs {@code 16-0.24.0}). This test does not force them to converge; it forces
 * every one of them to name the two versions the Dockerfile actually builds.
 */
public class PostgresImageVersionTest {

    private static final Path DOCKERFILE = Path.of("docker/postgres/Dockerfile");
    private static final Path CLUSTER = Path.of("k8s/app/database/cluster.yaml");
    private static final Path PUBLISH_WORKFLOW =
        Path.of(".github/workflows/postgres-image-publish.yml");
    private static final Path IT_INITIALIZER =
        Path.of("src/it/java/de/palsoftware/yvoke/PostgresTestContainerInitializer.java");

    /**
     * Any image reference whose repository ends in {@code pg_search}, with its tag.
     *
     * <p>
     * Deliberately matched on the repository suffix rather than a list of full names: a sixth
     * reference added under yet another repository name is exactly the drift this test exists to
     * catch, and a fixed list would not see it.
     */
    private static final Pattern IMAGE_REF = Pattern.compile("[\\w./-]*pg_search:([\\w][\\w.-]*)");

    /** {@code pg16-0.24.0} and {@code 16-0.24.0} both yield (16, 0.24.0). */
    private static final Pattern TAG_SHAPE =
        Pattern.compile("^(?:pg)?(\\d+)-(\\d+\\.\\d+\\.\\d+)$");

    /**
     * Drops whole-line {@code #} comments.
     *
     * <p>
     * Necessary rather than tidy, for the same reason {@code ReleaseImageTagPolicyTest} needs it:
     * the workflow explains every rule it obeys in a comment next to the code obeying it, and those
     * comments name the files being read. A scan over raw text is then satisfied by the prose and
     * would stay green with the actual step deleted.
     */
    private static String stripComments(String text) {
        return text.lines().filter(line -> !line.stripLeading().startsWith("#")).reduce("",
            (a, b) -> a + "\n" + b);
    }

    private static String read(Path path) throws IOException {
        assertThat(Files.isRegularFile(path))
            .as("%s must be readable from the module root — a test that read nothing would pass "
                + "vacuously", path)
            .isTrue();
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** Nothing authoritative lives here, and {@code target} is large enough to matter. */
    private static final Set<String> PRUNED =
        Set.of("target", ".git", "docs", "node_modules", ".idea");

    /** Extensions that can carry an image reference; {@code Dockerfile} is matched by name. */
    private static final Set<String> SCANNED = Set.of(".yml", ".yaml", ".java", ".sh", ".md");

    /**
     * Every tracked text file that could name the image.
     *
     * <p>
     * Walked rather than listed, and this is load-bearing rather than tidy. The first version of
     * this test used a fixed list of four paths plus the workflows directory, which read as
     * open-ended and was not: it missed {@code PostgresTestContainerInitializer}, the copy that
     * decides which Postgres the whole integration suite runs against, and it still listed
     * {@code redeploy.sh} after that file stopped naming the image at all — a dead entry that the
     * trailing {@code isRegularFile} filter would have swallowed silently had it been renamed. A
     * closed list cannot catch a reference someone adds somewhere new, which is the only kind of
     * drift worth a test.
     */
    private static List<Path> referencingFiles() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("."))) {
            return files.filter(Files::isRegularFile)
                .filter(p -> PRUNED.stream().noneMatch(d -> p.toString().contains("/" + d + "/")))
                .filter(p -> {
                    String name = p.getFileName().toString();
                    return name.equals("Dockerfile") || SCANNED.stream().anyMatch(name::endsWith);
                }).map(Path::normalize).sorted().toList();
        }
    }

    /** The two versions the Dockerfile actually builds, as they appear in a tag. */
    private static String[] builtVersions(String dockerfile) {
        Matcher base =
            Pattern.compile("(?m)^FROM\\s+pgvector/pgvector:pg(\\d+)").matcher(dockerfile);
        assertThat(base.find())
            .as("docker/postgres/Dockerfile must build FROM a pinned pgvector base whose tag names "
                + "the Postgres major — that major is half of every tag this image is published under")
            .isTrue();

        Matcher search = Pattern.compile("(?m)^ARG\\s+PG_SEARCH_VERSION=(\\d+\\.\\d+\\.\\d+)")
            .matcher(dockerfile);
        assertThat(search.find())
            .as("docker/postgres/Dockerfile must pin PG_SEARCH_VERSION to an exact release: it "
                + "selects the .deb that gets installed, so a floating value would change what the "
                + "image contains without changing what it is called")
            .isTrue();

        return new String[] {base.group(1), search.group(1)};
    }

    /**
     * Every hand-written copy of the version names what the Dockerfile builds.
     *
     * <p>
     * The failure this prevents has no loud symptom. Bumping pg_search in the Dockerfile alone
     * leaves {@code cluster.yaml} pulling a tag that still says {@code 0.24.0}; the publish
     * workflow then either refuses (the tag exists) or pushes NEW contents under an OLD name, at
     * which point the tag no longer identifies its bytes — which is the one property the whole
     * {@code IfNotPresent} scheme rests on.
     */
    @Test
    public void everyReferenceToThePostgresImageNamesTheVersionsItActuallyBuilds()
        throws IOException {
        String[] built = builtVersions(read(DOCKERFILE));

        Map<Path, List<String>> found = new LinkedHashMap<>();
        for (Path path : referencingFiles()) {
            // The tag charset is Docker's, which excludes '$' and the backticks and quotes that
            // wrap these names in prose — so a `${PG_TAG}` reference does not match (it is resolved
            // elsewhere and has no literal to compare), and a tag quoted inside a comment is
            // captured without its punctuation rather than reported as malformed.
            Matcher matcher = IMAGE_REF.matcher(read(path));
            while (matcher.find()) {
                found.computeIfAbsent(path, p -> new ArrayList<>()).add(matcher.group(1));
            }
        }

        assertThat(found.keySet())
            .as("vacuity guard: the sweep must reach the three files that decide what actually "
                + "runs — what the cluster pulls, what the local stack builds, and what the "
                + "integration suite starts. The last one is here because it was MISSED by the "
                + "first version of this test: a drifted tag there sends Testcontainers after an "
                + "image that exists in no registry, and locally the stale one is still cached, so "
                + "the whole IT suite stays green against the wrong pg_search build")
            .contains(CLUSTER, Path.of("docker-compose.yml"), IT_INITIALIZER);

        found.forEach((path, tags) -> tags.forEach(tag -> {
            Matcher shape = TAG_SHAPE.matcher(tag);
            assertThat(shape.matches())
                .as("%s references pg_search:%s, which does not name a Postgres major and a "
                    + "pg_search release. Every tag for this image must, because the tag is the "
                    + "only record of what is inside it", path, tag)
                .isTrue();

            assertThat(new String[] {shape.group(1), shape.group(2)}).as(
                "%s pulls pg_search:%s but docker/postgres/Dockerfile builds Postgres %s with "
                    + "pg_search %s. Bumping one without the other publishes new contents under an "
                    + "old name, or pulls a tag that was never pushed",
                path, tag, built[0], built[1]).containsExactly(built);
        }));
    }

    /**
     * The image the cluster pulls has a publisher.
     *
     * <p>
     * It had exactly one for the life of the project — the {@code docker push} in
     * {@code redeploy.sh} — which meant publishing it required a laptop with registry credentials,
     * and removing that push would have made the image unpublishable with nothing reporting so
     * until the next Postgres or pg_search bump.
     */
    @Test
    public void theImageTheClusterPullsIsPublishedByAWorkflow() throws IOException {
        assertThat(read(CLUSTER))
            .as("vacuity guard: cluster.yaml must pin the Postgres image by name and tag, or there "
                + "is nothing for the workflow below to resolve")
            .containsPattern("(?m)^\\s*imageName:\\s*\\S+:\\S+\\s*$");

        String workflow = stripComments(read(PUBLISH_WORKFLOW));

        // Both assert on the EXTRACTION, not on the filename appearing somewhere. A bare
        // `contains(path)` was satisfied by the refusal step's own `echo` messages, which name both
        // files — stripComments drops `#` lines and has no idea that prose inside a shell string is
        // equally inert. The whole resolve step could then be replaced by a hardcoded
        // `echo "image=…" >> "$GITHUB_OUTPUT"` with this test still green, which is precisely the
        // false-green shape the helper's own javadoc claims to prevent.
        assertThat(workflow)
            .as("the workflow must READ the image name out of cluster.yaml rather than restating "
                + "it. Restating it is what makes a mismatch possible at all: the workflow would "
                + "report success while the cluster pulled a tag nobody pushed")
            .containsPattern("sed[^\\n]*" + Pattern.quote(CLUSTER.toString()));

        assertThat(workflow)
            .as("the workflow must derive the versions from docker/postgres/Dockerfile rather than "
                + "carrying its own copy. A copy here would be one more, and the one place where "
                + "being wrong publishes rather than merely pulls")
            .containsPattern("sed[^\\n]*" + Pattern.quote(DOCKERFILE.toString()));

        assertThat(workflow)
            .as("the image that gets PUSHED must be the resolved one. Reading both files and then "
                + "pushing a literal would leave the derivation decorative — the same shape as a "
                + "column that is written but never filtered on")
            .containsPattern("tags:\\s*\\$\\{\\{\\s*steps\\.image\\.outputs\\.image\\s*\\}\\}");

        assertThat(workflow)
            .as("the workflow must refuse to overwrite an already-published tag. The CNPG cluster "
                + "pulls this image with 'Always', so a silently replaced tag changes the database "
                + "engine under the next pod restart — with no deploy, no manifest change and "
                + "nothing in git recording it")
            .contains("docker manifest inspect");
    }
}
