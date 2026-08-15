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

/**
 * XHuman 10's cycle-52 and cycle-54 HP drops on the orc grunt that
 * opened at 78,93.
 *
 * <p>Authenticated campaign-1800 fixture {@code retail-xhuman-10-idle}:
 * grunt 1471 starts Still at 78,93 and arrives on 81,90 at fixture 41.
 * Archer 1470 at 84,94 opens when that grunt first stands at 80,91; its
 * type-15 arrow frees at fixture 52 (60 to 54). Footman 1479 on 82,91
 * is already on stationary Attack from the arrival and lands opcode ten
 * at fixture 54 (54 to 46). The case's coarse first divergence after the
 * route-ownership wave is this second blow at cycle 54.
 */
class Xhuman10DamageTimingRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("xhuman 10's opening grunt is hurt on cycle 54")
    void xhuman10sOpeningGruntIsHurtOnCycle54() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 0);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        Unit grunt = unitAt(world, "unit-grunt", 78, 93);
        Unit footman = unitAt(world, "unit-footman", 82, 91);
        assertNotNull(grunt, "XHuman 10 has no grunt on 78,93");
        assertNotNull(footman, "XHuman 10 has no footman on 82,91");
        int opened = grunt.hitPoints();

        // Two HandleEachCycle warmup ticks precede fixture cycle 1 -- the
        // same boundary EngineTrace and the Human 12 playability click use.
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        Integer hpAt52 = null;
        Integer hpAt54 = null;
        Unit.Order footmanOrderAt54 = null;
        while (world.cycle() < 56) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 52) {
                hpAt52 = grunt.hitPoints();
            }
            if (fixture == 54) {
                hpAt54 = grunt.hitPoints();
                footmanOrderAt54 = footman.order();
            }
        }

        assertTrue(hpAt52 != null && hpAt52 < opened,
                "retail's first blow lands on cycle 52; the grunt is still at "
                        + hpAt52 + " of " + opened);
        assertTrue(hpAt54 != null && hpAt54 < hpAt52,
                "retail's second blow lands on cycle 54; the grunt is still at "
                        + hpAt54 + " of " + hpAt52);
        assertEquals(Unit.Order.ATTACK, footmanOrderAt54,
                "retail's footman stays on Attack at cycle 54, not "
                        + footmanOrderAt54);
        assertEquals(81, grunt.tileX(),
                "the grunt must stand on 81,90 when the second blow lands");
        assertEquals(90, grunt.tileY(),
                "the grunt must stand on 81,90 when the second blow lands");
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
