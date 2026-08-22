package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Locks the native route handoffs that meet at XHuman 12 fixture 58. */
class XHuman12Cycle58RouteHandoffRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("behavior-one route refills preserve the native cycle-58 front")
    void behaviorOneRouteRefillsPreserveNativeCycle58Front() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        Unit towerChaser = unitAt(world, "unit-grunt", 10, 90);
        Unit longRefusal = unitAt(world, "unit-grunt", 34, 38);
        Unit blockedRefill = unitAt(world, "unit-grunt", 27, 37);
        Unit residualPark = unitAt(world, "unit-grunt", 22, 37);
        Unit moveDetour = unitAt(world, "unit-ogre", 70, 35);
        assertNotNull(towerChaser, "XHuman 12 has no native-slot-1358 grunt");
        assertNotNull(longRefusal, "XHuman 12 has no native-slot-1510 grunt");
        assertNotNull(blockedRefill, "XHuman 12 has no native-slot-1514 grunt");
        assertNotNull(residualPark, "XHuman 12 has no native-slot-1517 grunt");
        assertNotNull(moveDetour, "XHuman 12 has no native-slot-1527 ogre");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 60) {
            mission.tick();
            int fixture = fixtureCycle(world);
            if (fixture == 58) {
                assertAt(towerChaser, 11, 88,
                        "the tower chase refills NW,NE instead of compass-south");
                assertAt(longRefusal, 34, 40,
                        "the retained route enters native's fifteen-count hold");
                assertAt(blockedRefill, 29, 36,
                        "the retained NE tail commits as the first residual settles");
                assertAt(residualPark, 24, 39,
                        "the blocked diagonal parks before its replacement route");
            }
            if (fixture == 59) {
                assertAt(moveDetour, 73, 34,
                        "the free-compass move detour parks its stale route tail");
            }
        }
        assertAt(residualPark, 25, 39,
                "the parked residual route refills and steps east at fixture 59");
        assertAt(moveDetour, 74, 34,
                "the parked move detour replans east at fixture 60");
    }

    @Test
    @DisplayName("an exhausted detached move detour stands down on its settle visit")
    void anExhaustedDetachedMoveDetourStandsDownOnItsSettleVisit() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        // Java 102 / native 1498 parks its east route at index 20, receives
        // the one-heading native replacement NE, and drains that heading at
        // 78,33 beside the occupied goal 79,34. The authenticated unit record
        // is Move/route-index 1 through fixture 132 and Still@581/1 on 133.
        Unit moveDetour = unitAt(world, "unit-ogre", 70, 38);
        assertNotNull(moveDetour, "XHuman 12 has no native-slot-1498 ogre");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 133) {
            mission.tick();
            int fixture = fixtureCycle(world);
            if (fixture == 121) {
                assertAt(moveDetour, 78, 33,
                        "the replacement NE heading lands on the native tile");
                assertEquals(0, moveDetour.pathLength(),
                        "the detached native route contains no stale tail");
                assertEquals(Unit.Order.MOVE, moveDetour.order(),
                        "the route remains Move while its pixels drain");
            }
            if (fixture == 132) {
                assertEquals(Unit.Order.MOVE, moveDetour.order(),
                        "the last residual cycle is still visibly Move");
            }
        }

        assertEquals(Unit.Order.STILL, moveDetour.order(),
                "the exhausted detached route stands down on fixture 133");
        assertEquals(581, moveDetour.battleNetSequenceOffset(),
                "replacement Still remains at the native ogre sequence head");
        assertEquals(1, moveDetour.battleNetAnimationTimer(),
                "the quiet replacement Still owns native timer one");
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }

    private static void assertAt(Unit unit, int x, int y, String message) {
        assertEquals(x, unit.tileX(), message);
        assertEquals(y, unit.tileY(), message);
    }

    private static Unit unitAt(World world, String ident, int x, int y) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.isAlive() && unit.isOnMap() && unit.type() != null
                    && ident.equals(unit.type().ident())
                    && unit.tileX() == x && unit.tileY() == y) {
                return unit;
            }
        }
        return null;
    }
}
