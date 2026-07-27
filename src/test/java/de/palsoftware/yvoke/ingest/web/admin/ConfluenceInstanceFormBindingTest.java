package de.palsoftware.yvoke.ingest.web.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.ServletRequestDataBinder;

/**
 * Constructor binding of the connectors form, driven through a real
 * {@link ServletRequestDataBinder} — the layer {@code ConfluenceConnectorControllerTest} skips by
 * calling the controller method directly with a hand-built {@code BindingResult}.
 *
 * <p>
 * This is where the form's "Save Instance" button used to die. An unchecked HTML checkbox submits
 * NO parameter at all, and a record component declared as a primitive {@code boolean} cannot be
 * constructed from a missing value: the binder records a type-mismatch error instead of defaulting
 * to false. The form opens with "Process attachments" unchecked, so the very first save of a new
 * instance failed 100% of the time — and because the binding result already held an error,
 * {@code ModelAttributeMethodProcessor} skipped {@code @Valid} entirely, making every constraint on
 * the DTO dead code on that path.
 */
class ConfluenceInstanceFormBindingTest {

    /** Exactly what the browser posts, minus the two checkboxes. */
    private static MockHttpServletRequest browserPost() {
        MockHttpServletRequest request =
            new MockHttpServletRequest("POST", "/admin/connectors/confluence");
        request.addParameter("id", "");
        request.addParameter("name", "iCC Wiki");
        request.addParameter("slug", "icc-wiki");
        request.addParameter("domain", "https://acme.atlassian.net");
        request.addParameter("email", "svc@example.com");
        request.addParameter("space", "DOCS");
        request.addParameter("rootPageId", "12345");
        request.addParameter("includeLabels", "");
        request.addParameter("excludeLabels", "");
        request.addParameter("targetCollection", "OIM - Docs");
        request.addParameter("targetTag", "10.0");
        return request;
    }

    private static ServletRequestDataBinder bind(MockHttpServletRequest request) {
        ServletRequestDataBinder binder = new ServletRequestDataBinder(null, "instanceForm");
        binder.setTargetType(ResolvableType.forClass(ConfluenceInstanceForm.class));
        binder.construct(request);
        return binder;
    }

    /**
     * The regression: an absent checkbox parameter must bind to {@code false} without an error,
     * with or without the hidden field marker. The DTO defaults it, so no caller — the form, an
     * htmx fragment, a future API — can be broken by a checkbox that simply was not ticked.
     */
    @Test
    void anUncheckedCheckboxBindsToFalseInsteadOfFailingTheWholeForm() {
        ServletRequestDataBinder binder = bind(browserPost());

        BindingResult result = binder.getBindingResult();
        assertThat(result.getAllErrors()).isEmpty();

        ConfluenceInstanceForm form = (ConfluenceInstanceForm) binder.getTarget();
        assertThat(form).isNotNull();
        assertThat(form.processAttachments()).isFalse();
        assertThat(form.enabled()).isFalse();
        assertThat(form.name()).isEqualTo("iCC Wiki");
    }

    /**
     * Spring's checkbox field marker (the hidden {@code _<field>} input Thymeleaf emits for
     * {@code th:field}, added by hand here because this form binds by name) is honoured by
     * constructor binding too — {@code WebDataBinder.resolvePrefixValue} maps it to
     * {@code getEmptyValue(Boolean.class)}, i.e. FALSE. The template sends both markers, so this
     * pins that the HTML-side contract still resolves.
     */
    @Test
    void theHiddenFieldMarkerAlsoResolvesAnUncheckedBoxToFalse() {
        MockHttpServletRequest request = browserPost();
        request.addParameter("_processAttachments", "on");
        request.addParameter("_enabled", "on");

        ServletRequestDataBinder binder = bind(request);

        assertThat(binder.getBindingResult().getAllErrors()).isEmpty();
        ConfluenceInstanceForm form = (ConfluenceInstanceForm) binder.getTarget();
        assertThat(form.processAttachments()).isFalse();
        assertThat(form.enabled()).isFalse();
    }

    /** The other half of the pair: a ticked box still binds to true. */
    @Test
    void aCheckedCheckboxBindsToTrue() {
        MockHttpServletRequest request = browserPost();
        request.addParameter("_processAttachments", "on");
        request.addParameter("processAttachments", "true");
        request.addParameter("_enabled", "on");
        request.addParameter("enabled", "true");

        ServletRequestDataBinder binder = bind(request);

        assertThat(binder.getBindingResult().getAllErrors()).isEmpty();
        ConfluenceInstanceForm form = (ConfluenceInstanceForm) binder.getTarget();
        assertThat(form.processAttachments()).isTrue();
        assertThat(form.enabled()).isTrue();
    }

    /**
     * A blank optional text field still normalizes to null through the compact constructor, so the
     * binder and the hand-constructed DTO agree.
     */
    @Test
    void blankOptionalFieldsBecomeNullThroughTheBinderToo() {
        ConfluenceInstanceForm form = (ConfluenceInstanceForm) bind(browserPost()).getTarget();

        assertThat(form.includeLabels()).isNull();
        assertThat(form.excludeLabels()).isNull();
        assertThat(form.id()).isNull();
    }
}
