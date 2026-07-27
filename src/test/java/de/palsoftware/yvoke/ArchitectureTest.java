package de.palsoftware.yvoke;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

/**
 * Enforces the domain/layer package architecture (top-level = domain, second-level = api/web/core;
 * {@code shared} = cross-cutting infrastructure only). Guards against regressions of the package
 * restructure.
 *
 * <p>
 * Note: there are known cross-domain dependency cycles (mainly {@code *.web.admin} controllers that
 * legitimately span domains, plus a few core-to-core edges). A global "free of cycles" rule is
 * intentionally NOT enforced yet; the cleanup is tracked as a maintainability item in
 * {@code docs/review/CODE-REVIEW-FIX-PLAN.md} (Wave 3), not as a build-gating regression check.
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

    @Test
    void domainsMustBeFreeOfCycles() {
        com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices()
            .matching("de.palsoftware.yvoke.(*)..").should().beFreeOfCycles()
            .because("domain packages should not have cyclic dependencies").check(classes);
    }
}
