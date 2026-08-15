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
 * XHuman 9's opening melee after the skeleton's first blow.
 *
 * <p>Authenticated campaign-1800 fixture {@code retail-xhuman-09-idle}:
 * skeleton 1431 starts at 15,118, arrives at 13,120 on cycle 26, and lands
 * its first blow on footman 1427 at cycle 55 (60 to 52). The footman has
 * already been on stationary Attack since the arrival (cycle 27) and
 * answers on cycle 57 (skeleton 30 to 24). The case's next coarse
 * divergence after that first blow is this defensive posture at cycle 56.
 */
class Xhuman09DefensiveReactionRealDataTest {

    @Test
    @DisplayName("xhuman 9's struck footman is still attacking on cycle 56")
    void xhuman9sStruckFootmanIsStillAttackingOnCycle56() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx09h", 0);
        Assumptions.assumeTrue(mission != null, "XHuman 9 is not in the pack");
        World world = mission.world();

        Unit skeleton = unitAt(world, "unit-skeleton", 15, 118);
        Unit footman = unitAt(world, "unit-footman", 13, 121);
        assertNotNull(skeleton, "XHuman 9 has no skeleton on 15,118");
        assertNotNull(footman, "XHuman 9 has no footman on 13,121");
        int skeletonOpened = skeleton.hitPoints();
        int footmanOpened = footman.hitPoints();

        Integer footmanHpAt55 = null;
        Unit.Order footmanOrderAt56 = null;
        Integer skeletonHpAt57 = null;
        while (world.cycle() < 57) {
            mission.tick();
            if (world.cycle() == 55) {
                footmanHpAt55 = footman.hitPoints();
            }
            if (world.cycle() == 56) {
                footmanOrderAt56 = footman.order();
            }
            if (world.cycle() == 57) {
                skeletonHpAt57 = skeleton.hitPoints();
            }
        }

        assertTrue(footmanHpAt55 != null && footmanHpAt55 < footmanOpened,
                "retail's skeleton first blow lands on cycle 55; the footman is still at "
                        + footmanHpAt55 + " of " + footmanOpened);
        assertEquals(Unit.Order.ATTACK, footmanOrderAt56,
                "retail's struck footman stays on Attack at cycle 56, not "
                        + footmanOrderAt56);
        assertTrue(skeletonHpAt57 != null && skeletonHpAt57 < skeletonOpened,
                "retail's footman answers on cycle 57; the skeleton is still at "
                        + skeletonHpAt57 + " of " + skeletonOpened);
        assertEquals(13, skeleton.tileX(),
                "the skeleton must still stand on 13,120 after the first exchange");
        assertEquals(120, skeleton.tileY(),
                "the skeleton must still stand on 13,120 after the first exchange");
        assertEquals(13, footman.tileX(),
                "the footman must still stand on 13,121 after the first exchange");
        assertEquals(121, footman.tileY(),
                "the footman must still stand on 13,121 after the first exchange");
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
