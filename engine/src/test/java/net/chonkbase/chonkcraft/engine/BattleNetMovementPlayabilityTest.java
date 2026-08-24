package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Player-command movement scenarios using the authenticated retail roster. */
class BattleNetMovementPlayabilityTest {

    private record Fixture(GameData data, World world, CommandApplier commands) {}

    private static GameData data() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        return new GameData(assets);
    }

    private static Fixture fixture(GameMap map) {
        GameData data = data();
        Player[] players = new Player[Player.MAX];
        for (int player = 0; player < players.length; player++) {
            players[player] = new Player(player,
                    player == 0 ? PudMap.PlayerType.PERSON : PudMap.PlayerType.NOBODY,
                    PudMap.Race.HUMAN);
        }
        World world = new World(map, players);
        world.setUnitTypes(data.unitTypes().types());
        world.setUpgrades(data.upgrades().upgrades());
        world.setMissileTypes(data.missiles().types());
        CommandApplier commands = new CommandApplier(
                world, new ArrayList<>(data.unitTypes().types().values()));
        return new Fixture(data, world, commands);
    }

    private static GameMap map(int width, int height, long flags) {
        GameMap map = new GameMap(width, height, new Tileset());
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                map.field(x, y).setFlags(flags);
            }
        }
        return map;
    }

    private static Unit place(Fixture fixture, String ident, int x, int y) {
        UnitType type = fixture.data().unitTypes().types().get(ident);
        assertNotNull(type, "retail roster has no " + ident);
        Unit unit = fixture.world().createUnit(type, 0, x, y);
        assertNotNull(unit, "could not place " + ident + " at " + x + "," + y);
        return unit;
    }

    private static boolean playerMoveInFlight(Unit unit) {
        return unit.order() == Unit.Order.MOVE
                || unit.battleNetPlayerCommandMove()
                || unit.hasQueuedOrders()
                || unit.queuedReplacementPending();
    }

    private static void assertAcceptedMove(Unit unit, String message) {
        assertTrue(unit.order() == Unit.Order.MOVE || playerMoveInFlight(unit),
                message);
    }

    private static void runToRest(World world, Unit unit, int limit) {
        for (int cycle = 0; cycle < limit && playerMoveInFlight(unit); cycle++) {
            world.tick();
        }
        assertEquals(Unit.Order.STILL, unit.order(),
                "the player move never reached a completed state: saved="
                        + unit.savedOrder() + ", scout="
                        + unit.battleNetScoutPatrol() + ", playerMove="
                        + unit.battleNetPlayerCommandMove() + ", at="
                        + unit.tileX() + "," + unit.tileY() + ", goal="
                        + unit.orderTargetX() + "," + unit.orderTargetY());
        assertTrue(!unit.hasQueuedOrders() && !unit.queuedReplacementPending(),
                "the player move never popped its queued dest");
    }

    @Test
    @DisplayName("a retail footman obeys a player move around a friendly formation")
    void footmanRoutesAroundFriendlyOccupancyAndCompletes() {
        Fixture fixture = fixture(map(32, 32, TileFlag.LAND_ALLOWED));
        // This is the player-visible junction between planning and walking:
        // the planner must regard stationary friends as walls, while the
        // mover must consume the resulting detour instead of retrying the
        // blocked straight line forever.
        for (int y = 3; y < 30; y++) {
            place(fixture, "unit-footman", 16, y);
        }
        Unit footman = place(fixture, "unit-footman", 8, 16);

        fixture.commands().apply(GameCommand.move(0, footman.id(), 24, 16));
        assertAcceptedMove(footman, "the wire move command was refused");
        runToRest(fixture.world(), footman, 4_000);

        assertTrue(footman.tileX() >= 24,
                "the footman did not route around the formation to the destination: "
                        + footman.tileX() + "," + footman.tileY());
    }

    @Test
    @DisplayName("retail naval and air movers obey their distinct terrain domains")
    void navalAndAirMovementCompleteThroughPlayerCommands() {
        GameMap sea = map(32, 24, TileFlag.WATER_ALLOWED);
        Fixture naval = fixture(sea);
        Unit destroyer = place(naval, "unit-human-destroyer", 4, 8);
        naval.commands().apply(GameCommand.move(0, destroyer.id(), 24, 16));
        assertAcceptedMove(destroyer, "the destroyer move was refused");
        runToRest(naval.world(), destroyer, 4_000);
        assertTrue(destroyer.tileX() >= 22 && destroyer.tileY() >= 14,
                "the destroyer did not complete its commanded sea passage");

        GameMap divided = map(32, 24, TileFlag.LAND_ALLOWED);
        for (int y = 0; y < divided.height(); y++) {
            divided.field(15, y).setFlags(
                    TileFlag.LAND_ALLOWED | TileFlag.FOREST | TileFlag.UNPASSABLE);
        }
        Fixture air = fixture(divided);
        Unit gryphon = place(air, "unit-gryphon-rider", 4, 8);
        // Retail large movers use an even lattice; command an even anchor so
        // completion, rather than the odd-goal widening policy, is measured.
        air.commands().apply(GameCommand.move(0, gryphon.id(), 24, 8));
        assertAcceptedMove(gryphon, "the gryphon move was refused");
        runToRest(air.world(), gryphon, 4_000);
        assertTrue(gryphon.tileX() >= 24,
                "the aircraft treated an impassable ground wall as flight terrain: "
                        + gryphon.tileX() + "," + gryphon.tileY());
    }

    @Test
    @DisplayName("a gryphon promotes Move after its committed attack releases")
    void gryphonAttackToMoveKeepsTheCommittedSwingAndThenObeysTheClick() {
        // Pinned BNE 2.02b's GiveOrder/ReleaseOrders boundary keeps an
        // unbreakable current animation at Orders[0] and places the flushed
        // replacement behind it. This is the player-visible air-unit case:
        // replacing ATTACK semantically while its long gryphon animation was
        // still current erased the attack state underneath that animation.
        // The flyer then appeared to ignore Move and a following Attack until
        // Stop happened to reset the presentation body.
        Fixture fixture = fixture(map(40, 32, TileFlag.LAND_ALLOWED));
        fixture.world().setAllied(0, 1, false);
        fixture.world().fog().revealAll(0);
        Unit gryphon = place(fixture, "unit-gryphon-rider", 8, 16);
        UnitType footmanType = fixture.data().unitTypes().types().get("unit-footman");
        assertNotNull(footmanType, "retail roster has no footman target");
        Unit target = fixture.world().createUnit(footmanType, 1, 10, 16);
        assertNotNull(target, "could not place the gryphon's target");

        assertTrue(fixture.commands().apply(GameCommand.attack(
                        0, gryphon.id(), target.id())),
                "the gryphon refused the opening attack");
        for (int cycle = 0; cycle < 500
                && !(gryphon.order() == Unit.Order.ATTACK
                        && gryphon.animation().unbreakable()); cycle++) {
            fixture.world().tick();
        }
        assertEquals(Unit.Order.ATTACK, gryphon.order(),
                "the fixture never entered the gryphon attack");
        assertTrue(gryphon.animation().unbreakable(),
                "the fixture never reached the committed gryphon swing");

        assertTrue(fixture.commands().apply(GameCommand.move(
                        0, gryphon.id(), 24, 16)),
                "the replacement Move was refused");
        assertEquals(Unit.Order.ATTACK, gryphon.order(),
                "Move replaced the order beneath the committed attack body");
        assertTrue(gryphon.queuedReplacementPending(),
                "the BNE flush replacement was not queued behind the swing");

        runToRest(fixture.world(), gryphon, 4_000);
        assertTrue(gryphon.tileX() >= 24,
                "the gryphon never obeyed Move after its committed attack: "
                        + gryphon.tileX() + "," + gryphon.tileY());

        Unit secondTarget = fixture.world().createUnit(footmanType, 1, 27, 16);
        assertNotNull(secondTarget, "could not place the post-Move target");
        int startingHealth = secondTarget.hitPoints();
        assertTrue(fixture.commands().apply(GameCommand.attack(
                        0, gryphon.id(), secondTarget.id())),
                "the gryphon refused Attack after completing Move");
        for (int cycle = 0; cycle < 4_000
                && secondTarget.hitPoints() == startingHealth; cycle++) {
            fixture.world().tick();
        }
        assertTrue(secondTarget.hitPoints() < startingHealth,
                "the gryphon sat after its post-Move Attack command");
    }

    @Test
    @DisplayName("a player move permanently replaces a footman's patrol")
    void aPlayerMovePermanentlyReplacesAFootmanPatrol() {
        Fixture fixture = fixture(map(40, 32, TileFlag.LAND_ALLOWED));
        Unit footman = place(fixture, "unit-footman", 8, 16);

        assertTrue(fixture.commands().apply(GameCommand.patrol(
                        0, footman.id(), 28, 16)),
                "the player Patrol command was refused");
        for (int cycle = 0; cycle < 200
                && !(footman.order() == Unit.Order.PATROL
                        && (footman.isMoving() || footman.offsetX() != 0)); cycle++) {
            fixture.world().tick();
        }
        assertEquals(Unit.Order.PATROL, footman.order(),
                "the fixture never entered the player's Patrol");

        assertTrue(fixture.commands().apply(GameCommand.move(
                        0, footman.id(), 8, 24)),
                "the replacement Move command was refused");
        runToRest(fixture.world(), footman, 4_000);
        int settledX = footman.tileX();
        int settledY = footman.tileY();
        for (int cycle = 0; cycle < 120; cycle++) {
            fixture.world().tick();
        }

        assertEquals(Unit.Order.STILL, footman.order(),
                "the old player Patrol resumed after the replacement Move");
        assertEquals(null, footman.savedOrder(),
                "a player Patrol was retained as an autonomous scout order");
        assertEquals(settledX, footman.tileX(),
                "the footman left the player's replacement destination");
        assertEquals(settledY, footman.tileY(),
                "the footman left the player's replacement destination");
    }

    @Test
    @DisplayName("Stop breaks a footman's patrol after its committed pixels land")
    void stopBreaksAFootmanPatrolAfterItsCommittedPixelsLand() {
        Fixture fixture = fixture(map(40, 32, TileFlag.LAND_ALLOWED));
        Unit footman = place(fixture, "unit-footman", 8, 16);

        assertTrue(fixture.commands().apply(GameCommand.patrol(
                        0, footman.id(), 28, 16)),
                "the player Patrol command was refused");
        for (int cycle = 0; cycle < 200
                && !(footman.order() == Unit.Order.PATROL
                        && (footman.isMoving() || footman.offsetX() != 0)); cycle++) {
            fixture.world().tick();
        }
        assertEquals(Unit.Order.PATROL, footman.order(),
                "the fixture never entered Patrol");
        assertTrue(fixture.commands().apply(GameCommand.stop(0, footman.id())),
                "the player Stop command was refused");

        for (int cycle = 0; cycle < 200
                && (footman.order() != Unit.Order.STILL
                        || footman.offsetX() != 0 || footman.offsetY() != 0); cycle++) {
            fixture.world().tick();
        }
        assertEquals(Unit.Order.STILL, footman.order(),
                "Stop never released the Patrol order");
        assertEquals(0, footman.offsetX(),
                "Stop froze the committed Patrol pixels horizontally");
        assertEquals(0, footman.offsetY(),
                "Stop froze the committed Patrol pixels vertically");
        assertEquals(null, footman.savedOrder(),
                "Stop left a Patrol continuation behind");
        assertFalse(footman.hasBattleNetPendingPatrol(),
                "Stop left an autonomous Patrol constructor armed");
    }

    @Test
    @DisplayName("a completed compass walk leaves no leftover pixels")
    void aCompletedCompassWalkLeavesNoLeftoverPixels() {
        // Commanded Human 1 footman 1592 and Human 12 gryphon 1500 both
        // go Still on the dest tile the same cycle as native, but Java
        // used to keep a 5- or 20-pixel leftover. Retail PF_REACHED
        // wipes the displacement.
        Fixture land = fixture(map(32, 32, TileFlag.LAND_ALLOWED));
        Unit footman = place(land, "unit-footman", 20, 16);
        assertTrue(land.commands().apply(GameCommand.move(0, footman.id(), 16, 12)),
                "the footman refused a north-west walk");
        runToRest(land.world(), footman, 4_000);
        assertEquals(0, footman.offsetX(),
                "the footman stood still with leftover pixels "
                        + footman.offsetX() + "," + footman.offsetY());
        assertEquals(0, footman.offsetY(),
                "the footman stood still with leftover pixels "
                        + footman.offsetX() + "," + footman.offsetY());

        Fixture air = fixture(map(32, 32, TileFlag.LAND_ALLOWED));
        Unit gryphon = place(air, "unit-gryphon-rider", 12, 16);
        assertTrue(air.commands().apply(GameCommand.move(0, gryphon.id(), 20, 16)),
                "the gryphon refused an east walk");
        runToRest(air.world(), gryphon, 4_000);
        assertEquals(0, gryphon.offsetX(),
                "the gryphon stood still with leftover pixels "
                        + gryphon.offsetX() + "," + gryphon.offsetY());
        assertEquals(0, gryphon.offsetY(),
                "the gryphon stood still with leftover pixels "
                        + gryphon.offsetX() + "," + gryphon.offsetY());
    }

    @Test
    @DisplayName("a Still defender finishes an orphaned half-tile combat step")
    void aStillDefenderFinishesAnOrphanedCombatStep() {
        // The Human expansion 3 playtest save captured two footmen in this
        // exact state: combat had returned them to Still, but Moving and a
        // half-tile IX/IY displacement survived.  Still never normally walks,
        // so they remained visibly frozen there while later attacks and
        // sounds continued underneath.
        Fixture fixture = fixture(map(16, 16, TileFlag.LAND_ALLOWED));
        Unit footman = place(fixture, "unit-footman", 8, 8);
        footman.setOrder(Unit.Order.STILL);
        footman.setOffset(-32, -32);
        footman.setWalkHolding(true);

        boolean showedWalkFrame = false;
        for (int cycle = 0; cycle < 100 && footman.walkHolding(); cycle++) {
            fixture.world().tick();
            showedWalkFrame |= footman.frame() != 0;
        }

        assertFalse(footman.walkHolding(),
                "the Still defender kept the orphaned Moving flag forever");
        assertEquals(0, footman.offsetX());
        assertEquals(0, footman.offsetY());
        assertTrue(showedWalkFrame,
                "the frozen step teleported instead of showing its remaining walk");
    }

    @Test
    @DisplayName("a player move into forest settles on the first tree's approach")
    void aPlayerMoveIntoForestSettlesOnTheFirstTreesApproach() {
        // Authenticated Orc 1 commanded fixture: peon 1594 at 25,18 is told
        // to walk to 30,18. That click is forest. Retail stores order point
        // 28,18 -- the first tree on the ray -- packs NE,E, and is Still on
        // 27,17 by cycle 40. Java used to keep walking east on y=18 and stay
        // on Move at the window's end.
        GameMap woods = map(32, 32, TileFlag.LAND_ALLOWED);
        for (int y = 16; y <= 19; y++) {
            for (int x = 28; x <= 31; x++) {
                woods.field(x, y).setFlags(
                        TileFlag.LAND_ALLOWED | TileFlag.FOREST | TileFlag.UNPASSABLE);
            }
        }
        Fixture fixture = fixture(woods);
        Unit peon = place(fixture, "unit-peon", 25, 18);

        assertTrue(fixture.commands().apply(GameCommand.move(0, peon.id(), 30, 18)),
                "the peon refused a point move into the trees");
        assertEquals(28, peon.orderTargetX(),
                "retail projects a forest click onto the first tree on the ray");
        assertEquals(18, peon.orderTargetY(),
                "retail projects a forest click onto the first tree on the ray");
        runToRest(fixture.world(), peon, 80);

        assertEquals(27, peon.tileX(),
                "the peon should stand on the tree's approach, not keep walking: "
                        + peon.tileX() + "," + peon.tileY());
        assertEquals(17, peon.tileY(),
                "the peon should take the forest wall-follow onto 27,17: "
                        + peon.tileX() + "," + peon.tileY());
    }

    @Test
    @DisplayName("a player move into water stores the first water square")
    void aPlayerMoveIntoWaterStoresTheFirstWaterSquare() {
        // The same BNE line that projects a forest click also projects a
        // shoreline click. A footman at 25,18 told to walk to 30,18 across
        // a water wall that begins at 28,18 keeps order point 28,18 -- the
        // first square it cannot enter -- and stays on land.
        GameMap shore = map(32, 32, TileFlag.LAND_ALLOWED);
        for (int y = 16; y <= 19; y++) {
            for (int x = 28; x <= 31; x++) {
                shore.field(x, y).setFlags(TileFlag.WATER_ALLOWED);
            }
        }
        Fixture fixture = fixture(shore);
        Unit footman = place(fixture, "unit-footman", 25, 18);

        assertTrue(fixture.commands().apply(GameCommand.move(0, footman.id(), 30, 18)),
                "the footman refused a point move into the water");
        assertEquals(28, footman.orderTargetX(),
                "retail projects a water click onto the first blocked square");
        assertEquals(18, footman.orderTargetY(),
                "retail projects a water click onto the first blocked square");
        runToRest(fixture.world(), footman, 80);

        assertTrue(fixture.world().map.field(footman.tileX(), footman.tileY())
                        .isLandPassable(),
                "the soldier should stand on the shore, not keep walking into water: "
                        + footman.tileX() + "," + footman.tileY());
        assertTrue(Math.max(Math.abs(footman.tileX() - 28),
                Math.abs(footman.tileY() - 18)) <= 1,
                "the soldier should stop beside the first water square: "
                        + footman.tileX() + "," + footman.tileY());
    }

    @Test
    @DisplayName("a player ship move onto land stores the first land square")
    void aPlayerShipMoveOntoLandStoresTheFirstLandSquare() {
        // Domain projection is not only land-into-trees. A destroyer at
        // 25,18 told to sail to 30,18 across a land wall that begins at
        // 28,18 keeps order point 28,18 -- the first square the hull cannot
        // enter -- and stays on water.
        GameMap sea = map(32, 32, TileFlag.WATER_ALLOWED);
        for (int y = 16; y <= 19; y++) {
            for (int x = 28; x <= 31; x++) {
                sea.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        Fixture fixture = fixture(sea);
        Unit destroyer = place(fixture, "unit-human-destroyer", 25, 18);

        assertTrue(fixture.commands().apply(GameCommand.move(0, destroyer.id(), 30, 18)),
                "the destroyer refused a point move onto land");
        assertEquals(28, destroyer.orderTargetX(),
                "retail projects a land click onto the first blocked square");
        assertEquals(18, destroyer.orderTargetY(),
                "retail projects a land click onto the first blocked square");
        runToRest(fixture.world(), destroyer, 80);

        assertTrue(fixture.world().map.field(destroyer.tileX(), destroyer.tileY())
                        .isWaterPassable(),
                "the ship should stand on the water, not keep sailing onto land: "
                        + destroyer.tileX() + "," + destroyer.tileY());
        assertTrue(Math.max(Math.abs(destroyer.tileX() - 28),
                Math.abs(destroyer.tileY() - 18)) <= 1,
                "the ship should stop beside the first land square: "
                        + destroyer.tileX() + "," + destroyer.tileY());
    }

    @Test
    @DisplayName("a human-1 grunt commanded east stands on the clicked square")
    void aHuman1GruntCommandedEastStandsOnTheClickedSquare() {
        // i9beef commanded fixture command-campaign-human-human01-pud-ground-e:
        // cycle 5 move unit 1588 x 24 y 31. Native starts at 20,31, first
        // steps on cycle 8, and is Still on 24,31 at cycle 72. The exe sha256
        // is the pinned 2.02b digest.
        GameData data = data();
        Mission mission = data.loadMission("campaigns/human/level01h", 0);
        Assumptions.assumeTrue(mission != null, "Human 1 is not in the pack");
        World world = mission.world();
        CommandApplier commands = new CommandApplier(
                world, new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);

        Unit walker = null;
        for (Unit unit : world.units()) {
            if (unit.isAlive() && unit.isOnMap()
                    && unit.tileX() == 20 && unit.tileY() == 31
                    && unit.player() == 0) {
                walker = unit;
                break;
            }
        }
        assertNotNull(walker, "Human 1 has no player unit on 20,31");

        assertTrue(commands.apply(GameCommand.move(0, walker.id(), 24, 31)),
                "the Human 1 walker refused the commanded east move");
        for (int cycle = 0; cycle < 80; cycle++) {
            world.tick();
        }
        assertEquals(24, walker.tileX(),
                "retail stands on the clicked square 24,31, not "
                        + walker.tileX() + "," + walker.tileY());
        assertEquals(31, walker.tileY(),
                "retail stands on the clicked square 24,31, not "
                        + walker.tileX() + "," + walker.tileY());
        assertEquals(Unit.Order.STILL, walker.order(),
                "retail is Still on 24,31 by cycle 72; Java is still "
                        + walker.order());
    }

    @Test
    @DisplayName("an orc-1 peon commanded onto open ground stands on the click")
    void anOrc1PeonCommandedOntoOpenGroundStandsOnTheClick() {
        // Fresh i9beef capture, pinned 2.02b. Cycle 5 move peon 1594 to
        // 22,18 -- west, off the trees. Native first steps on cycle 8 and
        // is Still on 22,18 at cycle 56.
        GameData data = data();
        Mission mission = data.loadMission("campaigns/orc/level01o", 0);
        Assumptions.assumeTrue(mission != null, "Orc 1 is not in the pack");
        World world = mission.world();
        CommandApplier commands = new CommandApplier(
                world, new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);

        Unit peon = null;
        for (Unit unit : world.units()) {
            if (unit.isAlive() && unit.isOnMap()
                    && unit.tileX() == 25 && unit.tileY() == 18
                    && unit.player() == 0) {
                peon = unit;
                break;
            }
        }
        assertNotNull(peon, "Orc 1 has no player peon on 25,18");

        assertTrue(commands.apply(GameCommand.move(0, peon.id(), 22, 18)),
                "the Orc 1 peon refused the open-ground move");
        for (int cycle = 0; cycle < 80; cycle++) {
            world.tick();
        }
        assertEquals(22, peon.tileX(),
                "retail stands on the clicked open square 22,18, not "
                        + peon.tileX() + "," + peon.tileY());
        assertEquals(18, peon.tileY(),
                "retail stands on the clicked open square 22,18, not "
                        + peon.tileX() + "," + peon.tileY());
        assertEquals(Unit.Order.STILL, peon.order(),
                "retail is Still on 22,18 by cycle 56; Java is still "
                        + peon.order());
    }

    @Test
    @DisplayName("a human-2 click onto an occupied square stays put")
    void aHuman2ClickOntoAnOccupiedSquareStaysPut() {
        // i9beef command-matrix Human 2 occupied: cycle 5 move unit 1579
        // x 7 y 34. The walker starts at 8,35, flickers to Move at cycle 10,
        // and is Still back on 8,35 for the rest of the 160-cycle fixture.
        GameData data = data();
        Mission mission = data.loadMission("campaigns/human/level02h", 0);
        Assumptions.assumeTrue(mission != null, "Human 2 is not in the pack");
        World world = mission.world();
        CommandApplier commands = new CommandApplier(
                world, new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);

        Unit walker = null;
        for (Unit unit : world.units()) {
            if (unit.isAlive() && unit.isOnMap()
                    && unit.tileX() == 8 && unit.tileY() == 35
                    && unit.player() == 0) {
                walker = unit;
                break;
            }
        }
        assertNotNull(walker, "Human 2 has no player unit on 8,35");

        assertTrue(commands.apply(GameCommand.move(0, walker.id(), 7, 34)),
                "retail accepted the occupied click; Java refused it outright");
        for (int cycle = 0; cycle < 80; cycle++) {
            world.tick();
        }
        assertEquals(8, walker.tileX(),
                "retail never left 8,35 after the occupied click; Java is at "
                        + walker.tileX() + "," + walker.tileY());
        assertEquals(35, walker.tileY(),
                "retail never left 8,35 after the occupied click; Java is at "
                        + walker.tileX() + "," + walker.tileY());
        assertEquals(Unit.Order.STILL, walker.order(),
                "retail is Still on 8,35; Java is still " + walker.order());
    }

    @Test
    @DisplayName("a human-12 zeppelin click resumes east toward 107,51")
    void aHuman12ZeppelinClickResumesEastTowardTheRetailScoutPoint() {
        // i9beef command-campaign-human-human12-pud-air-e, pinned 2.02b.
        // Cycle 5 move the player zeppelin on 82,52 to 86,52. Native
        // writes patrol dest 59,44 at fixture 2, Still on 86,52 at 48,
        // dest 107,51 at 49/51, 88,52 at 54 and 90,52 at 80. The
        // fixture forces initialization seed 1. Two HandleEachCycle
        // warmup ticks precede fixture cycle 1 -- the same boundary
        // EngineTrace uses -- because the resume point is two
        // asynchronous draws from the seed at that beat.
        GameData data = data();
        Mission mission = data.loadMission("campaigns/human/level12h", 0, 1);
        Assumptions.assumeTrue(mission != null, "Human 12 is not in the pack");
        World world = mission.world();
        CommandApplier commands = new CommandApplier(
                world, new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);

        Unit zeppelin = null;
        for (Unit unit : world.units()) {
            if (unit.isAlive() && unit.isOnMap()
                    && unit.tileX() == 82 && unit.tileY() == 52
                    && unit.player() == 0
                    && "unit-zeppelin".equals(unit.type().ident())) {
                zeppelin = unit;
                break;
            }
        }
        assertNotNull(zeppelin, "Human 12 has no player zeppelin on 82,52");

        mission.tick();
        mission.tick();
        for (int cycle = 1; cycle <= 80; cycle++) {
            if (cycle == 5) {
                assertTrue(commands.apply(GameCommand.move(
                                0, zeppelin.id(), 86, 52)),
                        "the Human 12 zeppelin refused the commanded east click");
            }
            mission.tick();
        }

        assertEquals(107, zeppelin.orderTargetX(),
                "retail resumes the scout toward 107,51, not "
                        + zeppelin.orderTargetX() + "," + zeppelin.orderTargetY());
        assertEquals(51, zeppelin.orderTargetY(),
                "retail resumes the scout toward 107,51, not "
                        + zeppelin.orderTargetX() + "," + zeppelin.orderTargetY());
        assertEquals(90, zeppelin.tileX(),
                "retail is on 90,52 by fixture 80, flying east, not "
                        + zeppelin.tileX() + "," + zeppelin.tileY());
        assertEquals(52, zeppelin.tileY(),
                "retail is on 90,52 by fixture 80, flying east, not "
                        + zeppelin.tileX() + "," + zeppelin.tileY());
        assertEquals(Unit.Order.PATROL, zeppelin.order(),
                "retail is back on Patrol toward 107,51; Java is still "
                        + zeppelin.order());
    }

    @Test
    @DisplayName("a human-13 daemon uses its asset-defined cold move wait")
    void aHuman13DaemonUsesItsAssetDefinedColdMoveWait() {
        // Pinned commanded fixture c1d74bda: player Move at fixture 5 from
        // 82,6 toward the occupied 86,4 point. Retail first changes physical
        // position at 15 and stands on the stride-neighbour 86,2 at 62.
        GameData data = data();
        String map = "campaigns/human/level13h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 13 is not in the pack");
        CommandApplier commands = new CommandApplier(mission.world(),
                new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);

        Unit daemon = mission.world().unitsSnapshot().stream()
                .filter(Unit::isAlive)
                .filter(Unit::isOnMap)
                .filter(unit -> unit.player() == 0)
                .filter(unit -> unit.tileX() == 82 && unit.tileY() == 6)
                .filter(unit -> "unit-daemon".equals(unit.type().ident()))
                .findFirst().orElse(null);
        assertNotNull(daemon, "Human 13 has no player daemon on 82,6");

        mission.tick();
        mission.tick();
        int startX = daemon.pixelX();
        int startY = daemon.pixelY();
        Integer firstProgress = null;
        Integer settled = null;
        for (int cycle = 1; cycle <= 80; cycle++) {
            if (cycle == 5) {
                assertTrue(commands.apply(GameCommand.move(
                        0, daemon.id(), 86, 4)));
            }
            mission.tick();
            if (firstProgress == null
                    && (daemon.pixelX() != startX || daemon.pixelY() != startY)) {
                firstProgress = cycle;
            }
            if (daemon.order() == Unit.Order.STILL
                    && daemon.tileX() == 86 && daemon.tileY() == 2) {
                settled = cycle;
                break;
            }
        }
        assertEquals(15, firstProgress);
        assertEquals(62, settled);
    }

    @Test
    @DisplayName("a human-12 scout zeppelin stands down at 50,4")
    void aHuman12ScoutZeppelinStandsDownAtItsFirstScoutPoint() {
        // i9beef retail-human-12-idle, pinned 2.02b. The zeppelin that
        // starts on 46,10 is sent to 50,4. Native is on that square from
        // fixture 43 and becomes Still at 63 -- it does not turn around
        // for the start tile. Java used to swap endpoints and leave at
        // 66, which is why the 1800-cycle survey first disagrees here
        // (native Still vs Java Patrol).
        GameData data = data();
        Mission mission = data.loadMission("campaigns/human/level12h", 1, 1);
        Assumptions.assumeTrue(mission != null, "Human 12 is not in the pack");
        World world = mission.world();

        Unit zeppelin = null;
        Unit oddDest = null;
        for (Unit unit : world.units()) {
            if (!unit.isAlive() || !unit.isOnMap()
                    || !"unit-zeppelin".equals(unit.type().ident())) {
                continue;
            }
            if (unit.tileX() == 46 && unit.tileY() == 10) {
                zeppelin = unit;
            }
            if (unit.tileX() == 92 && unit.tileY() == 14) {
                oddDest = unit;
            }
        }
        assertNotNull(zeppelin, "Human 12 has no zeppelin on 46,10");
        assertNotNull(oddDest, "Human 12 has no zeppelin on 92,14");

        mission.tick();
        mission.tick();
        for (int cycle = 1; cycle <= 63; cycle++) {
            mission.tick();
        }

        assertEquals(50, zeppelin.tileX(),
                "retail is standing on 50,4 at fixture 63, not "
                        + zeppelin.tileX() + "," + zeppelin.tileY());
        assertEquals(4, zeppelin.tileY(),
                "retail is standing on 50,4 at fixture 63, not "
                        + zeppelin.tileX() + "," + zeppelin.tileY());
        assertEquals(Unit.Order.STILL, zeppelin.order(),
                "retail stands down at the scout point; Java is still "
                        + zeppelin.order() + " toward "
                        + zeppelin.orderTargetX() + "," + zeppelin.orderTargetY());

        assertEquals(84, oddDest.tileX(),
                "retail is already on 84,10 at fixture 63, not "
                        + oddDest.tileX() + "," + oddDest.tileY());
        assertEquals(Unit.Order.PATROL, oddDest.order(),
                "retail stays Patrol on 84,10 at fixture 63; Java is "
                        + oddDest.order());

        for (int cycle = 64; cycle <= 80; cycle++) {
            mission.tick();
        }
        assertEquals(Unit.Order.STILL, zeppelin.order(),
                "retail is still standing on 50,4 at fixture 80; Java left for "
                        + zeppelin.order() + " at "
                        + zeppelin.tileX() + "," + zeppelin.tileY());
        assertEquals(Unit.Order.PATROL, oddDest.order(),
                "retail is still Patrol on 84,10 at fixture 80; Java is "
                        + oddDest.order());
        assertEquals(50, zeppelin.tileX(),
                "retail is still on 50,4 at fixture 80, not "
                        + zeppelin.tileX() + "," + zeppelin.tileY());
        assertEquals(4, zeppelin.tileY(),
                "retail is still on 50,4 at fixture 80, not "
                        + zeppelin.tileX() + "," + zeppelin.tileY());

        mission.tick();
        assertEquals(Unit.Order.PATROL, oddDest.order(),
                "retail is still Patrol on 84,10 at fixture 81; Java is "
                        + oddDest.order());
        mission.tick();
        // dest 83,10 is off the even flight lattice. The hull lands on
        // 84,10 at 62; retail stays Patrol through 81 and is Still at 82.
        // Java used to wait residual zero and stand down at 83, or stand
        // down at 63 the moment residual settled. Exact even dests (50,4)
        // still stand down when residual settles.
        assertEquals(84, oddDest.tileX(),
                "retail stands on 84,10 beside dest 83,10, not "
                        + oddDest.tileX() + "," + oddDest.tileY());
        assertEquals(10, oddDest.tileY(),
                "retail stands on 84,10 beside dest 83,10, not "
                        + oddDest.tileX() + "," + oddDest.tileY());
        assertEquals(Unit.Order.STILL, oddDest.order(),
                "retail is Still on 84,10 at fixture 82; Java is still "
                        + oddDest.order() + " at "
                        + oddDest.tileX() + "," + oddDest.tileY());

        for (int cycle = 83; cycle <= 97; cycle++) {
            mission.tick();
        }
        // i9beef retail-human-12-idle. Native 1559/1570 stay Still through
        // fixture 98 and go Patrol at 99. Java's aircraft beat is
        // world.cycle % 50 == 49, which is fixture 97 after two warmup
        // ticks, so both balloons left two cycles early.
        assertEquals(Unit.Order.STILL, zeppelin.order(),
                "retail is still Still on 50,4 at fixture 97; Java is "
                        + zeppelin.order() + " toward "
                        + zeppelin.orderTargetX() + "," + zeppelin.orderTargetY());
        assertEquals(Unit.Order.STILL, oddDest.order(),
                "retail is still Still on 84,10 at fixture 97; Java is "
                        + oddDest.order() + " toward "
                        + oddDest.orderTargetX() + "," + oddDest.orderTargetY());

        mission.tick();
        assertEquals(Unit.Order.STILL, oddDest.order(),
                "retail is still Still on 84,10 at fixture 98; Java is "
                        + oddDest.order());
        mission.tick();
        assertEquals(Unit.Order.PATROL, zeppelin.order(),
                "retail sends the 50,4 scout out again at fixture 99; Java is "
                        + zeppelin.order());
        assertEquals(Unit.Order.PATROL, oddDest.order(),
                "retail sends the 84,10 scout out again at fixture 99; Java is "
                        + oddDest.order());

        for (int cycle = 100; cycle <= 102; cycle++) {
            mission.tick();
        }
        // i9beef retail-human-12-idle. After the fixture-99 scout pass,
        // native 1559 first-steps east onto 86,10 at 102. Java used to
        // stand down from dest 83,10 still two pixels off 84,10, spend
        // fixture 102 draining that slide, and only step at 103.
        assertEquals(86, oddDest.tileX(),
                "retail is on 86,10 at fixture 102 after the scout resume, not "
                        + oddDest.tileX() + "," + oddDest.tileY());
        assertEquals(10, oddDest.tileY(),
                "retail is on 86,10 at fixture 102 after the scout resume, not "
                        + oddDest.tileX() + "," + oddDest.tileY());
        assertEquals(Unit.Order.PATROL, oddDest.order(),
                "retail is still on Patrol at 86,10; Java is " + oddDest.order());
        assertEquals(48, zeppelin.tileX(),
                "retail's even-dest scout is on 48,6 at fixture 102, not "
                        + zeppelin.tileX() + "," + zeppelin.tileY());
        assertEquals(6, zeppelin.tileY(),
                "retail's even-dest scout is on 48,6 at fixture 102, not "
                        + zeppelin.tileX() + "," + zeppelin.tileY());
    }

    @Test
    @DisplayName("a human-12 peon holds 103,1 through the last wood step")
    void aHuman12PeonHoldsTheLastWoodRingSquare() {
        // i9beef retail-human-12-idle. Native peon 1565 walks the same
        // 16-cycle NE legs onto 103,1 at fixture 211 and stays there
        // through 229, stepping onto the 104,0 ring of the 105,0 corner
        // tree only at 230. Java used to take that last step at 227.
        GameData data = data();
        Mission mission = data.loadMission("campaigns/human/level12h", 1, 1);
        Assumptions.assumeTrue(mission != null, "Human 12 is not in the pack");

        Unit peon = null;
        for (Unit unit : mission.world().units()) {
            if (unit.isAlive() && unit.isOnMap()
                    && unit.tileX() == 90 && unit.tileY() == 13
                    && "unit-peon".equals(unit.type().ident())) {
                peon = unit;
                break;
            }
        }
        assertNotNull(peon, "Human 12 has no peon on 90,13");

        mission.tick();
        mission.tick();
        for (int cycle = 1; cycle <= 227; cycle++) {
            mission.tick();
        }
        assertEquals(103, peon.tileX(),
                "retail is still on 103,1 at fixture 227, not "
                        + peon.tileX() + "," + peon.tileY());
        assertEquals(1, peon.tileY(),
                "retail is still on 103,1 at fixture 227, not "
                        + peon.tileX() + "," + peon.tileY());
        assertEquals(Unit.Order.HARVEST, peon.order(),
                "retail is still harvesting toward 105,0; Java is " + peon.order());

        mission.tick();
        mission.tick();
        assertEquals(103, peon.tileX(),
                "retail is still on 103,1 at fixture 229, not "
                        + peon.tileX() + "," + peon.tileY());
        assertEquals(1, peon.tileY(),
                "retail is still on 103,1 at fixture 229, not "
                        + peon.tileX() + "," + peon.tileY());

        mission.tick();
        assertEquals(104, peon.tileX(),
                "retail steps onto the 104,0 wood ring at fixture 230, not "
                        + peon.tileX() + "," + peon.tileY());
        assertEquals(0, peon.tileY(),
                "retail steps onto the 104,0 wood ring at fixture 230, not "
                        + peon.tileX() + "," + peon.tileY());
    }

    @Test
    @DisplayName("a human-11 zeppelin waits twenty cycles on the even tile")
    void aHuman11ZeppelinWaitsTwentyCyclesOnTheEvenTile() {
        // i9beef retail-human-11-idle. Computer zeppelin 1516 lands on
        // 86,52 at fixture 103 and is Still at 123 -- twenty cycles, the
        // same land-to-Still hold as Human 12 1559 (62 to 82) and Human 5
        // 1541 (129 to 149). This is a second campaign, not the next
        // Human 12 slot.
        GameData data = data();
        Mission mission = data.loadMission("campaigns/human/level11h", 1, 1);
        Assumptions.assumeTrue(mission != null, "Human 11 is not in the pack");

        Unit zeppelin = null;
        for (Unit unit : mission.world().units()) {
            if (unit.isAlive() && unit.isOnMap()
                    && unit.tileX() == 88 && unit.tileY() == 40
                    && "unit-zeppelin".equals(unit.type().ident())) {
                zeppelin = unit;
                break;
            }
        }
        assertNotNull(zeppelin, "Human 11 has no zeppelin on 88,40");

        mission.tick();
        mission.tick();
        for (int cycle = 1; cycle <= 122; cycle++) {
            mission.tick();
        }
        assertEquals(86, zeppelin.tileX(),
                "retail is on 86,52 at fixture 122, not "
                        + zeppelin.tileX() + "," + zeppelin.tileY());
        assertEquals(52, zeppelin.tileY(),
                "retail is on 86,52 at fixture 122, not "
                        + zeppelin.tileX() + "," + zeppelin.tileY());
        assertEquals(Unit.Order.PATROL, zeppelin.order(),
                "retail is still Patrol through the dest-arm hold; Java is "
                        + zeppelin.order());

        mission.tick();
        assertEquals(Unit.Order.STILL, zeppelin.order(),
                "retail stands down at fixture 123 after twenty dest-arm "
                        + "visits; Java is " + zeppelin.order());
        assertEquals(86, zeppelin.tileX(),
                "retail stands on 86,52, not "
                        + zeppelin.tileX() + "," + zeppelin.tileY());
        assertEquals(52, zeppelin.tileY(),
                "retail stands on 86,52, not "
                        + zeppelin.tileX() + "," + zeppelin.tileY());
    }
}
