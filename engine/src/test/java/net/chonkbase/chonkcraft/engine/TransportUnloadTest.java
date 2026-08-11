package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Putting a transport's cargo ashore.
 *
 * <p>Reported from play: "I can not unload, it doesn't even seem like I can
 * move the boat to a point where it's touching the shore... occasionally I get
 * its unload button to render/work but it does nothing".
 *
 * <p>Both halves of that are the same defect. Unloading was a single
 * instantaneous attempt at whichever square the boat happened to be floating
 * on -- {@code FindUnloadPosition} with a range of one, and nothing else. If
 * that square had no free land in the ring around it, the call returned zero
 * and the game did nothing: no order, no movement, no message.
 *
 * <p>Upstream's {@code COrder_Unload} is a three-state order. It searches up to
 * twenty tiles out from where the player pointed for a place a boat can sit and
 * a passenger can step off ({@code ClosestFreeDropZone}), sails there, and only
 * then lets anybody out; and if the hold is not empty afterwards it goes round
 * again from its new position. The implementation had only the last few lines of the last
 * state.
 *
 * <p>Measured on the mission in the report before the fix: a loaded transport
 * ordered to a landing site stopped one tile short of it and unloaded none of
 * its six passengers -- on a map where every one of its 591 coastal squares can
 * be unloaded onto. Nothing in the implementation would take it that last tile.
 */
class TransportUnloadTest {

    private static GameData load() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set -Dchonkcraft.pack=... or wc2.install.dir");
        return new GameData(assets);
    }

    /** Water in the north, land in the south, and a wide beach between. */
    private static World coast(GameData data) {
        GameMap map = new GameMap(40, 40, new Tileset());
        for (int y = 0; y < 40; y++) {
            for (int x = 0; x < 40; x++) {
                map.field(x, y).setFlags(y < 20 ? TileFlag.WATER_ALLOWED : TileFlag.LAND_ALLOWED);
            }
        }
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i,
                    i < 2 ? PudMap.PlayerType.PERSON : PudMap.PlayerType.NOBODY,
                    PudMap.Race.HUMAN);
        }
        World world = new World(map, players);
        world.setUnitTypes(data.unitTypes().types());
        world.setMissileTypes(data.missiles().types());
        return world;
    }

    private static Unit loadedBoat(World world, GameData data, int x, int y, int howMany) {
        UnitType boat = data.unitTypes().types().get("unit-human-transport");
        UnitType footman = data.unitTypes().types().get("unit-footman");
        Unit transport = world.createUnit(boat, 0, x, y);
        assertNotNull(transport, "no room for a transport at " + x + "," + y);
        for (int i = 0; i < howMany; i++) {
            Unit passenger = world.createUnit(footman, 0, 0, 30);
            assertNotNull(passenger);
            passenger.setTile(transport.tileX(), transport.tileY());
            assertTrue(world.board(passenger, transport), "a footman would not board");
        }
        assertEquals(howMany, transport.cargo().size());
        return transport;
    }

    /**
     * The report itself: a boat out at sea, told to land on a beach it is not
     * touching.
     *
     * <p>Ten tiles of open water between the transport and the shore. The old
     * code did nothing at all here, because the square under the boat has no
     * land anywhere near it.
     */
    @Test
    @DisplayName("A transport out at sea sails to the beach it was pointed at and unloads")
    void aBoatSailsToTheDropZoneItWasPointedAt() {
        GameData data = load();
        World world = coast(data);
        Unit transport = loadedBoat(world, data, 20, 8, 4);

        // The fixture is only the reported case if unloading on the spot is
        // impossible: nothing within a square of the boat may be land.
        for (int dy = -1; dy <= 2; dy++) {
            for (int dx = -1; dx <= 2; dx++) {
                var field = world.map().fieldOrNull(transport.tileX() + dx, transport.tileY() + dy);
                assertTrue(field == null || !field.hasFlag(TileFlag.LAND_ALLOWED),
                        "the boat must start with no land in reach, or this proves nothing");
            }
        }
        assertEquals(0, world.unload(transport),
                "unloading on the spot should be impossible here; that is the point");

        // Point at the beach, as the player does with the unload cursor.
        assertTrue(world.orderUnload(transport, 20, 22, null), "the order was refused");
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 60; cycle++) {
            world.tick();
            if (transport.cargo().isEmpty()) {
                break;
            }
        }

        assertTrue(transport.cargo().isEmpty(),
                "the transport still holds " + transport.cargo().size() + " after a minute;"
                        + " it is at " + transport.tileX() + "," + transport.tileY()
                        + ". COrder_Unload sails to a drop zone before letting anybody off,"
                        + " and the port only ever tried the square it was already on.");
        int ashore = 0;
        for (Unit unit : world.units()) {
            if (unit.player() == 0 && !unit.type().seaUnit() && unit.isOnMap()) {
                ashore++;
                assertTrue(world.map().field(unit.tileX(), unit.tileY())
                                .hasFlag(TileFlag.LAND_ALLOWED),
                        "a footman was put down in the sea at "
                                + unit.tileX() + "," + unit.tileY());
            }
        }
        assertEquals(4, ashore, "not everybody made it ashore");
    }

    /**
     * The other half of the report: a boat that stops a tile short of where it
     * was sent still unloads.
     *
     * <p>This is what the player actually experiences, because nobody parks a
     * two-by-two boat on an exact square. It is the reason the drop-zone search
     * starts from the position rather than from the boat.
     */
    @Test
    @DisplayName("Unloading works from a tile the boat merely ended up on")
    void aBoatThatStoppedShortStillUnloads() {
        GameData data = load();
        World world = coast(data);
        // Two tiles clear of the shoreline: adjacent to nothing landable.
        Unit transport = loadedBoat(world, data, 20, 17, 2);
        assertEquals(0, world.unload(transport),
                "the fixture must not already be beside land");

        // Told to unload where it stands. Upstream searches outwards from
        // there anyway, which is what covers the last tile.
        assertTrue(world.orderUnload(transport, transport.tileX(), transport.tileY(), null));
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 40; cycle++) {
            world.tick();
            if (transport.cargo().isEmpty()) {
                break;
            }
        }
        assertTrue(transport.cargo().isEmpty(),
                "a boat one tile off the coast could not put anybody ashore; it is at "
                        + transport.tileX() + "," + transport.tileY());
    }

    /** Clicking a single cargo icon lands that one and leaves the rest aboard. */
    @Test
    @DisplayName("Unloading one passenger leaves the others aboard")
    void oneAtATime() {
        GameData data = load();
        World world = coast(data);
        Unit transport = loadedBoat(world, data, 20, 8, 3);
        Unit chosen = transport.cargo().get(1);

        assertTrue(world.orderUnload(transport, 20, 22, chosen), "the order was refused");
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 60; cycle++) {
            world.tick();
            if (!transport.cargo().contains(chosen)) {
                break;
            }
        }
        assertTrue(chosen.isOnMap(), "the chosen passenger never landed");
        assertEquals(2, transport.cargo().size(),
                "unloading one emptied the boat, or landed nobody");
    }

    /**
     * The reported mission, played the way it was played: sail, then unload.
     *
     * <p>This is the measurement in the class comment. It failed before the
     * order existed and it is the case the report describes.
     */
    @Test
    @DisplayName("On the reported mission a loaded transport lands its troops")
    void theReportedMission() {
        GameData data = load();
        Mission mission = data.loadMission("campaigns/human/level05h");
        Assumptions.assumeTrue(mission != null, "the fifth human mission is not available");
        World world = mission.world();
        int me = GameData.personIn(data.campaignMap("campaigns/human/level05h"));

        UnitType boat = data.unitTypes().types().get("unit-human-transport");
        UnitType footman = data.unitTypes().types().get("unit-footman");

        Unit transport = world.createUnit(boat, me, 18, 0);
        Assumptions.assumeTrue(transport != null, "no open water at the fixture's start");
        for (int i = 0; i < 6; i++) {
            Unit passenger = world.createUnit(footman, me, 0, 0);
            if (passenger == null) {
                break;
            }
            passenger.setTile(transport.tileX(), transport.tileY());
            world.board(passenger, transport);
        }
        Assumptions.assumeTrue(transport.cargo().size() == 6, "could not fill the boat");

        assertTrue(world.orderUnload(transport, 22, 1, null), "the order was refused");
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 90; cycle++) {
            world.tick();
            if (transport.cargo().isEmpty()) {
                break;
            }
        }
        assertTrue(transport.cargo().isEmpty(),
                "on the reported mission the boat ended at " + transport.tileX() + ","
                        + transport.tileY() + " still holding " + transport.cargo().size()
                        + " of its six. Before the unload order it stopped at 21,1 -- one"
                        + " tile short of a square that unloads perfectly well -- and landed"
                        + " nobody.");
    }

    /**
     * The root cause, on its own.
     *
     * <p>. Naval types are blocked by
     * {@code MapFieldCoastAllowed} -- except transports, whose branch omits it.
     * The flag is declared as "Coast (transporter) units allowed"
     * ({@code tileset.h:67}) and this is the only thing that reads it that way.
     *
     * <p>Without the carve-out a Warcraft II coastline is a wall. The coast
     * ribbon between water and land admits neither ships nor soldiers, so no
     * boat can ever be within a square of a square a passenger could stand on,
     * and unloading is impossible everywhere rather than merely awkward.
     */
    @Test
    @DisplayName("A transport may enter coast squares and other ships may not")
    void onlyTransportsMayBeach() {
        GameData data = load();
        UnitType transport = data.unitTypes().types().get("unit-human-transport");
        UnitType destroyer = data.unitTypes().types().get("unit-human-destroyer");
        UnitType footman = data.unitTypes().types().get("unit-footman");
        assertNotNull(transport);
        assertNotNull(destroyer);
        assertNotNull(footman);
        assertTrue(transport.canTransport(), "the fixture's transport carries nothing");
        assertTrue(destroyer.seaUnit() && !destroyer.canTransport(),
                "the control has to be a warship that carries nothing");

        assertTrue((Unit.movementMaskFor(transport) & TileFlag.COAST_ALLOWED) != 0,
                "a transport cannot beach, so it can never reach a passenger's landing spot");
        assertTrue((Unit.movementMaskFor(transport) & TileFlag.WATER_ALLOWED) != 0,
                "a transport that only floats on coast could not sail");
        assertEquals(0, Unit.movementMaskFor(destroyer) & TileFlag.COAST_ALLOWED,
                "a destroyer is not a transport and must stay off the coast");
        assertEquals(0, Unit.movementMaskFor(footman) & TileFlag.COAST_ALLOWED,
                "land units are blocked by coast squares upstream too");
    }

    /**
     * And what that costs on a real map: whether a boat can get near land at
     * all.
     *
     * <p>This is the measurement behind the report. Every water square along
     * the mission's coast was counted, and then asked whether a transport
     * standing there could put a passenger ashore. Before the carve-out the
     * answer was none of them.
     */
    @Test
    @DisplayName("On the reported mission there are coastal squares a transport can unload from")
    void theCoastIsReachable() {
        GameData data = load();
        Mission mission = data.loadMission("campaigns/human/level05h");
        Assumptions.assumeTrue(mission != null, "the fifth human mission is not available");
        World world = mission.world();
        int me = GameData.personIn(data.campaignMap("campaigns/human/level05h"));

        UnitType boat = data.unitTypes().types().get("unit-human-transport");
        UnitType footman = data.unitTypes().types().get("unit-footman");
        Unit transport = world.createUnit(boat, me, 18, 0);
        assertNotNull(transport);
        Unit passenger = world.createUnit(footman, me, 0, 0);
        assertNotNull(passenger);
        passenger.setTile(transport.tileX(), transport.tileY());
        assertTrue(world.board(passenger, transport));

        int landable = 0;
        for (int y = 0; y < world.map().height() - 1; y++) {
            for (int x = 0; x < world.map().width() - 1; x++) {
                if (world.canUnloadFrom(transport, x, y)) {
                    landable++;
                }
            }
        }
        assertTrue(landable > 0,
                "not one square on this map lets a transport put a passenger ashore. The"
                        + " coast ribbon blocks ships and land units alike, and only the"
                        + " transporter carve-out in unittype.cpp opens it.");
    }

    /**
     * A crowded beach: nobody is landed on top of anybody.
     *
     * <p>Upstream's {@code UnitCanBeAt} tests terrain and occupancy together,
     * because a unit's movement mask names {@code MapFieldLandUnit},
     * {@code MapFieldSeaUnit} and {@code MapFieldBuilding} alongside the
     * terrain flags. The implementation splits the two into a required mask and a
     * blocking mask and hands both to {@code isFootprintFree}, so the same two
     * questions get asked -- and this is the test that says so, because it is
     * not obvious from the call sites that the second one is being asked at
     * all.
     */
    @Test
    @DisplayName("Passengers land on separate squares, never stacked")
    void nobodyIsLandedOnTopOfAnybody() {
        GameData data = load();
        World world = coast(data);
        Unit transport = loadedBoat(world, data, 20, 8, 6);

        assertTrue(world.orderUnload(transport, 20, 22, null), "the order was refused");
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 90; cycle++) {
            world.tick();
            if (transport.cargo().isEmpty()) {
                break;
            }
        }
        assertTrue(transport.cargo().isEmpty(),
                "still holding " + transport.cargo().size());

        java.util.Set<String> squares = new java.util.HashSet<>();
        int ashore = 0;
        for (Unit unit : world.units()) {
            if (unit.player() != 0 || unit.type().seaUnit() || !unit.isOnMap()) {
                continue;
            }
            ashore++;
            assertTrue(squares.add(unit.tileX() + "," + unit.tileY()),
                    "two footmen were put down on " + unit.tileX() + "," + unit.tileY()
                            + ": the landing search is ignoring who is already standing there");
        }
        assertEquals(6, ashore, "not everybody made it ashore");
    }

    /**
     * A drop zone with another boat already moored in it is passed over.
     *
     * <p>The transport is lifted off the map for the duration of the search so
     * that it does not collide with itself ({@code ClosestFreeDropZone}), which
     * only makes sense because everything <em>else</em> on the map still
     * counts. This checks the other half of that: a rival hull in the way is
     * not searched through.
     */
    @Test
    @DisplayName("A drop zone another ship is sitting in is not chosen")
    void anOccupiedDropZoneIsSkipped() {
        GameData data = load();
        World world = coast(data);
        UnitType boat = data.unitTypes().types().get("unit-human-transport");

        // 24,18 is where this boat unloads from when the coast is empty --
        // measured, not guessed. Mooring another hull exactly there is the
        // only arrangement that tests anything: a rival ship anywhere else
        // would be passed by whether occupancy were consulted or not.
        Unit loaded = loadedBoat(world, data, 20, 8, 2);
        Unit moored = world.createUnit(boat, 0, 24, 18);
        assertNotNull(moored);
        int mooredX = moored.tileX();
        int mooredY = moored.tileY();

        assertTrue(world.orderUnload(loaded, 20, 22, null), "the order was refused");
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 90; cycle++) {
            world.tick();
            if (loaded.cargo().isEmpty()) {
                break;
            }
        }

        assertTrue(loaded.cargo().isEmpty(),
                "the loaded boat gave up rather than finding another stretch of coast; it"
                        + " is at " + loaded.tileX() + "," + loaded.tileY());
        assertTrue(loaded.tileX() != mooredX || loaded.tileY() != mooredY,
                "the loaded boat unloaded from the very square another ship was moored in");
        assertEquals(mooredX, moored.tileX(), "the moored boat was shoved aside");
        assertEquals(mooredY, moored.tileY(), "the moored boat was shoved aside");
    }
}
