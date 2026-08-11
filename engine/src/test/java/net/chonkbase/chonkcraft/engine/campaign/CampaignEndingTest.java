package net.chonkbase.chonkcraft.engine.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The three things a campaign carries and nothing used to read: the ending
 * after the last mission, the picture behind each briefing, and the voice
 * reading it.
 *
 * <p>Every assertion here counts as well as checks. A test that walks what it
 * found and declares it all loadable passes perfectly when it found nothing,
 * which is exactly how a campaign could go fourteen missions without an ending
 * and fifty-two briefings without a background while the suite stayed green.
 */
class CampaignEndingTest {

    private static GameData load() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No Warcraft II retail assets configured.");
        return new GameData(assets);
    }

    private static List<Campaign> campaigns(GameData data) {
        List<Campaign> campaigns = data.campaigns();
        Assumptions.assumeTrue(campaigns.size() == 4, "no campaign scripts in this checkout");
        return campaigns;
    }

    @Test
    @DisplayName("every campaign ends in a cutscene and an ending, after its last map")
    void everyCampaignHasAnEnding() {
        GameData data = load();
        int endings = 0;
        for (Campaign campaign : campaigns(data)) {
            List<CampaignStep> closing = campaign.ending();
            // The four scripts agree on the shape: a video, then the ending.
            assertEquals(2, closing.size(), campaign.name() + " does not close on two steps");
            assertEquals(CampaignStep.Kind.VIDEO, closing.get(0).kind(),
                    campaign.name() + " has no closing cutscene");
            CampaignStep victory = closing.get(1);
            assertEquals(CampaignStep.Kind.VICTORY, victory.kind(),
                    campaign.name() + " has no ending");
            // And it is unreachable through introducing(), which is the reason
            // it went unplayed: that gathers only what comes before a mission.
            assertFalse(campaign.introducing(campaign.missions().size()).contains(victory),
                    campaign.name() + "'s ending is reachable as an introduction");
            endings++;
        }
        assertEquals(4, endings);
    }

    @Test
    @DisplayName("each ending's picture, prose and voices come out of the archive")
    void everyEndingResolves() {
        GameData data = load();
        int pictures = 0;
        int texts = 0;
        int voices = 0;
        for (Campaign campaign : campaigns(data)) {
            CampaignStep ending = campaign.ending().get(1);

            var picture = data.image(ending.path());
            assertNotNull(picture, campaign.name() + ": no picture at " + ending.path());
            assertNotNull(data.paletteFor(ending.path()),
                    campaign.name() + ": no palette for " + ending.path());
            assertEquals(640, picture.width(), campaign.name() + "'s ending is not a full page");
            assertEquals(480, picture.height(), campaign.name() + "'s ending is not a full page");
            pictures++;

            String prose = data.briefingText(ending.textPath());
            assertNotNull(prose, campaign.name() + ": no text at " + ending.textPath());
            // A paragraph, not the tail of the one before it. Both of the
            // original campaigns' endings used to come back cut: the human's
            // began ">0Quiet settles" and the orc's was the three characters
            // "in.", because the stand-in row for a disc without the expansion
            // won the path and it names the same entry four bytes early.
            assertTrue(prose.length() > 200,
                    campaign.name() + "'s ending is " + prose.length() + " characters");
            assertTrue(Character.isLetter(prose.charAt(0)),
                    campaign.name() + "'s ending opens on " + prose.charAt(0));
            texts++;

            assertFalse(ending.voices().isEmpty(), campaign.name() + "'s ending is silent");
            for (String voice : ending.voices()) {
                assertNotNull(data.sounds().clip(voice),
                        campaign.name() + ": no clip for " + voice);
                voices++;
            }
        }
        assertEquals(4, pictures);
        assertEquals(4, texts);
        // One recording each for Tides of Darkness, two and three for the
        // expansion's, exactly as the four scripts list them.
        assertEquals(7, voices);
    }

    @Test
    @DisplayName("a briefing voice reaches the mixer as sound rather than silence")
    void aBriefingVoiceActuallyPlays() {
        GameData data = load();
        var mission = data.loadMission("campaigns/human/level01h", 0);
        Assumptions.assumeTrue(mission != null, "the first human mission is not available");
        assertFalse(mission.voices().isEmpty(), "the first mission's briefing names no voices");

        var clip = data.sounds().clip(mission.voices().get(0));
        assertNotNull(clip, "no clip for " + mission.voices().get(0));
        assertTrue(clip.frameCount() > 0, "the clip decoded to nothing");

        // Through the mixer rather than merely decoded. There is no output
        // device on a build machine, so the proof is that rendering a block
        // with the voice playing gives something other than silence: the clip
        // resolves, the mixer accepts it, and samples come out.
        var mixer = new net.chonkbase.runtime.audio.AudioMixer();
        long voice = mixer.play(clip, net.chonkbase.runtime.audio.AudioBus.MUSIC,
                false, 0f, 0f, 30);
        assertTrue(voice != net.chonkbase.runtime.audio.AudioMixer.NO_VOICE,
                "the mixer refused the briefing voice");
        float[] block = new float[1024 * net.chonkbase.runtime.audio.AudioMixer.OUTPUT_CHANNELS];
        boolean heard = false;
        for (int attempt = 0; attempt < 8 && !heard; attempt++) {
            mixer.render(block, 1024);
            for (float sample : block) {
                if (sample != 0f) {
                    heard = true;
                    break;
                }
            }
        }
        assertTrue(heard, "the briefing voice rendered as silence");
    }

    @Test
    @DisplayName("the two original endings read as they were written")
    void theOriginalEndingsAreWhole() {
        GameData data = load();
        Assumptions.assumeTrue(data.hasExpansion(),
                "a disc without the expansion has only the stand-in ending texts");
        assertTrue(data.briefingText("campaigns/human/victory.txt")
                .startsWith("Quiet settles over the Black Morass"));
        assertTrue(data.briefingText("campaigns/orc/victory.txt")
                .startsWith("The victory pyres burn high"));
    }

    @Test
    @DisplayName("all fifty-two briefings name a background that decodes")
    void everyBriefingHasItsOwnBackground() {
        GameData data = load();
        int checked = 0;
        java.util.Set<String> distinct = new java.util.TreeSet<>();
        for (Campaign campaign : campaigns(data)) {
            for (CampaignStep step : campaign.missions()) {
                String path = step.mapArchivePath();
                Mission mission = data.loadMission(path, 0);
                assertNotNull(mission, "could not load " + path);
                String background = mission.background();
                assertNotNull(background, path + " names no briefing background");
                // The scripts write these a level above the graphics directory:
                // "../campaigns/human/interface/introscreen1.png". The table
                // stores the same spelling, so the lookup has to accept it.
                assertTrue(background.startsWith("../campaigns/"),
                        path + " names " + background);
                var picture = data.image(background);
                assertNotNull(picture, path + ": nothing decoded at " + background);
                assertNotNull(data.paletteFor(background), path + ": no palette for " + background);
                assertEquals(640, picture.width(), background + " is not a full page");
                assertEquals(480, picture.height(), background + " is not a full page");
                distinct.add(background);
                checked++;
            }
        }
        assertEquals(52, checked, "missions with a briefing background");
        // Ten pages, five a side. One background for all fifty-two would be the
        // fault this test exists to catch.
        assertEquals(10, distinct.size(), "distinct briefing backgrounds: " + distinct);
    }

    @Test
    @DisplayName("every briefing names voices, and every one of them is a clip")
    void everyBriefingHasItsVoices() {
        GameData data = load();
        int missions = 0;
        int voices = 0;
        List<String> silent = new ArrayList<>();
        for (Campaign campaign : campaigns(data)) {
            for (CampaignStep step : campaign.missions()) {
                String path = step.mapArchivePath();
                Mission mission = data.loadMission(path, 0);
                assertNotNull(mission, "could not load " + path);
                if (mission.voices().isEmpty()) {
                    silent.add(path);
                    continue;
                }
                for (String voice : mission.voices()) {
                    assertNotNull(data.sounds().clip(voice), path + ": no clip for " + voice);
                    voices++;
                }
                missions++;
            }
        }
        assertEquals(List.of(), silent, "missions whose briefing names no voice-over");
        assertEquals(52, missions);
        // The conversion table has 104 campaign sound rows and the scripts name
        // 97 of them from a mission: two takes each, less the seven the endings
        // account for and the handful of missions that ship with one.
        assertEquals(97, voices, "briefing voice files named by the mission scripts");
    }
}
