package net.chonkbase.chonkcraft.engine;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;

/** Opt-in normalized JSONL events for the BNE causal-twin debugger. */
final class CausalTrace {
    private static final String PATH = System.getenv("CHONKCRAFT_TRACE_BNE_CAUSAL");
    private static final String UNIT = System.getenv("CHONKCRAFT_TRACE_BNE_CAUSAL_UNIT");

    private final PrintWriter writer;
    private final Integer unitFilter;
    private long ordinal;

    private CausalTrace() {
        writer = null;
        unitFilter = null;
    }

    CausalTrace(Writer writer, Integer unitFilter) {
        this.writer = new PrintWriter(writer);
        this.unitFilter = unitFilter;
    }

    static CausalTrace fromEnvironment() {
        if (PATH == null || PATH.isBlank()) {
            return new CausalTrace();
        }
        try {
            Integer filter = UNIT == null || UNIT.isBlank()
                    ? null : Integer.parseInt(UNIT.trim());
            return new CausalTrace(new FileWriter(PATH, false), filter);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot open causal trace " + PATH, exception);
        }
    }

    boolean accepts(Integer unit) {
        return writer != null
                && (unit == null || unitFilter == null || unitFilter.equals(unit));
    }

    boolean enabled() {
        return writer != null;
    }

    /**
     * The one unit this trace was asked to follow, or null for all of them.
     *
     * <p>Per-cycle state is only worth writing for a unit somebody named. A
     * campaign fixture holds hundreds of units and eighteen hundred cycles,
     * and recording every one of them would bury the followed unit in a file
     * too large to read.
     */
    Integer focus() {
        return writer == null ? null : unitFilter;
    }

    synchronized void event(long cycle, String kind, Integer unit, Object... fields) {
        if (!accepts(unit)) {
            return;
        }
        if (fields.length % 2 != 0) {
            throw new IllegalArgumentException("causal event fields must be key/value pairs");
        }
        StringBuilder line = new StringBuilder(256);
        line.append("{\"schema\":1,\"side\":\"java\",\"ordinal\":")
                .append(ordinal++)
                .append(",\"cycle\":").append(cycle)
                .append(",\"kind\":").append(quoted(kind));
        if (unit != null) {
            line.append(",\"subject\":\"unit:").append(unit).append('\"');
        }
        line.append(",\"fields\":{");
        for (int index = 0; index < fields.length; index += 2) {
            if (index > 0) {
                line.append(',');
            }
            line.append(quoted(String.valueOf(fields[index]))).append(':')
                    .append(json(fields[index + 1]));
        }
        line.append("}}");
        writer.println(line);
        writer.flush();
    }

    /**
     * Records one already-evaluated Java predicate without changing its result.
     *
     * <p>The expression strings use the parity lab's small S-expression
     * vocabulary ({@code abs}, {@code sub}, {@code max}, symbols and integer
     * constants). Keeping the symbolic form beside the concrete operands is
     * what lets the offline semantic bridge compare this decision with a BNE
     * dynamic slice. With causal tracing disabled this returns before walking
     * the stack or allocating diagnostic data.</p>
     */
    boolean predicate(long cycle, Integer unit, String id,
            String leftExpression, int left, String operator,
            String rightExpression, int right, boolean result,
            String decision) {
        if (!accepts(unit)) {
            return result;
        }
        StackTraceElement caller = StackWalker.getInstance()
                .walk(frames -> frames.skip(1).findFirst()
                        .map(StackWalker.StackFrame::toStackTraceElement)
                        .orElse(null));
        String source = caller == null ? null
                : caller.getFileName() + ":" + caller.getLineNumber();
        event(cycle, "semantic.predicate", unit,
                "predicate_id", id,
                "lhs_expression", leftExpression,
                "lhs", left,
                "operator", operator,
                "rhs_expression", rightExpression,
                "rhs", right,
                "result", result,
                "decision", decision,
                "source", source);
        return result;
    }

    private static String json(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return quoted(value.toString());
    }

    private static String quoted(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2).append('\"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> result.append("\\\\");
                case '\"' -> result.append("\\\"");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 0x20) {
                        result.append(String.format("\\u%04x", (int) character));
                    } else {
                        result.append(character);
                    }
                }
            }
        }
        return result.append('\"').toString();
    }
}
