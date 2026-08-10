package de.palsoftware.yvoke.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * SEC-17. {@link ApiExceptionHandler} is the last stop for every unhandled failure raised by a
 * {@code @RestController} — the ingest API, the jobs API, the desktop-sync chat API and the MCP
 * metadata endpoints all funnel through it — so whatever it puts in the body is disclosed to
 * whoever can reach the endpoint.
 */
public class ApiExceptionHandlerTest {

    /**
     * An exception message is arbitrary third-party text. On these paths it routinely carries a
     * JDBC URL complete with credentials, a provider error body, an absolute file path, or a
     * fragment of the SQL that failed — and the callers who can trigger a 500 here include any
     * {@code ROLE_INGEST} bearer token, not just an admin. The handler must therefore report a
     * fixed string and the real diagnosis must go to the log only.
     *
     * <p>
     * The assertion is equality on the whole body, not containment. A body of
     * {@code {"error":"Internal server error: jdbc url=... password=hunter2"}} satisfies "contains
     * 'Internal server error'", so a containment check passes while the leak it exists to prevent
     * goes straight through — that is exactly how this was once missed in the sibling MVC test.
     * Equality also pins that no second key (a {@code detail}, a {@code trace}, an exception class
     * name) has been added alongside it.
     *
     * <p>
     * {@code MvcExceptionHandlerTest.restControllerErrorsStayOnTheJsonApiHandlerAndLeakNothing}
     * asserts the same rule end-to-end through MockMvc, but it does so via a hand-wired probe
     * controller and a standalone MockMvc setup, so it is really pinning advice <em>ordering</em>
     * (that a {@code @RestController} reaches this advice rather than the redirecting MVC one).
     * This test pins the handler's own contract directly, which is what survives any future change
     * to how the advices are selected.
     */
    @Test
    public void aGenericFailureIsReportedWithoutTheExceptionMessage() {
        RuntimeException leaky = new RuntimeException(
            "jdbc url=jdbc:postgresql://prod-db:5432/yvoke?user=postgres&password=hunter2");

        ResponseEntity<Map<String, Object>> response =
            new ApiExceptionHandler().handleGeneric(leaky);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).as("the 500 body must be the fixed notice and nothing else")
            .isEqualTo(Map.of("error", "Internal server error"));
    }
}
