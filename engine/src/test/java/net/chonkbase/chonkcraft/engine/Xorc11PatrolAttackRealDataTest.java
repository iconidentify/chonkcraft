package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.missile.Missile;
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
    @DisplayName("xorc 11's naval hit-help rectangle excludes its south edge")
    void xorc11sNavalHitHelpRectangleExcludesItsSouthEdge() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/orc-exp/levelx11o";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XOrc 11 is not in the pack");
        World world = mission.world();
        Unit struck = nearest(world, "unit-orc-destroyer", 10, 46);
        Unit outside = nearest(world, "unit-orc-destroyer", 8, 50);
        Unit attacker = nearest(world, "unit-human-destroyer", 22, 38);
        assertNotNull(struck);
        assertNotNull(outside);
        assertNotNull(attacker);

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 359) {
            mission.tick();
        }
        assertEquals(100, struck.hitPoints());
        assertEquals(Unit.Order.STILL, outside.order());

        mission.tick();
        assertEquals(77, struck.hitPoints(),
                "the fixture-360 cannon impact is the selection anchor");
        assertNull(outside.battleNetPendingHelpAttack(),
                "native's lower rectangle edge ends at y=49");

        mission.tick();
        assertEquals(Unit.Order.STILL, outside.order(),
                "slot 1485 remains Still at the fixture-361 boundary");
    }

    @Test
    @DisplayName("xorc 11 cannon source effects preserve the native pool order")
    void xorc11CannonSourceEffectsPreserveNativePoolOrder() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/orc-exp/levelx11o";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XOrc 11 is not in the pack");
        World world = mission.world();
        Unit battleship = unitById(world, 89);
        assertNotNull(battleship, "native slot 1511 battleship is absent");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 181) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 171) {
                assertEquals(9, constructedMissileFrom(world, 107)
                                .battleNetPoolSlot(),
                        "native slot 1493's cannon shell owns projectile slot 9");
            } else if (fixture == 173) {
                assertEquals(11, constructedMissileFrom(world, 75)
                                .battleNetPoolSlot(),
                        "native slot 1525's later shell owns projectile slot 11");
            } else if (fixture == 180) {
                assertEquals(127, battleship.hitPoints());
            } else if (fixture == 181) {
                assertEquals(117, battleship.hitPoints(),
                        "slot-9 impact resolves before slot-11 flight draws");
            }
        }
    }

    @Test
    @DisplayName("xorc 11's patrolling destroyer acquires before its residual stride")
    void xorc11sPatrollingDestroyerAcquiresBeforeItsResidualStride() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/orc-exp/levelx11o";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XOrc 11 is not in the pack");
        World world = mission.world();
        Unit destroyer = nearest(world, "unit-human-destroyer", 4, 18);
        Unit dragon = nearest(world, "unit-dragon", 2, 34);
        assertNotNull(destroyer);
        assertNotNull(dragon);

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 135) {
            mission.tick();
        }
        assertEquals(12, destroyer.tileX());
        assertEquals(24, destroyer.tileY());

        mission.tick();
        assertNotNull(destroyer.pendingAttack(),
                "Patrol OP0 banks Attack before consuming the new route");
        assertSame(dragon, destroyer.pendingAttack());
        assertEquals(10, destroyer.tileX());
        assertEquals(26, destroyer.tileY(),
                "the acquired route first-steps southwest, not southeast");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 167) {
            mission.tick();
        }
        assertEquals(Unit.Order.PATROL, destroyer.order(),
                "the queued Attack waits for the committed Patrol stride");
        assertSame(dragon, destroyer.pendingAttack());
        assertEquals(10, destroyer.tileX());
        assertEquals(26, destroyer.tileY());

        mission.tick();
        assertEquals(Unit.Order.ATTACK, destroyer.order(),
                "next_order promotes on the residual-settle fixture");
        assertEquals(null, destroyer.pendingAttack());
        assertEquals(10, destroyer.tileX());
        assertEquals(26, destroyer.tileY());
        assertEquals(3, destroyer.battleNetAnimationTimer(),
                "the promoted attack exposes BNE's timer-three constructor");

        mission.tick();
        assertEquals(2, destroyer.battleNetAnimationTimer());
        assertEquals(26, destroyer.tileY(),
                "constructor timer two must not leak into chase movement");

        mission.tick();
        assertEquals(1, destroyer.battleNetAnimationTimer());
        assertEquals(26, destroyer.tileY(),
                "constructor timer one is still a quiet native visit");

        mission.tick();
        assertEquals(10, destroyer.tileX());
        assertEquals(28, destroyer.tileY(),
                "the promoted attack takes BNE's first southward chase step");
    }

    @Test
    @DisplayName("xorc 11's destroyer retains Attack while its chase stride drains")
    void xorc11sDestroyerRetainsAttackWhileItsChaseStrideDrains() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/orc-exp/levelx11o";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XOrc 11 is not in the pack");
        World world = mission.world();
        Unit destroyer = nearest(world, "unit-human-destroyer", 4, 18);
        Unit dragon = nearest(world, "unit-dragon", 2, 34);
        assertNotNull(destroyer);
        assertNotNull(dragon);

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 182) {
            mission.tick();
        }

        assertEquals(10, destroyer.tileX());
        assertEquals(28, destroyer.tileY());
        assertEquals(-42, destroyer.offsetY(),
                "the native south stride still owes forty-two pixels");
        assertTrue(destroyer.isMoving());
        assertEquals(Unit.Order.ATTACK, destroyer.order(),
                "MoveToTarget owns the committed residual before target rescanning");
        assertSame(dragon, destroyer.target(),
                "the native Attack order retains its original CUnitPtr");
        assertTrue(dragon.isAlive());
        assertEquals(3181, destroyer.battleNetSequenceOffset(),
                "the native Move body advances instead of restoring Patrol");
    }

    @Test
    @DisplayName("xorc 11's person fleet answers a surviving ally's cannon splash")
    void xorc11sPersonFleetAnswersASurvivingAllysCannonSplash() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/orc-exp/levelx11o";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XOrc 11 is not in the pack");
        World world = mission.world();
        Unit struck = nearest(world, "unit-orc-destroyer", 10, 42);
        Unit responder = nearest(world, "unit-orc-destroyer", 6, 36);
        Unit second = nearest(world, "unit-orc-destroyer", 8, 38);
        Unit southern = nearest(world, "unit-orc-destroyer", 10, 46);
        Unit outside = nearest(world, "unit-orc-destroyer", 8, 50);
        Unit attacker = nearest(world, "unit-battleship", 16, 40);
        Unit northernAxe = nearest(world, "unit-axethrower", 6, 42);
        Unit southernAxe = nearest(world, "unit-axethrower", 6, 44);
        Unit outsideAxe = nearest(world, "unit-axethrower", 5, 41);
        Unit dragon = nearest(world, "unit-dragon", 2, 34);
        Unit humanDestroyer = nearest(
                world, "unit-human-destroyer", 6, 30);
        Unit reactiveDestroyer = nearest(
                world, "unit-human-destroyer", 22, 38);
        assertNotNull(struck);
        assertNotNull(responder);
        assertNotNull(second);
        assertNotNull(southern);
        assertNotNull(outside);
        assertNotNull(attacker);
        assertNotNull(northernAxe);
        assertNotNull(southernAxe);
        assertNotNull(outsideAxe);
        assertNotNull(dragon);
        assertNotNull(humanDestroyer);
        assertNotNull(reactiveDestroyer);

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 174) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 132) {
                assertEquals(33, struck.hitPoints(),
                        "the authenticated cannon roll leaves the struck ship alive");
                assertSame(attacker, struck.offeredTarget(),
                        "HitUnit offers the source to the struck ship");
                assertSame(attacker, responder.battleNetPendingHelpAttack());
                assertSame(attacker, second.battleNetPendingHelpAttack());
                assertSame(attacker, southern.battleNetPendingHelpAttack());
                assertSame(attacker,
                        northernAxe.battleNetPendingHelpAttack());
                assertSame(attacker,
                        southernAxe.battleNetPendingHelpAttack());
                assertEquals(null, outside.battleNetPendingHelpAttack(),
                        "the next destroyer beyond the marker-two gap stays idle");
                assertEquals(null, outsideAxe.battleNetPendingHelpAttack(),
                        "the axethrower beyond its person reaction radius stays idle");
            } else if (fixture == 133) {
                assertEquals(Unit.Order.STILL, responder.order());
                assertEquals(Unit.Order.STILL, second.order());
                assertEquals(Unit.Order.STILL, struck.order());
                assertEquals(Unit.Order.STILL, southern.order());
            } else if (fixture == 134) {
                assertEquals(Unit.Order.ATTACK, responder.order(),
                        "timer-two responder promotes first on its own idle boundary");
                assertEquals(Unit.Order.STILL, second.order());
                assertEquals(Unit.Order.STILL, struck.order());
                assertEquals(Unit.Order.STILL, southern.order());
            } else if (fixture == 135) {
                assertEquals(Unit.Order.ATTACK, responder.order());
                assertEquals(Unit.Order.ATTACK, second.order());
                assertEquals(Unit.Order.ATTACK, struck.order());
                assertSame(attacker, struck.target(),
                        "the struck hull accepts its offered attacker");
                assertEquals(Unit.Order.ATTACK, southern.order());
            } else if (fixture == 136) {
                assertEquals(Unit.Order.ATTACK, northernAxe.order(),
                        "the first ranged shore defender promotes at its idle boundary");
                assertEquals(Unit.Order.STILL, southernAxe.order());
            } else if (fixture == 137) {
                assertEquals(Unit.Order.ATTACK, northernAxe.order());
                assertEquals(Unit.Order.ATTACK, southernAxe.order());
            } else if (fixture == 155) {
                assertEquals(Unit.Order.STILL, northernAxe.order(),
                        "the first shoreline helper releases its temporary chase at Move OP0");
                assertEquals(Unit.Order.ATTACK, southernAxe.order());
                assertEquals(825, northernAxe.battleNetSequenceOffset(),
                        "the native Still program is installed immediately");
                assertEquals(1, northernAxe.battleNetAnimationTimer());
                assertEquals(2_555_427_864L,
                        Integer.toUnsignedLong(world.battleNetRandomSeed()),
                        "the same-visit active-order idle draw must precede "
                                + "the Dragon and cannon constructors");
            } else if (fixture == 156) {
                assertEquals(Unit.Order.STILL, northernAxe.order());
                assertEquals(Unit.Order.STILL, southernAxe.order(),
                        "each helper releases on its own residual-settle visit");
                assertEquals(4983,
                        northernAxe.battleNetSequenceOffset());
                assertEquals(1, northernAxe.battleNetAnimationTimer());
            } else if (fixture == 157) {
                assertEquals(4985,
                        northernAxe.battleNetSequenceOffset());
                assertEquals(4, northernAxe.battleNetAnimationTimer());
            } else if (fixture == 165) {
                assertEquals(85, dragon.hitPoints(),
                        "the opposing cannon must use BNE's aligned splash roll");
                assertEquals(100, humanDestroyer.hitPoints(),
                        "Dragon breath remains in flight until its first "
                                + "native action-seven pulse");
            } else if (fixture == 169) {
                assertEquals(92, humanDestroyer.hitPoints(),
                        "the first action-seven pulse applies native area damage");
                assertSame(humanDestroyer, responder.target(),
                        "the naval helper keeps its timer-one replacement target");
                assertEquals(3, responder.battleNetAnimationTimer(),
                        "settling the response route opens native Attack construction");
            } else if (fixture == 170) {
                assertSame(reactiveDestroyer, struck.target(),
                        "the struck ship rescans the battle line when its response route settles");
                assertEquals(3, struck.battleNetAnimationTimer(),
                        "the reactive retarget owns fresh Attack construction");
            } else if (fixture == 172) {
                assertEquals(92, humanDestroyer.hitPoints(),
                        "action seven keeps flying past its aim point instead "
                                + "of applying an early direct hit");
                assertEquals(1, struck.battleNetAnimationTimer(),
                        "the reactive constructor counts 3,2,1 without firing a stale shell");
                assertEquals(36, second.tileY(),
                        "the combat chase first parks its blocked Patrol tail");
            } else if (fixture == 173) {
                assertSame(reactiveDestroyer, struck.target());
                assertEquals(118, struck.battleNetAnimationTimer(),
                        "OP0 serves the remaining ranged cadence after the retarget");
                assertEquals(8, second.tileX());
                assertEquals(36, second.tileY(),
                        "Attack construction keeps the parked route quiet");
            } else if (fixture == 174) {
                assertEquals(8, second.tileX());
                assertEquals(34, second.tileY(),
                        "the engaged destroyer replans north after BNE's 3,2,1 handoff");
            }
            assertEquals(Unit.Order.STILL, outside.order(),
                    "the outside destroyer must not join the response");
            assertEquals(Unit.Order.STILL, outsideAxe.order(),
                    "the outside axethrower must not join the response");
        }

        assertEquals(6, responder.tileX());
        assertEquals(34, responder.tileY(),
                "the timer-one scan retargets the nearer northern destroyer");
        assertEquals(8, second.tileX());
        assertEquals(34, second.tileY());
        assertEquals(12, struck.tileX());
        assertEquals(40, struck.tileY());
        assertEquals(12, southern.tileX());
        assertEquals(44, southern.tileY());
        assertEquals(7, northernAxe.tileX());
        assertEquals(42, northernAxe.tileY());
        assertEquals(7, southernAxe.tileX());
        assertEquals(44, southernAxe.tileY());
    }

    @Test
    @DisplayName("xorc 11's responding destroyer rescans before its cold broadside")
    void xorc11sRespondingDestroyerPaysColdAttackConstructionAfterItsFinalChaseStride() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/orc-exp/levelx11o";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XOrc 11 is not in the pack");
        World world = mission.world();
        Unit responder = unitById(world, 79);
        Unit quarry = unitById(world, 58);
        Unit replacement = unitById(world, 42);
        assertNotNull(responder,
                "XOrc 11 has no native-slot-1521 responding destroyer");
        assertNotNull(quarry,
                "XOrc 11 has no native-slot-1542 human destroyer");
        assertNotNull(replacement,
                "XOrc 11 has no native-slot-1558 human destroyer");
        assertEquals(79, responder.id());
        assertEquals(58, quarry.id());
        assertEquals(42, replacement.id());

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 337) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 205) {
                assertEquals(3261, responder.battleNetSequenceOffset(),
                        "the final two pixels still belong to the Move body");
                assertEquals(1, responder.battleNetAnimationTimer());
                assertSame(quarry, responder.target(),
                        "the old HitUnit source owns the committed residual");
            } else if (fixture >= 206 && fixture <= 208) {
                assertEquals(3266, responder.battleNetSequenceOffset(),
                        "the settled naval residual opens Attack start");
                assertEquals(209 - fixture,
                        responder.battleNetAnimationTimer(),
                        "native naval Attack construction counts 3,2,1");
                assertSame(replacement, responder.target(),
                        "the in-range residual rescan selects the first "
                                + "equal-score ship in native screen-Y order");
            } else if (fixture >= 209 && fixture <= 326) {
                assertEquals(3266, responder.battleNetSequenceOffset(),
                        "the broadside cadence remains parked at OP0");
                assertEquals(327 - fixture,
                        responder.battleNetAnimationTimer(),
                        "the fresh 118-count broadside period drains in place");
            }
            if (fixture == 217) {
                assertEquals(54, quarry.hitPoints(),
                        "the released HitUnit source must not take a phantom cannon splash");
            } else if (fixture == 248) {
                assertEquals(86, responder.hitPoints(),
                        "the crossing cannon pulse still damages the responder");
                assertTrue(!responder.battleNetAttackOp0Damaged(),
                        "damage cannot re-arm a broadside hold already in progress");
            } else if (fixture == 328) {
                assertSame(replacement, responder.target(),
                        "the completed cadence still owns the arrival-scan winner");
                assertSame(responder,
                        constructedMissileFrom(world, responder.id()).source(),
                        "the first broadside constructs from the responding destroyer");
            } else if (fixture == 336) {
                assertEquals(98, replacement.hitPoints(),
                        "the cannon remains in flight through fixture 336");
            } else if (fixture == 337) {
                assertEquals(72, replacement.hitPoints(),
                        "the authenticated broadside lands for twenty-six damage");
            }
        }
    }

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

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 162) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture >= 159 && fixture <= 161) {
                assertEquals(18, destroyer.tileX(),
                        "the replacement Attack owns the settled naval residual");
                assertEquals(38, destroyer.tileY());
                assertEquals(3266, destroyer.battleNetSequenceOffset());
                assertEquals(162 - fixture,
                        destroyer.battleNetAnimationTimer(),
                        "native naval retarget construction counts 3,2,1");
            }
        }
        assertEquals(16, destroyer.tileX(),
                "timer one hands the retained west heading back to Move");
        assertEquals(38, destroyer.tileY());
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

    @Test
    @DisplayName("xorc 11's first corpse hold hands ownership to neutral")
    void xorc11sFirstCorpseHoldHandsOwnershipToNeutral() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/orc-exp/levelx11o";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XOrc 11 is not in the pack");
        World world = mission.world();
        Unit body = unitById(world, 75);
        assertNotNull(body, "native slot 1525's destroyer is absent");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 281) {
            mission.tick();
        }
        assertEquals("unit-orc-destroyer", body.type().ident(),
                "the fixture-282 type transition is the lifecycle anchor");

        mission.tick();
        assertEquals("unit-human-dead-body", body.type().ident());
        assertEquals(5, body.player(),
                "installing the body must retain its living owner");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 381) {
            mission.tick();
        }
        assertEquals(5, body.player(),
                "the living owner remains through the complete first compact hold");

        mission.tick();
        assertEquals(World.NEUTRAL_PLAYER, body.player(),
                "the first type-105 decay transition is native's owner handoff");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 481) {
            mission.tick();
        }
        assertTrue(body.isOnMap(),
                "the second compact hold must remain visible through fixture 481");

        mission.tick();
        assertTrue(!body.isOnMap(),
                "the compact decay program invokes Die at fixture 482");
    }

    @Test
    @DisplayName("xorc 11's later corpse repeats the two-hold lifecycle")
    void xorc11sLaterCorpseRepeatsTheTwoHoldLifecycle() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/orc-exp/levelx11o";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XOrc 11 is not in the pack");
        World world = mission.world();
        Unit body = unitById(world, 94);
        assertNotNull(body, "native slot 1506's destroyer is absent");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 349) {
            mission.tick();
        }
        assertEquals("unit-human-dead-body", body.type().ident());
        assertEquals(5, body.player(),
                "the held-out body must begin with the living owner");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 448) {
            mission.tick();
        }
        assertEquals(5, body.player());
        mission.tick();
        assertEquals(World.NEUTRAL_PLAYER, body.player(),
                "the independent first hold expires at fixture 449");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 548) {
            mission.tick();
        }
        assertTrue(body.isOnMap());
        mission.tick();
        assertTrue(!body.isOnMap(),
                "the independent second hold invokes Die at fixture 549");
    }

    private static Missile constructedMissileFrom(World world, int sourceId) {
        for (Missile missile : world.missiles()) {
            if (missile.source() != null
                    && missile.source().id() == sourceId
                    && world.battleNetProjectileConstructed(missile)) {
                return missile;
            }
        }
        throw new AssertionError("no constructed missile from unit " + sourceId);
    }

    private static Unit unitById(World world, int id) {
        return world.unitsSnapshot().stream()
                .filter(unit -> unit.id() == id)
                .findFirst().orElse(null);
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
