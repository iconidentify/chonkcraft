package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a map's own units become when the world takes them in.
 *
 * <p>ChonkCraft wraps {@code CreateUnit} itself ({@code scripts/wc2.legacy-declaration:117-140})
 * and every load-time creation goes through the wrapper: a slot nobody plays
 * gets no units at all, the neutral slot's are left exactly as written, and
 * everybody else's are converted to the owner's own race by the equivalence
 * table the same file builds. {@code campaigns/orc-exp/levelx09o} is why this
 * matters: it asks for fifty-two skeletons on a slot whose race is human, the
 * real game fields fifty-two militia, and this implementation fielded a skeleton army
 * standing where upstream has other troops entirely.
 */
class MapCreationRulesTest {

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static UnitType type(String ident) {
        UnitType made = new UnitType(ident);
        made.setTileSize(1, 1);
        made.setBoxSize(31, 31);
        made.setHitPoints(40);
        made.setSpeed(10);
        made.setLandUnit(true);
        return made;
    }

    /** A world whose slot 0 is a human computer and slot 2 is nobody. */
    private static World world(UnitType... types) {
        PudMap.PlayerType[] slots = new PudMap.PlayerType[16];
        java.util.Arrays.fill(slots, PudMap.PlayerType.NOBODY);
        slots[0] = PudMap.PlayerType.COMPUTER;
        slots[15] = PudMap.PlayerType.NEUTRAL;
        PudMap.Race[] races = new PudMap.Race[16];
        java.util.Arrays.fill(races, PudMap.Race.NEUTRAL);
        races[0] = PudMap.Race.HUMAN;
        Player[] players = new Player[16];
        for (int i = 0; i < 16; i++) {
            players[i] = new Player(i, slots[i], races[i]);
        }
        World world = new World(grass(20), players);
        java.util.Map<String, UnitType> byIdent = new java.util.LinkedHashMap<>();
        for (UnitType each : types) {
            byIdent.put(each.ident(), each);
        }
        world.setUnitTypes(byIdent);
        world.setRaceEquivalents(
                Map.of("unit-skeleton", "unit-attack-peasant"),
                Map.of("unit-attack-peasant", "unit-skeleton"));
        return world;
    }

    @Test
    @DisplayName("a human slot's skeletons come into the world as militia")
    void aUnitIsConvertedToItsOwnersRace() {
        UnitType skeleton = type("unit-skeleton");
        UnitType militia = type("unit-attack-peasant");
        World world = world(skeleton, militia);

        Unit made = world.createUnitForMap(skeleton, 0, 5, 5);
        assertNotNull(made, "the unit was refused outright");
        assertEquals("unit-attack-peasant", made.type().ident(),
                "the map asked for a skeleton on a human slot and got one. Every"
                        + " load-time creation goes through wc2.legacy-declaration's wrapper, which"
                        + " converts to the owner's race -- levelx09o's fifty-two"
                        + " skeletons are fifty-two militia in the real game");
    }

    @Test
    @DisplayName("a slot nobody plays gets no units at all")
    void aNobodySlotGetsNothing() {
        UnitType skeleton = type("unit-skeleton");
        World world = world(skeleton);

        assertNull(world.createUnitForMap(skeleton, 2, 5, 5),
                "a unit was made for a slot the map itself declares nobody;"
                        + " CclCreateUnit refuses these -- 'player does not exist' --"
                        + " and the wrapper above it returns nil first");
    }

    @Test
    @DisplayName("and the neutral slot's units are left exactly as written")
    void theNeutralSlotIsUntouched() {
        UnitType skeleton = type("unit-skeleton");
        UnitType militia = type("unit-attack-peasant");
        World world = world(skeleton, militia);

        Unit made = world.createUnitForMap(skeleton, 15, 5, 5);
        assertNotNull(made, "the neutral slot's unit was refused");
        assertEquals("unit-skeleton", made.type().ident(),
                "the neutral slot's unit was converted; the wrapper leaves player"
                        + " fifteen exactly as the map wrote it, which is what keeps a"
                        + " gold mine a gold mine");
    }
}
