package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** XHuman 10's previously refused gold route parks its free residual once. */
class XHuman10GoldResidualRouteParkRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("xhuman 10 free gold residual parks at 54 and steps at 55")
    void xhuman10FreeGoldResidualParksAt54AndStepsAt55() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx10h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        Unit peon = world.unitsSnapshot().stream()
                .filter(unit -> unit.player() == 3
                        && unit.type() != null
                        && "unit-peon".equals(unit.type().ident())
                        && unit.tileX() == 58 && unit.tileY() == 8)
                .findFirst().orElse(null);
        assertNotNull(peon,
                "XHuman 10 must contain native peon 1584 / Java 16");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        advanceToFixture(mission, world, 53);
        assertPosition(peon, 58, 5, -2, 2,
                "fixture 53 still owes the final diagonal pixels");
        assertEquals(1, peon.battleNetCollisionCounter(),
                "the replacement route retains its earlier refusal count");
        assertEquals(2, peon.pathLength(),
                "the replacement route still holds northwest then north");
        assertEquals(1, peon.battleNetPathStepsTaken(),
                "the diagonal is the replacement route's first spent step");
        assertEquals(Direction.fromDelta(1, -1), peon.lastStepHeading(),
                "the draining replacement step is northeast");
        assertEquals(Direction.fromDelta(-1, -1), peon.peekHeading(),
                "northwest is the cached remainder's blocked head");
        assertEquals(Direction.fromDelta(0, -1), peon.peekHeadingAtDepth(1),
                "north is the free optimized remainder behind it");

        mission.tick();
        assertPosition(peon, 58, 5, 0, 0,
                "fixture 54 parks the free north remainder at route index 20");
        assertEquals(2, peon.pathLength(),
                "the parked route retains the two-heading optimized remainder");
        assertTrue(peon.battleNetWoodRouteIndex20(),
                "the residual park must survive until the next decide visit");

        mission.tick();
        assertPosition(peon, 58, 4, 0, 32,
                "fixture 55 commits north after the one-visit route park");

        advanceToFixture(mission, world, 70);
        assertPosition(peon, 58, 4, 0, 2,
                "fixture 70 still owes the final two north pixels");

        mission.tick();
        assertPosition(peon, 58, 4, 0, 0,
                "fixture 71 settles on the mine skirt before staged entry");

        advanceToFixture(mission, world, 73);
        assertPosition(peon, 58, 4, 0, 0,
                "action 25 keeps its final quiet visit at fixture 73");

        mission.tick();
        assertPosition(peon, 59, 3, -32, 32,
                "fixture 74 starts the staged southeast mine entry");
    }

    private static void advanceToFixture(Mission mission, World world,
            int fixture) {
        while (world.cycle() - BNE_INITIALIZATION_TICKS < fixture) {
            mission.tick();
        }
    }

    private static void assertPosition(Unit unit, int x, int y,
            int offsetX, int offsetY, String message) {
        assertEquals(x, unit.tileX(), message + " (x)");
        assertEquals(y, unit.tileY(), message + " (y)");
        assertEquals(offsetX, unit.offsetX(), message + " (offset x)");
        assertEquals(offsetY, unit.offsetY(), message + " (offset y)");
    }
}
