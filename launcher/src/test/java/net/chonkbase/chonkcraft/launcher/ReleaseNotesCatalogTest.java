package net.chonkbase.chonkcraft.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReleaseNotesCatalogTest {

    @Test
    @DisplayName("multiline Unicode notes survive the published catalog format")
    void historyRoundTrips() throws Exception {
        var original = new ReleaseNotesCatalog.History(List.of(
                new ReleaseNotesCatalog.Entry("2026.0810.15", "2026-08-10T12:00:00Z",
                        "Oil and ships", "- Tankers return reliably.\n- Peons say “Right-o.”",
                        "abc123")));

        var decoded = ReleaseNotesCatalog.parse(ReleaseNotesCatalog.encode(original));

        assertEquals(original, decoded);
    }

    @Test
    @DisplayName("republishing a version replaces its note instead of duplicating history")
    void versionsAreUnique() {
        var old = new ReleaseNotesCatalog.Entry("1.0.0", "2026-08-09T12:00:00Z",
                "Old title", "Old body", "old");
        var replacement = new ReleaseNotesCatalog.Entry("1.0.0",
                "2026-08-10T12:00:00Z", "Corrected title", "Corrected body", "new");

        var result = ReleaseNotesCatalog.append(
                new ReleaseNotesCatalog.History(List.of(old)), replacement);

        assertEquals(1, result.entries().size());
        assertEquals("Corrected title", result.latest().title());
    }

    @Test
    @DisplayName("unbounded release-note inputs are refused")
    void oversizedHistoryIsRefused() {
        byte[] tooLarge = new byte[(int) ReleaseNotesCatalog.MAX_BYTES + 1];
        assertThrows(IOException.class, () -> ReleaseNotesCatalog.parse(tooLarge));
    }

    @Test
    @DisplayName("the legacy automated placeholder is not exposed as a commit hash")
    void legacyAutomationTextGetsAPlayerFacingMigrationEntry() {
        var release = new GameReleaseManager.Release("1.0.0",
                java.net.URI.create("https://example.test/game.jar"), "0".repeat(64), 10,
                "Automated game update from deadbeef", "deadbeef",
                "2026-08-10T12:00:00Z", null, "", -1);

        var entry = ReleaseNotesCatalog.fromRelease(release).latest();

        assertEquals("Earlier ChonkCraft update", entry.title());
        assertEquals("This release predates detailed release notes.", entry.body());
    }
}
