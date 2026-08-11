package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Non-capital sea patrol keeps walking when a rewritten goal lands on coast.
 *
 * <p>XHuman 07 submarine 1511 sat at 20,52 with coast goal 18,51 (empty FOUND
 * path). Building-footprint Still used to treat every non-open-water goal as
 * a failed shipyard approach and promote Still at fixture 48 while native
 * double-stepped west to open water 18,52. Coast empty-FOUND snaps to a free
 * double-step open-water tile; building footprints still Still.
 */
class NavalPatrolCoastGoalTest {

    private static GameMap waterWithCoastAndBuilding(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.WATER_ALLOWED);
            }
        }
        // Coast strip.
        for (int x = 0; x < size; x++) {
            map.field(x, 4).setFlags(
                    TileFlag.WATER_ALLOWED | TileFlag.COAST_ALLOWED);
        }
        // Building footprint (shipyard edge stand-in).
        map.field(10, 10).setFlags(
                TileFlag.WATER_ALLOWED | TileFlag.BUILDING);
        return map;
    }

    private static UnitType submarine() {
        UnitType type = new UnitType("unit-orc-submarine");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(60);
        type.setSpeed(8);
        type.setSeaUnit(true);
        type.setCanAttack(true);
        type.setCanTargetSea(true);
        type.setBasicDamage(10);
        type.setPiercingDamage(0);
        type.setMaxAttackRange(4);
        type.setSightRange(5);
        type.setNumDirections(8);
        AnimationSet set = new AnimationSet("sub");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 16", "wait 1",
                "frame 5", "move 16", "unbreakable end", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    private static Unit armPatrol(World world, UnitType type,
            int x, int y, int goalX, int goalY) {
        Unit sub = world.createUnit(type, 0, x, y);
        assertTrue(sub != null, "sub places");
        sub.setBattleNetDoubleStep(true);
        sub.setOrder(Unit.Order.PATROL);
        sub.setOrderTarget(goalX, goalY);
        sub.setPatrol(x + 4, y);
        sub.setTile(x, y);
        sub.clearPath();
        sub.setBattleNetOrderDelay(0);
        return sub;
    }

    @Test
    @DisplayName("a submarine patrol on a coast goal does not still-promote")
    void aSubmarinePatrolOnACoastGoalDoesNotStillPromote() {
        GameMap map = waterWithCoastAndBuilding(16);
        World world = new World(map);
        world.fog().revealAll(0);
        world.restoreRandom(1, 0);

        // Even lattice ship; odd coast goal so double-step pathfind often
        // answers empty FOUND (native return-leg shape: 20,52 → coast 18,51).
        Unit sub = armPatrol(world, submarine(), 6, 6, 5, 4);
        int startX = sub.tileX();
        int startY = sub.tileY();
        for (int i = 0; i < 16; i++) {
            world.tick();
            if (sub.order() == Unit.Order.STILL) {
                break;
            }
            if (sub.tileX() != startX || sub.tileY() != startY) {
                break;
            }
        }
        assertEquals(Unit.Order.PATROL, sub.order(),
                "coast empty-FOUND must not Still "
                        + "(XHuman 07 sub 1511 fixture 48)");
        assertTrue(sub.tileX() != startX || sub.tileY() != startY,
                "must double-step onto open water after coast snap");
    }

    @Test
    @DisplayName("a submarine patrol on a building footprint still promotes still")
    void aSubmarinePatrolOnABuildingFootprintStillPromotesStill() {
        GameMap map = waterWithCoastAndBuilding(16);
        World world = new World(map);
        world.fog().revealAll(0);
        world.restoreRandom(1, 0);

        Unit sub = armPatrol(world, submarine(), 8, 8, 10, 10);
        for (int i = 0; i < 12; i++) {
            world.tick();
            if (sub.order() == Unit.Order.STILL) {
                break;
            }
        }
        assertEquals(Unit.Order.STILL, sub.order(),
                "building-footprint rewrite still Still-promotes "
                        + "(XORc 11 destroyer 1519)");
    }
}
