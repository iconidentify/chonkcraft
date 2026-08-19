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
 * stays on Attack at cycle 56 before answering on cycle 57 (skeleton 30 to
 * 24). Separate neighboring-footman reactions make cycle 56 the case's next
 * coarse frontier after this focal exchange.
 */
class Xhuman09DefensiveReactionRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("xhuman 9's struck footman is still attacking on cycle 56")
    void xhuman9sStruckFootmanIsStillAttackingOnCycle56() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx09h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 9 is not in the pack");
        World world = mission.world();

        Unit skeleton = unitAt(world, "unit-skeleton", 15, 118);
        Unit footman = unitAt(world, "unit-footman", 13, 121);
        assertNotNull(skeleton, "XHuman 9 has no skeleton on 15,118");
        assertNotNull(footman, "XHuman 9 has no footman on 13,121");
        int skeletonOpened = skeleton.hitPoints();
        int footmanOpened = footman.hitPoints();

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        Integer skeletonOffsetXAt41 = null;
        Integer skeletonOffsetYAt41 = null;
        Integer skeletonOffsetXAt45 = null;
        Integer skeletonOffsetYAt45 = null;
        Integer skeletonOffsetXAt46 = null;
        Integer skeletonOffsetYAt46 = null;
        Integer footmanHpAt54 = null;
        Integer footmanHpAt55 = null;
        Unit.Order footmanOrderAt56 = null;
        Integer skeletonHpAt57 = null;
        while (fixtureCycle(world) < 57) {
            mission.tick();
            if (fixtureCycle(world) == 41) {
                skeletonOffsetXAt41 = skeleton.offsetX();
                skeletonOffsetYAt41 = skeleton.offsetY();
            }
            if (fixtureCycle(world) == 45) {
                skeletonOffsetXAt45 = skeleton.offsetX();
                skeletonOffsetYAt45 = skeleton.offsetY();
            }
            if (fixtureCycle(world) == 46) {
                skeletonOffsetXAt46 = skeleton.offsetX();
                skeletonOffsetYAt46 = skeleton.offsetY();
            }
            if (fixtureCycle(world) == 54) {
                footmanHpAt54 = footman.hitPoints();
            }
            if (fixtureCycle(world) == 55) {
                footmanHpAt55 = footman.hitPoints();
            }
            if (fixtureCycle(world) == 56) {
                footmanOrderAt56 = footman.order();
            }
            if (fixtureCycle(world) == 57) {
                skeletonHpAt57 = skeleton.hitPoints();
            }
        }

        assertEquals(7, skeletonOffsetXAt41,
                "script.bin leaves seven horizontal walk pixels on cycle 41");
        assertEquals(-7, skeletonOffsetYAt41,
                "the south-west chase still owes seven vertical pixels on cycle 41");
        assertEquals(2, skeletonOffsetXAt45,
                "the skeleton still owes two horizontal pixels on cycle 45");
        assertEquals(-2, skeletonOffsetYAt45,
                "the skeleton still owes two vertical pixels on cycle 45");
        assertEquals(0, skeletonOffsetXAt46,
                "the native Move body reaches the tile anchor on cycle 46");
        assertEquals(0, skeletonOffsetYAt46,
                "the native Move body reaches the tile anchor on cycle 46");
        assertEquals(footmanOpened, footmanHpAt54,
                "the skeleton must finish its borrowed walk pixels before attacking; "
                        + "retail leaves the footman untouched through cycle 54");
        assertEquals(52, footmanHpAt55,
                "retail's skeleton first blow lands on cycle 55");
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

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
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
