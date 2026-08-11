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
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.campaign.Campaign;
import net.chonkbase.chonkcraft.engine.campaign.CampaignStep;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The screen a campaign ends on.
 *
 * <p>Finishing all fourteen missions used to show one sentence this implementation wrote
 * itself. The script has a picture, a paragraph and a recording waiting behind
 * the last map; these draw them.
 */
class CampaignEndingScreenTest {

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

    /**
     * Writes a rendered screen out when asked, for looking at.
     *
     * <p>{@code -Dchonkcraft.screenshot.dir=/tmp/shots} and nothing otherwise: a
     * suite that scatters images through the build directory on every run is a
     * suite people stop reading the output of.
     */
    private static void keep(BufferedImage image, String name) throws java.io.IOException {
        String directory = System.getProperty("chonkcraft.screenshot.dir");
        if (directory == null || directory.isBlank()) {
            return;
        }
        Path into = Paths.get(directory);
        Files.createDirectories(into);
        javax.imageio.ImageIO.write(image, "png", into.resolve(name + ".png").toFile());
    }

    @Test
    @DisplayName("all four endings draw their picture, their prose and their voices")
    void everyEndingDraws() throws java.io.IOException {
        GameData data = load();
        List<Campaign> campaigns = data.campaigns();
        Assumptions.assumeTrue(campaigns.size() == 4, "no campaign scripts in this checkout");

        int drawn = 0;
        int spoken = 0;
        for (Campaign campaign : campaigns) {
            List<CampaignStep> closing = campaign.ending();
            assertEquals(2, closing.size(), campaign.name() + " has no closing steps");
            CampaignStep ending = closing.get(1);
            assertEquals(CampaignStep.Kind.VICTORY, ending.kind());

            String prose = data.briefingText(ending.textPath());
            assertNotNull(prose, campaign.name() + ": no ending text");
            String race = campaign.name().startsWith("orc") ? "orc" : "human";

            BriefingScreen screen = new BriefingScreen(data, race, WIDTH, HEIGHT,
                    ending.path(), "", prose, "Continue", () -> { });
            screen.setSize(WIDTH, HEIGHT);
            BufferedImage image = render(screen);
            keep(image, "ending-" + campaign.name());

            assertNotNull(screen.continueBoundsForTest(), campaign.name() + ": no way onward");
            assertFalse(isBlank(image), campaign.name() + "'s ending drew nothing");
            drawn++;

            screen.speak(null, ending.voices());
            assertEquals(List.of(ending.voices().get(0)), screen.playedVoicesForTest(),
                    campaign.name() + "'s ending did not begin its narration");
            spoken++;
        }
        assertEquals(4, drawn);
        assertEquals(4, spoken);
    }

    @Test
    @DisplayName("the first briefing of each campaign draws on its own page")
    void theOpeningBriefingsDraw() throws java.io.IOException {
        GameData data = load();
        List<Campaign> campaigns = data.campaigns();
        Assumptions.assumeTrue(campaigns.size() == 4, "no campaign scripts in this checkout");

        int drawn = 0;
        for (Campaign campaign : campaigns) {
            var step = campaign.missions().get(0);
            var mission = data.loadMission(step.mapArchivePath(), 0);
            assertNotNull(mission, "could not load " + step.mapArchivePath());
            String race = campaign.name().startsWith("orc") ? "orc" : "human";
            BriefingScreen screen = new BriefingScreen(data, race, WIDTH, HEIGHT,
                    mission.background(), campaign.name() + " - Mission 1", mission.briefing(),
                    "Continue", () -> { });
            screen.setSize(WIDTH, HEIGHT);
            BufferedImage image = render(screen);
            keep(image, "briefing-" + campaign.name());
            assertFalse(isBlank(image), campaign.name() + "'s first briefing drew nothing");
            drawn++;
        }
        assertEquals(4, drawn);
    }

    /** Whether anything was drawn at all: a black panel is what failure looks like. */
    private static boolean isBlank(BufferedImage image) {
        int lit = 0;
        for (int y = 0; y < image.getHeight(); y += 8) {
            for (int x = 0; x < image.getWidth(); x += 8) {
                if ((image.getRGB(x, y) & 0xFFFFFF) != 0) {
                    lit++;
                }
            }
        }
        return lit < (image.getWidth() / 8) * (image.getHeight() / 8) / 10;
    }

    @Test
    @DisplayName("a campaign's closing cutscene is a video the disc has")
    void theClosingCutscenesAreThere() {
        GameData data = load();
        List<Campaign> campaigns = data.campaigns();
        Assumptions.assumeTrue(campaigns.size() == 4, "no campaign scripts in this checkout");
        int videos = 0;
        for (Campaign campaign : campaigns) {
            CampaignStep closing = campaign.ending().get(0);
            assertEquals(CampaignStep.Kind.VIDEO, closing.kind());
            assertTrue(closing.path().endsWith(".ogv"), closing.path());
            if (data.video(closing.path()) != null) {
                videos++;
            }
        }
        // The original two campaigns' closing cutscenes are on every disc; the
        // expansion's are only on its own, so this counts rather than demands.
        assertTrue(videos >= 2, "only " + videos + " closing cutscenes decoded");
    }
}
