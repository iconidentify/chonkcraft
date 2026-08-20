package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** XHuman 7's second gold-route residual drops its blocked stale tail. */
class XHuman07SecondGoldResidualRefillRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("xhuman 7 second gold residual replans at 53 and steps at 54")
    void xhuman7SecondGoldResidualReplansAt53AndStepsAt54() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx07h",
                GameData.personIn(data.campaignMap(
                        "campaigns/human-exp/levelx07h")), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 7 is not in the pack");
        World world = mission.world();

        Unit peon = unitAt(world, "unit-peon", 102, 102);
        Unit mine = unitAt(world, "unit-gold-mine", 106, 104);
        assertNotNull(peon, "XHuman 7 has no focus peon on 102,102");
        assertNotNull(mine, "XHuman 7 has no gold mine on 106,104");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        int x53 = -1;
        int y53 = -1;
        int path53 = -1;
        int delay53 = -1;
        int x54 = -1;
        int y54 = -1;
        int x70 = -1;
        int y70 = -1;
        int x73 = -1;
        int y73 = -1;
        boolean removed89 = false;
        int x89 = -1;
        int y89 = -1;
        while (world.cycle() < 94) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 53) {
                x53 = peon.tileX();
                y53 = peon.tileY();
                path53 = peon.pathLength();
                delay53 = peon.battleNetOrderDelay();
            }
            if (fixture == 54) {
                x54 = peon.tileX();
                y54 = peon.tileY();
            }
            if (fixture == 70) {
                x70 = peon.tileX();
                y70 = peon.tileY();
            }
            if (fixture == 73) {
                x73 = peon.tileX();
                y73 = peon.tileY();
            }
            if (fixture == 89) {
                removed89 = peon.removed();
                x89 = peon.tileX();
                y89 = peon.tileY();
            }
        }

        assertEquals(104, x53, "fixture 53 only settles the second east residual");
        assertEquals(103, y53, "the stale east tail is parked in place");
        assertEquals(0, path53, "native parks route index 20 on the second residual");
        assertEquals(0, delay53, "the second residual does not inherit the first-residual hold");
        assertEquals(105, x54, "the refill steps southeast on its next visit");
        assertEquals(104, y54, "the refill bypasses the east-moving ally");
        assertEquals(105, x70, "the southeast residual drains through fixture 70");
        assertEquals(104, y70, "action 25 starts on the mine skirt");
        assertEquals(106, x73, "the staged mine entry spends at fixture 73");
        assertEquals(105, y73, "the staged entry first moves southeast");
        assertTrue(removed89, "the peon enters the mine at fixture 89");
        assertEquals(106, x89, "mine entry restores the resource anchor x");
        assertEquals(104, y89, "mine entry restores the resource anchor y");
    }

    private static Unit unitAt(World world, String ident, int x, int y) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.isAlive() && unit.type() != null
                    && ident.equals(unit.type().ident())
                    && unit.tileX() == x && unit.tileY() == y) {
                return unit;
            }
        }
        return null;
    }
}
