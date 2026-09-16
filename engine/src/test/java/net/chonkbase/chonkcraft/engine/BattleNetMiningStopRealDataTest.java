package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.save.LoadGame;
import net.chonkbase.chonkcraft.engine.save.SaveGame;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A peon accepted Stop on its way to a mine but continued the entire gold loop.
 * BNE 2.02b worker-stop-{10,20,40,55,220,580,600,630}-20260915 captures enter
 * through the right-click handler and player Stop dispatcher. Action 23
 * finishes its committed stride; action 25's boarding remains committed.
 * The human-worker-stop and blocked-worker-stop captures on the same date
 * cover the other race and an allied grunt obstructing the second trip.
 */
class BattleNetMiningStopRealDataTest {
    private static final String MAP = "campaigns/orc/level01o";

    @Test
    @DisplayName("stopping a mining approach finishes one stride and stays outside the mine")
    void stoppingAMiningApproachFinishesOneStride() throws IOException {
        GameData data = data();
        int[][] cases = {{10, 24, 25, 17}, {20, 24, 25, 17}, {40, 40, 25, 16}, {220, 220, 25, 15},
                {580, 588, 27, 21}, {600, 604, 27, 20}, {630, 636, 27, 18}};
        for (int[] sample : cases) {
            run(data, sample, -1);
        }
        for (int[] sample : new int[][] {{10, 25, 13, 8}, {20, 25, 13, 8},
                {40, 41, 13, 7}, {55, 57, 13, 6}}) {
            run(data, sample, -1, true, false);
        }
        for (int[] sample : new int[][] {{600, 604, 26, 20}, {630, 636, 27, 18}}) {
            run(data, sample, -1, false, true);
        }
    }

    @Test
    @DisplayName("stopping a committed mine boarding retains the native entry and cargo")
    void stoppingACommittedBoardingRetainsTheNativeEntry() {
        Game game = new Game(data());
        for (int cycle = 1; cycle <= 250; cycle++) {
            if (cycle == 5)
                game.apply(GameCommand.harvest(game.person, game.worker.id(), 26, 13));
            if (cycle == 55)
                game.apply(GameCommand.stop(game.person, game.worker.id()));
            game.mission.tick();
            if (cycle >= 55) {
                assertEquals(cycle < 59 || cycle >= 209, game.worker.isOnMap(),
                        "the committed boarding enters on 59 and leaves on 209, cycle " + cycle);
            }
            if (cycle >= 209) {
                assertEquals(
                        100, game.worker.carried(), "boarding must preserve the completed load");
            }
        }
        assertTrue(
                game.worker.returningToDepot(), "the native mine exit still sends the load home");
    }

    @Test
    @DisplayName("saving around a mining stop preserves its pixels cargo and random streams")
    void savingAroundAMiningStopPreservesItsContinuation() throws IOException {
        GameData data = data();
        for (int[] sample : new int[][] {{10, 24, 25, 17}, {600, 604, 27, 20}}) {
            List<List<Integer>> uninterrupted = run(data, sample, -1);
            for (int checkpoint : new int[] {sample[0] - 1, sample[0], sample[1] - 1, sample[1]}) {
                assertEquals(uninterrupted, run(data, sample, checkpoint),
                        "saving at " + checkpoint + " changed the stopped worker's continuation");
            }
        }
    }

    private static List<List<Integer>> run(GameData data, int[] sample, int checkpoint)
            throws IOException {
        return run(data, sample, checkpoint, false, false);
    }

    private static List<List<Integer>> run(GameData data, int[] sample, int checkpoint,
            boolean human, boolean blocked) throws IOException {
        Game game = new Game(data, human);
        List<List<Integer>> frames = new ArrayList<>();
        int stop = sample[0], settled = sample[1];
        for (int cycle = 1; cycle <= stop + 400; cycle++) {
            if (cycle == 5) {
                game.apply(GameCommand.harvest(game.person, game.worker.id(),
                        human ? 13 : 26, human ? 2 : 13));
            }
            if (cycle == 410 && blocked) {
                Unit blocker = game.mission.world().units().stream()
                        .filter(unit -> unit.player() == game.person
                                && unit.tileX() == 18 && unit.tileY() == 23)
                        .findFirst().orElseThrow();
                game.apply(GameCommand.move(game.person, blocker.id(), 27, 20));
            }
            if (cycle == stop) {
                assertTrue(game.worker.isOnMap(), "Stop must be issued to a visible worker");
                game.apply(GameCommand.stop(game.person, game.worker.id()));
            }
            game.mission.tick();
            if (cycle == checkpoint)
                game.reload();
            if (cycle >= stop) {
                assertTrue(game.worker.isOnMap(), "Stop must prevent mine entry at " + cycle);
                if (cycle < settled) {
                    assertEquals(Unit.Order.HARVEST, game.worker.order(),
                            "the committed movement remains owned by Harvest at " + cycle);
                } else {
                    assertEquals(Unit.Order.STILL, game.worker.order(),
                            "Stop must remain settled instead of restarting mining at " + cycle);
                    assertEquals(List.of(sample[2], sample[3], sample[2] * 32, sample[3] * 32),
                            List.of(game.worker.tileX(), game.worker.tileY(),
                                    game.worker.tileX() * 32 + game.worker.offsetX(),
                                    game.worker.tileY() * 32 + game.worker.offsetY()),
                            "the stopped worker must retain its native tile and pixels at "
                                    + cycle);
                }
            }
            frames.add(List.of(game.worker.tileX(), game.worker.tileY(), game.worker.offsetX(),
                    game.worker.offsetY(), game.worker.order().ordinal(), game.worker.carried(),
                    game.mission.world().randomSeed(), game.mission.world().battleNetRandomSeed(),
                    game.mission.world().player(game.person).get(UnitType.Resource.GOLD)));
        }
        assertEquals(stop + 400, frames.size(),
                "the stopped worker must be observed beyond a full mine visit");
        return frames;
    }

    private static GameData data() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(assets);
    }

    private static final class Game {
        final GameData data;
        final int person;
        final String map;
        Mission mission;
        Unit worker;
        CommandApplier commands;

        Game(GameData data) {
            this(data, false);
        }

        Game(GameData data, boolean human) {
            this.data = data;
            map = human ? "campaigns/human/level01h" : MAP;
            person = GameData.personIn(data.campaignMap(map));
            mission = data.loadMission(map, person, 1);
            bind();
            mission.tick();
            mission.tick();
        }

        void bind() {
            List<Unit> workers =
                    mission.world()
                            .units()
                            .stream()
                            .filter(unit -> unit.player() == person && unit.type().canGather())
                            .toList();
            assertEquals(1, workers.size(), "the first mission must have one controlled worker");
            worker = workers.getFirst();
            commands = new CommandApplier(
                    mission.world(), new ArrayList<>(data.unitTypes().types().values()));
            data.configureCommands(commands);
        }

        void apply(GameCommand command) {
            assertTrue(commands.apply(command), "the player's worker command must be accepted");
        }

        void reload() throws IOException {
            StringWriter saved = new StringWriter();
            SaveGame.writeWithTriggers(
                    mission.world(), map, map.equals(MAP) ? "orc" : "human", 1,
                    mission.triggers().savedState(), saved);
            mission = data.loadMission(map, person, 1);
            for (Unit unit : new ArrayList<>(mission.world().units())) mission.world().remove(unit);
            LoadGame.apply(mission.world(), saved.toString(), data.unitTypes().types());
            mission.triggers().restoreState(LoadGame.triggerState(saved.toString()));
            bind();
        }
    }
}
