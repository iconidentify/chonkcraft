package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Boots the same two artifacts a player receives: game JAR plus BNE pack. */
class StandaloneBootTest {

    @Test
    @DisplayName("the game JAR and BNE pack boot every campaign without a checkout")
    void theReleaseArtifactsBootTheGame() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null, "No Warcraft II retail assets configured.");
        GameData data = new GameData(assets);
        assertTrue(data.unitTypes().types().size() > 100, "retail roster did not boot");
        assertTrue(data.userInterface("summer").buttons().all().size() > 200,
                "command panel did not boot");
        assertTrue(data.upgrades().upgrades().all().size() > 40,
                "technology tree did not boot");

        int missions = 0;
        for (var campaign : data.campaigns()) {
            for (var step : campaign.missions()) {
                assertNotNull(data.loadMission(step.mapArchivePath()),
                        "could not boot " + step.mapArchivePath());
                missions++;
            }
        }
        assertEquals(4, data.campaigns().size(), "campaigns in native data");
        assertEquals(52, missions, "missions booted from native data");
    }
}
