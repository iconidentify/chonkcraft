package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.data.map.PudMap;
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
 * The guards stand over the prisoner, and neither side swings.
 *
 * <p>On the first mission of the orc expansion the caged Beast Cry -- 240 hit
 * points, owned by a rescue-passive slot -- was cut down by the four guards
 * around it in 54 simulated seconds with nobody touching the controls, and
 * the mission declared its own DEFEAT: the hero both defeat triggers watch
 * was dead before a player could reach it. The world had one diplomacy table
 * and derived "enemy" as "not allied", and a computer player and a
 * rescue-passive slot are not allied -- they are not enemies either, which a
 * single table has no way to say.
 *
 * <p>{@code CPlayer::Init} keeps
 * {@code Enemy} and {@code Allied} as separate masks, and its computer case
 * marks persons and rescue-active slots as enemies -- not rescue-passive.
 * These drive the standing through what a guard actually does: sixty seconds
 * of the world running with a prisoner in reach. The control hands the same
 * prisoner to a person slot and requires the same guard to draw blood, because
 * a guard that attacks nobody passes the first test while proving nothing.
 */
class PrisonerStandingTest {

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

    private static UnitType grunt() {
        UnitType type = new UnitType("unit-grunt");
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setDemand(1);
        type.setSightRange(4);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(6);
        type.setMaxAttackRange(1);
        // The shipped grunt's own figures. Without a react range autoAttack
        // returns before it looks, and both pens below go quiet for the wrong
        // reason.
        type.setReactRangePerson(6);
        type.setReactRangeComputer(4);
        type.setAnimationSet(fighter());
        return type;
    }

    /** An unarmed captive, which is what every caged hero effectively is here. */
    private static UnitType captive() {
        UnitType type = new UnitType("unit-beast-cry");
        type.setTileSize(1, 1);
        type.setHitPoints(240);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setDemand(1);
        type.setSightRange(4);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(9);
        type.setMaxAttackRange(1);
        type.setAnimationSet(fighter());
        return type;
    }

    /** Slot 0 holds the prisoner, slot 1 is the computer whose guards stand over it. */
    private static World world(PudMap.PlayerType prisonerKind) {
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            PudMap.PlayerType kind = switch (i) {
                case 0 -> prisonerKind;
                case 1 -> PudMap.PlayerType.COMPUTER;
                default -> PudMap.PlayerType.NOBODY;
            };
            players[i] = new Player(i, kind, PudMap.Race.ORC);
        }
        return new World(grass(30), players);
    }

    @Test
    @DisplayName("a computer's guards do not kill the prisoner they stand over")
    void theGuardsDoNotKillThePrisoner() {
        World world = world(PudMap.PlayerType.RESCUE_PASSIVE);
        assertFalse(world.isEnemyPlayer(1, 0),
                "a computer counts a rescue-passive slot an enemy: CPlayer::Init's computer"
                        + " case marks persons and rescue-active, and nothing else");
        assertFalse(world.isEnemyPlayer(0, 1),
                "the prisoner counts its guards enemies, so it would swing first");
        assertFalse(world.isAllied(1, 0),
                "not enemies must not have become allies: the guards do not free the"
                        + " prisoner either");

        Unit prisoner = world.createUnit(captive(), 0, 10, 10);
        assertNotNull(prisoner, "nowhere to stand the prisoner");
        for (int i = 0; i < 4; i++) {
            assertNotNull(world.createUnit(grunt(), 1, 9 + (i % 2) * 2, 9 + (i / 2) * 2),
                    "nowhere to stand guard " + i);
        }

        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 60; cycle++) {
            world.tick();
        }

        assertEquals(240, prisoner.hitPoints(),
                "the guards drew blood on a prisoner: a rescue-passive unit was struck by"
                        + " the computer standing over it, which is the fault that lost"
                        + " orc-exp/levelx01o in 54 seconds with nobody playing");
        assertTrue(prisoner.isAlive(), "the prisoner is dead");
    }

    /**
     * The same pen with the prisoner handed to a person slot, and now the
     * guards must draw blood -- this is what proves the sixty quiet seconds
     * above are the diplomacy holding and not four guards who cannot fight.
     */
    @Test
    @DisplayName("the same guards still cut down a person's unit in the same pen")
    void theSameGuardsStillFightAPerson() {
        World world = world(PudMap.PlayerType.PERSON);
        assertTrue(world.isEnemyPlayer(1, 0),
                "a computer must count a person an enemy, or this control proves nothing");

        Unit victim = world.createUnit(captive(), 0, 10, 10);
        assertNotNull(victim, "nowhere to stand the victim");
        for (int i = 0; i < 4; i++) {
            assertNotNull(world.createUnit(grunt(), 1, 9 + (i % 2) * 2, 9 + (i / 2) * 2),
                    "nowhere to stand guard " + i);
        }

        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 60; cycle++) {
            world.tick();
        }

        assertTrue(victim.hitPoints() < 240,
                "four guards left a person's unit untouched for sixty seconds, so the"
                        + " quiet pen in the test above proves nothing about diplomacy");
    }
}
