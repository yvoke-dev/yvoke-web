package de.palsoftware.yvoke;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A release is identified by exactly one string, and this test pins the places that could quietly
 * introduce a second one. The release version originates in the git tag: {@code release.yml}
 * resolves it, stamps the jar with it and pins it into {@code k8s/app/kustomization.yaml}, which
 * DECLARES the release the cluster runs on the commit that gets tagged. ({@code release-version.sh}
 * derives the same string from a working tree — it is what the release workflow re-runs as its
 * final gate, and what stamps a local build; it no longer names an image, because nothing outside
 * CI publishes one.)
 *
 * <p>
 * Those two are a source and a mirror, never two sources. The manifest is what makes
 * {@code git show 1.0.0:k8s/app/kustomization.yaml} answer "what does this release deploy?" with no
 * script in the loop, and it is what a pull-based GitOps controller would read — but a version read
 * FROM the manifest would be a disaster in the other direction: a dirty working tree would then
 * build a jar stamping itself as a release. So git decides and the manifest records.
 *
 * <p>
 * Their agreement is enforced where publishing happens rather than where deploying happens:
 * {@code release.yml} writes both {@code newTag}s from one value and then re-derives the version on
 * the tagged tree, and {@code docker-build-publish.yml} refuses to push unless the commit it is
 * publishing pins the release twice. {@code redeploy.sh} used to carry a third copy of that check,
 * because it could push; it now publishes nothing at all, which is why it needs no guard — see
 * {@link #theDeployScriptCannotPublishAnImage()}.
 *
 * <p>
 * The arrangement replaces {@code :latest} on both release images, which was not merely untidy.
 * Paired with {@code imagePullPolicy: Always} it meant that any pod restart — an OOM kill, a node
 * reboot, a {@code kubectl rollout restart} — re-pulled whatever {@code :latest} pointed at and
 * re-ran the Flyway migration initContainer. The schema could therefore advance with no deploy and
 * no operator action, and "which schema is this cluster on?" had no answer derivable from the
 * manifests. That is also what made a rollback meaningless: every stored ReplicaSet revision had a
 * byte-identical pod template, so {@code kubectl rollout undo} re-pulled the same moving tag.
 *
 * <p>
 * One consequence is load-bearing rather than stylistic and has an assertion below:
 * {@code imagePullPolicy} becomes {@code IfNotPresent} because an immutable tag identifies its
 * content — which in turn means a published tag must never be overwritten, or a node serves stale
 * bytes from its cache.
 *
 * <p>
 * The app image and the migration image are deliberately two expansions of one variable. They carry
 * the code and the schema for the same release, and a version skew between them is exactly the
 * failure that has no loud symptom: Flyway's {@code ignoreMigrationPatterns} defaults to
 * {@code *:future} (verified against the shipped flyway 10.22.0), so an older migration image
 * against a newer database applies nothing, reports success and leaves the schema ahead of the
 * code.
 */
public class ReleaseImageTagPolicyTest {

    private static final Path REDEPLOY = Path.of("redeploy.sh");
    private static final Path KUSTOMIZATION = Path.of("k8s/app/kustomization.yaml");
    private static final Path DEPLOYMENT = Path.of("k8s/app/yvoke-app/deployment.yaml");
    private static final Path MIGRATION_DOCKERFILE = Path.of("docker/db/Dockerfile");
    private static final Path PUBLISH_WORKFLOW =
        Path.of(".github/workflows/docker-build-publish.yml");

    /**
     * The placeholder {@code deployment.yaml} carries where a tag would otherwise sit. The
     * kustomization's {@code images:} transformer replaces it, so it is never deployed — it exists
     * for the person who runs {@code kubectl apply -f deployment.yaml} directly, bypassing
     * kustomize and therefore the release declaration. It is a legal Docker tag, so only the PULL
     * fails: an ImagePullBackOff naming the mistake, rather than a plausible-but-wrong
     * {@code :latest} that silently deploys something.
     */
    private static final String SENTINEL = "SET-BY-KUSTOMIZE";

    private static String read(Path path) throws IOException {
        assertThat(Files.isRegularFile(path))
            .as("%s must be readable from the module root — a test that read nothing would pass "
                + "vacuously", path)
            .isTrue();
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * Drops whole-line {@code #} comments from shell and YAML.
     *
     * <p>
     * Necessary rather than tidy: every rule below is explained in a comment next to the code that
     * obeys it, and those comments necessarily quote the thing being forbidden — the deploy script
     * explains why {@code kubectl rollout restart} is gone and why it generates no overlay, naming
     * both. A scan over raw text matches the explanation and reports the rule as broken by the very
     * comment documenting it. Only full-line comments are removed, so a {@code #} inside a value is
     * left alone.
     */
    private static String stripComments(String text) {
        return text.lines().filter(line -> !line.stripLeading().startsWith("#")).reduce("",
            (a, b) -> a + "\n" + b);
    }

    /**
     * The heredoc body of {@code usage()} — what an operator actually reads from {@code --help}.
     *
     * <p>
     * Scoped deliberately: the assertions over it are about what the help TELLS someone to run, and
     * the surrounding script legitimately contains the same words for other reasons.
     */
    private static String usageText(String script) {
        Matcher usage =
            Pattern.compile("usage\\(\\)\\s*\\{.*?\\n(.*?)\\nEOF", Pattern.DOTALL).matcher(script);
        assertThat(usage.find()).as("redeploy.sh must define usage() as a heredoc").isTrue();
        return usage.group(1);
    }

    /**
     * The script with its comments AND its {@code usage()} heredoc removed — what actually runs.
     *
     * <p>
     * The heredoc has to go for the same reason the comments do, and it is the easier of the two to
     * forget: it is a shell string rather than a comment, so {@code stripComments} leaves it, and
     * it legitimately says things like "builds and pushes NO images". An assertion that the script
     * never pushes is otherwise unwritable — the word is right there in the help text, describing
     * its own absence.
     */
    private static String executableBody(String script) {
        return stripComments(
            script.replaceAll("(?s)usage\\(\\)\\s*\\{.*?\\nEOF", "usage() { :; }"));
    }

    private static List<String> matches(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        List<String> found = new ArrayList<>();
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    private static List<Path> trackedManifests() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("k8s"))) {
            return files.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList();
        }
    }

    @Test
    public void noTrackedManifestPinsAMovingTag() throws IOException {
        List<Path> manifests = trackedManifests();
        assertThat(manifests).as(
            "vacuity guard: the k8s manifests must be found, or the scan " + "below proves nothing")
            .hasSizeGreaterThan(1);

        for (Path manifest : manifests) {
            assertThat(read(manifest)).as(
                "%s must not pin ':latest'. A moving tag makes every pod restart a silent "
                    + "redeploy — including a re-run of the Flyway migration initContainer — and "
                    + "leaves the cluster's actual version underivable from the manifests",
                manifest).doesNotContain(":latest");
        }
    }

    @Test
    public void theTrackedManifestDeclaresTheReleaseItDeploys() throws IOException {
        String kustomization = stripComments(read(KUSTOMIZATION));

        List<String> tags =
            matches(kustomization, "(?m)^\\s*newTag:\\s*\"?([^\"\\s]+)\"?\\s*$").stream()
                .map(line -> line.replaceAll(".*newTag:\\s*\"?([^\"\\s]+)\"?\\s*$", "$1")).toList();

        assertThat(tags)
            .as("the manifest must pin both release images: the deployed version is a fact that "
                + "belongs in git, so that `git show <tag>:k8s/app/kustomization.yaml` answers "
                + "'what does this release deploy?' without running anything")
            .hasSize(2);

        assertThat(tags.stream().distinct().toList())
            .as("both images must carry the SAME version. They are the code and the schema of one "
                + "release, and a skew between them is the failure with no loud symptom: Flyway "
                + "ignores future migrations by default, so an older migration image against a "
                + "newer database applies nothing and reports success")
            .hasSize(1);

        assertThat(tags.get(0))
            .as("the pinned tag must look like a release version. This is the value that must "
                + "equal the git tag of the commit it is committed on — release.yml writes both "
                + "newTags from that one value, and docker-build-publish.yml refuses to push "
                + "unless the commit it is publishing pins the release twice")
            .matches("\\d+\\.\\d+\\.\\d+");

        String deployment = read(DEPLOYMENT);
        for (String image : List.of("yvoke/app", "yvoke/db-migration")) {
            assertThat(deployment)
                .as("%s must carry the %s sentinel in the tracked base, so that applying the base "
                    + "without the release overlay fails visibly instead of deploying something "
                    + "plausible", image, SENTINEL)
                .contains("image: " + image + ":" + SENTINEL);
        }

        assertThat(matches(deployment, "imagePullPolicy:\\s*Always"))
            .as("both release containers must use IfNotPresent: an immutable tag identifies its "
                + "content, so re-pulling buys nothing and makes every pod start depend on the "
                + "registry being reachable. (This is also why a published tag must never be "
                + "overwritten — a node would keep serving the cached bytes.)")
            .isEmpty();
    }

    @Test
    public void theDeployScriptDerivesOneVersionAndUsesItEverywhere() throws IOException {
        String redeploy = stripComments(read(REDEPLOY));
        // Anchored to a whole line: the usage heredoc is not a comment, and its target list says
        // "Maven build -> docker compose build -> down/up", so a bare contains() was satisfied by
        // help text. Deleting the actual build step would then leave the local target booting
        // whatever stale images the daemon already holds — CLAUDE.md's `docker compose up -d never
        // rebuilds` pitfall verbatim — with this guard still green.
        assertThat(redeploy).as("vacuity guard: redeploy.sh must still build and deploy something")
            .containsPattern("(?m)^\\s*docker compose build\\s*$").contains("kubectl apply");

        assertThat(redeploy)
            .as("redeploy.sh must derive the version from release-version.sh rather than deriving "
                + "it again itself — the whole scheme rests on there being exactly one derivation")
            .contains("release-version.sh");

        List<String> mvnwLines = matches(redeploy, "(?m)^\\s*\\./mvnw .*$");
        assertThat(mvnwLines).as("vacuity guard: redeploy.sh must invoke the Maven wrapper")
            .isNotEmpty();
        assertThat(mvnwLines)
            .as("every Maven invocation must stamp the derived version into the artifact. A build "
                + "path without -Drevision produces a SNAPSHOT jar that then gets tagged and "
                + "pushed as a release — the jar and the image would disagree about what they are")
            .allMatch(line -> line.contains("-Drevision="));

        assertThat(redeploy)
            .as("'kubectl rollout restart' must be gone. It existed only to force a re-pull of a "
                + "moving tag; with a per-release tag the pod template itself changes, so the "
                + "apply rolls on its own — and keeping the restart would mean an apply that "
                + "changed nothing still looked like a successful deploy, destroying the one "
                + "diagnostic signal this scheme buys")
            .doesNotContain("rollout restart");
    }

    /**
     * The deploy script cannot publish a release image, which is what replaced four guards.
     *
     * <p>
     * It used to build and push all three images, and every safeguard around that existed to make
     * the push safe: a refusal to push a {@code -SNAPSHOT}, a comparison of the derived version
     * against the manifest, a {@code docker manifest inspect} probe against overwriting a published
     * tag, and a registry-name check — plus {@code ALLOW_SNAPSHOT_PUSH} and
     * {@code ALLOW_TAG_OVERWRITE} to soften two of them. Each was a rule a reader had to know.
     *
     * <p>
     * Once CI published every image the path was already unreachable in normal operation — on a
     * released version the overwrite probe refused, and on anything else the SNAPSHOT guard did —
     * so it could only run under an override, which is exactly the situation the guards were
     * protecting against. Deleting the push deletes the need for all of them: a script with no
     * {@code docker
     * push} cannot publish a wrong image, and an argument that does not exist cannot be misused.
     *
     * <p>
     * This is the one assertion that keeps that true. Reintroducing a push here would silently
     * reintroduce every one of those failure modes, because none of the guards remain to catch it.
     */
    @Test
    public void theDeployScriptCannotPublishAnImage() throws IOException {
        String redeploy = read(REDEPLOY);
        String body = executableBody(redeploy);

        // Ban the WORD, not one spelling of one command. The first version of this assertion was
        // `doesNotContain("docker push")` plus `doesNotContainPattern("docker\\s+build\\b")`, and
        // between them they missed `docker buildx build --push` (the modern form, and the one the
        // sibling workflows use), `docker image push`, `docker compose push` and `crane push` —
        // every publish route anyone would actually reach for today. A guard whose whole purpose is
        // to stop a future edit is worth nothing if it only recognises the syntax that happened to
        // be there when it was written.
        assertThat(body)
            .as("no executable line of redeploy.sh may push anything. Images are published by "
                + "GitHub Actions from a tagged commit; a laptop that can push can publish a "
                + "SNAPSHOT jar under a release tag, over bytes that nodes have already cached "
                + "with IfNotPresent")
            .doesNotContain("push");

        assertThat(body)
            .as("redeploy.sh must not build a release image either. Building without pushing looks "
                + "harmless, but it is how a push comes back: the tags have to be named somewhere, "
                + "and a named tag one line away from a push is not a boundary. ('docker compose "
                + "build' for the local stack is untouched — it produces no registry image.)")
            .doesNotContainPattern("docker\\s+(image\\s+|buildx\\s+)?build\\b")
            .doesNotContain("buildx");

        assertThat(usageText(redeploy))
            .as("the rollback instruction must name the checkout as the version selector. The "
                + "tracked manifest is what the apply reads, so nothing in the environment can "
                + "select a release — and a rollback that silently redeploys the version being "
                + "rolled away from is the worst possible successful exit")
            .doesNotContain("TAG=").contains("git checkout");

        assertThat(body)
            .as("nothing may generate a kustomize overlay at deploy time. The manifest under "
                + "version control IS the deployment declaration, so a generated one would be a "
                + "second answer to 'what is deployed?' that no reviewer and no git history sees")
            .doesNotContain("k8s/.release");
    }

    /**
     * The workflow that PUBLISHES the images and the manifest that PULLS them name the same
     * repositories.
     *
     * <p>
     * Nothing connects those two files, and the failure is late and total: rename an image on one
     * side and the release publishes successfully, the manifest applies successfully, and the pods
     * sit in ImagePullBackOff pulling something that was never pushed. Both sides are read from the
     * real files here, so they cannot drift apart without this failing.
     */
    @Test
    public void thePublishedImageNamesAreTheOnesTheClusterPulls() throws IOException {
        String publish = read(PUBLISH_WORKFLOW);
        String kustomization = stripComments(read(KUSTOMIZATION));

        List<String> pulled = matches(kustomization, "(?m)^\\s*newName:\\s*(\\S+)\\s*$").stream()
            .map(line -> line.replaceAll(".*newName:\\s*(\\S+)\\s*$", "$1")).toList();

        assertThat(pulled).as("vacuity guard: the manifest must rename both images to the registry "
            + "repositories they are published under").hasSize(2);

        for (String image : pulled) {
            assertThat(publish)
                .as("the manifest pulls '%s', so the publish workflow must push exactly that "
                    + "repository. A rename on either side alone produces an ImagePullBackOff at "
                    + "deploy time, long after both workflows reported success", image)
                .contains(image + ":");
        }
    }

    /**
     * The manifest-versus-tag agreement is still enforced somewhere.
     *
     * <p>
     * {@code redeploy.sh} used to compare its git-derived version against the manifest's
     * {@code newTag} and refuse to push on a mismatch, and that comparison was pinned by a test.
     * Deleting the push deleted the comparison — correctly, since the script can no longer publish
     * anything — but it also deleted the only assertion covering the rule, leaving the class
     * javadoc above asserting that {@code docker-build-publish.yml} carries it while nothing
     * checked that it still does. Its manifest check could have been deleted with the whole suite
     * green.
     *
     * <p>
     * That workflow is the last gate before bytes reach the registry, and it fires on ANY published
     * release including a hand-made one, so it cannot lean on the release workflow's guarantees.
     */
    @Test
    public void thePublishWorkflowRefusesACommitThatDoesNotDeclareTheReleaseItPublishes()
        throws IOException {
        String publish = stripComments(read(PUBLISH_WORKFLOW));

        assertThat(publish)
            .as("vacuity guard: %s must still be the workflow that pushes the release images",
                PUBLISH_WORKFLOW)
            .contains("docker/build-push-action");

        assertThat(publish)
            .as("the publish workflow must read the tracked manifest at the commit it is "
                + "publishing. Nothing else compares the release being pushed against the release "
                + "the manifest declares — redeploy.sh gave that check up when it gave up pushing")
            .contains(KUSTOMIZATION.toString());

        assertThat(publish)
            .as("...and it must require BOTH images to be pinned to the version it is publishing. "
                + "Counting is the whole point: one pinned image and one stale one is precisely the "
                + "code-and-schema skew that has no loud symptom, because Flyway ignores future "
                + "migrations by default")
            .containsPattern("newTag[^\\n]*RELEASE_VERSION").containsPattern("-ne\\s+2");
    }

    @Test
    public void theSchemaAuthorityIsPinnedToAnExactVersion() throws IOException {
        assertThat(read(MIGRATION_DOCKERFILE))
            .as("the migration image is the sole schema authority — the app does not run Flyway — "
                + "so a floating base tag means the tool that owns the database can change under a "
                + "rebuild with nothing recording it. Pin the full version; a release must be "
                + "reproducible down to the thing that writes the schema")
            .containsPattern("FROM flyway/flyway:\\d+\\.\\d+\\.\\d+");
    }
}
