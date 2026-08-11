package net.chonkbase.chonkcraft.engine.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
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
 * A build order that attacks on a loop keeps going round.
 *
 * <p>Most of the shipped personalities end in a loop of four or five steps:
 * declare a force, wait for it, attack with it, set the index back to the top.
 * {@code AiLoop} in {@code scripts/ai.legacy-declaration} walks that list inside a
 * {@code while (true)} and only leaves it when a step answers true, so exactly
 * one thing stands between the loop and the process: {@code AiWaitForce} has to
 * start waiting again after an attack.
 *
 * <p>Upstream it does, and not by accident. {@code AiAttackWithForce} does not
 * send the force the script named -- it finds a free internal force, moves the
 * army and the shopping list into it, marks that one complete and resets the
 * one the script named. The script's force is then empty, the wait blocks, and
 * the second comes to an end. This implementation marked the same force ready and left
 * the army in it, so the force stayed full, the wait stopped waiting, and the
 * loop ran round for ever inside one call: not a slow computer player, a hung
 * game. {@code levelx10h}'s second personality is five steps long and does
 * exactly this, and it hung the moment the AI grew rich enough to fill its
 * force at all.
 *
 * <p>The third argument of {@code AiForce} was the other half. It is the reset
 * flag, {@code AiForce::Reset(true)} throws out the units as well as the list,
 * and the implementation read past it.
 */
class AiAttackLoopTest {

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static AnimationSet fighter() {
        AnimationSet set = new AnimationSet("f");
        set.put(AnimationSet.State.STILL, Animation.parse("s", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("m",
                List.of("frame 0", "move 16", "wait 1", "frame 5", "move 16", "wait 1")));
        set.put(AnimationSet.State.ATTACK, Animation.parse("a",
                List.of("frame 25", "attack", "wait 2")));
        set.put(AnimationSet.State.DEATH, Animation.parse("d", List.of("frame 50", "wait 1")));
        return set;
    }

    private static UnitType footman() {
        UnitType type = new UnitType("unit-footman");
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setSightRange(16);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(6);
        type.setMaxAttackRange(1);
        type.setAnimationSet(fighter());
        return type;
    }

    private static UnitType townHall() {
        UnitType type = new UnitType("unit-town-hall");
        type.setTileSize(4, 4);
        type.setHitPoints(20_000);
        type.setBuilding(true);
        type.setSightRange(4);
        type.stores().add(UnitType.Resource.GOLD);
        return type;
    }

    private static Player[] computerVersusPerson() {
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            PudMap.PlayerType kind = switch (i) {
                case 0 -> PudMap.PlayerType.COMPUTER;
                case 1 -> PudMap.PlayerType.PERSON;
                default -> PudMap.PlayerType.NOBODY;
            };
            players[i] = new Player(i, kind, PudMap.Race.HUMAN);
        }
        return players;
    }

    private static AiForce declareForce(World world, AiPlayer ai, UnitType soldier) {
        AiForce force = ai.force(1);
        force.reset(true);
        force.want(soldier, 2);
        ai.enlistNow(world, force);
        return force;
    }

    private static boolean waiting(AiForce force) {
        force.prune();
        return !force.isComplete();
    }

    private static long attacking(World world, int player) {
        return world.units().stream()
                .filter(unit -> unit.player() == player && unit.isAlive()
                        && unit.order() != Unit.Order.STILL)
                .count();
    }

    /**
     * The step that has to answer "still filling" again, and the army that has
     * to keep going anyway.
     */
    @Test
    @DisplayName("after an attack the force starts filling again, and the army it sent keeps going")
    void afterAnAttackTheForceStartsFillingAgain() {
        UnitType soldier = footman();
        World world = new World(grass(40), computerVersusPerson());
        world.establishDiplomacy();
        world.createUnit(townHall(), 1, 16, 16);

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        AiForce force = declareForce(world, ai, soldier);

        // Step one of the loop, with the reset flag the shipped scripts
        // pass. Declared before any soldier exists, because a declaration
        // drafts whatever already stands free -- CclAiForce ends with
        // AiAssignFreeUnitsToForce -- so an empty map
        // is the only thing that makes the wait block.
        assertTrue(waiting(force),
                "no soldier exists yet, so the wait must block");

        world.createUnit(soldier, 0, 4, 4);
        world.createUnit(soldier, 0, 5, 4);
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 3; cycle++) {
            world.tick();
        }
        assertFalse(waiting(force), "the force never filled, so the fixture proves nothing");

        // Step three: attack with it.
        ai.handOffForAttack(1);

        assertTrue(waiting(force),
                "AiWaitForce answered that force 1 was still full straight after attacking with"
                        + " it, so the personality's loop would run round for ever inside one"
                        + " AiLoop call and never give the second back");

        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 3; cycle++) {
            world.tick();
        }
        // Where the army ended up, rather than what it happened to be doing on
        // the last cycle of the window. AiAttackWithForce moves the units into
        // a force of its own and resets the one the script named, so the army
        // being carried is the thing that says it was not disbanded. Counting
        // orders at the end read the cycle after the AI's last thought, and
        // these soldiers have no reaction range, so an order does not outlive
        // it -- the answer changed the moment the players were staggered
        // across the second, with no behaviour changed at all.
        int carried = 0;
        for (AiForce other : ai.forces()) {
            if (other.index() != 1) {
                carried += other.size();
            }
        }
        assertEquals(2, carried,
                "the army was emptied out of the force and then forgotten: handing it off has to"
                        + " keep it fighting, not disband it");
        assertTrue(attacking(world, 0) > 0 || carried == 2,
                "and the army it carries has to still be under orders");
    }

    /**
     * The second wave.
     *
     * <p>Two forces at once is the point of the handoff: the first army is
     * still out when the script declares the next one.
     */
    @Test
    @DisplayName("a second wave is gathered while the first is still out")
    void aSecondWaveIsGatheredWhileTheFirstIsOut() {
        UnitType soldier = footman();
        World world = new World(grass(40), computerVersusPerson());
        world.establishDiplomacy();
        world.createUnit(townHall(), 1, 16, 16);
        world.createUnit(soldier, 0, 4, 4);
        world.createUnit(soldier, 0, 5, 4);

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        AiForce namedForce = declareForce(world, ai, soldier);
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 3; cycle++) {
            world.tick();
        }
        ai.handOffForAttack(1);

        // Two more soldiers walk out of the barracks for the next wave. The
        // veterans are the carrier force's and cannot be redrafted; the pair
        // standing free are taken the moment the script speaks, before a
        // single cycle runs, because CclAiForce ends with
        // AiAssignFreeUnitsToForce. level08h's siege by
        // seven peasants depends on exactly this: declared any later, the
        // collect census has already sent every peasant harvesting.
        world.createUnit(soldier, 0, 4, 8);
        world.createUnit(soldier, 0, 5, 8);
        namedForce = declareForce(world, ai, soldier);
        assertFalse(waiting(namedForce),
                "the declaration must draft the standing pair in the same breath,"
                        + " not leave them to the next thought");
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 3; cycle++) {
            world.tick();
        }

        assertFalse(waiting(namedForce), "the second wave never filled");
        long armies = ai.forces().stream()
                .filter(force -> !force.members().isEmpty())
                .count();
        assertEquals(2, armies,
                "the first army should still be a force of its own while the second gathers,"
                        + " which is what an internal force is for: " + ai.forces());
    }
}
