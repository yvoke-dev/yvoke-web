package de.palsoftware.yvoke;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The git tag is the source of truth for the release version, and this test pins the four places
 * that claim would otherwise be re-decided independently: the pom, the jar's filename, the build
 * metadata baked into the artifact, and {@code release-version.sh} itself.
 *
 * <p>
 * The rule the whole scheme rests on is that the version is the tag <em>verbatim</em> — tag
 * {@code 1.0.0} produces Maven version {@code 1.0.0}, image tag {@code 1.0.0} and
 * {@code build.version=1.0.0}, with no prefix to add and none to strip. That is not a cosmetic
 * preference. A transformation, however small, is a second derivation, and two derivations can
 * disagree: the first draft of this scheme had the release script strip a leading {@code v} while
 * the deploy script kept it, so a documented rollback command named an image tag no build had ever
 * produced. Deleting the transformation is what makes that class of drift unrepresentable rather
 * than merely tested.
 *
 * <p>
 * Two of the assertions below are behavioural rather than textual: they copy the script into a
 * throwaway git repository, tag it, and run it. A text scan could only confirm that today's script
 * does not contain a prefix-stripping construct anyone happened to think of; running it against a
 * real tag confirms the property that matters, and would catch a future "improvement" that
 * reintroduces a transformation in a shape this test never anticipated.
 *
 * <p>
 * Note what is deliberately NOT asserted here: that any particular tag exists in this repository.
 * The release branch of the script only fires on an exact tag of a clean tree, no JUnit tier can
 * manufacture that state for the checkout it is running in, and faking it would test the fake. That
 * half is gated in {@code release.yml} instead, which creates the tag and then — before pushing
 * anything — requires {@code release-version.sh} to derive exactly the version it just tagged.
 */
public class ReleaseVersionTest {

    private static final Path POM = Path.of("pom.xml");
    private static final Path DOCKERFILE = Path.of("Dockerfile");
    private static final Path RELEASE_VERSION_SCRIPT = Path.of("release-version.sh");

    /**
     * The Docker tag grammar (a tag may not begin with {@code .} or {@code -}, and is at most 128
     * characters). Asserting the derived version against it is not a tautology: it is what catches
     * a later change that appends semver build metadata such as {@code +g1a2b3c}, which is a
     * perfectly legal Maven version and an illegal Docker tag, and would therefore break
     * {@code docker build -t} for releases only.
     */
    private static final Pattern DOCKER_TAG =
        Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9._-]{0,127}$");

    /**
     * The project's own coordinates, with the {@code <parent>} block removed first.
     *
     * <p>
     * Parsing the raw file would read Spring Boot's values instead of this project's: the first
     * {@code <artifactId>} in pom.xml is {@code spring-boot-starter-parent} and the first
     * {@code <version>} is the Boot release. A test that compared {@code build.artifact} against a
     * first-match parse would be comparing it against {@code spring-boot-starter-parent} and would
     * pass or fail for reasons unconnected to anything it claims to check.
     */
    private static String projectPom() throws IOException {
        String pom = Files.readString(POM, StandardCharsets.UTF_8);
        assertThat(pom)
            .as("pom.xml must be readable from the module root at %s — a test that "
                + "silently read nothing would pass vacuously", POM.toAbsolutePath())
            .contains("<project");
        return pom.replaceAll("(?s)<parent>.*?</parent>", "");
    }

    private static String tagValue(String xml, String tag) {
        Matcher matcher = Pattern
            .compile("<" + tag + ">\\s*(.*?)\\s*</" + tag + ">", Pattern.DOTALL).matcher(xml);
        return matcher.find() ? matcher.group(1) : null;
    }

    @Test
    public void theProjectVersionIsDrivenByTheRevisionProperty() throws IOException {
        String pom = projectPom();

        assertThat(tagValue(pom, "artifactId"))
            .as("vacuity guard: with the <parent> block stripped, the first artifactId must be "
                + "this project's own — otherwise every assertion below is reading Spring Boot's "
                + "coordinates")
            .isEqualTo("yvoke");

        assertThat(tagValue(pom, "version"))
            .as("the project version must come from the ${revision} placeholder so a release can "
                + "be built with -Drevision=<tag> and no per-release pom edit. Maven resolves "
                + "exactly three placeholders inside <version> — revision, sha1 and changelist — "
                + "so a differently-named property would be accepted by the XML and silently fail "
                + "to resolve")
            .isEqualTo("${revision}");

        assertThat(tagValue(pom, "revision"))
            .as("a <revision> default must be declared in <properties>, or a plain "
                + "'./mvnw verify' with no -Drevision on the command line resolves the literal "
                + "string ${revision} as the artifact version")
            .isNotNull();
    }

    @Test
    public void theCheckedInRevisionDefaultIsNotAReleaseVersion() throws IOException {
        assertThat(tagValue(projectPom(), "revision"))
            .as("the checked-in default is what every untagged working copy and every CI push "
                + "build resolves to, so it must be impossible to mistake for a release. Ending it "
                + "in -SNAPSHOT is what lets 'is this a release?' stay a substring test rather "
                + "than a judgement, both in redeploy.sh's push guard and in the release workflow")
            .endsWith("-SNAPSHOT");
    }

    @Test
    public void theProjectDoesNotPublishAMavenArtifact() throws IOException {
        assertThat(projectPom())
            .as("${revision} without flatten-maven-plugin installs a pom whose <version> is still "
                + "the literal placeholder, which is unresolvable for anyone consuming it. That is "
                + "harmless here ONLY because this project is an application that is never "
                + "published — declaring distributionManagement would silently make the "
                + "unflattened pom a real defect, so the day that changes, flatten must be added "
                + "in the same change")
            .doesNotContain("<distributionManagement>");

        List<Path> buildEntryPoints = buildEntryPoints();
        assertThat(buildEntryPoints).as("vacuity guard: the build entry points must be found, or "
            + "the scan below proves nothing").isNotEmpty();

        for (Path script : buildEntryPoints) {
            assertThat(Files.readString(script, StandardCharsets.UTF_8))
                .as("%s must not invoke 'mvn install' or 'mvn deploy': both write the unflattened "
                    + "pom described above into a repository other builds can resolve from", script)
                .doesNotContainPattern("mvnw?\\b[^\\n]*\\b(install|deploy)\\b");
        }
    }

    private static List<Path> buildEntryPoints() throws IOException {
        Path workflows = Path.of(".github/workflows");
        try (var files = Files.walk(workflows)) {
            List<Path> paths = new ArrayList<>(files.filter(Files::isRegularFile).toList());
            paths.add(Path.of("redeploy.sh"));
            return paths;
        }
    }

    @Test
    public void theDockerfileNamesTheJarItCopiesExactly() throws IOException {
        String finalName = tagValue(projectPom(), "finalName");
        assertThat(finalName)
            .as("a <finalName> must pin the artifact filename. Without it the jar is named after "
                + "the version, so the filename changes per release — and the Dockerfile below "
                + "cannot name a moving target")
            .isNotNull();

        String dockerfile = Files.readString(DOCKERFILE, StandardCharsets.UTF_8);

        assertThat(dockerfile)
            .as("COPY target/*.jar into a non-directory destination is a build failure the moment "
                + "two jars match ('the destination must be a directory'), and a dynamic version "
                + "makes two jars in target/ ordinary rather than exotic. Today this only ever "
                + "works because redeploy.sh happens to rm -rf target/ first — an accident, not a "
                + "guarantee, and one that does not hold for a plain 'docker compose build'")
            .doesNotContain("target/*.jar");

        assertThat(dockerfile)
            .as("the Dockerfile must copy exactly the artifact the pom's <finalName> produces. "
                + "Both sides of this comparison are read from real files, so the two cannot drift "
                + "apart without this failing — a test that hard-coded the expected name on both "
                + "sides would agree with any bug")
            .contains("target/" + finalName + ".jar");
    }

    @Test
    public void theBuildStampsTheVersionAndTimeIntoTheArtifact() throws Exception {
        Properties buildInfo = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/META-INF/build-info.properties")) {
            assertThat(in)
                .as("META-INF/build-info.properties must be on the classpath. It is produced by "
                    + "the spring-boot-maven-plugin build-info goal, and it is the single "
                    + "load-bearing resource of this whole scheme: BuildProperties is "
                    + "@ConditionalOnResource on it, so if the goal is ever unbound the bean "
                    + "vanishes silently, /actuator/info goes empty and the admin footer fails at "
                    + "render time rather than at startup. This assertion is the fast-tier guard "
                    + "that fails first")
                .isNotNull();
            buildInfo.load(in);
        }

        assertThat(buildInfo.getProperty("build.artifact"))
            .as("build.artifact must be this project's own artifactId, read from the pom rather "
                + "than written twice")
            .isEqualTo(tagValue(projectPom(), "artifactId"));

        String version = buildInfo.getProperty("build.version");
        assertThat(version).as("build.version must be present and non-blank").isNotBlank();
        assertThat(version)
            .as("an unresolved placeholder must fail loudly here rather than ship as a plausible "
                + "string. '${revision}' means Maven never substituted the property; "
                + "'@project.version@' means resource filtering did not run — both would otherwise "
                + "be advertised to MCP clients and printed in the admin footer as if they were a "
                + "version")
            .doesNotContain("${").doesNotContain("@");

        assertThat(Instant.parse(buildInfo.getProperty("build.time")))
            .as("build.time must be a parseable instant — it is what tells an operator which build "
                + "is actually running when two images share a version")
            .isNotNull();
    }

    @Test
    public void theDerivedVersionIsAlwaysAUsableDockerTag() throws Exception {
        assertThat(Files.isExecutable(RELEASE_VERSION_SCRIPT))
            .as("%s must be executable — redeploy.sh and the release workflow both call it "
                + "directly", RELEASE_VERSION_SCRIPT)
            .isTrue();

        String derived = runScript(Path.of("").toAbsolutePath());

        assertThat(derived.lines().count())
            .as("the script must print exactly one line: both callers capture it with $(...) and "
                + "would otherwise embed a newline in an image tag")
            .isEqualTo(1);
        assertThat(derived)
            .as("whatever the script derives must be usable unchanged as a Docker tag, because "
                + "that is precisely what redeploy.sh does with it")
            .matches(DOCKER_TAG);
    }

    @Test
    public void anExactTagOnACleanTreeIsTheVersionVerbatim(@TempDir Path repo) throws Exception {
        initRepository(repo);
        git(repo, "tag", "9.9.9");

        assertThat(runScript(repo))
            .as("the version IS the tag, byte for byte. Any transformation — stripping a prefix, "
                + "adding one, normalising — is a second derivation, and the other places that "
                + "must agree on this string (the image tag, the rollback command, the CI gate) "
                + "each derive it separately. This is the assertion that keeps them from drifting")
            .isEqualTo("9.9.9");
    }

    @Test
    public void anythingOtherThanAnExactCleanTagIsASnapshot(@TempDir Path repo) throws Exception {
        initRepository(repo);
        git(repo, "tag", "9.9.9");
        Files.writeString(repo.resolve("untracked.txt"), "x");

        assertThat(runScript(repo))
            .as("a dirty tree must never derive a release version. Note the tree is dirtied here "
                + "with an UNTRACKED file on purpose: 'git describe --dirty' ignores those, so the "
                + "only thing that catches this case is the 'git status --porcelain' check — which "
                + "is also why the generated k8s overlay has to be git-ignored, or the first "
                + "release would make every later build derive a SNAPSHOT")
            .endsWith("-SNAPSHOT");
    }

    /**
     * The release workflow must check out the full history.
     *
     * <p>
     * This is the single most likely future breakage in the whole scheme, and it is silent.
     * {@code actions/checkout} defaults to a depth-1 checkout with no tags, so
     * {@code release-version.sh} would find no tag, derive a SNAPSHOT, and the release would build
     * and publish an artifact that identifies itself as a non-release — with every step still
     * reporting success. Nobody removes {@code fetch-depth: 0} deliberately; it disappears when
     * someone rewrites the checkout step for an unrelated reason.
     */
    @Test
    public void theReleaseWorkflowChecksOutTheTagsItsVersionIsDerivedFrom() throws IOException {
        Path workflow = Path.of(".github/workflows/release.yml");
        String release = Files.readString(workflow, StandardCharsets.UTF_8);

        assertThat(release)
            .as("vacuity guard: %s must still be the workflow that derives and tags the release",
                workflow)
            .contains("release-version.sh").contains("git tag");

        assertThat(release)
            .as("actions/checkout defaults to depth 1 with no tags, which makes the derivation "
                + "return a SNAPSHOT and the release publish a non-release artifact, silently")
            .contains("fetch-depth: 0");

        List<String> mvnwLines = new ArrayList<>();
        Matcher mvnw = Pattern.compile("(?m)^\\s*\\./mvnw .*$").matcher(release);
        while (mvnw.find()) {
            mvnwLines.add(mvnw.group());
        }

        assertThat(mvnwLines).as("vacuity guard: the release workflow must build something")
            .isNotEmpty();
        assertThat(mvnwLines)
            .as("every Maven invocation in the release workflow must stamp the release version. "
                + "Without -Drevision the pom falls back to its 0.0.0-SNAPSHOT default, and the "
                + "release would publish a jar identifying itself as a non-release under a release "
                + "tag — the workflow's own build-info check catches it, but only that check does")
            .allMatch(line -> line.contains("-Drevision="));
    }

    /**
     * The publish workflow validates the tag it is handed.
     *
     * <p>
     * It fires on <em>any</em> published release, including one created by hand in the GitHub UI,
     * and the tag name flows straight into both image tags. A release named {@code v1.2.3} or
     * {@code 1.2.3-rc1} would therefore publish images under that spelling — breaking the
     * one-string rule at the only point in the system where it is not derived — and Docker Hub tags
     * are not cheaply retractable.
     */
    @Test
    public void thePublishWorkflowRefusesATagThatIsNotThisProjectsVersionShape()
        throws IOException {
        Path workflow = Path.of(".github/workflows/docker-build-publish.yml");
        String publish = Files.readString(workflow, StandardCharsets.UTF_8);

        assertThat(publish)
            .as("vacuity guard: %s must still be the workflow that publishes the images", workflow)
            .contains("docker/build-push-action");

        assertThat(publish)
            .as("the publish workflow must reject a tag that is not a plain N.N.N version before "
                + "it pushes anything — it is triggered by an event, not by a script, so the "
                + "guarantees the release workflow enforces do not reach it")
            .contains("^[0-9]+\\.[0-9]+\\.[0-9]+$");
    }

    /** Layouts behind the sign-in that must show which build is running. */
    private static final List<Path> SIGNED_IN_LAYOUTS =
        List.of(Path.of("src/main/resources/templates/admin/layout.html"),
            Path.of("src/main/resources/templates/chat/layout.html"));

    /**
     * Every signed-in surface shows which build it is running.
     *
     * <p>
     * Both sidebars carry it, so a report about an answer — or about a cost — can name the build
     * that produced it without the reporter having to reach the admin console. What matters is that
     * the two layouts do not drift: they are separate fragments with separate footers, and the
     * obvious failure is adding a surface and forgetting one.
     *
     * <p>
     * The class name is {@code build-version}, never bare {@code version}: in this codebase and in
     * {@code spec.md}, "version" already means the corpus tag (9.3.1 / 10.0), and admin templates
     * already carry a {@code versions} model attribute for exactly that. The {@code th:title} is
     * part of the contract rather than decoration — a non-release build renders a long
     * {@code git describe} string into a fixed-width sidebar, so the CSS truncates it and the
     * tooltip is what keeps the full value reachable.
     */
    @Test
    public void everySignedInLayoutShowsTheBuildVersion() throws IOException {
        for (Path layoutPath : SIGNED_IN_LAYOUTS) {
            String layout = Files.readString(layoutPath, StandardCharsets.UTF_8);

            assertThat(layout)
                .as("vacuity guard: %s must still declare the footer this assertion is about",
                    layoutPath)
                .contains("user-profile-footer");

            List<String> expressions = new ArrayList<>();
            Matcher matcher =
                Pattern.compile("<span[^>]*build-version[^>]*>", Pattern.DOTALL).matcher(layout);
            while (matcher.find()) {
                expressions.add(matcher.group());
            }

            assertThat(expressions)
                .as("%s must render the build version exactly once — each layout is a fragment "
                    + "consumed by every page under it, so a second copy is a second thing to keep "
                    + "true", layoutPath)
                .hasSize(1);
            assertThat(expressions.get(0))
                .as("the version element in %s must RENDER the bean's value, not merely mention "
                    + "it: the element ships with a hardcoded 'dev' placeholder, so without "
                    + "th:text the footer would show that literal on every page while this "
                    + "assertion stayed green", layoutPath)
                .contains("th:text=\"${@buildProperties.version}\"").contains("th:title");
        }
    }

    /**
     * The build version stays behind the sign-in.
     *
     * <p>
     * This is the one placement rule that is a security decision rather than a product one, and it
     * is the only absence {@code spec.md} still claims — so it is asserted rather than narrated.
     * The login page is {@code permitAll}: a version there is disclosed to anyone who can reach the
     * host, which is a materially different exposure from showing it to a signed-in user. The same
     * reasoning is why {@code /actuator/info} is acceptable only because nothing publishes the
     * management port.
     *
     * <p>
     * The scan is over every template reachable before authentication rather than a named file,
     * because the risk arrives with a NEW pre-auth page, not with an edit to the existing one.
     */
    @Test
    public void noPreAuthenticationTemplateDisclosesTheBuildVersion() throws IOException {
        List<Path> preAuth;
        try (var files = Files.walk(Path.of("src/main/resources/templates"))) {
            preAuth = files.filter(Files::isRegularFile).filter(p -> {
                String name = p.getFileName().toString();
                return name.equals("login.html") || name.startsWith("error")
                    || p.toString().contains("/error/");
            }).sorted().toList();
        }

        assertThat(preAuth)
            .as("vacuity guard: at least the login template must be found, or this test passes "
                + "without examining anything")
            .isNotEmpty();

        for (Path template : preAuth) {
            assertThat(Files.readString(template, StandardCharsets.UTF_8))
                .as("%s is reachable before sign-in, so a build version there is disclosed to "
                    + "anyone who can reach the host — a different decision from showing it to a "
                    + "signed-in user, and one spec.md states as a guarantee", template)
                .doesNotContain("buildProperties").doesNotContain("build-version");
        }
    }

    /**
     * A throwaway repository with one commit. Signing and identity are forced off rather than
     * inherited: this machine signs commits by default, and a temp repo has no key configured, so
     * an inherited {@code commit.gpgsign=true} would fail the commit and the test would report a
     * versioning defect that is really a local git preference.
     */
    private static void initRepository(Path repo) throws Exception {
        Files.copy(RELEASE_VERSION_SCRIPT.toAbsolutePath(), repo.resolve("release-version.sh"),
            StandardCopyOption.COPY_ATTRIBUTES);
        git(repo, "init", "--quiet");
        git(repo, "add", ".");
        git(repo, "-c", "user.email=test@example.invalid", "-c", "user.name=Test", "-c",
            "commit.gpgsign=false", "-c", "tag.gpgSign=false", "commit", "--quiet", "-m", "seed");
    }

    private static void git(Path cwd, String... args) throws Exception {
        // Signing is forced off for BOTH object kinds. This machine signs by default and a temp
        // repository has no key, so an inherited commit.gpgsign OR tag.gpgSign would fail the
        // setup and report a versioning defect that is really a local git preference.
        List<String> command = new ArrayList<>(
            List.of("git", "-c", "commit.gpgsign=false", "-c", "tag.gpgSign=false"));
        command.addAll(List.of(args));
        run(cwd, command.toArray(new String[0]));
    }

    private static String runScript(Path cwd) throws Exception {
        return run(cwd, "bash", cwd.resolve("release-version.sh").toString());
    }

    private static String run(Path cwd, String... command) throws Exception {
        Process process =
            new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        assertThat(exit).as("%s failed (exit %d):%n%s", String.join(" ", command), exit, output)
            .isZero();
        return output.strip();
    }
}
