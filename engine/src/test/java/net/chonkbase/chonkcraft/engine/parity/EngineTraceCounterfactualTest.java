package net.chonkbase.chonkcraft.engine.parity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineTraceCounterfactualTest {

    @TempDir
    Path temporary;

    @Test
    void parsesABoundedInterventionPlan() throws Exception {
        Path plan = temporary.resolve("candidate.tsv");
        Files.writeString(plan, """
                # bne-counterfactual-v1
                pre\t25\t58\tset-order\tPATROL
                post\t25\t37\tset-tile\t85,36
                """);

        var parsed = EngineTrace.CounterfactualPlan.load(plan);

        assertEquals(2, parsed.interventions().size());
        var first = parsed.interventions().get(0);
        assertEquals(EngineTrace.CounterfactualPhase.PRE, first.phase());
        assertEquals(25, first.cycle());
        assertEquals(58, first.unitId());
        assertEquals("set-order", first.operation());
        assertEquals("PATROL", first.value());
    }

    @Test
    void rejectsAnUnboundedOrUnknownOperation() throws Exception {
        Path plan = temporary.resolve("candidate.tsv");
        Files.writeString(plan, """
                # bne-counterfactual-v1
                pre\t25\t58\tarbitrary-code\tanything
                """);

        assertThrows(IllegalArgumentException.class,
                () -> EngineTrace.CounterfactualPlan.load(plan));
    }
}
