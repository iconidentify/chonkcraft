package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                assertEquals(guardTower.id(), launch.targetId(),
                        "selector zero chooses native guard-tower slot 1429");
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

    @Test
    @DisplayName("a constructed assault Patrol preserves its paid blocked route")
    void constructedAssaultPatrolPreservesPaidBlockedRoute() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit ogre = unitById(world, 244);
        assertNotNull(ogre, "XHuman 12 has no native-slot-1356 ogre");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 255) {
            mission.tick();
        }
        assertEquals(Unit.Order.PATROL, ogre.order());
        assertEquals(11, ogre.tileX());
        assertEquals(86, ogre.tileY());
        assertEquals(2, ogre.pathLength());
        assertEquals(Direction.fromDelta(-1, -1), ogre.peekHeading());
        assertEquals(Direction.fromDelta(1, -1),
                ogre.peekHeadingAtDepth(1));
        assertEquals(1, ogre.battleNetCollisionCounter());
        assertEquals(14, ogre.battleNetOrderDelay());
        assertEquals(586, ogre.battleNetSequenceOffset());
        assertEquals(15, ogre.battleNetAnimationTimer());

        while (fixtureCycle(world) < 269) {
            mission.tick();
        }
        assertEquals(Unit.Order.PATROL, ogre.order());
        assertEquals(11, ogre.tileX());
        assertEquals(86, ogre.tileY());
        assertEquals(2, ogre.pathLength());
        assertEquals(0, ogre.battleNetOrderDelay());
        assertEquals(586, ogre.battleNetSequenceOffset());
        assertEquals(1, ogre.battleNetAnimationTimer());

        mission.tick();
        assertEquals(270, fixtureCycle(world));
        assertEquals(10, ogre.tileX());
        assertEquals(85, ogre.tileY());
        assertEquals(Direction.fromDelta(-1, -1),
                ogre.lastStepHeading());
        assertEquals(1, ogre.pathLength());
    }

    @Test
    @DisplayName("a refused recurring regroup retires after its paid wake")
    void refusedRecurringRegroupRetiresAfterPaidWake() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit axethrower = unitById(world, 241);
        assertNotNull(axethrower,
                "XHuman 12 has no native-slot-1359 axethrower");
        Unit meleeVictim = unitById(world, 118);
        assertNotNull(meleeVictim,
                "XHuman 12 has no native-slot-1482 grunt");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 252) {
            mission.tick();
        }
        assertEquals(Unit.Order.MOVE, axethrower.order());
        assertEquals(12, axethrower.tileX());
        assertEquals(89, axethrower.tileY());
        assertEquals(5, axethrower.pathLength());
        assertEquals(Direction.fromDelta(0, -1), axethrower.peekHeading());
        assertEquals(1, axethrower.battleNetCollisionCounter());
        assertEquals(14, axethrower.battleNetOrderDelay());

        while (fixtureCycle(world) < 267) {
            mission.tick();
        }
        assertEquals(Unit.Order.MOVE, axethrower.order());
        assertEquals(0, axethrower.pathLength());
        assertTrue(axethrower.routeSpent(),
                "the paid regroup wake parks native route cursor twenty");
        assertEquals(2, axethrower.battleNetCollisionCounter(),
                "the timer-one wake parks the refused north face");
        assertEquals(0xb24f08db, world.battleNetRandomSeed(),
                "the paid regroup band has not spent its replacement Still draw");

        mission.tick();
        assertEquals(268, fixtureCycle(world));
        assertEquals(Unit.Order.STILL, axethrower.order(),
                "the parked recurring regroup has completed its native attempt");
        assertEquals(12, axethrower.tileX());
        assertEquals(89, axethrower.tileY());
        assertEquals(0, axethrower.pathLength());
        assertEquals(2, axethrower.battleNetCollisionCounter());
        assertEquals(825, axethrower.battleNetSequenceOffset());
        assertEquals(1, axethrower.battleNetAnimationTimer());
        assertEquals(0xa9b57d10, world.battleNetRandomSeed(),
                "the terminal Move callback executes replacement Still in-place");

        mission.tick();
        assertEquals(269, fixtureCycle(world));
        assertEquals(4983, axethrower.battleNetSequenceOffset());
        assertEquals(1, axethrower.battleNetAnimationTimer());
        assertEquals(0x06451374, world.battleNetRandomSeed(),
                "the following ordinary Still marker owns its separate draw");

        while (fixtureCycle(world) < 275) {
            mission.tick();
        }
        assertEquals(46, meleeVictim.hitPoints(),
                "the restored asynchronous ordinal feeds native's eight-point hit");
        assertEquals(0x907b5c2f, world.battleNetRandomSeed(),
                "the authenticated asynchronous stream stays aligned through damage");

        while (fixtureCycle(world) < 299) {
            mission.tick();
        }
        assertEquals(Unit.Order.MOVE, axethrower.order(),
                "the next recurring regroup is a fresh Move attempt");
        assertEquals(3, axethrower.battleNetAnimationTimer());
        assertEquals(2, axethrower.battleNetOrderDelay());
        assertFalse(axethrower.routeSpent(),
                "a new regroup must not inherit the retired cursor-twenty callback");

        while (fixtureCycle(world) < 302) {
            mission.tick();
        }
        assertEquals(Unit.Order.MOVE, axethrower.order());
        assertEquals(11, axethrower.tileX(),
                "the fresh regroup path commits native's north-west wall face");
        assertEquals(88, axethrower.tileY());
        assertEquals(Direction.fromDelta(-1, -1),
                axethrower.lastStepHeading());
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

    private static Unit unitById(World world, int id) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.id() == id) {
                return unit;
            }
        }
        return null;
    }
}
