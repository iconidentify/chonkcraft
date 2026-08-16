package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.save.LoadGame;
import net.chonkbase.chonkcraft.engine.save.SaveGame;
import net.chonkbase.chonkcraft.engine.trigger.TriggerSystem;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Human 2's delayed victory is the only DELAYED_VICTORY program.
 *
 * <p>The sealed condition is: player 6 has no ranger or archer left, and a
 * rescued archer or ranger of the person stands next to the circle of
 * power. Retail then counts 120 cycles before victory. A mid-countdown
 * save must resume the same remaining beats, not restart the 120.
 */
class Human2DelayedVictoryRealDataTest {

    private static final String MAP = "campaigns/human/level02h";

    @Test
    @DisplayName("human 2's rescue countdown keeps the same remaining beats after save and resume")
    void human2RescueCountdownKeepsTheSameRemainingBeatsAfterSaveAndResume()
            throws Exception {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission continuous = data.loadMission(MAP);
        Assumptions.assumeTrue(continuous != null, "Human 2 is not in the pack");
        bringElvesToTheCircle(continuous);

        int armedAt = -1;
        for (int cycle = 1; cycle <= 8; cycle++) {
            continuous.tick();
            if (!continuous.triggers().savedState().delays().isEmpty()) {
                armedAt = cycle;
                break;
            }
        }
        assertTrue(armedAt > 0,
                "rescuing the six elves and standing one on the circle must start the 120-cycle victory wait");
        assertEquals(TriggerSystem.Outcome.RUNNING, continuous.outcome(),
                "the delay must not win on the cycle it arms");
        int remaining = continuous.triggers().savedState().delays().get(0).remaining();
        assertTrue(remaining > 4 && remaining <= 120,
                "retail's Human 2 delay is 120 cycles; the armed wait was " + remaining);

        StringWriter out = new StringWriter();
        SaveGame.writeWithTriggers(continuous.world(), MAP, "human", 2,
                continuous.triggers().savedState(), out);
        String script = out.toString();

        Mission resumed = data.loadMission(MAP);
        for (Unit unit : new ArrayList<>(resumed.world().units())) {
            resumed.world().remove(unit);
        }
        LoadGame.apply(resumed.world(), script, data.unitTypes().types());
        resumed.triggers().restoreState(LoadGame.triggerState(script));
        assertTrue(script.contains("rescuedFrom"),
                "the save must name which slot a rescued elf came from");
        int rescued = 0;
        int playerSixArchers = 0;
        for (Unit unit : resumed.world().units()) {
            if (unit == null || !unit.isAlive() || unit.type() == null) {
                continue;
            }
            if (unit.wasRescued()) {
                rescued++;
            }
            if (unit.player() == 6 && "unit-archer".equals(unit.type().ident())) {
                playerSixArchers++;
            }
        }
        assertEquals(0, playerSixArchers,
                "reloading must not put the six elves back in the rescue-passive slot");
        assertTrue(rescued > 0,
                "reloading must remember that the elves were rescued");
        assertEquals(continuous.triggers().savedState().delays(),
                resumed.triggers().savedState().delays(),
                "the save must carry the in-flight Human 2 countdown, not restart it");

        int half = Math.max(1, remaining / 2);
        for (int step = 0; step < half; step++) {
            continuous.tick();
            resumed.tick();
        }
        assertEquals(TriggerSystem.Outcome.RUNNING, continuous.outcome(),
                "the continuous Human 2 wait must still be counting");
        assertEquals(TriggerSystem.Outcome.RUNNING, resumed.outcome(),
                "the resumed Human 2 wait must still be counting");
        assertEquals(continuous.triggers().savedState().delays(),
                resumed.triggers().savedState().delays(),
                "continuous and resumed Human 2 countdowns must stay in lockstep");

        int guard = remaining + 4;
        while (continuous.outcome() == TriggerSystem.Outcome.RUNNING && guard-- > 0) {
            continuous.tick();
            resumed.tick();
        }
        assertEquals(TriggerSystem.Outcome.VICTORY, continuous.outcome(),
                "Human 2 must win when the 120-cycle rescue wait ends");
        assertEquals(TriggerSystem.Outcome.VICTORY, resumed.outcome(),
                "the resumed Human 2 wait must win on the same beat as the continuous one");
        assertEquals(0, continuous.triggers().decidedBy(),
                "Human 2 is decided by the delayed-victory trigger, not the wipe");
        assertEquals(0, resumed.triggers().decidedBy(),
                "the resumed mission must still be decided by the delayed-victory trigger");
    }

    /**
     * Rescues the six northwest elves and stands one on the circle.
     *
     * <p>Uses the same HandleEachCycle rescue the player gets by walking a
     * soldier next to the prisoners, then the same adjacency the trigger
     * asks of the circle. The long walk is not the thing under test.
     */
    private static void bringElvesToTheCircle(Mission mission) {
        World world = mission.world();
        Unit saviour = null;
        List<Unit> prisoners = new ArrayList<>();
        for (Unit unit : world.units()) {
            if (unit == null || !unit.isAlive() || unit.type() == null) {
                continue;
            }
            if (unit.player() == 1 && saviour == null) {
                saviour = unit;
            }
            if (unit.player() == 6) {
                prisoners.add(unit);
            }
        }
        assertFalse(prisoners.isEmpty(), "Human 2 ships six rescue-passive elves");
        assertTrue(saviour != null, "Human 2 ships a person-owned soldier");
        for (Unit prisoner : prisoners) {
            world.markOccupancy(saviour, false);
            saviour.setTile(prisoner.tileX() + 1, prisoner.tileY());
            world.markOccupancy(saviour, true);
            world.rescueBattleNetUnit(prisoner);
        }
        Unit rescued = null;
        for (Unit unit : world.units()) {
            if (unit != null && unit.isAlive() && unit.wasRescued()
                    && unit.type() != null
                    && "unit-archer".equals(unit.type().ident())) {
                rescued = unit;
                break;
            }
        }
        assertTrue(rescued != null, "a rescued elf must still be an archer");
        world.markOccupancy(rescued, false);
        rescued.setTile(29, 36);
        world.markOccupancy(rescued, true);
    }
}
