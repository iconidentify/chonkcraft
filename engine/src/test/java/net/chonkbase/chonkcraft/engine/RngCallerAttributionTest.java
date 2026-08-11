package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every random number says which part of the game asked for it.
 *
 * <p>The cross-engine ledger puts BNE's native return address beside the Java
 * name of whoever drew, and reads the pair to say where the two engines stop
 * agreeing. That only works while the Java name is real. The reader used to
 * keep {@code World} frames alone, so once the {@code BattleNet*} subsystems
 * moved out of {@code World} every projectile, idle and construction draw was
 * attributed to {@code ?} -- a ledger of question marks distinguishes nothing,
 * and the omission is invisible unless something asserts on it.
 *
 * <p>It used to be a method name and a line number as well, which meant the
 * same draw changed identity whenever a comment was added above it. The name
 * checked here is the class and the method, and the line is carried beside it
 * as a hint rather than as the identity.
 */
class RngCallerAttributionTest {

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static UnitType footman() {
        UnitType type = new UnitType("unit-footman");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setNumDirections(8);
        type.setBasicDamage(6);
        type.setPiercingDamage(3);
        return type;
    }

    /** The `caller` field of every asynchronous draw in the recorded trace. */
    private static List<String> asyncCallers(String trace) {
        List<String> callers = new ArrayList<>();
        for (String line : trace.split("\n")) {
            if (line.contains("\"kind\":\"rng.async.draw\"")) {
                callers.add(field(line, "caller"));
            }
        }
        return callers;
    }

    private static String field(String line, String name) {
        String key = "\"" + name + "\":\"";
        int start = line.indexOf(key);
        if (start < 0) {
            return null;
        }
        start += key.length();
        return line.substring(start, line.indexOf('"', start));
    }

    @Test
    @DisplayName("a unit being put on the map names the world method that rolled its heading")
    void aDrawInsideWorldNamesTheWorldMethodThatAsked() {
        World world = new World(grass(16));
        StringWriter trace = new StringWriter();
        world.recordCausalEventsTo(trace, null);

        world.createUnit(footman(), 0, 5, 5);

        List<String> callers = asyncCallers(trace.toString());
        assertFalse(callers.isEmpty(),
                "putting a footman on the map drew no asynchronous random number,"
                        + " so this proves nothing about who is credited for one");
        assertEquals(List.of("World.initializeBattleNetUnit"),
                callers.stream().distinct().toList(),
                "the heading and animation-timer draws a new unit costs are"
                        + " credited to something other than the world method"
                        + " that takes them");
    }

    @Test
    @DisplayName("a melee blow's damage roll reaches back to the combat system that struck it")
    void aDrawBelowTheCombatSystemNamesBothTheFormulaAndTheCombatSystem() {
        World world = new World(grass(16));
        Unit attacker = world.createUnit(footman(), 0, 5, 5);
        Unit target = world.createUnit(footman(), 1, 6, 5);
        StringWriter trace = new StringWriter();
        world.recordCausalEventsTo(trace, null);

        // The opcode-ten arm of the melee formula, which is the one that draws
        // from the asynchronous stream rather than the synchronized one.
        world.battleNetNativeMeleeDamage.add(attacker);
        world.combat.applyDamage(attacker, target, 1);

        String line = null;
        for (String candidate : trace.toString().split("\n")) {
            if (candidate.contains("\"kind\":\"rng.async.draw\"")) {
                line = candidate;
            }
        }
        assertNotNull(line, "the melee blow rolled no asynchronous damage at all");
        assertEquals("World.battleNetMeleeDamage", field(line, "caller"),
                "the damage roll is credited to something other than the"
                        + " formula that takes it");
        assertTrue(field(line, "caller_chain")
                        .contains("BattleNetCombatSystem.applyDamage"),
                "the roll's caller chain never reaches the combat system that"
                        + " struck the blow, so a ledger reading it cannot say"
                        + " which part of the game spent the number: "
                        + field(line, "caller_chain"));
    }

    @Test
    @DisplayName("an idle re-arm and a constructor burn each name their own subsystem")
    void drawsInsideExtractedSubsystemsNameTheSubsystem() {
        World world = new World(grass(16));
        Unit flyer = world.createUnit(footman(), 0, 5, 5);
        Unit builder = world.createUnit(footman(), 0, 7, 5);
        builder.setBattleNetConstructorStreamBurns(1, 0);
        StringWriter trace = new StringWriter();
        world.recordCausalEventsTo(trace, null);

        world.idle.rearmBattleNetFlyingIdleTimer(flyer);
        world.construction.burnBattleNetConstructorStream(builder);

        assertEquals(
                List.of("BattleNetIdleSystem.rearmBattleNetFlyingIdleTimer",
                        "BattleNetConstructionSystem.burnBattleNetConstructorStream"),
                asyncCallers(trace.toString()),
                "draws taken inside the extracted subsystems are credited to"
                        + " the wrong class, which is how a subsystem"
                        + " extraction quietly turns a ledger into question"
                        + " marks");
    }

    @Test
    @DisplayName("the line a draw sits on is a hint beside its name, not the name")
    void theSourceLineIsCarriedSeparatelyFromTheStableIdentity() {
        World world = new World(grass(16));
        StringWriter trace = new StringWriter();
        world.recordCausalEventsTo(trace, null);

        world.createUnit(footman(), 0, 5, 5);

        String line = trace.toString().split("\n")[0];
        assertFalse(field(line, "caller").contains(":"),
                "the caller identity still carries a source line, so every draw"
                        + " in the file changes identity when a line moves"
                        + " above it");
        assertTrue(line.contains("\"caller_line\":"),
                "the source line was dropped rather than kept as diagnostic"
                        + " metadata beside the stable name");
    }

    @Test
    @DisplayName("asynchronous draws are numbered so one engine can say which one it means")
    void asynchronousDrawsCarryAMonotonicOrdinal() {
        World world = new World(grass(16));
        StringWriter trace = new StringWriter();
        world.recordCausalEventsTo(trace, null);

        world.createUnit(footman(), 0, 5, 5);
        world.createUnit(footman(), 0, 7, 5);

        List<Integer> ordinals = new ArrayList<>();
        for (String line : trace.toString().split("\n")) {
            if (line.contains("\"kind\":\"rng.async.draw\"")) {
                int start = line.indexOf("\"draw\":") + "\"draw\":".length();
                int end = start;
                while (end < line.length() && Character.isDigit(line.charAt(end))) {
                    end++;
                }
                ordinals.add(Integer.parseInt(line.substring(start, end)));
            }
        }
        assertTrue(ordinals.size() >= 2,
                "two footmen drew fewer than two asynchronous numbers between"
                        + " them, so the ordering below proves nothing");
        for (int index = 1; index < ordinals.size(); index++) {
            assertEquals(ordinals.get(index - 1) + 1, ordinals.get(index),
                    "asynchronous draw numbers skip or repeat, so a ledger"
                            + " cannot say which draw of the run it is looking"
                            + " at");
        }
    }

    @Test
    @DisplayName("an ordinary game walks no stack for a random number")
    void anUntracedWorldRecordsNothingAndWalksNoStack() {
        World world = new World(grass(16));

        world.createUnit(footman(), 0, 5, 5);

        assertFalse(world.causalTrace.enabled(),
                "an ordinary world starts with causal tracing on, which would"
                        + " put a stack walk and a JSON line under every draw"
                        + " the game takes");
    }
}
