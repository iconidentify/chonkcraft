package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated occupied-gold-skirt pathfinding from retail Orc 11. */
class Orc11MarkedGoldSkirtRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("orc 11's gold route may end under a quiescent peasant")
    void orc11PeasantTakesTheNativeWestRoutePastAnOccupiedGoldSkirt() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/orc/level11o";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Orc 11 is not in the pack");
        World world = mission.world();
        Unit peasant = unitById(world, 139);
        assertNotNull(peasant, "Orc 11 has no Java unit 139");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 137) {
            mission.tick();
        }

        assertEquals(13, peasant.tileX(),
                "fixture 137 consumes the route's opening south-west step");
        assertEquals(124, peasant.tileY(),
                "the opening south-west step lands on native's 13,124");
        assertEquals(6, peasant.peekHeading(),
                "native's next heading is west, not a second south-west");

        while (fixtureCycle(world) < 153) {
            mission.tick();
        }

        assertEquals(12, peasant.tileX(),
                "fixture 153 consumes native's second route heading");
        assertEquals(124, peasant.tileY(),
                "the occupied marked skirt must not bend the peasant south");
    }

    @Test
    @DisplayName("orc 11's later gold route ends on the same-cycle south-west skirt")
    void orc11LaterPeasantKeepsTheNativeSixHeadingMineApproach() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/orc/level11o";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Orc 11 is not in the pack");
        World world = mission.world();
        Unit peasant = unitById(world, 95);
        Unit crossingPeasant = unitById(world, 110);
        assertNotNull(peasant, "Orc 11 has no Java unit 95 / native 1505");
        assertNotNull(crossingPeasant,
                "Orc 11 has no Java unit 110 / native 1490");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 137) {
            mission.tick();
        }

        assertEquals(8, crossingPeasant.tileX(),
                "native slot 1490 has just crossed south-west onto the skirt");
        assertEquals(122, crossingPeasant.tileY(),
                "the crossing peasant occupies native's terminal skirt cell");
        assertEquals(0, crossingPeasant.battleNetCollisionCounter(),
                "the same-cycle crossing carries no native collision debt");
        assertSame(peasant.resourceUnit(), crossingPeasant.resourceUnit(),
                "both outbound workers belong to the same mine queue");
        assertEquals(UnitType.Resource.GOLD, crossingPeasant.carrying(),
                "the outbound worker carries the native gold task tag");
        assertTrue(crossingPeasant.isMoving(),
                "the blocker is draining its committed terminal stride");
        assertEquals(0, crossingPeasant.pathLength(),
                "the crossing worker has consumed its last route byte");
        assertTrue(crossingPeasant.routeSpent(),
                "the crossing worker owns only its terminal residual");
        assertEquals(5, peasant.pathLength(),
                "after consuming the opening SE, native's six-byte route has five left");
        assertEquals(Direction.fromDelta(1, 1), peasant.peekHeading(),
                "the second native heading remains south-east");

        while (fixtureCycle(world) < 201) {
            mission.tick();
        }

        assertEquals(9, peasant.tileX(),
                "the first south-west lands at native's 9,121");
        assertEquals(121, peasant.tileY(),
                "native is one diagonal step from the marked mine skirt");
        assertEquals(1, peasant.pathLength(),
                "no inserted south step may remain before the final diagonal");
        assertEquals(Direction.fromDelta(-1, 1), peasant.peekHeading(),
                "the terminal cached heading is south-west");

        while (fixtureCycle(world) < 217) {
            mission.tick();
        }

        assertEquals(8, peasant.tileX(),
                "native finishes the six-heading approach on the occupied skirt");
        assertEquals(122, peasant.tileY(),
                "the final south-west must not be delayed by an inserted south");
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
