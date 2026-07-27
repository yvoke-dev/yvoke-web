package de.palsoftware.yvoke.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/**
 * Pins the unified MVC error handling (Wave 3.6 / ARC-11 / MNT-10): browser form posts get a flash
 * error + redirect-back; everything else keeps its original behavior; raw exception detail never
 * leaks; and {@code @RestController}s stay on the JSON {@link ApiExceptionHandler} path.
 */
class MvcExceptionHandlerTest {

    private static final String HTML = "text/html,application/xhtml+xml";

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
            .andExpect(flash().attribute("error",
                org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("0xdeadbeef"))));
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

    @Test
    void restControllerErrorsStayOnTheJsonApiHandler() throws Exception {
        mvc.perform(post("/rest/boom").header("Accept", "application/json"))
            .andExpect(status().isInternalServerError())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .string(org.hamcrest.Matchers.containsString("Internal server error")));
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
    }
}
