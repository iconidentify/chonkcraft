package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * XHuman 10's cycle-52 and cycle-54 HP drops on the orc grunt that
 * opened at 78,93.
 *
 * <p>Authenticated campaign-1800 fixture {@code retail-xhuman-10-idle}:
 * grunt 1471 starts Still at 78,93 and arrives on 81,90 at fixture 41.
 * Archer 1470 at 84,94 waits through fixture 25 and opens on its own Still
 * marker at 26; its type-15 arrow frees at fixture 52
 * (60 to 54). Footman 1479 on 82,91
 * is already on stationary Attack from the arrival and lands opcode ten
 * at fixture 54 (54 to 46). The case's coarse first divergence after the
 * route-ownership wave is this second blow at cycle 54.
 */
class Xhuman10DamageTimingRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("xhuman 10's knight can hit again after chasing a new target")
    void xhuman10KnightClearsItsLandedLatchForTheNextChase() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Authenticated native slot 1493 / Java 107 lands its prior swing on
        // grunt 1475, then follows replacement grunt 1477 west and opens a
        // fresh Attack body.  The old body's duplicate-hit latch must not cross
        // that chase: native 1485 hits for nine and 1493 immediately follows
        // for ten on fixture 154, taking the target from 29 to 10 HP.
        Unit knight = unitById(world, 107);
        Unit grunt = unitById(world, 123);
        assertNotNull(knight, "XHuman 10 has no arriving knight 107");
        assertNotNull(grunt, "XHuman 10 has no replacement grunt 123");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 153) {
            mission.tick();
        }

        assertSame(grunt, knight.target());
        assertEquals(29, grunt.hitPoints());
        assertEquals(1935, knight.battleNetSequenceOffset());
        assertEquals(1, knight.battleNetAnimationTimer());
        assertTrue(!knight.battleNetSequenceMeleeLanded(),
                "the prior target's landed latch must be clear for this Attack body");

        mission.tick();
        assertEquals(10, grunt.hitPoints(),
                "both authenticated knight blows land on fixture 154");
        assertEquals(1941, knight.battleNetSequenceOffset());
        assertEquals(5, knight.battleNetAnimationTimer());
        assertTrue(knight.battleNetSequenceMeleeLanded(),
                "the new body's OP10 now owns the duplicate-hit latch");
    }

    @Test
    @DisplayName("xhuman 10's boxed defender retries and releases its unreachable target")
    void xhuman10BoxedDefenderRetriesAndReleasesItsUnreachableTarget() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Authenticated native slot 1489 / Java 111. After its dying quarry's
        // Attack tail names slot 1477, BNE keeps the blocked north-west route
        // byte and revisits Move OP0 on six consecutive scheduler calls. Each
        // refusal advances both the collision nibble and AutoSelectTarget's
        // six-beat clock. The sixth scan proves that every visible hostile is
        // unreachable and releases the weak computer-owned attack to Still.
        Unit knight = unitAt(world, "unit-knight", 84, 89);
        Unit replacement = unitById(world, 123);
        Unit arrivingKnight = unitById(world, 107);
        Unit retargetingOgre = unitById(world, 62);
        Unit southernGuardTower = unitById(world, 63);
        assertNotNull(knight, "XHuman 10 has no boxed eastern knight");
        assertNotNull(replacement, "XHuman 10 has no replacement grunt");
        assertNotNull(arrivingKnight, "XHuman 10 has no western residual knight");
        assertNotNull(retargetingOgre, "XHuman 10 has no southern retargeting ogre");
        assertNotNull(southernGuardTower, "XHuman 10 has no southern guard tower");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 145) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture >= 136 && fixture <= 138) {
                assertEquals(Unit.Order.ATTACK, knight.order());
                assertSame(replacement, knight.target());
                assertEquals(1922, knight.battleNetSequenceOffset());
                assertEquals(139 - fixture,
                        knight.battleNetAnimationTimer(),
                        "native Attack construction counts 3,2,1");
                if (fixture == 138) {
                    assertEquals(Direction.fromDelta(-1, -1), knight.heading(),
                            "the finished swing leaves the native north-west face");
                    assertEquals(643, retargetingOgre.battleNetSequenceOffset());
                    assertEquals(23,
                            retargetingOgre.battleNetAnimationTimer(),
                            "an in-range building retarget enters BNE's melee OP0 hold");
                }
            } else if (fixture >= 139 && fixture <= 144) {
                assertEquals(83, knight.tileX());
                assertEquals(89, knight.tileY());
                assertEquals(Unit.Order.ATTACK, knight.order());
                assertSame(replacement, knight.target());
                assertEquals(1874, knight.battleNetSequenceOffset(),
                        "the boxed chase remains on native Move OP0");
                assertEquals(1, knight.battleNetAnimationTimer());
                assertEquals(fixture - 138,
                        knight.battleNetCollisionCounter(),
                        "every native refusal advances the collision nibble");
                assertEquals(643, retargetingOgre.battleNetSequenceOffset());
                assertEquals(161 - fixture,
                        retargetingOgre.battleNetAnimationTimer(),
                        "the building retarget must not enter its damage opcode early");
                if (fixture == 144) {
                    assertEquals(25,
                            arrivingKnight.battleNetMeleeSyncRemaining(),
                            "the settled residual refreshes table 0x27 on BNE's "
                                    + "arrival callback");
                    assertEquals(0xe4880eeb, world.randomSeed(),
                            "fixture 144 consumes all three authenticated "
                                    + "0x4234CD draws");
                }
            }
        }

        assertEquals(83, knight.tileX());
        assertEquals(89, knight.tileY());
        assertEquals(Unit.Order.STILL, knight.order(),
                "the sixth reachability scan releases the boxed defender");
        assertNull(knight.target());
        assertEquals(1869, knight.battleNetSequenceOffset());
        assertEquals(1, knight.battleNetAnimationTimer());
        assertEquals(0x20873cbc, world.battleNetRandomSeed(),
                "the Attack-to-Still handoff pays native's same-visit active-order "
                        + "idle draw before the later idle and projectile callbacks");
        assertEquals(130, southernGuardTower.hitPoints(),
                "BNE has not damaged the southern tower by fixture 145");
        assertEquals(16, retargetingOgre.battleNetAnimationTimer());
    }

    @Test
    @DisplayName("xhuman 10's struck knight recruits its adjacent idle brother")
    void xhuman10DirectHitRecruitsTheAdjacentIdleKnight() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Authenticated native slots 1489 / 1477, Java ids 111 / 123.
        // Grunt 1477 hits knight 1493 on fixture 176. Retail 0x40a9d0's
        // person two-tile loop selects idle knight 1489 and writes that grunt
        // as its target. Attack remains visible for construction 3,2,1; the
        // grunt then dies on fixture 179 and the helper returns to Still.
        Unit knight = unitById(world, 111);
        Unit grunt = unitById(world, 123);
        Unit struckKnight = unitById(world, 107);
        assertNotNull(knight, "XHuman 10 has no native-slot-1489 knight");
        assertNotNull(grunt, "XHuman 10 has no native-slot-1477 grunt");
        assertNotNull(struckKnight, "XHuman 10 has no native-slot-1493 knight");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 175) {
            mission.tick();
        }

        assertEquals(Unit.Order.STILL, knight.order());
        assertNull(knight.target());
        assertNull(knight.battleNetPendingHelpAttack());
        assertSame(grunt, struckKnight.target());
        assertEquals(83, knight.tileX());
        assertEquals(89, knight.tileY());
        assertEquals(81, grunt.tileX());
        assertEquals(89, grunt.tileY());
        assertEquals(5, grunt.hitPoints());

        mission.tick();
        assertEquals(Unit.Order.ATTACK, knight.order());
        assertSame(grunt, knight.target());
        assertEquals(1922, knight.battleNetSequenceOffset());
        assertEquals(3, knight.battleNetAnimationTimer());
        mission.tick();
        assertEquals(2, knight.battleNetAnimationTimer());
        mission.tick();
        assertEquals(1, knight.battleNetAnimationTimer());
        assertEquals(0x2d83452a, world.battleNetRandomSeed(),
                "native async stream is aligned before the helper's OP0 handoff");
        mission.tick();
        assertEquals(Unit.Order.STILL, knight.order());
        assertNull(knight.target());
        assertEquals(1869, knight.battleNetSequenceOffset());
        assertEquals(1, knight.battleNetAnimationTimer());
        assertEquals(0xe933771a, world.battleNetRandomSeed(),
                "fresh Still dispatch owns its native same-visit idle draw");
        assertEquals(83, knight.tileX(), "construction must not move the helper");
        assertEquals(89, knight.tileY(), "construction must not move the helper");
        assertEquals(Unit.Order.DYING, grunt.order());
    }

    @Test
    @DisplayName("xhuman 10's queued helper drops a quarry killed by the landing arrow")
    void xhuman10QueuedHitHelpDropsQuarryKilledByCycleEndArrow() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Authenticated native slots 1489 / 1471, Java ids 111 / 129. The
        // knight accepts the grunt's HitUnit offer and pays Attack construction
        // on fixtures 200..202. On fixture 203 an already-landed arrow from
        // guard tower 1468 frees and kills the offered grunt. Retail's unit
        // visit observes that owed projectile free before spending the queued
        // helper handoff, clears the target and exposes fresh Still@1869/1.
        Unit knight = unitById(world, 111);
        Unit grunt = unitById(world, 129);
        Unit laterVictim = unitById(world, 107);
        assertNotNull(knight, "XHuman 10 has no native-slot-1489 knight");
        assertNotNull(grunt, "XHuman 10 has no native-slot-1471 grunt");
        assertNotNull(laterVictim,
                "XHuman 10 has no native-slot-1493 later projectile victim");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 199) {
            mission.tick();
        }

        mission.tick();
        assertEquals(Unit.Order.ATTACK, knight.order());
        assertSame(grunt, knight.target());
        assertEquals(83, knight.tileX());
        assertEquals(89, knight.tileY());
        assertEquals(1922, knight.battleNetSequenceOffset());
        assertEquals(3, knight.battleNetAnimationTimer());
        assertEquals(11, grunt.hitPoints());
        mission.tick();
        assertEquals(2, knight.battleNetAnimationTimer());
        mission.tick();
        assertEquals(1, knight.battleNetAnimationTimer());
        mission.tick();
        assertEquals(Unit.Order.STILL, knight.order());
        assertNull(knight.target());
        assertEquals(83, knight.tileX(),
                "the lethal projectile free must preempt the helper's route step");
        assertEquals(89, knight.tileY(),
                "the lethal projectile free must preempt the helper's route step");
        assertEquals(1869, knight.battleNetSequenceOffset());
        assertEquals(1, knight.battleNetAnimationTimer());
        assertEquals(Unit.Order.DYING, grunt.order());
        assertEquals(0x20c9b54f, world.battleNetRandomSeed(),
                "fresh Still dispatch must own the native same-visit idle draw");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 228) {
            mission.tick();
        }
        assertEquals(70, laterVictim.hitPoints(),
                "fixture 203's draw must feed the native five-point axe impact");
        assertEquals(0xb45bf651, world.randomSeed(),
                "the fixture-228 impact must retain the authenticated sync seed");
    }

    @Test
    @DisplayName("xhuman 10's residual park owns refusal one and Attack recovery")
    void xhuman10ResidualParkCountsItsRefusalBeforeThePaidRecovery() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Authenticated native slot 1475 / Java id 125. Its northwest pixel
        // residual settles on fixture 182 with the next east byte parked
        // at route index 20. FUN_004379e0 owns that same visit, raising the
        // sticky refusal nibble to one. Refusals two through seven follow on
        // fixtures 183..188; eight installs Move 15 on 189. After the complete
        // band, the attack chase clears the nibble and opens Attack 3 on 204
        // without taking the newly available southeast route.
        Unit grunt = unitById(world, 125);
        assertNotNull(grunt, "XHuman 10 has no native-slot-1475 grunt");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 181) {
            mission.tick();
        }

        for (int fixture = 182; fixture <= 188; fixture++) {
            mission.tick();
            assertEquals(80, grunt.tileX());
            assertEquals(89, grunt.tileY());
            assertEquals(2482, grunt.battleNetSequenceOffset(),
                    "fixture " + fixture + " parks at native Move start");
            assertEquals(1, grunt.battleNetAnimationTimer(),
                    "fixture " + fixture + " owns a one-visit refusal");
            assertEquals(fixture - 181, grunt.battleNetRefusals(),
                    "the settle visit itself is refusal one");
        }
        mission.tick();
        assertEquals(2482, grunt.battleNetSequenceOffset());
        assertEquals(15, grunt.battleNetAnimationTimer());
        assertEquals(8, grunt.battleNetRefusals());
        for (int fixture = 190; fixture <= 203; fixture++) {
            mission.tick();
            assertEquals(204 - fixture, grunt.battleNetAnimationTimer(),
                    "fixture " + fixture + " drains the paid Move band");
            assertEquals(80, grunt.tileX());
            assertEquals(89, grunt.tileY());
        }
        mission.tick();
        assertEquals(Unit.Order.ATTACK, grunt.order());
        assertEquals(80, grunt.tileX());
        assertEquals(89, grunt.tileY());
        assertEquals(2539, grunt.battleNetSequenceOffset());
        assertEquals(3, grunt.battleNetAnimationTimer());
        assertEquals(0, grunt.battleNetRefusals());
        assertEquals(0, grunt.battleNetCollisionCounter());
        mission.tick();
        assertEquals(2539, grunt.battleNetSequenceOffset());
        assertEquals(2, grunt.battleNetAnimationTimer());
        mission.tick();
        assertEquals(2539, grunt.battleNetSequenceOffset());
        assertEquals(1, grunt.battleNetAnimationTimer());
        mission.tick();
        assertEquals(80, grunt.tileX(),
                "the paid recovery probe remains blocked on fixture 207");
        assertEquals(89, grunt.tileY(),
                "the paid recovery probe remains blocked on fixture 207");
        assertEquals(2539, grunt.battleNetSequenceOffset(),
                "the blocked Move probe re-arms native Attack construction");
        assertEquals(3, grunt.battleNetAnimationTimer());
    }

    @Test
    @DisplayName("xhuman 10's knight retains Attack until the dead-quarry tail scan")
    void xhuman10KnightRetainsAttackUntilTheDeadQuarryTailScan() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        Unit knight = unitAt(world, "unit-knight", 84, 89);
        // Authenticated native slot 1477 / Java action-table id 123. Its
        // 81,89 coordinate belongs to the later fixture, not mission load.
        Unit replacement = unitById(world, 123);
        assertNotNull(knight, "XHuman 10 has no eastern knight on 84,89");
        assertNotNull(replacement, "XHuman 10 has no replacement grunt on 81,89");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 136) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 133) {
                Unit dyingGrunt = knight.target();
                assertNotNull(dyingGrunt,
                        "native keeps the dying quarry banked at fixture 133");
                assertEquals("unit-grunt", dyingGrunt.type().ident(),
                        "the banked quarry is the authenticated central grunt");
                assertEquals(Unit.Order.DYING, dyingGrunt.order());
                assertEquals(Unit.Order.ATTACK, knight.order(),
                        "native keeps Attack while its script tail drains");
                assertSame(dyingGrunt, knight.target(),
                        "the dead quarry remains banked until the OP0 wrap");
                assertEquals(1945, knight.battleNetSequenceOffset());
                assertEquals(3, knight.battleNetAnimationTimer());
            }
        }

        assertEquals(Unit.Order.ATTACK, knight.order());
        assertSame(replacement, knight.target(),
                "native tail OP0 hands the knight to the next adjacent grunt");
        assertEquals(1922, knight.battleNetSequenceOffset());
        assertEquals(3, knight.battleNetAnimationTimer(),
                "the replacement opens knight Attack construction 3");
    }

    @Test
    @DisplayName("xhuman 10's adjacent tail retarget keeps the melee sync clock live")
    void xhuman10AdjacentTailRetargetKeepsMeleeSyncClockLive() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Authenticated native slots 1485 and 1493 / Java 115 and 107 both
        // finish their old grunt's Attack tail on fixture 194, retarget the
        // adjacent slot-1482 grunt, and debit FUN_004234b0. Their fresh
        // constructor plus committed OP0 hold then drains the same table-0x27
        // clock, so both refresh together when OP0 advances on fixture 220.
        // Java 107 used to retain an obsolete out-of-range destination arm;
        // that surrogate froze only its sync clock through the whole hold.
        Unit southernKnight = unitById(world, 115);
        Unit northernKnight = unitById(world, 107);
        Unit grunt = unitById(world, 118);
        assertNotNull(southernKnight, "XHuman 10 has no native-slot-1485 knight");
        assertNotNull(northernKnight, "XHuman 10 has no native-slot-1493 knight");
        assertNotNull(grunt, "XHuman 10 has no native-slot-1482 grunt");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 194) {
            mission.tick();
        }

        for (Unit knight : new Unit[] {southernKnight, northernKnight}) {
            assertSame(grunt, knight.target());
            assertEquals(1922, knight.battleNetSequenceOffset());
            assertEquals(3, knight.battleNetAnimationTimer());
            assertEquals(25, knight.battleNetMeleeSyncRemaining());
            assertTrue(!knight.battleNetAttackWrapDestArmPending(),
                    "an adjacent tail replacement has no destination arm left");
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 219) {
            mission.tick();
        }
        for (Unit knight : new Unit[] {southernKnight, northernKnight}) {
            assertEquals(1922, knight.battleNetSequenceOffset());
            assertEquals(1, knight.battleNetAnimationTimer());
            assertEquals(1, knight.battleNetMeleeSyncRemaining(),
                    "the refresh is due on the next native action visit");
        }
        assertEquals(0x102f11d5, world.randomSeed());

        mission.tick();
        for (Unit knight : new Unit[] {southernKnight, northernKnight}) {
            assertEquals(1923, knight.battleNetSequenceOffset());
            assertEquals(1, knight.battleNetAnimationTimer());
            assertEquals(25, knight.battleNetMeleeSyncRemaining());
        }
        assertEquals(0x7aae88db, world.randomSeed(),
                "fixture 220 owns both authenticated knight refreshes");
    }

    @Test
    @DisplayName("xhuman 10's northern knight routes through a same-pass mover")
    void xhuman10KnightRoutesThroughASamePassAcceptedMover() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Native slots 1485/1493, Java 115/107, finish Attack construction
        // against axethrower 1496 together. Slot 1485 is visited first on
        // fixture 324 and commits SW from (82,90) with collision nibble zero.
        // Slot 1493's path writer then sees that action-three body as soft,
        // stores SW,NW,N, and collision-waits at (82,88) under Move 1874/15.
        // The dead-quarry handoff clears Java's historical refusal proxy on
        // 1485: native keeps the route generation in its collision nibble,
        // and the retired Java-only proxy must not turn the accepted mover
        // back into a wall and select the free east face.
        Unit southernKnight = unitById(world, 115);
        Unit northernKnight = unitById(world, 107);
        Unit axethrower = unitById(world, 104);
        assertNotNull(southernKnight, "XHuman 10 has no native-slot-1485 knight");
        assertNotNull(northernKnight, "XHuman 10 has no native-slot-1493 knight");
        assertNotNull(axethrower, "XHuman 10 has no native-slot-1496 axethrower");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 323) {
            mission.tick();
        }

        for (Unit knight : new Unit[] {southernKnight, northernKnight}) {
            assertSame(axethrower, knight.target());
            assertEquals(1922, knight.battleNetSequenceOffset());
            assertEquals(1, knight.battleNetAnimationTimer());
            assertEquals(0, knight.battleNetCollisionCounter());
        }
        assertEquals(0, southernKnight.battleNetRefusals(),
                "the retired Java-only refusal proxy must be cleared");

        mission.tick();
        assertEquals(81, southernKnight.tileX());
        assertEquals(89, southernKnight.tileY());
        assertEquals(32, southernKnight.offsetX());
        assertEquals(32, southernKnight.offsetY());
        Unit collisionOccupant = world.blockerOnLayer(
                northernKnight, 81, 89);
        assertSame(southernKnight, collisionOccupant,
                () -> "same-layer lookup selected "
                        + (collisionOccupant == null ? "null"
                                : collisionOccupant.id() + ":"
                                        + collisionOccupant.type().ident()));
        assertEquals(82, northernKnight.tileX(),
                "the north knight must collision-wait instead of stepping east");
        assertEquals(88, northernKnight.tileY());
        assertEquals(1874, northernKnight.battleNetSequenceOffset());
        assertEquals(15, northernKnight.battleNetAnimationTimer());
    }

    @Test
    @DisplayName("xhuman 10's opening grunt is hurt on cycle 54")
    void xhuman10sOpeningGruntIsHurtOnCycle54() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        Unit grunt = unitAt(world, "unit-grunt", 78, 93);
        Unit footman = unitAt(world, "unit-footman", 82, 91);
        Unit archer = unitAt(world, "unit-archer", 84, 94);
        assertNotNull(grunt, "XHuman 10 has no grunt on 78,93");
        assertNotNull(footman, "XHuman 10 has no footman on 82,91");
        assertNotNull(archer, "XHuman 10 has no archer on 84,94");
        int opened = grunt.hitPoints();

        // Two HandleEachCycle warmup ticks precede fixture cycle 1 -- the
        // same boundary EngineTrace and the Human 12 playability click use.
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        Integer hpAt52 = null;
        Integer hpAt54 = null;
        Unit.Order archerOrderAt25 = null;
        Unit.Order archerOrderAt26 = null;
        Unit.Order footmanOrderAt54 = null;
        while (world.cycle() < 56) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 25) {
                archerOrderAt25 = archer.order();
            }
            if (fixture == 26) {
                archerOrderAt26 = archer.order();
            }
            if (fixture == 52) {
                hpAt52 = grunt.hitPoints();
            }
            if (fixture == 54) {
                hpAt54 = grunt.hitPoints();
                footmanOrderAt54 = footman.order();
            }
        }

        assertEquals(Unit.Order.STILL, archerOrderAt25,
                "native waits for the archer's own cycle-26 Still marker");
        assertEquals(Unit.Order.ATTACK, archerOrderAt26,
                "native promotes the archer on its cycle-26 Still marker");
        assertTrue(hpAt52 != null && hpAt52 < opened,
                "retail's first blow lands on cycle 52; the grunt is still at "
                        + hpAt52 + " of " + opened);
        assertTrue(hpAt54 != null && hpAt54 < hpAt52,
                "retail's second blow lands on cycle 54; the grunt is still at "
                        + hpAt54 + " of " + hpAt52);
        assertEquals(Unit.Order.ATTACK, footmanOrderAt54,
                "retail's footman stays on Attack at cycle 54, not "
                        + footmanOrderAt54);
        assertEquals(81, grunt.tileX(),
                "the grunt must stand on 81,90 when the second blow lands");
        assertEquals(90, grunt.tileY(),
                "the grunt must stand on 81,90 when the second blow lands");
    }

    @Test
    @DisplayName("xhuman 10's splash-help knight defers SyncRand to Attack OP0")
    void xhuman10SplashHelpKnightDefersSyncRandUntilAttackOp0() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        Unit guard = unitAt(world, "unit-footman", 98, 56);
        Unit knight = unitAt(world, "unit-knight", 84, 89);
        assertNotNull(guard,
                "XHuman 10 has no stationary footman on 98,56");
        assertNotNull(knight,
                "XHuman 10 has no splash-help knight on 84,89");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        Integer seedAt57 = null;
        Integer seedAt58 = null;
        Integer seedAt60 = null;
        Integer knightSequenceAt58 = null;
        Integer knightTimerAt58 = null;
        Integer knightSyncAt58 = null;
        Boolean knightPendingAt58 = null;
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 61) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 57) {
                seedAt57 = world.randomSeed();
            } else if (fixture == 58) {
                seedAt58 = world.randomSeed();
                knightSequenceAt58 = knight.battleNetSequenceOffset();
                knightTimerAt58 = knight.battleNetAnimationTimer();
                knightSyncAt58 = knight.battleNetMeleeSyncRemaining();
                knightPendingAt58 = knight.battleNetPendingMeleeSyncRand();
            } else if (fixture == 60) {
                seedAt60 = world.randomSeed();
            }
        }

        assertEquals(0x967eb0e7, seedAt57,
                "the two earlier live footmen own the first two native draws");
        assertEquals(0x2781e494, seedAt58,
                "the stationary footman owns native fixture 58's sole draw");
        assertEquals(Unit.Order.ATTACK, guard.order(),
                "the person guard must still be on native action 16");
        assertTrue(guard.battleNetStationaryAttack(),
                "the 98,56 footman is the stationary action-16 witness");
        assertEquals(1922, knightSequenceAt58,
                "the residual arrival opens the knight's Attack start");
        assertEquals(3, knightTimerAt58,
                "native keeps Attack construction 3,2,1 after the arrival");
        assertEquals(0, knightSyncAt58,
                "the knight must not arm its melee cadence on residual settle");
        assertEquals(Boolean.TRUE, knightPendingAt58,
                "the knight retains the draw until Attack OP0");
        assertEquals(0xc46b9b3d, seedAt60,
                "the next mobile grunt owns fixture 60's draw");
        assertEquals(0xf94bdf32, world.randomSeed(),
                "the knight finally debits on native Attack OP0 at fixture 61");
        assertTrue(knight.battleNetMeleeSyncRemaining() > 0,
                "Attack OP0 arms the knight's twenty-five-cycle cadence");
    }

    @Test
    @DisplayName("xhuman 10's refused knight reconstructs Attack before walking")
    void xhuman10RefusedKnightReconstructsAttackBeforeWalking() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Unit knight = unitAt(world, "unit-knight", 84, 88);
        assertNotNull(knight,
                "XHuman 10 has no refused knight on 84,88 after warmup");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 67) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture >= 63 && fixture <= 65) {
                assertEquals(84, knight.tileX(),
                        "fixture " + fixture + " native holds the refused chase "
                                + "through Attack 3,2,1; id=" + knight.id()
                                + " stage="
                                + knight.battleNetAttackRefusalRecoveryStage()
                                + " seq=" + knight.battleNetSequenceOffset()
                                + "/" + knight.battleNetAnimationTimer());
                assertEquals(88, knight.tileY());
                assertEquals(1922, knight.battleNetSequenceOffset(),
                        "the refusal wake reconstructs the knight Attack start");
                assertEquals(66 - fixture, knight.battleNetAnimationTimer(),
                        "Attack construction must count 3,2,1");
            } else if (fixture == 66) {
                assertEquals(84, knight.tileX(),
                        "native plans the replacement route before walking it");
                assertEquals(88, knight.tileY());
                assertEquals(1874, knight.battleNetSequenceOffset(),
                        "the timer-one boundary hands ownership back to Move");
                assertEquals(1, knight.battleNetAnimationTimer());
                assertNotNull(knight.target());
                assertEquals(82, knight.target().tileX(),
                        "the plan-only visit names the nearby grunt");
                assertEquals(88, knight.target().tileY());
            }
        }

        assertEquals(83, knight.tileX(),
                "native first-steps west only on fixture 67");
        assertEquals(88, knight.tileY());
        assertTrue(knight.isMoving(),
                "the replacement chase must remain live after the deferred step");
    }

    @Test
    @DisplayName("xhuman 10's commanded knight pays Attack construction before retarget")
    void xhuman10CommandedKnightHoldsBeforeItsAutomaticRetarget() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        Unit knight = unitAt(world, "unit-knight", 84, 91);
        assertNotNull(knight,
                "XHuman 10 has no commanded knight on 84,91");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 64) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture >= 61 && fixture <= 63) {
                assertEquals(83, knight.tileX(),
                        "native retains the settled route through Attack 3,2,1");
                assertEquals(90, knight.tileY(),
                        "native retains the settled route through Attack 3,2,1");
                assertEquals(1922, knight.battleNetSequenceOffset(),
                        "the commanded-to-auto handoff belongs to Attack start");
                assertEquals(64 - fixture, knight.battleNetAnimationTimer(),
                        "Attack construction counts 3,2,1 before retargeting");
            }
        }

        assertEquals(82, knight.tileX(),
                "native first-steps northwest only after the construction hold");
        assertEquals(89, knight.tileY(),
                "native first-steps northwest only after the construction hold");
        assertTrue(knight.isMoving(),
                "fixture 64 owns the replacement chase residual");
    }

    @Test
    @DisplayName("xhuman 10's retained-route knight opens and debits on residual settle")
    void xhuman10RetainedRouteKnightOpensAndDebitsOnResidualSettle() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Native slot 1480. Its splash-help handoff retains route-buffer
        // cursor one while replacing the catapult with the nearby grunt.
        Unit knight = unitAt(world, "unit-knight", 84, 91);
        assertNotNull(knight, "XHuman 10 has no retained-route knight on 84,91");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 76) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 75) {
                assertEquals(0xd9e2b600, world.randomSeed(),
                        "native has not paid slot 1480's table-0x27 draw yet");
                assertEquals(1, knight.pathLength(),
                        "the final cached heading survives through the residual");
                assertEquals(2, knight.battleNetPathStepsTaken(),
                        "the replacement route retains native cursor-two provenance");
                assertEquals(1917, knight.battleNetSequenceOffset());
                assertEquals(1, knight.battleNetAnimationTimer());
            }
        }

        assertEquals(0x9cfbae39, world.randomSeed(),
                "fixture 76 pays the retained-route knight's synchronized draw");
        assertEquals(1923, knight.battleNetSequenceOffset(),
                "native residual-opens immediately past Attack OP0");
        assertEquals(1, knight.battleNetAnimationTimer());
        assertEquals(0, knight.pathLength(),
                "native parks route cursor 20 on the settle visit");
        assertTrue(!knight.battleNetPendingMeleeSyncRand(),
                "the first melee draw must no longer be pending");
        assertTrue(knight.battleNetMeleeSyncRemaining() > 0,
                "the draw arms the recurring melee cadence");
    }

    @Test
    @DisplayName("xhuman 10's knight finishes construction before replacing a dying quarry")
    void xhuman10KnightReplacesDyingQuarryOnThePaidOp0Visit() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Native slot 1480 / Java id 120. After its completed swing replaces
        // grunt 1477 with footman 1471, that footman enters Die while Attack
        // construction is on timer two. Retail retains the dying CUnitPtr for
        // the timer-two -> timer-one tick. The paid OP0 visit then installs
        // adjacent grunt 1482 directly into the 23-count body hold, preserving
        // the table-0x27 synchronized debit on fixture 228.
        Unit knight = unitById(world, 120);
        Unit oldGrunt = unitById(world, 123);
        Unit dyingFootman = unitById(world, 129);
        Unit replacementGrunt = unitById(world, 118);
        assertNotNull(knight, "XHuman 10 has no native-slot-1480 knight");
        assertNotNull(oldGrunt, "XHuman 10 has no native-slot-1477 grunt");
        assertNotNull(dyingFootman, "XHuman 10 has no native-slot-1471 footman");
        assertNotNull(replacementGrunt, "XHuman 10 has no native-slot-1482 grunt");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 201) {
            mission.tick();
        }

        assertSame(oldGrunt, knight.target());
        assertEquals(1945, knight.battleNetSequenceOffset());
        assertEquals(1, knight.battleNetAnimationTimer());

        mission.tick();
        assertSame(dyingFootman, knight.target());
        assertEquals(1922, knight.battleNetSequenceOffset());
        assertEquals(3, knight.battleNetAnimationTimer());
        mission.tick();
        assertSame(dyingFootman, knight.target());
        assertEquals(2, knight.battleNetAnimationTimer());
        mission.tick();
        assertSame(dyingFootman, knight.target(),
                "timer two must not free-scan away from the dying incumbent");
        assertEquals(Unit.Order.DYING, dyingFootman.order());
        assertEquals(1, knight.battleNetAnimationTimer());

        mission.tick();
        assertSame(replacementGrunt, knight.target());
        assertEquals(1922, knight.battleNetSequenceOffset());
        assertEquals(23, knight.battleNetAnimationTimer(),
                "the timer-one replacement enters the already-paid body hold");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 228) {
            mission.tick();
        }
        assertEquals(0xb45bf651, world.randomSeed(),
                "the paid constructor keeps the recurring melee debit on fixture 228");
    }

    @Test
    @DisplayName("xhuman 10's paid chase wake redraws and steps on one visit")
    void xhuman10PaidChaseWakeRedrawsAndStepsOnOneVisit() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Native slot 1480 / Java 120 selects axe 1496 in its paid Attack
        // tail, writes W,NW on fixture 332 and holds that buffer through the
        // complete Move band. Fixture 347 parks the cursor at twenty. The
        // following Move OP0 writes SW,NW,N,... and consumes SW in the same
        // visit; an extra Java route-plan visit leaves the knight one tile
        // behind from fixture 348 onward.
        Unit knight = unitById(world, 120);
        Unit axe = unitById(world, 104);
        assertNotNull(knight, "XHuman 10 has no native-slot-1480 knight");
        assertNotNull(axe, "XHuman 10 has no native-slot-1496 axethrower");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 347) {
            mission.tick();
        }
        assertSame(axe, knight.target());
        assertEquals(82, knight.tileX());
        assertEquals(89, knight.tileY());
        assertEquals(0, knight.pathLength(),
                "the paid Move band parks its old route at RI20");

        mission.tick();
        assertEquals(348, world.cycle() - BNE_INITIALIZATION_TICKS);
        assertEquals(81, knight.tileX(),
                "the RI20 wake must consume the fresh southwest heading");
        assertEquals(90, knight.tileY());
        assertEquals(Direction.fromDelta(-1, 1), knight.lastStepHeading());
        assertTrue(knight.isMoving());
    }

    @Test
    @DisplayName("xhuman 10's struck knight answers a nonlethal catapult splash")
    void xhuman10StruckKnightAnswersNonlethalCatapultSplash() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Authenticated native slot 1480 / Java 120 takes eight nonlethal
        // catapult splash damage on fixture 431. HitUnit banks the source for
        // the struck unit, which promotes action 12 on its fixture-432 Still
        // marker. This is local offer ownership, not person melee help:
        // neighbouring knight 1485 / Java 115 remains uninvolved.
        Unit struck = unitById(world, 120);
        Unit neighbour = unitById(world, 115);
        Unit catapult = unitById(world, 113);
        assertNotNull(struck, "XHuman 10 has no native-slot-1480 knight");
        assertNotNull(neighbour, "XHuman 10 has no native-slot-1485 knight");
        assertNotNull(catapult, "XHuman 10 has no native-slot-1487 catapult");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 430) {
            mission.tick();
        }
        assertEquals(52, struck.hitPoints());
        assertEquals(Unit.Order.STILL, struck.order());
        assertNull(struck.offeredTarget());

        mission.tick();
        assertEquals(431, world.cycle() - BNE_INITIALIZATION_TICKS);
        assertEquals(44, struck.hitPoints());
        assertEquals(Unit.Order.STILL, struck.order(),
                "the projectile lands after this unit's action visit");
        assertSame(catapult, struck.offeredTarget(),
                "positive splash banks its source for the struck unit");
        assertNull(neighbour.battleNetPendingHelpAttack(),
                "nonlethal person splash does not recruit melee brothers");

        mission.tick();
        assertEquals(432, world.cycle() - BNE_INITIALIZATION_TICKS);
        assertEquals(Unit.Order.ATTACK, struck.order());
        assertSame(catapult, struck.target());
        assertEquals(1922, struck.battleNetSequenceOffset());
        assertEquals(3, struck.battleNetAnimationTimer());
        assertEquals(Unit.Order.STILL, neighbour.order());
    }

    @Test
    @DisplayName("xhuman 10's paid first-collision chase refills as its residual settles")
    void xhuman10PaidFirstCollisionChaseRefillsOnResidualSettle() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Authenticated native slot 1493 / Java 107 carries collision one
        // through an already-paid three-step Attack chase. Its last north
        // heading has been consumed while the pixels still drain on fixture
        // 374. Native's residual-settle callback writes W,W and consumes the
        // first west heading on fixture 375; deferring NewPath to the next
        // callback leaves Java one tile east at the accepted fleet frontier.
        Unit knight = unitById(world, 107);
        Unit axe = unitById(world, 104);
        assertNotNull(knight, "XHuman 10 has no native-slot-1493 knight");
        assertNotNull(axe, "XHuman 10 has no native-slot-1496 axethrower");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 374) {
            mission.tick();
        }
        assertSame(axe, knight.target());
        assertEquals(80, knight.tileX());
        assertEquals(87, knight.tileY());
        assertTrue(knight.isMoving(),
                "fixture 374 still owes the last three northbound pixels");
        assertEquals(0, knight.pathLength(),
                "the paid route has consumed every cached heading");
        assertEquals(1, knight.battleNetCollisionCounter(),
                "the route retains native's first collision generation");
        assertEquals(0, knight.battleNetRefusals(),
                "no hard-refusal generation owns this clean residual");
        assertTrue(knight.battleNetAttackWrapDestArmPending(),
                "Attack construction remains paid through the route wrap");

        mission.tick();
        assertEquals(375, world.cycle() - BNE_INITIALIZATION_TICKS);
        assertEquals(79, knight.tileX(),
                "the settle visit must consume the fresh west heading");
        assertEquals(87, knight.tileY());
        assertEquals(Direction.fromDelta(-1, 0), knight.lastStepHeading());
        assertTrue(knight.isMoving());
        assertEquals(1, knight.pathLength(),
                "one west byte remains behind the committed route head");
    }

    @Test
    @DisplayName("xhuman 10's grunt resumes past OP0 after its paid chase")
    void xhuman10GruntResumesPastOp0AfterItsPaidChase() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Authenticated native slots 1475 / 1493, Java ids 125 / 107. The
        // grunt pays Attack construction through fixtures 228..230 while the
        // knight remains out of range, then follows its cached east route.
        // When the stride settles on fixture 247, retail resumes directly at
        // 2540/1 past OP0 rather than charging a second 2539/3,2,1 constructor.
        // That body reaches OP10 on fixture 257 and deals the native three HP.
        Unit grunt = unitById(world, 125);
        Unit knight = unitById(world, 107);
        assertNotNull(grunt, "XHuman 10 has no native-slot-1475 grunt");
        assertNotNull(knight, "XHuman 10 has no native-slot-1493 knight");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 230) {
            mission.tick();
        }

        assertSame(knight, grunt.target());
        assertEquals(2539, grunt.battleNetSequenceOffset());
        assertEquals(1, grunt.battleNetAnimationTimer(),
                "fixture 230 finishes the one and only Attack constructor");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 247) {
            mission.tick();
        }
        assertEquals(81, grunt.tileX());
        assertEquals(89, grunt.tileY());
        assertSame(knight, grunt.target());
        assertEquals(2540, grunt.battleNetSequenceOffset(),
                "the paid chase resumes immediately past Attack OP0");
        assertEquals(1, grunt.battleNetAnimationTimer());

        mission.tick();
        assertEquals(2544, grunt.battleNetSequenceOffset());
        assertEquals(3, grunt.battleNetAnimationTimer(),
                "fixture 248 enters the authenticated melee wind-up");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 257) {
            mission.tick();
        }
        assertEquals(67, knight.hitPoints(),
                "fixture 257 owns native slot 1475's three-point OP10 blow");
        assertEquals(2558, grunt.battleNetSequenceOffset());
        assertEquals(5, grunt.battleNetAnimationTimer());
    }

    @Test
    @DisplayName("xhuman 10's replacement refusals preserve native RNG and cavalry flow")
    void xhuman10ReplacementRefusalsPreserveNativeRngAndCavalryFlow() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Native 1486 is the blocked replacement-ray witness. Native 1485
        // proves that already-paid Attack construction is not charged again
        // when its first replacement stride settles. Native 1471 receives the
        // projectile whose damage roll follows both transitions.
        Unit refusedGrunt = unitAt(world, "unit-grunt", 78, 89);
        Unit cavalry = unitAt(world, "unit-knight", 84, 90);
        Unit hurtGrunt = unitAt(world, "unit-grunt", 78, 93);
        assertNotNull(refusedGrunt);
        assertNotNull(cavalry);
        assertNotNull(hurtGrunt);
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 81) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 77) {
                assertEquals(0xfed4b178, world.battleNetRandomSeed(),
                        "the blocked replacement grunt must not steal the "
                                + "following projectile's async draw");
                assertEquals(80, refusedGrunt.tileX());
                assertEquals(90, refusedGrunt.tileY());
                assertEquals(2482, refusedGrunt.battleNetSequenceOffset(),
                        "native keeps the grunt on Move during the refusal band");
                assertEquals(10, refusedGrunt.battleNetAnimationTimer(),
                        "fixture 77 is the tenth count of the retained Move band");
                assertEquals(2, refusedGrunt.pathLength(),
                        "the blocked replacement ray remains cached");
            } else if (fixture == 79) {
                assertEquals(0x9ca2a85c, world.battleNetRandomSeed(),
                        "native async ownership must remain aligned through "
                                + "the footman's damage roll");
                assertEquals(42, hurtGrunt.hitPoints(),
                        "the aligned footman roll deals native four damage");
            } else if (fixture == 80) {
                assertEquals(83, cavalry.tileX());
                assertEquals(90, cavalry.tileY());
                assertTrue(cavalry.battleNetSequenceOffset() >= 1874
                                && cavalry.battleNetSequenceOffset() < 1922,
                        "the settled route boundary must remain owned by Move");
                assertEquals(0, cavalry.battleNetOrderDelay(),
                        "already-paid Attack construction must not buy a "
                                + "second residual hold");
            }
        }

        assertEquals(82, cavalry.tileX(),
                "native redraws and first-steps west on fixture 81");
        assertEquals(90, cavalry.tileY());
        assertTrue(cavalry.isMoving(),
                "the paid-construction replacement chase remains live");
        assertTrue(cavalry.battleNetSequenceOffset() >= 1874
                        && cavalry.battleNetSequenceOffset() < 1922,
                "fixture 81 advances directly through the knight Move body");
    }

    @Test
    @DisplayName("xhuman 10's residual retarget leaves its dying quarry")
    void xhuman10ResidualRetargetLeavesItsDyingQuarry() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        Unit grunt = unitAt(world, "unit-grunt", 78, 87);
        Unit footman = unitAt(world, "unit-footman", 82, 88);
        Unit knight = unitAt(world, "unit-knight", 84, 89);
        assertNotNull(grunt, "XHuman 10 has no grunt on 78,87");
        assertNotNull(footman, "XHuman 10 has no footman on 82,88");
        assertNotNull(knight, "XHuman 10 has no knight on 84,89");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 54) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 50) {
                assertSame(footman, grunt.target(),
                        "the committed residual still owns the dying footman");
                assertEquals(Unit.Order.DYING, footman.order());
            } else if (fixture == 51) {
                assertSame(knight, grunt.target(),
                        "retail OP0 replaces the corpse with the live knight");
                assertEquals(2539, grunt.battleNetSequenceOffset(),
                        "retail parks on the grunt's Attack start");
                assertEquals(3, grunt.battleNetAnimationTimer(),
                        "the replacement pays Attack construction 3,2,1");
                assertEquals(81, grunt.tileX());
                assertEquals(89, grunt.tileY());
            } else if (fixture == 53) {
                assertEquals(81, grunt.tileX());
                assertEquals(89, grunt.tileY(),
                        "the grunt must hold through construction fixture 53");
            }
        }

        assertSame(knight, grunt.target());
        assertEquals(82, grunt.tileX());
        assertEquals(88, grunt.tileY(),
                "retail resumes the live chase NE on fixture 54");
        assertTrue(grunt.isMoving(),
                "the grunt must be walking, not swinging at the corpse");
    }

    @Test
    @DisplayName("xhuman 10's settled grunt replaces its equal offered target")
    void xhuman10SettledGruntReplacesItsEqualOfferedTarget() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Native slot 1495: the western grunt which first chases the footman
        // on 82,88 and then the knight which opened on 84,89.
        Unit grunt = unitAt(world, "unit-grunt", 78, 87);
        assertNotNull(grunt, "XHuman 10 has no western grunt on 78,87");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 69) {
            mission.tick();
        }
        Unit replacement = unitAt(world, "unit-knight", 83, 88);
        Unit incumbent = unitAt(world, "unit-knight", 83, 89);
        assertNotNull(replacement,
                "native slot 1493 must be adjacent on 83,88 at fixture 69");
        assertNotNull(incumbent,
                "native slot 1489 must remain on 83,89 at fixture 69");
        assertSame(incumbent, grunt.target(),
                "fixture 69 still owns the offered knight and cached heading");
        assertEquals(1, grunt.pathLength(),
                "fixture 69 retains the last heading of the old chase");

        mission.tick();

        assertSame(replacement, grunt.target(),
                "native OP0 free-scan replaces the equal offered knight at fixture 70");
        assertEquals(0, grunt.pathLength(),
                "SetAutoTarget clears the stale heading when the goal changes");
        assertEquals(0xd9e2b600, world.randomSeed(),
                "the first live in-range callback pays native FUN_004234b0");
        assertTrue(grunt.battleNetMeleeSyncRemaining() > 0,
                "the successful callback arms the repeating melee cadence");
    }

    @Test
    @DisplayName("xhuman 10's blocked replacement route pays one refusal band")
    void xhuman10BlockedReplacementRoutePaysOneRefusalBand() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Native slot 1475: the southern grunt which opens on 76,92.
        Unit grunt = unitAt(world, "unit-grunt", 76, 92);
        assertNotNull(grunt, "XHuman 10 has no southern grunt on 76,92");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 75) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 57) {
                assertEquals(79, grunt.tileX());
                assertEquals(90, grunt.tileY());
                Unit blocker = unitAt(world, "unit-grunt", 80, 90);
                assertNotNull(blocker,
                        "native slot 1486 is crossing the replacement ray");
                assertEquals(0, blocker.battleNetCollisionCounter(),
                        "the moving blocker remains cooperative in FUN_004379e0");
                assertNotNull(grunt.target());
                assertEquals(83, grunt.target().tileX());
                assertEquals(89, grunt.target().tileY());
                assertEquals(4, grunt.pathLength(),
                        "retail keeps the blocked E,NE,E,E replacement ray");
                assertEquals(Direction.fromDelta(1, 0), grunt.peekHeading(),
                        "the allied blocker refuses the first east heading");
                assertEquals(2482, grunt.battleNetSequenceOffset(),
                        "the refusal owns the grunt Move start");
                assertEquals(15, grunt.battleNetAnimationTimer(),
                        "a newly blocked replacement ray pays one full Move band");
            } else if (fixture == 64) {
                assertEquals(83, grunt.target().tileX(),
                        "a better scan result remains banked during the band");
                assertEquals(89, grunt.target().tileY());
                assertEquals(2482, grunt.battleNetSequenceOffset());
                assertEquals(8, grunt.battleNetAnimationTimer());
            } else if (fixture == 72) {
                assertEquals(79, grunt.tileX(),
                        "the timer-one wake opens Attack instead of walking");
                assertEquals(90, grunt.tileY());
                assertEquals(2539, grunt.battleNetSequenceOffset(),
                        "retail pays Attack construction after the refusal band");
                assertEquals(3, grunt.battleNetAnimationTimer());
            }
        }

        assertEquals(82, grunt.target().tileX(),
                "the banked replacement installs after Attack 3,2,1");
        assertEquals(89, grunt.target().tileY());
        assertEquals(3, grunt.pathLength(),
                "retail installs the replacement NE,E,E route without stepping it");
        assertEquals(Direction.fromDelta(1, -1), grunt.peekHeading());
        assertEquals(2482, grunt.battleNetSequenceOffset());
        assertEquals(15, grunt.battleNetAnimationTimer(),
                "the first replacement probe retains native Move ownership");
    }

    @Test
    @DisplayName("xhuman 10's paid refusal wake routes through departing allies")
    void xhuman10PaidRefusalWakeRoutesThroughDepartingAllies() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Native slot 1490: the grunt which opens on 76,88, parks its E
        // residual at fixture 41 and pays the complete cooperative-refusal
        // band through fixture 56.
        Unit grunt = unitAt(world, "unit-grunt", 76, 88);
        assertNotNull(grunt, "XHuman 10 has no western grunt on 76,88");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 57) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 42) {
                assertEquals(78, grunt.tileX());
                assertEquals(88, grunt.tileY());
                assertEquals(2, grunt.pathLength(),
                        "the parked replacement keeps its two headings");
            } else if (fixture == 56) {
                assertEquals(78, grunt.tileX(),
                        "the paid band holds the grunt through timer one");
                assertEquals(88, grunt.tileY());
                assertTrue(grunt.battleNetRefusalHold(),
                        "the wake retains refusal provenance for target routing");
            }
        }

        assertEquals(79, grunt.tileX(),
                "retail first-steps east on the refusal wake");
        assertEquals(88, grunt.tileY(),
                "the departing ally is soft for the replacement ray");
        assertTrue(grunt.isMoving());
        assertNotNull(grunt.target());
        assertEquals("unit-knight", grunt.target().type().ident());
        assertEquals(83, grunt.target().tileX());
        assertEquals(89, grunt.target().tileY());
        assertEquals(2, grunt.pathLength(),
                "E,E,SE is installed and the first east heading is spent");
        assertEquals(Direction.fromDelta(1, 0), grunt.peekHeading(),
                "the second native heading remains east");
        assertEquals(0, grunt.battleNetCollisionCounter(),
                "a successful wake clears the paid collision provenance");
        assertEquals(0, grunt.battleNetRefusals());
    }

    @Test
    @DisplayName("xhuman 10's settled replacement keeps its blocked route")
    void xhuman10SettledReplacementKeepsItsBlockedRoute() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Native slot 1497: the northern grunt whose east residual settles
        // as its target changes from the footman to the knight. Native draws
        // SE,SE,NE through the departing allied grunt, restores occupancy,
        // then refuses that first SE without discarding the route.
        Unit grunt = unitAt(world, "unit-grunt", 76, 86);
        assertNotNull(grunt, "XHuman 10 has no northern grunt on 76,86");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 59) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture >= 56) {
                assertEquals(79, grunt.tileX(),
                        "the blocked replacement must not sidestep at fixture "
                                + fixture);
                assertEquals(87, grunt.tileY());
                assertNotNull(grunt.target());
                assertEquals("unit-knight", grunt.target().type().ident());
                assertEquals(83, grunt.target().tileX());
                assertEquals(89, grunt.target().tileY());
                assertEquals(3, grunt.pathLength(),
                        "retail retains the SE,SE,NE replacement ray");
                assertEquals(Direction.fromDelta(1, 1), grunt.peekHeading());
                assertEquals(2482, grunt.battleNetSequenceOffset(),
                        "the cooperative refusal owns Move start");
                assertEquals(71 - fixture, grunt.battleNetAnimationTimer(),
                        "the refusal counts down from native timer fifteen");
            }
        }
    }

    @Test
    @DisplayName("xhuman 10's archer keeps Attack while the melee arrival owns SyncRand")
    void xhuman10ArcherRetargetOwnsItsAttackLoopWrap() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Native slot 1473 wraps and retargets on fixture 90, but bow +0xb and
        // +0x7a refresh without touching SyncRand (XHuman 9/12 prove that
        // independently). The same fixture's sole 0x4234CD draw belongs to
        // native grunt 1477 / Java 123: its one cached heading survives
        // Attack 3,2,1 and is parked when OP0 accepts the first live arrival.
        Unit archer = unitAt(world, "unit-archer", 84, 93);
        Unit arrival = unitById(world, 123);
        assertNotNull(archer, "XHuman 10 has no archer on 84,93");
        assertNotNull(arrival, "XHuman 10 has no Java-paired grunt 1477");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 89) {
            mission.tick();
        }
        assertEquals(Unit.Order.ATTACK, archer.order());
        assertEquals(2054, archer.battleNetSequenceOffset());
        assertEquals(1, archer.battleNetAnimationTimer());
        assertEquals(0, archer.battleNetMeleeSyncRemaining(),
                "bow animation floors are not table-0x27 melee cadence");
        assertTrue(arrival.battleNetPendingMeleeSyncRand());
        assertEquals(1, arrival.pathLength());
        assertEquals(2539, arrival.battleNetSequenceOffset());
        assertEquals(1, arrival.battleNetAnimationTimer());
        assertEquals(0x31dff4f5, world.randomSeed());

        mission.tick();
        assertEquals(0x237c228a, world.randomSeed(),
                "grunt 1477's path-one OP0 owns caller 0x4234CD at fixture 90");
        assertTrue(!arrival.battleNetPendingMeleeSyncRand());
        assertEquals(0, arrival.pathLength());
        assertEquals(2540, arrival.battleNetSequenceOffset());
        assertEquals(1, arrival.battleNetAnimationTimer());
        assertEquals(Unit.Order.ATTACK, archer.order(),
                "the fresh ranged Attack constructor owns the retarget visit");
        assertNotNull(archer.target());
        assertEquals(81, archer.target().tileX());
        assertEquals(90, archer.target().tileY());
        assertEquals(2039, archer.battleNetSequenceOffset());
        assertEquals(3, archer.battleNetAnimationTimer());

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 93) {
            mission.tick();
        }
        assertEquals(Unit.Order.ATTACK, archer.order());
        assertEquals(2039, archer.battleNetSequenceOffset());
        assertEquals(63, archer.battleNetAnimationTimer(),
                "the new ranged swing enters native's committed OP0 hold");

        mission.tick();
        assertEquals(Unit.Order.ATTACK, archer.order(),
                "action 16 remains Attack throughout the committed OP0 hold");
        assertEquals(2039, archer.battleNetSequenceOffset());
        assertEquals(62, archer.battleNetAnimationTimer(),
                "native owns the first quiet committed-hold visit at fixture 94");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 155) {
            mission.tick();
        }
        assertEquals(Unit.Order.ATTACK, archer.order());
        assertEquals(2039, archer.battleNetSequenceOffset());
        assertEquals(1, archer.battleNetAnimationTimer());

        mission.tick();
        assertEquals(Unit.Order.ATTACK, archer.order(),
                "the prior quarry's recovery must not end the replacement engagement");
        assertNotNull(archer.target());
        assertEquals(81, archer.target().tileX());
        assertEquals(90, archer.target().tileY());
        assertEquals(2040, archer.battleNetSequenceOffset(),
                "native advances the replacement attack body at fixture 156");
        assertEquals(1, archer.battleNetAnimationTimer());
    }

    @Test
    @DisplayName("xhuman 10's recurring ranged retarget renews the OP0 cadence")
    void xhuman10RecurringRangedRetargetRenewsOp0Cadence() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Native archer 1502 / Java 98 enters its first 63-count ranged hold
        // at fixture 26. When that hold expires it changes grunts at fixture
        // 89, reconstructs Attack 3,2,1, and renews the same hold at 92.
        // Carrying the old hold-active bit across the retarget let Java enter
        // windup and create a phantom arrow at fixture 103. Its three async
        // constructor draws then changed footman 121's fixture-104 damage to
        // grunt 1471 / Java 129 from native eight to four.
        Unit archer = unitAt(world, "unit-archer", 84, 85);
        Unit grunt = unitAt(world, "unit-grunt", 78, 93);
        Unit dyingIncumbent = unitById(world, 105);
        Unit thirdTarget = unitById(world, 118);
        Unit heldAxe = unitById(world, 122);
        Unit easternOgre = unitById(world, 52);
        assertNotNull(archer, "XHuman 10 has no archer on 84,85");
        assertNotNull(grunt, "XHuman 10 has no opening grunt on 78,93");
        assertNotNull(dyingIncumbent, "XHuman 10 has no second archer target");
        assertNotNull(thirdTarget, "XHuman 10 has no third archer target");
        assertNotNull(heldAxe, "XHuman 10 has no western held axethrower");
        assertNotNull(easternOgre, "XHuman 10 has no eastern melee ogre");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 89) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 57) {
                assertEquals(887, heldAxe.battleNetSequenceOffset(),
                        "a retained dying target reopens Attack construction");
                assertEquals(3, heldAxe.battleNetAnimationTimer());
            } else if (fixture == 60) {
                assertEquals(887, heldAxe.battleNetSequenceOffset());
                assertEquals(63, heldAxe.battleNetAnimationTimer(),
                        "the dying pointer still owns native's ranged OP0 hold");
                assertEquals(Unit.Order.ATTACK, heldAxe.order(),
                        "the committed hold keeps native stand-ground attack ownership");
            } else if (fixture == 67) {
                assertEquals(887, heldAxe.battleNetSequenceOffset());
                assertEquals(56, heldAxe.battleNetAnimationTimer(),
                        "the hold prevents a phantom dying-target axe");
                assertEquals(Unit.Order.ATTACK, heldAxe.order());
            } else if (fixture == 68) {
                assertEquals(73, easternOgre.hitPoints(),
                        "the later melee roll remains on native's async draw");
            }
        }
        assertEquals(2039, archer.battleNetSequenceOffset());
        assertEquals(3, archer.battleNetAnimationTimer(),
                "the second ranged retarget restarts Attack construction");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 92) {
            mission.tick();
        }
        assertEquals(2039, archer.battleNetSequenceOffset());
        assertEquals(63, archer.battleNetAnimationTimer(),
                "the replacement swing must renew native's ranged OP0 hold");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 104) {
            mission.tick();
        }
        assertEquals(2039, archer.battleNetSequenceOffset(),
                "the phantom fixture-103 arrow must never enter windup");
        assertEquals(51, archer.battleNetAnimationTimer());
        assertEquals(34, grunt.hitPoints(),
                "fixture-104 melee must retain BNE's eight-damage async roll");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 154) {
            mission.tick();
        }
        assertSame(dyingIncumbent, archer.target());
        assertEquals(Unit.Order.DYING, dyingIncumbent.order());
        assertEquals(2039, archer.battleNetSequenceOffset());
        assertEquals(1, archer.battleNetAnimationTimer());

        mission.tick();
        assertEquals(Unit.Order.ATTACK, archer.order(),
                "a dying incumbent must not drop stationary ranged combat to Still");
        assertSame(thirdTarget, archer.target(),
                "native's OP0 free scan installs the live 80,88 grunt");
        assertEquals(80, thirdTarget.tileX());
        assertEquals(88, thirdTarget.tileY());
        assertEquals(2039, archer.battleNetSequenceOffset());
        assertEquals(3, archer.battleNetAnimationTimer(),
                "the third ranged engagement restarts Attack construction");
    }

    @Test
    @DisplayName("xhuman 10's paid route settles through Attack before its next step")
    void xhuman10PaidRouteResidualReopensAttackBeforeNextHeading() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Native slot 1497. A collision-backed tail-wrap route has three bytes
        // left when its first residual settles. Attack owns 90..92; Move spends
        // the retained southeast heading only on fixture 93.
        Unit grunt = unitAt(world, "unit-grunt", 76, 86);
        assertNotNull(grunt, "XHuman 10 has no northern grunt on 76,86");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 89) {
            mission.tick();
        }
        assertEquals(78, grunt.tileX());
        assertEquals(88, grunt.tileY());
        assertEquals(2, grunt.offsetX());
        assertEquals(-2, grunt.offsetY());

        mission.tick();
        assertEquals(78, grunt.tileX());
        assertEquals(88, grunt.tileY());
        assertEquals(0, grunt.offsetX());
        assertEquals(0, grunt.offsetY());
        assertEquals(3, grunt.pathLength(),
                "Attack construction retains the native SE,SE,NE route");
        assertEquals(2539, grunt.battleNetSequenceOffset());
        assertEquals(3, grunt.battleNetAnimationTimer());

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 92) {
            mission.tick();
        }
        assertEquals(78, grunt.tileX());
        assertEquals(88, grunt.tileY());
        assertEquals(2539, grunt.battleNetSequenceOffset());
        assertEquals(1, grunt.battleNetAnimationTimer(),
                "native slot 1497 / Java unit " + grunt.id());

        mission.tick();
        assertEquals(79, grunt.tileX());
        assertEquals(89, grunt.tileY());
        assertTrue(grunt.isMoving(),
                "Move spends the retained southeast heading after 3,2,1");
        assertEquals(2, grunt.pathLength());
    }

    @Test
    @DisplayName("xhuman 10's refused melee tail opens past attack op0")
    void xhuman10RefusedMeleeTailOpensPastAttackOp0() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Native 1538 / Java 62 begins at 91,58. Its crowded approach is
        // refused once, then a one-heading southeast recovery route settles
        // adjacent to native footman 1542. That paid residual enters the ogre
        // Attack program after OP0; treating it as a fresh approach installs
        // a 23-cycle hold and suppresses the fixture-92 blow.
        Unit ogre = unitAt(world, "unit-ogre", 91, 58);
        Unit footman = unitAt(world, "unit-footman", 98, 56);
        assertNotNull(ogre, "XHuman 10 has no refusal-tail ogre on 91,58");
        assertNotNull(footman, "XHuman 10 has no stationary footman on 98,56");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 85) {
            mission.tick();
        }
        assertEquals(97, ogre.tileX());
        assertEquals(57, ogre.tileY());
        assertEquals(1, ogre.battleNetRefusals(),
                "the opening is owned by the paid refusal-recovery tail");
        assertEquals(644, ogre.battleNetSequenceOffset(),
                "native enters immediately after ogre Attack OP0");
        assertEquals(1, ogre.battleNetAnimationTimer());

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 92) {
            mission.tick();
        }
        assertEquals(658, ogre.battleNetSequenceOffset(),
                "fixture 92 consumes native ogre OP10 into its frame-35 wait");
        assertEquals(5, ogre.battleNetAnimationTimer());
        assertEquals(32, footman.hitPoints(),
                "the refusal-tail ogre must land native fixture 92's nine damage");
    }

    @Test
    @DisplayName("xhuman 10's melee loop retains its offered equal-score target")
    void xhuman10MeleeLoopRetainsItsOfferedEqualScoreTarget() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Native 1542 / Java 58 begins at 98,56. At fixture 83 its completed
        // swing sees three adjacent, equally scored ogres. OfferNewTarget has
        // banked native 1543 / Java 57 at 97,56, so BNE prices that incumbent
        // before its screen-Y walk, restarts Attack construction 3,2,1, and
        // enters the 23-cycle committed hold instead of hitting ogre 1548.
        Unit footman = unitAt(world, "unit-footman", 98, 56);
        assertNotNull(footman, "XHuman 10 has no footman on 98,56");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 83) {
            mission.tick();
        }
        Unit offeredOgre = unitAt(world, "unit-ogre", 97, 56);
        Unit spatialFirstOgre = unitAt(world, "unit-ogre", 97, 55);
        assertNotNull(offeredOgre, "XHuman 10 has no offered ogre on 97,56");
        assertNotNull(spatialFirstOgre,
                "XHuman 10 has no spatial-first ogre on 97,55");
        assertSame(offeredOgre, footman.target(),
                "native's banked equal-score offer wins the tail-wrap scan");
        assertEquals(2539, footman.battleNetSequenceOffset());
        assertEquals(3, footman.battleNetAnimationTimer(),
                "native restarts footman Attack construction at fixture 83");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 86) {
            mission.tick();
        }
        assertSame(offeredOgre, footman.target());
        assertEquals(2539, footman.battleNetSequenceOffset());
        assertEquals(23, footman.battleNetAnimationTimer(),
                "the offered retarget enters the committed melee body hold");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 93) {
            mission.tick();
        }
        assertEquals(73, spatialFirstOgre.hitPoints(),
                "the discarded equal-score target must not take an early hit");
    }

    @Test
    @DisplayName("xhuman 10's paid cavalry residual retarget opens attack immediately")
    void xhuman10PaidCavalryResidualRetargetOpensAttackImmediately() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Native 1485 / Java 115 begins at 84,90. Its paid westbound route
        // finishes on fixture 93 as the dying footman ceases to be a goal.
        // CheckForTargetInRange names adjacent grunt 1477 / Java 123 and, in
        // the same callback, opens knight Attack@1922/3 and calls 0x4234CD.
        Unit knight = unitAt(world, "unit-knight", 84, 90);
        assertNotNull(knight, "XHuman 10 has no paid-residual knight on 84,90");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 93) {
            mission.tick();
        }
        Unit grunt = unitAt(world, "unit-grunt", 81, 89);
        assertNotNull(grunt, "native replacement grunt must stand on 81,89");
        assertSame(grunt, knight.target(),
                "the settled route must promote its in-range replacement");
        assertEquals(82, knight.tileX());
        assertEquals(90, knight.tileY());
        assertEquals(1922, knight.battleNetSequenceOffset(),
                "native opens fresh knight Attack construction on the settle visit");
        assertEquals(3, knight.battleNetAnimationTimer());
        assertEquals(0xaf1cf0fb, world.randomSeed(),
                "the paid arrival must debit native caller 0x4234CD at fixture 93");
        assertEquals(25, knight.battleNetMeleeSyncRemaining(),
                "the first in-range debit arms the native attack-loop cadence");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 96) {
            mission.tick();
        }
        assertEquals(1922, knight.battleNetSequenceOffset());
        assertEquals(23, knight.battleNetAnimationTimer(),
                "construction 3,2,1 enters native's committed melee body hold");
    }

    @Test
    @DisplayName("xhuman 10's long gold corridor parks its occupied cardinal tail")
    void xhuman10LongGoldCorridorParksItsOccupiedCardinalTail() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Authenticated native slot 1438 / Java 162. Its six-heading gold
        // prefix ends at (17,114) behind peon 1433. Retail parks the stale
        // south tail on fixture 115, refills an occupied south-east head, and
        // advances collision 1..8 before serving the complete refusal band.
        // It does not free-wake south when the old cell clears on fixture 117.
        Unit peon = unitAt(world, "unit-peon", 15, 107);
        assertNotNull(peon, "XHuman 10 has no native-slot-1438 gold peon");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 122) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture >= 115 && fixture <= 122) {
                assertEquals(17, peon.tileX());
                assertEquals(114, peon.tileY(),
                        "the occupied-prefix refusal must not free-wake south on "
                                + "fixture " + fixture);
                assertEquals(fixture - 114,
                        peon.battleNetCollisionCounter(),
                        "native increments the collision nibble on every refused "
                                + "route generation");
            }
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 137) {
            mission.tick();
        }
        assertEquals(17, peon.tileX());
        assertEquals(115, peon.tileY(),
                "native wakes after the bounded band and replans south");
        assertEquals(8, peon.battleNetCollisionCounter(),
                "the paid refusal generation remains attached to the route");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 414) {
            mission.tick();
        }
        assertEquals(7, peon.battleNetCollisionCounter());
        assertEquals(1, peon.battleNetAnimationTimer());

        mission.tick();
        assertEquals(8, peon.battleNetCollisionCounter(),
                "the eighth native collision generation owns the paid band");
        assertEquals(15, peon.battleNetAnimationTimer(),
                "the generation-eight visit arms Move 15 on fixture 415");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 430) {
            mission.tick();
        }
        assertEquals(15, peon.tileX());
        assertEquals(115, peon.tileY(),
                "the paid wake consumes the native north-east route head");
        assertEquals(8, peon.battleNetCollisionCounter(),
                "the route keeps the collision generation which paid its wait");
    }

    @Test
    @DisplayName("independent laden peons retain a repeatedly refused return route")
    void xhuman10LadenPeonRetainsItsRefusedReturnRoute() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Authenticated native slot 1588 / Java 12 leaves the mine carrying
        // 100 gold and caches SW,S,S,S,SW toward the stronghold. After SW's
        // residual settles at (56,2), the next S is occupied. Retail retains
        // all four tail bytes and restarts peon Move at 2600/15 on fixture
        // 270; it repeats that refusal at fixture 285 with collision nibble
        // two. Clearing the cached tail lets the resource order replan and
        // step SE immediately on fixture 271.
        Unit peon = unitById(world, 12);
        assertNotNull(peon, "XHuman 10 has no native-slot-1588 return peon");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 248) {
            mission.tick();
        }
        assertEquals(56, peon.tileX());
        assertEquals(2, peon.tileY());
        assertTrue(peon.returningToDepot());
        assertEquals(100, peon.carried());
        assertEquals(4, peon.pathLength(),
                "the SW step leaves the authenticated S,S,S,SW tail");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 270) {
            mission.tick();
        }
        assertEquals(56, peon.tileX());
        assertEquals(2, peon.tileY());
        assertEquals(4, peon.pathLength(),
                "the first laden refusal parks the cursor without erasing its bytes");
        assertEquals(1, peon.battleNetCollisionCounter());
        assertEquals(2600, peon.battleNetSequenceOffset());
        assertEquals(15, peon.battleNetAnimationTimer());

        mission.tick();
        assertEquals(56, peon.tileX(),
                "the refused return must remain on its native tile at fixture 271");
        assertEquals(2, peon.tileY());
        assertEquals(4, peon.pathLength());
        assertEquals(14, peon.battleNetAnimationTimer());

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 285) {
            mission.tick();
        }
        assertEquals(56, peon.tileX());
        assertEquals(2, peon.tileY());
        assertEquals(4, peon.pathLength());
        assertEquals(2, peon.battleNetCollisionCounter(),
                "the second timer-one refusal advances the same generation");
        assertEquals(2600, peon.battleNetSequenceOffset());
        assertEquals(15, peon.battleNetAnimationTimer());

        // Independent native slot 1539 / Java 61 on Human 14 retains its
        // final south byte through Move 2600/15..1. At fixture 391 the clean
        // laden convoy in slot 1537 is still draining through that square, so
        // retail advances the collision generation and starts another full
        // band without erasing the cached byte. Java used to park the route
        // and commit a fresh southeast detour on fixture 392.
        Mission human14 = data.loadMission("campaigns/human/level14h",
                GameData.personIn(data.campaignMap(
                        "campaigns/human/level14h")), 1);
        Assumptions.assumeTrue(human14 != null,
                "Human 14 is not in the pack");
        World human14World = human14.world();
        Unit returner = unitById(human14World, 61);
        Unit convoy = unitById(human14World, 63);
        assertNotNull(returner,
                "Human 14 has no native-slot-1539 return peon");
        assertNotNull(convoy,
                "Human 14 has no native-slot-1537 convoy peon");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            human14.tick();
        }
        while (human14World.cycle() - BNE_INITIALIZATION_TICKS < 390) {
            human14.tick();
        }

        assertEquals(56, returner.tileX());
        assertEquals(57, returner.tileY());
        assertTrue(returner.returningToDepot());
        assertEquals(100, returner.carried());
        assertEquals(1, returner.pathLength());
        assertEquals(1, returner.battleNetCollisionCounter());
        assertEquals(1, returner.battleNetAnimationTimer());
        assertTrue(convoy.isMoving());
        assertTrue(convoy.returningToDepot());
        assertEquals(100, convoy.carried());
        assertEquals(0, convoy.battleNetCollisionCounter());
        assertTrue(human14World.battleNetCooperativeBlocker(
                returner, convoy));

        human14.tick();
        assertEquals(391,
                human14World.cycle() - BNE_INITIALIZATION_TICKS);
        assertEquals(56, returner.tileX());
        assertEquals(57, returner.tileY());
        assertEquals(1, returner.pathLength(),
                "the still-blocked cached south byte remains live");
        assertEquals(2, returner.battleNetCollisionCounter());
        assertEquals(2600, returner.battleNetSequenceOffset());
        assertEquals(15, returner.battleNetAnimationTimer());
        assertEquals(14, returner.battleNetOrderDelay());

        human14.tick();
        assertEquals(392,
                human14World.cycle() - BNE_INITIALIZATION_TICKS);
        assertEquals(56, returner.tileX(),
                "the repeated band prevents Java's early southeast detour");
        assertEquals(57, returner.tileY());
        assertEquals(1, returner.pathLength());
        assertEquals(14, returner.battleNetAnimationTimer());

        while (human14World.cycle() - BNE_INITIALIZATION_TICKS < 405) {
            human14.tick();
        }
        assertEquals(56, returner.tileX());
        assertEquals(57, returner.tileY());
        assertEquals(1, returner.pathLength(),
                "Move timer one retains the consumed south tail for this visit");
        assertEquals(2, returner.battleNetCollisionCounter());
        assertEquals(2600, returner.battleNetSequenceOffset());
        assertEquals(1, returner.battleNetAnimationTimer());

        human14.tick();
        assertEquals(406,
                human14World.cycle() - BNE_INITIALIZATION_TICKS);
        assertEquals(56, returner.tileX(),
                "the timer-one action parks the newly blocked south tail");
        assertEquals(57, returner.tileY());
        assertEquals(0, returner.pathLength(),
                "native route index twenty exposes no live Java headings");
        assertEquals(3, returner.battleNetCollisionCounter());
        assertEquals(2600, returner.battleNetSequenceOffset());
        assertEquals(1, returner.battleNetAnimationTimer());

        human14.tick();
        assertEquals(407,
                human14World.cycle() - BNE_INITIALIZATION_TICKS);
        assertEquals(57, returner.tileX(),
                "the following resource visit redraws southeast around traffic");
        assertEquals(58, returner.tileY());
        assertTrue(returner.isMoving());
        assertEquals(3, returner.battleNetCollisionCounter(),
                "the redrawn route retains collision generation three");
    }

    @Test
    @DisplayName("xhuman 10's laden peon redraws behind a collided returner")
    void xhuman10LadenPeonRedrawsBehindACollidedReturner() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Authenticated native slot 1584 / Java 16 exits its mine with the
        // old approach collision generation cleared, then caches S,S,SW after
        // its opening SW return stride. On fixture 290, collided returner
        // 1590 still occupies the next S at the start of the pass. Retail
        // raises collision one and parks the cursor at 20/Move 2600/1; it
        // redraws SE,S,SW,NW and commits SE on the following fixture. Treating
        // every far laden blocker as cooperative freezes this peon for 15.
        Unit peon = unitById(world, 16);
        assertNotNull(peon, "XHuman 10 has no native-slot-1584 return peon");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 268) {
            mission.tick();
        }
        assertEquals(56, peon.tileX());
        assertEquals(3, peon.tileY());
        assertTrue(peon.returningToDepot());
        assertEquals(100, peon.carried());
        assertEquals(0, peon.battleNetCollisionCounter(),
                "DropOutNearest clears the mine-approach collision generation");
        assertEquals(3, peon.pathLength());

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 290) {
            mission.tick();
        }
        assertEquals(56, peon.tileX());
        assertEquals(3, peon.tileY());
        assertEquals(0, peon.pathLength(),
                "the collided block parks native route index at twenty");
        assertEquals(1, peon.battleNetCollisionCounter());
        assertEquals(2600, peon.battleNetSequenceOffset());
        assertEquals(1, peon.battleNetAnimationTimer());

        mission.tick();
        assertEquals(57, peon.tileX());
        assertEquals(4, peon.tileY(),
                "the following action visit commits the replacement SE head");
        assertEquals(3, peon.pathLength());
        assertEquals(1, peon.battleNetCollisionCounter());
    }

    @Test
    @DisplayName("xhuman 10's laden peon refills before the depot skirt")
    void xhuman10LadenPeonRefillsBeforeTheDepotSkirt() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Authenticated native slot 1596 / Java 4 consumes SW,S,S,SW on its
        // laden walk. The last residual reaches exact tile centre at (55,6)
        // on fixture 310. That point is two anchors from the stronghold's
        // contracted entry, so retail remains in action 24, refills SW,SE,
        // and commits SW to (54,7) in the same visit. Action 25 begins only
        // after this new stride settles; staging it at (55,6) is three cycles
        // late at the first observable tile boundary.
        Unit peon = unitById(world, 4);
        assertNotNull(peon, "XHuman 10 has no native-slot-1596 return peon");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 309) {
            mission.tick();
        }
        assertEquals(55, peon.tileX());
        assertEquals(6, peon.tileY());
        assertTrue(peon.returningToDepot());
        assertEquals(100, peon.carried());

        mission.tick();
        assertEquals(54, peon.tileX(),
                "action 24 must refill and commit on the residual-settle visit");
        assertEquals(7, peon.tileY());
        assertEquals(0, peon.battleNetOrderDelay(),
                "the outer point is not yet action 25's depot skirt");
    }

    @Test
    @DisplayName("xhuman 10's return route honors a surfaced worker's departure")
    void xhuman10ReturnRouteHonorsASurfacedWorkersDeparture() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Native slot 1436 / Java 164 surfaces at (15,116) under a 25-visit
        // ready Still whose queued next action is Return Goods. When slot 1434
        // / Java 166 plans north from (15,117), native treats that ready body
        // as a promised departure, writes a north-led route through it, then
        // refuses the still-occupied first byte. Fixture 318 is Move 2600/1,
        // collision one, route index twenty. Keeping every Still body solid
        // made Java wall-follow NW and move immediately.
        Unit returner = unitById(world, 166);
        Unit surfaced = unitById(world, 164);
        assertNotNull(returner, "XHuman 10 has no native-slot-1434 returner");
        assertNotNull(surfaced, "XHuman 10 has no native-slot-1436 blocker");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 317) {
            mission.tick();
        }
        assertEquals(15, returner.tileX());
        assertEquals(117, returner.tileY());
        assertEquals(Unit.Order.STILL, surfaced.order());
        assertEquals(15, surfaced.tileX());
        assertEquals(116, surfaced.tileY());
        assertTrue(surfaced.returningToDepot());
        assertEquals(100, surfaced.carried());
        assertTrue(surfaced.battleNetOrderDelay() > 0);
        assertTrue(surfaced.queuedReplacementPending());
        assertTrue(surfaced.hasQueuedOrders());
        assertEquals(Unit.QueuedOrderKind.RETURN_GOODS,
                surfaced.queuedOrders().getFirst().kind());

        mission.tick();
        assertEquals(15, returner.tileX(),
                "the direct north route must refuse instead of detouring NW");
        assertEquals(117, returner.tileY());
        assertEquals(0, returner.pathLength(),
                "the refused route projects native route index twenty");
        assertEquals(1, returner.battleNetCollisionCounter());
        assertEquals(2600, returner.battleNetSequenceOffset());
        assertEquals(1, returner.battleNetAnimationTimer());
    }

    @Test
    @DisplayName("xhuman 10's expansion peon receives the native eastern hall site")
    void xhuman10ExpansionPeonReceivesTheNativeEasternHallSite() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();
        Unit peon = unitById(world, 49);
        assertNotNull(peon, "XHuman 10 has no native-slot-1551 expansion peon");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        mission.tick();

        assertEquals(Unit.Order.BUILD, peon.order());
        assertNotNull(peon.pendingBuild());
        assertEquals("unit-great-hall", peon.pendingBuild().ident());
        assertEquals(40, peon.buildTileX(),
                "authenticated native action 28 stores priority-list hall "
                        + "top-left (40,79)");
        assertEquals(79, peon.buildTileY());
        assertEquals(40, peon.buildGoalX());
        assertEquals(79, peon.buildGoalY());
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
