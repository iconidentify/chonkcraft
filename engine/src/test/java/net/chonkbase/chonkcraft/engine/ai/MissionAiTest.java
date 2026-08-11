package net.chonkbase.chonkcraft.engine.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.campaign.Campaign;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.perf.SimulationProfile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every campaign opponent executes the authenticated retail {@code ai.bin}.
 *
 * <p>The old version of this test certified which community ChonkCraft retired scripting language
 * personality each map selected. That was useful while those scripts drove
 * the game, but it became actively misleading after the native BNE AI landed:
 * the retail executable selects a numbered profile from the PUD's AIPL bytes
 * and executes main archive entry 277. These assertions pin that real runtime
 * boundary for every computer slot in all fifty-two campaign missions.
 */
class MissionAiTest {

    private static GameData load() {
        GameData data = SimulationProfile.load();
        Assumptions.assumeTrue(data != null,
                "No authenticated Warcraft II asset source is configured");
        return data;
    }

    /** Every mission the native campaign catalog names, in campaign order. */
    private static List<String> missionPaths(GameData data) {
        List<String> paths = new ArrayList<>();
        for (Campaign campaign : data.campaigns()) {
            for (var step : campaign.missions()) {
                paths.add(step.mapArchivePath());
            }
        }
        return paths;
    }

    @Test
    @DisplayName("all 52 missions attach the AIPL-selected retail ai.bin profile")
    void everyComputerSlotRunsItsRetailProfile() {
        GameData data = load();
        List<String> paths = missionPaths(data);
        assertEquals(52, paths.size(), "the campaigns should name fifty-two missions");

        List<String> wrong = new ArrayList<>();
        int attached = 0;
        for (String path : paths) {
            PudMap pud = data.campaignMap(path);
            Mission mission = data.loadMission(path);
            if (pud == null || mission == null) {
                wrong.add(path + " did not construct");
                continue;
            }
            if (mission.ai().size() != mission.world().ais().size()) {
                wrong.add(path + " assignments=" + mission.ai().size()
                        + " active-ai=" + mission.world().ais().size());
            }
            for (AiAssignment assignment : mission.ai()) {
                attached++;
                int slot = assignment.player();
                if (slot < 0 || slot >= pud.aiTypes().length) {
                    wrong.add(path + " invalid player " + slot);
                    continue;
                }
                int profile = pud.aiTypes()[slot];
                String expected = "retail-ai.bin:" + profile;
                AiPlayer ai = mission.world().ais().get(slot);
                if (!expected.equals(assignment.attached()) || ai == null
                        || ai.battleNetBuildProfileId() != profile
                        || ai.savedBattleNetState() == null || ai.usePlan()) {
                    wrong.add(path + " player " + slot + " expected " + expected
                            + " but assignment=" + assignment + " profile="
                            + (ai == null ? "missing" : ai.battleNetBuildProfileId())
                            + " native-state=" + (ai != null && ai.savedBattleNetState() != null)
                            + " generic-plan=" + (ai != null && ai.usePlan()));
                }
            }
        }

        assertTrue(attached >= 100,
                "too few campaign computer slots were exercised: " + attached);
        assertTrue(wrong.isEmpty(), "retail AI attachment failures:\n  "
                + String.join("\n  ", wrong));
    }

    @Test
    @DisplayName("a plain campaign map also boots retail AI without a mission personality")
    void plainMapUsesItsOwnRetailProfile() {
        GameData data = load();
        PudMap pud = data.campaignMap("campaigns/human/level14h");
        Assumptions.assumeTrue(pud != null, "no campaign map available");

        var world = new net.chonkbase.chonkcraft.engine.World(
                net.chonkbase.chonkcraft.engine.map.GameMap.from(pud,
                        data.loadTileset(pud.tileset()).tileset()),
                net.chonkbase.chonkcraft.engine.Player.from(pud));
        world.setUnitTypes(data.unitTypes().types());
        data.populate(world, pud);
        world.enableAiForComputerPlayers();

        List<AiAssignment> assignments = data.attachRetailAi(
                world, pud, java.util.Map.of());
        assertFalse(assignments.isEmpty(), "the map attached no computer players");
        for (AiAssignment assignment : assignments) {
            AiPlayer ai = world.ais().get(assignment.player());
            assertNotNull(ai);
            int profile = pud.aiTypes()[assignment.player()];
            assertEquals("retail-ai.bin:" + profile, assignment.attached());
            assertEquals(profile, ai.battleNetBuildProfileId());
            assertNotNull(ai.savedBattleNetState(), "retail bytecode was not bootstrapped");
            assertFalse(ai.usePlan(), "the generic Java plan is competing with retail ai.bin");
        }
    }
}
