package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** XHuman 12's ranged chase replaces an exhausted blocked route immediately. */
class XHuman12RangedBlockedTailRetargetRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("xhuman 12 axe spends its replacement route on fixture 54")
    void xhuman12AxeSpendsReplacementRouteOnFixture54() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx12h",
                GameData.personIn(data.campaignMap(
                        "campaigns/human-exp/levelx12h")), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        Unit axe = unitAt(world, "unit-axethrower", 34, 35);
        Unit footman = unitAt(world, "unit-footman", 32, 43);
        assertNotNull(axe, "XHuman 12 has no focus axe on 34,35");
        assertNotNull(footman, "XHuman 12 has no footman on 32,43");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        Unit targetAt53 = null;
        int pathLengthAt53 = -1;
        int xAt54 = -1;
        int yAt54 = -1;
        int delayAt54 = -1;
        while (world.cycle() < 56) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 53) {
                targetAt53 = axe.target();
                pathLengthAt53 = axe.pathLength();
            }
            if (fixture == 54) {
                xAt54 = axe.tileX();
                yAt54 = axe.tileY();
                delayAt54 = axe.battleNetOrderDelay();
            }
        }

        assertNotNull(targetAt53, "the axe must still have its tower quarry");
        assertTrue(targetAt53.type().building(),
                "fixture 53 still names the tower before the free scan");
        assertEquals(1, pathLengthAt53,
                "only the blocked south tail remains at fixture 53");
        assertSame(footman, axe.target(),
                "fixture 54 retargets the footman exactly as native BNE does");
        assertEquals(36, xAt54,
                "the replacement southwest heading is spent on fixture 54");
        assertEquals(37, yAt54,
                "the axe lands on native BNE's 36,37 on fixture 54");
        assertEquals(0, delayAt54,
                "an exhausted blocked tail does not buy a ranged teardown hold");
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
