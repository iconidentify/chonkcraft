package net.chonkbase.chonkcraft.data.source;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import net.chonkbase.assetpack.AssetKind;
import net.chonkbase.assetpack.AssetPackWriter;
import net.chonkbase.assetpack.Codec;
import net.chonkbase.assetpack.PackManifest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Playable media carries the original computer-player program the engine interprets. */
class AssetSourcePlayabilityTest {

    @TempDir
    Path temporary;

    @Test
    @DisplayName("a pack carrying main entry 277 has the retail AI program")
    void retailAiSlotMakesPackPlayable() {
        Path file = temporary.resolve("retail.chonkpack");
        try (AssetPackWriter writer = new AssetPackWriter(file, identity())) {
            int ai = writer.add("archives/maindat/0277", AssetKind.BINARY, Codec.STORE,
                    "assets/archives/maindat/0277.bin", new byte[] {1, 2, 3, 4},
                    4, Map.of());
            int[] slots = new int[278];
            Arrays.fill(slots, -1);
            slots[277] = ai;
            writer.archive(1000, "maindat", slots);
            writer.finish();
        }

        try (PackSource source = PackSource.open(file)) {
            assertTrue(source.hasRetailAiProgram());
        }
    }

    @Test
    @DisplayName("a pack without main entry 277 is not playable media")
    void missingRetailAiSlotIsNotPlayable() {
        Path file = temporary.resolve("incomplete.chonkpack");
        try (AssetPackWriter writer = new AssetPackWriter(file, identity())) {
            writer.finish();
        }

        try (PackSource source = PackSource.open(file)) {
            assertFalse(source.hasRetailAiProgram());
        }
    }

    private static PackManifest.Identity identity() {
        return new PackManifest.Identity(
                "wc2-retail-test", "Retail test media", "test",
                "test builder", "2026-08-09T12:00:00Z", Map.of());
    }
}
