package de.palsoftware.yvoke.chat.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.rag.core.model.AgenticChatContext;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SubmitReviewTool} — the reviewer's verdict sink + halt signal. If the
 * {@code approved} coercion or the halt flag breaks, a flagged answer could ship as approved or the
 * reviewer loop could never terminate (runaway token spend). The verdict is captured into a
 * 1-element array, mirroring OrchestrationService's usage.
 */
class SubmitReviewToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SubmitReviewTool toolCapturing(Verdict[] holder) {
        return new SubmitReviewTool(objectMapper, v -> holder[0] = v);
    }

    @Test
    void approvedTrue_capturesApprovedVerdict_returnsRecorded() {
        Verdict[] holder = new Verdict[1];
        SubmitReviewTool tool = toolCapturing(holder);

        String out = tool.call("{\"approved\":true,\"feedback\":\"looks good\"}");

        assertThat(out).isEqualTo("Verdict recorded.");
        assertThat(holder[0]).isNotNull();
        assertThat(holder[0].approved()).isTrue();
        assertThat(holder[0].feedback()).isEqualTo("looks good");
        assertThat(holder[0].unsupportedClaims()).isEmpty();
    }

    @Test
    void approvedFalse_capturesRejectionWithClaims() {
        Verdict[] holder = new Verdict[1];
        SubmitReviewTool tool = toolCapturing(holder);

        tool.call("{\"approved\":false,\"feedback\":\"missing citation\","
            + "\"unsupported_claims\":[\"claim 1\",\"claim 2\"]}");

        assertThat(holder[0].approved()).isFalse();
        assertThat(holder[0].feedback()).isEqualTo("missing citation");
        assertThat(holder[0].unsupportedClaims()).containsExactly("claim 1", "claim 2");
    }

    @Test
    void approvedMissing_defaultsToFalse() {
        Verdict[] holder = new Verdict[1];
        SubmitReviewTool tool = toolCapturing(holder);

        tool.call("{\"feedback\":\"n/a\"}"); // no "approved" key

        assertThat(holder[0].approved()).isFalse();
    }

    @Test
    void explicitNullUnsupportedClaims_coercedToEmptyList() {
        Verdict[] holder = new Verdict[1];
        SubmitReviewTool tool = toolCapturing(holder);

        tool.call("{\"approved\":false,\"feedback\":\"x\",\"unsupported_claims\":null}");

        assertThat(holder[0].unsupportedClaims()).isNotNull().isEmpty();
    }

    @Test
    void callWithContext_setsHaltRequested() {
        Verdict[] holder = new Verdict[1];
        SubmitReviewTool tool = toolCapturing(holder);
        AgenticChatContext ctx = new AgenticChatContext();

        String out = tool.callWithContext("{\"approved\":true,\"feedback\":\"ok\"}", ctx);

        assertThat(out).isEqualTo("Verdict recorded.");
        assertThat(ctx.isHaltRequested()).isTrue();
        assertThat(holder[0].approved()).isTrue();
    }

    @Test
    void call_withoutContext_stillDeliversVerdict() {
        Verdict[] holder = new Verdict[1];
        SubmitReviewTool tool = toolCapturing(holder);

        String out = tool.call("{\"approved\":true,\"feedback\":\"ok\"}"); // context == null path

        assertThat(out).isEqualTo("Verdict recorded.");
        assertThat(holder[0]).isNotNull();
    }

    @Test
    void malformedJson_returnsParseError_doesNotHaltOrDeliver() {
        Verdict[] holder = new Verdict[1];
        SubmitReviewTool tool = toolCapturing(holder);
        AgenticChatContext ctx = new AgenticChatContext();

        String out = tool.callWithContext("garbage{", ctx);

        assertThat(out).startsWith("Error parsing arguments:");
        assertThat(holder[0]).isNull(); // onVerdict never invoked
        assertThat(ctx.isHaltRequested()).isFalse(); // halt not set on failure
    }
}
