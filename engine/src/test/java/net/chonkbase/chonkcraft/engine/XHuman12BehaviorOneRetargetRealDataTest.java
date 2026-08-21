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

/** Locks behavior-one incumbent and blocked-retarget boundaries on XHuman 12. */
class XHuman12BehaviorOneRetargetRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("behavior-one grunts retarget on native chase boundaries")
    void behaviorOneGruntsRetargetOnNativeChaseBoundaries() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        Unit buildingUpgrade = unitAt(world, "unit-grunt", 19, 46);
        Unit blockedUpgrade = unitAt(world, "unit-grunt", 21, 42);
        assertNotNull(buildingUpgrade, "XHuman 12 has no native-slot-1470 grunt");
        assertNotNull(blockedUpgrade, "XHuman 12 has no native-slot-1480 grunt");
        assertEquals(1, buildingUpgrade.battleNetAiBehavior());
        assertEquals(1, blockedUpgrade.battleNetAiBehavior());

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 60) {
            mission.tick();
            int fixture = fixtureCycle(world);
            if (fixture == 25) {
                assertTargetAt(buildingUpgrade, "unit-human-guard-tower", 24, 50,
                        "an equal building score retains behavior one's incumbent");
                assertTargetAt(blockedUpgrade, "unit-human-guard-tower", 25, 42,
                        "the nearer defender still owns its opening tower goal");
            }
            if (fixture == 41) {
                assertEquals(22, buildingUpgrade.tileX());
                assertEquals(44, buildingUpgrade.tileY());
                assertTargetAt(buildingUpgrade, "unit-human-guard-tower", 25, 42,
                        "a strict building upgrade retargets after the cached NE");
                assertEquals(Direction.fromDelta(0, -1),
                        buildingUpgrade.peekHeading(),
                        "the replacement route keeps native north next");

                assertEquals(22, blockedUpgrade.tileX());
                assertEquals(42, blockedUpgrade.tileY());
                assertTargetAt(blockedUpgrade, "unit-footman", 29, 43,
                        "blocked building path work hands off to the mobile threat");
                assertEquals(19, blockedUpgrade.pathLength());
                assertEquals(Direction.fromDelta(0, 1), blockedUpgrade.peekHeading());
            }
            if (fixture == 57) {
                assertEquals(22, buildingUpgrade.tileX(),
                        "the first retarget leg pays its residual hold");
                assertEquals(44, buildingUpgrade.tileY());
                assertEquals(22, blockedUpgrade.tileX());
                assertEquals(42, blockedUpgrade.tileY());
            }
        }

        assertEquals(22, buildingUpgrade.tileX());
        assertEquals(43, buildingUpgrade.tileY(),
                "north follows the residual hold at fixture 60");
        assertEquals(23, blockedUpgrade.tileX());
        assertEquals(41, blockedUpgrade.tileY(),
                "the blocked retarget returns to the tower and steps NE at fixture 60");
        assertTargetAt(blockedUpgrade, "unit-human-guard-tower", 25, 42,
                "the next behavior-one boundary selects the stronger live goal");
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }

    private static void assertTargetAt(Unit unit, String ident, int x, int y,
            String message) {
        Unit target = unit.target();
        assertNotNull(target, message);
        assertEquals(ident, target.type().ident(), message);
        assertEquals(x, target.tileX(), message);
        assertEquals(y, target.tileY(), message);
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
