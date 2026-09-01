package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Locks the offered-building target input used by a settled melee redraw. */
class XHuman12OfferedBuildingResidualAxisSkirtRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("an offered building residual aims its mobile retarget along the reached skirt axis")
    void offeredBuildingResidualKeepsTheReachedMobileSkirtAxis() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        // Native slot 1508 / Java 92 drains the west step of its guard-tower
        // route while that same tower remains in COrder_Attack's offered slot.
        // Target scan replaces it with footman 1478 / Java 123. Since row 44
        // is already on the footman's melee skirt, native draws six west bytes
        // toward (32,44) while keeping orderXY at the real target point (32,43).
        Unit grunt = unitById(world, 92);
        Unit tower = unitById(world, 115);
        Unit footman = unitById(world, 123);
        assertNotNull(grunt, "XHuman 12 has no native-slot-1508 grunt");
        assertNotNull(tower, "XHuman 12 has no incumbent guard tower");
        assertNotNull(footman, "XHuman 12 has no replacement footman");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 286) {
            mission.tick();
        }

        assertEquals(38, grunt.tileX());
        assertEquals(44, grunt.tileY());
        assertEquals(tower, grunt.target());
        assertEquals(tower, grunt.offeredTarget(),
                "the old building must still own the offered-target slot");
        assertEquals(1, grunt.battleNetCollisionCounter());

        mission.tick();
        assertEquals(287, fixtureCycle(world));
        assertEquals(37, grunt.tileX());
        assertEquals(44, grunt.tileY(),
                "the replacement route must consume west, not northwest");
        assertEquals(footman, grunt.target());
        assertEquals(32, grunt.pathGoalX(),
                "the stored goal remains the mobile target point");
        assertEquals(43, grunt.pathGoalY());
        assertEquals(5, grunt.pathLength());
        for (int depth = 0; depth < grunt.pathLength(); depth++) {
            assertEquals(Direction.fromDelta(-1, 0),
                    grunt.peekHeadingAtDepth(depth),
                    "native's remaining fixture-287 heading at depth " + depth);
        }
        assertEquals(0, grunt.battleNetCollisionCounter(),
                "the replacement retires the building collision owner");

        while (fixtureCycle(world) < 322) {
            mission.tick();
        }
        assertEquals(35, grunt.tileX());
        assertEquals(44, grunt.tileY(),
                "the third cached heading remains on the reached skirt row");
        assertEquals(3, grunt.pathLength());
    }

    @Test
    @DisplayName("an offered-building wall rejoin retains the remaining axis ray")
    void offeredBuildingWallRejoinRetainsTheRemainingAxisRay() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        Unit grunt = unitById(world, 80);
        Unit footman = unitById(world, 123);
        assertNotNull(grunt, "XHuman 12 has no native-slot-1520 grunt");
        assertNotNull(footman, "XHuman 12 has no replacement footman");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 324) {
            mission.tick();
        }
        assertEquals(38, grunt.tileX());
        assertEquals(44, grunt.tileY());
        assertEquals(2, grunt.pathLength(),
                "the old west residual and its cached tail remain live");

        mission.tick();
        assertEquals(325, fixtureCycle(world));
        assertEquals(37, grunt.tileX());
        assertEquals(43, grunt.tileY(),
                "native opens northwest around the moving axis blocker");
        assertEquals(footman, grunt.target());
        assertEquals(32, grunt.pathGoalX());
        assertEquals(43, grunt.pathGoalY(),
                "orderXY publishes the replacement's real point");
        int west = Direction.fromDelta(-1, 0);
        int southwest = Direction.fromDelta(-1, 1);
        int[] remaining = {west, west, southwest, west, west};
        assertEquals(remaining.length, grunt.pathLength());
        for (int depth = 0; depth < remaining.length; depth++) {
            assertEquals(remaining[depth], grunt.peekHeadingAtDepth(depth),
                    "native fixture-325 route byte at depth " + depth);
        }
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }

    private static Unit unitById(World world, int id) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.id() == id) {
                return unit;
            }
        }
        return null;
    }
}
