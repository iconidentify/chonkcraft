package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.pathfinder.PathFinder;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Regression coverage for large warships chasing coastal buildings. */
class BattleshipCoastalAttackTest {

    private static UnitType combatant(String ident) {
        UnitType type = new UnitType(ident);
        type.setName(ident);
        type.setTileSize(1, 1);
        type.setHitPoints(500);
        type.setSpeed(32);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(20);
        type.setMaxAttackRange(1);
        type.setSightRange(16);
        type.setMissile("missile-none");
        AnimationSet set = new AnimationSet(ident);
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE,
                Animation.parse("move", List.of(
                        "unbreakable begin", "frame 0", "move 32",
                        "unbreakable end", "wait 1")));
        set.put(AnimationSet.State.ATTACK,
                Animation.parse("attack", List.of(
                        "unbreakable begin", "frame 0", "attack",
                        "unbreakable end", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    private static UnitType battleship() {
        UnitType type = combatant("unit-battleship");
        type.setSeaUnit(true);
        type.setTileSize(2, 2);
        type.setMaxAttackRange(6);
        return type;
    }

    private static UnitType shipyard() {
        UnitType type = combatant("unit-orc-shipyard");
        type.setBuilding(true);
        type.setTileSize(3, 3);
        type.setSpeed(0);
        type.setCanAttack(false);
        return type;
    }

    private static GameMap openWater(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.WATER_ALLOWED);
            }
        }
        return map;
    }

    @Test
    @DisplayName("a capital ship drops a coast-blocked cached step and replans")
    void capitalShipReplansAStaleTerrainBlockedChaseStep() {
        GameMap map = openWater(40);
        // The stale route's west anchor is permanent coast, while north-west
        // is open water and a fresh route can carry the ship around it.
        map.field(18, 20).setFlags(TileFlag.LAND_ALLOWED);
        World world = new World(map);
        world.setAllied(0, 1, false);
        Unit ship = world.createUnit(battleship(), 0, 20, 20);
        Unit port = world.createUnit(shipyard(), 1, 6, 12);

        assertTrue(world.orderAttack(ship, port));
        ship.setChasing(true);
        ship.setStepDrained(true);
        ship.setPathGoal(port.tileX(), port.tileY());
        ship.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {Direction.fromDelta(-1, 0)}));

        world.tick();

        assertEquals(0, ship.pathLength(),
                "permanent coast was preserved like a temporary ship blocker");
        assertEquals(0, ship.battleNetOrderDelay(),
                "permanent coast incorrectly armed the congestion hold");
        boolean moved = false;
        for (int cycle = 0; cycle < 80 && !moved; cycle++) {
            world.tick();
            moved = ship.tileX() != 20 || ship.tileY() != 20;
        }
        assertTrue(moved, "the live attack order never replanned around the coast");
        assertSame(port, ship.target(), "replanning discarded the requested shipyard");
    }

}
