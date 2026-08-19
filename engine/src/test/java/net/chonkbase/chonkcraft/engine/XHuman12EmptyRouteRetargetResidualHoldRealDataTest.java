package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * XHuman 12's empty-route retarget pays Attack-four after its first residual.
 *
 * <p>Authenticated campaign-1800 fixture {@code retail-xhuman-12-idle}:
 * grunt 1507 exhausts its old route, retargets the knight on 30,44 and takes
 * a north first step on fixture 36. When that step's pixels settle on fixture
 * 52, native holds Attack-four through 54. Only on fixture 55 does it retarget
 * the footman on 32,43 and commit east to 28,38. Java used to spend that east
 * heading on fixture 52, three action visits early.</p>
 */
class XHuman12EmptyRouteRetargetResidualHoldRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("xhuman 12 holds an empty-route retarget residual through fixture 54")
    void xhuman12HoldsEmptyRouteRetargetResidualThroughFixture54() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx12h",
                GameData.personIn(data.campaignMap(
                        "campaigns/human-exp/levelx12h")), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        Unit grunt = unitAt(world, "unit-grunt", 25, 38);
        Unit knight = unitAt(world, "unit-knight", 30, 44);
        Unit footman = unitAt(world, "unit-footman", 32, 43);
        assertNotNull(grunt, "XHuman 12 has no focus grunt opening on 25,38");
        assertNotNull(knight, "XHuman 12 has no knight on 30,44");
        assertNotNull(footman, "XHuman 12 has no footman on 32,43");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        Unit targetAt36 = null;
        Unit targetAt52 = null;
        Unit targetAt55 = null;
        int xAt52 = -1;
        int yAt52 = -1;
        int delayAt52 = -1;
        int xAt54 = -1;
        int yAt54 = -1;
        int xAt55 = -1;
        int yAt55 = -1;
        while (world.cycle() < 58) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 36) {
                targetAt36 = grunt.target();
            }
            if (fixture == 52) {
                targetAt52 = grunt.target();
                xAt52 = grunt.tileX();
                yAt52 = grunt.tileY();
                delayAt52 = grunt.battleNetOrderDelay();
            }
            if (fixture == 54) {
                xAt54 = grunt.tileX();
                yAt54 = grunt.tileY();
            }
            if (fixture == 55) {
                targetAt55 = grunt.target();
                xAt55 = grunt.tileX();
                yAt55 = grunt.tileY();
            }
        }

        assertSame(knight, targetAt36,
                "the exhausted route retargets to the knight on fixture 36");
        assertSame(knight, targetAt52,
                "the residual hold must precede the fixture-55 free scan");
        assertEquals(27, xAt52,
                "the held grunt must not spend east on fixture 52");
        assertEquals(38, yAt52,
                "the held grunt stays on native's 27,38");
        assertEquals(2, delayAt52,
                "Attack-four has two quiet visits left after its arm visit");
        assertEquals(27, xAt54,
                "native remains held through fixture 54");
        assertEquals(38, yAt54,
                "native remains on 27,38 through fixture 54");
        assertSame(footman, targetAt55,
                "fixture 55 free-scans back to the footman");
        assertEquals(28, xAt55,
                "the first post-hold east heading lands on fixture 55");
        assertEquals(38, yAt55,
                "the post-hold step reaches native's 28,38");
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
