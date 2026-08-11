package net.chonkbase.chonkcraft.engine.campaign;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Everything a campaign says introduces a mission must be loadable.
 *
 * <p>This checks a seam rather than a component, and the seam is where it
 * broke: the campaign scripts were read correctly and the archive holds every
 * file they name, but the names did not match. The act title cards are written
 * by the extractor a level above the graphics folder and the conversion table
 * records that with a leading {@code ../}, while the scripts ask for them
 * without it. Every card resolved to nothing, and the first four missions of
 * every campaign opened on a blank screen -- with no error anywhere, because
 * an image that cannot be found is indistinguishable from a mission that has
 * no picture.
 *
 * <p>So this asserts on the count as well as on each path. A check that only
 * says "everything found was loadable" passes perfectly when nothing is found.
 */
class CampaignIntroTest {

    private static GameData load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    @Test
    @DisplayName("Every picture and video a campaign introduces a mission with is there")
    void everyIntroductionLoads() {
        GameData data = load();
        List<Campaign> campaigns = data.campaigns();
        assertFalse(campaigns.isEmpty(), "no campaigns were read at all");

        List<String> missing = new ArrayList<>();
        int pictures = 0;
        int videos = 0;
        for (Campaign campaign : campaigns) {
            for (int mission = 1; mission <= campaign.missions().size(); mission++) {
                for (CampaignStep step : campaign.introducing(mission)) {
                    switch (step.kind()) {
                        case PICTURE -> {
                            pictures++;
                            if (data.image(step.path()) == null
                                    || data.paletteFor(step.path()) == null) {
                                missing.add(campaign.name() + " mission " + mission
                                        + ": picture " + step.path());
                            }
                        }
                        case VIDEO -> {
                            videos++;
                            if (data.video(step.path()) == null) {
                                missing.add(campaign.name() + " mission " + mission
                                        + ": video " + step.path());
                            }
                        }
                        default -> { }
                    }
                }
            }
        }

        assertTrue(missing.isEmpty(),
                "the campaigns name " + missing.size() + " things that cannot be loaded:\n"
                        + String.join("\n", missing));

        // Four campaigns, four acts each, and the expansion pair share an
        // opening cutscene. Asserting there are some of each is what stops
        // this passing by finding nothing.
        assertTrue(pictures >= 16,
                "only " + pictures + " act title cards; every campaign has four acts");
        assertTrue(videos >= 6, "only " + videos + " cutscenes between the campaigns");
    }

    /**
     * The first mission of every campaign opens on something.
     *
     * <p>This is the one a player meets first, and it was the one that was
     * broken: the opening missions have no cutscene, only the card announcing
     * the act, so dropping the cards meant the campaign began on nothing at
     * all.
     */
    @Test
    @DisplayName("Every campaign opens on something")
    void everyCampaignOpensOnSomething() {
        GameData data = load();
        for (Campaign campaign : data.campaigns()) {
            List<CampaignStep> opening = campaign.introducing(1);
            assertFalse(opening.isEmpty(),
                    campaign.name() + " begins with nothing to show");
            boolean anyLoadable = false;
            for (CampaignStep step : opening) {
                if (step.kind() == CampaignStep.Kind.PICTURE
                        && data.image(step.path()) != null) {
                    anyLoadable = true;
                } else if (step.kind() == CampaignStep.Kind.VIDEO
                        && data.video(step.path()) != null) {
                    anyLoadable = true;
                }
            }
            assertTrue(anyLoadable,
                    campaign.name() + " names things to open with and none of them load");
        }
    }

    /**
     * A path written with the extractor's {@code ../} and one written without
     * it are the same asset, and both must find it.
     */
    @Test
    @DisplayName("A path is found whether or not it climbs out of the graphics folder")
    void bothSpellingsResolve() {
        GameData data = load();
        String withoutPrefix = "campaigns/human/interface/Act_I_-_Shores_of_Lordareon.png";
        String withPrefix = "../" + withoutPrefix;
        assertNotNull(data.image(withoutPrefix),
                "the spelling the scripts use does not resolve");
        assertNotNull(data.image(withPrefix),
                "the spelling the conversion table uses does not resolve");
    }
}
