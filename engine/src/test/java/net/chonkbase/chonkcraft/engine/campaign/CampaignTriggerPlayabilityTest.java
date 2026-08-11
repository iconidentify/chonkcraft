package net.chonkbase.chonkcraft.engine.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.trigger.TriggerSystem;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A fail-closed referee for every shipped campaign wrapper. */
class CampaignTriggerPlayabilityTest {

    private static GameData load() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null, "No Warcraft II retail assets configured.");
        return new GameData(assets);
    }

    @Test
    @DisplayName("all 52 retail wrappers arm 137 triggers and run without deciding or faulting")
    void everyRetailWrapperRunsCleanly() {
        GameData data = load();
        int missions = 0;
        int triggers = 0;
        List<String> failures = new ArrayList<>();
        List<String> decided = new ArrayList<>();

        for (Campaign campaign : data.campaigns()) {
            for (CampaignStep step : campaign.missions()) {
                Mission mission = data.loadMission(step.mapArchivePath());
                missions++;
                triggers += mission.triggers().triggerCount();
                for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 30; cycle++) {
                    mission.tick();
                    if (mission.outcome() != TriggerSystem.Outcome.RUNNING) {
                        decided.add(step.mapArchivePath() + " -> " + mission.outcome()
                                + " at cycle " + cycle + " by trigger "
                                + mission.triggers().decidedBy() + " of "
                                + mission.triggers().decidedOfHowMany());
                        break;
                    }
                }
                for (String failure : mission.triggers().failures()) {
                    failures.add(step.mapArchivePath() + ": " + failure);
                }
            }
        }

        assertEquals(52, missions, "campaign mission wrappers exercised");
        assertEquals(137, triggers, "retail trigger pairs armed before play");
        assertTrue(failures.isEmpty(),
                "a swallowed script fault can leave a mission permanently unwinnable: "
                        + failures);
        assertTrue(decided.isEmpty(),
                "an untouched mission must not win or lose before a player can act: " + decided);
    }
}
