package net.chonkbase.chonkcraft.engine.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Remembering an enemy base after the scout that found it has gone.
 *
 * <p>Scout a town in Warcraft II and it stays on your map. This implementation drew
 * enemy units and buildings alike only while a square they stood on was lit,
 * so a scouted base vanished the moment the scout looked away and the player
 * had no record of anything they had found. That makes scouting nearly
 * pointless, which is a gameplay difference and not a cosmetic one.
 *
 * <p>Upstream's rule is exact: a type carrying {@code VisibleUnderFog} is
 * drawn from a snapshot taken when it slipped out of sight
 * ({@code CUnit::IsVisibleInViewport}). In the shipped data that flag is on
 * every building and on nothing else, so bases persist and the garrison does
 * not. The flag was not even parsed here.
 */
class SeenBuildingsTest {

    private static GameData load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    private static World plain(GameData data, int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
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

    /**
     * The flag itself. If this is not parsed nothing below can work, and the
     * failure is silent: every building simply reads as not worth remembering.
     */
    @Test
    @DisplayName("Buildings are flagged visible under fog and units are not")
    void theFlagIsParsed() {
        GameData data = load();
        int buildings = 0;
        int flaggedNonBuildings = 0;
        for (UnitType type : data.unitTypes().types().values()) {
            if (type.building() && type.visibleUnderFog()) {
                buildings++;
            }
            if (!type.building() && type.visibleUnderFog()) {
                flaggedNonBuildings++;
            }
        }
        assertTrue(buildings > 10,
                "only " + buildings + " buildings are visible under fog; the flag is not"
                        + " being read out of units.legacy-declaration");
        assertEquals(0, flaggedNonBuildings,
                "a unit that is not a building is remembered under fog, so enemy armies"
                        + " would be tracked through the dark");
    }

    /** The whole point: look away and the base is still there. */
    @Test
    @DisplayName("A scouted building is remembered after the scout leaves")
    void aScoutedBaseIsRemembered() {
        GameData data = load();
        World world = plain(data, 48);
        UnitType hall = data.unitTypes().types().get("unit-town-hall");
        UnitType footman = data.unitTypes().types().get("unit-footman");
        assertNotNull(hall);

        Unit building = world.createUnit(hall, 1, 30, 30);
        Unit scout = world.createUnit(footman, 0, 31, 31);
        world.tick();

        assertTrue(world.fog().isVisible(0, 30, 30), "the scout cannot see the hall");
        assertEquals(0, world.seenBuildings().size(0),
                "a building in plain sight should be drawn live, not remembered");

        // The scout dies where it stands.
        world.remove(scout);
        world.tick();

        assertFalse(world.fog().isVisible(0, 30, 30), "the ground is still watched");
        assertEquals(1, world.seenBuildings().size(0),
                "the hall was forgotten the moment the scout died");
        assertTrue(world.isVisibleAsGoal(0, building),
                "a remembered VisibleUnderFog building stopped being a valid goal");

        var memory = world.seenBuildings().forPlayer(0).iterator().next();
        assertEquals(hall, memory.type());
        assertEquals(1, memory.owner(), "the memory forgot whose it was");
        assertEquals(30, memory.tileX());
        assertEquals(30, memory.tileY());
    }

    /** Ground never visited is not remembered, or scouting would be free. */
    @Test
    @DisplayName("A building never seen is not remembered")
    void theUnseenIsNotRemembered() {
        GameData data = load();
        World world = plain(data, 48);
        UnitType hall = data.unitTypes().types().get("unit-town-hall");

        world.createUnit(hall, 1, 30, 30);
        world.createUnit(data.unitTypes().types().get("unit-footman"), 0, 2, 2);
        world.tick();

        assertEquals(0, world.seenBuildings().size(0),
                "a building on ground the player has never visited is on their map");
    }

    /**
     * The counterpart of remembering: a building torn down while you were away
     * stays on your map until you look, and goes the moment you do. That is
     * what upstream's reference-counted corpses produce, and it is the
     * behaviour players rely on when they raze a base and the enemy keeps
     * attacking the empty ground.
     */
    @Test
    @DisplayName("A razed building stays remembered until the player looks again")
    void aRazedBuildingSurvivesInMemory() {
        GameData data = load();
        World world = plain(data, 48);
        UnitType hall = data.unitTypes().types().get("unit-town-hall");
        UnitType footman = data.unitTypes().types().get("unit-footman");

        Unit building = world.createUnit(hall, 1, 30, 30);
        Unit scout = world.createUnit(footman, 0, 31, 31);
        world.tick();
        world.remove(scout);
        world.tick();
        assertEquals(1, world.seenBuildings().size(0));

        // It is destroyed while nobody of player 0 is watching.
        world.remove(building);
        world.tick();
        assertEquals(1, world.seenBuildings().size(0),
                "the player learned the building was gone without looking");

        // Somebody walks back over the spot.
        world.createUnit(footman, 0, 31, 31);
        world.tick();
        assertEquals(0, world.seenBuildings().size(0),
                "the memory outlived a look at the empty ground");
    }

    /** A unit is not a building and must not be tracked through the dark. */
    @Test
    @DisplayName("Ordinary units are not remembered")
    void unitsAreNotRemembered() {
        GameData data = load();
        World world = plain(data, 48);
        UnitType footman = data.unitTypes().types().get("unit-footman");

        world.createUnit(footman, 1, 30, 30);
        Unit scout = world.createUnit(footman, 0, 31, 31);
        world.tick();
        world.remove(scout);
        world.tick();

        assertEquals(0, world.seenBuildings().size(0),
                "an enemy soldier is being remembered under fog, which would let a player"
                        + " track an army they cannot see");
    }

    /** Your own buildings need no memory; you always see them. */
    @Test
    @DisplayName("A player does not remember their own buildings")
    void ownBuildingsAreNotRemembered() {
        GameData data = load();
        World world = plain(data, 48);
        world.createUnit(data.unitTypes().types().get("unit-town-hall"), 0, 30, 30);
        world.tick();
        world.tick();
        assertEquals(0, world.seenBuildings().size(0),
                "a player is remembering their own building as though it were scouted");
    }
}
