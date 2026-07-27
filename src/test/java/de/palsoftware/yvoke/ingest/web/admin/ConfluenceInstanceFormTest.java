package de.palsoftware.yvoke.ingest.web.admin;

import static org.assertj.core.api.Assertions.assertThat;

import de.palsoftware.yvoke.ingest.core.confluence.ConfluenceInstance;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The connector form is the first {@code @Valid} form post in this code base, so these tests pin
 * the convention: a record DTO that structurally cannot carry the API token, with every rejection
 * carrying a message an administrator can act on.
 */
class ConfluenceInstanceFormTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static ConfluenceInstanceForm valid() {
        return new ConfluenceInstanceForm(null, "iCC Wiki", "icc-wiki",
            "https://acme.atlassian.net", "svc@example.com", "DOCS", "12345", "public", "draft",
            "OIM - Docs", "10.0", false, true);
    }

    private static Set<String> propertiesInError(ConfluenceInstanceForm form) {
        return validator.validate(form).stream().map(ConstraintViolation::getPropertyPath)
            .map(Object::toString).collect(Collectors.toSet());
    }

    /**
     * The token must never round-trip through the form: a component that does not exist cannot be
     * bound from the request, echoed back into the HTML, or logged with the rest of the DTO.
     */
    @Test
    void theFormHasNoTokenComponentAtAll() {
        String[] components = Arrays.stream(ConfluenceInstanceForm.class.getRecordComponents())
            .map(RecordComponent::getName).toArray(String[]::new);

        assertThat(components).noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("token")
            || name.toLowerCase(Locale.ROOT).contains("secret")
            || name.toLowerCase(Locale.ROOT).contains("password"));
    }

    @Test
    void aFullyPopulatedFormIsValid() {
        assertThat(validator.validate(valid())).isEmpty();
    }

    @Test
    void everyMandatoryFieldIsRequired() {
        ConfluenceInstanceForm blank = new ConfluenceInstanceForm(null, "  ", "", " ", "", "  ", "",
            null, null, "   ", null, false, true);

        assertThat(propertiesInError(blank)).contains("name", "slug", "domain", "email", "space",
            "rootPageId", "targetCollection");
    }

    /**
     * The slug is embedded in the job kind {@code confluence-page-import:<slug>}, which the job
     * engine parses with {@code split(":")[0]} — a colon, slash or space round-trips wrong. It
     * mirrors {@code ck_confluence_instances_slug_format}, so an invalid value would otherwise
     * reach the database as a raw constraint violation.
     */
    @Test
    void theSlugMustMatchTheFormatTheDatabaseAndTheJobKindAgreeOn() {
        for (String bad : new String[] {"ICC-Wiki", "icc wiki", "icc:wiki", "-icc", "icc/wiki"}) {
            ConfluenceInstanceForm form = new ConfluenceInstanceForm(null, "iCC Wiki", bad,
                "https://acme.atlassian.net", "svc@example.com", "DOCS", "12345", null, null,
                "OIM - Docs", null, false, true);
            assertThat(propertiesInError(form)).as("slug %s must be rejected", bad)
                .contains("slug");
        }
    }

    /** Nothing checked this before: a non-numeric page id fails at the first CQL call, not here. */
    @Test
    void theRootPageIdMustBeNumeric() {
        ConfluenceInstanceForm form = new ConfluenceInstanceForm(null, "iCC Wiki", "icc-wiki",
            "https://acme.atlassian.net", "svc@example.com", "DOCS", "DOCS-12345", null, null,
            "OIM - Docs", null, false, true);

        assertThat(propertiesInError(form)).contains("rootPageId");
    }

    /**
     * Requirement 4: a stored row whose domain is not a URL still LISTS (the record canonicalizes
     * leniently on read), but saving it back must be a field error rather than a 500 out of the
     * repository's strict canonicalization.
     */
    @Test
    void aDomainThatIsNotAnAbsoluteHttpUrlIsAFieldError() {
        for (String bad : new String[] {"acme.atlassian.net", "ftp://acme.atlassian.net",
            "javascript:alert(1)", "https://"}) {
            ConfluenceInstanceForm form =
                new ConfluenceInstanceForm(null, "iCC Wiki", "icc-wiki", bad, "svc@example.com",
                    "DOCS", "12345", null, null, "OIM - Docs", null, false, true);
            assertThat(propertiesInError(form)).as("domain %s must be rejected", bad)
                .contains("domain");
        }
    }

    @Test
    void anEmailThatIsNotAnAddressIsAFieldError() {
        ConfluenceInstanceForm form =
            new ConfluenceInstanceForm(null, "iCC Wiki", "icc-wiki", "https://acme.atlassian.net",
                "not-an-email", "DOCS", "12345", null, null, "OIM - Docs", null, false, true);

        assertThat(propertiesInError(form)).contains("email");
    }

    /** Every message is flashed verbatim to an administrator, so none may be a bare field name. */
    @Test
    void everyRejectionCarriesAnActionableMessage() {
        ConfluenceInstanceForm blank = new ConfluenceInstanceForm(null, null, null, null, null,
            null, null, null, null, null, null, false, true);

        assertThat(validator.validate(blank)).isNotEmpty()
            .allSatisfy(violation -> assertThat(violation.getMessage()).hasSizeGreaterThan(10));
    }

    /** Surrounding whitespace is normalized before validation, not after it. */
    @Test
    void valuesAreTrimmedAndBlanksBecomeNull() {
        ConfluenceInstanceForm form = new ConfluenceInstanceForm(null, " iCC Wiki ", " icc-wiki ",
            " https://acme.atlassian.net ", " svc@example.com ", " DOCS ", " 12345 ", "  ", "  ",
            " OIM - Docs ", "  ", false, true);

        assertThat(validator.validate(form)).isEmpty();
        assertThat(form.name()).isEqualTo("iCC Wiki");
        assertThat(form.slug()).isEqualTo("icc-wiki");
        assertThat(form.rootPageId()).isEqualTo("12345");
        assertThat(form.includeLabels()).isNull();
        assertThat(form.targetTag()).isNull();
    }

    @Test
    void theInstanceItBuildsCarriesTheEditedFieldsAndNoCredential() {
        UUID id = UUID.randomUUID();
        ConfluenceInstance instance = new ConfluenceInstanceForm(id, "iCC Wiki", "icc-wiki",
            "https://acme.atlassian.net", "svc@example.com", "DOCS", "12345", "public", "draft",
            "OIM - Docs", "10.0", true, false).toInstance();

        assertThat(instance.id()).isEqualTo(id);
        assertThat(instance.name()).isEqualTo("iCC Wiki");
        assertThat(instance.slug()).isEqualTo("icc-wiki");
        assertThat(instance.space()).isEqualTo("DOCS");
        assertThat(instance.rootPageId()).isEqualTo("12345");
        assertThat(instance.includeLabels()).isEqualTo("public");
        assertThat(instance.excludeLabels()).isEqualTo("draft");
        assertThat(instance.targetCollection()).isEqualTo("OIM - Docs");
        assertThat(instance.targetTag()).isEqualTo("10.0");
        assertThat(instance.processAttachments()).isTrue();
        assertThat(instance.enabled()).isFalse();
        assertThat(instance.apiTokenEnc()).isNull();
        assertThat(instance.tokenKeyId()).isNull();
    }
}
