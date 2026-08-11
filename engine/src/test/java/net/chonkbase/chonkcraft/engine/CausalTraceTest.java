package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import org.junit.jupiter.api.Test;

final class CausalTraceTest {
    @Test
    void writesStableJsonAndHonoursTheUnitFilter() {
        StringWriter output = new StringWriter();
        CausalTrace trace = new CausalTrace(output, 7);
        trace.event(24, "movement.step", 6, "x", 1);
        trace.event(24, "movement.step", 7,
                "from", "40,8", "to", "39,8", "clear", true);

        assertEquals("{\"schema\":1,\"side\":\"java\",\"ordinal\":0,"
                + "\"cycle\":24,\"kind\":\"movement.step\","
                + "\"subject\":\"unit:7\",\"fields\":{"
                + "\"from\":\"40,8\",\"to\":\"39,8\",\"clear\":true}}\n",
                output.toString());
    }

    @Test
    void reportsWhetherCausalTracingIsEnabled() {
        assertEquals(true, new CausalTrace(new StringWriter(), null).enabled());
    }

    @Test
    void writesConcreteAndSymbolicPredicateEvidenceWithoutChangingTheResult() {
        StringWriter output = new StringWriter();
        CausalTrace trace = new CausalTrace(output, 7);

        assertEquals(true, trace.predicate(23, 7, "distance.boundary",
                "abs(sub(unit.order_x,unit.x))", 2, ">", "1", 1,
                true, "take step"));

        String line = output.toString();
        assertTrue(line.contains("\"kind\":\"semantic.predicate\""));
        assertTrue(line.contains("\"predicate_id\":\"distance.boundary\""));
        assertTrue(line.contains("\"lhs\":2"));
        assertTrue(line.contains("\"rhs\":1"));
        assertTrue(line.contains("\"result\":true"));
        assertTrue(line.contains("\"source\":\"CausalTraceTest.java:"));
    }

    @Test
    void filteredPredicateReturnsItsResultWithoutEmittingDiagnostics() {
        StringWriter output = new StringWriter();
        CausalTrace trace = new CausalTrace(output, 7);

        assertEquals(false, trace.predicate(23, 8, "distance.boundary",
                "unit.distance", 1, ">", "2", 2,
                false, "wait"));
        assertEquals("", output.toString());
    }
}
