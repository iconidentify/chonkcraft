package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.missile.Missile;
import net.chonkbase.chonkcraft.engine.pathfinder.BattleNetPathFinder;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * XHuman 07 submarine patrol return lands on open water at fixture 48.
 *
 * <p>Coast-goal Still used to leave the sub at 20,52 under STILL while native
 * double-stepped to 18,52 under PATROL. The retail map is the efficacy check:
 * synthetic maps rarely answer empty-FOUND the way the coast rewrite does.
 */
class NavalPatrolCoastGoalRealDataTest {

    private static GameData load() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set -Dchonkcraft.pack=... or wc2.install.dir");
        return new GameData(assets);
    }

    @Test
    @DisplayName("xhuman 07 submarine patrols open water at fixture 48")
    void xHuman07SubmarinePatrolsOpenWaterAtFixture48() {
        Mission mission = load().loadMission("campaigns/human-exp/levelx07h");
        Assumptions.assumeTrue(mission != null, "levelx07h did not load");
        // Fixture cycle = world − 2; fixture 48 is world 50.
        for (int i = 0; i < 50; i++) {
            mission.tick();
        }
        World world = mission.world();
        Unit sub = null;
        for (Unit u : world.units) {
            if (u != null && u.type() != null
                    && "unit-orc-submarine".equals(u.type().ident())
                    && u.player() == 6
                    && u.tileX() >= 16 && u.tileX() <= 22
                    && u.tileY() >= 50 && u.tileY() <= 56) {
                sub = u;
                break;
            }
        }
        assertTrue(sub != null, "western orc submarine not found");
        assertEquals(Unit.Order.PATROL, sub.order(),
                "coast empty-FOUND must keep Patrol, not Still "
                        + "(native 1511 fixture 48)");
        assertEquals(18, sub.tileX(),
                "native double-steps west to open water 18,52 at fixture 48");
        assertEquals(52, sub.tileY(),
                "native double-steps west to open water 18,52 at fixture 48");
    }

    @Test
    @DisplayName("xhuman 07 moving submarine answers the attacked naval guard")
    void xHuman07MovingSubmarineAnswersAttackedNavalGuard() {
        GameData data = load();
        String map = "campaigns/human-exp/levelx07h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "levelx07h did not load");
        Unit sub = null;
        for (Unit u : mission.world().units()) {
            if (u != null && u.type() != null
                    && "unit-orc-submarine".equals(u.type().ident())
                    && u.player() == 6
                    && u.tileX() >= 16 && u.tileX() <= 22
                    && u.tileY() >= 50 && u.tileY() <= 56) {
                sub = u;
                break;
            }
        }
        assertTrue(sub != null, "western orc submarine not found");
        Unit guard = mission.world().units().stream()
                .filter(u -> u.id() == 180)
                .findFirst().orElseThrow();

        // The naval ready callback is native behaviour six. Its opening home
        // is the service base at 22,27; the later coast rewrite changes only
        // the live order point, not that AI state.
        // The sealed fixture is pinned to initialization seed one. Its first
        // type-17 torpedo is constructed on fixture 45 from native IX/IY,
        // without the target's retained Java residual bank. Direct Mission
        // ticks retain the two-cycle initialization prefix.
        while (mission.world().cycle() < 47) {
            mission.tick();
        }
        Missile torpedo = mission.world().missiles().stream()
                .filter(missile -> missile.source() != null
                        && missile.source().id() == 178)
                .findFirst().orElseThrow();
        assertEquals(2765, (int) torpedo.toX(),
                "fixture 45 retains native's target-X jitter");
        assertEquals(3856, (int) torpedo.toY(),
                "fixture 45 retains native's target-Y jitter");
        assertEquals(128, torpedo.battleNetRemaining());

        while (mission.world().cycle() < 57) {
            mission.tick();
        }
        assertEquals(68, guard.hitPoints(),
                "the fixture-55 torpedo applies native's 32 damage");
        int guardHpAfterFirstHit = guard.hitPoints();
        assertEquals(6, sub.battleNetAiBehavior());
        assertEquals(22, sub.battleNetAiHomeX());
        assertEquals(27, sub.battleNetAiHomeY());
        assertTrue(sub.hasBattleNetPendingPatrol(),
                "naval help must survive behind the committed residual pixels");
        assertEquals(86, sub.battleNetPendingPatrolX(),
                "behavior six rendezvouses with guarded destroyer 1420");
        assertEquals(120, sub.battleNetPendingPatrolY());

        // Fixture cycle = world - 2. The old west residual lands on fixture
        // 91 and owns the whole visit; only then may the replacement patrol
        // construct. Its 3,2,1 hold releases the first SE stride on 94.
        while (mission.world().cycle() < 93) {
            mission.tick();
        }
        assertEquals(Unit.Order.PATROL, sub.order());
        assertEquals(18, sub.tileX());
        assertEquals(52, sub.tileY());
        assertEquals(86, sub.orderTargetX());
        assertEquals(120, sub.orderTargetY());
        assertEquals(2, sub.battleNetOrderDelay(),
                "the replacement has entered the native 3,2,1 release");

        while (mission.world().cycle() < 96) {
            mission.tick();
        }
        assertEquals(20, sub.tileX(),
                "native recurring patrol first-steps SE on fixture 94");
        assertEquals(54, sub.tileY());

        // The same hidden attacker lands another blow on the same guard at
        // fixture 155. Native sees that the roaming submarine is already
        // answering this guard and does not replace its live route a second
        // time. Java used to clear the remaining eighteen bytes here, then
        // replan only after the current residual and a two-visit constructor.
        while (mission.world().cycle() < 157) {
            mission.tick();
        }
        assertEquals(46, guard.hitPoints(),
                "the fixture-155 torpedo applies native's later 22 damage");
        assertTrue(guard.hitPoints() < guardHpAfterFirstHit);
        assertFalse(sub.hasBattleNetPendingPatrol(),
                "an identical guard rendezvous is not queued twice");
        assertEquals(18, sub.pathLength(),
                "the second guard hit retains the native route tail");

        while (mission.world().cycle() < 182) {
            mission.tick();
        }
        assertEquals(24, sub.tileX(),
                "the retained southeast byte lands on fixture 180");
        assertEquals(58, sub.tileY());
        assertEquals(17, sub.pathLength(),
                "fixture 180 consumes exactly one retained route byte");

        // The same native route begins SE,SE,SE,SE,S. Once the fourth
        // diagonal lands on fixture 223, free SE is two Chebyshev tiles
        // closer to the patrol point than the cached S. Retail nevertheless
        // trusts the complete twenty-byte buffer and commits S on 266. The
        // short wall-follow residual in XOrc 11 remains the held-out
        // free-closer form.
        while (mission.world().cycle() < 225) {
            mission.tick();
        }
        assertEquals(26, sub.tileX());
        assertEquals(60, sub.tileY());
        assertEquals(BattleNetPathFinder.MAX_PATH,
                sub.battleNetPathInitialLength());
        assertEquals(16, sub.pathLength());
        assertEquals(4, sub.battleNetPathStepsTaken());
        assertEquals(Direction.fromDelta(0, 1), sub.peekHeading(),
                "the full native route exposes south after four diagonals");

        while (mission.world().cycle() < 268) {
            mission.tick();
        }
        assertEquals(26, sub.tileX(),
                "a full-buffer patrol must not free-closer southeast");
        assertEquals(62, sub.tileY(),
                "native spends the cached south heading on fixture 266");
        assertEquals(Direction.fromDelta(0, 1), sub.lastStepHeading());
        assertEquals(15, sub.pathLength());

        // The defended destroyer eventually enters its death action. Unit 89
        // runs before unit 180, so the killing visit cannot retroactively
        // interrupt the submarine: the live rendezvous remains until its next
        // residual-settle action boundary.
        while (!guard.isDying()) {
            mission.tick();
        }
        assertTrue(guard.isDying(),
                "the guarded destroyer has entered its native death action");
        assertEquals(86, sub.orderTargetX(),
                "a dying guard remains the rendezvous through the Move body");
        assertEquals(120, sub.orderTargetY());
        int committedX = sub.tileX();
        int committedY = sub.tileY();

        // On that boundary native releases the dead rendezvous, restores
        // behavior six's authored origin as the reverse endpoint, and
        // coast-rewrites the service-base home. The bounded loop follows the
        // event instead of baking in the two-visit phase difference between
        // direct Mission ticks and the sealed headless fixture runner.
        long releaseDeadline = mission.world().cycle() + 64;
        while (sub.orderTargetX() == 86
                && sub.orderTargetY() == 120
                && mission.world().cycle() < releaseDeadline) {
            mission.tick();
        }
        assertEquals(Unit.Order.PATROL, sub.order());
        assertEquals(committedX, sub.tileX(),
                "release settles the live stride before replacing its route");
        assertEquals(committedY, sub.tileY(),
                "release must not consume another cached south byte");
        assertEquals(24, sub.orderTargetX());
        assertEquals(42, sub.orderTargetY(),
                "the blocked service-base home is rewritten toward the hull");
        assertEquals(18, sub.patrolX());
        assertEquals(54, sub.patrolY(),
                "native restores the behavior-six patrol origin");
        assertEquals(2, sub.battleNetOrderDelay(),
                "the reconstructed patrol holds for the next two visits");

        mission.tick();
        mission.tick();
        assertEquals(committedX, sub.tileX(),
                "the reconstructed Still owns its two quiet visits");
        assertEquals(committedY, sub.tileY());
        mission.tick();
        assertEquals(committedX - 2, sub.tileX(),
                "the returning patrol first-steps northwest after the hold");
        assertEquals(committedY - 2, sub.tileY());
        assertEquals(Direction.fromDelta(-1, -1), sub.lastStepHeading());
    }

    @Test
    @DisplayName("a post-death repeat hit does not enlist a second naval helper")
    void postDeathRepeatHitDoesNotEnlistASecondNavalHelper() {
        GameData data = load();
        String map = "campaigns/human-exp/levelx07h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "levelx07h did not load");
        mission.tick();
        mission.tick();
        Unit guard = mission.world().units().stream()
                .filter(u -> u.id() == 180)
                .findFirst().orElseThrow();
        Unit primaryHelper = mission.world().units().stream()
                .filter(u -> u.type() != null
                        && "unit-orc-submarine".equals(u.type().ident())
                        && u.player() == 6
                        && u.tileX() >= 16 && u.tileX() <= 22
                        && u.tileY() >= 50 && u.tileY() <= 56)
                .findFirst().orElseThrow();

        while (mission.world().cycle() - 2 < 355) {
            mission.tick();
        }
        assertTrue(guard.isDying(),
                "the authenticated naval guard enters Die before the repeat hit");
        assertTrue(primaryHelper.battleNetNavalGuardTarget() == guard,
                "the first helper retains the live guard pointer into Die");
        for (Unit candidate : mission.world().units()) {
            if (candidate == primaryHelper) {
                continue;
            }
            assertFalse(candidate.battleNetPendingNavalGuardTarget() == guard
                            || candidate.battleNetNavalGuardTarget() == guard,
                    "the post-death repeat hit must not enlist a second helper");
        }
    }
}
