package de.palsoftware.yvoke.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Safe in-process computation tools for arithmetic, statistics, and date intervals. Runs entirely
 * in-process with no shell, filesystem, or network access — providing mathematical execution
 * without arbitrary code execution vulnerabilities.
 */
@Component
public class ComputeTools {

    private static final Logger log = LoggerFactory.getLogger(ComputeTools.class);

    public static final String CALCULATE_DESCRIPTION =
        "Evaluate an arithmetic expression and return the numeric result. Supports + - * / % ^ "
            + "(** for power), parentheses, functions (abs, sign, sqrt, cbrt, round, floor, ceil, "
            + "trunc, exp, ln, log, log2, log10, sin, cos, tan, asin, acos, atan, atan2, pow, "
            + "hypot, min, max) and constants (pi, e, tau). Pure math only — no variables or code.";

    public static final String EXPRESSION_PARAM_DESCRIPTION =
        "Arithmetic expression to evaluate, e.g. \"(1200 * 1.19) / 12\"";

    public static final String STATISTICS_DESCRIPTION =
        "Compute summary statistics (count, sum, mean, median, min, max, range, sample variance "
            + "and standard deviation) over a list of numbers.";

    public static final String VALUES_PARAM_DESCRIPTION = "The numeric values to summarize";

    public static final String DATE_DIFF_DESCRIPTION =
        "Compute the signed difference between two dates/times (to − from) in the requested unit "
            + "(weeks, days, hours, minutes, seconds, milliseconds). Accepts ISO 8601 strings "
            + "(e.g. '2026-07-29', '2026-07-29T10:30:00Z'). Defaults to 'days'.";

    public static final String FROM_PARAM_DESCRIPTION =
        "ISO 8601 start date/time (e.g. '2026-07-29', '2026-07-29T10:30:00Z')";

    public static final String TO_PARAM_DESCRIPTION =
        "ISO 8601 end date/time (e.g. '2026-07-30', '2026-07-30T10:30:00Z')";

    public static final String UNIT_PARAM_DESCRIPTION =
        "Optional duration unit: weeks | days | hours | minutes | seconds | milliseconds (default: days)";

    private static final int MAX_EXPRESSION_LENGTH = 1000;

    private static final Map<String, Long> UNIT_MS = Map.of("milliseconds", 1L, "seconds", 1_000L,
        "minutes", 60_000L, "hours", 3_600_000L, "days", 86_400_000L, "weeks", 604_800_000L);

    private final ObjectMapper objectMapper;

    public ComputeTools(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @McpTool(name = "calculate", description = CALCULATE_DESCRIPTION)
    @Tool(name = "calculate", description = CALCULATE_DESCRIPTION)
    public String calculate(
        @McpToolParam(description = EXPRESSION_PARAM_DESCRIPTION, required = true)
        @ToolParam(description = EXPRESSION_PARAM_DESCRIPTION, required = true) String expression) {

        if (expression == null || expression.isBlank()) {
            return "calculate error: expression must not be null or blank";
        }
        if (expression.length() > MAX_EXPRESSION_LENGTH) {
            return "calculate error: expression too long (max " + MAX_EXPRESSION_LENGTH + " chars)";
        }
        try {
            double result = safeCalculate(expression.trim());
            Map<String, Object> response = new HashMap<>();
            response.put("expression", expression.trim());
            if (result == Math.floor(result) && !Double.isInfinite(result)
                && Math.abs(result) < 1e15) {
                response.put("result", (long) result);
            } else {
                response.put("result", result);
            }
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.debug("calculate failed for expression '{}': {}", expression, e.getMessage());
            return "calculate error: " + e.getMessage();
        }
    }

    @McpTool(name = "statistics", description = STATISTICS_DESCRIPTION)
    @Tool(name = "statistics", description = STATISTICS_DESCRIPTION)
    public String statistics(@McpToolParam(description = VALUES_PARAM_DESCRIPTION, required = true)
    @ToolParam(description = VALUES_PARAM_DESCRIPTION, required = true) List<Double> values) {

        if (values == null) {
            return "statistics error: values list must not be null";
        }
        try {
            Map<String, Object> stats = computeStatistics(values);
            return objectMapper.writeValueAsString(stats);
        } catch (Exception e) {
            log.debug("statistics calculation failed: {}", e.getMessage());
            return "statistics error: " + e.getMessage();
        }
    }

    @McpTool(name = "date_diff", description = DATE_DIFF_DESCRIPTION)
    @Tool(name = "date_diff", description = DATE_DIFF_DESCRIPTION)
    public String dateDiff(
        @McpToolParam(description = FROM_PARAM_DESCRIPTION, required = true)
        @ToolParam(description = FROM_PARAM_DESCRIPTION, required = true) String from,
        @McpToolParam(description = TO_PARAM_DESCRIPTION, required = true)
        @ToolParam(description = TO_PARAM_DESCRIPTION, required = true) String to,
        @McpToolParam(description = UNIT_PARAM_DESCRIPTION, required = false)
        @ToolParam(description = UNIT_PARAM_DESCRIPTION, required = false) String unit) {

        if (from == null || from.isBlank()) {
            return "date_diff error: 'from' must not be null or blank";
        }
        if (to == null || to.isBlank()) {
            return "date_diff error: 'to' must not be null or blank";
        }
        String resolvedUnit = (unit == null || unit.isBlank()) ? "days" : unit.trim().toLowerCase();
        Long unitMultiplier = UNIT_MS.get(resolvedUnit);
        if (unitMultiplier == null) {
            return "date_diff error: unknown unit '" + resolvedUnit + "'. Supported: "
                + UNIT_MS.keySet();
        }
        try {
            long fromMs = parseToEpochMilli(from.trim());
            long toMs = parseToEpochMilli(to.trim());
            double difference = (double) (toMs - fromMs) / unitMultiplier;
            Map<String, Object> response = new HashMap<>();
            response.put("from", from.trim());
            response.put("to", to.trim());
            response.put("unit", resolvedUnit);
            response.put("difference", difference);
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.debug("date_diff failed for from='{}' to='{}': {}", from, to, e.getMessage());
            return "date_diff error: " + e.getMessage();
        }
    }

    private static long parseToEpochMilli(String dateStr) {
        try {
            return Instant.parse(dateStr).toEpochMilli();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(dateStr).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(dateStr).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(dateStr).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("unparseable date string: '" + dateStr + "'");
        }
    }

    private static Map<String, Object> computeStatistics(List<Double> values) {
        for (Double v : values) {
            if (v == null || !Double.isFinite(v)) {
                throw new IllegalArgumentException("values must all be finite numbers");
            }
        }
        int count = values.size();
        Map<String, Object> result = new HashMap<>();
        result.put("count", count);

        if (count == 0) {
            result.put("sum", 0.0);
            result.put("mean", null);
            result.put("median", null);
            result.put("min", null);
            result.put("max", null);
            result.put("range", null);
            result.put("variance", null);
            result.put("stdev", null);
            return result;
        }

        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        double mean = sum / count;

        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int mid = count / 2;
        double median =
            (count % 2 == 0) ? (sorted.get(mid - 1) + sorted.get(mid)) / 2.0 : sorted.get(mid);
        double min = sorted.get(0);
        double max = sorted.get(count - 1);

        result.put("sum", sum);
        result.put("mean", mean);
        result.put("median", median);
        result.put("min", min);
        result.put("max", max);
        result.put("range", max - min);

        if (count > 1) {
            double sumSqDiff = 0.0;
            for (double v : values) {
                sumSqDiff += Math.pow(v - mean, 2);
            }
            double variance = sumSqDiff / (count - 1);
            result.put("variance", variance);
            result.put("stdev", Math.sqrt(variance));
        } else {
            result.put("variance", null);
            result.put("stdev", null);
        }
        return result;
    }

    // ---------------------------------------------------------------------------
    // Safe Recursive-Descent Expression Parser
    // ---------------------------------------------------------------------------

    private enum TokenType {
        NUMBER, NAME, OP, LPAREN, RPAREN, COMMA
    }

    private record Token(TokenType type, double numValue, String textValue) {}

    private static final Map<String, Double> CONSTANTS =
        Map.of("pi", Math.PI, "e", Math.E, "tau", Math.PI * 2.0);

    private static double safeCalculate(String expression) {
        List<Token> tokens = tokenize(expression);
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("empty expression");
        }
        Parser parser = new Parser(tokens);
        double result = parser.parse();
        if (!Double.isFinite(result)) {
            throw new ArithmeticException("result is not a finite number (e.g. division by zero)");
        }
        return result;
    }

    private static List<Token> tokenize(String input) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int len = input.length();
        while (i < len) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (Character.isDigit(c) || c == '.') {
                int j = i;
                while (j < len && (Character.isDigit(input.charAt(j)) || input.charAt(j) == '.')) {
                    j++;
                }
                if (j < len && (input.charAt(j) == 'e' || input.charAt(j) == 'E')) {
                    j++;
                    if (j < len && (input.charAt(j) == '+' || input.charAt(j) == '-')) {
                        j++;
                    }
                    while (j < len && Character.isDigit(input.charAt(j))) {
                        j++;
                    }
                }
                String text = input.substring(i, j);
                try {
                    double val = Double.parseDouble(text);
                    if (!Double.isFinite(val)) {
                        throw new IllegalArgumentException("invalid number: " + text);
                    }
                    tokens.add(new Token(TokenType.NUMBER, val, text));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("invalid number: " + text);
                }
                i = j;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < len
                    && (Character.isLetterOrDigit(input.charAt(j)) || input.charAt(j) == '_')) {
                    j++;
                }
                tokens.add(new Token(TokenType.NAME, 0.0, input.substring(i, j).toLowerCase()));
                i = j;
                continue;
            }
            if (c == '*' && i + 1 < len && input.charAt(i + 1) == '*') {
                tokens.add(new Token(TokenType.OP, 0.0, "^"));
                i += 2;
                continue;
            }
            if ("+-*/%^".indexOf(c) >= 0) {
                tokens.add(new Token(TokenType.OP, 0.0, String.valueOf(c)));
                i++;
                continue;
            }
            if (c == '(') {
                tokens.add(new Token(TokenType.LPAREN, 0.0, "("));
                i++;
                continue;
            }
            if (c == ')') {
                tokens.add(new Token(TokenType.RPAREN, 0.0, ")"));
                i++;
                continue;
            }
            if (c == ',') {
                tokens.add(new Token(TokenType.COMMA, 0.0, ","));
                i++;
                continue;
            }
            throw new IllegalArgumentException("unexpected character: '" + c + "'");
        }
        return tokens;
    }

    private static class Parser {
        private final List<Token> tokens;
        private int pos = 0;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        double parse() {
            double result = parseExpression();
            if (pos < tokens.size()) {
                throw new IllegalArgumentException("unexpected trailing tokens");
            }
            return result;
        }

        private Token peek() {
            return pos < tokens.size() ? tokens.get(pos) : null;
        }

        private boolean isOp(String op) {
            Token t = peek();
            return t != null && t.type() == TokenType.OP && op.equals(t.textValue());
        }

        private double parseExpression() {
            double value = parseTerm();
            while (isOp("+") || isOp("-")) {
                Token op = tokens.get(pos++);
                double rhs = parseTerm();
                value = "+".equals(op.textValue()) ? value + rhs : value - rhs;
            }
            return value;
        }

        private double parseTerm() {
            double value = parsePower();
            while (isOp("*") || isOp("/") || isOp("%")) {
                Token op = tokens.get(pos++);
                double rhs = parsePower();
                if ("*".equals(op.textValue())) {
                    value = value * rhs;
                } else if ("/".equals(op.textValue())) {
                    if (rhs == 0.0) {
                        throw new ArithmeticException("division by zero");
                    }
                    value = value / rhs;
                } else {
                    if (rhs == 0.0) {
                        throw new ArithmeticException("modulo by zero");
                    }
                    value = value % rhs;
                }
            }
            return value;
        }

        private double parsePower() {
            double base = parseUnary();
            if (isOp("^")) {
                pos++;
                double exponent = parsePower(); // right-associative
                return Math.pow(base, exponent);
            }
            return base;
        }

        private double parseUnary() {
            if (isOp("+")) {
                pos++;
                return parseUnary();
            }
            if (isOp("-")) {
                pos++;
                return -parseUnary();
            }
            return parsePrimary();
        }

        private double parsePrimary() {
            Token t = peek();
            if (t == null) {
                throw new IllegalArgumentException("unexpected end of expression");
            }
            if (t.type() == TokenType.NUMBER) {
                pos++;
                return t.numValue();
            }
            if (t.type() == TokenType.LPAREN) {
                pos++;
                double value = parseExpression();
                Token close = peek();
                if (close == null || close.type() != TokenType.RPAREN) {
                    throw new IllegalArgumentException("missing closing parenthesis");
                }
                pos++;
                return value;
            }
            if (t.type() == TokenType.NAME) {
                pos++;
                String name = t.textValue();
                Token next = peek();
                if (next != null && next.type() == TokenType.LPAREN) {
                    pos++;
                    List<Double> args = new ArrayList<>();
                    if (peek() != null && peek().type() != TokenType.RPAREN) {
                        args.add(parseExpression());
                        while (peek() != null && peek().type() == TokenType.COMMA) {
                            pos++;
                            args.add(parseExpression());
                        }
                    }
                    Token close = peek();
                    if (close == null || close.type() != TokenType.RPAREN) {
                        throw new IllegalArgumentException("missing ) after " + name + "(");
                    }
                    pos++;
                    return invokeFunction(name, args);
                }
                Double constant = CONSTANTS.get(name);
                if (constant != null) {
                    return constant;
                }
                throw new IllegalArgumentException("unknown name: " + name);
            }
            throw new IllegalArgumentException("unexpected token: " + t.textValue());
        }

        private static double invokeFunction(String name, List<Double> args) {
            return switch (name) {
                case "abs" -> {
                    checkArgCount(name, args, 1);
                    yield Math.abs(args.get(0));
                }
                case "sign" -> {
                    checkArgCount(name, args, 1);
                    yield Math.signum(args.get(0));
                }
                case "sqrt" -> {
                    checkArgCount(name, args, 1);
                    yield Math.sqrt(args.get(0));
                }
                case "cbrt" -> {
                    checkArgCount(name, args, 1);
                    yield Math.cbrt(args.get(0));
                }
                case "round" -> {
                    checkArgCount(name, args, 1);
                    yield (double) Math.round(args.get(0));
                }
                case "floor" -> {
                    checkArgCount(name, args, 1);
                    yield Math.floor(args.get(0));
                }
                case "ceil" -> {
                    checkArgCount(name, args, 1);
                    yield Math.ceil(args.get(0));
                }
                case "trunc" -> {
                    checkArgCount(name, args, 1);
                    double v = args.get(0);
                    yield v < 0 ? Math.ceil(v) : Math.floor(v);
                }
                case "exp" -> {
                    checkArgCount(name, args, 1);
                    yield Math.exp(args.get(0));
                }
                case "ln" -> {
                    checkArgCount(name, args, 1);
                    yield Math.log(args.get(0));
                }
                case "log", "log10" -> {
                    checkArgCount(name, args, 1);
                    yield Math.log10(args.get(0));
                }
                case "log2" -> {
                    checkArgCount(name, args, 1);
                    yield Math.log(args.get(0)) / Math.log(2);
                }
                case "sin" -> {
                    checkArgCount(name, args, 1);
                    yield Math.sin(args.get(0));
                }
                case "cos" -> {
                    checkArgCount(name, args, 1);
                    yield Math.cos(args.get(0));
                }
                case "tan" -> {
                    checkArgCount(name, args, 1);
                    yield Math.tan(args.get(0));
                }
                case "asin" -> {
                    checkArgCount(name, args, 1);
                    yield Math.asin(args.get(0));
                }
                case "acos" -> {
                    checkArgCount(name, args, 1);
                    yield Math.acos(args.get(0));
                }
                case "atan" -> {
                    checkArgCount(name, args, 1);
                    yield Math.atan(args.get(0));
                }
                case "atan2" -> {
                    checkArgCount(name, args, 2);
                    yield Math.atan2(args.get(0), args.get(1));
                }
                case "pow" -> {
                    checkArgCount(name, args, 2);
                    yield Math.pow(args.get(0), args.get(1));
                }
                case "hypot" -> {
                    checkArgCount(name, args, 2);
                    yield Math.hypot(args.get(0), args.get(1));
                }
                case "min" -> {
                    if (args.isEmpty()) {
                        throw new IllegalArgumentException("min requires at least 1 argument");
                    }
                    double min = args.get(0);
                    for (int i = 1; i < args.size(); i++) {
                        min = Math.min(min, args.get(i));
                    }
                    yield min;
                }
                case "max" -> {
                    if (args.isEmpty()) {
                        throw new IllegalArgumentException("max requires at least 1 argument");
                    }
                    double max = args.get(0);
                    for (int i = 1; i < args.size(); i++) {
                        max = Math.max(max, args.get(i));
                    }
                    yield max;
                }
                default -> throw new IllegalArgumentException("unknown function: " + name);
            };
        }

        private static void checkArgCount(String name, List<Double> args, int expected) {
            if (args.size() != expected) {
                throw new IllegalArgumentException(
                    name + " requires " + expected + " arguments, got " + args.size());
            }
        }
    }
}
