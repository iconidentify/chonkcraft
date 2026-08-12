package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.ResourceInfo;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.unit.UnitType.Resource;
import org.junit.jupiter.api.Test;

/** Tests for ships, transports, aircraft, and oil. */
class NavalAirTest {

    /** Land on the left, water on the right, meeting at column 15. */
    private static GameMap coast(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(x < 15 ? TileFlag.LAND_ALLOWED : TileFlag.WATER_ALLOWED);
            }
        }
        return map;
    }

    private static Player[] twoPlayers() {
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i,
                    i < 2 ? PudMap.PlayerType.PERSON : PudMap.PlayerType.NOBODY,
                    PudMap.Race.HUMAN);
        }
        return players;
    }

    private static AnimationSet mover() {
        AnimationSet set = new AnimationSet("m");
        set.put(AnimationSet.State.STILL, Animation.parse("s", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("m",
                List.of("frame 0", "move 16", "wait 1", "frame 5", "move 16", "wait 1")));
        set.put(AnimationSet.State.DEATH, Animation.parse("d", List.of("frame 50", "wait 1")));
        return set;
    }

    private static UnitType footman() {
        UnitType type = new UnitType("unit-footman");
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setSightRange(4);
        type.setAnimationSet(mover());
        return type;
    }

    private static UnitType transport() {
        UnitType type = new UnitType("unit-human-transport");
        type.setTileSize(1, 1);
        type.setHitPoints(150);
        type.setSpeed(10);
        type.setSeaUnit(true);
        type.setSightRange(4);
        type.setMaxOnBoard(6);
        type.canTransport_().add("LandUnit");
        type.setAnimationSet(mover());
        return type;
    }

    private static UnitType destroyer() {
        UnitType type = new UnitType("unit-human-destroyer");
        type.setTileSize(1, 1);
        type.setHitPoints(100);
        type.setSpeed(10);
        type.setSeaUnit(true);
        type.setCanAttack(true);
        type.setCanTargetSea(true);
        type.setBasicDamage(35);
        type.setMaxAttackRange(4);
        type.setAnimationSet(mover());
        return type;
    }

    private static UnitType gryphon() {
        UnitType type = new UnitType("unit-gryphon-rider");
        type.setTileSize(1, 1);
        type.setHitPoints(100);
        type.setSpeed(14);
        type.setAirUnit(true);
        type.setSightRange(6);
        type.setAnimationSet(mover());
        return type;
    }

    private static UnitType tanker() {
        UnitType type = new UnitType("unit-human-oil-tanker");
        type.setTileSize(1, 1);
        type.setHitPoints(90);
        type.setSpeed(10);
        type.setSeaUnit(true);
        type.setAnimationSet(mover());

        ResourceInfo oil = new ResourceInfo(Resource.OIL);
        oil.setCapacity(100);
        oil.setWaitAtResource(2);
        oil.setWaitAtDepot(2);
        oil.setRefineryHarvester(true);
        type.gathering().put(Resource.OIL, oil);
        return type;
    }

    private static UnitType oilPlatform() {
        UnitType type = new UnitType("unit-human-oil-platform");
        type.setTileSize(3, 3);
        type.setHitPoints(50_000);
        type.setBuilding(true);
        // GivesResource = "oil", CanHarvest = true
        // (scripts/human/units.legacy-declaration:1333). The pair is what tells a platform
        // from the patch beneath it: both give oil and only the platform can
        // be pumped.
        type.setGivesResource(UnitType.Resource.OIL);
        type.setCanHarvest(true);
        return type;
    }

    private static UnitType refinery() {
        UnitType type = new UnitType("unit-human-refinery");
        type.setTileSize(3, 3);
        type.setHitPoints(600);
        type.setBuilding(true);
        type.stores().add(Resource.OIL);
        return type;
    }

    private static UnitType townHall() {
        UnitType type = new UnitType("unit-town-hall");
        type.setTileSize(4, 4);
        type.setHitPoints(1200);
        type.setBuilding(true);
        type.stores().add(Resource.GOLD);
        type.stores().add(Resource.WOOD);
        return type;
    }

    // ---------------------------------------------------------------- naval

    @Test
    void aShipStaysOnWaterAndAFootmanOnLand() {
        World world = new World(coast(30), twoPlayers());
        Unit ship = world.createUnit(destroyer(), 0, 20, 5);
        Unit soldier = world.createUnit(footman(), 0, 5, 5);

        // A ship can be ordered onto the beach and simply will not get there.
        // Upstream's CommandMove never asks the pathfinder anything, so the
        // order is always taken; what refuses the beach is the walk, and
        // COrder_Move::Execute widens its Range on PF_UNREACHABLE until the
        // goal covers water the ship can sit in. This used to be asserted the
        // other way round -- the order refused outright -- which reads better
        // on a click and is not what the game does.
        assertTrue(world.orderMove(ship, 5, 5), "the order is taken, as upstream takes it");
        int shipStartDistance = Math.abs(ship.tileX() - 5);
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 20; cycle++) {
            world.tick();
            assertTrue(world.map().field(ship.tileX(), ship.tileY())
                            .hasFlag(TileFlag.WATER_ALLOWED | TileFlag.COAST_ALLOWED),
                    "a destroyer sailed up the shore to " + ship.tileX() + "," + ship.tileY());
        }
        assertTrue(Math.abs(ship.tileX() - 5) < shipStartDistance,
                "a destroyer ordered onto land must sail toward the closest legal water");
        assertEquals(15, ship.tileX(),
                "the destroyer must stop at the water edge nearest its land destination");
        assertTrue(world.orderMove(ship, 25, 20), "but should move freely at sea");

        assertTrue(world.orderMove(soldier, 25, 5), "the order is taken");
        int soldierStartDistance = Math.abs(soldier.tileX() - 25);
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 20; cycle++) {
            world.tick();
            assertTrue(world.map().field(soldier.tileX(), soldier.tileY())
                            .hasFlag(TileFlag.LAND_ALLOWED | TileFlag.COAST_ALLOWED),
                    "a footman walked onto water at " + soldier.tileX() + "," + soldier.tileY());
        }
        assertTrue(Math.abs(soldier.tileX() - 25) < soldierStartDistance,
                "a footman ordered onto water must walk toward the closest legal shore");
        assertEquals(14, soldier.tileX(),
                "the footman must stop at the land edge nearest its water destination");
        assertTrue(world.orderMove(soldier, 10, 20));
    }

    @Test
    void aMoveIntoTreesApproachesTheClosestWalkableEdge() {
        GameMap map = new GameMap(20, 20, new Tileset());
        for (int y = 0; y < 20; y++) {
            for (int x = 0; x < 20; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        for (int y = 9; y <= 11; y++) {
            for (int x = 10; x <= 12; x++) {
                map.field(x, y).setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
            }
        }
        World world = new World(map, twoPlayers());
        Unit soldier = world.createUnit(footman(), 0, 2, 10);

        assertTrue(world.orderMove(soldier, 11, 10),
                "the move into the forest must be accepted before route planning");
        for (int cycle = 0; cycle < 600 && soldier.order() != Unit.Order.STILL; cycle++) {
            world.tick();
        }

        assertEquals(Unit.Order.STILL, soldier.order(), "the forest approach never settled");
        assertTrue(soldier.tileX() > 2,
                "the footman acknowledged a forest-edge move but never approached it");
        assertTrue(world.map().field(soldier.tileX(), soldier.tileY())
                        .hasFlag(TileFlag.LAND_ALLOWED),
                "the footman stopped on the forest instead of beside it");
        assertEquals(2, Math.max(Math.abs(soldier.tileX() - 11),
                        Math.abs(soldier.tileY() - 10)),
                "the footman did not stop at the closest reachable edge of the tree block");
    }

    @Test
    void anAircraftCrossesBoth() {
        World world = new World(coast(30), twoPlayers());
        Unit flyer = world.createUnit(gryphon(), 0, 5, 5);
        // Terrain is no obstacle to a gryphon; the destination is over water.
        assertTrue(world.orderMove(flyer, 25, 25));
    }

    @Test
    void shipsAndLandUnitsDoNotBlockEachOther() {
        // They occupy different flags, so a boat sailing past the shore does
        // not collide with the army standing on it.
        World world = new World(coast(30), twoPlayers());
        Unit soldier = world.createUnit(footman(), 0, 14, 5);
        Unit ship = world.createUnit(destroyer(), 0, 15, 5);

        assertTrue(world.map().field(14, 5).hasFlag(TileFlag.LAND_UNIT));
        assertTrue(world.map().field(15, 5).hasFlag(TileFlag.SEA_UNIT));
        assertFalse(world.map().field(15, 5).hasFlag(TileFlag.LAND_UNIT));
        assertTrue(soldier.isAlive() && ship.isAlive());
    }

    // ------------------------------------------------------------ transports

    @Test
    void aTransportCarriesLandUnits() {
        World world = new World(coast(30), twoPlayers());
        Unit ship = world.createUnit(transport(), 0, 15, 5);
        Unit soldier = world.createUnit(footman(), 0, 14, 5);

        assertTrue(world.board(soldier, ship));
        assertEquals(1, ship.cargo().size());
        assertTrue(soldier.isAboard());

        // A boarded unit leaves the map: no ground, no vision, no target.
        assertFalse(soldier.isOnMap());
        assertFalse(world.map().field(14, 5).hasFlag(TileFlag.LAND_UNIT));
        // Its vision goes with it. Checked well inland, because the ship's own
        // sight still covers the square the soldier boarded from.
        assertFalse(world.fog().isVisible(0, 10, 5), "a passenger does not scout");
    }

    @Test
    void aTransportUnloadsOntoTheShore() {
        World world = new World(coast(30), twoPlayers());
        Unit ship = world.createUnit(transport(), 0, 15, 5);
        Unit soldier = world.createUnit(footman(), 0, 14, 5);
        world.board(soldier, ship);

        assertEquals(1, world.unload(ship));
        assertTrue(soldier.isOnMap());
        assertFalse(soldier.isAboard());
        assertEquals(0, ship.cargo().size());
        // It comes ashore beside the ship, on ground it can stand on.
        assertTrue(world.map().field(soldier.tileX(), soldier.tileY()).isLandPassable());
    }

    @Test
    void aTransportHasALimit() {
        World world = new World(coast(30), twoPlayers());
        Unit ship = world.createUnit(transport(), 0, 15, 5);

        int boarded = 0;
        for (int i = 0; i < 10; i++) {
            Unit soldier = world.createUnit(footman(), 0, 14, i);
            // Move it alongside so it is in reach.
            soldier.setTile(14, 5);
            if (world.board(soldier, ship)) {
                boarded++;
            }
        }
        assertEquals(6, boarded, "a transport holds six");
        assertFalse(ship.hasRoom());
    }

    @Test
    void aTransportWillNotCarryABuilding() {
        World world = new World(coast(30), twoPlayers());
        Unit ship = world.createUnit(transport(), 0, 15, 5);
        Unit hall = world.createUnit(townHall(), 0, 11, 4);
        assertFalse(world.board(hall, ship));
    }

    @Test
    void aUnitTooFarAwayCannotBoard() {
        World world = new World(coast(30), twoPlayers());
        Unit ship = world.createUnit(transport(), 0, 25, 25);
        Unit soldier = world.createUnit(footman(), 0, 5, 5);
        assertFalse(world.board(soldier, ship), "it has to reach the ship first");
    }

    @Test
    void sinkingATransportDrownsItsCargo() {
        // The whole risk of moving an army by sea.
        World world = new World(coast(30), twoPlayers());
        Unit ship = world.createUnit(transport(), 0, 15, 5);
        Unit first = world.createUnit(footman(), 0, 14, 5);
        Unit second = world.createUnit(footman(), 0, 14, 6);
        second.setTile(14, 5);

        world.board(first, ship);
        world.board(second, ship);
        assertEquals(2, ship.cargo().size());

        world.kill(ship);
        assertFalse(first.isAlive(), "the cargo should go down with the ship");
        assertFalse(second.isAlive());
        assertEquals(0, ship.cargo().size());
    }

    // ------------------------------------------------------------------ oil

    @Test
    void aTankerWillNotUnloadAtATownHall() {
        // Oil is the awkward resource: a tanker is a refinery-harvester and
        // cannot unload at the hall the way a peasant can.
        World world = new World(coast(40), twoPlayers());
        world.createUnit(townHall(), 0, 2, 2);
        world.createUnit(oilPlatform(), 15, 25, 5);
        Unit tanker = world.createUnit(tanker(), 0, 20, 5);

        assertTrue(world.orderHarvest(tanker, 25, 5));
        for (int cycle = 0; cycle < 6000; cycle++) {
            world.tick();
        }
        assertEquals(0, world.player(0).get(Resource.OIL),
                "a town hall should not accept oil");
    }

    @Test
    void aTankerUnloadsAtARefinery() {
        World world = new World(coast(40), twoPlayers());
        world.createUnit(oilPlatform(), 15, 30, 5);

        // A refinery is a shore building: it must sit where a boat can reach
        // it, which for this map means right on the waterline.
        world.createUnit(refinery(), 0, 15, 20);
        Unit tanker = world.createUnit(tanker(), 0, 25, 5);

        assertTrue(world.orderHarvest(tanker, 30, 5));
        for (int cycle = 0; cycle < 20_000; cycle++) {
            world.tick();
            if (world.player(0).get(Resource.OIL) > 0) {
                break;
            }
        }
        assertTrue(world.player(0).get(Resource.OIL) > 0,
                "a refinery should accept the oil");
    }

    @Test
    void anOilPatchIsNotYetASource() {
        // A patch has to be built over before it yields anything.
        World world = new World(coast(30), twoPlayers());
        UnitType patch = new UnitType("unit-oil-patch");
        patch.setTileSize(3, 3);
        patch.setHitPoints(50_000);
        patch.setBuilding(true);
        world.createUnit(patch, 15, 25, 5);
        Unit tanker = world.createUnit(tanker(), 0, 20, 5);

        assertFalse(world.orderHarvest(tanker, 25, 5),
                "an unbuilt patch should not be harvestable");
    }
}
