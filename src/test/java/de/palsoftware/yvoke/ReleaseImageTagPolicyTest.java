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
 * introduce a second one. The version is DERIVED from the git tag by {@code release-version.sh} and
 * used for the Maven build and both image tags; {@code k8s/app/kustomization.yaml} DECLARES the
 * release the cluster runs, committed on the commit that gets tagged.
 *
 * <p>
 * Those two are a source and a mirror, never two sources. The manifest is what makes
 * {@code git show 1.0.0:k8s/app/kustomization.yaml} answer "what does this release deploy?" with no
 * script in the loop, and it is what a pull-based GitOps controller would read — but a version read
 * FROM the manifest would be a disaster in the other direction: a dirty working tree would then
 * build a jar stamping itself as a release. So git decides, the manifest records, and
 * {@code redeploy.sh} refuses to push when they disagree. Nothing else would notice a manifest
 * pinned to 1.0.0 on a commit tagged 1.0.1.
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
                + "equal the git tag of the commit it is committed on — redeploy.sh refuses to "
                + "push when they disagree, because nothing else would notice")
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
        assertThat(redeploy).as("vacuity guard: redeploy.sh must still build and push images")
            .contains("docker build").contains("docker push");

        assertThat(redeploy)
            .as("redeploy.sh must derive the version from release-version.sh rather than deriving "
                + "it again itself — the whole scheme rests on there being exactly one derivation")
            .contains("release-version.sh");

        assertThat(matches(redeploy, "(?m)^TAG=.*$"))
            .as("TAG must BE the derived version, with no environment override. An override made "
                + "every guard below inspect a different string from the one the jar was stamped "
                + "with, so `TAG=<a released version> ./redeploy.sh k8s` on a dirty tree wrapped a "
                + "SNAPSHOT jar in release-labelled images and pushed them over the published ones, "
                + "reporting success throughout")
            .containsExactly("TAG=\"$VERSION\"");

        List<String> mvnwLines = matches(redeploy, "(?m)^\\s*\\./mvnw .*$");
        assertThat(mvnwLines).as("vacuity guard: redeploy.sh must invoke the Maven wrapper")
            .isNotEmpty();
        assertThat(mvnwLines)
            .as("every Maven invocation must stamp the derived version into the artifact. A build "
                + "path without -Drevision produces a SNAPSHOT jar that then gets tagged and "
                + "pushed as a release — the jar and the image would disagree about what they are")
            .allMatch(line -> line.contains("-Drevision="));

        // Assert the COMPARISON, not the filename. `contains("kustomization.yaml")` was satisfied
        // by merely reading the file — the guard it claimed to pin could be deleted wholesale and
        // this test stayed green, which is the exact shape of test this project keeps a rule about.
        assertThat(redeploy)
            .as("redeploy.sh must compare the version it derived from git against the tag pinned "
                + "in the manifest, and exit non-zero when they differ. The manifest is a MIRROR of "
                + "the git tag, never a second source of it: committing newTag 1.0.0 and then "
                + "tagging 1.0.1 deploys a different release than the one that was built, and this "
                + "comparison is the only thing in the system that would notice")
            .containsPattern("\\$\\{TAG\\}\"\\s*!=\\s*\"\\$\\{MANIFEST_TAG\\}");

        assertThat(redeploy)
            .as("'kubectl rollout restart' must be gone. It existed only to force a re-pull of a "
                + "moving tag; with a per-release tag the pod template itself changes, so the "
                + "apply rolls on its own — and keeping the restart would mean an apply that "
                + "changed nothing still looked like a successful deploy, destroying the one "
                + "diagnostic signal this scheme buys")
            .doesNotContain("rollout restart");
    }

    @Test
    public void aSnapshotIsNeverPushedAndTheDeployOnlyPathIsBothParsedAndDocumented()
        throws IOException {
        String redeploy = read(REDEPLOY);

        // The guard itself, not the word. `contains("SNAPSHOT")` was satisfied by the comments and
        // the usage heredoc, so the whole refusal block could be deleted with the test still green.
        assertThat(stripComments(redeploy))
            .as("redeploy.sh must refuse to push a version that is not an exact clean tag, and the "
                + "refusal must actually exit. Every non-release derivation ends in -SNAPSHOT, so "
                + "this stays a substring test rather than a judgement")
            .containsPattern("\\*SNAPSHOT\\*").contains("ALLOW_SNAPSHOT_PUSH");

        assertThat(stripComments(redeploy))
            .as("a published tag must never be overwritten — that is what lets the manifests pull "
                + "with IfNotPresent. It was stated in four places and enforced in none, while the "
                + "mirror guard made overwriting the only thing a local k8s push could do: TAG "
                + "equals the version the manifest declares, which CI has normally published "
                + "already. The registry must be probed rather than the rule trusted")
            .contains("docker manifest inspect").contains("ALLOW_TAG_OVERWRITE");

        assertThat(redeploy)
            .as("--deploy-only must be parsed AND documented in the usage text. A flag that is "
                + "documented but unparsed (or parsed but undocumented) is the same silent no-op "
                + "shape as a form field no controller binds")
            .containsPattern("--deploy-only\\)").containsPattern("--deploy-only\\s+\\w");

        // The usage text advertised `TAG=1.0.0 ./redeploy.sh k8s --deploy-only` as THE rollback
        // command. On that path TAG is read nowhere: the deploy is `kustomize build k8s/app`, whose
        // version comes from the tracked manifest — so following the built-in help re-applies the
        // release you are trying to roll away from, and reports success. The version selector is
        // the checkout, which is why the help must name it.
        assertThat(usageText(redeploy)).as(
            "the usage text must not suggest an environment variable selects what --deploy-only "
                + "applies; the checked-out manifest does. A rollback that silently redeploys the "
                + "version being rolled away from is the worst possible successful exit")
            .doesNotContain("TAG=").contains("git checkout");

        assertThat(stripComments(redeploy))
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
        String publish = read(Path.of(".github/workflows/docker-build-publish.yml"));
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
