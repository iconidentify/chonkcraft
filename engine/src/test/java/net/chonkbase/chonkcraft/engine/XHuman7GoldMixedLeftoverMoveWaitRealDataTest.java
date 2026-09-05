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
 * A gold-approach mixed leftover pays Move 15 and keeps its cached bytes.
 *
 * <p>Authenticated campaign-1800 fixture {@code retail-xhuman-07-idle}:
 * peon 1446 / Java 154 residual-settles at (110,106) with leftover
 * {@code W,SW} onto allied peon 1458. Native writes Move-start/15, keeps
 * those two bytes, and consumes west at fixture 573. Java used to drop the
 * leftover, bump collision through eight, then start a fourteen-count that
 * spent west at 579.</p>
 */
class XHuman7GoldMixedLeftoverMoveWaitRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("a gold mixed leftover spends west after one move-fifteen band")
    void aGoldMixedLeftoverSpendsWestAfterOneMoveFifteenBand() {
        Mission mission = mission("campaigns/human-exp/levelx07h");
        Unit peon = byId(mission.world(), 154);
        assertNotNull(peon, "XHuman 7 has no Java twin for native peon 1446");

        tickThrough(mission, 557);
        assertEquals(110, peon.tileX(),
                "the peon is still draining its west residual");
        assertEquals(106, peon.tileY());
        assertEquals(2, peon.pathLength(),
                "the mixed leftover W,SW is still cached");
        assertEquals(6, peon.peekHeading(),
                "the leftover head is west");
        assertEquals(5, peon.peekHeadingAtDepth(1),
                "the leftover tail is south-west");
        assertEquals(1, peon.battleNetCollisionCounter());

        tickThrough(mission, 558);
        assertEquals(110, peon.tileX(),
                "the blocked west cell does not commit on the settle visit");
        assertEquals(2, peon.pathLength(),
                "native keeps the mixed leftover through Move 15");
        assertEquals(2, peon.battleNetCollisionCounter(),
                "the settle visit advances one collision generation");
        assertEquals(14, peon.battleNetOrderDelay(),
                "the blocked leftover owns one fifteen-count, not two");

        tickThrough(mission, 572);
        assertEquals(110, peon.tileX(),
                "the fifteen-count still owns the visit before the spend");
        assertEquals(106, peon.tileY());
        assertEquals(2, peon.battleNetCollisionCounter(),
                "collision must not climb while the leftover is parked");

        tickThrough(mission, 573);
        assertEquals(109, peon.tileX(),
                "native consumes cached west at fixture 573");
        assertEquals(106, peon.tileY());
        assertTrue(peon.pathLength() <= 1,
                "west is spent; south-west may remain");
    }

    private static Mission mission(String map) {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        return mission;
    }

    private static void tickThrough(Mission mission, int fixtureCycle) {
        while (mission.world().cycle() - BNE_INITIALIZATION_TICKS
                < fixtureCycle) {
            mission.tick();
        }
    }

    private static Unit byId(World world, int id) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.id() == id) {
                return unit;
            }
        }
        return null;
    }
}
