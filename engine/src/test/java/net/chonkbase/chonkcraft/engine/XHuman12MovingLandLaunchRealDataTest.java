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

/** Authenticated moving-unit handoff for XHuman 12's first ground launch. */
class XHuman12MovingLandLaunchRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("xhuman 12 queues Patrol behind a moving cycle-49 land launch")
    void xhuman12QueuesPatrolBehindMovingCycle49LandLaunch() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        Unit ogre = unitAt(world, "unit-ogre", 6, 91);
        assertNotNull(ogre, "XHuman 12 has no native-slot-1356 ogre");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 72) {
            mission.tick();
            int fixture = fixtureCycle(world);
            if (fixture == 49) {
                assertEquals(2, ogre.battleNetAiBehavior(),
                        "the recurring ground launch recruits the moving ogre");
                assertEquals(Unit.Order.MOVE, ogre.order(),
                        "committed pixels keep the old Move current");
                assertEquals(0, ogre.pathLength(),
                        "native parks route index 20 on the launch visit");
                assertTrue(ogre.hasBattleNetPendingPatrol(),
                        "Patrol must survive as the next order");
                assertEquals(ogre.battleNetAiHomeX(),
                        ogre.battleNetPendingPatrolX());
                assertEquals(ogre.battleNetAiHomeY(),
                        ogre.battleNetPendingPatrolY());
            }
            if (fixture == 57) {
                assertEquals(10, ogre.tileX(),
                        "the launch must stop the stale route from gliding east");
                assertEquals(90, ogre.tileY());
                assertEquals(Unit.Order.PATROL, ogre.order(),
                        "Patrol promotes on the committed stride's settle visit");
                assertEquals(10, ogre.patrolX());
                assertEquals(90, ogre.patrolY());
                assertEquals(581, ogre.battleNetSequenceOffset(),
                        "the Patrol handoff constructs native Still");
                assertEquals(3, ogre.battleNetAnimationTimer());
            }
            if (fixture == 60) {
                assertEquals(10, ogre.tileX());
                assertEquals(89, ogre.tileY(),
                        "the constructed assault Patrol first-steps north");
            }
            if (fixture >= 60 && fixture <= 71) {
                assertEquals(Unit.Order.PATROL, ogre.order(),
                        "the direct Attack stays queued while Patrol pixels drain");
            }
        }
        assertEquals(Unit.Order.ATTACK, ogre.order(),
                "the queued direct Attack promotes on the stride's settle visit");
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
