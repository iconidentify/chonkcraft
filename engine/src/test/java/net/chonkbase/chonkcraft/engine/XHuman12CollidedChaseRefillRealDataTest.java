package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** XHuman 12's collided melee chase refills one visit after residual settle. */
class XHuman12CollidedChaseRefillRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("xhuman 12 collided chase refills at 56 and steps at 71")
    void xhuman12CollidedChaseRefillsAt56AndStepsAt71() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx12h",
                GameData.personIn(data.campaignMap(
                        "campaigns/human-exp/levelx12h")), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        Unit grunt = unitAt(world, "unit-grunt", 31, 38);
        Unit footman = unitAt(world, "unit-footman", 32, 43);
        assertNotNull(grunt, "XHuman 12 has no focus grunt on 31,38");
        assertNotNull(footman, "XHuman 12 has no footman on 32,43");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        int x55 = -1;
        int y55 = -1;
        int path55 = -1;
        int x56 = -1;
        int y56 = -1;
        int path56 = -1;
        int heading56 = -1;
        int delay56 = -1;
        int x70 = -1;
        int y70 = -1;
        int x71 = -1;
        int y71 = -1;
        while (world.cycle() < 74) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 55) {
                x55 = grunt.tileX();
                y55 = grunt.tileY();
                path55 = grunt.pathLength();
            }
            if (fixture == 56) {
                x56 = grunt.tileX();
                y56 = grunt.tileY();
                path56 = grunt.pathLength();
                heading56 = grunt.peekHeading();
                delay56 = grunt.battleNetOrderDelay();
            }
            if (fixture == 70) {
                x70 = grunt.tileX();
                y70 = grunt.tileY();
            }
            if (fixture == 71) {
                x71 = grunt.tileX();
                y71 = grunt.tileY();
            }
        }

        assertSame(footman, grunt.target(),
                "the refill remains a chase of native's original footman");
        assertEquals(32, x55, "fixture 55 only settles the old east residual");
        assertEquals(38, y55, "the route terminator cannot step southeast");
        assertEquals(0, path55, "native parks the spent route at fixture 55");
        assertEquals(32, x56, "the new route's occupied south cell is refused");
        assertEquals(38, y56, "the refusal keeps the grunt on 32,38");
        assertEquals(4, path56, "native stores S,SW,SW,SE at fixture 56");
        assertEquals(Direction.fromDelta(0, 1), heading56,
                "the refill soft-clears the moving ally and opens south");
        assertEquals(14, delay56,
                "timer fifteen leaves fourteen quiet scheduler visits");
        assertEquals(32, x70, "the complete refusal hold lasts through 70");
        assertEquals(38, y70, "the grunt stays on 32,38 through fixture 70");
        assertEquals(32, x71, "the first stored south heading spends at 71");
        assertEquals(39, y71, "native reaches 32,39 on fixture 71");
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
