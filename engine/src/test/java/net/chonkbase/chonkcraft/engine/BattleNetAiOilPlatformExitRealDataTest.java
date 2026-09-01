package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated AI oil-platform exit placement from retail BNE. */
class BattleNetAiOilPlatformExitRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("an XHuman 5 tanker chooses its platform before leaving the shipyard")
    void anXHuman5TankerUsesTheHiddenReadyPlatformForItsDepotExit() {
        Mission mission = loadMission("campaigns/human-exp/levelx05h");
        World world = mission.world();
        Unit tanker = unitById(world, 43);
        Unit shipyard = unitById(world, 41);
        Unit platform = unitById(world, 42);
        assertNotNull(tanker,
                "XHuman 5 has no Java tanker 43 / native slot 1557");
        assertNotNull(shipyard, "XHuman 5 has no p4 shipyard at 89,59");
        assertNotNull(platform, "XHuman 5 has no p4 platform at 99,63");

        tickThrough(mission, 434);
        assertTrue(tanker.removed(),
                "fixture 434 must still contain the tanker in its shipyard");
        assertSame(shipyard, tanker.worksite());
        assertNull(tanker.resourceUnit(),
                "the completed return order owns no remembered platform");

        mission.tick();
        assertEquals(435, fixtureCycle(world));
        assertEquals(92, tanker.tileX(),
                "the hidden ready callback selects the east shipyard face");
        assertEquals(60, tanker.tileY(),
                "the first legal east-face anchor is absolute-even");
        assertEquals(Unit.Order.STILL, tanker.order());
        assertTrue(tanker.battleNetDoubleStep());
        assertSame(platform, tanker.resourceUnit(),
                "native stores platform slot 1558 before the tanker surfaces");
        assertEquals(25, tanker.battleNetOrderDelay(),
                "raw action 23 remains behind the 25-cycle Still head");
        assertEquals(1, tanker.queuedOrders().size());
        assertEquals(Unit.QueuedOrderKind.HARVEST,
                tanker.queuedOrders().getFirst().kind());
        assertSame(platform, tanker.queuedOrders().getFirst().target());

        tickThrough(mission, 459);
        assertEquals(Unit.Order.STILL, tanker.order());
        assertEquals(92, tanker.tileX());
        assertEquals(60, tanker.tileY());
        mission.tick();
        assertEquals(Unit.Order.HARVEST, tanker.order(),
                "native promotes action 23 on fixture 460");
        tickThrough(mission, 462);
        mission.tick();
        assertEquals(94, tanker.tileX(),
                "native commits the first east stride on fixture 463");
        assertEquals(60, tanker.tileY());
    }

    @Test
    @DisplayName("an XOrc 7 loaded tanker pays the native first-refusal Move band")
    void anXOrc7LoadedTankerPaysTheFirstRefusalMoveBand() {
        Mission mission = loadMission("campaigns/orc-exp/levelx07o");
        World world = mission.world();
        Unit tanker = unitById(world, 13);
        assertNotNull(tanker,
                "XOrc 7 has no Java unit 13 / native tanker 1587");

        tickThrough(mission, 251);
        assertEquals(26, tanker.tileX());
        assertEquals(8, tanker.tileY());
        assertTrue(tanker.returningToDepot());
        assertEquals(100, tanker.carried());

        mission.tick();
        assertEquals(252, fixtureCycle(world));
        assertEquals(26, tanker.tileX(),
                "the occupied SW head must not be retried on the next visit");
        assertEquals(8, tanker.tileY());
        assertEquals(1, tanker.battleNetCollisionCounter(),
                "native raises unit+0x1d's collision nibble from zero to one");
        assertEquals(0, tanker.battleNetRefusals(),
                "the generic eight-visit naval refusal ladder does not own action 24");
        assertEquals(14, tanker.battleNetOrderDelay());
        assertEquals(15, tanker.battleNetAnimationTimer());
        assertEquals(6, tanker.pathLength(),
                "native retains SW,SW,W,SW,SW,NW behind cursor zero");
        assertEquals(Direction.fromDelta(-1, 1), tanker.peekHeading());

        tickThrough(mission, 266);
        assertEquals(26, tanker.tileX(),
                "the full Move band holds the loaded tanker through fixture 266");
        assertEquals(8, tanker.tileY());
        mission.tick();
        assertEquals(267, fixtureCycle(world));
        assertEquals(24, tanker.tileX(),
                "the preserved SW head commits when the paid band expires");
        assertEquals(10, tanker.tileY());
        assertEquals(1, tanker.battleNetCollisionCounter());
    }

    @Test
    @DisplayName("an Orc 7 loaded tanker refills its first exhausted return buffer inline")
    void anOrc7LoadedTankerRefillsItsFirstReturnBufferInline() {
        Mission mission = loadMission("campaigns/orc/level07o");
        World world = mission.world();
        Unit tanker = unitById(world, 68);
        Unit refinery = unitById(world, 64);
        assertNotNull(tanker, "Orc 7 has no Java unit 68 / native tanker 1532");
        assertNotNull(refinery, "Orc 7 has no human refinery at 50,35");

        tickThrough(mission, 219);
        assertEquals(56, tanker.tileX());
        assertEquals(50, tanker.tileY());
        assertTrue(tanker.returningToDepot());
        assertEquals(100, tanker.carried());
        assertSame(refinery, tanker.returnDepotGoal());
        assertTrue(tanker.routeSpent(),
                "native has consumed the one-byte NW return buffer on cycle 219");

        tickThrough(mission, 250);
        assertEquals(56, tanker.tileX());
        assertEquals(50, tanker.tileY());
        mission.tick();
        assertEquals(251, fixtureCycle(world));
        assertEquals(54, tanker.tileX(),
                "native refills NW,NW,N,N,N and commits NW on the residual-settle visit");
        assertEquals(48, tanker.tileY());
        assertEquals(0, tanker.waitCycles(),
                "action 24 must not serve the generic empty-route ten at open sea");
    }

    @Test
    @DisplayName("an Orc 7 loaded tanker repeats the inline return refill on its next trip")
    void anOrc7LoadedTankerRepeatsTheReturnRefillOnItsNextTrip() {
        Mission mission = loadMission("campaigns/orc/level07o");
        World world = mission.world();
        Unit tanker = unitById(world, 68);
        assertNotNull(tanker, "Orc 7 has no Java unit 68 / native tanker 1532");

        tickThrough(mission, 1029);
        assertEquals(56, tanker.tileX());
        assertEquals(50, tanker.tileY());
        assertTrue(tanker.routeSpent(),
                "the second authenticated trip consumes the same one-byte NW buffer");

        tickThrough(mission, 1060);
        mission.tick();
        assertEquals(1061, fixtureCycle(world));
        assertEquals(54, tanker.tileX(),
                "native repeats the inline NW refill on cycle 1061");
        assertEquals(48, tanker.tileY());
        assertEquals(0, tanker.waitCycles());
    }

    @Test
    @DisplayName("an XOrc 8 loaded tanker chooses the refinery over the shipyard")
    void anXOrc8LoadedTankerChoosesTheNativeRefinery() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/orc-exp/levelx08o";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        World world = mission.world();
        Unit tanker = unitById(world, 134);
        Unit shipyard = unitById(world, 150);
        Unit refinery = unitById(world, 155);
        assertNotNull(tanker,
                "XOrc 8 has no Java unit 134 / native tanker 1466");
        assertNotNull(shipyard, "XOrc 8 has no human shipyard at 83,69");
        assertNotNull(refinery, "XOrc 8 has no human refinery at 87,71");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 189) {
            mission.tick();
        }

        int hiddenShipyardTravel = world.unitReachableTravel(
                tanker, shipyard, 1);
        int hiddenRefineryTravel = world.unitReachableTravel(
                tanker, refinery, 1);
        assertSame(refinery,
                world.harvest.bestDepotByTravel(
                        tanker, UnitType.Resource.OIL, 1000),
                "native selects refinery slot 1445 before platform dropout; "
                        + "Java hidden straight/travel lengths were shipyard="
                        + tanker.distanceTo(shipyard) + "/"
                        + hiddenShipyardTravel + ", refinery="
                        + tanker.distanceTo(refinery) + "/"
                        + hiddenRefineryTravel);

        while (fixtureCycle(world) < 210) {
            mission.tick();
        }

        assertEquals(114, tanker.tileX());
        assertEquals(54, tanker.tileY());
        int shipyardTravel = world.unitReachableTravel(tanker, shipyard, 1);
        int refineryTravel = world.unitReachableTravel(tanker, refinery, 1);
        assertSame(refinery, tanker.returnDepotGoal(),
                "native slot 1466 targets refinery slot 1445 through cycle 210; "
                        + "Java travel lengths were shipyard=" + shipyardTravel
                        + ", refinery=" + refineryTravel);
        assertEquals(97, tanker.orderTargetX(),
                "native retains the refinery-corner SpreadUnit point through cycle 210");
        assertEquals(65, tanker.orderTargetY());

        while (fixtureCycle(world) < 218) {
            mission.tick();
        }
        assertEquals(112, tanker.tileX(),
                "native commits the first south-west refinery-route stride on cycle 218");
        assertEquals(56, tanker.tileY());
        assertEquals(89, tanker.orderTargetX(),
                "MoveToDepot replaces the spread point with the refinery edge");
        assertEquals(71, tanker.orderTargetY());

        while (fixtureCycle(world) < 250) {
            mission.tick();
        }
        assertEquals(110, tanker.tileX(),
                "native consumes the route's second south-west heading on cycle 250");
        assertEquals(58, tanker.tileY());
    }

    @Test
    @DisplayName("an XHuman 6 loaded tanker chooses the nearer shipyard")
    void anXHuman6LoadedTankerChoosesTheNativeShipyard() {
        Mission mission = loadMission("campaigns/human-exp/levelx06h");
        World world = mission.world();
        Unit tanker = unitById(world, 84);
        Unit refinery = unitById(world, 78);
        Unit shipyard = unitById(world, 81);
        assertNotNull(tanker,
                "XHuman 6 has no Java unit 84 / native tanker 1516");
        assertNotNull(refinery, "XHuman 6 has no orc refinery at 49,47");
        assertNotNull(shipyard, "XHuman 6 has no orc shipyard at 40,51");

        tickThrough(mission, 316);

        assertEquals(48, tanker.tileX());
        assertEquals(68, tanker.tileY());
        assertEquals(100, tanker.carried());
        assertTrue(tanker.returningToDepot());
        assertSame(shipyard, tanker.returnDepotGoal(),
                "native slot 1516 stores shipyard slot 1519 before its timed Still head; "
                        + "Java selected refinery " + refinery.id());

        tickThrough(mission, 343);
        assertEquals(48, tanker.tileX());
        assertEquals(68, tanker.tileY());
        mission.tick();
        assertEquals(344, fixtureCycle(world));
        assertEquals(46, tanker.tileX(),
                "the native shipyard route opens with a north-west doubled stride");
        assertEquals(66, tanker.tileY());
    }

    @Test
    @DisplayName("an Orc 8 tanker tests the exit anchor instead of its drawn hull")
    void anOrc8TankerUsesTheNativeOverlappingWestAnchor() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/orc/level08o";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        for (int cycle = 1; cycle < 155; cycle++) {
            mission.tick();
        }

        mission.tick();
        Unit tanker = at(mission.world(), "unit-human-oil-tanker", 84, 104);
        assertNotNull(tanker,
                "native slot 1482 surfaces on the platform's west anchor at fixture 155");
        assertEquals(Unit.Order.STILL, tanker.order());
        assertTrue(tanker.battleNetDoubleStep());
        assertEquals(0, (tanker.tileX() | tanker.tileY()) & 1);

        Unit platform = at(mission.world(), "unit-human-oil-platform", 85, 103);
        assertNotNull(platform);
        assertTrue(tanker.tileX() + tanker.type().tileWidth() > platform.tileX(),
                "the proof must retain native's visual hull/platform overlap");

        for (int cycle = 156; cycle <= 179; cycle++) {
            mission.tick();
            assertEquals(Unit.Order.STILL, tanker.order(),
                    "native's queued return must respect the ready delay on cycle " + cycle);
            assertEquals(84, tanker.tileX());
            assertEquals(104, tanker.tileY());
        }
        mission.tick();
        assertEquals(Unit.Order.RETURN_GOODS, tanker.order(),
                "native promotes the delayed return order on fixture cycle 180");
        assertEquals(84, tanker.tileX());
        assertEquals(104, tanker.tileY());

        mission.tick();
        mission.tick();
        assertEquals(84, tanker.tileX(),
                "native holds the route anchor through fixture cycle 182");
        assertEquals(104, tanker.tileY());
        mission.tick();
        assertEquals(84, tanker.tileX(),
                "native takes its first doubled north stride on fixture cycle 183");
        assertEquals(102, tanker.tileY());

        Unit southTanker = at(mission.world(), "unit-human-oil-tanker", 84, 106);
        assertNotNull(southTanker,
                "the neighboring native tanker is the non-overlap control");
        for (int cycle = 184; cycle <= 256; cycle++) {
            mission.tick();
            assertEquals(84, southTanker.tileX(),
                    "a clear south-face hull must not inherit the overlap rule on cycle "
                            + cycle);
            assertEquals(106, southTanker.tileY());
        }

        mission.tick();
        assertEquals(257, fixtureCycle(mission.world()));
        assertEquals(84, southTanker.tileX());
        assertEquals(104, southTanker.tileY(),
                "the paid north head commits after its refusal band");
        assertEquals(2, southTanker.pathLength());
        assertEquals(Direction.fromDelta(-1, -1),
                southTanker.peekHeading(),
                "the retained route next aims northwest through the crowded corner");

        while (fixtureCycle(mission.world()) < 288) {
            mission.tick();
        }
        assertEquals(84, southTanker.tileX());
        assertEquals(104, southTanker.tileY());
        assertEquals(0, southTanker.offsetX());
        assertEquals(2, southTanker.offsetY(),
                "fixture 288 still owes the final north residual pixels");
        assertEquals(11, southTanker.battleNetRefusals(),
                "the prior occupied north head leaves its sticky refusal generation");
        assertNotNull(at(mission.world(), "unit-human-destroyer", 82, 104),
                "the west side of the doubled northwest corner is occupied");
        assertNotNull(at(mission.world(), "unit-human-oil-tanker", 84, 102),
                "the north side of the doubled northwest corner is occupied");

        mission.tick();
        assertEquals(289, fixtureCycle(mission.world()));
        assertEquals(84, southTanker.tileX(),
                "native refuses a diagonal squeezed between two allied hulls");
        assertEquals(104, southTanker.tileY());
        assertEquals(0, southTanker.offsetX());
        assertEquals(0, southTanker.offsetY());
        assertEquals(0, southTanker.pathLength(),
                "FUN_004379e0 parks the stale northwest tail");
        assertEquals(12, southTanker.battleNetRefusals());
        assertEquals(14, southTanker.waitCycles(),
                "the already-paid refusal generation owns a complete Move band");

        while (fixtureCycle(mission.world()) < 303) {
            mission.tick();
        }
        assertEquals(84, southTanker.tileX());
        assertEquals(104, southTanker.tileY(),
                "the corner refusal holds through timer one");
        mission.tick();
        assertEquals(304, fixtureCycle(mission.world()));
        assertEquals(84, southTanker.tileX());
        assertEquals(102, southTanker.tileY(),
                "the fresh route takes north after the adjacent tanker vacates");
        assertEquals(Direction.fromDelta(0, -1),
                southTanker.lastStepHeading());
    }

    @Test
    @DisplayName("an XOrc 11 tanker uses BNE's unrounded east platform face")
    void anXOrc11TankerUsesTheUnroundedEastPlatformFace() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/orc-exp/levelx11o";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (mission.world().cycle() - BNE_INITIALIZATION_TICKS < 160) {
            mission.tick();
        }

        Unit tanker = at(mission.world(), "unit-human-oil-tanker", 8, 20);
        assertNotNull(tanker,
                "native slot 1552 surfaces on the platform's east face at fixture 160");
        assertEquals(Unit.Order.STILL, tanker.order());
        assertTrue(tanker.battleNetDoubleStep(),
                "the even east-face anchor keeps the doubled naval lattice");
    }

    @Test
    @DisplayName("an XHuman 8 tanker leaves its platform on BNE's even anchor grid")
    void anXHuman8TankerLeavesItsPlatformOnTheEvenAnchorGrid() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx08h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        for (int cycle = 1; cycle < 258; cycle++) {
            mission.tick();
        }
        mission.tick();

        Unit tanker = at(mission.world(), "unit-orc-oil-tanker", 66, 58);
        assertNotNull(tanker,
                "native slot 1538 surfaces south of its platform on fixture cycle 258");
        assertEquals(Unit.Order.STILL, tanker.order(),
                "the platform exit exposes the naval ready boundary");
        assertTrue(tanker.battleNetDoubleStep(),
                "the contained tanker must retain native unit+0x1c bit 1");
        assertEquals(0, (tanker.tileX() | tanker.tileY()) & 1,
                "native retains its doubled-grid bit and rejects odd dropout anchors");

        for (int cycle = 259; cycle <= 282; cycle++) {
            mission.tick();
            assertEquals(Unit.Order.STILL, tanker.order(),
                    "the naval ready window must hold through fixture cycle " + cycle);
            assertEquals(66, tanker.tileX());
            assertEquals(58, tanker.tileY());
        }
        mission.tick();
        assertEquals(Unit.Order.RETURN_GOODS, tanker.order(),
                "native promotes queued action 24 on fixture cycle 283");
        mission.tick();
        mission.tick();
        mission.tick();
        assertEquals(64, tanker.tileX(),
                "native takes its first doubled west stride on fixture cycle 286");
        assertEquals(58, tanker.tileY());

        for (int cycle = 287; cycle < 318; cycle++) {
            mission.tick();
            assertEquals(64, tanker.tileX(),
                    "the west stride must drain through fixture cycle " + cycle);
            assertEquals(58, tanker.tileY());
        }
        mission.tick();
        assertEquals(62, tanker.tileX(),
                "native takes the refinery wall route's northwest stride on cycle 318");
        assertEquals(56, tanker.tileY(),
                "the marked refinery skirt must beat the straight blocked-goal prefix");

        for (int cycle = 319; cycle <= 381; cycle++) {
            mission.tick();
        }
        assertEquals(60, tanker.tileX(),
                "the final doubled west stride lands on the outer refinery skirt");
        assertEquals(56, tanker.tileY());
        int oilBefore = mission.world().player(tanker.player())
                .get(UnitType.Resource.OIL);

        mission.tick();
        assertTrue(tanker.isOnMap(),
                "native action 25 remains visible on refinery visit 382");
        assertTrue(tanker.battleNetResourceApproachStaged(),
                "the doubled outer skirt must arm native's three-visit depot stage");
        mission.tick();
        assertTrue(tanker.isOnMap(),
                "native action 25 remains visible on refinery visit 383");
        mission.tick();
        assertTrue(tanker.isOnMap(),
                "native action 25 remains visible on refinery visit 384");
        mission.tick();
        assertFalse(tanker.isOnMap(),
                "native banks and enters hidden action 26 on fixture cycle 385");
        assertEquals(oilBefore + 125,
                mission.world().player(tanker.player()).get(UnitType.Resource.OIL),
                "the refinery's 125-percent income must land on the entry cycle");
    }

    private static Unit at(World world, String ident, int x, int y) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.isAlive() && unit.isOnMap() && unit.tileX() == x
                    && unit.tileY() == y && unit.type() != null
                    && ident.equals(unit.type().ident())) {
                return unit;
            }
        }
        return null;
    }

    private static Unit unitById(World world, int id) {
        return world.unitsSnapshot().stream()
                .filter(unit -> unit.id() == id)
                .findFirst().orElse(null);
    }

    private static Mission loadMission(String map) {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        return mission;
    }

    private static void tickThrough(Mission mission, int fixtureCycle) {
        while (fixtureCycle(mission.world()) < fixtureCycle) {
            mission.tick();
        }
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }
}
