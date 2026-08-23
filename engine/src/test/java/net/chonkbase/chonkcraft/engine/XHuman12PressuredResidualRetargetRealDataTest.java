package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated idle-choice ownership at a pressured melee retarget. */
class XHuman12PressuredResidualRetargetRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("pressured residual retargets pay their native idle choices")
    void pressuredResidualRetargetsPayTheirNativeIdleChoices() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        // Native slots 1513/1516 (Java 87/84) both pass through
        // FUN_0040ad50 on fixture 70 before their residual handoff opens
        // Attack construction. Their ordered draws are what leave the async
        // stream on 0x34a060d8 for the following footman damage roll.
        Unit collided = unitById(world, 87);
        Unit routeParked = unitById(world, 84);
        Unit knight = unitById(world, 125);
        Unit tower = unitById(world, 115);
        Unit damagedGrunt = unitById(world, 98);
        Unit paidHeadGrunt = unitById(world, 92);
        Unit paidBandGrunt = unitById(world, 90);
        Unit followingGrunt = unitById(world, 87);
        Unit hardRefusalGrunt = unitById(world, 94);
        Unit hardRefusedResidualRetargetGrunt = unitById(world, 97);
        Unit settledBandGrunt = unitById(world, 124);
        Unit cannonTower = unitById(world, 117);
        Unit damagedAtNextBoundary = unitById(world, 95);
        Unit hardRefusedRetarget = unitById(world, 86);
        Unit boundedPrefixGrunt = unitById(world, 80);
        Unit paidConstructionGrunt = unitById(world, 88);
        Unit hardRefusalResidualGrunt = unitById(world, 83);
        Unit postParkRefillGrunt = unitById(world, 100);
        Unit openingPatrolOgre = unitById(world, 244);
        Unit crowdedApproachOgre = unitById(world, 206);
        Unit saturatedResidualGrunt = unitById(world, 108);
        Unit footman = unitById(world, 123);
        Unit exhaustedApproachGrunt = unitById(world, 99);
        Unit heldRetargetGrunt = unitById(world, 152);
        Unit heldRetargetKnight = unitById(world, 154);
        Unit damagedGuardTower = unitById(world, 230);
        Unit staleOfferGrunt = unitById(world, 132);
        Unit replacementFootman = unitById(world, 151);
        Unit stableBuildingResidualGrunt = unitById(world, 130);
        Unit diagonalMomentumGrunt = unitById(world, 93);
        assertNotNull(collided);
        assertNotNull(routeParked);
        assertNotNull(knight);
        assertNotNull(tower);
        assertNotNull(damagedGrunt);
        assertNotNull(paidHeadGrunt);
        assertNotNull(paidBandGrunt);
        assertNotNull(followingGrunt);
        assertNotNull(hardRefusalGrunt);
        assertNotNull(hardRefusedResidualRetargetGrunt);
        assertNotNull(settledBandGrunt);
        assertNotNull(cannonTower);
        assertNotNull(damagedAtNextBoundary);
        assertNotNull(hardRefusedRetarget);
        assertNotNull(boundedPrefixGrunt);
        assertNotNull(paidConstructionGrunt);
        assertNotNull(hardRefusalResidualGrunt);
        assertNotNull(postParkRefillGrunt);
        assertNotNull(openingPatrolOgre);
        assertNotNull(crowdedApproachOgre);
        assertNotNull(saturatedResidualGrunt);
        assertNotNull(footman);
        assertNotNull(exhaustedApproachGrunt);
        assertNotNull(heldRetargetGrunt);
        assertNotNull(heldRetargetKnight);
        assertNotNull(damagedGuardTower);
        assertNotNull(staleOfferGrunt);
        assertNotNull(replacementFootman);
        assertNotNull(stableBuildingResidualGrunt);
        assertNotNull(diagonalMomentumGrunt);

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 71) {
            mission.tick();
            if (fixtureCycle(world) == 69) {
                assertSame(heldRetargetKnight, heldRetargetGrunt.target());
                assertEquals(2539,
                        heldRetargetGrunt.battleNetSequenceOffset());
                assertEquals(23,
                        heldRetargetGrunt.battleNetAnimationTimer(),
                        "a melee OP0-hold retarget must enter a fresh native body hold");
            }
            if (fixtureCycle(world) == 70) {
                assertSame(knight, collided.target());
                assertSame(tower, routeParked.target());
                assertEquals(2539, collided.battleNetSequenceOffset());
                assertEquals(2539, routeParked.battleNetSequenceOffset());
                assertEquals(3, collided.battleNetAnimationTimer());
                assertEquals(3, routeParked.battleNetAnimationTimer());
                assertEquals(0x34a060d8, world.battleNetRandomSeed(),
                        "both native land-idle choices must be present in table order");
            }
        }

        assertEquals(43, damagedGrunt.hitPoints(),
                "the cycle-71 melee roll must use native async result 26126");

        mission.tick();
        assertEquals(72, fixtureCycle(world));
        assertSame(footman, paidHeadGrunt.target(),
                "the paid residual boundary free-scans onto the footman");
        assertEquals(37, paidHeadGrunt.tileX());
        assertEquals(39, paidHeadGrunt.tileY(),
                "the new route must spend the old approved NE head immediately");
        assertEquals(0, paidHeadGrunt.battleNetOrderDelay(),
                "a free paid head must not manufacture a refusal hold");

        mission.tick();
        assertEquals(73, fixtureCycle(world));
        assertEquals(34, paidBandGrunt.tileX());
        assertEquals(39, paidBandGrunt.tileY(),
                "a fully paid cooperative band must park, then replan instead of sleeping forever");
        assertEquals(34, followingGrunt.tileX());
        assertEquals(40, followingGrunt.tileY(),
                "the earlier replanning grunt must release the native square for the following attacker");
        assertEquals(39, routeParked.tileX(),
                "a building retarget must re-arm its native construction instead of walking");
        assertEquals(39, routeParked.tileY());
        assertEquals(28, hardRefusalGrunt.tileX());
        assertEquals(39, hardRefusalGrunt.tileY(),
                "hard-refusal provenance must retain its established wait lifecycle");
        assertSame(cannonTower, settledBandGrunt.target());
        assertEquals(22, settledBandGrunt.tileX());
        assertEquals(46, settledBandGrunt.tileY(),
                "a settle-visit retarget must spend its approved old head before the replacement tail");
        assertEquals(2539, routeParked.battleNetSequenceOffset());
        assertEquals(3, routeParked.battleNetAnimationTimer(),
                "an exhausted approach must re-arm through native Attack construction");

        mission.tick();
        assertEquals(74, fixtureCycle(world));
        assertEquals(52, damagedAtNextBoundary.hitPoints(),
                "the completed-band land-idle draw must precede the native melee roll");
        assertSame(cannonTower, hardRefusedRetarget.target());
        assertEquals(28, hardRefusedRetarget.tileX());
        assertEquals(37, hardRefusedRetarget.tileY(),
                "a hard-refused old head must yield to the fresh native route");
        assertEquals(0xd1802f1b, world.battleNetRandomSeed(),
                "a hard-refusal retarget must not steal the first projectile draw");
        assertEquals(36, boundedPrefixGrunt.tileX());
        assertEquals(39, boundedPrefixGrunt.tileY(),
                "a bounded empty-route prefix must park its synthetic Java tail");

        mission.tick();
        assertEquals(75, fixtureCycle(world));
        assertEquals(35, boundedPrefixGrunt.tileX());
        assertEquals(39, boundedPrefixGrunt.tileY(),
                "the visit after the park must replan and take BNE's west heading");
        assertEquals(3, boundedPrefixGrunt.battleNetCollisionCounter(),
                "the native collision nibble survives the bounded west step");
        assertEquals(5, boundedPrefixGrunt.peekHeading(),
                "the bounded formation route retains its captured SW tail");
        assertEquals(33, paidConstructionGrunt.tileX());
        assertEquals(39, paidConstructionGrunt.tileY(),
                "a fully paid pre-step band must not charge a second wait after construction");
        assertEquals(26, hardRefusalResidualGrunt.tileX());
        assertEquals(39, hardRefusalResidualGrunt.tileY(),
                "hard-refusal residual pressure must spend its free component instead of constructing");

        mission.tick();
        assertEquals(76, fixtureCycle(world));
        assertEquals(2539,
                exhaustedApproachGrunt.battleNetSequenceOffset());
        assertEquals(3,
                exhaustedApproachGrunt.battleNetAnimationTimer(),
                "a newly exhausted approach must enter the shared native retry loop");
        assertEquals(2539, routeParked.battleNetSequenceOffset());
        assertEquals(3, routeParked.battleNetAnimationTimer(),
                "a blocked retry must pay and re-arm every third cycle");
        assertEquals(11, openingPatrolOgre.tileX());
        assertEquals(88, openingPatrolOgre.tileY(),
                "land Patrol attack construction must park the old route before the replacement NE step");
        assertEquals(1, openingPatrolOgre.pathLength(),
                "the opening Attack stride retains its cooperative cached tail while its pixels drain");
        assertEquals(true,
                openingPatrolOgre.battleNetLandPatrolAttackRoutePending(),
                "the Patrol handoff owns the route until the opening stride settles");

        mission.tick();
        assertEquals(77, fixtureCycle(world));
        assertEquals(35, postParkRefillGrunt.tileX());
        assertEquals(40, postParkRefillGrunt.tileY(),
                "a cooperatively blocked post-park refill must enter BNE's fresh refusal band");
        assertEquals(14, postParkRefillGrunt.battleNetAnimationTimer(),
                "the fresh replacement route must retain native Move timer fourteen");

        mission.tick();
        assertEquals(78, fixtureCycle(world));
        assertEquals(0xe2319ac4, world.randomSeed(),
                "a due melee-loop draw must survive an OP0 retarget early return");

        mission.tick();
        assertEquals(79, fixtureCycle(world));
        assertEquals(90, heldRetargetKnight.hitPoints(),
                "the replacement hold must prevent an early melee hit");
        assertEquals(13, heldRetargetGrunt.battleNetAnimationTimer());

        while (fixtureCycle(world) < 82) {
            mission.tick();
        }
        assertEquals(65, damagedGuardTower.hitPoints(),
                "the cycle-82 tower hit must retain native damage ownership");
        assertEquals(85, heldRetargetKnight.hitPoints(),
                "active-order idle cadence must hand the knight native damage result 5336");
        assertEquals(0xc6c78b0f, world.battleNetRandomSeed(),
                "the complete cycle-82 asynchronous stream must match BNE");

        while (fixtureCycle(world) < 84) {
            mission.tick();
        }
        assertSame(replacementFootman, staleOfferGrunt.target(),
                "the settled residual must replace its stale tower offer");
        assertEquals(23, staleOfferGrunt.tileX());
        assertEquals(54, staleOfferGrunt.tileY(),
                "a stale attack-back offer must not rewrite the replacement route's native SE head");

        while (fixtureCycle(world) < 87) {
            mission.tick();
        }
        assertEquals(29, hardRefusalGrunt.tileX());
        assertEquals(39, hardRefusalGrunt.tileY(),
                "a saturated collision must retain the first native wall-face recovery step");
        assertEquals(2539,
                hardRefusedResidualRetargetGrunt.battleNetSequenceOffset());
        assertEquals(3,
                hardRefusedResidualRetargetGrunt.battleNetAnimationTimer(),
                "a hard-refused residual retarget returns through BNE idle into Attack construction");
        assertEquals(0x257C043E, world.battleNetRandomSeed(),
                "the residual retarget idle draw must leave BNE's cycle-87 asynchronous seed");

        mission.tick();
        assertEquals(88, fixtureCycle(world));
        assertEquals(11, openingPatrolOgre.tileX());
        assertEquals(88, openingPatrolOgre.tileY(),
                "the Patrol handoff must park its stale tail instead of taking a second north step");
        assertEquals(0, openingPatrolOgre.pathLength());
        assertEquals(643, openingPatrolOgre.battleNetSequenceOffset());
        assertEquals(3, openingPatrolOgre.battleNetAnimationTimer(),
                "the residual boundary must return through BNE active-order idle and Attack construction");
        assertEquals(18, heldRetargetGrunt.hitPoints(),
                "the following melee must inherit BNE damage result 19791");
        assertEquals(0xD4AB8912, world.battleNetRandomSeed(),
                "both newly authenticated idle callbacks must preserve BNE's cycle-88 asynchronous stream");

        while (fixtureCycle(world) < 90) {
            mission.tick();
        }
        assertEquals(11, crowdedApproachOgre.tileX());
        assertEquals(85, crowdedApproachOgre.tileY(),
                "a surrounded approach must re-enter Attack instead of sliding sideways");
        assertEquals(643, crowdedApproachOgre.battleNetSequenceOffset());
        assertEquals(3, crowdedApproachOgre.battleNetAnimationTimer());
        assertEquals(27, saturatedResidualGrunt.tileX());
        assertEquals(38, saturatedResidualGrunt.tileY(),
                "a saturated first residual keeps BNE's full Move refusal band");
        assertEquals(2482,
                saturatedResidualGrunt.battleNetSequenceOffset());
        assertEquals(14,
                saturatedResidualGrunt.battleNetAnimationTimer());
        assertEquals(34, collided.tileX());
        assertEquals(39, collided.tileY(),
                "a fresh long residual parks before consuming the replacement north head");

        mission.tick();
        assertEquals(91, fixtureCycle(world));
        assertEquals(60, damagedGuardTower.hitPoints(),
                "cycle-90 projectile motion and both active-order idle retries must leave the melee pair BNE's two damage rolls");
        assertEquals(22, stableBuildingResidualGrunt.tileX());
        assertEquals(42, stableBuildingResidualGrunt.tileY(),
                "a retained building residual starts its native timer immediately and moves north on the authenticated boundary");
        assertEquals(30, diagonalMomentumGrunt.tileX());
        assertEquals(36, diagonalMomentumGrunt.tileY(),
                "a settled one-collision formation route repeats its paid diagonal momentum");
        assertEquals(38, paidHeadGrunt.tileX());
        assertEquals(38, paidHeadGrunt.tileY(),
                "the mirrored-diagonal residual keeps the already-approved northeast stride");
        assertEquals(33, paidConstructionGrunt.tileX());
        assertEquals(39, paidConstructionGrunt.tileY());
        assertEquals(2539, paidConstructionGrunt.battleNetSequenceOffset());
        assertEquals(3, paidConstructionGrunt.battleNetAnimationTimer(),
                "a collision-three residual backtracks into the shared Attack retry without gliding");
        assertEquals(36, boundedPrefixGrunt.tileX());
        assertEquals(39, boundedPrefixGrunt.tileY(),
                "the fully paid formation prefix must spend its east recovery step");
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
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
