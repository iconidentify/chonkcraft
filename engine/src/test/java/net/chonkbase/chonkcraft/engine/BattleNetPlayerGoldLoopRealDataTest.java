package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

/** A player's gold loop preserves depot exit, route choice and repeated credit. */
class BattleNetPlayerGoldLoopRealDataTest {

    @Test
    @DisplayName("a player gold loop retains its exit pause and credits the second load on time")
    void aPlayerGoldLoopRetainsItsExitPauseAndCreditsTheSecondLoadOnTime() throws IOException {
        run(0, -1);
        run(1, -1);
    }

    @Test
    @DisplayName("a gold worker goes around a standing ally and keeps gathering when it moves away")
    void aGoldWorkerGoesAroundAStandingAllyAndKeepsGatheringWhenItMovesAway() throws IOException {
        run(2, -1);
        run(3, -1);
    }

    @Test
    @DisplayName("saving a gold worker preserves its depot stay exit pause and next load")
    void savingAGoldWorkerPreservesItsDepotStayExitPauseAndNextLoad() throws IOException {
        List<List<Integer>> uninterrupted = run(0, -1);
        for (int checkpoint : new int[] {394, 543, 544, 545, 568, 569, 570, 571, 572, 837, 1022, 1172}) {
            assertEquals(uninterrupted, run(0, checkpoint),
                    "saving at " + checkpoint + " must preserve every later worker position, load and bank credit");
        }
    }

    private static List<List<Integer>> run(int scenario, int checkpoint) throws IOException {
        Campaign game = new Campaign();
        List<List<Integer>> frames = new ArrayList<>();
        for (int cycle = 1; cycle <= 1500; cycle++) {
            if (cycle == 5) {
                game.apply(GameCommand.harvest(game.person, game.worker.id(), 26, 13));
            }
            if (scenario == 1 && (cycle == 220 || cycle == 221)) {
                game.apply(GameCommand.returnGoods(game.person, game.worker.id()));
            }
            if (scenario >= 2 && cycle == 410) {
                game.apply(GameCommand.move(game.person, game.blocker.id(), 27, 20));
            }
            if (scenario == 3 && cycle == 630) {
                game.apply(GameCommand.move(game.person, game.blocker.id(), 18, 23));
            }
            game.mission.tick();
            if (cycle == checkpoint) game.reload();

            // Pinned BNE 2.02b captures worker-normal-gold-20260909,
            // worker-gold-blocked-20260909 and worker-gold-blocker-released-20260909
            // drive the actual right-click handler. Each credits 100 gold on
            // 394 and 1022. The repeated Return Goods dispatcher control has
            // the same first return. Before the depot-ready correction Java
            // skipped the player's pause and credited the second load on 995.
            int gold = cycle < 394 ? 1000 : cycle < 1022 ? 1100 : 1200;
            assertEquals(gold, game.mission.world().player(game.person).get(UnitType.Resource.GOLD),
                    "the player must receive exactly one load at each native deposit cycle: " + cycle);
            assertEquals(30, game.worker.hitPoints(), "the worker must survive its complete resource loop");
            boolean contained = cycle >= 59 && cycle < 209
                    || cycle >= 394 && cycle < 544
                    || cycle >= 687 && cycle < 837
                    || cycle >= 1022 && cycle < 1172
                    || cycle >= 1315 && cycle < 1465;
            assertEquals(!contained, game.worker.isOnMap(),
                    "the worker must enter and leave the mine or hall on the native visit: " + cycle);
            if (cycle >= 544 && cycle <= 571 || cycle >= 1172 && cycle <= 1199) {
                position(game.worker, 26, 22, 832, 704, cycle);
            }
            if (cycle == 600) {
                position(game.worker, scenario >= 2 ? 26 : 27, 20,
                        scenario >= 2 ? 832 : 864, 647, cycle);
                if (scenario >= 2) position(game.blocker, 27, 20, 864, 640, cycle);
            }
            if (cycle == 837 || cycle == 862) position(game.worker, 25, 15, 800, 480, cycle);
            if (cycle == 997) position(game.worker, 24, 21, 768, 672, cycle);
            if (cycle == 1500) position(game.worker, 24, 16, 791, 489, cycle);
            frames.add(game.frame());
        }
        assertEquals(1500, frames.size(), "the referee must observe two deposits and the third laden return");
        return frames;
    }

    private static void position(Unit unit, int x, int y, int px, int py, int cycle) {
        assertEquals(List.of(x, y, px, py), List.of(unit.tileX(), unit.tileY(),
                unit.tileX() * 32 + unit.offsetX(), unit.tileY() * 32 + unit.offsetY()),
                "the worker and its blocking ally must occupy the native tile and pixels at " + cycle);
    }

    private static final class Campaign {
        private static final String MAP = "campaigns/orc/level01o";
        final GameData data;
        final int person;
        Mission mission;
        CommandApplier commands;
        Unit worker;
        Unit blocker;

        Campaign() throws IOException {
            AssetSource assets = AssetSource.fromEnvironment();
            Assumptions.assumeTrue(assets != null, "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
            data = new GameData(assets);
            person = GameData.personIn(data.campaignMap(MAP));
            mission = data.loadMission(MAP, person, 1);
            assertNotNull(mission, "Orc 1 must load for the player's gold loop");
            identify();
            blocker = mission.world().units().stream().filter(unit -> unit.player() == person
                    && unit.tileX() == 18 && unit.tileY() == 23).findFirst().orElseThrow();
            commands();
            mission.tick();
            mission.tick();
        }

        private void identify() {
            List<Unit> workers = mission.world().units().stream().filter(unit -> unit.player() == person
                    && unit.type().canGather()).toList();
            assertEquals(1, workers.size(), "the controlled peon must have one identity, including while contained");
            worker = workers.getFirst();
        }

        private void commands() {
            commands = new CommandApplier(mission.world(), new ArrayList<>(data.unitTypes().types().values()));
            data.configureCommands(commands);
        }

        void apply(GameCommand command) {
            assertTrue(commands.apply(command), "the player's legal worker or blocker command must be accepted");
        }

        void reload() throws IOException {
            StringWriter saved = new StringWriter();
            SaveGame.writeWithTriggers(mission.world(), MAP, "orc", 1, mission.triggers().savedState(), saved);
            mission = data.loadMission(MAP, person, 1);
            World world = mission.world();
            for (Unit initial : new ArrayList<>(world.units())) world.remove(initial);
            LoadGame.apply(world, saved.toString(), data.unitTypes().types());
            mission.triggers().restoreState(LoadGame.triggerState(saved.toString()));
            identify();
            commands();
        }

        List<Integer> frame() {
            World world = mission.world();
            return List.of(world.randomSeed(), world.battleNetRandomSeed(),
                    worker.tileX(), worker.tileY(), worker.offsetX(), worker.offsetY(),
                    worker.hitPoints(), worker.isOnMap() ? 1 : 0, worker.carried(),
                    world.player(person).get(UnitType.Resource.GOLD));
        }
    }
}
