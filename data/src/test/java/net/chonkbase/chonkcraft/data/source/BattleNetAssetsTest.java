package net.chonkbase.chonkcraft.data.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.chonkbase.chonkcraft.data.GraphicsIndex;
import net.chonkbase.chonkcraft.data.NameTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BattleNetAssetsTest {

    @Test
    void generatedIndexCarriesTheCompleteNamedBnePayload(@TempDir Path root)
            throws IOException {
        Files.write(root.resolve("INSTALL.EXE"), new byte[16]);
        Path isp = Files.createDirectories(root.resolve("ISP"));
        Files.write(isp.resolve("INSTALL.EXE"), new byte[4]);

        try (BattleNetAssets assets = BattleNetAssets.tryAt(root)) {
            assertNotNull(assets);
            Map<AssetSource.SupplementalAsset.Kind, Long> byKind =
                    assets.assets().stream().collect(Collectors.groupingBy(
                            AssetSource.SupplementalAsset::kind, Collectors.counting()));
            assertEquals(684, assets.assets().size());
            assertEquals(494L, byKind.get(AssetSource.SupplementalAsset.Kind.SOUND));
            assertEquals(153L, byKind.get(AssetSource.SupplementalAsset.Kind.MAP));
            assertEquals(13L, byKind.get(AssetSource.SupplementalAsset.Kind.VIDEO));
            assertEquals(20L, byKind.get(AssetSource.SupplementalAsset.Kind.MUSIC));
            assertTrue(assets.assets().stream().anyMatch(asset ->
                    asset.path().equals("sounds/human/basic_voices/selected/1.wav")));
            assertTrue(assets.assets().stream().anyMatch(asset ->
                    asset.path().equals("maps/All You Need BNE.pud")));
        }
    }

    @Test
    void namedVoicesAndMoviesRecreateTheirClassicArchiveSlots(@TempDir Path root)
            throws IOException {
        Files.write(root.resolve("INSTALL.EXE"), new byte[16]);
        GraphicsIndex index = GraphicsIndex.load(
                NameTable.from(new byte[] {1, 0}), true);

        try (BattleNetAssets assets = BattleNetAssets.tryAt(root)) {
            BattleNetArchive sounds = BattleNetArchive.create(
                    ArchiveIds.SFXDAT, index, assets, null);
            BattleNetArchive movies = BattleNetArchive.create(
                    ArchiveIds.MUDDAT, index, assets, null);
            BattleNetArchive speech = BattleNetArchive.create(
                    ArchiveIds.SNDDAT, index, assets, null);
            BattleNetArchive main = BattleNetArchive.create(
                    ArchiveIds.MAINDAT, index, assets, null);

            assertNotNull(sounds);
            assertEquals(385, sounds.entryCount());
            assertEquals(382, IntStream.range(0, sounds.entryCount())
                    .filter(sounds::isValid).count());
            assertTrue(sounds.isValid(5),
                    "human basic selected 1 must occupy classic SFXDAT entry 5");
            assertFalse(sounds.isValid(68),
                    "BNE does not carry the classic placeholder spell sound");

            assertNotNull(movies);
            assertEquals(19, movies.entryCount());
            assertEquals(12, IntStream.range(0, movies.entryCount())
                    .filter(movies::isValid).count());

            assertNotNull(speech);
            assertTrue(speech.isValid(4),
                    "BNE's 16-bit first human briefing must replace SNDDAT entry 4");
            assertNotNull(main);
            assertTrue(main.isValid(430),
                    "BNE's named Blizzard movie must replace MAINDAT entry 430");
        }
    }
}
