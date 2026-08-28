package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.ai.AiPlayer;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
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
        Unit guardTower = unitAt(world, "unit-human-guard-tower", 15, 67);
        assertNotNull(guardTower,
                "XHuman 12 has no native-slot-1429 hostile objective");

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
                assertEquals(13, ogre.battleNetAiHomeX());
                assertEquals(66, ogre.battleNetAiHomeY(),
                        "the hostile tower point is normalized before launch");
                assertTrue(ogre.hasBattleNetPendingPatrol(),
                        "Patrol must survive as the next order");
                assertEquals(ogre.battleNetAiHomeX(),
                        ogre.battleNetPendingPatrolX());
                assertEquals(ogre.battleNetAiHomeY(),
                        ogre.battleNetPendingPatrolY());
                AiPlayer ai = world.enableAi(ogre.player());
                AiPlayer.DecisionLaunch launch = ai.battleNetDecisionLaunches()
                        .stream()
                        .filter(candidate -> "ground".equals(candidate.domain()))
                        .findFirst().orElseThrow();
                assertEquals(13, launch.targetX(),
                        "native records the normalized ground-force point");
                assertEquals(66, launch.targetY(),
                        "the launch ledger exposes the coordinate passed to behavior two");
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

        while (fixtureCycle(world) < 215) {
            mission.tick();
            int fixture = fixtureCycle(world);
            if (fixture == 199) {
                assertEquals(Unit.Order.STILL, ogre.order());
                assertTrue(ogre.hasBattleNetPendingPatrol());
                assertEquals(4985, ogre.battleNetSequenceOffset());
                assertEquals(2, ogre.battleNetAnimationTimer());
            }
            if (fixture == 200) {
                assertEquals(Unit.Order.STILL, ogre.order());
                assertTrue(ogre.hasBattleNetPendingPatrol());
                assertEquals(4985, ogre.battleNetSequenceOffset());
                assertEquals(1, ogre.battleNetAnimationTimer());
            }
            if (fixture >= 201 && fixture <= 203) {
                assertEquals(Unit.Order.PATROL, ogre.order());
                assertEquals(581, ogre.battleNetSequenceOffset());
                assertEquals(204 - fixture,
                        ogre.battleNetAnimationTimer());
                assertEquals(0, ogre.pathLength(),
                        "the Patrol constructor must not plan before timer one");
            }
            if (fixture == 204) {
                assertEquals(Unit.Order.PATROL, ogre.order());
                assertEquals(10, ogre.tileX());
                assertEquals(88, ogre.tileY(),
                        "the normalized assault goal routes west, not southwest");
                assertEquals(3, ogre.pathLength(),
                        "committing west retains NW,NE,E in the route buffer");
                assertEquals(Direction.fromDelta(-1, -1),
                        ogre.peekHeading());
                assertEquals(Direction.fromDelta(1, -1),
                        ogre.peekHeadingAtDepth(1));
                assertEquals(Direction.fromDelta(1, 0),
                        ogre.peekHeadingAtDepth(2));
            }
        }
        assertEquals(Unit.Order.PATROL, ogre.order());
        assertEquals(10, ogre.tileX());
        assertEquals(88, ogre.tileY());
        assertEquals(2, ogre.offsetX(),
                "fixture 215 retains the final two westbound pixels");

        mission.tick();
        assertEquals(Unit.Order.PATROL, ogre.order());
        assertEquals(9, ogre.tileX(),
                "fixture 216 settles west and immediately commits the northwest refill");
        assertEquals(87, ogre.tileY());
        assertEquals(32, ogre.offsetX());
        assertEquals(32, ogre.offsetY());
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
