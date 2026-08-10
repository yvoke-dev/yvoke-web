package de.palsoftware.yvoke.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import de.palsoftware.yvoke.shared.api.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.security.access.AccessDeniedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import org.hamcrest.Matchers;

/**
 * Pins the unified MVC error handling (Wave 3.6 / ARC-11 / MNT-10): browser form posts get a flash
 * error + redirect-back; everything else keeps its original behavior; raw exception detail never
 * leaks; and {@code @RestController}s stay on the JSON {@link ApiExceptionHandler} path.
 */
class MvcExceptionHandlerTest {

    private static final String HTML = "text/html,application/xhtml+xml";

    /**
     * {@code handleUnexpected} catches {@code Exception}, so it catches
     * {@code AccessDeniedException} too, and the {@code instanceof} re-throw is the only thing that
     * hands the denial back to Spring Security's {@code ExceptionTranslationFilter}. Delete that
     * one clause — it reads like a redundant special case, since the very next condition already
     * lets most requests through — and a denied browser form post becomes a 302 to the previous
     * page with the flash text "Something went wrong while processing your request." The user is
     * told a transient failure occurred and invited to retry; the response carries a 3xx, so
     * nothing in front of the app records an authorization failure, and the 403 page (and any
     * denial handling behind it) is never reached. An authorization decision has silently become
     * indistinguishable from a database blip.
     *
     * <p>
     * Nothing executes that clause today. {@code MvcExceptionHandlerTest}'s only access-denied
     * probe is {@code /rest/denied} on a {@code @RestController}, which {@code ApiExceptionHandler}
     * claims first at {@code HIGHEST_PRECEDENCE}, so this advice never sees it; the plain
     * {@code @Controller} probe has no access-denied endpoint at all. The handler is therefore
     * driven directly here rather than through MockMvc — routing an {@code AccessDeniedException}
     * through {@code standaloneSetup} would only prove which advice won, not what this branch does.
     *
     * <p>
     * The second stanza is what makes the first discriminating: the SAME request object, which is
     * shaped as a classic browser form post (POST + {@code Accept: text/html} + a Referer), really
     * is redirected when the exception is an ordinary one. Without it, an assertion that
     * "access-denied propagates" would also pass if {@code isNavigationalFormPost} had simply
     * stopped matching, which would delete the flash-redirect behaviour every admin form relies on.
     */
    @Test
    void anAccessDeniedOnABrowserFormPostIsRethrownRatherThanBecomingAFlashRedirect()
        throws Exception {
        MvcExceptionHandler handler = new MvcExceptionHandler();

        MockHttpServletRequest formPost =
            new MockHttpServletRequest("POST", "/admin/collections/42/delete");
        formPost.addHeader("Accept", HTML);
        formPost.addHeader("Referer", "http://localhost/admin/collections");

        AccessDeniedException denied =
            new AccessDeniedException("no write access to collection 42");
        RedirectAttributesModelMap deniedFlash = new RedirectAttributesModelMap();

        assertThatThrownBy(() -> handler.handleUnexpected(denied, formPost, deniedFlash))
            .as("an authorization failure must reach Spring Security, not be converted into a "
                + "friendly 'try again' redirect that hides it")
            .isSameAs(denied);
        assertThat(deniedFlash.getFlashAttributes())
            .as("a denial must not leave a flash message behind either").isEmpty();

        RedirectAttributesModelMap unexpectedFlash = new RedirectAttributesModelMap();
        assertThat(handler.handleUnexpected(new IllegalStateException("db exploded at 0xdeadbeef"),
            formPost, unexpectedFlash))
            .as("this exact request shape IS the one that gets redirected — otherwise the "
                + "assertion above would pass for the wrong reason")
            .isEqualTo("redirect:/admin/collections");
        assertThat(unexpectedFlash.getFlashAttributes().get("error"))
            .isEqualTo(MvcExceptionHandler.GENERIC_ERROR_MESSAGE);
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController(), new RestProbeController())
            .setControllerAdvice(new ApiExceptionHandler(), new MvcExceptionHandler()).build();
    }

    @Test
    void validationFormPostRedirectsBackWithItsOwnMessage() throws Exception {
        mvc.perform(post("/probe/validation").header("Accept", HTML).header("Referer",
            "http://localhost/admin/collections")).andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/collections"))
            .andExpect(flash().attribute("error", "Name already exists"));
    }

    @Test
    void unexpectedFormPostRedirectsBackWithGenericMessageAndNeverLeaksDetail() throws Exception {
        mvc.perform(post("/probe/unexpected").header("Accept", HTML).header("Referer",
            "http://localhost/admin/pricing")).andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/pricing"))
            .andExpect(flash().attribute("error", MvcExceptionHandler.GENERIC_ERROR_MESSAGE))
            .andExpect(
                flash().attribute("error", Matchers.not(Matchers.containsString("0xdeadbeef"))));
    }

    @Test
    void refererQueryStringIsPreservedOnRedirect() throws Exception {
        mvc.perform(post("/probe/validation").header("Accept", HTML).header("Referer",
            "http://localhost/admin/json-objects?collection=X&tag=Y"))
            .andExpect(redirectedUrl("/admin/json-objects?collection=X&tag=Y"));
    }

    @Test
    void externalRefererIsReducedToSameOriginPath() throws Exception {
        // Open-redirect guard: only the path+query of the Referer is honored, never its host.
        mvc.perform(post("/probe/unexpected").header("Accept", HTML).header("Referer",
            "https://evil.example.com/admin/pwn?z=1")).andExpect(redirectedUrl("/admin/pwn?z=1"));
    }

    @Test
    void missingRefererFallsBackToAdminLanding() throws Exception {
        mvc.perform(post("/probe/unexpected").header("Accept", HTML))
            .andExpect(redirectedUrl("/admin"));
    }

    @Test
    void responseStatusExceptionStaysARealStatusCodeNotARedirect() throws Exception {
        mvc.perform(post("/probe/not-found").header("Accept", HTML).header("Referer",
            "http://localhost/admin/documents")).andExpect(status().isNotFound());
    }

    @Test
    void getViewRenderIsNotHijackedIntoARedirect() {
        // A GET that fails must keep propagating (its normal 500 page), not become a redirect.
        assertThatThrownBy(() -> mvc.perform(get("/probe/get-boom").header("Accept", HTML)))
            .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void htmxPostIsNotHijackedIntoARedirect() {
        assertThatThrownBy(() -> mvc
            .perform(post("/probe/unexpected").header("Accept", HTML).header("HX-Request", "true")))
            .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void fetchPostWithoutHtmlAcceptIsNotHijackedIntoARedirect() {
        // e.g. stopGeneration: a bare fetch() sends Accept: */*, expects a status, not a redirect.
        assertThatThrownBy(() -> mvc.perform(post("/probe/unexpected").header("Accept", "*/*")))
            .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    /**
     * An {@code AccessDeniedException} raised INSIDE a handler — an ownership check in a service,
     * an explicit guard called from the controller — is the shape this codebase relies on for
     * everything the filter chain cannot decide up front (there is no method security here; the
     * annotations are inert and banned). By the time it is thrown the response is the application's
     * to shape, and the two advices want opposite things: {@code MvcExceptionHandler} re-throws it
     * so Spring Security can render the browser 403 page, which is right for a {@code @Controller}
     * and wrong for a {@code @RestController}, whose caller needs a machine-readable status.
     *
     * <p>
     * Delete {@code ApiExceptionHandler.handleAccessDenied} and the fall-through is not the
     * framework default — {@code ApiExceptionHandler}'s own catch-all {@code Exception} branch
     * claims it first and returns 500 {@code Internal server error}. A denial then reads to every
     * non-browser client as "the server is broken", which is the one status that invites a retry
     * loop against an endpoint that will never authorize, and it erases the distinction an operator
     * needs from the logs: an authorization refusal becomes indistinguishable from a crash.
     *
     * <p>
     * {@code restControllerErrorsStayOnTheJsonApiHandlerAndLeakNothing} exercises only that generic
     * branch, so it stays green through the whole regression. The body equality also pins SEC-17
     * here: the denial message is authored next to the data being protected and routinely names it
     * ({@code "no write access to collection 42"}), so it must not travel to the caller.
     */
    @Test
    void anAccessDeniedInsideARestControllerStaysA403JsonBody() throws Exception {
        String body = mvc.perform(post("/rest/denied").header("Accept", "application/json"))
            .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();

        assertThat(body).isEqualTo("{\"error\":\"Access denied\"}");
        assertThat(body).doesNotContain("no write access to collection 42");
    }

    /**
     * SEC-17: the JSON body must be the generic message and NOTHING else.
     *
     * <p>
     * This previously asserted only that the body CONTAINED "Internal server error", which a body
     * of {@code {"error":"Internal server error: secret detail 0xdeadbeef"}} satisfies — so the
     * assertion passed while the leak it exists to prevent went straight through. An exception
     * message is arbitrary third-party text: provider responses, SQL fragments, connection strings
     * and file paths all reach it, and any of them in a response body is disclosed to whoever can
     * call the endpoint. The equality assertion is the whole point; containment is not enough.
     */
    @Test
    void restControllerErrorsStayOnTheJsonApiHandlerAndLeakNothing() throws Exception {
        String body = mvc.perform(post("/rest/boom").header("Accept", "application/json"))
            .andExpect(status().isInternalServerError()).andReturn().getResponse()
            .getContentAsString();

        assertThat(body).isEqualTo("{\"error\":\"Internal server error\"}");
        assertThat(body).doesNotContain("0xdeadbeef").doesNotContain("secret detail")
            .doesNotContain("IllegalStateException");
    }

    /**
     * A {@code @Valid} rejection on a REST body arrives as {@code MethodArgumentNotValidException},
     * and an {@code @ExceptionHandler} match beats Spring's default resolver — so the moment
     * {@code ApiExceptionHandler} declares a catch-all {@code Exception} branch (it does, and it
     * must, for SEC-17), the framework's built-in 400 stops happening and a malformed payload
     * becomes a 500 unless this specific handler exists. That inverts the meaning of the response
     * for every non-browser caller of {@code /api/**}: a 400 says "fix your payload" and is not
     * retried, while a 500 says "the server failed" and is exactly what a desktop sync client or an
     * ingest script retries with backoff — so one bad field turns into an endless hammering of the
     * endpoint while the operator watches server errors accumulate for a request that was never
     * going to succeed.
     *
     * <p>
     * The field name is the other half of the contract. Validation here is the project's boundary
     * defence (SEC-18 bounds on titles, roles, batch sizes), and a caller told only "invalid
     * request body" against a DTO with a dozen constrained fields cannot act on it; naming the
     * field is what makes the rejection self-correcting.
     *
     * <p>
     * Nothing existing would notice the deletion. {@code DesktopSyncDtoValidationTest} and
     * {@code EnqueueRequestTest} drive a {@code Validator} directly and never go through MVC
     * exception resolution at all, and no other test posts an invalid body at a
     * {@code @RestController} — the whole path from "constraint violated" to "status on the wire"
     * is untested without this.
     */
    @Test
    void anInvalidRequestBodyIsAFourHundredNamingTheOffendingField() throws Exception {
        String body = mvc
            .perform(post("/rest/validated").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"   \"}").header("Accept", "application/json"))
            .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();

        assertThat(body).as("the caller must be told WHICH field was rejected")
            .startsWith("{\"error\":\"name ");
        assertThat(body).as("a bad payload is the caller's fault, not a server failure")
            .doesNotContain("Internal server error");
    }

    /**
     * A non-integer paging parameter is the most ordinary bad request an admin page can receive,
     * and it is the only failure shape in this class raised <em>before</em> the handler body runs.
     * Every admin list binds {@code page}/{@code size} as {@code int} and the RAG admin page binds
     * {@code limit} as {@code Integer}, so {@code ?page=abc} from a hand-edited URL, a stale
     * bookmark or a crawler produces a {@code MethodArgumentTypeMismatchException} during argument
     * resolution.
     *
     * <p>
     * That exception matches {@code handleUnexpected} ({@code @ExceptionHandler(Exception.class)}),
     * and the ONLY reason the caller sees a 400 is that {@code isNavigationalFormPost} returns
     * false for a GET and the advice re-throws the original — which
     * {@code ExceptionHandlerExceptionResolver} treats as "continue with default processing", so
     * {@code DefaultHandlerExceptionResolver.handleTypeMismatch} maps it to 400. Widen that guard
     * (drop the state-changing-method check, or simplify the condition to
     * {@code ex instanceof AccessDeniedException}) and the GET is swallowed into a
     * flash-and-redirect instead: the browser is bounced back to its {@code Referer} — the page
     * that produced the bad link — or, with no {@code Referer}, to {@code /admin}, so a rejected
     * request renders a <em>different page</em> with a 302 and no 400 anywhere. A monitored
     * endpoint stops reporting client errors entirely, and a link with a permanently bad parameter
     * becomes a silent bounce rather than a diagnosable status.
     *
     * <p>
     * The REST half is asserted alongside because the same input inverts there, and that inversion
     * is invisible from either class on its own: {@code ApiExceptionHandler} is
     * {@code @Order(HIGHEST_PRECEDENCE)} with a catch-all {@code Exception} branch, and an
     * {@code @ExceptionHandler} match beats Spring's default resolver — so a non-integer
     * {@code limit} on a {@code @RestController} is reported as 500 "Internal server error", not
     * 400 ({@code MethodArgumentTypeMismatchException} extends {@code TypeMismatchException} →
     * {@code BeansException}, so {@code handleBadRequest(IllegalArgumentException)} never matches).
     * That is exactly the failure {@code handleInvalidBody} exists to prevent for {@code @Valid}
     * bodies, and nothing prevents it for type conversion: a desktop sync client or ingest script
     * reads 500 as "the server failed" and retries with backoff forever a request that can never
     * succeed. Pinning it means the day someone adds a {@code MethodArgumentTypeMismatchException}
     * handler to make it a 400, that improvement is a deliberate, visible change to this assertion
     * rather than an accident.
     *
     * <p>
     * None of the neighbouring tests can notice either half: they all raise their exception from
     * inside the handler body (form posts, htmx, a GET view render,
     * {@code ResponseStatusException}, {@code AccessDeniedException}) or from bean validation on a
     * request body. The argument-resolution path — and with it the re-throw fall-through to the
     * framework's default resolver — is otherwise never exercised. The probes below are separate
     * controllers with their own {@code MockMvc} so the shared {@code setUp()} harness is left
     * exactly as it is.
     */
    @Test
    void aNonIntegerPagingParamIsAFourHundredOnAnMvcPageButAFiveHundredOnTheJsonApi()
        throws Exception {
        MockMvc paged = MockMvcBuilders
            .standaloneSetup(new PagedProbeController(), new RestPagedProbeController())
            .setControllerAdvice(new ApiExceptionHandler(), new MvcExceptionHandler()).build();

        paged.perform(get("/probe/paged").param("page", "abc").header("Accept", HTML))
            .andExpect(status().isBadRequest()).andExpect(header().doesNotExist("Location"));

        String body = paged
            .perform(get("/rest/paged").param("limit", "abc").header("Accept", "application/json"))
            .andExpect(status().isInternalServerError()).andReturn().getResponse()
            .getContentAsString();

        assertThat(body)
            .as("documented asymmetry: the JSON catch-all claims the type mismatch before the "
                + "framework's 400 can happen — change this only on purpose")
            .isEqualTo("{\"error\":\"Internal server error\"}");
    }

    /**
     * A {@link ResponseStatusException} is how every deliberate 4xx on the JSON surface is raised —
     * a 404 from an {@code orElseThrow} on a deleted document, a 409 on a duplicate ingest,
     * {@code UserArgumentResolver}'s 401 — and {@code ApiExceptionHandler} is
     * {@code @Order(HIGHEST_PRECEDENCE)} with a catch-all {@code Exception} branch, so an
     * {@code @ExceptionHandler} match beats Spring's {@code ResponseStatusExceptionResolver}. The
     * framework's default therefore does NOT back this up: the moment {@code handleResponseStatus}
     * stops returning {@code ex.getStatusCode()}, every one of those intentional client errors
     * collapses into 500 "Internal server error" and no test in this repository notices, because
     * {@code responseStatusExceptionStaysARealStatusCodeNotARedirect} exercises the
     * {@code @Controller} half only.
     *
     * <p>
     * What that costs is retry semantics. A 4xx says "fix your request" and is terminal; a 500 says
     * "the server broke" and is exactly what an ingest script, an MCP client or the desktop sync
     * client retries with backoff — so a request that can never succeed is hammered indefinitely
     * while the operator watches server errors accumulate for a payload nobody will ever correct.
     * The caller also loses the ability to distinguish "this document is gone" from "this instance
     * is unhealthy", which is the difference between reporting a missing id to the user and failing
     * over.
     *
     * <p>
     * The exact-body assertions carry two further contracts. SEC-17: the body must be the
     * app-authored {@code getReason()} and nothing else — never the exception's own
     * {@code getMessage()}, which prefixes the status and is arbitrary third-party text on the
     * wrapped-cause constructors. And the reason-less probe pins the
     * {@code ex.getStatusCode().toString()} fallback, without which a caller receives a body of
     * {@code null} for a status raised with no message at all. (That literal is Spring's
     * {@code HttpStatus.toString()}, {@code value + " " + name()}; if a Spring upgrade changes it,
     * this assertion is the intended place to notice.)
     *
     * <p>
     * The absent {@code Location} header pins the advice-ordering contract stated in
     * {@code ApiExceptionHandler}'s class comment: {@code @RestController} is meta-annotated with
     * {@code @Controller}, so {@code MvcExceptionHandler} also matches these probes and must not
     * claim them — its {@code handleStatus} would answer with a bare {@code sendError} and an empty
     * body, losing the JSON contract even while the status happened to survive. Both advices are
     * registered here for exactly that reason.
     */
    @Test
    void aResponseStatusExceptionFromARestControllerKeepsItsStatusAndReasonAsJson()
        throws Exception {
        MockMvc rest = MockMvcBuilders.standaloneSetup(new RestStatusProbeController())
            .setControllerAdvice(new ApiExceptionHandler(), new MvcExceptionHandler()).build();

        String notFound =
            rest.perform(post("/rest/status/not-found").header("Accept", "application/json"))
                .andExpect(status().isNotFound()).andExpect(header().doesNotExist("Location"))
                .andReturn().getResponse().getContentAsString();

        assertThat(notFound)
            .as("the deliberate status survives and the app-authored reason is the WHOLE body")
            .isEqualTo("{\"error\":\"document 7c9 is gone\"}");
        assertThat(notFound).as("a client error must never be reported as a server failure")
            .doesNotContain("Internal server error");

        String conflict =
            rest.perform(post("/rest/status/conflict").header("Accept", "application/json"))
                .andExpect(status().isConflict()).andExpect(header().doesNotExist("Location"))
                .andReturn().getResponse().getContentAsString();

        assertThat(conflict)
            .as("with no reason the status itself is reported — never a null error field")
            .isEqualTo("{\"error\":\"409 CONFLICT\"}");
    }

    @RestController
    static class RestStatusProbeController {
        @PostMapping("/rest/status/not-found")
        @ResponseBody
        String notFound() {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "document 7c9 is gone");
        }

        @PostMapping("/rest/status/conflict")
        @ResponseBody
        String conflict() {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
    }

    @Controller
    static class PagedProbeController {
        @GetMapping("/probe/paged")
        String paged(@RequestParam(defaultValue = "0") int page) {
            return "admin/list";
        }
    }

    @RestController
    static class RestPagedProbeController {
        @GetMapping("/rest/paged")
        @ResponseBody
        String paged(@RequestParam(defaultValue = "100") int limit) {
            return String.valueOf(limit);
        }
    }

    @Controller
    static class ProbeController {
        @PostMapping("/probe/validation")
        String validation() {
            throw new IllegalArgumentException("Name already exists");
        }

        @PostMapping("/probe/unexpected")
        String unexpected() {
            throw new IllegalStateException("db exploded at 0xdeadbeef");
        }

        @PostMapping("/probe/not-found")
        String notFound() {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "gone");
        }

        @GetMapping("/probe/get-boom")
        String getBoom() {
            throw new IllegalStateException("boom");
        }

        @GetMapping("/probe/paged")
        String paged(@RequestParam(defaultValue = "0") int page) {
            return "admin/list";
        }

        @PostMapping("/probe/ok")
        String ok(RedirectAttributes redirectAttributes) {
            redirectAttributes.addFlashAttribute("success", "done");
            return "redirect:/probe";
        }
    }

    @RestController
    static class RestProbeController {
        @PostMapping("/rest/boom")
        @ResponseBody
        String boom() {
            throw new IllegalStateException("secret detail 0xdeadbeef");
        }

        @PostMapping("/rest/denied")
        @ResponseBody
        String denied() {
            throw new AccessDeniedException("no write access to collection 42");
        }

        @PostMapping("/rest/validated")
        @ResponseBody
        String validated(@Valid @RequestBody ProbeBody probeBody) {
            return probeBody.name();
        }

        @GetMapping("/rest/paged")
        @ResponseBody
        String paged(@RequestParam(defaultValue = "100") int limit) {
            return String.valueOf(limit);
        }
    }

    record ProbeBody(@NotBlank String name) {}
}
