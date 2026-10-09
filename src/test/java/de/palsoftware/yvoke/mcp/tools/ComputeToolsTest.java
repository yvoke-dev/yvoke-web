package de.palsoftware.yvoke.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ComputeToolsTest {

    private ComputeTools computeTools;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        computeTools = new ComputeTools(objectMapper);
    }

    @Test
    void calculate_simpleArithmetic() throws Exception {
        String json = computeTools.calculate("36 + 10 + 5");
        JsonNode node = objectMapper.readTree(json);
        assertThat(node.get("expression").asText()).isEqualTo("36 + 10 + 5");
        assertThat(node.get("result").asDouble()).isEqualTo(51.0);
    }

    @Test
    void calculate_operatorPrecedence() throws Exception {
        String json = computeTools.calculate("2 + 3 * 4");
        JsonNode node = objectMapper.readTree(json);
        assertThat(node.get("result").asDouble()).isEqualTo(14.0);

        String jsonWithParens = computeTools.calculate("(2 + 3) * 4");
        JsonNode nodeParens = objectMapper.readTree(jsonWithParens);
        assertThat(nodeParens.get("result").asDouble()).isEqualTo(20.0);
    }

    @Test
    void calculate_powerOperator() throws Exception {
        String jsonCaret = computeTools.calculate("2 ^ 3");
        assertThat(objectMapper.readTree(jsonCaret).get("result").asDouble()).isEqualTo(8.0);

        String jsonStars = computeTools.calculate("2 ** 3");
        assertThat(objectMapper.readTree(jsonStars).get("result").asDouble()).isEqualTo(8.0);

        // Right-associative: 2 ^ (3 ^ 2) = 2 ^ 9 = 512
        String jsonRightAssoc = computeTools.calculate("2 ^ 3 ^ 2");
        assertThat(objectMapper.readTree(jsonRightAssoc).get("result").asDouble()).isEqualTo(512.0);
    }

    @Test
    void calculate_functionsAndConstants() throws Exception {
        String jsonAbs = computeTools.calculate("abs(-42)");
        assertThat(objectMapper.readTree(jsonAbs).get("result").asDouble()).isEqualTo(42.0);

        String jsonSqrt = computeTools.calculate("sqrt(144)");
        assertThat(objectMapper.readTree(jsonSqrt).get("result").asDouble()).isEqualTo(12.0);

        String jsonMinMax = computeTools.calculate("min(10, max(3, 7))");
        assertThat(objectMapper.readTree(jsonMinMax).get("result").asDouble()).isEqualTo(7.0);

        String jsonPi = computeTools.calculate("round(pi * 100) / 100");
        assertThat(objectMapper.readTree(jsonPi).get("result").asDouble()).isEqualTo(3.14);
    }

    @Test
    void calculate_unaryOperatorsAndScientificNotation() throws Exception {
        String json = computeTools.calculate("-5 + 1e2");
        assertThat(objectMapper.readTree(json).get("result").asDouble()).isEqualTo(95.0);
    }

    @Test
    void calculate_errors() {
        assertThat(computeTools.calculate(null))
            .contains("calculate error: expression must not be null or blank");
        assertThat(computeTools.calculate("   "))
            .contains("calculate error: expression must not be null or blank");
        assertThat(computeTools.calculate("10 / 0")).contains("calculate error: division by zero");
        assertThat(computeTools.calculate("unknownFunc(1)"))
            .contains("calculate error: unknown function: unknownfunc");
        assertThat(computeTools.calculate("2 + (3"))
            .contains("calculate error: missing closing parenthesis");
        assertThat(computeTools.calculate("bad@char"))
            .contains("calculate error: unexpected character");
    }

    @Test
    void statistics_summaryValues() throws Exception {
        String json = computeTools.statistics(List.of(1.0, 2.0, 3.0, 4.0, 5.0));
        JsonNode node = objectMapper.readTree(json);
        assertThat(node.get("count").asInt()).isEqualTo(5);
        assertThat(node.get("sum").asDouble()).isEqualTo(15.0);
        assertThat(node.get("mean").asDouble()).isEqualTo(3.0);
        assertThat(node.get("median").asDouble()).isEqualTo(3.0);
        assertThat(node.get("min").asDouble()).isEqualTo(1.0);
        assertThat(node.get("max").asDouble()).isEqualTo(5.0);
        assertThat(node.get("range").asDouble()).isEqualTo(4.0);
        assertThat(node.get("variance").asDouble()).isEqualTo(2.5);
        assertThat(node.get("stdev").asDouble()).isCloseTo(Math.sqrt(2.5), Offset.offset(0.0001));
    }

    @Test
    void statistics_evenCountMedian() throws Exception {
        String json = computeTools.statistics(List.of(10.0, 20.0, 30.0, 40.0));
        JsonNode node = objectMapper.readTree(json);
        assertThat(node.get("median").asDouble()).isEqualTo(25.0);
    }

    @Test
    void statistics_emptyList() throws Exception {
        String json = computeTools.statistics(List.of());
        JsonNode node = objectMapper.readTree(json);
        assertThat(node.get("count").asInt()).isEqualTo(0);
        assertThat(node.get("sum").asDouble()).isEqualTo(0.0);
        assertThat(node.get("mean").isNull()).isTrue();
    }

    @Test
    void statistics_null() {
        assertThat(computeTools.statistics(null))
            .contains("statistics error: values list must not be null");
    }

    @Test
    void dateDiff_days() throws Exception {
        String json = computeTools.dateDiff("2026-07-20", "2026-07-29", "days");
        JsonNode node = objectMapper.readTree(json);
        assertThat(node.get("difference").asDouble()).isEqualTo(9.0);
    }

    @Test
    void dateDiff_weeksAndHours() throws Exception {
        String jsonWeeks = computeTools.dateDiff("2026-07-01", "2026-07-15", "weeks");
        assertThat(objectMapper.readTree(jsonWeeks).get("difference").asDouble()).isEqualTo(2.0);

        String jsonHours =
            computeTools.dateDiff("2026-07-29T10:00:00Z", "2026-07-29T12:30:00Z", "hours");
        assertThat(objectMapper.readTree(jsonHours).get("difference").asDouble()).isEqualTo(2.5);
    }

    @Test
    void dateDiff_defaultsToDays() throws Exception {
        String json = computeTools.dateDiff("2026-07-20", "2026-07-29", null);
        JsonNode node = objectMapper.readTree(json);
        assertThat(node.get("unit").asText()).isEqualTo("days");
        assertThat(node.get("difference").asDouble()).isEqualTo(9.0);
    }

    @Test
    void dateDiff_errors() {
        assertThat(computeTools.dateDiff(null, "2026-07-29", "days")).contains("date_diff error");
        assertThat(computeTools.dateDiff("not-a-date", "2026-07-29", "days"))
            .contains("date_diff error");
        assertThat(computeTools.dateDiff("2026-07-20", "2026-07-29", "lightyears"))
            .contains("unknown unit");
    }
}
