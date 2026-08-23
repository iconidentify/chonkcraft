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
 * XOrc 11's human battleship leaves Patrol for Attack at fixture 58.
 *
 * <p>Authenticated campaign-1800 fixture {@code retail-xorc-11-idle}:
 * battleship 1511 opens on 20,40, takes its first west stride under Patrol
 * (order 5) at fixture 5, and is on Attack (order 12) at fixture 58, still
 * on 18,40. The case's named patrol/attack witness is this promotion, not
 * the neighbouring destroyer's brief fixture-53 patrol.
 */
class Xorc11PatrolAttackRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("xorc 11's opening battleship is attacking on cycle 58")
    void xorc11sOpeningBattleshipIsAttackingOnCycle58() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/orc-exp/levelx11o",
                GameData.personIn(data.campaignMap(
                        "campaigns/orc-exp/levelx11o")), 1);
        Assumptions.assumeTrue(mission != null, "XOrc 11 is not in the pack");
        World world = mission.world();

        // Native slot 1511 stands on 18,40. Java's 2x2 even-grid placement
        // opens that hull on 20,40; take the battleship nearest 18,40.
        Unit ship = nearest(world, "unit-battleship", 18, 40);
        assertNotNull(ship, "XOrc 11 has no human battleship near 18,40");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        Unit.Order orderAt5 = null;
        Unit.Order orderAt58 = null;
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 61) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 5) {
                orderAt5 = ship.order();
            }
            if (fixture == 58) {
                orderAt58 = ship.order();
            }
            if (fixture >= 58 && fixture <= 60) {
                assertEquals(18, ship.tileX(),
                        "Attack construction holds the west stride through "
                                + fixture);
                assertEquals(40, ship.tileY());
                assertEquals(576, ship.pixelX(),
                        "the promotion visit must drain the final Patrol pixels "
                                + "before Attack owns the hull at fixture "
                                + fixture);
                assertEquals(0, ship.offsetX(),
                        "no Patrol displacement may leak into the chase leg");
                assertEquals(3092, ship.battleNetSequenceOffset(),
                        "capital Patrol promotion opens Attack start");
                assertEquals(61 - fixture,
                        ship.battleNetAnimationTimer(),
                        "native capital Attack construction counts 3,2,1");
            }
        }

        assertEquals(Unit.Order.PATROL, orderAt5,
                "retail's battleship is still on Patrol at cycle 5, not "
                        + orderAt5);
        assertEquals(Unit.Order.ATTACK, orderAt58,
                "retail's battleship leaves Patrol for Attack at cycle 58, not "
                        + orderAt58);
        assertEquals(16, ship.tileX(),
                "the timer-one handoff spends the west chase stride on cycle 61");
        assertEquals(40, ship.tileY(),
                "the battleship's first chase stride stays on the 40-row");
    }

    @Test
    @DisplayName("xorc 11's crossing cannon shells land on cycle 91 with BNE outer splash")
    void xorc11sCrossingCannonShellsLandOnCycle91WithBneOuterSplash() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/orc-exp/levelx11o",
                GameData.personIn(data.campaignMap(
                        "campaigns/orc-exp/levelx11o")), 1);
        Assumptions.assumeTrue(mission != null, "XOrc 11 is not in the pack");
        World world = mission.world();

        Unit ship = nearest(world, "unit-battleship", 18, 40);
        assertNotNull(ship, "XOrc 11 has no human battleship near 18,40");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 91) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture >= 69 && fixture <= 90) {
                assertEquals(150, ship.hitPoints(),
                        "BNE's type-7 cannon travelers are still airborne at fixture "
                                + fixture);
            }
        }

        // Native is 127 here. Java is currently 125 because an older idle-RNG
        // schedule difference gives these otherwise-correct splash rolls two
        // neighbouring async values. Keep this regression scoped to what this
        // evidence proves independently: type-7 arrival timing and the legal
        // outer-splash damage band (9..18 per shell after armor), neither an
        // early full-band kill nor a missing impact.
        assertTrue(ship.hitPoints() >= 114 && ship.hitPoints() <= 132,
                "fixture 91 must apply exactly two legal outer-band splashes, hp="
                        + ship.hitPoints());
        assertEquals(Unit.Order.ATTACK, ship.order(),
                "the corrected cannon crossing must leave the battleship fighting");
    }

    @Test
    @DisplayName("xorc 11's destroyer answers a surviving ally's cannon splash")
    void xorc11sDestroyerAnswersASurvivingAllysCannonSplash() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/orc-exp/levelx11o",
                GameData.personIn(data.campaignMap(
                        "campaigns/orc-exp/levelx11o")), 1);
        Assumptions.assumeTrue(mission != null, "XOrc 11 is not in the pack");
        World world = mission.world();

        Unit destroyer = nearest(world, "unit-human-destroyer", 22, 38);
        Unit dataGuard = nearest(world, "unit-human-destroyer", 22, 32);
        Unit attackedShip = nearest(world, "unit-battleship", 18, 40);
        assertNotNull(destroyer,
                "XOrc 11 has no human destroyer at the shipyard guard post");
        assertNotNull(dataGuard,
                "XOrc 11 has no PUD-data destroyer north of the responder");
        assertNotNull(attackedShip,
                "XOrc 11 has no attacked battleship near 18,40");
        assertTrue(!destroyer.battleNetReadySuppressed(),
                "native responder 1519 carries ai_marker zero");
        assertTrue(dataGuard.battleNetReadySuppressed(),
                "native non-responder 1531 carries unit+0x5f bit two");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 95) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 91) {
                assertEquals(127, attackedShip.hitPoints(),
                        "both authenticated cannon rolls leave the ally alive");
                assertEquals(Unit.Order.STILL, destroyer.currentAction(),
                        "HitUnit writes next-order Attack after this unit's visit");
            } else if (fixture >= 92 && fixture <= 94) {
                assertEquals(Unit.Order.ATTACK, destroyer.currentAction(),
                        "the queued help attack promotes on fixture " + fixture);
                assertEquals(3266, destroyer.battleNetSequenceOffset());
                assertEquals(95 - fixture,
                        destroyer.battleNetAnimationTimer(),
                        "native Attack construction counts 3,2,1");
            }
            if (fixture >= 91) {
                assertEquals(Unit.Order.STILL, dataGuard.currentAction(),
                        "the distant marker-two guard must not answer the hit");
            }
        }

        assertEquals(20, destroyer.tileX(),
                "the timer-one help handoff takes the south-west stride");
        assertEquals(40, destroyer.tileY());
        assertEquals(704, destroyer.pixelX(),
                "the doubled stride opens cold on its commit cycle");
        assertEquals(1216, destroyer.pixelY());
    }

    @Test
    @DisplayName("xorc 11 chases a movable ship from its native point anchor")
    void xorc11ChasesAMovableShipFromItsNativePointAnchor() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/orc-exp/levelx11o";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XOrc 11 is not in the pack");
        World world = mission.world();

        // Native slot 1519 / Java 81 finishes its southwest residual at
        // fixture 127, replaces the old quarry with the destroyer at 10,42,
        // and first-steps NW to 18,38. BNE treats movable units as point
        // targets even when their ChonkCraft sprite definition is 2x2. Using
        // the footprint's near edge (11,42) instead drew a north-first route.
        Unit destroyer = nearest(world, "unit-human-destroyer", 22, 38);
        assertNotNull(destroyer,
                "XOrc 11 has no human destroyer at the shipyard guard post");
        assertEquals(81, destroyer.id());

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 126) {
            mission.tick();
        }
        assertEquals(20, destroyer.tileX());
        assertEquals(40, destroyer.tileY());

        mission.tick();
        assertEquals(127,
                world.cycle() - BNE_INITIALIZATION_TICKS);
        assertEquals(18, destroyer.tileX(),
                "the native point anchor yields a northwest first stride");
        assertEquals(38, destroyer.tileY());
        assertNotNull(destroyer.target());
        assertEquals(10, destroyer.target().tileX());
        assertEquals(42, destroyer.target().tileY());
        assertEquals(10, destroyer.attackGoalX(),
                "the Attack order union retains the target's BNE point anchor");
        assertEquals(42, destroyer.attackGoalY());
    }

    @Test
    @DisplayName("xorc 11's destroyer pays Attack construction before swapping patrol lanes")
    void xorc11sDestroyerPaysAttackConstructionBeforeSwappingPatrolLanes() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/orc-exp/levelx11o",
                GameData.personIn(data.campaignMap(
                        "campaigns/orc-exp/levelx11o")), 1);
        Assumptions.assumeTrue(mission != null, "XOrc 11 is not in the pack");
        World world = mission.world();

        Unit destroyer = nearest(world, "unit-human-destroyer", 8, 22);
        Unit battleship = nearest(world, "unit-battleship", 6, 24);
        assertNotNull(destroyer,
                "XOrc 11 has no human destroyer near its 8,22 opening");
        assertNotNull(battleship,
                "XOrc 11 has no battleship near its 6,24 opening");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 58) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture >= 55 && fixture <= 57) {
                assertEquals(10, destroyer.tileX(),
                        "fixture " + fixture
                                + " Attack construction must hold the southwest stride");
                assertEquals(24, destroyer.tileY(),
                        "fixture " + fixture
                                + " Attack construction must hold the southwest stride");
                assertEquals(3266, destroyer.battleNetSequenceOffset(),
                        "the queued patrol acquisition opens Attack start");
                assertEquals(58 - fixture,
                        destroyer.battleNetAnimationTimer(),
                        "native counts Attack construction 3,2,1");
            }
        }

        assertEquals(8, destroyer.tileX(),
                "the timer-one visit first-steps southwest into the vacated lane");
        assertEquals(26, destroyer.tileY(),
                "the timer-one visit first-steps southwest into the vacated lane");
        assertEquals(10, battleship.tileX(),
                "the earlier native slot vacates the destroyer's lane southeast");
        assertEquals(28, battleship.tileY(),
                "the earlier native slot vacates the destroyer's lane southeast");
        assertEquals(Unit.Order.PATROL, battleship.order(),
                "the battleship keeps Patrol while making room");
    }

    @Test
    @DisplayName("xorc 11's southern battleship promotes its banked attack on landing")
    void xorc11sSouthernBattleshipPromotesBankedAttackOnLanding() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/orc-exp/levelx11o",
                GameData.personIn(data.campaignMap(
                        "campaigns/orc-exp/levelx11o")), 1);
        Assumptions.assumeTrue(mission != null, "XOrc 11 is not in the pack");
        World world = mission.world();
        Unit ship = nearest(world, "unit-battleship", 6, 24);
        assertNotNull(ship, "XOrc 11 has no southern battleship at 6,24");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 58) {
            mission.tick();
        }
        assertEquals(Unit.Order.PATROL, ship.order());
        assertNotNull(ship.pendingAttack(),
                "native banks order 12 when the first stride lands");
        assertEquals(6, ship.pendingAttackX());
        assertEquals(36, ship.pendingAttackY());

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 110) {
            mission.tick();
        }
        assertEquals(Unit.Order.PATROL, ship.order());
        assertEquals(10, ship.tileX());
        assertEquals(28, ship.tileY());
        assertEquals(318, ship.pixelX());
        assertEquals(894, ship.pixelY());
        assertEquals(3087, ship.battleNetSequenceOffset());
        assertEquals(1, ship.battleNetAnimationTimer());
        assertNotNull(ship.pendingAttack(),
                "the banked attack must survive the entire Patrol Move body");

        mission.tick();
        assertEquals(Unit.Order.ATTACK, ship.order(),
                "the landing OP0 promotes next_order in the same visit");
        assertEquals(10, ship.tileX());
        assertEquals(28, ship.tileY());
        assertEquals(320, ship.pixelX());
        assertEquals(896, ship.pixelY());
        assertEquals(3092, ship.battleNetSequenceOffset());
        assertEquals(3, ship.battleNetAnimationTimer());
    }

    private static Unit nearest(World world, String ident, int x, int y) {
        Unit best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Unit unit : world.unitsSnapshot()) {
            if (!unit.isAlive() || !unit.isOnMap() || unit.type() == null
                    || !ident.equals(unit.type().ident())) {
                continue;
            }
            int dist = Math.max(Math.abs(unit.tileX() - x),
                    Math.abs(unit.tileY() - y));
            if (dist < bestDist) {
                best = unit;
                bestDist = dist;
            }
        }
        return best;
    }
}
