package net.chonkbase.chonkcraft.engine.save;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringWriter;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.MapField;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ground a game changed, across a save.
 *
 * <p>A save wrote the explored bits and the buildings each player remembered
 * and nothing whatever about the map itself, and nothing read any back. What a
 * player saw: a wood cleared over an hour of chopping was standing again on
 * resuming, the squares impassable, with the woodcutter that had cut its way
 * into the stand now inside the trees; a wall an army had spent five minutes
 * breaching was whole again, and the hole it had walked through was gone. A
 * measured example -- a peasant felling one tree on the seventh human mission,
 * saved and reloaded -- came back with the tree standing, the square carrying
 * {@code FOREST} and {@code UNPASSABLE} again, its hundred wood restored, and
 * the peasant standing in the middle of it.
 *
 * <p>Upstream writes every square of the map into the save:
 * {@code CMap::Save},, loops over
 * {@code CMapField::Save}. This implementation writes
 * only the squares that differ from the map file, because unlike upstream --
 * which stubs {@code SetTile} out while the map reloads,
 * The game it reloads the map and keeps what the
 * map says.
 *
 * <p>Every test here fells its tree and breaches its wall by giving a unit an
 * order and running the game, not by calling {@code clearWoodTile} or
 * {@code hitWall}. Those two were never wrong: they change the map correctly
 * and always have, which is why this survived. What was missing is between the
 * simulation and the file.
 */
class SavedTerrainTest {

    /** How wide the fixture's map is, and how deep. */
    private static final int SIZE = 32;

    private static final int FOREST_X = 10;
    private static final int FOREST_Y = 10;
    private static final int WALL_X = 20;
    private static final int WALL_Y = 10;

    /**
     * Real tile codes and real terrain flags, lifted off a shipped map.
     *
     * <p>The fixture's own map is built by hand so that a peasant can be put
     * beside one tree and a footman beside one wall with nothing else on the
     * board -- on the real seventh mission a lone footman sent at that wall is
     * killed from behind it before the wall comes down. The codes and flags are
     * the game's, because {@code clearWoodTile} and {@code hitWall} both
     * re-derive a square's picture from the tileset's transition tables, and a
     * made-up code would exercise none of that.
     */
    private record Sample(GameData data, PudMap.Tileset tilesetKind, Tileset tileset,
            int grass, long grassFlags,
            int forest, long forestFlags, int wall, long wallFlags) {}

    private static Sample sample() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install configured. Set -Dchonkcraft.pack or wc2.install.dir.");
        GameData data = new GameData(assets);
        PudMap source = data.campaignMap("campaigns/human/level07h");
        Assumptions.assumeTrue(source != null, "the seventh human mission is not available");
        Tileset tileset = data.loadTileset(source.tileset()).tileset();
        GameMap real = GameMap.from(source, tileset);

        int grass = -1;
        long grassFlags = 0;
        int forest = -1;
        long forestFlags = 0;
        int wall = -1;
        long wallFlags = 0;
        for (int y = 0; y < real.height(); y++) {
            for (int x = 0; x < real.width(); x++) {
                MapField field = real.field(x, y);
                if (field.isForest() && forest < 0) {
                    forest = field.tile();
                    forestFlags = field.flags();
                } else if (field.isWall() && wall < 0) {
                    wall = field.tile();
                    wallFlags = field.flags();
                } else if (grass < 0 && field.isLandPassable() && !field.isForest()
                        && !field.isWall() && !field.hasFlag(TileFlag.ROCKS)) {
                    grass = field.tile();
                    grassFlags = field.flags();
                }
            }
        }
        Assumptions.assumeTrue(grass >= 0 && forest >= 0 && wall >= 0,
                "the seventh human mission has no forest, wall and open ground to copy");
        return new Sample(data, source.tileset(), tileset, grass, grassFlags,
                forest, forestFlags, wall, wallFlags);
    }

    /**
     * Open ground with a three by three stand of trees and a wall across it.
     *
     * <p>The tree the peasant is sent at has six wood left rather than a
     * hundred, so the test does not have to sit through the forty seconds of
     * game time a whole square takes; the other eight are untouched, and are
     * the control that says a save which restores the ground has not simply
     * cleared it.
     */
    private static GameMap ground(Sample sample) {
        return ground(sample, sample.tileset());
    }

    private static GameMap ground(Sample sample, Tileset tileset) {
        GameMap map = new GameMap(SIZE, SIZE, tileset);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                MapField field = map.field(x, y);
                field.setTile(sample.grass());
                field.setFlags(sample.grassFlags());
            }
        }
        for (int y = FOREST_Y; y < FOREST_Y + 3; y++) {
            for (int x = FOREST_X; x < FOREST_X + 3; x++) {
                MapField field = map.field(x, y);
                field.setTile(sample.forest());
                field.setFlags(sample.forestFlags());
                field.setValue(GameMap.WOOD_PER_FOREST_TILE);
            }
        }
        map.field(FOREST_X, FOREST_Y).setValue(6);
        for (int x = WALL_X; x < WALL_X + 3; x++) {
            MapField field = map.field(x, WALL_Y);
            field.setTile(sample.wall());
            field.setFlags(sample.wallFlags());
            field.setValue(GameMap.WALL_HIT_POINTS);
        }
        map.recordLoadedTerrain();
        return map;
    }

    private static World world(Sample sample, GameMap map) {
        World world = new World(map);
        world.setUpgrades(sample.data().upgrades().upgrades());
        world.setUnitTypes(sample.data().unitTypes().types());
        world.setMissileTypes(sample.data().missiles().types());
        return world;
    }

    /** A game in which a tree has been felled and a wall breached, and the peasant. */
    private record Cleared(Sample sample, World world, GameMap map, Unit peasant, Unit footman) {}

    private static Cleared cleared() {
        Sample sample = sample();
        GameMap map = ground(sample);
        World world = world(sample, map);
        var types = sample.data().unitTypes().types();

        Unit peasant = world.createUnit(types.get("unit-peasant"), 0, FOREST_X - 1, FOREST_Y);
        assertTrue(world.orderHarvest(peasant, FOREST_X, FOREST_Y),
                "the fixture could not order a peasant to chop");
        Unit footman = world.createUnit(types.get("unit-footman"), 0, WALL_X, WALL_Y + 1);
        assertTrue(world.orderAttackGround(footman, WALL_X, WALL_Y),
                "the fixture could not order a footman at the wall");

        for (int cycle = 0; cycle < 4000; cycle++) {
            world.tick();
            if (!map.field(FOREST_X, FOREST_Y).isForest()
                    && !map.field(WALL_X, WALL_Y).isWall()) {
                break;
            }
        }
        assertFalse(map.field(FOREST_X, FOREST_Y).isForest(),
                "the fixture's peasant never felled its tree, so it proves nothing");
        assertFalse(map.field(WALL_X, WALL_Y).isWall(),
                "the fixture's footman never breached its wall, so it proves nothing");
        // The next tree along, chopped at but not down. The first peasant is
        // carrying the wood from the felled square and retail correctly makes
        // it deliver before accepting more. Use a second real worker so this
        // setup continues to exercise the normal harvest order without
        // depending on the old loaded-worker behavior.
        Unit secondPeasant = world.createUnit(
                types.get("unit-peasant"), 0, FOREST_X + 1, FOREST_Y - 1);
        assertTrue(world.orderHarvest(secondPeasant, FOREST_X + 1, FOREST_Y),
                "the fixture could not send a second peasant at the next tree");
        for (int cycle = 0; cycle < 600; cycle++) {
            world.tick();
        }
        MapField second = map.field(FOREST_X + 1, FOREST_Y);
        assertTrue(second.isForest() && second.value() > 0
                        && second.value() < GameMap.WOOD_PER_FOREST_TILE,
                "the fixture's second tree holds " + second.value() + " wood, so it is either "
                        + "untouched or gone and proves nothing");
        return new Cleared(sample, world, map, peasant, footman);
    }

    private static String save(Cleared cleared) throws IOException {
        StringWriter out = new StringWriter();
        SaveGame.write(cleared.world(), "test-map", null, 0, out);
        return out.toString();
    }

    /** The same board as the fixture started from, and the save replayed onto it. */
    private static World reload(Cleared cleared, String script) {
        GameMap map = ground(cleared.sample());
        World reloaded = world(cleared.sample(), map);
        LoadGame.apply(reloaded, script, cleared.sample().data().unitTypes().types());
        return reloaded;
    }

    /** Reloads through the fresh tileset instance a real F12 load creates. */
    private static World reloadFresh(Cleared cleared, String script) {
        Tileset fresh = cleared.sample().data().loadTileset(
                cleared.sample().tilesetKind()).tileset();
        GameMap map = ground(cleared.sample(), fresh);
        World reloaded = world(cleared.sample(), map);
        LoadGame.apply(reloaded, script, cleared.sample().data().unitTypes().types());
        return reloaded;
    }

    @Test
    @DisplayName("runtime terrain pictures survive a fresh tileset instance")
    void runtimeTerrainPicturesSurviveFreshTileset() throws IOException {
        Cleared cleared = cleared();
        java.util.List<GameMap.TerrainChange> changes =
                cleared.map().terrainChangedSinceLoad();
        assertTrue(changes.stream().anyMatch(change ->
                        change.tile() >= cleared.sample().data().loadTileset(
                                cleared.sample().tilesetKind()).tileset().tileCount()),
                "the fixture minted no runtime-only tile code, so it cannot reproduce F12");

        World reloaded = reloadFresh(cleared, save(cleared));
        for (GameMap.TerrainChange change : changes) {
            int restored = reloaded.map().tileset().graphicFor(
                    reloaded.map().field(change.x(), change.y()).tile());
            assertEquals(change.graphic(), restored,
                    "the terrain picture changed at " + change.x() + "," + change.y());
            assertTrue(restored != 0,
                    "a runtime transition became the solid black tile at "
                            + change.x() + "," + change.y());
        }
    }

    @Test
    @DisplayName("schema-two runtime terrain codes migrate without black squares")
    void oldRuntimeTerrainCodesDoNotBecomeBlack() throws IOException {
        Cleared cleared = cleared();
        String legacy = save(cleared)
                .replace("SaveFormat(\"chonkcraft-save\", 3)",
                        "SaveFormat(\"chonkcraft-save\", 2)")
                .replaceAll("SetSavedTile\\(([-0-9]+), ([-0-9]+), ([-0-9]+), "
                                + "(\\\"0x[0-9a-f]+\\\"), ([-0-9]+), [-0-9]+\\)",
                        "SetSavedTile($1, $2, $3, $4, $5)");

        World reloaded = reloadFresh(cleared, legacy);
        for (GameMap.TerrainChange change : cleared.map().terrainChangedSinceLoad()) {
            int restored = reloaded.map().tileset().graphicFor(
                    reloaded.map().field(change.x(), change.y()).tile());
            assertTrue(restored != 0,
                    "legacy runtime code became the solid black tile at "
                            + change.x() + "," + change.y());
        }
    }

    @Test
    @DisplayName("a wood that was cut down is still cut down when the game is opened again")
    void felledWoodStaysFelled() throws IOException {
        Cleared cleared = cleared();
        MapField felled = cleared.map().field(FOREST_X, FOREST_Y);
        int code = felled.tile();

        World reloaded = reload(cleared, save(cleared));
        MapField after = reloaded.map().field(FOREST_X, FOREST_Y);

        assertFalse(after.isForest(),
                "the felled tree was standing again: the save carries no terrain flags");
        assertFalse(after.hasFlag(TileFlag.UNPASSABLE),
                "the cleared square was impassable again, so nothing could walk over it");
        assertEquals(code, after.tile(),
                "the square drew trees again: the save carries no tile codes");
        assertEquals(0, after.value(),
                "the felled square handed out another hundred wood after the reload");
        assertTrue(reloaded.map().field(FOREST_X + 1, FOREST_Y + 1).isForest(),
                "the untouched trees are gone too, so the save is clearing ground rather than "
                        + "restoring it");
    }

    @Test
    @DisplayName("the woodcutter is not inside the trees again after a reload")
    void theWorkerIsNotBuriedInTerrainItCleared() throws IOException {
        Cleared cleared = cleared();
        World world = cleared.world();
        // Where a woodcutter ends up: standing on ground it felled itself.
        assertTrue(world.orderMove(cleared.peasant(), FOREST_X, FOREST_Y),
                "the fixture could not walk the peasant onto the ground it cleared");
        for (int cycle = 0; cycle < 400; cycle++) {
            world.tick();
            if (cleared.peasant().tileX() == FOREST_X && cleared.peasant().tileY() == FOREST_Y) {
                break;
            }
        }
        assertEquals(FOREST_X, cleared.peasant().tileX(),
                "the fixture never got the peasant onto the felled square");
        assertEquals(FOREST_Y, cleared.peasant().tileY());

        World reloaded = reload(cleared, save(cleared));
        Unit peasant = find(reloaded, "unit-peasant");
        MapField under = reloaded.map().field(peasant.tileX(), peasant.tileY());

        assertTrue(under.isLandPassable(),
                "the wood grew back over the worker: it is standing inside terrain nothing "
                        + "can enter or leave");
    }

    @Test
    @DisplayName("a wall an army broke through is still broken through")
    void aBreachedWallStaysBreached() throws IOException {
        Cleared cleared = cleared();
        int code = cleared.map().field(WALL_X, WALL_Y).tile();

        World reloaded = reload(cleared, save(cleared));
        MapField after = reloaded.map().field(WALL_X, WALL_Y);

        assertFalse(after.isWall(),
                "the breach closed over the save and the wall was whole again");
        assertFalse(after.hasFlag(TileFlag.UNPASSABLE),
                "the army could no longer walk through the hole it had made");
        assertEquals(code, after.tile(), "the breach was drawn as an intact wall again");
        assertTrue(reloaded.map().field(WALL_X + 1, WALL_Y).isWall(),
                "the rest of the wall came down too, so the save is not restoring what it found");
    }

    @Test
    @DisplayName("a tree half way down keeps the wood it has left")
    void aPartlyChoppedTreeKeepsWhatIsLeftInIt() throws IOException {
        Cleared cleared = cleared();
        int left = cleared.map().field(FOREST_X + 1, FOREST_Y).value();

        World reloaded = reload(cleared, save(cleared));
        MapField after = reloaded.map().field(FOREST_X + 1, FOREST_Y);

        assertEquals(left, after.value(),
                "the half-chopped tree came back whole, worth a full hundred wood again");
        assertTrue(after.isForest(),
                "the half-chopped square lost its trees instead of its wood");
    }

    @Test
    @DisplayName("every square of the map comes back the way the game left it")
    void theWholeMapComesBackAsItStood() throws IOException {
        Cleared cleared = cleared();
        String script = save(cleared);
        World reloaded = reload(cleared, script);
        GameMap untouched = ground(cleared.sample());

        int changed = 0;
        int wrong = 0;
        String first = null;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                String was = describe(cleared.map().field(x, y));
                String now = describe(reloaded.map().field(x, y));
                if (!was.equals(describe(untouched.field(x, y)))) {
                    changed++;
                }
                if (!was.equals(now)) {
                    wrong++;
                    if (first == null) {
                        first = x + "," + y + " was " + was + " and came back " + now;
                    }
                }
            }
        }
        // An empty sweep passes vacuously: if the fixture had changed nothing,
        // a save that carries nothing would compare equal everywhere.
        assertTrue(changed >= 4,
                "the fixture only changed " + changed + " squares, which proves nothing");
        assertEquals(0, wrong, "squares came back different from how the game left them: " + first);
    }

    @Test
    @DisplayName("a save carries the ground that changed and not the whole map")
    void onlyTheChangedGroundIsWritten() throws IOException {
        Cleared cleared = cleared();
        String script = save(cleared);

        long lines = script.lines().filter(line -> line.startsWith("SetSavedTile(")).count();
        assertEquals(cleared.map().terrainChangedSinceLoad().size(), lines,
                "the save does not name every square the game changed");
        assertTrue(lines > 0, "the save says nothing at all about the ground");
        assertTrue(lines < SIZE * SIZE / 4,
                "the save wrote " + lines + " of " + (SIZE * SIZE) + " squares, which is a dump "
                        + "of the map rather than what the game changed");
    }

    @Test
    @DisplayName("a save written before the ground was carried still opens")
    void anOlderSaveStillLoads() throws IOException {
        Cleared cleared = cleared();
        // Exactly what this implementation wrote until now: every line of a current save
        // except the ones describing the ground.
        String older = save(cleared).lines()
                .filter(line -> !line.startsWith("SetSavedTile("))
                .reduce(new StringBuilder(), (text, line) -> text.append(line).append('\n'),
                        StringBuilder::append)
                .toString();
        assertFalse(older.contains("SetSavedTile"), "the older save was not stripped");

        World reloaded = reload(cleared, older);

        assertTrue(reloaded.units().size() >= 2,
                "an older save no longer loads at all: its units did not arrive");
        assertTrue(reloaded.map().field(FOREST_X, FOREST_Y).isForest(),
                "an older save says nothing about the ground, so the map's own terrain must "
                        + "stand");
        assertTrue(reloaded.map().field(WALL_X, WALL_Y).isWall(),
                "an older save says nothing about the wall, so the map's own wall must stand");
    }

    /** A square as tile code, terrain flags and spare value. */
    private static String describe(MapField field) {
        return field.tile() + "/" + Long.toHexString(field.flags() & ~GameMap.OCCUPANCY_FLAGS)
                + "/" + field.value();
    }

    private static Unit find(World world, String ident) {
        return world.units().stream()
                .filter(unit -> unit.type() != null && ident.equals(unit.type().ident()))
                .findFirst().orElseThrow();
    }
}
