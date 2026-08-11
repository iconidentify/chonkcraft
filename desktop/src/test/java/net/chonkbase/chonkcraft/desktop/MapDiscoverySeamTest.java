package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.map.PudReader;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.save.LoadGame;
import net.chonkbase.chonkcraft.engine.save.SaveGame;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Choosing a map, and finding it again in a save.
 *
 * <p>The launcher used to walk the installation directory for {@code *.PUD}
 * three times over -- once for the menu, once for {@code -Dchonkcraft.map}, once in
 * the headless network peer -- and a skirmish save recorded
 * {@code mapFile.toAbsolutePath()}. All four assume the map is a file lying in
 * a directory, which is the one thing that stops being true when the data
 * moves into a pack: a pack entry has no path, so the menu would have come up
 * empty and every skirmish save ever written would have failed to load with
 * "the map this save names is missing" printed over a path that looks
 * perfectly correct.
 *
 * <p>Two properties are pinned here. The menu's list must read exactly as the
 * directory walk left it, name for name and in the same order, because a
 * player knows where their maps are in that list. And a save must find its map
 * whichever way it recorded it: by name, which is what is written now and
 * carries to another machine, or by absolute path, which is what every save
 * written before this one holds.
 */
class MapDiscoverySeamTest {

    /**
     * The 1995 installation, or a skipped test.
     *
     * <p>An installation rather than {@code AssetSource.fromEnvironment}: the
     * first test compares the source's answer against a directory walk of the
     * same installation, and there is nothing to walk when a pack is what is
     * configured.
     */
    private static InstallSource install() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return install;
    }

    /**
     * The walk the launcher used to do, kept here to be compared against.
     *
     * <p>This is {@code Main.findMaps} as it was written: every regular file
     * under the installation whose name ends in {@code .PUD}, sorted by that
     * name. It is the definition of the order the menu is expected to have.
     */
    private static List<String> walkedNames(Path installRoot) throws IOException {
        try (var walk = Files.walk(installRoot)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .toUpperCase(Locale.ROOT).endsWith(".PUD"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(path -> path.getFileName().toString())
                    .toList();
        }
    }

    @Test
    @DisplayName("the map list reads the same as the directory walk left it")
    void theMenuKeepsItsOrder() throws IOException {
        try (InstallSource assets = install()) {
            // The one place the directory behind the source is named: the walk
            // is what the answer is being compared against, so it has to be a
            // real directory. Everything below asks the source.
            Path root = assets.root();
            List<String> walked = walkedNames(root);

            List<Path> menuMaps = Main.findMaps(assets);
            List<String> shown = new ArrayList<>();
            for (Path map : menuMaps) {
                shown.add(map.getFileName().toString());
            }
            if (!walked.isEmpty()) {
                assertTrue(walked.size() >= 8,
                        "only " + walked.size() + " maps under " + root
                                + ": this proves nothing about an order");
                assertEquals(walked, shown,
                        "the menu's map list changed order or membership: a player's "
                                + "fifth map is no longer the fifth entry");
            } else {
                // BNE puts every multiplayer map inside INSTALL.EXE. There is
                // deliberately no directory order to preserve, but an empty
                // filesystem walk must not turn into an empty menu.
                assertTrue(assets.isBattleNetEdition(),
                        "this release has neither loose maps nor BNE embedded maps");
                assertTrue(shown.size() >= 100,
                        "only " + shown.size() + " embedded BNE maps reached the menu");
                assertEquals(assets.mapNames(),
                        menuMaps.stream().map(Path::toString).toList(),
                        "the BNE map names changed while becoming menu paths");
            }
        }
    }

    @Test
    @DisplayName("every map on the list opens, and opens the map it names")
    void everyNamedMapIsTheFileItStandsFor() throws IOException {
        try (InstallSource assets = install()) {
            List<Path> maps = Main.findMaps(assets);
            assertTrue(maps.size() >= 8,
                    "only " + maps.size() + " maps: an empty list passes every check below");

            int checked = 0;
            for (Path map : maps) {
                byte[] served = Main.mapBytes(assets, map);
                assertNotNull(served, "no bytes for " + map);
                // Where that name lies on the disk, which is what the launcher
                // used to hand to PudReader itself.
                Path onDisk = assets.mapPath(map.toString());
                if (onDisk != null) {
                    assertArrayEquals(Files.readAllBytes(onDisk), served,
                            map + " came back as different bytes: two players simulating "
                                    + "from these desynchronise on the first cycle");
                } else {
                    AssetSource.SupplementalAsset embedded = assets.supplementalAssets().stream()
                            .filter(asset -> asset.kind()
                                    == AssetSource.SupplementalAsset.Kind.MAP)
                            .filter(asset -> asset.path().equalsIgnoreCase(
                                    "maps/" + map.toString().replace('\\', '/')))
                            .findFirst()
                            .orElse(null);
                    assertNotNull(embedded,
                            map + " is on the menu but in neither a file nor BNE's MPQ");
                    assertArrayEquals(assets.supplementalAsset(embedded), served,
                            map + " changed between the BNE MPQ and the map menu");
                }
                checked++;
            }
            assertEquals(maps.size(), checked, "not every map was compared");
        }
    }

    /**
     * A saved game naming its map however the caller says.
     *
     * <p>Written through {@code SaveGame} rather than assembled here, so what
     * the test loads is the format the game writes. The world is bare: this is
     * about the one string in the header, and a save of an empty world carries
     * it exactly as a save of a played one does.
     */
    private static Path saveNaming(String mapPath, Path directory) throws IOException {
        World world = new World(new GameMap(16, 16, new Tileset()));
        Path file = directory.resolve("seam" + SaveGame.SUFFIX);
        SaveGame.write(world, mapPath, null, 0, file);
        return file;
    }

    @Test
    @DisplayName("a save that names its map by name opens it again")
    void aSaveNamingItsMapReloads(@TempDir Path directory) throws IOException {
        try (AssetSource assets = install()) {
            Assumptions.assumeTrue(!assets.mapNames().isEmpty(), "no maps in this installation");
            String name = assets.mapNames().getFirst();

            Path file = saveNaming(name, directory);
            LoadGame.Header header = LoadGame.header(LoadGame.read(file));
            assertNotNull(header, "the save has no header");
            assertEquals(name, header.mapPath(), "the name did not survive being written");

            // The control. This is the whole of the old test -- Paths.get on
            // the recorded string, then Files.isRegularFile -- and it has to
            // fail here, or the resolution below is proving nothing.
            assertFalse(Files.isRegularFile(Paths.get(header.mapPath())),
                    "a bare map name must not be a file, or this test cannot tell "
                            + "the two ways of recording a map apart");

            Path resolved = Main.savedMap(assets, header.mapPath());
            assertNotNull(resolved, "the save named " + name + " and it was not found");
            assertFalse(resolved.isAbsolute(),
                    "a map named by name came back as a path: " + resolved);

            PudMap map = PudReader.read(Main.mapBytes(assets, resolved));
            assertTrue(map.width() > 0 && map.height() > 0,
                    "the map loaded to nothing: " + map.width() + " by " + map.height());
        }
    }

    @Test
    @DisplayName("a save written before this, holding a whole path, still opens")
    void anOldSaveHoldingAPathStillReloads(@TempDir Path directory) throws IOException {
        try (InstallSource assets = install()) {
            Assumptions.assumeTrue(!assets.mapNames().isEmpty(), "no maps in this installation");
            String name = assets.mapNames().getFirst();
            Path onDisk = assets.mapPath(name);
            if (onDisk == null) {
                // BNE has no loose map to stand in for a pre-pack save. Put
                // one of its real maps at an absolute legacy location; the
                // old format cared only that the recorded path was a file.
                onDisk = directory.resolve("legacy-map.pud");
                Files.write(onDisk, assets.map(name));
            }
            // The fixture has to be a real file or the old form is not being
            // reproduced.
            assertTrue(Files.isRegularFile(onDisk), onDisk + " is not a file");

            Path file = saveNaming(onDisk.toAbsolutePath().toString(), directory);
            LoadGame.Header header = LoadGame.header(LoadGame.read(file));
            assertNotNull(header, "the save has no header");

            Path resolved = Main.savedMap(assets, header.mapPath());
            assertNotNull(resolved, "an old save stopped loading: " + header.mapPath());
            assertTrue(resolved.isAbsolute(),
                    "the path in an old save was answered with something else: " + resolved);
            assertEquals(onDisk.toAbsolutePath(), resolved,
                    "an old save was pointed at a different file");

            assertArrayEquals(Files.readAllBytes(onDisk), Main.mapBytes(assets, resolved),
                    "the map an old save names came back as different bytes");
        }
    }
}
