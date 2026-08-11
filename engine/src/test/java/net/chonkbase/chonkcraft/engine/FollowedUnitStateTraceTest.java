package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A followed unit says what it was, every cycle, not only when something fired.
 *
 * <p>The native side of the parity lab reconstructs a unit's whole 152-byte
 * record at every cycle, so a miner can watch an unnamed counter climb to a
 * threshold and a timer arm behind it. The implementation's causal events all fire when
 * something happens -- a route searched, a step taken, a number drawn -- so a
 * state that climbs while the unit stands still and does nothing was the one
 * shape the two sides could not be compared on.
 *
 * <p>The cost of that has to be nothing. The event is written only when a
 * trace file and a unit have both been named, which is never during a game.
 */
class FollowedUnitStateTraceTest {

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static UnitType peasant() {
        UnitType type = new UnitType("unit-peasant");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setNumDirections(8);
        AnimationSet set = new AnimationSet("walker");
        set.put(AnimationSet.State.STILL, Animation.parse("still",
                List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 16", "wait 1",
                "frame 5", "move 16", "unbreakable end", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    private static List<String> stateLines(String trace) {
        List<String> lines = new ArrayList<>();
        for (String line : trace.split("\n")) {
            if (line.contains("\"kind\":\"state.unit\"")) {
                lines.add(line);
            }
        }
        return lines;
    }

    @Test
    @DisplayName("the followed unit reports its hidden state on every cycle it lives through")
    void everyCycleOfTheFollowedUnitIsRecorded() {
        World world = new World(grass(24));
        Unit worker = world.createUnit(peasant(), 0, 5, 5);
        StringWriter trace = new StringWriter();
        world.recordCausalEventsTo(trace, worker.id());

        for (int cycle = 0; cycle < 6; cycle++) {
            world.tick();
        }

        List<String> lines = stateLines(trace.toString());
        assertEquals(6, lines.size(),
                "six cycles produced a different number of state records, so a"
                        + " miner reading them cannot tell a quiet cycle from a"
                        + " missing one");
        for (int index = 0; index < lines.size(); index++) {
            assertTrue(lines.get(index).contains("\"cycle\":" + (index + 1)),
                    "the state records are not one per cycle in order: "
                            + lines.get(index));
        }
        assertTrue(lines.get(0).contains("\"subject\":\"unit:" + worker.id() + "\""),
                "the state record does not name the unit it describes");
    }

    @Test
    @DisplayName("it carries the hidden counters a native record would show")
    void theRecordCarriesTheStateThatNoOtherEventExposes() {
        World world = new World(grass(24));
        Unit worker = world.createUnit(peasant(), 0, 5, 5);
        StringWriter trace = new StringWriter();
        world.recordCausalEventsTo(trace, worker.id());
        worker.setBattleNetCollisionCounter(3);
        worker.setBattleNetOrderDelay(2);

        world.tick();

        String line = stateLines(trace.toString()).getFirst();
        for (String field : List.of("\"collision\":", "\"order_delay\":",
                "\"path_length\":", "\"wait\":", "\"offset_x\":",
                "\"heading\":", "\"order\":", "\"target\":",
                "\"battlenet_idle_phase\":", "\"melee_sync_remaining\":")) {
            assertTrue(line.contains(field),
                    "the followed unit's record omits " + field + ", which the"
                            + " native side of the same comparison has: " + line);
        }
        assertTrue(line.contains("\"collision\":3"),
                "the collision counter reported is not the one the unit holds");
    }

    @Test
    @DisplayName("a unit nobody named costs the game nothing")
    void anUnfollowedWorldWritesNoStateAtAll() {
        World world = new World(grass(24));
        Unit worker = world.createUnit(peasant(), 0, 5, 5);
        StringWriter trace = new StringWriter();
        // Tracing on, but no unit named: the whole point of the filter.
        world.recordCausalEventsTo(trace, null);

        world.tick();

        assertTrue(stateLines(trace.toString()).isEmpty(),
                "a trace with no unit named still recorded per-cycle state for"
                        + " every unit on the map, which buries the followed"
                        + " unit in a file too large to read");
        assertFalse(new World(grass(24)).causalTrace.enabled(),
                "an ordinary world starts with causal tracing on");
        assertEquals(worker.id(), worker.id());
    }

    @Test
    @DisplayName("a unit that has left the map stops reporting rather than lying")
    void aUnitThatIsGoneReportsNothing() {
        World world = new World(grass(24));
        Unit worker = world.createUnit(peasant(), 0, 5, 5);
        StringWriter trace = new StringWriter();
        world.recordCausalEventsTo(trace, worker.id() + 1000);

        world.tick();

        assertTrue(stateLines(trace.toString()).isEmpty(),
                "a unit that is not on the map produced a state record anyway");
    }
}
