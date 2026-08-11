package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Player-visible BNE attack-move certification scenarios. */
class BattleNetAttackMovePlayabilityTest {

    private static GameMap openField(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static UnitType soldier(String ident) {
        UnitType type = new UnitType(ident);
        type.setName(ident);
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(6);
        type.setMaxAttackRange(1);
        type.setSightRange(9);
        type.setReactRangePerson(6);
        type.setReactRangeComputer(6);
        type.setMissile("missile-none");
        AnimationSet set = new AnimationSet("soldier");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        // The two halves and unbreakable boundary are important. A one-line
        // move lets the order scan on a beat retail gives to the committed
        // step and previously made the system test a false positive.
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 16", "wait 1",
                "frame 5", "move 16", "unbreakable end", "wait 1")));
        set.put(AnimationSet.State.ATTACK, Animation.parse("attack", List.of(
                "unbreakable begin", "frame 5", "wait 1",
                "frame 10", "attack", "unbreakable end", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    private static World twoSideField() {
        World world = new World(openField(48));
        world.setAllied(0, 1, false);
        return world;
    }

    @Test
    @DisplayName("a player attack-move interrupts, wins, and resumes")
    void playerAttackMoveCompletesTheCombatLoop() {
        World world = twoSideField();
        UnitType marcherType = soldier("unit-footman");
        UnitType picketType = soldier("unit-grunt");
        picketType.setCanAttack(false);
        picketType.setSpeed(0);
        Unit marcher = world.createUnit(marcherType, 0, 4, 20);
        Unit picket = world.createUnit(picketType, 1, 18, 20);
        picket.setHitPoints(6);

        CommandApplier commands = new CommandApplier(
                world, List.of(marcherType, picketType));
        commands.apply(GameCommand.attackMove(0, marcher.id(), 44, 20));

        boolean acquiredBeforeDestination = false;
        boolean dealtDamage = false;
        boolean resumedAfterKill = false;
        for (int cycle = 0; cycle < 3000 && marcher.order() != Unit.Order.STILL;
                cycle++) {
            int before = picket.hitPoints();
            world.tick();
            acquiredBeforeDestination |= marcher.target() == picket
                    && marcher.tileX() < 44;
            dealtDamage |= picket.hitPoints() < before;
            resumedAfterKill |= !picket.isAlive() && marcher.tileX() > 24;
        }

        assertTrue(acquiredBeforeDestination,
                "the command reached its destination before detecting the picket");
        assertTrue(dealtDamage, "the acquired enemy was never damaged");
        assertFalse(picket.isAlive(), "the combat never resolved");
        assertTrue(resumedAfterKill,
                "the marcher stopped at the fight instead of resuming its destination");
        assertTrue(marcher.tileX() > 40,
                "the resumed march never completed its player-visible advance");
    }

    @Test
    @DisplayName("a blocked attack-move recovers when the obstruction clears")
    void blockedAttackMoveDoesNotDeadlock() {
        World world = twoSideField();
        UnitType marcherType = soldier("unit-footman");
        UnitType blockerType = soldier("unit-friendly-blocker");
        blockerType.setSpeed(0);
        blockerType.setCanAttack(false);
        Unit marcher = world.createUnit(marcherType, 0, 4, 20);
        Unit blocker = world.createUnit(blockerType, 0, 5, 20);

        // Make a one-tile corridor so the first route cannot silently avoid
        // the obstruction and cease testing refused-step recovery.
        for (int y = 0; y < world.map().height(); y++) {
            if (y == 20) {
                continue;
            }
            for (int x = 0; x < world.map().width(); x++) {
                world.map().field(x, y).setFlags(TileFlag.UNPASSABLE);
            }
        }

        CommandApplier commands = new CommandApplier(
                world, List.of(marcherType, blockerType));
        commands.apply(GameCommand.attackMove(0, marcher.id(), 40, 20));
        for (int cycle = 0; cycle < 20; cycle++) {
            world.tick();
        }
        assertEquals(4, marcher.tileX(),
                "the marcher crossed an occupied one-tile corridor");

        // The referee clears a temporary obstruction, then observes only
        // ordinary engine behavior. It does not repair or reissue the order.
        world.kill(blocker);
        for (int cycle = 0; cycle < 2000 && marcher.order() != Unit.Order.STILL;
                cycle++) {
            world.tick();
        }
        // The first failed plans widen the position order's legal arrival
        // radius. That exact endpoint is a precision question; the system
        // contract here is that the original command wakes and advances once
        // the temporary obstruction no longer occupies the corridor.
        assertTrue(marcher.tileX() > 30,
                "the original attack-move stayed deadlocked after the route cleared");
        assertEquals(Unit.Order.STILL, marcher.order(),
                "the recovered command never reached a completed state");
    }

    @Test
    @DisplayName("attack-move aimed at a visible wall becomes bombardment")
    void visibleWallUsesTheCombatOrder() {
        GameMap map = openField(32);
        map.field(20, 20).setFlags(TileFlag.LAND_ALLOWED | TileFlag.WALL
                | TileFlag.HUMAN | TileFlag.UNPASSABLE);
        map.field(20, 20).setValue(GameMap.WALL_HIT_POINTS);
        World world = new World(map);
        UnitType footmanType = soldier("unit-footman");
        Unit footman = world.createUnit(footmanType, 0, 18, 20);
        CommandApplier commands = new CommandApplier(world, List.of(footmanType));

        commands.apply(GameCommand.attackMove(0, footman.id(), 20, 20));

        assertEquals(Unit.Order.ATTACK_GROUND, footman.order(),
                "the visible wall was treated as a walk destination");
    }
}
