package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated one-visit park of XHuman 12 grunt 1495's south tail. */
class Xhuman12CollisionThreeCardinalTerminatorRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("xhuman 12's collision-three south tail parks before SE refill")
    void xhuman12GruntParksItsSouthTailBeforeSouthEastRefill() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit grunt = unitById(world, 105);
        assertNotNull(grunt,
                "XHuman 12 has no Java unit 105 / native grunt 1495");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 221) {
            mission.tick();
        }

        assertEquals(30, grunt.tileX(), "the south residual drains at 30,41");
        assertEquals(41, grunt.tileY(), "the south residual drains at 30,41");
        assertTrue(grunt.isMoving(), "fixture 221 still owes two south pixels");

        mission.tick();
        assertEquals(222, fixtureCycle(world));
        assertEquals(30, grunt.tileX(),
                "the residual-settle visit must not consume the replacement route");
        assertEquals(41, grunt.tileY(),
                "native remains at 30,41 behind route index twenty");
        assertFalse(grunt.isMoving(), "the south residual is fully settled");
        assertEquals(0, grunt.pathLength(),
                "the exhausted one-byte route is parked");
        assertEquals(4, grunt.battleNetCollisionCounter(),
                "the park advances collision generation three to four");
        assertEquals(1, grunt.battleNetRefusals(),
                "the sticky hard-refusal provenance survives the park");

        mission.tick();
        assertEquals(223, fixtureCycle(world));
        assertEquals(31, grunt.tileX(),
                "the following Move callback consumes south-east");
        assertEquals(42, grunt.tileY(),
                "the following Move callback consumes south-east");
        assertTrue(grunt.isMoving(),
                "the committed south-east tile owns its pixel residual");
        assertEquals(Direction.fromDelta(1, 1), grunt.lastStepHeading(),
                "the replacement route's first byte is south-east");
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
