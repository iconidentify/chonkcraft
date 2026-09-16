package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.save.LoadGame;
import net.chonkbase.chonkcraft.engine.save.SaveGame;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Human 8's hall rescues its village, including workers hidden in the mine.
 * Native 0x452430 checks type flag 0x1000 and transfers every roster member
 * without death flags 1, 2 or 4. Containment flag 8 does not exclude a worker.
 * The authenticated human8-building-first-20260915 capture witnesses the
 * village handoff at cycle 1028; these controlled contacts isolate that rule
 * from the campaign's already-divergent combat and travel timing.
 */
class BattleNetVillageRescueRealDataTest {
    private static final String MAP = "campaigns/human/level08h";

    @Test
    @DisplayName("rescuing the farm before the hall still frees visible and mining villagers")
    void aHallRescuesVisibleAndContainedWorkersAfterTheFarm() throws IOException {
        run(-1);
    }

    @Test
    @DisplayName("saving before or after the hall handoff keeps the mining villagers controllable")
    void savingTheVillageHandoffRetainsTheWorkers() throws IOException {
        run(300);
        run(303);
    }

    private static void run(int checkpoint) throws IOException {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        GameData data = new GameData(assets);
        int person = GameData.personIn(data.campaignMap(MAP));
        Mission mission = data.loadMission(MAP, person, 1);
        Map<Integer, Unit> identities = new LinkedHashMap<>();
        for (Unit unit : mission.world().units()) {
            identities.put(unit.id(), unit);
        }
        List<Integer> workers = mission.world().units().stream()
                .filter(unit -> unit.player() == 2 && unit.type().canGather())
                .map(Unit::id).toList();
        assertEquals(10, workers.size(), "the village must begin with its ten actual workers");
        int hallId = at(mission.world(), "unit-town-hall", 74, 72).id();
        int farmId = at(mission.world(), "unit-farm", 89, 68).id();
        var footman = data.unitTypes().types().get("unit-footman");
        mission.tick();
        mission.tick();
        List<Integer> survivors = new ArrayList<>();
        List<Integer> dying = new ArrayList<>();
        Map<Integer, Integer> contained = new LinkedHashMap<>();
        for (int cycle = 1; cycle <= 304; cycle++) {
            if (cycle == 5) {
                mission.world().createUnit(footman, person, 91, 70);
            }
            if (cycle == 300) {
                assertEquals(person, find(identities, farmId).player(),
                        "the farm must be rescued before approaching the hall");
                assertEquals(2, find(identities, hallId).player(),
                        "the farm must not transfer the whole village");
                for (int id : workers) {
                    Unit worker = find(identities, id);
                    assertEquals(2, worker.player(), "the farm must leave distant workers alone");
                    if (worker.isDying()) {
                        dying.add(id);
                    } else {
                        survivors.add(id);
                        if (!worker.isOnMap()) {
                            contained.put(id, worker.worksite().id());
                        }
                    }
                }
                assertEquals(8, survivors.size(), "eight villagers must still be living");
                assertEquals(2, contained.size(), "two survivors must be inside the actual mine");
                assertEquals(2, dying.size(), "two dying villagers must exercise the exclusion");
                mission.world().createUnit(footman, person, 78, 72);
            }
            mission.tick();
            if (cycle == checkpoint) {
                mission = reload(data, mission, person, identities);
            }
            if (cycle >= 300) {
                assertEquals(cycle < 303 ? 2 : person, find(identities, hallId).player(),
                        "the hall must wait for its native action marker at " + cycle);
            }
        }
        for (int id : survivors) {
            Unit worker = find(identities, id);
            assertEquals(person, worker.player(), "every living villager must transfer with the hall");
            assertEquals(2, worker.rescuedFrom(), "the worker must remember its original village");
        }
        for (int id : dying) {
            assertEquals(2, find(identities, id).player(), "the hall must not rescue dying units");
        }
        assertEquals(0, mission.world().battleNetWorkerFamilyCount(2),
                "the old village must no longer count the transferred workers");
        assertEquals(8, mission.world().battleNetWorkerFamilyCount(person),
                "the new owner must count all eight surviving workers, including the two in the mine");
        for (var entry : contained.entrySet()) {
            Unit worker = find(identities, entry.getKey());
            assertFalse(worker.isOnMap(), "rescue must not teleport a worker out of its mine");
            assertEquals(find(identities, entry.getValue()).id(), worker.worksite().id(),
                    "the mining visit must keep its original container");
            assertEquals(0, worker.markedSightRange(),
                    "a worker inside the mine must not add a second sight contribution");
        }

        CommandApplier commands = new CommandApplier(mission.world(),
                new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);
        Map<Integer, List<Integer>> clicked = new LinkedHashMap<>();
        List<Integer> progressed = new ArrayList<>();
        List<Integer> lost = new ArrayList<>();
        for (int cycle = 0; cycle < 600
                && progressed.size() + lost.size() < survivors.size(); cycle++) {
            for (int id : survivors) {
                Unit worker = find(identities, id);
                if (worker.isOnMap() && !clicked.containsKey(id)) {
                    assertTrue(commands.apply(GameCommand.move(person, worker.id(), 92, 90)),
                            "a rescued villager must accept the player's withdrawal");
                    clicked.put(id, List.of(worker.pixelX(), worker.pixelY()));
                }
            }
            mission.tick();
            for (int id : clicked.keySet()) {
                if (progressed.contains(id) || lost.contains(id)) {
                    continue;
                }
                Unit worker = find(identities, id);
                if (worker.isDying() || worker.destroyed() || worker.hitPoints() <= 0) {
                    lost.add(id);
                } else if (!clicked.get(id).equals(List.of(worker.pixelX(), worker.pixelY()))) {
                    progressed.add(id);
                }
            }
        }
        assertEquals(8, clicked.size(), "both hidden workers must emerge and accept commands too");
        assertTrue(progressed.containsAll(contained.keySet()),
                "both formerly hidden workers must emerge and move, not merely change owner");
        assertEquals(8, progressed.size() + lost.size(),
                "living rescued workers still waiting after the player's order: "
                + survivors.stream().filter(id -> !progressed.contains(id) && !lost.contains(id))
                        .map(id -> find(identities, id).toString()).toList());
    }

    private static Unit at(World world, String ident, int x, int y) {
        return world.units().stream().filter(unit -> unit.type().ident().equals(ident)
                && unit.tileX() == x && unit.tileY() == y).findFirst().orElseThrow();
    }

    private static Unit find(Map<Integer, Unit> identities, int id) {
        return identities.get(id);
    }

    private static Mission reload(GameData data, Mission old, int person,
            Map<Integer, Unit> identities) throws IOException {
        List<Unit> savedUnits = old.world().units().stream()
                .filter(unit -> unit.type() != null && !unit.isDying()).toList();
        StringWriter save = new StringWriter();
        SaveGame.writeWithTriggers(old.world(), MAP, "human", 8, old.triggers().savedState(), save);
        Mission restored = data.loadMission(MAP, person, 1);
        for (Unit unit : new ArrayList<>(restored.world().units())) {
            restored.world().remove(unit);
        }
        LoadGame.apply(restored.world(), save.toString(), data.unitTypes().types());
        restored.triggers().restoreState(LoadGame.triggerState(save.toString()));
        List<Unit> restoredUnits = restored.world().units();
        assertEquals(savedUnits.size(), restoredUnits.size(), "the save must restore its whole roster");
        identities.replaceAll((id, unit) -> {
            int index = savedUnits.indexOf(unit);
            return index < 0 ? unit : restoredUnits.get(index);
        });
        return restored;
    }
}
