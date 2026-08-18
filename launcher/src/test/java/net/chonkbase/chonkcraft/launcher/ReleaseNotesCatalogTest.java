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
    @DisplayName("automatic rebuilds retain only the newest copy of a release story")
    void repeatedReleaseStoriesAreCompacted() {
        var newestDuplicate = new ReleaseNotesCatalog.Entry("2026.0818.23",
                "2026-08-18T14:40:29Z", "Smarter Armies",
                "- Units follow orders reliably.", "newest");
        var sameTitleOlderCopy = new ReleaseNotesCatalog.Entry("2026.0818.22",
                "2026-08-18T00:14:26Z", "Smarter Armies",
                "- Earlier wording of the same release.", "older-title");
        var sameBodyOlderCopy = new ReleaseNotesCatalog.Entry("2026.0817.21",
                "2026-08-17T13:29:46Z", "Automatic rebuild",
                "- Units follow orders reliably.", "older-body");
        var distinctRelease = new ReleaseNotesCatalog.Entry("2026.0816.19",
                "2026-08-16T17:13:28Z", "Reliable Siege",
                "- Catapults fire once per committed attack.", "distinct");

        var result = ReleaseNotesCatalog.append(
                new ReleaseNotesCatalog.History(List.of(
                        sameTitleOlderCopy, sameBodyOlderCopy, distinctRelease)),
                newestDuplicate);

        assertEquals(List.of(newestDuplicate, distinctRelease), result.entries());
    }

    @Test
    @DisplayName("distinct historical releases retain newest-first order")
    void distinctReleaseStoriesRemainVisible() {
        var latest = new ReleaseNotesCatalog.Entry("3.0.0",
                "2026-08-18T12:00:00Z", "Combat timing", "Combat notes", "c");
        var middle = new ReleaseNotesCatalog.Entry("2.0.0",
                "2026-08-17T12:00:00Z", "Campaign saves", "Save notes", "b");
        var oldest = new ReleaseNotesCatalog.Entry("1.0.0",
                "2026-08-16T12:00:00Z", "Oil hauling", "Oil notes", "a");

        var result = ReleaseNotesCatalog.append(
                new ReleaseNotesCatalog.History(List.of(middle, oldest)), latest);

        assertEquals(List.of(latest, middle, oldest), result.entries());
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
