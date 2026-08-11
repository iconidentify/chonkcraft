package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.missile.MissileClass;
import net.chonkbase.chonkcraft.engine.missile.MissileType;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The order a blast reaches the people standing in it.
 *
 * <p>{@code Missile::MissileHit} does not walk the unit list. It calls
 * {@code Select} over the blast box, and
 * {@code Select} sweeps the map square by square -- y outer, x inner -- taking
 * each square's {@code UnitCache} as it comes
 * ({@code include/unit_find.h:240-272}). Where a unit stands decides when it is
 * reached; when it was built decides nothing.
 *
 * <p>That matters because every blow inside the blast draws its own damage
 * from the shared stream, and the draw is divided by the target's armour, so
 * two bystanders who swap places swap damage. On {@code maps/demo/demo03} a
 * destroyer's shell catches five units around 8,3 on cycle 58 and upstream
 * deals 5 to a knight at 7,2, 11 to a footman at 8,2, 6 to a grunt at 9,2 and
 * 7 each to a footman at 7,3 and a peasant at 7,4 -- along the rows. This implementation
 * dealt the same five draws to the same five units in the order they had been
 * created, so its knight took 9 where upstream's took 5. Every seed afterwards
 * agreed and every hit point did not; that map's first divergence moved from
 * cycle 58 to 61.
 */
class SplashOrderTest {

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    /** Range two, so the box is the three squares by three around the impact. */
    private static MissileType shell() {
        return new MissileType("missile-catapult-rock", null, MissileClass.POINT_TO_POINT,
                32, 32, 1, 1, 16, 1, 2, 4, 0, null, null, false, 0, 0, false);
    }

    private static UnitType catapult() {
        UnitType type = new UnitType("unit-catapult");
        type.setTileSize(1, 1);
        type.setHitPoints(120);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(25);
        type.setPiercingDamage(25);
        type.setMaxAttackRange(8);
        type.setMissile("missile-catapult-rock");
        type.setNumDirections(1);
        AnimationSet set = new AnimationSet("siege");
        set.put(AnimationSet.State.STILL, Animation.parse("still",
                List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.ATTACK, Animation.parse("attack",
                List.of("unbreakable begin", "frame 0", "attack", "unbreakable end",
                        "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    /**
     * A bystander that does nothing at all.
     *
     * <p>A building on purpose. A standing soldier wanders, scans for a fight
     * and faces where it was built, and all three spend numbers from the same
     * stream the damage comes off -- so a fixture built from soldiers would
     * measure the order they were created in through the back door, which is
     * the very thing under test.
     */
    private static UnitType shed() {
        UnitType type = new UnitType("unit-farm");
        type.setTileSize(1, 1);
        type.setHitPoints(400);
        type.setBuilding(true);
        type.setLandUnit(false);
        type.setArmor(0);
        type.setNumDirections(1);
        AnimationSet set = new AnimationSet("shed");
        set.put(AnimationSet.State.STILL, Animation.parse("still",
                List.of("frame 0", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    /** Where the shell lands, and the two squares it catches either side. */
    private static final int IMPACT_X = 20;
    private static final int IMPACT_Y = 20;
    private static final int NORTH_Y = IMPACT_Y - 1;
    private static final int SOUTH_Y = IMPACT_Y + 1;

    /**
     * Shells the same square with the two bystanders built in the given order,
     * and reports what each of them lost.
     *
     * @param northFirst whether the northern one is built first, and so has
     *                   the lower id and comes first in the world's own list
     * @return the damage the northern one took, then the southern one
     */
    private static int[] shellTwo(boolean northFirst) {
        World world = new World(grass(40));
        world.setMissileTypes(Map.of("missile-catapult-rock", shell()));
        world.setAllied(0, 1, false);

        Unit north;
        Unit south;
        if (northFirst) {
            north = world.createUnit(shed(), 1, IMPACT_X, NORTH_Y);
            south = world.createUnit(shed(), 1, IMPACT_X, SOUTH_Y);
        } else {
            south = world.createUnit(shed(), 1, IMPACT_X, SOUTH_Y);
            north = world.createUnit(shed(), 1, IMPACT_X, NORTH_Y);
        }
        Unit siege = world.createUnit(catapult(), 0, IMPACT_X - 6, IMPACT_Y);
        assertTrue(world.orderAttackGround(siege, IMPACT_X, IMPACT_Y),
                "the catapult would not shell open ground");

        int northWas = north.hitPoints();
        int southWas = south.hitPoints();
        for (int cycle = 0; cycle < 200; cycle++) {
            world.tick();
            if (north.hitPoints() < northWas || south.hitPoints() < southWas) {
                break;
            }
        }
        return new int[] {northWas - north.hitPoints(), southWas - south.hitPoints()};
    }

    @Test
    @DisplayName("a blast reaches its victims by where they stand, not by when they were built")
    void theSweepGoesAlongTheRows() {
        int[] northBuiltFirst = shellTwo(true);
        int[] southBuiltFirst = shellTwo(false);

        assertTrue(northBuiltFirst[0] > 0 && northBuiltFirst[1] > 0,
                "the blast did not catch both sheds, so nothing below is measured");
        // Two draws, and they have to be different numbers or the readings
        // below say nothing whichever order the blast went in.
        assertNotEquals(northBuiltFirst[0], northBuiltFirst[1],
                "both sheds took " + northBuiltFirst[0] + ". The two draws came out equal, so"
                        + " this fixture cannot tell one order from the other");

        assertEquals(northBuiltFirst[0], southBuiltFirst[0],
                "the northern shed took " + northBuiltFirst[0] + " when it was built first and "
                        + southBuiltFirst[0] + " when it was built second, from the same seed at"
                        + " the same distance. The blast is going round the units in the order"
                        + " they were created; Select sweeps the ground");
        assertEquals(northBuiltFirst[1], southBuiltFirst[1],
                "and the southern shed took " + northBuiltFirst[1] + " against "
                        + southBuiltFirst[1] + " for the same reason");
    }
}
