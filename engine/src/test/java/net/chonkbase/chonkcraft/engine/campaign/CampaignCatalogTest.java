package net.chonkbase.chonkcraft.engine.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Structural checksum for the four sealed campaign-menu declarations. */
class CampaignCatalogTest {

    @Test
    void containsEveryCampaignMissionAndPresentationStepInOrder() {
        List<Campaign> campaigns = CampaignCatalog.generated();
        assertEquals(List.of("human", "orc", "human-exp", "orc-exp"),
                campaigns.stream().map(Campaign::name).toList());
        assertEquals(List.of(23, 23, 19, 19),
                campaigns.stream().map(c -> c.steps().size()).toList());
        assertEquals(List.of(14, 14, 12, 12),
                campaigns.stream().map(c -> c.missions().size()).toList());
        assertEquals("campaigns/human/level01h",
                campaigns.get(0).missions().get(0).mapArchivePath());
        assertEquals("campaigns/orc-exp/levelx12o",
                campaigns.get(3).missions().get(11).mapArchivePath());
        assertEquals(List.of("campaigns/human-exp/victory-1.wav",
                        "campaigns/human-exp/victory-2.wav"),
                campaigns.get(2).ending().get(1).voices());
        assertEquals(List.of("campaigns/orc-exp/victory-1.wav",
                        "campaigns/orc-exp/victory-2.wav",
                        "campaigns/orc-exp/victory-3.wav"),
                campaigns.get(3).ending().get(1).voices());
    }
}
