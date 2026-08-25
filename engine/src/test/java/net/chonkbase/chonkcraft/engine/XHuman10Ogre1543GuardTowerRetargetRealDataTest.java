package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * XHuman 10's ogre 1543 settles a paid chase residual next to guard tower
 * 1537 and replaces its old footman quarry on fixture 154.
 *
 * <p>The authenticated 2.02b executable exposes fresh Attack construction
 * {@code 643/3,2,1}, then parks on {@code 643/23}. It does not enter the
 * attack body or damage the tower on fixture 162.
 */
class XHuman10Ogre1543GuardTowerRetargetRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("xhuman 10's struck guard tower recruits footman 1529 on its idle boundary")
    void xhuman10StruckGuardTowerRecruitsFootman1529OnItsIdleBoundary() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Authenticated native slots: idle footman 1529 / Java 71, struck
        // human guard tower 1537 / Java 63, and ogre aggressor 1538 / Java 62.
        // FUN_0040a9d0 banks action 12 at fixture 168. The footman's current
        // action remains Still until its idle program reaches the fixture-173
        // boundary, where next_order becomes current order.
        Unit footman = unitById(world, 71);
        Unit tower = unitById(world, 63);
        Unit ogre = unitById(world, 62);
        assertNotNull(footman, "XHuman 10 has no native-slot-1529 footman");
        assertNotNull(tower, "XHuman 10 has no native-slot-1537 guard tower");
        assertNotNull(ogre, "XHuman 10 has no native-slot-1538 ogre");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 167) {
            mission.tick();
        }
        assertEquals(130, tower.hitPoints());
        assertEquals(Unit.Order.STILL, footman.order());
        assertNull(footman.battleNetPendingHelpAttack());

        mission.tick();
        assertSame(ogre, footman.battleNetPendingHelpAttack(),
                "fixture 168 must bank the tower's aggressor on footman 1529");
        assertEquals(Unit.Order.STILL, footman.order(),
                "the banked order must wait for the footman's idle boundary");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 172) {
            mission.tick();
        }
        assertEquals(Unit.Order.STILL, footman.order());
        assertSame(ogre, footman.battleNetPendingHelpAttack());

        mission.tick();
        assertEquals(Unit.Order.ATTACK, footman.order());
        assertSame(ogre, footman.target(),
                "fixture 173 must promote the exact aggressor native queued");
    }

    @Test
    @DisplayName("xhuman 10 footman 1529 rescans at its queued hit-help handoff")
    void xhuman10Footman1529RescansAtItsQueuedHitHelpHandoff() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Native footman 1529 / Java 71 first accepts ogre 1538 / Java 62
        // from the guard tower's hit-help offer. On the queued Attack's
        // timer-one handoff, AutoSelectTarget owns a fresh scan and replaces
        // that source with ogre 1548 / Java 52 before committing NW. The
        // replacement order remains queued behind the first pixel residual,
        // so native pays Attack construction 2539/3,2,1 at (97,59) before it
        // consumes the cached N heading.
        Unit footman = unitById(world, 71);
        Unit offeredOgre = unitById(world, 62);
        Unit selectedOgre = unitById(world, 52);
        assertNotNull(footman, "XHuman 10 has no native-slot-1529 footman");
        assertNotNull(offeredOgre, "XHuman 10 has no native-slot-1538 ogre");
        assertNotNull(selectedOgre, "XHuman 10 has no native-slot-1548 ogre");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 173) {
            mission.tick();
        }
        assertSame(offeredOgre, footman.target(),
                "fixture 173 must still expose the queued hit source");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 176) {
            mission.tick();
        }
        assertSame(selectedOgre, footman.target(),
                "timer-one handoff must freshly select native ogre 1548");
        assertEquals(97, footman.tileX());
        assertEquals(59, footman.tileY());
        assertEquals(2485, footman.battleNetSequenceOffset());
        assertEquals(1, footman.battleNetAnimationTimer());

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 192) {
            mission.tick();
        }
        assertSame(selectedOgre, footman.target());
        assertEquals(97, footman.tileX());
        assertEquals(59, footman.tileY(),
                "the first residual must settle before cached N is spent");
        assertEquals(2539, footman.battleNetSequenceOffset());
        assertEquals(3, footman.battleNetAnimationTimer());

        mission.tick();
        assertEquals(2, footman.battleNetAnimationTimer());
        mission.tick();
        assertEquals(1, footman.battleNetAnimationTimer());
        mission.tick();
        assertEquals(97, footman.tileX());
        assertEquals(58, footman.tileY(),
                "fixture 195 must consume the retained north heading");
        assertEquals(2485, footman.battleNetSequenceOffset());
        assertEquals(1, footman.battleNetAnimationTimer());
    }

    @Test
    @DisplayName("xhuman 10 footman 1529 pays its exhausted-route retarget on settle")
    void xhuman10Footman1529PaysItsExhaustedRouteRetargetOnSettle() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        Unit footman = unitById(world, 71);
        Unit oldOgre = unitById(world, 52);
        Unit replacementOgre = unitById(world, 57);
        assertNotNull(footman, "XHuman 10 has no native-slot-1529 footman");
        assertNotNull(oldOgre, "XHuman 10 has no native-slot-1548 ogre");
        assertNotNull(replacementOgre, "XHuman 10 has no native-slot-1543 ogre");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 210) {
            mission.tick();
        }
        assertSame(oldOgre, footman.target());
        assertEquals(2, footman.offsetY(),
                "fixture 210 retains the final two northbound pixels");
        assertEquals(1, footman.pathLength(),
                "the exhausted-route projection still exposes its stale tail");
        assertEquals(0x7d55368c, world.randomSeed());

        mission.tick();
        assertSame(replacementOgre, footman.target(),
                "the settle boundary must choose native ogre 1543");
        assertEquals(0, footman.pathLength(),
                "native route index twenty discards the stale tail");
        assertEquals(2539, footman.battleNetSequenceOffset());
        assertEquals(3, footman.battleNetAnimationTimer(),
                "the paid retarget still opens cold Attack construction");
        assertEquals(0x102f11d5, world.randomSeed(),
                "the exhausted-route settle owns FUN_004234b0 immediately");
    }

    @Test
    @DisplayName("xhuman 10 knight 1489 drops an unreplaced close-hit source after construction")
    void xhuman10Knight1489DropsUnreplacedCloseHitSourceAfterConstruction() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        Unit knight = unitById(world, 111);
        Unit offeredGrunt = unitById(world, 118);
        assertNotNull(knight, "XHuman 10 has no native-slot-1489 knight");
        assertNotNull(offeredGrunt, "XHuman 10 has no native-slot-1482 grunt");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 211) {
            mission.tick();
        }
        assertSame(offeredGrunt, knight.target());
        assertEquals(Unit.Order.ATTACK, knight.order());
        assertEquals(83, knight.tileX());
        assertEquals(89, knight.tileY());
        assertEquals(1922, knight.battleNetSequenceOffset());
        assertEquals(1, knight.battleNetAnimationTimer());

        mission.tick();
        assertEquals(Unit.Order.STILL, knight.order(),
                "the temporary close-hit order must not become a detour chase");
        assertNull(knight.target());
        assertEquals(83, knight.tileX());
        assertEquals(89, knight.tileY());
        assertEquals(1869, knight.battleNetSequenceOffset());
        assertEquals(1, knight.battleNetAnimationTimer());
        assertEquals(0x35ed03ea, world.battleNetRandomSeed(),
                "the released hit-help order must dispatch fresh Still now");
    }

    @Test
    @DisplayName("xhuman 10 ogre 1548 constructs before striking its replacement tower")
    void xhuman10Ogre1548ConstructsBeforeStrikingItsReplacementTower() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Native ogre 1548 / Java 52 finishes the current chase tile on
        // fixture 167 and replaces footman 1529 with guard tower 1537. Its
        // remaining cached headings are discarded; the replacement owns cold
        // Attack construction 643/3,2,1 and then the committed 23-count hold.
        Unit ogre = unitById(world, 52);
        Unit tower = unitById(world, 63);
        Unit oldFootman = unitById(world, 71);
        assertNotNull(ogre, "XHuman 10 has no native-slot-1548 ogre");
        assertNotNull(tower, "XHuman 10 has no native-slot-1537 guard tower");
        assertNotNull(oldFootman, "XHuman 10 has no native-slot-1529 footman");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 166) {
            mission.tick();
        }
        assertSame(oldFootman, ogre.target());

        mission.tick();
        assertSame(tower, ogre.target());
        assertEquals(643, ogre.battleNetSequenceOffset(),
                "the in-range replacement must open cold Attack construction");
        assertEquals(3, ogre.battleNetAnimationTimer());
        mission.tick();
        assertEquals(2, ogre.battleNetAnimationTimer());
        mission.tick();
        assertEquals(1, ogre.battleNetAnimationTimer());
        mission.tick();
        assertEquals(643, ogre.battleNetSequenceOffset());
        assertEquals(23, ogre.battleNetAnimationTimer(),
                "the paid constructor must enter the native committed hold");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 175) {
            mission.tick();
        }
        assertEquals(126, tower.hitPoints(),
                "ogre 1548 must not land Java's phantom fixture-175 blow");
    }

    @Test
    @DisplayName("xhuman 10 ogre 1543 pays the melee approach hold after retargeting the guard tower")
    void xhuman10Ogre1543PaysTheMeleeApproachHoldAfterRetargetingTheGuardTower() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        Unit ogre = unitById(world, 57);
        Unit tower = unitById(world, 63);
        assertNotNull(ogre, "XHuman 10 has no native-slot-1543 ogre");
        assertNotNull(tower, "XHuman 10 has no native-slot-1537 guard tower");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 154) {
            mission.tick();
        }

        assertEquals(98, ogre.tileX());
        assertEquals(57, ogre.tileY());
        assertSame(tower, ogre.target(),
                "the residual-settle scan must select the adjacent guard tower");
        assertEquals(Unit.Order.ATTACK, ogre.order());
        assertEquals(643, ogre.battleNetSequenceOffset(),
                "native restarts ogre Attack construction on the retarget visit");
        assertEquals(3, ogre.battleNetAnimationTimer(),
                "native exposes construction timer three at fixture 154");

        mission.tick();
        assertEquals(2, ogre.battleNetAnimationTimer());
        mission.tick();
        assertEquals(1, ogre.battleNetAnimationTimer());
        mission.tick();
        assertEquals(643, ogre.battleNetSequenceOffset());
        assertEquals(23, ogre.battleNetAnimationTimer(),
                "the paid constructor must enter the native melee approach hold");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 162) {
            mission.tick();
        }
        assertEquals(130, tower.hitPoints(),
                "ogre 1543 must not land Java's phantom fixture-162 hit");
    }

    @Test
    @DisplayName("xhuman 10 ogre 1538 keeps the full repeated-swing recovery against the guard tower")
    void xhuman10Ogre1538KeepsTheFullRepeatedSwingRecoveryAgainstTheGuardTower() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Native ogre 1538 / Java 62 lands its first tower blow on fixture
        // 168, then keeps the complete Attack recovery and next windup. Its
        // parallel presentation reaches hit() again on fixture 183 while the
        // retail sequence is still at 666/4. That visual callback must not
        // collapse the sequence merely because the victim is a building.
        Unit ogre = unitById(world, 62);
        Unit tower = unitById(world, 63);
        assertNotNull(ogre, "XHuman 10 has no native-slot-1538 ogre");
        assertNotNull(tower, "XHuman 10 has no native-slot-1537 guard tower");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 168) {
            mission.tick();
        }
        assertSame(tower, ogre.target());
        assertEquals(126, tower.hitPoints(),
                "ogre 1538's first authenticated tower blow lands on fixture 168");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 183) {
            mission.tick();
        }
        assertEquals(666, ogre.battleNetSequenceOffset());
        assertEquals(3, ogre.battleNetAnimationTimer(),
                "presentation hit must leave the native recovery countdown intact");
        assertEquals(126, tower.hitPoints(),
                "fixture 183 is presentation only, not a retail damage boundary");
        assertEquals(0x54d4b09e, world.battleNetRandomSeed(),
                "a recovery-frame presentation hit owns no native damage roll");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 187) {
            mission.tick();
        }
        assertEquals(123, tower.hitPoints(),
                "ogre 1543 supplies the separate authenticated fixture-187 hit");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 191) {
            mission.tick();
        }
        assertEquals(123, tower.hitPoints(),
                "ogre 1538 must not invent another recovery-frame hit at fixture 191");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 193) {
            mission.tick();
        }
        assertEquals(121, tower.hitPoints(),
                "ogre 1538's next retail OP10 lands on fixture 193");
    }

    @Test
    @DisplayName("xhuman 10's ballista returns through Still before reacquiring")
    void xhuman10BallistaTailUsesTheNativeSiegeStillPulse() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();
        Unit ballista = unitById(world, 117);
        Unit corpse = unitById(world, 105);
        Unit replacement = unitById(world, 118);
        Unit laterReplacement = unitById(world, 125);
        assertNotNull(ballista, "XHuman 10 has no native-slot-1483 ballista");
        assertNotNull(corpse, "XHuman 10 has no native-slot-1495 grunt");
        assertNotNull(replacement, "XHuman 10 has no native-slot-1482 grunt");
        assertNotNull(laterReplacement,
                "XHuman 10 has no native-slot-1486 later grunt");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 226) {
            mission.tick();
        }
        assertEquals(Unit.Order.ATTACK, ballista.order());
        assertEquals(1716, ballista.battleNetSequenceOffset());
        assertEquals(1, ballista.battleNetAnimationTimer());
        assertEquals("unit-grunt", corpse.type().ident());

        mission.tick();
        assertEquals(Unit.Order.STILL, ballista.order());
        assertNull(ballista.target());
        assertEquals(1609, ballista.battleNetSequenceOffset());
        assertEquals(3, ballista.battleNetAnimationTimer());
        assertEquals("unit-human-dead-body", corpse.type().ident(),
                "native grunt corpse changes to shared land-body type 105");
        mission.tick();
        assertEquals(2, ballista.battleNetAnimationTimer());
        mission.tick();
        assertEquals(1, ballista.battleNetAnimationTimer());
        mission.tick();
        assertEquals(Unit.Order.ATTACK, ballista.order());
        assertSame(replacement, ballista.target());
        assertEquals(1699, ballista.battleNetSequenceOffset());
        assertEquals(3, ballista.battleNetAnimationTimer());
        mission.tick();
        assertSame(replacement, ballista.target());
        assertEquals(2, ballista.battleNetAnimationTimer());
        mission.tick();
        assertSame(replacement, ballista.target());
        assertEquals(1, ballista.battleNetAnimationTimer());
        mission.tick();
        assertEquals(Unit.Order.STILL, ballista.order(),
                "dying stationary-siege construction completes through Still");
        assertNull(ballista.target());
        assertEquals(1609, ballista.battleNetSequenceOffset());
        assertEquals(3, ballista.battleNetAnimationTimer());
        mission.tick();
        assertEquals(Unit.Order.STILL, ballista.order());
        assertEquals(2, ballista.battleNetAnimationTimer());
        mission.tick();
        assertEquals(Unit.Order.STILL, ballista.order());
        assertEquals(1, ballista.battleNetAnimationTimer());
        mission.tick();
        assertEquals(Unit.Order.ATTACK, ballista.order());
        assertSame(laterReplacement, ballista.target());
        assertEquals(1699, ballista.battleNetSequenceOffset());
        assertEquals(3, ballista.battleNetAnimationTimer());
        mission.tick();
        assertEquals(2, ballista.battleNetAnimationTimer());
        mission.tick();
        assertEquals(1, ballista.battleNetAnimationTimer());
        mission.tick();
        assertEquals(Unit.Order.STILL, ballista.order());
        assertNull(ballista.target());
        assertEquals(1609, ballista.battleNetSequenceOffset());
        assertEquals(3, ballista.battleNetAnimationTimer());
    }

    @Test
    @DisplayName("xhuman 10's mobile catapult retargets directly at its attack tail")
    void xhuman10MobileCatapultTailKeepsTheNativeFreeScan() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();
        Unit catapult = unitById(world, 113);
        Unit oldTarget = unitById(world, 108);
        Unit replacement = unitById(world, 107);
        assertNotNull(catapult, "XHuman 10 has no native-slot-1487 catapult");
        assertNotNull(oldTarget, "XHuman 10 has no native-slot-1492 old quarry");
        assertNotNull(replacement, "XHuman 10 has no native-slot-1493 replacement");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 202) {
            mission.tick();
        }
        assertEquals(Unit.Order.ATTACK, catapult.order());
        assertSame(oldTarget, catapult.target());
        assertEquals(540, catapult.battleNetSequenceOffset());
        assertEquals(1, catapult.battleNetAnimationTimer());

        mission.tick();
        assertEquals(Unit.Order.ATTACK, catapult.order(),
                "mobile action 12 must not borrow stationary siege's Still pulse");
        assertSame(replacement, catapult.target());
        assertEquals(503, catapult.battleNetSequenceOffset());
        assertEquals(3, catapult.battleNetAnimationTimer());
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
