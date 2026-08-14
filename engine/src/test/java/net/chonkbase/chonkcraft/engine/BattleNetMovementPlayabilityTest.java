package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
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

    private static void runToRest(World world, Unit unit, int limit) {
        for (int cycle = 0; cycle < limit && unit.order() != Unit.Order.STILL; cycle++) {
            world.tick();
        }
        assertEquals(Unit.Order.STILL, unit.order(),
                "the player move never reached a completed state");
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
        assertEquals(Unit.Order.MOVE, footman.order(), "the wire move command was refused");
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
        assertEquals(Unit.Order.MOVE, destroyer.order(), "the destroyer move was refused");
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
        assertEquals(Unit.Order.MOVE, gryphon.order(), "the gryphon move was refused");
        runToRest(air.world(), gryphon, 4_000);
        assertTrue(gryphon.tileX() >= 24,
                "the aircraft treated an impassable ground wall as flight terrain: "
                        + gryphon.tileX() + "," + gryphon.tileY());
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
}
