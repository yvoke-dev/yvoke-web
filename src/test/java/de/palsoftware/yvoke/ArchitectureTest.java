package de.palsoftware.yvoke;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import de.palsoftware.yvoke.llm.core.service.AccountingLlmClient;
import de.palsoftware.yvoke.llm.core.service.LlmClient;
import de.palsoftware.yvoke.llm.core.service.ModelRoutingLlmClient;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaParameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.Assumptions;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Enforces the domain/layer package architecture (top-level = domain, second-level = api/web/core;
 * {@code shared} = cross-cutting infrastructure only). Guards against regressions of the package
 * restructure.
 *
 * <p>
 * Note on cycles: {@link #domainsMustBeFreeOfCycles()} <em>is</em> enforced, at domain-slice
 * granularity ({@code de.palsoftware.yvoke.(*)..}) — so a cycle <em>between</em> two domains fails
 * the build. This javadoc previously said the opposite ("intentionally NOT enforced yet"), left
 * over from when the rule was still a tracked cleanup item; the docs that describe it as an
 * enforced invariant were the accurate ones. Note what it does not say: cross-domain *dependencies*
 * are legitimate here (a cross-domain orchestrator such as {@code lifecycle.core} depends on
 * several domains), so only a genuine cycle is a violation — do not "fix" a one-way domain→domain
 * edge.
 */
class ArchitectureTest {

    private static final JavaClasses classes =
        new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("de.palsoftware.yvoke");

    private static final String[] DOMAIN_PACKAGES = {"..chat..", "..collection..", "..document..",
        "..ingest..", "..kg..", "..llm..", "..mcp..", "..rag..", "..tag..", "..lifecycle.."};

    /** The core architectural guarantee of the whole restructure: shared is infra-only. */
    @Test
    void sharedMustNotDependOnAnyDomain() {
        noClasses().that().resideInAPackage("..shared..").should().dependOnClassesThat()
            .resideInAnyPackage(DOMAIN_PACKAGES)
            .because(
                "shared holds only cross-cutting infrastructure; domain logic must not leak into it")
            .check(classes);
    }

    @Test
    void controllersMustResideInApiWebOrSecurity() {
        classes().that().areAnnotatedWith(Controller.class).or()
            .areAnnotatedWith(RestController.class).should()
            .resideInAnyPackage("..api..", "..web..", "..security..")
            .because(
                "controllers belong to the api/web tech layer (security is the auth infra exception)")
            .check(classes);
    }

    /** Domain core (services/repositories/entities) must not depend on our presentation layers. */
    @Test
    void coreMustNotDependOnOurPresentationLayers() {
        noClasses().that().resideInAPackage("de.palsoftware.yvoke..core..").should()
            .dependOnClassesThat()
            .resideInAnyPackage("de.palsoftware.yvoke..api..", "de.palsoftware.yvoke..web..")
            .because(
                "domain core must not depend on the api/web layers (api/web depend on core, not vice versa)")
            .check(classes);
    }

    /**
     * Authorization here is filter-chain only: there is no {@code @EnableMethodSecurity} anywhere,
     * so Spring's method-security interceptors are never registered and a {@code @PreAuthorize} (or
     * {@code @Secured} / {@code @RolesAllowed}) would be **silently inert** — the method runs for
     * everyone while the code, the review and the reader all believe it is gated. That is an
     * authorization hole created by *adding* a guard, and nothing else in the build catches it.
     *
     * <p>
     * The rule is deliberately conditional: a future change may legitimately switch method security
     * on, it just has to add {@code @EnableMethodSecurity} in the same change.
     */
    @Test
    void methodSecurityAnnotationsRequireMethodSecurityToBeEnabled() {
        boolean enabled =
            classes.stream().anyMatch(c -> c.isAnnotatedWith(EnableMethodSecurity.class));
        Assumptions.assumeFalse(enabled,
            "method security is enabled, so these annotations are honoured and the rule does not apply");

        for (String annotation : new String[] {
            "org.springframework.security.access.prepost.PreAuthorize",
            "org.springframework.security.access.prepost.PostAuthorize",
            "org.springframework.security.access.annotation.Secured",
            "jakarta.annotation.security.RolesAllowed"}) {
            String reason = "there is no @EnableMethodSecurity, so " + annotation
                + " is silently inert — express the rule as a filter-chain matcher in SecurityConfig"
                + " or an explicit guard (see PrivilegedJobKindGuard), or enable method security";
            ArchRuleDefinition.noMethods().should().beAnnotatedWith(annotation).because(reason)
                .allowEmptyShould(true).check(classes);
            noClasses().should().beAnnotatedWith(annotation).because(reason).allowEmptyShould(true)
                .check(classes);
        }
    }

    @Test
    void domainsMustBeFreeOfCycles() {
        SlicesRuleDefinition.slices().matching("de.palsoftware.yvoke.(*)..").should()
            .beFreeOfCycles().because("domain packages should not have cyclic dependencies")
            .check(classes);
    }

    /** Method names that write. Deliberately broad — a read path never needs any of them. */
    private static final Pattern MUTATING =
        Pattern.compile("^(save|insert|update|upsert|delete|create|import|purge|remove|clear"
            + "|consolidate|enqueue|touch|append)[A-Z].*|^(save|insert|update|upsert|delete|create"
            + "|import|purge|remove|clear|consolidate|enqueue|touch|append)$");

    /**
     * MCP tools are READ-ONLY over the corpus, and nothing in the code enforces that on its own.
     *
     * <p>
     * The MCP surface is reachable by any signed-in user from an external AI client, with no
     * per-area restriction — access control is the sign-in, not the content. Every tool today only
     * reads, but a tool is a small class with a repository already injected, so adding a write is a
     * two-line change that no reviewer would necessarily catch and no other test would fail on. The
     * read-only property is the whole security argument for exposing the corpus this way, so it is
     * asserted structurally rather than left as prose.
     *
     * <p>
     * Retrieval telemetry is not a counter-example: {@code search_corpus} reaches it through
     * {@code HybridSearch}, so the write is not a call made BY a class in {@code ..mcp..}.
     */
    @Test
    void mcpToolsMustNotCallMutatingRepositoryMethods() {
        noClasses().that().resideInAPackage("..mcp..").should()
            .callMethodWhere(new DescribedPredicate<JavaMethodCall>(
                "a mutating method on a de.palsoftware.yvoke type") {
                @Override
                public boolean test(JavaMethodCall call) {
                    // Scoped to the types that actually reach the corpus. A bare method-name match
                    // is too broad: LlmCallContextHolder.clear() resets a ThreadLocal for cost
                    // attribution and is not a write to anything.
                    String owner = call.getTargetOwner().getSimpleName();
                    boolean touchesCorpus = owner.endsWith("Repository")
                        || owner.endsWith("Service") || owner.endsWith("Consolidator");
                    return call.getTargetOwner().getPackageName().startsWith("de.palsoftware.yvoke")
                        && touchesCorpus && MUTATING.matcher(call.getTarget().getName()).matches();
                }
            })
            .because("MCP tools expose the corpus read-only to external AI clients; a tool that "
                + "writes would let any signed-in user mutate shared content from outside the app")
            .check(classes);
    }

    /** Matches a ':' inside a "${...}" placeholder — i.e. exactly the inline-default form. */
    private static final Pattern INLINE_PLACEHOLDER_DEFAULT = Pattern.compile("\\$\\{[^{}:]*:");

    /**
     * No inline {@code @Value} defaults: a configuration default lives in {@code application.yml}
     * and nowhere else (CLAUDE.md section 1).
     *
     * <p>
     * The rule reads like tidiness and is not. A default written into the annotation is a second,
     * invisible source of truth for the same key, and Spring resolves it in silence: rename or
     * remove the property in {@code application.yml} and the application still starts, still logs
     * nothing, and runs on a number that exists only inside a Java file — while the yml, which is
     * what every operator, every deployment manifest and every reviewer actually reads, has stopped
     * describing the running configuration. Every {@code @Value} site in this codebase is clean
     * today, so the yml is a complete inventory of what is tunable; a single inline default ends
     * that property, and the next one is then unremarkable.
     *
     * <p>
     * It is also the wrong failure mode for this application in particular. The values behind these
     * placeholders include {@code app.security.secret-key} and {@code secret-salt} (SEC-15),
     * {@code app.security.mock} (SEC-09), {@code app.rate-limit.enabled} (SEC-03),
     * {@code app.retrieval.max-limit} — which bounds the single un-chunked rerank request — and
     * {@code app.ai.kg.max-tokens}. For all of those, "carry on with a hardcoded fallback" is
     * precisely what must not happen: a missing security or budget property has to stop the context
     * from starting, loudly, at deploy time. An inline default converts that into a silent
     * degradation discovered weeks later, and in the {@code secret-key} case it would defeat the
     * fail-closed startup check that exists specifically to prevent it.
     *
     * <p>
     * Nothing else can catch this: it compiles, it starts, it warns about nothing, and a
     * {@code @SpringBootTest} passes because the property is present in the test's own yml. The
     * check covers fields, methods and — the form this codebase overwhelmingly uses, and the one a
     * casual text search over the sources is most likely to miss — constructor and method
     * <em>parameters</em>.
     */
    @Test
    void valueAnnotationsMustNotCarryInlineDefaults() {
        List<String> violations = new ArrayList<>();
        for (JavaClass clazz : classes) {
            for (JavaField field : clazz.getFields()) {
                collectInlineValueDefaults(field.getAnnotations(), field.getFullName(), violations);
            }
            for (JavaCodeUnit codeUnit : clazz.getCodeUnits()) {
                collectInlineValueDefaults(codeUnit.getAnnotations(), codeUnit.getFullName(),
                    violations);
                for (JavaParameter parameter : codeUnit.getParameters()) {
                    collectInlineValueDefaults(parameter.getAnnotations(),
                        codeUnit.getFullName() + " parameter #" + parameter.getIndex(), violations);
                }
            }
        }

        assertThat(violations)
            .as("@Value must reference a property that application.yml defines; an inline ':'"
                + " default is a second, invisible source of truth that keeps the application"
                + " running on a value the yml no longer contains")
            .isEmpty();
    }

    private static void collectInlineValueDefaults(Set<? extends JavaAnnotation<?>> annotations,
        String location, List<String> violations) {
        for (JavaAnnotation<?> annotation : annotations) {
            if (!annotation.getRawType().isEquivalentTo(Value.class)) {
                continue;
            }
            String placeholder = String.valueOf(annotation.get("value").orElse(""));
            if (INLINE_PLACEHOLDER_DEFAULT.matcher(placeholder).find()) {
                violations.add(location + " -> " + placeholder);
            }
        }
    }

    /**
     * Every LLM call must pass through the accounting seam, or it is invisible and unbilled.
     *
     * <p>
     * {@code AccountingLlmClient} is the {@code @Primary}
     * {@link de.palsoftware.yvoke.llm.core.service.LlmClient} and the single place cost, token
     * usage and gateway cache status are recorded. Injecting a concrete provider client instead
     * still compiles, still answers, and silently produces calls that appear in no ledger — so
     * spend reports understate reality with nothing to indicate it. Depending on the interface
     * keeps the decorator in the path.
     *
     * <p>
     * The rule is scoped to outside {@code ..llm..} because the provider clients and the decorator
     * that wraps them legitimately live there.
     */
    @Test
    void providerClientsMustOnlyBeReachedThroughTheAccountingSeam() {
        noClasses().that().resideOutsideOfPackage("de.palsoftware.yvoke.llm..").should()
            .dependOnClassesThat(PROVIDER_CLIENT)
            .because("every call must go through the @Primary AccountingLlmClient; bypassing it "
                + "produces LLM calls that are never logged, priced or shown on the cost dashboard")
            .check(classes);
    }

    /**
     * Every concrete {@link LlmClient} in the provider package except the two seams themselves.
     *
     * <p>
     * Derived rather than listed, because the listed form had already failed silently. It named the
     * clients with the regex {@code (Gemini|CloudflareGemini|OpenRouter|AzureOpenAi)LlmClient}, and
     * {@code haveNameMatching} is a FULL match — so an alternative had to be followed immediately
     * by {@code LlmClient}, and {@code AzureOpenAiResponsesLlmClient} matched none of them. That
     * was the one client actually wired for Azure, so the rule guarding the billing seam covered
     * every client except the one in use, and a bypass compiled green. Naming classes is what made
     * the omission possible; a predicate over the type hierarchy cannot omit a client that does not
     * exist yet.
     */
    private static final DescribedPredicate<JavaClass> PROVIDER_CLIENT =
        new DescribedPredicate<>("a provider LlmClient outside the accounting seam") {
            @Override
            public boolean test(JavaClass candidate) {
                return candidate.getPackageName().equals("de.palsoftware.yvoke.llm.core.service")
                    && candidate.isAssignableTo(LlmClient.class)
                    && !candidate.getName().equals(LlmClient.class.getName())
                    && !candidate.getName().equals(AccountingLlmClient.class.getName())
                    && !candidate.getName().equals(ModelRoutingLlmClient.class.getName());
            }
        };
}
