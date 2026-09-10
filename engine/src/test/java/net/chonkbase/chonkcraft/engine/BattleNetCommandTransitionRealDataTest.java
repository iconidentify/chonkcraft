package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.missile.Missile;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.save.LoadGame;
import net.chonkbase.chonkcraft.engine.save.SaveGame;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Player command constructors must release movement and combat on retail's visits. */
class BattleNetCommandTransitionRealDataTest {

    @Test
    @DisplayName("a queued retarget advances the committed movement program before starting its attack")
    void aQueuedRetargetAdvancesTheCommittedMovementProgramBeforeStartingItsAttack() {
        Campaign game = new Campaign("campaigns/human/level13h");
        Unit knight = game.unit("unit-knight", 63, 117);
        Unit peasant = game.unit("unit-peasant", 52, 115);
        Unit footman = game.unit("unit-footman", 54, 116);
        Unit gryphon = game.unit("unit-gryphon-rider", 56, 108);
        Unit attacker = game.unit("unit-axethrower", 125, 24, 0);
        List<Unit> selected = List.of(knight, peasant, footman, gryphon);
        int[][] home = {{63, 117}, {52, 115}, {54, 116}, {56, 108}};
        int[][] away = {{63, 113}, {56, 115}, {50, 120}, {56, 104}};
        // Retail slot 1505 queues its new quarry behind the southwest step
        // on fixture 28. HandleUnitAction advances the committed Move program
        // before promoting next_order on 44 (0x4524bb, 0x4524cd, 0x452ef0).
        // Walking only presentation pixels used to pin script.bin at 833/1
        // for fifteen visits, hiding the stale program from position checks.
        int[] sequence = {833, 837, 837, 842, 846, 846, 851, 855,
                860, 864, 864, 869, 873, 873, 878, 882};
        int[] timer = {1, 2, 1, 1, 2, 1, 1, 1, 1, 2, 1, 1, 2, 1, 1, 1};
        for (int cycle = 1; cycle <= 46; cycle++) {
            applyProfile(game, selected, home, away, cycle);
            game.mission.tick();
            if (cycle >= 28 && cycle <= 43) {
                assertTrue(attacker.queuedReplacementPending(),
                        "the replacement must wait for the committed step at " + cycle);
                assertTrue(attacker.isMoving(),
                        "the queued attack must leave the current step moving at " + cycle);
                assertEquals(sequence[cycle - 28], attacker.battleNetSequenceOffset(),
                        "the queued retarget must advance retail's movement instruction at " + cycle);
                assertEquals(timer[cycle - 28], attacker.battleNetAnimationTimer(),
                        "the queued retarget must preserve retail's movement wait at " + cycle);
            }
            if (cycle >= 44) {
                position(attacker, 123, 26, 3936, 832, cycle);
                assertTrue(!attacker.queuedReplacementPending(),
                        "landing must promote the replacement without another movement visit");
                assertEquals(887, attacker.battleNetSequenceOffset(),
                        "the replacement must construct Attack when the movement body finishes");
                assertEquals(47 - cycle, attacker.battleNetAnimationTimer(),
                        "the replacement must preserve Attack construction on visits 44 through 46");
            }
        }
    }

    @Test
    @DisplayName("a scout advances its committed frame before completing an odd destination")
    void aScoutAdvancesItsCommittedFrameBeforeCompletingAnOddDestination() {
        Campaign game = new Campaign("campaigns/orc/level14o");
        Unit axe = game.unit("unit-axethrower", 7, 117);
        Unit grunt = game.unit("unit-grunt", 12, 114);
        Unit balloon = game.unit("unit-balloon", 62, 52, 1);
        List<Unit> selected = List.of(axe, grunt);
        int[][] home = {{7, 117}, {12, 114}};
        int[][] away = {{7, 113}, {8, 110}};
        // Authenticated campaign-orc-14 and the extended retail capture:
        // 0x437670 parks the destination route through 0x450ad0 on 602.
        // The old destination arm skipped the first committed frame on 583
        // while clearing its overshoot byte, leaving every pixel one tick late.
        for (int cycle = 1; cycle <= 605; cycle++) {
            applyProfile(game, selected, home, away, cycle);
            game.mission.tick();
            if (cycle == 582) position(balloon, 52, 30, 1728, 960, cycle);
            if (cycle == 583) position(balloon, 52, 30, 1724, 960, cycle);
            if (cycle == 600) position(balloon, 52, 30, 1670, 960, cycle);
            if (cycle == 601) position(balloon, 52, 30, 1666, 960, cycle);
            if (cycle >= 602) {
                position(balloon, 52, 30, 1664, 960, cycle);
                assertEquals(Unit.Order.STILL, balloon.order(),
                        "the scout must finish on the even anchor beside its point at " + cycle);
            }
            if (cycle >= 602 && cycle <= 604) {
                assertEquals(2083, balloon.battleNetSequenceOffset(),
                        "the completed flight must construct Still at " + cycle);
                assertEquals(605 - cycle, balloon.battleNetAnimationTimer(),
                        "the completed flight must retain native construction timing at " + cycle);
            }
            if (cycle == 605) assertEquals(2086, balloon.battleNetSequenceOffset(),
                    "the scout must enter its native idle loop after construction");
        }
    }

    @Test
    @DisplayName("a regrouping fighter redraws a blocked cached route before its next stride")
    void aRegroupingFighterRedrawsABlockedCachedRouteBeforeItsNextStride() {
        Campaign game = new Campaign("campaigns/human/level13h");
        Unit ogre = game.unit("unit-ogre", 120, 22, 0);
        // Authenticated retail-human-13-idle: after combat, slot 1510
        // refuses its cached north byte on 267 and writes NW,NE,NW,W on 268.
        // Replacing this parked route with a terrain line takes NW initially
        // but blocks north on 280 instead of spending the native NE byte.
        for (int cycle = 1; cycle <= 281; cycle++) {
            game.mission.tick();
            if (cycle == 267) {
                position(ogre, 123, 30, 3936, 960, cycle);
                assertEquals(586, ogre.battleNetSequenceOffset(),
                        "the blocked regroup must park on the native Move marker");
                assertEquals(1, ogre.battleNetAnimationTimer(),
                        "the parked regroup must retry on the next visit");
            }
            if (cycle == 268) position(ogre, 122, 29, 3936, 960, cycle);
            if (cycle == 280) position(ogre, 123, 28, 3904, 928, cycle);
            if (cycle == 281) position(ogre, 123, 28, 3907, 925, cycle);
        }
    }

    @Test
    @DisplayName("regrouping fighters release combat collision state and walk on the native movement body")
    void regroupingFightersReleaseCombatCollisionStateAndWalkOnTheNativeMovementBody() {
        Campaign game = new Campaign("campaigns/human/level13h");
        Unit knight = game.unit("unit-knight", 63, 117);
        Unit peasant = game.unit("unit-peasant", 52, 115);
        Unit footman = game.unit("unit-footman", 54, 116);
        Unit gryphon = game.unit("unit-gryphon-rider", 56, 108);
        Unit eastern = game.unit("unit-ogre", 123, 19, 0);
        Unit neighbour = game.unit("unit-ogre", 125, 22, 0);
        List<Unit> selected = List.of(knight, peasant, footman, gryphon);
        int[][] home = {{63, 117}, {52, 115}, {54, 116}, {56, 108}};
        int[][] away = {{63, 113}, {56, 115}, {50, 120}, {56, 104}};
        // Authenticated campaign-human-13: the player pass queues regroup
        // on 249. 0x45149a calls 0x438410, which parks the route and clears
        // the collision nibble. Slot 1519 takes NE on 252; its real Move
        // body participates in the route which sends slot 1511 NW on 255.
        for (int cycle = 1; cycle <= 256; cycle++) {
            applyProfile(game, selected, home, away, cycle);
            game.mission.tick();
            if (cycle >= 249 && cycle <= 251) {
                position(eastern, 123, 30, 3936, 960, cycle);
                assertEquals(581, eastern.battleNetSequenceOffset(),
                        "the queued regroup must finish Still construction at " + cycle);
                assertEquals(252 - cycle, eastern.battleNetAnimationTimer(),
                        "the regroup must retain all three construction visits at " + cycle);
            }
            if (cycle == 252) {
                position(eastern, 124, 29, 3936, 960, cycle);
                assertEquals(589, eastern.battleNetSequenceOffset(),
                        "the first regroup stride must expose the native Move body");
            }
            if (cycle == 253) position(eastern, 124, 29, 3939, 957, cycle);
            if (cycle == 254) position(eastern, 124, 29, 3942, 954, cycle);
            if (cycle == 255) position(neighbour, 121, 28, 3904, 928, cycle);
            if (cycle == 256) position(neighbour, 121, 28, 3901, 925, cycle);
        }
    }

    @Test
    @DisplayName("a blocked paid chase parks before drawing its next route")
    void aBlockedPaidChaseParksBeforeDrawingItsNextRoute() {
        Campaign game = new Campaign("campaigns/human/level13h");
        Unit knight = game.unit("unit-knight", 63, 117);
        Unit peasant = game.unit("unit-peasant", 52, 115);
        Unit footman = game.unit("unit-footman", 54, 116);
        Unit gryphon = game.unit("unit-gryphon-rider", 56, 108);
        Unit ogre = game.unit("unit-ogre", 123, 19, 0);
        List<Unit> selected = List.of(knight, peasant, footman, gryphon);
        int[][] home = {{63, 117}, {52, 115}, {54, 116}, {56, 108}};
        int[][] away = {{63, 113}, {56, 115}, {50, 120}, {56, 104}};
        // Authenticated campaign-human-13 slot 1519. FUN_004379e0
        // refuses cached SE on 173, increments collision and parks RI20.
        // NewPath writes E,SE,SW on 174. Consuming E on the refusal visit
        // advances every later stride and steals the idle choice on 213.
        for (int cycle = 1; cycle <= 214; cycle++) {
            applyProfile(game, selected, home, away, cycle);
            game.mission.tick();
            if (cycle == 173) {
                position(ogre, 121, 28, 3872, 896, cycle);
                assertEquals(586, ogre.battleNetSequenceOffset(),
                        "the blocked paid route must retain Move on its refusal visit");
                assertEquals(1, ogre.battleNetAnimationTimer(),
                        "a parked cursor must retry on the following Move visit");
            }
            if (cycle == 174) position(ogre, 122, 28, 3872, 896, cycle);
            if (cycle == 175) position(ogre, 122, 28, 3875, 896, cycle);
            if (cycle == 210) position(ogre, 123, 30, 3936, 958, cycle);
            if (cycle >= 211) {
                position(ogre, 123, 30, 3936, 960, cycle);
                assertEquals(Unit.Order.STILL, ogre.order(),
                        "the completed pursuit must release its dying quarry at " + cycle);
            }
            if (cycle >= 211 && cycle <= 213) {
                assertEquals(581, ogre.battleNetSequenceOffset(),
                        "ending the pursuit must retain Still construction at " + cycle);
                assertEquals(214 - cycle, ogre.battleNetAnimationTimer(),
                        "the blocked route must not advance the later idle choice at " + cycle);
            }
            if (cycle == 214) assertEquals(4983, ogre.battleNetSequenceOffset(),
                    "the ogre must own its idle choice on the native visit");
        }
    }

    @Test
    @DisplayName("a chaser finishes its stride and releases a dead target before spending another heading")
    void aChaserFinishesItsStrideAndReleasesADeadTargetBeforeSpendingAnotherHeading() {
        Campaign game = new Campaign("campaigns/human/level13h");
        Unit knight = game.unit("unit-knight", 63, 117);
        Unit peasant = game.unit("unit-peasant", 52, 115);
        Unit footman = game.unit("unit-footman", 54, 116);
        Unit gryphon = game.unit("unit-gryphon-rider", 56, 108);
        Unit grunt = game.unit("unit-grunt", 117, 23, 0);
        Unit quarry = game.unit("unit-knight", 120, 29, 1);
        List<Unit> selected = List.of(knight, peasant, footman, gryphon);
        int[][] home = {{63, 117}, {52, 115}, {54, 116}, {56, 108}};
        int[][] away = {{63, 113}, {56, 115}, {50, 120}, {56, 104}};
        // Authenticated campaign-human-13, fixture
        // ecd647bf9dfdf417574de5294df59f0893f374f4754dcb6ef9a3fbabafda4ae8.
        // On 204, 0x43777c finds the knight dying and 0x437793 clears
        // the target. Still constructs with the route bytes untouched.
        // This paid route has three spent headings and a prior refusal;
        // its remaining S,SW cannot carry the grunt toward a dead knight.
        for (int cycle = 1; cycle <= 208; cycle++) {
            applyProfile(game, selected, home, away, cycle);
            game.mission.tick();
            if (cycle == 203) {
                assertTrue(quarry.isDying(), "the chased knight must die before the stride lands");
                assertSame(quarry, grunt.target(), "the committed stride must keep its quarry reference");
                position(grunt, 123, 28, 3934, 894, cycle);
            }
            if (cycle >= 204) {
                position(grunt, 123, 28, 3936, 896, cycle);
                assertEquals(Unit.Order.STILL, grunt.order(),
                        "the dead quarry must release pursuit on the landing visit at " + cycle);
                assertNull(grunt.target(), "the finished attack must release its dead quarry at " + cycle);
                assertEquals(2, grunt.pathLength(),
                        "ending the attack must retain both unused route headings at " + cycle);
            }
            if (cycle >= 204 && cycle <= 206) {
                assertEquals(2477, grunt.battleNetSequenceOffset(),
                        "the finished pursuit must construct native Still at " + cycle);
                assertEquals(207 - cycle, grunt.battleNetAnimationTimer(),
                        "the idle choice must wait for all three construction visits at " + cycle);
            }
            if (cycle == 207) assertEquals(4983, grunt.battleNetSequenceOffset(),
                    "the released chaser must own its native idle choice on 207");
        }
    }

    @Test
    @DisplayName("a blocked unit's movement remainder does not bring it into splash range")
    void aBlockedUnitsMovementRemainderDoesNotBringItIntoSplashRange() {
        Campaign game = new Campaign("campaigns/human-exp/levelx10h");
        Unit peasant = game.unit("unit-peasant", 105, 99);
        Unit grunt = game.unit("unit-grunt", 76, 92, 2);
        List<Unit> selected = List.of(peasant);
        int[][] home = {{105, 99}};
        int[][] away = {{105, 95}};
        // Authenticated campaign-xhuman-10, fixture
        // 2991cba8b85bc62adccc11b8ac049e8e7a40643d9f58e89d9e3bf676abc110f3.
        // Ballista impact 2568,2832 at 87 admits four victims, excluding
        // grunt 1475. FUN_00410680 reads raw pixels at +0/+2; adding the
        // movement remainder invents a fifth victim and a fifth damage roll.
        for (int cycle = 1; cycle <= 87; cycle++) {
            applyProfile(game, selected, home, away, cycle);
            game.mission.tick();
            if (cycle >= 75) {
                position(grunt, 79, 90, 2528, 2880, cycle);
                assertEquals(60, grunt.hitPoints(),
                        "the grunt outside the bolt's splash must keep its health at " + cycle);
            }
        }
    }

    @Test
    @DisplayName("a finished swing retries an unreachable replacement before pursuing a surviving worker")
    void aFinishedSwingRetriesAnUnreachableReplacementBeforePursuingASurvivingWorker() {
        Campaign game = new Campaign("campaigns/human/level08h");
        Unit footman = game.unit("unit-footman", 87, 92);
        Unit knight = game.unit("unit-knight", 90, 90);
        Unit attacker = game.unit("unit-attack-peasant", 68, 67, 4);
        Unit replacement = game.unit("unit-peasant", 74, 61, 2);
        List<Unit> selected = List.of(footman, knight);
        int[][] home = {{87, 92}, {90, 90}};
        int[][] away = {{87, 88}, {94, 94}};
        // Authenticated campaign-human-08, fixture
        // e64b36cce0cb8343a51f4e052b3b5be1c0bbad81d618ab82982452df39c39b16.
        // At 452, 0x437732 selects worker 1536 before NewPath. Its failure
        // reaches 0x43789d Still and active idle before the new Attack is
        // promoted. The retry on 455 constructs again; the worker's death
        // on 457 releases pursuit toward the surviving worker on 458.
        for (int cycle = 1; cycle <= 459; cycle++) {
            applyProfile(game, selected, home, away, cycle);
            game.mission.tick();
            if (cycle >= 452 && cycle <= 457) {
                position(attacker, 78, 62, 2496, 1984, cycle);
                assertSame(replacement, attacker.target(),
                        "the failed route must retain the newly selected worker at " + cycle);
                assertEquals(2657, attacker.battleNetSequenceOffset(),
                        "each failed approach must construct its replacement attack at " + cycle);
                assertEquals(3 - (cycle - 452) % 3, attacker.battleNetAnimationTimer(),
                        "the retry must finish all three construction visits at " + cycle);
            }
            if (cycle == 458) position(attacker, 78, 63, 2496, 1984, cycle);
            if (cycle == 459) position(attacker, 78, 63, 2496, 1987, cycle);
        }
    }

    @Test
    @DisplayName("a killing blow finishes its body before constructing the next attack")
    void aKillingBlowFinishesItsBodyBeforeConstructingTheNextAttack() throws Exception {
        verifyKillingBlowRetarget(false);
    }

    @Test
    @DisplayName("saving after a killing blow preserves the next attack boundary")
    void savingAfterAKillingBlowPreservesTheNextAttackBoundary() throws Exception {
        verifyKillingBlowRetarget(true);
    }

    private static void verifyKillingBlowRetarget(boolean reload) throws Exception {
        String map = "campaigns/human/level08h";
        Campaign game = new Campaign(map);
        Mission mission = game.mission;
        Unit attacker = game.unit("unit-attack-peasant", 77, 69, 4);
        // Native slot 1520 kills its worker on 565. The 0x437478 write
        // retires +0x88 while the committed swing still has fifteen visits
        // left. The next target constructs Attack before any pursuit.
        for (int cycle = 1; cycle <= 583; cycle++) {
            mission.tick();
            if (cycle == 570 && reload) {
                StringWriter saved = new StringWriter();
                SaveGame.writeWithTriggers(mission.world(), map, "human", 8,
                        mission.triggers().savedState(), saved);
                GameData data = new GameData(AssetSource.fromEnvironment());
                mission = data.loadMission(map, game.person, 1);
                for (Unit unit : new ArrayList<>(mission.world().units())) {
                    mission.world().remove(unit);
                }
                LoadGame.apply(mission.world(), saved.toString(), data.unitTypes().types());
                mission.triggers().restoreState(LoadGame.triggerState(saved.toString()));
                List<Unit> restored = mission.world().units().stream()
                        .filter(unit -> unit.player() == 4
                                && unit.type().ident().equals("unit-attack-peasant")
                                && unit.tileX() == 77 && unit.tileY() == 68).toList();
                assertEquals(1, restored.size(),
                        "the saved attacker must remain uniquely identifiable");
                attacker = restored.getFirst();
            }
            if (cycle >= 565 && cycle <= 582) {
                position(attacker, 77, 68, 2464, 2176, cycle);
            }
            if (cycle >= 580 && cycle <= 582) {
                assertEquals(2657, attacker.battleNetSequenceOffset(),
                        "the retired quarry must not skip the replacement's attack constructor");
                assertEquals(583 - cycle, attacker.battleNetAnimationTimer(),
                        "a completed killing swing must retain native replacement timing");
            }
            if (cycle == 583) position(attacker, 77, 69, 2464, 2176, cycle);
        }
    }

    @Test
    @DisplayName("a melee retarget waits behind an ally without changing its route face")
    void aMeleeRetargetWaitsBehindAnAllyWithoutChangingItsRouteFace() {
        Campaign game = new Campaign("campaigns/human/level08h");
        Unit footman = game.unit("unit-footman", 87, 92);
        Unit knight = game.unit("unit-knight", 90, 90);
        Unit attacker = game.unit("unit-attack-peasant", 70, 72, 4);
        Unit worker = game.unit("unit-peasant", 74, 61, 2);
        List<Unit> selected = List.of(footman, knight);
        int[][] home = {{87, 92}, {90, 90}};
        int[][] away = {{87, 88}, {94, 94}};
        // Native slot 1513 changes its dying, distant quarry through Move
        // on 372. The blocked first heading parks RI20 before its queued
        // Attack constructs; later retries retain the fresh route writer.
        for (int cycle = 1; cycle <= 382; cycle++) {
            applyProfile(game, selected, home, away, cycle);
            game.mission.tick();
            if (cycle >= 372 && cycle <= 380) {
                position(attacker, 78, 62, 2496, 1984, cycle);
                assertSame(worker, attacker.target(),
                        "the blocked pursuit must retain its replacement quarry");
                boolean construction = cycle >= 373 && cycle <= 375;
                assertEquals(construction ? 2657 : 2600,
                        attacker.battleNetSequenceOffset(),
                        "the retarget must preserve native movement and construction ownership");
                assertEquals(construction ? 376 - cycle : 1,
                        attacker.battleNetAnimationTimer(),
                        "a blocked route must not insert another attack or fifteen-visit wait");
            }
            if (cycle == 381) position(attacker, 78, 61, 2496, 1984, cycle);
            if (cycle == 382) position(attacker, 78, 61, 2496, 1981, cycle);
        }
    }

    @Test
    @DisplayName("a melee retarget keeps its weapon cooldown while pursuing the next enemy")
    void aMeleeRetargetKeepsItsWeaponCooldownWhilePursuingTheNextEnemy() {
        Campaign game = new Campaign("campaigns/human/level08h");
        Unit footman = game.unit("unit-footman", 87, 92);
        Unit knight = game.unit("unit-knight", 90, 90);
        Unit attacker = game.unit("unit-attack-peasant", 72, 60, 4);
        Unit worker = game.unit("unit-peasant", 74, 61, 2);
        List<Unit> selected = List.of(footman, knight);
        int[][] home = {{87, 92}, {90, 90}};
        int[][] away = {{87, 88}, {94, 94}};
        // BNE slot 1538 reloads +0x7a at 0x40b369 on fixture 361.
        // Its next pursuit lands with seven visits still owed. Attack
        // construction consumes three, then OP0 holds the remaining four.
        for (int cycle = 1; cycle <= 396; cycle++) {
            applyProfile(game, selected, home, away, cycle);
            game.mission.tick();
            if (cycle == 364) {
                position(attacker, 79, 61, 2528, 1984, cycle);
                assertSame(worker, attacker.target(),
                        "the completed retarget constructor must select before routing");
            }
            if (cycle == 380) position(attacker, 79, 61, 2528, 1952, cycle);
            if (cycle >= 383 && cycle <= 386) {
                assertEquals(2657, attacker.battleNetSequenceOffset(),
                        "the resumed attack must wait at its native opening callback");
                assertEquals(387 - cycle, attacker.battleNetAnimationTimer(),
                        "movement must consume, rather than restart, the weapon cooldown");
            }
            if (cycle >= 387) assertEquals(25, worker.hitPoints(),
                    "the replacement swing must not hit before native fixture 397");
        }
    }

    @Test
    @DisplayName("an archer's stop takes effect when its committed movement finishes")
    void anArchersStopTakesEffectWhenItsCommittedMovementFinishes() {
        Campaign game = new Campaign("campaigns/human/level11h");
        Unit ballista = game.unit("unit-ballista", 8, 89);
        Unit archer = game.unit("unit-archer", 7, 86);
        List<Unit> selected = List.of(ballista, archer);
        int[][] home = {{8, 89}, {7, 86}};
        int[][] away = {{8, 85}, {11, 90}};
        // Native slot 1450 receives Stop on 255 and lands on 259. Its
        // residual movement cursor has finished; a cached Move-start cursor
        // must not be mistaken for another outstanding refusal callback.
        for (int cycle = 1; cycle <= 267; cycle++) {
            applyProfile(game, selected, home, away, cycle);
            game.mission.tick();
            if (cycle == 258) position(archer, 7, 86, 226, 2754, cycle);
            if (cycle >= 259) {
                position(archer, 7, 86, 224, 2752, cycle);
                assertEquals(Unit.Order.STILL, archer.order(),
                        "Stop must take ownership on the last committed movement visit");
            }
            if (cycle >= 259 && cycle <= 261) {
                assertEquals(262 - cycle, archer.battleNetAnimationTimer(),
                        "the new Stop must retain the native constructor at " + cycle);
            }
            if (cycle == 262 || cycle == 267) {
                assertEquals(4983, archer.battleNetSequenceOffset(),
                        "the completed Stop must reach its native idle callback on time");
            }
        }
    }

    @Test
    @DisplayName("a worker resumes its loaded return when an escape stride lands")
    void aWorkerResumesItsLoadedReturnWhenAnEscapeStrideLands() {
        Campaign game = new Campaign("campaigns/human/level08h");
        Unit footman = game.unit("unit-footman", 87, 92);
        Unit knight = game.unit("unit-knight", 90, 90);
        Unit worker = game.unit("unit-peasant", 74, 61, 2);
        List<Unit> selected = List.of(footman, knight);
        int[][] home = {{87, 92}, {90, 90}};
        int[][] away = {{87, 88}, {94, 94}};
        int load = 0;
        // The sealed command run's worker 1536 lands its temporary escape
        // on 323. The 0x452fa2 write restores action 24 on that same visit;
        // its three-call Still retries keep the loaded return active.
        for (int cycle = 1; cycle <= 420; cycle++) {
            applyProfile(game, selected, home, away, cycle);
            game.mission.tick();
            if (cycle == 322) {
                position(worker, 80, 60, 2558, 1922, cycle);
                assertEquals(Unit.Order.MOVE, worker.order(),
                        "the escape must retain its last committed pixels");
                load = worker.carried();
                assertTrue(load > 0, "the interrupted worker must still carry its load");
            }
            if (cycle >= 323 && cycle <= 338) {
                position(worker, 80, 60, 2560, 1920, cycle);
                assertEquals(Unit.Order.HARVEST, worker.order(),
                        "the worker must resume its loaded return on the landing visit");
                assertEquals(3 - (cycle - 323) % 3,
                        worker.battleNetAnimationTimer(),
                        "the restored return must keep its native retry callback at " + cycle);
                assertEquals(load, worker.carried(),
                        "an escape and its route retries must preserve the load");
                assertTrue(worker.returningToDepot(),
                        "the loaded worker must remain on the return leg");
            }
            if (cycle == 397) assertEquals(21, worker.hitPoints(),
                    "the worker's retry callbacks must preserve the later damage draw");
            if (cycle == 420) assertEquals(15, worker.hitPoints(),
                    "repeated return retries must retain native combat damage");
        }
    }

    @Test
    @DisplayName("stop on a worker's landing constructs Still before a later attack-move")
    void stopOnAWorkersLandingConstructsStillBeforeALaterAttackMove() {
        Campaign game = new Campaign("campaigns/orc/level09o");
        Unit grunt = game.unit("unit-grunt", 42, 91);
        Unit peon = game.unit("unit-peon", 38, 92);
        Unit destroyer = game.unit("unit-orc-destroyer", 52, 82);
        List<Unit> selected = List.of(grunt, peon, destroyer);
        int[][] home = {{42, 91}, {38, 92}, {52, 82}};
        int[][] away = {{42, 87}, {42, 88}, {52, 78}};
        // Native slot 1478 drains its last two pixels on the Stop visit
        // at 110. This promotes fresh Still 3..1; it does not execute the
        // idle callback used by an autonomous empty-route completion.
        for (int cycle = 1; cycle <= 116; cycle++) {
            applyProfile(game, selected, home, away, cycle);
            game.mission.tick();
            if (cycle == 109) position(peon, 39, 91, 1250, 2910, cycle);
            if (cycle >= 110 && cycle <= 112) {
                position(peon, 39, 91, 1248, 2912, cycle);
                assertEquals(Unit.Order.STILL, peon.order(),
                        "the completed Stop must retain its constructor at " + cycle);
                assertEquals(113 - cycle, peon.battleNetAnimationTimer(),
                        "landing must construct Still without an extra idle action");
            }
            if (cycle == 113) {
                assertEquals(Unit.Order.ATTACK_MOVE, peon.order(),
                        "the later march must promote on the native action callback");
                assertEquals(3, peon.battleNetAnimationTimer());
            }
            if (cycle == 116) position(peon, 40, 90, 1248, 2912, cycle);
        }
    }

    @Test
    @DisplayName("a siege command startup keeps nearby worker routes and replacements on time")
    void aSiegeCommandStartupKeepsNearbyWorkerRoutesAndReplacementsOnTime() {
        Campaign game = new Campaign("campaigns/orc-exp/levelx11o");
        Unit peon = game.unit("unit-peon", 0, 49);
        Unit grunt = game.unit("unit-grunt", 1, 48);
        Unit caster = game.unit("unit-evil-knight", 0, 44);
        Unit catapult = game.unit("unit-catapult", 1, 45);
        List<Unit> selected = List.of(peon, grunt, caster, catapult);
        int[][] home = {{0, 49}, {1, 48}, {0, 44}, {1, 45}};
        int[][] away = {{0, 45}, {5, 44}, {4, 48}, {1, 49}};
        // The catapult's native Still program is on shared-body timer one
        // when Move arrives. Its presentation wait adds no native time.
        // Delaying it three ticks makes the worker route through its body
        // at 81, instead of following the sealed SE,SE,SW wall route.
        for (int cycle = 1; cycle <= 151; cycle++) {
            applyProfile(game, selected, home, away, cycle);
            game.mission.tick();
            if (cycle == 11) position(catapult, 1, 46, 32, 1440, cycle);
            if (cycle == 43) {
                position(catapult, 1, 46, 32, 1472, cycle);
                assertEquals(Unit.Order.PATROL, catapult.order(),
                        "the newest patrol must replace the siege stride on landing");
            }
            if (cycle == 78) {
                position(catapult, 1, 47, 32, 1504, cycle);
                assertEquals(Unit.Order.MOVE, catapult.order(),
                        "the return click must take ownership when the patrol stride lands");
            }
            if (cycle == 81) assertEquals(1, catapult.battleNetCollisionCounter(),
                    "the refused return route must make this occupied square a native wall");
            if (cycle == 97) position(peon, 2, 47, 32, 1472, cycle);
            if (cycle == 113) {
                position(peon, 2, 47, 64, 1504, cycle);
                assertEquals(Unit.Order.ATTACK_MOVE, peon.order(),
                        "the worker's replacement must start on its native landing visit");
            }
            if (cycle == 116) position(peon, 1, 46, 64, 1504, cycle);
            if (cycle >= 148) {
                position(peon, 0, 45, 0, 1440, cycle);
                assertEquals(Unit.Order.STILL, peon.order(),
                        "the worker must finish the replacement at its destination");
            }
        }
    }

    @Test
    @DisplayName("stop replaces a blocked march with fresh Still before the next attack-move")
    void stopReplacesABlockedMarchWithFreshStillBeforeTheNextAttackMove() {
        Campaign game = new Campaign("campaigns/orc-exp/levelx11o");
        Unit peon = game.unit("unit-peon", 0, 49);
        Unit grunt = game.unit("unit-grunt", 1, 48);
        Unit caster = game.unit("unit-evil-knight", 0, 44);
        Unit catapult = game.unit("unit-catapult", 1, 45);
        List<Unit> selected = List.of(peon, grunt, caster, catapult);
        int[][] home = {{0, 49}, {1, 48}, {0, 44}, {1, 45}};
        int[][] away = {{0, 45}, {5, 44}, {4, 48}, {1, 49}};
        // Native command-campaign XOrc 11 slot 1488: Move 15..1 at
        // 95..109, Stop construction 3..1 at 110..112, then action 10.
        // An idle callback on 110 steals the destroyer's later damage draw.
        for (int cycle = 1; cycle <= 116; cycle++) {
            applyProfile(game, selected, home, away, cycle);
            game.mission.tick();
            if (cycle >= 95 && cycle <= 109) {
                position(grunt, 3, 45, 96, 1440, cycle);
                assertEquals(110 - cycle, grunt.battleNetAnimationTimer(),
                        "the blocked march must retain its native Move wait at " + cycle);
            }
            if (cycle >= 110 && cycle <= 112) {
                assertEquals(Unit.Order.STILL, grunt.order(),
                        "Stop must own its complete constructor at " + cycle);
                assertEquals(113 - cycle, grunt.battleNetAnimationTimer(),
                        "Stop must construct fresh Still without an idle callback at " + cycle);
            }
            if (cycle >= 113) assertEquals(Unit.Order.ATTACK_MOVE, grunt.order(),
                    "the newest march must promote when Stop construction finishes");
            if (cycle == 116) position(grunt, 4, 44, 96, 1440, cycle);
        }
    }

    @Test
    @DisplayName("a blocked pursuit clears its old collision generation before accepting a new route")
    void aBlockedPursuitClearsItsOldCollisionGenerationBeforeAcceptingANewRoute() {
        Campaign game = new Campaign("campaigns/human-exp/levelx12h");
        Unit grunt = game.unit("unit-grunt", 18, 55, 2);
        Unit footman = game.unit("unit-footman", 26, 59, 1);
        // Native slot 1457 pays two full Move refusal bands, then the
        // 0x43841b watch clears its collision nibble on Attack promotion.
        // Retaining ten made the later valid W route look exhausted.
        for (int cycle = 1; cycle <= 375; cycle++) {
            game.mission.tick();
            if (cycle >= 188 && cycle <= 217) {
                position(grunt, 24, 59, 768, 1888, cycle);
                assertEquals(15 - (cycle - 188) % 15,
                        grunt.battleNetAnimationTimer(),
                        "the blocked pursuit must finish each paid wait at " + cycle);
            }
            if (cycle >= 218 && cycle <= 220) {
                assertEquals(0, grunt.battleNetCollisionCounter(),
                        "the new Attack order must release the retired collision generation");
                assertEquals(221 - cycle, grunt.battleNetAnimationTimer(),
                        "the new Attack order must count its constructor at " + cycle);
            }
            if (cycle >= 344 && cycle <= 358) {
                position(grunt, 24, 59, 768, 1888, cycle);
                assertEquals(359 - cycle, grunt.battleNetAnimationTimer(),
                        "the fresh route must retain its complete native refusal wait");
                assertSame(footman, grunt.target(),
                        "the rebuilt route must belong to the new quarry");
                assertEquals(1, grunt.battleNetCollisionCounter(),
                        "the new route must start its own collision generation");
            }
            if (cycle == 359) position(grunt, 23, 59, 768, 1888, cycle);
            if (cycle == 374) position(grunt, 23, 59, 738, 1888, cycle);
            if (cycle == 375) position(grunt, 22, 58, 736, 1888, cycle);
        }
    }

    @Test
    @DisplayName("a cavalry pursuit keeps its complete offered route and paid attack startup")
    void aCavalryPursuitKeepsItsCompleteOfferedRouteAndPaidAttackStartup() {
        Campaign game = new Campaign("campaigns/human-exp/levelx10h");
        Unit knight = game.unit("unit-knight", 84, 88);
        Unit catapult = game.unit("unit-catapult", 74, 89, 2);
        // The 0x44ff2a route witness writes SW,SW,SW,W,W at 438.
        // After the later refusal, NW lands in range at 502 and enters
        // the already-paid Attack body; another constructor delays the hit.
        for (int cycle = 1; cycle <= 512; cycle++) {
            game.mission.tick();
            if (cycle == 438) {
                assertSame(catapult, knight.target(),
                        "the offered quarry must own the complete skirt route");
                assertEquals(4, knight.pathLength(),
                        "the first stride must leave all four native route bytes");
            }
            if (cycle == 490) position(knight, 75, 89, 2432, 2880, cycle);
            if (cycle == 502) {
                position(knight, 75, 89, 2400, 2848, cycle);
                assertEquals(1, knight.battleNetAnimationTimer(),
                        "the paid pursuit must enter Attack without another constructor");
                assertTrue(knight.fighting(), "the landed pursuit must own an active attack");
            }
            if (cycle == 511) assertEquals(99, catapult.hitPoints(),
                    "the knight's next blow must still be winding up");
            if (cycle == 512) assertEquals(92, catapult.hitPoints(),
                    "the knight's blow must land on the native damage visit");
        }
    }

    @Test
    @DisplayName("a completed pursuit retains its aggressor and damages the correct replacement")
    void aCompletedPursuitRetainsItsAggressorAndDamagesTheCorrectReplacement() {
        Campaign game = new Campaign("campaigns/human-exp/levelx04h");
        Unit peasant = game.unit("unit-peasant", 66, 68);
        Unit mage = game.unit("unit-mage", 65, 65);
        Unit selectedFootman = game.unit("unit-footman", 71, 62);
        Unit balloon = game.unit("unit-balloon", 78, 108);
        Unit defender = game.unit("unit-footman", 72, 60);
        Unit aggressor = game.unit("unit-axethrower", 78, 61, 0);
        Unit otherAxe = game.unit("unit-axethrower", 78, 62, 0);
        List<Unit> selected = List.of(peasant, mage, selectedFootman, balloon);
        int[][] home = {{66, 68}, {65, 65}, {71, 62}, {78, 108}};
        int[][] away = {{66, 64}, {69, 69}, {67, 66}, {78, 104}};
        // Native slot 1518 retains its recorded aggressor at unit+0x54
        // when the old quarry dies. The 0x453023 watch seals construction
        // at 311; 0x44fbd0 writes NE at 314 around the aggressor's body.
        // A plausible-looking route used to hide blows to the wrong axe.
        for (int cycle = 1; cycle <= 365; cycle++) {
            applyProfile(game, selected, home, away, cycle);
            game.mission.tick();
            if (cycle >= 311) assertSame(aggressor, defender.target(),
                    "the recorded aggressor must remain the replacement quarry at " + cycle);
            if (cycle >= 311 && cycle <= 313) {
                assertEquals(314 - cycle, defender.battleNetAnimationTimer(),
                        "the replacement pursuit must finish construction at " + cycle);
            }
            if (cycle == 314) position(defender, 75, 60, 2368, 1952, cycle);
            if (cycle == 330) position(defender, 75, 60, 2400, 1920, cycle);
            if (cycle == 339) assertEquals(40, aggressor.hitPoints(),
                    "the replacement's first blow must still be winding up");
            if (cycle == 340) assertEquals(33, aggressor.hitPoints(),
                    "the first replacement blow must damage the recorded aggressor");
            if (cycle == 365) {
                assertEquals(25, aggressor.hitPoints(),
                        "the second replacement blow must hit the same aggressor");
                assertEquals(40, otherAxe.hitPoints(),
                        "the neighboring axe must not receive the aggressor's damage");
            }
        }
    }

    @Test
    @DisplayName("a paid pursuit replacing a ranged quarry finishes its new attack transition")
    void aPaidPursuitReplacingARangedQuarryFinishesItsNewAttackTransition() {
        Campaign game = new Campaign("campaigns/human-exp/levelx04h");
        Unit peasant = game.unit("unit-peasant", 66, 68);
        Unit mage = game.unit("unit-mage", 65, 65);
        Unit selectedFootman = game.unit("unit-footman", 71, 62);
        Unit balloon = game.unit("unit-balloon", 78, 108);
        Unit defender = game.unit("unit-footman", 72, 61);
        Unit replacement = game.unit("unit-axethrower", 78, 59, 0);
        List<Unit> selected = List.of(peasant, mage, selectedFootman, balloon);
        int[][] home = {{66, 68}, {65, 65}, {71, 62}, {78, 108}};
        int[][] away = {{66, 64}, {69, 69}, {67, 66}, {78, 104}};
        // Native slot 1498 replaces axe 1490 with 1521 on the landing
        // callback at 379. AutoSelectTarget queues action 12 through
        // 0x436887; promotion at 0x453023 writes Attack construction 3.
        // Reusing the old Move body used to land the blow on 390, not 415.
        for (int cycle = 1; cycle <= 415; cycle++) {
            applyProfile(game, selected, home, away, cycle);
            game.mission.tick();
            if (cycle >= 379 && cycle <= 381) {
                position(defender, 75, 61, 2400, 1952, cycle);
                assertSame(replacement, defender.target(),
                        "the newly adjacent axe must own the landed pursuit at " + cycle);
                assertEquals(382 - cycle, defender.battleNetAnimationTimer(),
                        "the new target must pay its own attack transition at " + cycle);
            }
            if (cycle == 382) assertEquals(23, defender.battleNetAnimationTimer(),
                    "the replacement must enter the native melee wind-up after construction");
            if (cycle == 414) assertEquals(30, replacement.hitPoints(),
                    "the replacement must remain unharmed until the native damage visit");
            if (cycle == 415) assertEquals(20, replacement.hitPoints(),
                    "the replacement blow must land for native damage on its native visit");
        }
    }

    @Test
    @DisplayName("a retaliating defender pursues its next enemy and waits behind a moving ally")
    void aRetaliatingDefenderPursuesItsNextEnemyAndWaitsBehindAMovingAlly() {
        Campaign game = new Campaign("campaigns/human-exp/levelx04h");
        Unit peasant = game.unit("unit-peasant", 66, 68);
        Unit mage = game.unit("unit-mage", 65, 65);
        Unit selectedFootman = game.unit("unit-footman", 71, 62);
        Unit balloon = game.unit("unit-balloon", 78, 108);
        Unit defender = game.unit("unit-footman", 72, 61);
        Unit stationaryNeighbour = game.unit("unit-footman", 72, 62);
        Unit grunt = game.unit("unit-grunt", 77, 61, 0);
        Unit axe = game.unit("unit-axethrower", 78, 62, 0);
        List<Unit> selected = List.of(peasant, mage, selectedFootman, balloon);
        int[][] home = {{66, 68}, {65, 65}, {71, 62}, {78, 108}};
        int[][] away = {{66, 64}, {69, 69}, {67, 66}, {78, 104}};
        // Native slot 1498 promotes action 16 to 12 at 211, then pursues
        // axethrower 1490 after its aggressor dies. The hardware watch at
        // 0x437a25 seals the full blocked-route wait beginning at 331.
        // Slot 1495 has only a retired offer and must remain stationary.
        for (int cycle = 1; cycle <= 346; cycle++) {
            applyProfile(game, selected, home, away, cycle);
            game.mission.tick();
            if (cycle == 211) assertSame(grunt, defender.target(),
                    "the live aggressor must become the defender's replacement target");
            if (cycle >= 312 && cycle <= 314) {
                assertSame(axe, defender.target(),
                        "a retaliating defender must acquire the next enemy at " + cycle);
                assertEquals(315 - cycle, defender.battleNetAnimationTimer(),
                        "pursuit must pay the replacement's construction visits at " + cycle);
            }
            if (cycle == 315) position(defender, 73, 60, 2304, 1952, cycle);
            if (cycle >= 331 && cycle <= 345) {
                position(defender, 73, 60, 2336, 1920, cycle);
                assertEquals(346 - cycle, defender.battleNetAnimationTimer(),
                        "a moving ally must retain the native fifteen-visit route wait at " + cycle);
            }
            if (cycle >= 334 && cycle <= 337) {
                position(stationaryNeighbour, 72, 62, 2304, 1984, cycle);
                assertEquals(Unit.Order.STILL, stationaryNeighbour.order(),
                        "a retired aggressor must not turn stationary defense into pursuit at " + cycle);
            }
            if (cycle == 346) position(defender, 74, 60, 2336, 1920, cycle);
        }
    }

    @Test
    @DisplayName("an archer replaces a dying quarry during reload without buying another reload")
    void anArcherReplacesADyingQuarryDuringReloadWithoutBuyingAnotherReload() {
        Campaign game = new Campaign("campaigns/human-exp/levelx04h");
        Unit peasant = game.unit("unit-peasant", 66, 68);
        Unit mage = game.unit("unit-mage", 65, 65);
        Unit footman = game.unit("unit-footman", 71, 62);
        Unit balloon = game.unit("unit-balloon", 78, 108);
        Unit archer = game.unit("unit-archer", 70, 62);
        Unit replacement = game.unit("unit-grunt", 77, 61, 0);
        List<Unit> selected = List.of(peasant, mage, footman, balloon);
        int[][] home = {{66, 68}, {65, 65}, {71, 62}, {78, 108}};
        int[][] away = {{66, 64}, {69, 69}, {67, 66}, {78, 104}};
        Set<Missile> observed = new LinkedHashSet<>();
        List<Integer> shots = new ArrayList<>();
        // Native slot 1486 retargets on 245 while 0x40b106 installs the
        // remaining 63-count reload. Its next arrow is constructed at 319.
        for (int cycle = 1; cycle <= 319; cycle++) {
            applyProfile(game, selected, home, away, cycle);
            game.mission.tick();
            for (Missile missile : game.mission.world().missiles()) {
                if (missile.source() == archer
                        && game.mission.world().savedProjectileStartCycle(missile) >= 0
                        && observed.add(missile)) shots.add(cycle);
            }
            if (cycle == 245) {
                assertSame(replacement, archer.target(),
                        "the archer must replace its dying quarry before the reload expires");
                assertEquals(63, archer.battleNetAnimationTimer(),
                        "the replacement must inherit only the remaining reload");
            }
        }
        assertEquals(List.of(57, 188, 319), shots,
                "retargeting during reload must preserve every native arrow launch visit");
    }

    @Test
    @DisplayName("a replacement march routes around its own occupied square before returning home")
    void aReplacementMarchRoutesAroundItsOwnOccupiedSquareBeforeReturningHome() {
        Campaign game = new Campaign("campaigns/human/level10h");
        Unit footman = game.unit("unit-footman", 107, 115);
        Unit peasant = game.unit("unit-attack-peasant", 111, 112);
        List<Unit> selected = List.of(footman, peasant);
        int[][] home = {{107, 115}, {111, 112}};
        int[][] away = {{107, 111}, {115, 108}};
        // Authenticated campaign-human-10 slot 1502. At 116, the native
        // wall follower keeps map word 0x101 on the router's old square.
        // Its two wall faces score 25 and 27, so the first route wins.
        // Clearing self-occupancy incorrectly scored the second face 19
        // and sent the peasant west instead of northeast after the click.
        for (int cycle = 1; cycle <= 186; cycle++) {
            applyProfile(game, selected, home, away, cycle);
            game.mission.tick();
            if (cycle >= 113 && cycle <= 115) {
                position(peasant, 111, 114, 3552, 3648, cycle);
                assertEquals(116 - cycle, peasant.battleNetAnimationTimer(),
                        "the replacement march must finish construction before routing at " + cycle);
            }
            if (cycle == 116) position(peasant, 112, 113, 3552, 3648, cycle);
            if (cycle == 117) position(peasant, 112, 113, 3555, 3645, cycle);
            if (cycle == 132) position(peasant, 112, 112, 3584, 3616, cycle);
            if (cycle == 150) position(peasant, 112, 111, 3584, 3581, cycle);
            if (cycle >= 183) {
                position(peasant, 111, 112, 3552, 3584, cycle);
                assertEquals(Unit.Order.STILL, peasant.order(),
                        "the later return click must finish at home on the native visit " + cycle);
            }
        }
    }

    @Test
    @DisplayName("a settled pursuit acquires the adjacent enemy before parking its blocked route")
    void aSettledPursuitAcquiresTheAdjacentEnemyBeforeParkingItsBlockedRoute() {
        Campaign game = new Campaign("campaigns/human-exp/levelx04h");
        Unit peasant = game.unit("unit-peasant", 66, 68);
        Unit mage = game.unit("unit-mage", 65, 65);
        Unit footman = game.unit("unit-footman", 71, 62);
        Unit balloon = game.unit("unit-balloon", 78, 108);
        Unit grunt = game.unit("unit-grunt", 77, 61, 0);
        List<Unit> selected = List.of(peasant, mage, footman, balloon);
        int[][] home = {{66, 68}, {65, 65}, {71, 62}, {78, 108}};
        int[][] away = {{66, 64}, {69, 69}, {67, 66}, {78, 104}};
        // Authenticated campaign-xhuman-04 slot 1484; native 0x453023
        // writes fresh construction at 217 after the shared attack callback.
        // Its OP10 deals seven to slot 1505 at 253 before Stop at 255.
        for (int cycle = 1; cycle <= 268; cycle++) {
            applyProfile(game, selected, home, away, cycle);
            game.mission.tick();
            if (cycle >= 217 && cycle <= 219) {
                position(footman, 72, 63, 2304, 2016, cycle);
                assertSame(grunt, footman.target(),
                        "the adjacent enemy must own the completed pursuit at " + cycle);
                assertEquals(220 - cycle, footman.battleNetAnimationTimer(),
                        "the replacement must pay its three native construction visits at " + cycle);
            }
            if (cycle == 252) assertEquals(43, grunt.hitPoints(),
                    "the footman's replacement swing must still be winding up");
            if (cycle == 253) assertEquals(36, grunt.hitPoints(),
                    "the replacement swing must land on the native damage visit");
            if (cycle == 268) assertEquals(Unit.Order.STILL, footman.order(),
                    "Stop must release the completed swing on the native boundary");
        }
    }

    @Test
    @DisplayName("ranged retargets keep the remaining reload and repeat it after the next shot")
    void rangedRetargetsKeepTheRemainingReloadAndRepeatItAfterTheNextShot() {
        Campaign game = new Campaign("campaigns/human-exp/levelx04h");
        Unit peasant = game.unit("unit-peasant", 66, 68);
        Unit mage = game.unit("unit-mage", 65, 65);
        Unit footman = game.unit("unit-footman", 71, 62);
        Unit balloon = game.unit("unit-balloon", 78, 108);
        Unit axe = game.unit("unit-axethrower", 78, 61, 0);
        Unit firstReplacement = game.unit("unit-footman", 71, 60);
        Unit secondReplacement = game.unit("unit-footman", 71, 61);
        List<Unit> selected = List.of(peasant, mage, footman, balloon);
        int[][] home = {{66, 68}, {65, 65}, {71, 62}, {78, 108}};
        int[][] away = {{66, 64}, {69, 69}, {67, 66}, {78, 104}};
        Set<Missile> observed = new LinkedHashSet<>();
        List<Integer> shots = new ArrayList<>();
        // Authenticated campaign-xhuman-04 slot 1506; hardware watches
        // seal 0x40b369's 1 -> 66 write at 103 and 0x40b106's timer 63
        // at 106. The same unmodified fixture fires on 48, 179 and 244.
        for (int cycle = 1; cycle <= 244; cycle++) {
            applyProfile(game, selected, home, away, cycle);
            game.mission.tick();
            for (Missile missile : game.mission.world().missiles()) {
                if (missile.source() == axe
                        && game.mission.world().savedProjectileStartCycle(missile) >= 0
                        && observed.add(missile)) shots.add(cycle);
            }
            if (cycle == 103) {
                assertSame(firstReplacement, axe.target(),
                        "the completed shot must acquire the native replacement quarry");
                assertEquals(66, axe.battleNetRangedAttackCadenceRemaining(),
                        "a completed shot must restart reload even with one old count left");
            }
            if (cycle == 106) {
                assertSame(secondReplacement, axe.target(),
                        "a second target change must take effect during reload");
                assertEquals(63, axe.battleNetAnimationTimer(),
                        "the second target must inherit the unspent reload immediately");
            }
            if (cycle == 234) assertEquals(66, axe.battleNetRangedAttackCadenceRemaining(),
                    "keeping the same quarry must still restart the next reload");
        }
        assertEquals(List.of(48, 179, 244), shots,
                "target changes must preserve the native repeated firing visits");
    }

    @Test
    @DisplayName("an attack-move acquisition starts pursuit after three construction visits on landing")
    void anAttackMoveAcquisitionStartsPursuitAfterThreeConstructionVisitsOnLanding() {
        Campaign game = new Campaign("campaigns/human-exp/levelx04h");
        Unit peasant = game.unit("unit-peasant", 66, 68);
        Unit mage = game.unit("unit-mage", 65, 65);
        Unit footman = game.unit("unit-footman", 71, 62);
        Unit balloon = game.unit("unit-balloon", 78, 108);
        List<Unit> selected = List.of(peasant, mage, footman, balloon);
        int[][] home = {{66, 68}, {65, 65}, {71, 62}, {78, 108}};
        int[][] away = {{66, 64}, {69, 69}, {67, 66}, {78, 104}};
        // Authenticated campaign-xhuman-04 slot 1484 drains its opening
        // pursuit at 133. Attack 2539/3,2,1 precedes the next east stride
        // at 136; the player's Move at 160 then takes ownership at 168.
        for (int cycle = 1; cycle <= 187; cycle++) {
            applyProfile(game, selected, home, away, cycle);
            game.mission.tick();
            if (cycle >= 133 && cycle <= 135) {
                position(footman, 70, 63, 2240, 2016, cycle);
                assertEquals(136 - cycle, footman.battleNetAnimationTimer(),
                        "landing must not charge a fourth construction visit at " + cycle);
            }
            if (cycle == 136) position(footman, 71, 63, 2240, 2016, cycle);
            if (cycle == 152) position(footman, 72, 63, 2272, 2016, cycle);
            if (cycle == 168) {
                position(footman, 72, 63, 2304, 2016, cycle);
                assertEquals(Unit.Order.MOVE, footman.order(),
                        "the return click must replace pursuit on its native landing visit");
            }
            if (cycle == 171) position(footman, 71, 62, 2304, 2016, cycle);
            if (cycle == 187) position(footman, 71, 62, 2272, 1984, cycle);
        }
    }

    @Test
    @DisplayName("a retaliating grunt routes around enemy bodies when its quarry changes")
    void aRetaliatingGruntRoutesAroundEnemyBodiesWhenItsQuarryChanges() {
        Campaign game = new Campaign("campaigns/human-exp/levelx04h");
        Unit peasant = game.unit("unit-peasant", 66, 68);
        Unit mage = game.unit("unit-mage", 65, 65);
        Unit footman = game.unit("unit-footman", 71, 62);
        Unit balloon = game.unit("unit-balloon", 78, 108);
        Unit grunt = game.unit("unit-grunt", 77, 61, 0);
        Unit ballista = game.unit("unit-ballista", 68, 62);
        Unit arrivalTarget = game.unit("unit-footman", 72, 61);
        List<Unit> selected = List.of(peasant, mage, footman, balloon);
        int[][] home = {{66, 68}, {65, 65}, {71, 62}, {78, 108}};
        int[][] away = {{66, 64}, {69, 69}, {67, 66}, {78, 104}};
        // Authenticated campaign-xhuman-04, fixture
        // 96468cc0d8e05f0e1ce83fd7adae1394ec736157cf87cda3e9c4842d53a4777c.
        // The native 0x44fc4d branch retains enemy occupancy while the
        // ballista's hit offer is live. The writer stores SW,S,SW,SW,NW,N
        // at 105, before Move consumes that route's first southwest byte.
        for (int cycle = 1; cycle <= 180; cycle++) {
            applyProfile(game, selected, home, away, cycle);
            game.mission.tick();
            if (cycle >= 105 && cycle <= 155) assertSame(ballista, grunt.target(),
                    "the retaliation route must keep the selected ballista at " + cycle);
            if (cycle == 105) position(grunt, 74, 60, 2400, 1888, cycle);
            if (cycle == 121) position(grunt, 74, 60, 2368, 1920, cycle);
            if (cycle == 124) position(grunt, 74, 61, 2368, 1920, cycle);
            if (cycle == 140) position(grunt, 73, 62, 2368, 1952, cycle);
            if (cycle >= 156) {
                position(grunt, 73, 62, 2336, 1984, cycle);
                assertSame(arrivalTarget, grunt.target(),
                        "arrival must select the reachable footman beside the native route at " + cycle);
            }
        }
    }

    @Test
    @DisplayName("a death knight's move uses the native footfalls through repeated command replacements")
    void aDeathKnightsMoveUsesTheNativeFootfallsThroughRepeatedCommandReplacements() {
        Campaign game = new Campaign("campaigns/orc-exp/levelx01o");
        Unit ogre = game.unit("unit-ogre", 89, 92);
        Unit caster = game.unit("unit-death-knight", 90, 90);
        List<Unit> selected = List.of(ogre, caster);
        int[][] home = {{89, 92}, {90, 90}};
        int[][] away = {{89, 88}, {86, 90}};
        // Authenticated campaign-xorc-01, fixture
        // 477a93c96c32c0eabdc489c0fcfb3c2493083b9de2842a88e6eaf7f012bc6fdd.
        // Native slot 1491 uses the 18-visit Move body even when a plain
        // Move owns it. The replacement clicks must land on those pixels.
        for (int cycle = 1; cycle <= 179; cycle++) {
            applyProfile(game, selected, home, away, cycle);
            game.mission.tick();
            if (cycle == 43) position(caster, 89, 91, 2848, 2899, cycle);
            if (cycle == 51) position(caster, 88, 92, 2848, 2912, cycle);
            if (cycle == 69) position(caster, 87, 91, 2816, 2944, cycle);
            if (cycle == 110) position(caster, 88, 89, 2787, 2877, cycle);
            if (cycle == 126) {
                position(caster, 88, 89, 2816, 2848, cycle);
                assertEquals(Unit.Order.ATTACK_MOVE, caster.order(),
                        "the newest march must take ownership when the old Move lands");
            }
            if (cycle == 147) position(caster, 86, 90, 2784, 2880, cycle);
            if (cycle == 165) {
                position(caster, 86, 90, 2752, 2880, cycle);
                assertEquals(Unit.Order.MOVE, caster.order(),
                        "the return click must own the completed march stride");
            }
            if (cycle == 179) position(caster, 87, 89, 2771, 2861, cycle);
        }
    }

    @Test
    @DisplayName("a siege engine keeps the clicked building until its pursuit fails and then acquires on the same visit")
    void aSiegeEngineKeepsTheClickedBuildingUntilItsPursuitFailsThenAcquiresImmediately() {
        Campaign game = new Campaign("campaigns/human-exp/levelx04h");
        Unit ballista = game.unit("unit-ballista", 68, 62);
        Unit hall = game.unit("unit-great-hall", 75, 114, 6);
        Unit grunt = game.unit("unit-grunt", 77, 61, 0);
        for (int cycle = 1; cycle <= 240; cycle++) {
            if (cycle == 5) assertTrue(game.commands.apply(GameCommand.attack(
                    ballista.player(), ballista.id(), hall.id())),
                    "the building click must reach the command dispatcher");
            game.mission.tick();
            // UI fixture afb1f39311ef857ec3275ae79e07bf06aa6492d44b978ee07de964f869ce0600.
            // The native watchpoint at 0x453097 proves 9 -> 2 at 233;
            // 0x452573 dispatches Still before 0x452fa2 promotes weak Attack 16.
            if (cycle >= 6 && cycle <= 232) assertSame(hall, ballista.target(),
                    "a nearby hostile must not replace the active building click at " + cycle);
            if (cycle == 201) position(ballista, 69, 69, 2208, 2176, cycle);
            if (cycle >= 233) position(ballista, 69, 69, 2208, 2208, cycle);
            if ((cycle >= 233 && cycle <= 235) || cycle >= 239) {
                assertSame(grunt, ballista.target(),
                        "the failed pursuit must release the building before idle acquires at " + cycle);
                assertTrue(ballista.battleNetStationaryAttack(),
                        "idle acquisition must own the stationary attack, not the player's old pursuit");
            }
            if (cycle >= 236 && cycle <= 238) assertEquals(Unit.Order.STILL, ballista.order(),
                    "an out-of-range stationary target must return to idle at " + cycle);
        }
    }

    @Test
    @DisplayName("a ranged attack keeps its paid firing startup after a refused pursuit")
    void aRangedAttackKeepsItsPaidFiringStartupAfterARefusedPursuit() {
        Campaign game = new Campaign("campaigns/human/level13h");
        Unit knight = game.unit("unit-knight", 63, 117);
        Unit peasant = game.unit("unit-peasant", 52, 115);
        Unit footman = game.unit("unit-footman", 54, 116);
        Unit gryphon = game.unit("unit-gryphon-rider", 56, 108);
        Unit axe = game.unit("unit-axethrower", 118, 24, 0);
        List<Unit> selected = List.of(knight, peasant, footman, gryphon);
        int[][] home = {{63, 117}, {52, 115}, {54, 116}, {56, 108}};
        int[][] away = {{63, 113}, {56, 115}, {50, 120}, {56, 104}};
        Set<Missile> observed = new LinkedHashSet<>();
        List<Integer> shots = new ArrayList<>();
        for (int cycle = 1; cycle <= 190; cycle++) {
            applyProfile(game, selected, home, away, cycle);
            game.mission.tick();
            for (Missile missile : game.mission.world().missiles()) {
                if (missile.source() == axe
                        && game.mission.world().savedProjectileStartCycle(missile) >= 0
                        && observed.add(missile)) shots.add(cycle);
            }
            if (cycle >= 152 && cycle <= 157) position(axe, 119, 25, 3808, 800, cycle);
            if (cycle == 158) position(axe, 120, 26, 3808, 800, cycle);
            if (cycle >= 174) position(axe, 120, 26, 3840, 832, cycle);
        }
        // Authenticated campaign-human-13 slot 1506 creates these three
        // projectiles. Its six refused wakes at 152..157 do not charge a
        // second constructor after the next committed stride lands at 174.
        assertEquals(List.of(13, 78, 184), shots,
                "the refused pursuit must preserve the native firing visits");
    }

    @Test
    @DisplayName("a mage's attack-move applies the native caster target restrictions")
    void aMagesAttackMoveAppliesTheNativeCasterTargetRestrictions() {
        Campaign game = new Campaign("campaigns/human-exp/levelx04h");
        Unit peasant = game.unit("unit-peasant", 66, 68);
        Unit mage = game.unit("unit-mage", 65, 65);
        Unit footman = game.unit("unit-footman", 71, 62);
        Unit balloon = game.unit("unit-balloon", 78, 108);
        List<Unit> selected = List.of(peasant, mage, footman, balloon);
        int[][] home = {{66, 68}, {65, 65}, {71, 62}, {78, 108}};
        int[][] away = {{66, 64}, {69, 69}, {67, 66}, {78, 104}};
        for (int cycle = 1; cycle <= 170; cycle++) {
            applyProfile(game, selected, home, away, cycle);
            game.mission.tick();
            // The 0x40a972 caster arm rejects the winning armed grunt at
            // 73,60. Native slot 1478 therefore keeps marching southeast
            // at 126 instead of chasing northeast and abandoning the click.
            if (cycle == 126) position(mage, 68, 67, 2144, 2112, cycle);
            if (cycle == 144) position(mage, 69, 68, 2176, 2144, cycle);
            if (cycle == 162) {
                position(mage, 69, 68, 2208, 2176, cycle);
                assertEquals(Unit.Order.MOVE, mage.order(),
                        "the return click must replace the completed march stride");
            }
            if (cycle == 165) position(mage, 68, 67, 2208, 2176, cycle);
            if (cycle == 170) position(mage, 68, 67, 2199, 2167, cycle);
        }
    }

    @Test
    @DisplayName("a ranged retarget completes its stride and arrival before starting a fresh firing delay")
    void aRangedRetargetCompletesItsStrideAndArrivalBeforeStartingAFreshFiringDelay() {
        Campaign game = new Campaign("campaigns/human-exp/levelx04h");
        Unit peasant = game.unit("unit-peasant", 66, 68);
        Unit mage = game.unit("unit-mage", 65, 65);
        Unit footman = game.unit("unit-footman", 71, 62);
        Unit balloon = game.unit("unit-balloon", 78, 108);
        Unit axe = game.unit("unit-axethrower", 78, 62, 0);
        Unit pursuitTarget = game.unit("unit-footman", 71, 60);
        Unit arrivalTarget = game.unit("unit-footman", 71, 61);
        List<Unit> selected = List.of(peasant, mage, footman, balloon);
        int[][] home = {{66, 68}, {65, 65}, {71, 62}, {78, 108}};
        int[][] away = {{66, 64}, {69, 69}, {67, 66}, {78, 104}};
        int firstShot = -1;
        for (int cycle = 1; cycle <= 183; cycle++) {
            applyProfile(game, selected, home, away, cycle);
            game.mission.tick();
            // Authenticated campaign-xhuman-04, native slot 1490: a new
            // target is queued at 72, takes ownership at 88, and changes
            // again when the next stride lands at 107. The first axe is
            // created at 183. An early shot at 118 shifted every later roll.
            if (cycle >= 72 && cycle <= 106) assertSame(pursuitTarget, axe.target(),
                    "the queued pursuit must retain its quarry at " + cycle);
            if (cycle == 88) position(axe, 77, 60, 2464, 1920, cycle);
            if (cycle == 91) position(axe, 76, 59, 2464, 1920, cycle);
            if (cycle >= 107) {
                position(axe, 76, 59, 2432, 1888, cycle);
                assertSame(arrivalTarget, axe.target(),
                        "the arrival scan must select the new quarry on the landing visit");
            }
            if (firstShot < 0 && game.mission.world().missiles().stream()
                    .anyMatch(missile -> missile.source() == axe)) firstShot = cycle;
        }
        assertEquals(183, firstShot, "retargeting must preserve the native first firing delay");
    }

    @Test
    @DisplayName("a submarine honors replacements through acquisition, weapon cooldown and arrival")
    void aSubmarineHonorsReplacementsThroughAcquisitionWeaponCooldownAndArrival() {
        Campaign game = new Campaign("campaigns/human-exp/levelx07h");
        Unit paladin = game.unit("unit-paladin", 12, 106);
        Unit submarine = game.unit("unit-human-submarine", 84, 116);
        Unit destroyer = game.unit("unit-human-destroyer", 24, 114);
        List<Unit> selected = List.of(paladin, submarine, destroyer);
        int[][] home = {{12, 106}, {84, 116}, {24, 114}};
        int[][] away = {{12, 102}, {84, 112}, {28, 114}};
        for (int cycle = 1; cycle <= 260; cycle++) {
            applyProfile(game, selected, home, away, cycle);
            game.mission.tick();

            // command-campaign's authenticated campaign-xhuman-07 capture,
            // seed one: acquisition commits south at 9 before queued Attack.
            // Patrol replaces that stride at 52. The later Attack-Move takes
            // ownership at 148 while the old weapon cooldown keeps expiring.
            if (cycle >= 6 && cycle <= 8) {
                position(submarine, 84, 116, 2688, 3712, cycle);
                assertEquals(Unit.Order.ATTACK_MOVE, submarine.order(),
                        "acquisition must not invent a firing body before the march starts");
            }
            if (cycle == 9) position(submarine, 84, 118, 2688, 3712, cycle);
            if (cycle == 51) position(submarine, 84, 118, 2688, 3774, cycle);
            if (cycle == 52) {
                position(submarine, 84, 118, 2688, 3776, cycle);
                assertEquals(Unit.Order.PATROL, submarine.order(),
                        "the most recent click must replace the completed stride on arrival");
            }
            if (cycle == 148) assertEquals(Unit.Order.ATTACK_MOVE, submarine.order(),
                    "the replacement march must own the completed attack callback");
            if (cycle == 160) assertEquals(Unit.Order.MOVE, submarine.order(),
                    "changing orders must not pause weapon cooldown and delay the next Move");
            if (cycle == 163) position(submarine, 84, 116, 2688, 3776, cycle);
            if (cycle == 205) position(submarine, 84, 116, 2688, 3714, cycle);
            if (cycle == 206) {
                position(submarine, 84, 116, 2688, 3712, cycle);
                assertEquals(Unit.Order.STILL, submarine.order(),
                        "finishing the last pixels must finish the player's Move on that visit");
            }
            if (cycle >= 257 && cycle <= 259) {
                assertEquals(Unit.Order.STILL, paladin.order(), "Stop must replace an idle order too");
                assertEquals(260 - cycle, paladin.battleNetAnimationTimer(),
                        "Stop must construct fresh Still instead of running the old idle callback");
            }
        }
    }

    @Test
    @DisplayName("a point march refills its route and a worker can receive a move to its own tile")
    void aPointMarchRefillsItsRouteAndAWorkerCanReceiveAMoveToItsOwnTile() {
        Campaign game = new Campaign("campaigns/human/level07h");
        Unit footman = game.unit("unit-footman", 12, 4);
        Unit knight = game.unit("unit-knight", 9, 5);
        Unit peasant = game.unit("unit-peasant", 17, 6);
        Unit transport = game.unit("unit-human-transport", 20, 6);
        List<Unit> selected = List.of(footman, knight, peasant, transport);
        int[][] home = {{12, 4}, {9, 5}, {17, 6}, {20, 6}};
        int[][] away = {{12, 0}, {13, 1}, {13, 6}, {20, 2}};
        for (int cycle = 1; cycle <= 150; cycle++) {
            applyProfile(game, selected, home, away, cycle);
            game.mission.tick();
            if (cycle == 79) assertEquals(Unit.Order.MOVE, peasant.order(),
                    "clicking the worker's own tile must install the replacement Move");
            if (cycle == 82) {
                assertEquals(Unit.Order.STILL, peasant.order(), "the zero-distance Move must complete on its callback");
                assertEquals(3, peasant.battleNetAnimationTimer(), "the completed Move must construct fresh Still");
            }
            if (cycle == 131) position(footman, 12, 0, 352, 32, cycle);
            if (cycle == 147) {
                position(footman, 12, 0, 384, 0, cycle);
                assertEquals(Unit.Order.STILL, footman.order(), "the march must finish at the clicked tile");
            }
        }
    }

    @Test
    @DisplayName("a gryphon keeps the native startup waits through stop, patrol and attack-move")
    void aGryphonKeepsTheNativeStartupWaitsThroughStopPatrolAndAttackMove() {
        Campaign game = new Campaign("campaigns/human/level13h");
        Unit knight = game.unit("unit-knight", 63, 117);
        Unit peasant = game.unit("unit-peasant", 52, 115);
        Unit footman = game.unit("unit-footman", 54, 116);
        Unit gryphon = game.unit("unit-gryphon-rider", 56, 108);
        List<Unit> selected = List.of(knight, peasant, footman, gryphon);
        int[][] home = {{63, 117}, {52, 115}, {54, 116}, {56, 108}};
        int[][] away = {{63, 113}, {56, 115}, {50, 120}, {56, 104}};
        for (int cycle = 1; cycle <= 155; cycle++) {
            applyProfile(game, selected, home, away, cycle);
            game.mission.tick();
            if (cycle <= 34) position(gryphon, 56, 108, 1792, 3456, cycle);
            if (cycle == 19) assertEquals(Unit.Order.STILL, gryphon.order(),
                    "Stop must supersede the opening march on its native callback");
            if (cycle == 35) position(gryphon, 56, 106, 1792, 3456, cycle);
            if (cycle == 59) position(gryphon, 56, 104, 1792, 3392, cycle);
            if (cycle == 91) position(gryphon, 56, 106, 1792, 3328, cycle);
            if (cycle == 115) assertEquals(Unit.Order.ATTACK_MOVE, gryphon.order(),
                    "the replacement march must take ownership when the old stride lands");
            if (cycle == 123) position(gryphon, 56, 104, 1792, 3392, cycle);
            if (cycle == 147) {
                position(gryphon, 56, 104, 1792, 3328, cycle);
                assertEquals(Unit.Order.STILL, gryphon.order(), "the gryphon must finish the march when it lands");
            }
        }
    }

    @Test
    @DisplayName("stop followed by move preserves a blocked peon's committed refusal wait")
    void stopFollowedByMovePreservesABlockedPeonsCommittedRefusalWait() {
        Campaign game = new Campaign("campaigns/orc/level12o");
        Unit catapult = game.unit("unit-catapult", 12, 125);
        Unit axe = game.unit("unit-axethrower", 15, 124);
        Unit peon = game.unit("unit-peon", 15, 122);
        Unit zeppelin = game.unit("unit-zeppelin", 24, 124);
        List<Unit> selected = List.of(catapult, axe, peon, zeppelin);
        int[][] home = {{12, 125}, {15, 124}, {15, 122}, {24, 124}};
        int[][] away = {{12, 121}, {19, 120}, {19, 126}, {24, 120}};
        for (int cycle = 1; cycle <= 31; cycle++) {
            applyProfile(game, selected, home, away, cycle);
            game.mission.tick();
            if (cycle >= 19 && cycle <= 26) {
                assertEquals(Unit.Order.ATTACK_MOVE, peon.order(),
                        "replacement commands must wait for the blocked movement callback at " + cycle);
                position(peon, 15, 122, 480, 3904, cycle);
            }
            if (cycle == 27) assertEquals(Unit.Order.MOVE, peon.order(),
                    "the most recent Move must replace Stop when the refusal wait expires");
            if (cycle == 30) position(peon, 16, 122, 480, 3904, cycle);
            if (cycle == 31) position(peon, 16, 122, 483, 3904, cycle);
        }
    }

    private static void applyProfile(Campaign game, List<Unit> selected,
            int[][] home, int[][] away, int cycle) {
        for (int index = 0; index < selected.size(); index++) {
            Unit unit = selected.get(index);
            int[] point = cycle == 75 || cycle == 160 ? home[index] : away[index];
            boolean attacks = unit.type().canAttack();
            // These command-campaign recipes use Move for workers and
            // casters at 20. Spellcasters are still armed for Attack-Move.
            boolean patrols = attacks && unit.type().gathering().isEmpty()
                    && !"unit-mage".equals(unit.type().ident())
                    && !"unit-death-knight".equals(unit.type().ident())
                    && !"unit-evil-knight".equals(unit.type().ident());
            GameCommand command = switch (cycle) {
                case 5, 75, 160 -> GameCommand.move(game.person, unit.id(), point[0], point[1]);
                case 6, 111 -> attacks ? GameCommand.attackMove(game.person, unit.id(), point[0], point[1])
                        : GameCommand.move(game.person, unit.id(), point[0], point[1]);
                case 19, 110, 255 -> GameCommand.stop(game.person, unit.id());
                case 20 -> patrols ? GameCommand.patrol(game.person, unit.id(), point[0], point[1])
                        : GameCommand.move(game.person, unit.id(), point[0], point[1]);
                default -> null;
            };
            if (command != null) game.apply(command);
        }
    }

    @Test
    @DisplayName("a blocked opening march stays finished after movement returns to idle")
    void aBlockedOpeningMarchStaysFinishedAfterMovementReturnsToIdle() {
        Campaign game = new Campaign("campaigns/human/level07h");
        Unit peasant = game.unit("unit-peasant", 17, 6);
        for (int cycle = 1; cycle <= 18; cycle++) {
            if (cycle == 5) game.apply(GameCommand.move(game.person, peasant.id(), 13, 6));
            if (cycle == 6) game.apply(GameCommand.attackMove(game.person, peasant.id(), 13, 6));
            game.mission.tick();
            position(peasant, 17, 6, 544, 192, cycle);
            // Native campaign-human-07 slot 1577 returns action 10 to Still
            // at fixture 9. The borrowed Move's terminal result is final.
            if (cycle >= 9) assertEquals(Unit.Order.STILL, peasant.order(),
                    "a failed movement result must not resurrect the finished Attack-Move at " + cycle);
        }
    }

    private static void position(Unit unit, int x, int y, int px, int py, int cycle) {
        assertEquals(List.of(x, y, px, py), List.of(unit.tileX(), unit.tileY(), unit.pixelX(), unit.pixelY()),
                "the selected unit must occupy retail's tile and pixels at " + cycle);
    }

    private static final class Campaign {
        final Mission mission;
        final CommandApplier commands;
        final int person;

        Campaign(String map) {
            AssetSource source = AssetSource.fromEnvironment();
            Assumptions.assumeTrue(source != null, "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
            GameData data = new GameData(source);
            person = GameData.personIn(data.campaignMap(map));
            mission = data.loadMission(map, person, 1);
            commands = new CommandApplier(mission.world(), new ArrayList<>(data.unitTypes().types().values()));
            data.configureCommands(commands);
            mission.tick();
            mission.tick();
        }

        Unit unit(String ident, int x, int y) {
            return unit(ident, x, y, person);
        }

        Unit unit(String ident, int x, int y, int player) {
            List<Unit> matches = mission.world().units().stream().filter(unit -> unit.player() == player
                    && unit.type().ident().equals(ident) && unit.tileX() == x && unit.tileY() == y).toList();
            assertEquals(1, matches.size(), "the campaign must contain the selected " + ident);
            return matches.getFirst();
        }

        void apply(GameCommand command) {
            assertTrue(commands.apply(command), "the legal player command must be accepted");
        }
    }
}
