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
import net.chonkbase.chonkcraft.engine.trigger.TriggerSystem;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Loads and plays the shipped campaigns.
 *
 * <p>A campaign mission is two halves kept in different places: the map is a
 * PUD inside {@code maindat.war}, the rules a {@code .sms} script shipped with
 * ChonkCraft. These check that both arrive and agree, all the way to winning the
 * first human mission by its own victory condition.
 */
class CampaignRealDataTest {

    private static GameData load() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No Warcraft II retail assets configured.");
        return new GameData(assets);
    }

    @Test
    @DisplayName("the four campaigns load with every mission map present")
    void theCampaignsLoad() {
        GameData data = load();
        List<Campaign> campaigns = data.campaigns();
        Assumptions.assumeTrue(!campaigns.isEmpty(), "no campaign scripts in this checkout");

        List<String> names = new ArrayList<>();
        int missions = 0;
        for (Campaign campaign : campaigns) {
            names.add(campaign.name());
            missions += campaign.missions().size();
            for (CampaignStep step : campaign.missions()) {
                assertNotNull(data.campaignMap(step.mapArchivePath()),
                        "no map in the archive for " + step.mapArchivePath());
            }
        }
        assertEquals(List.of("human", "orc", "human-exp", "orc-exp"), names);
        // Fourteen missions a side in Tides of Darkness, twelve in the
        // expansion.
        assertEquals(52, missions);
    }

    @Test
    @DisplayName("every campaign map in the conversion table is a readable PUD")
    void everyCampaignMapParses() {
        GameData data = load();
        List<String> paths = data.campaignMapPaths();
        assertEquals(84, paths.size(), "campaign maps named by the conversion table");
        for (String path : paths) {
            var map = data.campaignMap(path);
            assertNotNull(map, path + " did not read");
            assertTrue(map.width() > 0 && map.height() > 0, path + " has no extent");
        }
    }

    @Test
    @DisplayName("mission briefings decode from the archive as readable prose")
    void briefingsDecode() {
        GameData data = load();
        String briefing = data.text("human/level01h");
        Assumptions.assumeTrue(briefing != null, "no briefing text in this installation");

        // Code page 437, not Latin-1. Read the wrong way this is still a
        // string, just one with accented letters where the borders should be.
        assertTrue(briefing.contains("Lord Terenas"), "the first briefing names Lord Terenas");
        assertTrue(briefing.length() > 200, "a briefing is a few paragraphs");
    }

    @Test
    @DisplayName("a mission arrives with its triggers, allow table and briefing")
    void everyMissionLoadsWholeC() {
        GameData data = load();
        Assumptions.assumeTrue(!data.campaigns().isEmpty(), "no campaign scripts in this checkout");

        for (Campaign campaign : data.campaigns()) {
            for (CampaignStep step : campaign.missions()) {
                Mission mission = data.loadMission(step.mapArchivePath(), 0);
                assertNotNull(mission, "could not load " + step.mapArchivePath());
                assertTrue(mission.triggers().triggerCount() > 0,
                        step.mapArchivePath() + " has no victory or defeat condition");
                assertFalse(mission.allowed().isEmpty(),
                        step.mapArchivePath() + " declares nothing about what may be built");
                assertNotNull(mission.briefing(), step.mapArchivePath() + " has no briefing");
            }
        }
    }

    @Test
    @DisplayName("the first human mission forbids what it has not taught yet")
    void theFirstMissionRestrictsTheRoster() {
        GameData data = load();
        Mission mission = data.loadMission("campaigns/human/level01h", 0);
        Assumptions.assumeTrue(mission != null, "the first human mission is not available");

        int player = humanPlayer(mission);
        // The script permits five types and forbids the rest, which is how the
        // game teaches itself one building at a time.
        assertTrue(mission.allowed().isAllowed(player, "unit-farm"));
        assertTrue(mission.allowed().isAllowed(player, "unit-footman"));
        assertTrue(mission.allowed().isAllowed(player, "unit-peasant"));
        assertFalse(mission.allowed().isAllowed(player, "unit-knight"));
        assertFalse(mission.allowed().isAllowed(player, "unit-ballista"));
        assertFalse(mission.allowed().isAllowed(player, "unit-human-shipyard"));
        assertFalse(mission.allowed().isAllowed(player, "unit-gold-mine"),
                "DefineAllowSpecialUnits must run in the mission interpreter");
        assertFalse(mission.dependencies().isSatisfied("unit-knight", ident -> false),
                "the mission interpreter discarded the knight's prerequisites");
        assertTrue(mission.dependencies().isSatisfied("unit-knight",
                java.util.Set.of("unit-stables", "unit-human-blacksmith")::contains));
    }

    @Test
    @DisplayName("the first human mission can be won by meeting its own condition")
    void theFirstMissionCanBeWon() {
        GameData data = load();
        Mission probe = data.loadMission("campaigns/human/level01h", 0);
        Assumptions.assumeTrue(probe != null, "the first human mission is not available");
        int player = humanPlayer(probe);

        Mission mission = data.loadMission("campaigns/human/level01h", player);
        assertEquals(TriggerSystem.Outcome.RUNNING, mission.outcome());

        var types = data.unitTypes().types();
        UnitType farm = types.get("unit-farm");
        UnitType barracks = types.get("unit-human-barracks");
        Assumptions.assumeTrue(farm != null && barracks != null, "roster incomplete");

        // The script asks for four farms and a barracks.
        for (int i = 0; i < 4; i++) {
            mission.world().createUnit(farm, player, 2 + i * 3, 2);
        }
        run(mission, 40);
        assertEquals(TriggerSystem.Outcome.RUNNING, mission.outcome(),
                "farms alone are not the condition");

        mission.world().createUnit(barracks, player, 2, 8);
        run(mission, 40);
        assertEquals(TriggerSystem.Outcome.VICTORY, mission.outcome());
    }

    @Test
    @DisplayName("losing every unit loses the mission")
    void theFirstMissionCanBeLost() {
        GameData data = load();
        Mission probe = data.loadMission("campaigns/human/level01h", 0);
        Assumptions.assumeTrue(probe != null, "the first human mission is not available");
        int player = humanPlayer(probe);

        Mission mission = data.loadMission("campaigns/human/level01h", player);
        for (Unit unit : new ArrayList<>(mission.world().units())) {
            if (unit.player() == player) {
                mission.world().kill(unit);
            }
        }
        run(mission, 60);
        assertEquals(TriggerSystem.Outcome.DEFEAT, mission.outcome(),
                "trigger failures=" + mission.triggers().failures()
                        + ", triggers=" + mission.triggers().triggerCount()
                        + ", units=" + mission.world().units().stream()
                                .filter(unit -> unit.player() == player && unit.isAlive())
                                .map(unit -> unit.type().ident()).toList());
    }

    /** Which slot the map gives a town hall to. */
    private static int humanPlayer(Mission mission) {
        for (Unit unit : mission.world().units()) {
            if (unit.type() != null && "unit-town-hall".equals(unit.type().ident())) {
                return unit.player();
            }
        }
        return 0;
    }

    /** Triggers run once a second, so a cycle or two would prove nothing. */
    private static void run(Mission mission, int cycles) {
        for (int i = 0; i < cycles; i++) {
            mission.tick();
        }
    }
}
