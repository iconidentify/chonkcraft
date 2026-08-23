package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated saturated-collision ownership in a crowded attack formation. */
class XHuman12SaturatedFormationCollisionRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("a saturated moving ally remains solid after its wake step")
    void saturatedMovingAllyRemainsSolidAfterItsWakeStep() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        // Native slot 1496 / Java 104 wakes from a crowded refusal with the
        // high collision nibble at four.  It keeps that provenance through
        // the successful SE stride, parks its blocked residual at five, and
        // retains five through the replacement NE step.  Slot 1504 / Java 96
        // must therefore continue to see it as a solid formation member and
        // hold (31,41), rather than path north through its vacated tile.
        Unit departing = unitById(world, 104);
        Unit following = unitById(world, 96);
        Unit recurringPark = unitById(world, 118);
        Unit firstGenerationFollower = unitById(world, 83);
        Unit retargetedBuildingChaser = unitById(world, 93);
        Unit retargetedMobileChaser = unitById(world, 80);
        Unit saturatedTerminator = unitById(world, 105);
        Unit saturatedRetarget = unitById(world, 90);
        assertNotNull(departing);
        assertNotNull(following);
        assertNotNull(recurringPark);
        assertNotNull(firstGenerationFollower);
        assertNotNull(retargetedBuildingChaser);
        assertNotNull(retargetedMobileChaser);
        assertNotNull(saturatedTerminator);
        assertNotNull(saturatedRetarget);

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 90) {
            mission.tick();
        }
        assertEquals(31, departing.tileX());
        assertEquals(40, departing.tileY());
        assertEquals(4, departing.battleNetCollisionCounter(),
                "a successful saturated wake must retain native 0x40");

        mission.tick();
        assertEquals(91, fixtureCycle(world));
        assertEquals(30, retargetedBuildingChaser.tileX());
        assertEquals(36, retargetedBuildingChaser.tileY());
        assertEquals(0, retargetedBuildingChaser.battleNetCollisionCounter(),
                "a committed building retarget clears native collision ownership");
        assertEquals(36, retargetedMobileChaser.tileX());
        assertEquals(39, retargetedMobileChaser.tileY());
        assertEquals(0, retargetedMobileChaser.battleNetCollisionCounter(),
                "a committed mobile retarget clears native collision ownership");

        while (fixtureCycle(world) < 106) {
            mission.tick();
        }
        assertEquals(31, departing.tileX());
        assertEquals(40, departing.tileY());
        assertEquals(5, departing.battleNetCollisionCounter(),
                "parking the blocked residual advances native 0x40 to 0x50");

        mission.tick();
        assertEquals(107, fixtureCycle(world));
        assertEquals(32, departing.tileX());
        assertEquals(39, departing.tileY());
        assertEquals(5, departing.battleNetCollisionCounter(),
                "the replacement stride keeps saturated formation provenance");
        assertEquals(31, following.tileX());
        assertEquals(41, following.tileY(),
                "the following attacker must not detour through its ally");

        mission.tick();
        assertEquals(108, fixtureCycle(world));
        assertEquals(31, following.tileX());
        assertEquals(41, following.tileY(),
                "BNE keeps the crowded attacker in its retry loop");

        while (fixtureCycle(world) < 110) {
            mission.tick();
        }
        assertEquals(27, recurringPark.tileX());
        assertEquals(39, recurringPark.tileY());
        assertEquals(4, recurringPark.battleNetCollisionCounter(),
                "the second blocked residual advances the same native generation");
        assertEquals(31, retargetedBuildingChaser.tileX());
        assertEquals(37, retargetedBuildingChaser.tileY());
        assertEquals(37, retargetedMobileChaser.tileX());
        assertEquals(38, retargetedMobileChaser.tileY());
        assertEquals(27, firstGenerationFollower.tileX());
        assertEquals(40, firstGenerationFollower.tileY());

        mission.tick();
        assertEquals(111, fixtureCycle(world));
        assertEquals(28, recurringPark.tileX());
        assertEquals(40, recurringPark.tileY(),
                "the parked generation redraws and takes BNE's southeast step");
        assertEquals(27, firstGenerationFollower.tileX());
        assertEquals(39, firstGenerationFollower.tileY(),
                "the first refused generation refills on its settle visit");

        // Native slot 1495 / Java 105 later drains its final NW residual
        // carrying collision generation five. Its refusal counter happens to
        // read one again, but that cannot make this a first-generation route:
        // retail advances the route cursor 1 -> 20 and leaves Move-start/1 on
        // fixture 121. An immediate refill stepped SE and stole the cell before
        // the eastern formation member could make its authenticated move.
        while (fixtureCycle(world) < 120) {
            mission.tick();
        }
        assertEquals(29, saturatedTerminator.tileX());
        assertEquals(39, saturatedTerminator.tileY());
        assertEquals(5, saturatedTerminator.battleNetCollisionCounter(),
                "the final residual still belongs to saturated generation five");
        assertEquals(2, saturatedTerminator.battleNetRefusals(),
                "the local refusal count remains below the saturated collision generation");
        assertEquals(36, saturatedRetarget.tileX());
        assertEquals(38, saturatedRetarget.tileY());
        assertEquals(4, saturatedRetarget.battleNetCollisionCounter(),
                "the replacement scan begins with paid formation generation four");
        assertEquals(3, saturatedRetarget.battleNetPathStepsTaken(),
                "three approved route legs prove this is not fresh pressure");

        mission.tick();
        assertEquals(121, fixtureCycle(world));
        assertEquals(29, saturatedTerminator.tileX(),
                "a saturated route terminator parks for the native RI20 visit");
        assertEquals(39, saturatedTerminator.tileY(),
                "the replacement southeast heading cannot spend on the settle visit");
        assertEquals(0, saturatedTerminator.pathLength(),
                "the parked native cursor exposes no live Java heading");
        assertEquals(6, saturatedTerminator.battleNetCollisionCounter(),
                "the RI20 park advances saturated formation ownership to 0x60");
        assertEquals(2482, saturatedTerminator.battleNetSequenceOffset());
        assertEquals(1, saturatedTerminator.battleNetAnimationTimer(),
                "the terminator visit remains on native Move-start/1");
        assertEquals(37, saturatedRetarget.tileX(),
                "a saturated residual retarget commits its replacement southeast step");
        assertEquals(39, saturatedRetarget.tileY());
        assertNotNull(saturatedRetarget.target());
        assertEquals(123, saturatedRetarget.target().id(),
                "the native free scan replaces the knight with the footman");
        assertEquals(0, saturatedRetarget.battleNetCollisionCounter(),
                "successful replacement NewPath clears paid formation pressure");

        mission.tick();
        assertEquals(122, fixtureCycle(world));
        assertEquals(29, saturatedTerminator.tileX());
        assertEquals(39, saturatedTerminator.tileY());
        assertEquals(0, saturatedTerminator.battleNetCollisionCounter(),
                "active-order Still clears the completed 0x60 generation");
        assertEquals(2539, saturatedTerminator.battleNetSequenceOffset());
        assertEquals(3, saturatedTerminator.battleNetAnimationTimer(),
                "the post-terminator visit enters native Attack construction");
        assertEquals((int) 0x9a5a9fb5L, world.battleNetRandomSeed(),
                "the active-order callback must retain its authenticated idle draw");

        while (fixtureCycle(world) < 125) {
            mission.tick();
        }
        assertEquals(30, saturatedTerminator.tileX(),
                "timer one hands the fresh southeast route back to Move");
        assertEquals(40, saturatedTerminator.tileY());
    }

    @Test
    @DisplayName("a saturated cardinal residual refills immediately but a diagonal parks")
    void saturatedCardinalResidualRefillsImmediatelyButDiagonalParks() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit cardinalResidual = unitById(world, 94);
        Unit diagonalResidual = unitById(world, 105);
        assertNotNull(cardinalResidual);
        assertNotNull(diagonalResidual);

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 102) {
            mission.tick();
        }
        assertEquals(Direction.fromDelta(1, 0), cardinalResidual.lastStepHeading(),
                "native slot 1506 is settling a saturated east retry");
        mission.tick();
        assertEquals(103, fixtureCycle(world));
        assertEquals(30, cardinalResidual.tileX(),
                "the saturated cardinal retry refills and commits on settle");
        assertEquals(39, cardinalResidual.tileY());

        while (fixtureCycle(world) < 120) {
            mission.tick();
        }
        assertEquals(Direction.fromDelta(-1, -1),
                diagonalResidual.lastStepHeading(),
                "native slot 1495 is settling a saturated northwest residual");
        mission.tick();
        assertEquals(121, fixtureCycle(world));
        assertEquals(29, diagonalResidual.tileX(),
                "a saturated diagonal terminator owns the RI20 park");
        assertEquals(39, diagonalResidual.tileY());
    }

    @Test
    @DisplayName("paid attack handoffs preserve the saturated battle line")
    void paidAttackHandoffsPreserveTheSaturatedBattleLine() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        // Native slot 1470 / Java 130 redraws after a paid hit-help handoff.
        // Its route must retain the generation-four ally at 24,40 as a wall,
        // yielding N,N instead of cutting N,NE through the battle line.
        Unit rearFormationGrunt = unitById(world, 130);
        // Native slot 1476 / Java 124 has already paid Attack construction,
        // spent the first byte of a full replacement route and is settling
        // that residual. It returns to Attack 2539/3 before another N byte
        // may be spent.
        Unit paidRouteGrunt = unitById(world, 124);
        assertNotNull(rearFormationGrunt);
        assertNotNull(paidRouteGrunt);

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 142) {
            mission.tick();
        }
        assertEquals(23, rearFormationGrunt.tileX());
        assertEquals(41, rearFormationGrunt.tileY());
        assertEquals(23, paidRouteGrunt.tileX());
        assertEquals(47, paidRouteGrunt.tileY());

        mission.tick();
        assertEquals(143, fixtureCycle(world));
        assertEquals(23, rearFormationGrunt.tileX(),
                "the rear grunt must not cut northeast through its saturated ally");
        assertEquals(40, rearFormationGrunt.tileY(),
                "the native hit-help route spends its second north heading");
        assertEquals(23, paidRouteGrunt.tileX());
        assertEquals(47, paidRouteGrunt.tileY(),
                "the paid full route yields to Attack before spending a second N");
        assertEquals(19, paidRouteGrunt.pathLength(),
                "Attack construction retains the rest of the full route");
        assertEquals(2539, paidRouteGrunt.battleNetSequenceOffset());
        assertEquals(3, paidRouteGrunt.battleNetAnimationTimer(),
                "the residual handoff re-enters native Attack construction");
    }

    @Test
    @DisplayName("an accepted saturated park keeps its partial wall byte")
    void acceptedSaturatedParkKeepsItsPartialWallByte() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit crowdedGrunt = unitById(world, 118);
        assertNotNull(crowdedGrunt);

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 176) {
            mission.tick();
        }
        assertEquals(31, crowdedGrunt.tileX());
        assertEquals(40, crowdedGrunt.tileY());
        assertEquals(6, crowdedGrunt.battleNetCollisionCounter(),
                "the accepted park must reach its saturated refill generation");
        assertEquals(0, crowdedGrunt.battleNetRefusals());

        mission.tick();
        assertEquals(177, fixtureCycle(world));
        assertEquals(31, crowdedGrunt.tileX());
        assertEquals(41, crowdedGrunt.tileY(),
                "BNE retains and commits the first south wall byte");
        assertEquals(Direction.fromDelta(0, 1),
                crowdedGrunt.lastStepHeading());
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
