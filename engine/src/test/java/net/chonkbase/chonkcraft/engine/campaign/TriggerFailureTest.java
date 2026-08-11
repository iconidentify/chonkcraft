package net.chonkbase.chonkcraft.engine.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.trigger.TriggerSystem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A trigger that fails says so.
 *
 * <p>It used to be dropped in silence. The comment beside the catch said a
 * broken condition should cost its own trigger rather than the mission, which
 * is right, but the trigger it costs may be the victory condition -- and then
 * the mission cannot be completed and nothing anywhere says why. That is how a
 * script fault stayed invisible: the campaign simply stopped being winnable.
 */
class TriggerFailureTest {

    private static TriggerSystem system(TriggerSystem.ProgramSpec... programs) {
        World world = new World(new GameMap(8, 8, new Tileset()));
        return new TriggerSystem(world, 0, java.util.List.of(programs));
    }

    @Test
    @DisplayName("a condition that throws is reported, not swallowed")
    void aBrokenConditionIsReported() {
        TriggerSystem triggers = system(
                new TriggerSystem.ProgramSpec("NO_SUCH_OPCODE", "VICTORY"));

        triggers.evaluate();
        assertFalse(triggers.failures().isEmpty(),
                "the trigger was dropped without a word");
        assertEquals(TriggerSystem.Outcome.RUNNING, triggers.outcome(),
                "a broken condition must not decide the mission");
    }

    @Test
    @DisplayName("a working trigger still fires and reports nothing")
    void aWorkingTriggerIsQuiet() {
        TriggerSystem triggers = system(new TriggerSystem.ProgramSpec("TRUE", "VICTORY"));

        triggers.evaluate();
        assertEquals(TriggerSystem.Outcome.VICTORY, triggers.outcome());
        assertTrue(triggers.failures().isEmpty(),
                "a trigger that worked should not be reported as a failure: "
                        + triggers.failures());
    }

    @Test
    @DisplayName("an action that throws is reported too")
    void aBrokenActionIsReported() {
        TriggerSystem triggers = system(
                new TriggerSystem.ProgramSpec("TRUE", "NO_SUCH_ACTION"));

        triggers.evaluate();
        assertFalse(triggers.failures().isEmpty(), "a failing action said nothing");
    }
}
