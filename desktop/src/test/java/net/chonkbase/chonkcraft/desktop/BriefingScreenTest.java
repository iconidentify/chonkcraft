package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The briefing screen, on the game's own prose. */
class BriefingScreenTest {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 800;

    private static GameData load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    private static BufferedImage render(BriefingScreen screen) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = image.createGraphics();
        screen.paint(g2);
        g2.dispose();
        return image;
    }

    @Test
    @DisplayName("the first mission's briefing draws with its own text")
    void theBriefingDraws() {
        GameData data = load();
        var mission = data.loadMission("campaigns/human/level01h", 0);
        Assumptions.assumeTrue(mission != null && mission.briefing() != null,
                "the first human mission is not available");

        BriefingScreen screen = new BriefingScreen(data, "human", WIDTH, HEIGHT,
                mission.background(), "Human Campaign - Mission 1", mission.briefing(),
                "Continue", () -> { });
        screen.setSize(WIDTH, HEIGHT);
        render(screen);

        assertNotNull(screen.continueBoundsForTest(), "no way onward was drawn");
        int panelTop = (HEIGHT - 480) / 2;
        var button = screen.continueBoundsForTest();
        assertTrue(button.y + button.height <= panelTop + 480,
                "the button ran off the bottom of the panel");
    }

    @Test
    @DisplayName("paragraph breaks survive the wrap")
    void paragraphsAreKept() {
        GameData data = load();
        var mission = data.loadMission("campaigns/human/level01h", 0);
        Assumptions.assumeTrue(mission != null && mission.briefing() != null,
                "the first human mission is not available");

        BriefingScreen screen = new BriefingScreen(data, "human", WIDTH, HEIGHT,
                mission.background(), "", mission.briefing(), "Continue", () -> { });
        screen.setSize(WIDTH, HEIGHT);
        render(screen);

        List<String> lines = screen.wrap(mission.briefing(),
                GameFont.load(data, GameFont.Face.GAME), 320);
        assertFalse(lines.isEmpty());
        // The briefings are written as paragraphs; reflowing them into one
        // block would lose the shape the text was given.
        assertTrue(lines.stream().anyMatch(String::isEmpty),
                "the paragraph breaks were flattened away");
        assertTrue(lines.stream().anyMatch(line -> line.contains("Lord Terenas")),
                "the prose did not survive wrapping");
    }

    @Test
    @DisplayName("the button reports once when pressed")
    void theButtonReports() {
        GameData data = load();
        AtomicInteger pressed = new AtomicInteger();
        BriefingScreen screen = new BriefingScreen(data, "human", WIDTH, HEIGHT,
                null, "Mission Accomplished", "Mission 1 is complete.", "Next Mission",
                pressed::incrementAndGet);
        screen.setSize(WIDTH, HEIGHT);
        render(screen);

        screen.pressForTest();
        assertEquals(1, pressed.get());
    }

    @Test
    @DisplayName("the briefing is drawn on the picture its own script names")
    void theScriptsBackgroundIsUsed() {
        GameData data = load();
        var mission = data.loadMission("campaigns/human/level01h", 0);
        Assumptions.assumeTrue(mission != null, "the first human mission is not available");
        assertEquals("../campaigns/human/interface/introscreen1.png", mission.background());

        BriefingScreen own = new BriefingScreen(data, "human", WIDTH, HEIGHT,
                mission.background(), "", mission.briefing(), "Continue", () -> { });
        own.setSize(WIDTH, HEIGHT);
        BufferedImage drawnOnIt = render(own);

        // The fallback is the menu's blank scroll, which is what every briefing
        // in the game used to come up on. The two have to differ, and by more
        // than a rounding: this is the whole of the third gap.
        BriefingScreen fallback = new BriefingScreen(data, "human", WIDTH, HEIGHT,
                null, "", mission.briefing(), "Continue", () -> { });
        fallback.setSize(WIDTH, HEIGHT);
        BufferedImage drawnOnScroll = render(fallback);

        int differing = 0;
        for (int y = 0; y < HEIGHT; y += 4) {
            for (int x = 0; x < WIDTH; x += 4) {
                if (drawnOnIt.getRGB(x, y) != drawnOnScroll.getRGB(x, y)) {
                    differing++;
                }
            }
        }
        int sampled = (HEIGHT / 4) * (WIDTH / 4);
        assertTrue(differing > sampled / 4,
                "the script's background changed " + differing + " of " + sampled + " samples");
    }

    @Test
    @DisplayName("the ten briefing backgrounds all draw, and they are ten pictures")
    void everyBackgroundDraws() throws java.io.IOException {
        GameData data = load();
        Assumptions.assumeTrue(data.campaigns().size() == 4, "no campaign scripts");

        java.util.Map<String, Integer> byBackground = new java.util.TreeMap<>();
        java.util.Set<Integer> shapes = new java.util.HashSet<>();
        int missions = 0;
        for (var campaign : data.campaigns()) {
            for (var step : campaign.missions()) {
                var mission = data.loadMission(step.mapArchivePath(), 0);
                assertNotNull(mission);
                String background = mission.background();
                assertNotNull(background, step.mapArchivePath() + " names no background");
                if (byBackground.putIfAbsent(background, 1) == null) {
                    BriefingScreen screen = new BriefingScreen(data, "human", WIDTH, HEIGHT,
                            background, "", mission.briefing(), "Continue", () -> { });
                    screen.setSize(WIDTH, HEIGHT);
                    BufferedImage drawn = render(screen);
                    // A hash of the top strip, which is background and nothing
                    // else. Ten backgrounds must give ten different answers; a
                    // single shared picture would give one.
                    int hash = 0;
                    for (int x = 0; x < WIDTH; x += 3) {
                        hash = hash * 31 + drawn.getRGB(x, HEIGHT / 2);
                    }
                    shapes.add(hash);
                }
                missions++;
            }
        }
        assertEquals(52, missions, "campaign missions");
        assertEquals(10, byBackground.size(), "distinct backgrounds: " + byBackground.keySet());
        assertEquals(10, shapes.size(), "distinct pictures drawn");
    }

    @Test
    @DisplayName("no briefing in the game runs past its own button")
    void everyBriefingFitsThePage() {
        GameData data = load();
        Assumptions.assumeTrue(data.campaigns().size() == 4, "no campaign scripts");

        int checked = 0;
        for (var campaign : data.campaigns()) {
            for (var step : campaign.missions()) {
                var mission = data.loadMission(step.mapArchivePath(), 0);
                assertNotNull(mission);
                BriefingScreen screen = new BriefingScreen(data, "human", WIDTH, HEIGHT,
                        mission.background(), "Mission", mission.briefing(),
                        "Continue", () -> { });
                screen.setSize(WIDTH, HEIGHT);
                render(screen);
                var button = screen.continueBoundsForTest();
                // The expansion's briefings are nearly twice the length of the
                // originals', and one size for all of them put five paragraphs
                // through the button and off the bottom of the page.
                assertTrue(screen.proseBottomForTest() <= button.y,
                        step.mapArchivePath() + ": prose reaches "
                                + screen.proseBottomForTest() + ", button at " + button.y);
                checked++;
            }
        }
        assertEquals(52, checked);
    }

    @Test
    @DisplayName("the briefing plays every voice its script names, and stops on continue")
    void theBriefingSpeaks() {
        GameData data = load();
        var mission = data.loadMission("campaigns/human/level01h", 0);
        Assumptions.assumeTrue(mission != null, "the first human mission is not available");
        assertEquals(2, mission.voices().size(), "the first mission names two takes");
        for (String voice : mission.voices()) {
            assertNotNull(data.sounds().clip(voice), "no clip for " + voice);
        }

        BriefingScreen screen = new BriefingScreen(data, "human", WIDTH, HEIGHT,
                mission.background(), "", mission.briefing(), "Continue", () -> { });
        screen.setSize(WIDTH, HEIGHT);
        render(screen);

        // No device on a build machine, so the audio is null; what is being
        // proved is that the screen resolves each named file and hands it on,
        // one at a time. Nothing used to call voices() at all.
        screen.speak(null, mission.voices());
        assertEquals(List.of(mission.voices().get(0)), screen.playedVoicesForTest(),
                "the briefing should open on the first take alone");

        screen.pressForTest();
        assertEquals(List.of(mission.voices().get(0)), screen.playedVoicesForTest(),
                "pressing on should stop the narration, not run through it");
    }

    @Test
    @DisplayName("an empty briefing does not break the screen")
    void anEmptyBriefingIsHarmless() {
        GameData data = load();
        // A mission whose text is missing from the installation still has to
        // show its heading and its way onward.
        BriefingScreen screen = new BriefingScreen(data, "human", WIDTH, HEIGHT,
                null, "Mission 1", null, "Continue", () -> { });
        screen.setSize(WIDTH, HEIGHT);
        render(screen);
        assertNotNull(screen.continueBoundsForTest());
    }
}
