package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
 * Who a tower shoots when the bills tie, and what a tower's bill even is.
 *
 * <p>Two rules from campaigns/human-exp/levelx12h's opening, both upstream's
 * own. The candidates arrive in the box scan's order -- {@code Select} walks
 * the tile cache row by row ({@code unit/unit_find.h:286-294}) -- and the
 * finder keeps the first best it meets, so a tie between two identical
 * grunts goes to the northernmost. And the pathfinder's opening shortcut,
 * {@code AStarFindSimplePath}'s within-range arm,
 * measures the straight line corner to corner with no cost test at all,
 * which is the only reason an immobile attacker's bill for an in-range enemy
 * reads {@code PF_REACHED} rather than unreachable: a guard tower can enter
 * nothing, not even its own square.
 */
class TargetTieBreakTest {

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static UnitType tower() {
        UnitType type = new UnitType("unit-human-guard-tower");
        type.setTileSize(2, 2);
        type.setHitPoints(130);
        type.setBuilding(true);
        type.setSightRange(9);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setMaxAttackRange(6);
        type.setReactRangePerson(6);
        type.setReactRangeComputer(6);
        type.setBasicDamage(4);
        type.setPiercingDamage(12);
        return type;
    }

    private static UnitType grunt() {
        UnitType type = new UnitType("unit-grunt");
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setNumDirections(8);
        type.setSightRange(4);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setMaxAttackRange(1);
        type.setBasicDamage(6);
        type.setPriority(60);
        return type;
    }

    /** Ticks until the tower has chosen, bounded so a failure says so. */
    private static void tickUntilChosen(World world, Unit tower) {
        for (int cycle = 0; cycle < 20 && tower.target() == null; cycle++) {
            world.tick();
        }
        assertNotNull(tower.target(), "the tower never picked anybody at all");
    }

    @Test
    @DisplayName("an immobile tower's bill measures the straight line, not a path it could never walk")
    void theTowerPricesWhatItCanShoot() {
        World world = new World(grass(32));
        // Walled in, as levelx12h's tower is walled in by its own crowd: the
        // full search cannot leave the footprint, so only the shortcut can
        // ever answer for it.
        for (int y = 9; y <= 12; y++) {
            for (int x = 9; x <= 12; x++) {
                boolean inside = x >= 10 && x <= 11 && y >= 10 && y <= 11;
                if (!inside) {
                    world.map().field(x, y).setFlags(TileFlag.UNPASSABLE);
                }
            }
        }
        Unit tower = world.createUnit(tower(), 0, 10, 10);
        // Created first, scanned first: the far grunt sits north, so a port
        // whose every bill reads unreachable ties them and hands the shot to
        // the scan's first. Only the corner-to-corner shortcut prices the
        // southern one higher: distance six against the tower's corner is
        // within range and bills PF_REACHED, distance eight is a full search
        // an immobile tower always loses.
        // Both grunts are in attack range by the footprint measure the
        // range gate uses -- six from the tower's nearest tile -- but only
        // the southern one is within six of the tower's own corner, which
        // is the line the shortcut draws: the north-eastern one is seven
        // corner to corner and its full search dies inside the wall.
        Unit far = world.createUnit(grunt(), 1, 14, 4);
        Unit near = world.createUnit(grunt(), 1, 10, 16);
        tickUntilChosen(world, tower);
        assertEquals(near, tower.target(),
                "the tower shot the grunt it could never price. AStarFindSimplePath's"
                        + " within-range arm answers PF_REACHED corner to corner with no"
                        + " cost test (astar.cpp:960-965); demanding the tower be able"
                        + " to enter squares makes every bill unreachable and the tie"
                        + " falls to the wrong grunt -- levelx12h's tower shot the one"
                        + " target upstream prices lowest");
        assertEquals(true, far.isAlive(), "the far grunt was only ever a decoy");
    }

    @Test
    @DisplayName("a tie between identical grunts goes to the one the scan reaches first")
    void aTieGoesToTheNorthernmost() {
        World world = new World(grass(32));
        Unit tower = world.createUnit(tower(), 0, 10, 10);
        // The southern grunt is created first, so a walk over the unit list
        // asks it first and keeps it on the tie. The box scan asks rows top
        // to bottom and takes the northern one.
        world.createUnit(grunt(), 1, 10, 16);
        Unit northern = world.createUnit(grunt(), 1, 10, 4);
        tickUntilChosen(world, tower);
        assertEquals(northern, tower.target(),
                "the tie went to creation order. Select walks the tile cache row by"
                        + " row (unit_find.h:286-294) and the finder keeps the first"
                        + " best it meets, so two identical bills resolve to the"
                        + " northernmost candidate, whatever order they were founded in");
    }

    @Test
    @DisplayName("a tower's standby order retains the goal of its completed shot")
    void aCompletedStandingSwingKeepsItsGoalWhileReturningToStandby() {
        World world = new World(grass(32));
        UnitType towerType = tower();
        AnimationSet animations = new AnimationSet("tower");
        animations.put(AnimationSet.State.STILL, Animation.parse("still",
                List.of("frame 0", "wait 1")));
        animations.put(AnimationSet.State.ATTACK, Animation.parse("attack",
                List.of("unbreakable begin", "frame 0", "attack", "wait 3",
                        "frame 0", "unbreakable end", "wait 1")));
        towerType.setAnimationSet(animations);
        Unit tower = world.createUnit(towerType, 0, 10, 10);
        UnitType quarryType = grunt();
        quarryType.setHitPoints(4000);
        Unit quarry = world.createUnit(quarryType, 1, 14, 10);

        tickUntilChosen(world, tower);
        for (int cycle = 0; cycle < 12; cycle++) {
            world.tick();
        }

        assertEquals(quarry, tower.target(),
                "COrder_Still cleared its goal when SUB_STILL_ATTACK returned to standby");
    }
}
