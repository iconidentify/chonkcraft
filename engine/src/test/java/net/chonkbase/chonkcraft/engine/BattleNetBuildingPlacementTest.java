package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where the computer's ring walk begins, and how far apart its candidates are.
 *
 * <p>Retail BNE has two building-placement entry points, {@code 0x43a380} for
 * an ordinary building and {@code 0x43a420} for a farm, and they differ in one
 * number: the lattice the shared search at {@code 0x439de0} walks on. Both
 * start one lattice step north-west of the anchor, which is why an AI town
 * grows outward from its hall rather than on top of it.
 */
class BattleNetBuildingPlacementTest {

    private static GameMap land(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static Player[] computer() {
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i,
                    i == 0 ? PudMap.PlayerType.COMPUTER : PudMap.PlayerType.NOBODY,
                    PudMap.Race.ORC);
        }
        return players;
    }

    private static UnitType worker() {
        UnitType type = new UnitType("unit-peon");
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        return type;
    }

    private static UnitType building(String ident, int size) {
        UnitType type = new UnitType(ident);
        type.setTileSize(size, size);
        type.setHitPoints(500);
        type.setBuilding(true);
        return type;
    }

    @Test
    @DisplayName("a farm is looked for two tiles north-west of the peon, an ordinary "
            + "building six")
    void theTwoEntryPointsDifferOnlyInTheLatticeTheyWalk() {
        World world = new World(land(64), computer());
        Unit peon = world.createUnit(worker(), 0, 30, 30);
        assertNotNull(peon, "the peon must be on the map to anchor the search");

        assertArrayEquals(new int[] {28, 28},
                world.aiFindBattleNetFoodPlace(peon, building("unit-pig-farm", 2)),
                "a farm walks the two-tile lattice, so the first candidate is the "
                        + "square two north and two west of the anchor");
        assertArrayEquals(new int[] {24, 24},
                world.aiFindBattleNetBuildingPlace(peon, building("unit-orc-barracks", 3)),
                "an ordinary building walks the six-tile lattice, so its first "
                        + "candidate is six north and six west of the same anchor");
    }

    @Test
    @DisplayName("the computer grows its town around the gold hall, not around the "
            + "peon that happens to be free")
    void aGoldDepotInTheSameCellTakesOverAsTheAnchor() {
        World world = new World(land(64), computer());
        UnitType hallType = building("unit-great-hall", 4);
        hallType.stores().add(UnitType.Resource.GOLD);
        Unit hall = world.createUnit(hallType, 0, 40, 40);
        assertNotNull(hall, "the hall must be on the map to be found as a depot");
        Unit peon = world.createUnit(worker(), 0, 10, 10);

        int[] site = world.aiFindBattleNetFoodPlace(peon, building("unit-pig-farm", 2));
        assertNotNull(site, "an open map around a hall must yield a farm site");
        int toHall = Math.max(Math.abs(site[0] - 40), Math.abs(site[1] - 40));
        int toPeon = Math.max(Math.abs(site[0] - 10), Math.abs(site[1] - 10));
        assertTrue(toHall <= 8, "the ring is walked around the hall at 40,40, so the "
                + "farm lands within a couple of lattice steps of it; got "
                + site[0] + "," + site[1]);
        assertTrue(toPeon > toHall, "the free peon at 10,10 is not the anchor: a town "
                + "that grew around whichever worker happened to be idle would put "
                + "this farm at " + site[0] + "," + site[1] + ", nearer the peon");
    }

    @Test
    @DisplayName("the ring skips candidates that fall off the map instead of "
            + "stopping at the edge")
    void aWalkStartedInTheCornerKeepsGoingPastTheOffMapCandidates() {
        World world = new World(land(64), computer());
        Unit peon = world.createUnit(worker(), 0, 1, 1);

        int[] site = world.aiFindBattleNetFoodPlace(peon, building("unit-pig-farm", 2));
        assertNotNull(site, "the search must not give up merely because the first "
                + "candidate at -1,-1 is off the map");
        assertTrue(site[0] >= 0 && site[1] >= 0,
                "an answer off the map would be no answer: got "
                        + site[0] + "," + site[1]);
        assertArrayEquals(new int[] {3, 1}, site,
                "with -1,-1 off the map and 1,1 held by the peon itself, the third "
                        + "candidate on the first side is the one that takes it");
    }

    @Test
    @DisplayName("the farm ring will not sit on another building's body")
    void theFarmRingWillNotSitOnAnotherBuildingsBody() {
        World world = new World(land(64), computer());
        UnitType hallType = building("unit-great-hall", 4);
        hallType.stores().add(UnitType.Resource.GOLD);
        assertNotNull(world.createUnit(hallType, 0, 40, 40),
                "the hall is the depot the farm ring walks around");
        UnitType farmType = building("unit-pig-farm", 2);
        assertNotNull(world.createUnit(farmType, 0, 41, 45),
                "an existing farm body has to occupy its four tiles");
        Unit peon = world.createUnit(worker(), 0, 30, 30);
        assertNotNull(peon, "the peon asks for the next farm");

        int[] site = world.aiFindBattleNetFoodPlace(peon, farmType);
        assertNotNull(site, "open ground around the hall must still yield a farm");
        boolean overlaps = site[0] < 41 + 2 && site[0] + 2 > 41
                && site[1] < 45 + 2 && site[1] + 2 > 45;
        assertTrue(!overlaps,
                "native 0x416c40 refuses every tile that already carries a "
                        + "building, so the next farm cannot cover 41,45; got "
                        + site[0] + "," + site[1]);
    }

    @Test
    @DisplayName("a map too small to hold even the first ring gets no site rather "
            + "than a wrong one")
    void theSearchRefusesAMapShorterThanItsOpeningRing() {
        World world = new World(land(6), computer());
        Unit peon = world.createUnit(worker(), 0, 3, 3);

        assertNull(
                world.aiFindBattleNetBuildingPlace(peon, building("unit-orc-barracks", 3)),
                "the six-tile lattice opens on a side of eighteen tiles, which does "
                        + "not fit, and the honest answer is none");
    }
}
