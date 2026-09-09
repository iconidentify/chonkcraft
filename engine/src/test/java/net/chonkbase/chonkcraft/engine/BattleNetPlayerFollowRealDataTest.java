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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Player Follow keeps committed pixels and the native waiting action between clicks. */
class BattleNetPlayerFollowRealDataTest {

    @Test
    @DisplayName("a group follow finishes the old stride and settles before the next ground click")
    void groupFollowFinishesTheOldStrideAndSettlesBeforeTheNextGroundClick() throws IOException {
        run(0, -1);
    }

    @Test
    @DisplayName("a follower resumes behind a moving leader and accepts a later ground click")
    void followerResumesBehindAMovingLeaderAndAcceptsALaterGroundClick() throws IOException {
        run(1, -1);
    }

    @Test
    @DisplayName("group followers refill their route on arrival and retain the leader through repeated clicks")
    void groupFollowersRefillTheirRouteOnArrivalAndRetainTheLeaderThroughRepeatedClicks() throws IOException {
        run(2, -1);
    }

    @Test
    @DisplayName("rapid follow clicks wait for the current opening before installing the replacement")
    void rapidFollowClicksWaitForTheCurrentOpeningBeforeInstallingTheReplacement() throws IOException {
        List<List<Integer>> uninterrupted = run(3, -1);
        for (int checkpoint : new int[] {9, 10, 11, 12, 14}) {
            sameContinuation(uninterrupted, run(3, checkpoint), checkpoint);
        }
    }

    @Test
    @DisplayName("saving a group follow preserves committed movement and the next ground click")
    void savingAGroupFollowPreservesCommittedMovementAndTheNextGroundClick() throws IOException {
        List<List<Integer>> uninterrupted = run(0, -1);
        for (int checkpoint : new int[] {5, 6, 9, 75, 76, 79, 108, 112, 129, 130}) {
            sameContinuation(uninterrupted, run(0, checkpoint), checkpoint);
        }
    }

    @Test
    @DisplayName("saving a waiting follower preserves its response when the leader moves again")
    void savingAWaitingFollowerPreservesItsResponseWhenTheLeaderMovesAgain() throws IOException {
        List<List<Integer>> uninterrupted = run(1, -1);
        for (int checkpoint : new int[] {15, 89, 110, 126, 158, 239, 242, 243}) {
            sameContinuation(uninterrupted, run(1, checkpoint), checkpoint);
        }
    }

    private static List<List<Integer>> run(int scenario, int checkpoint) throws IOException {
        Campaign game = new Campaign();
        List<List<Integer>> result = new ArrayList<>();
        int horizon = scenario == 0 ? 200 : scenario == 1 ? 400 : scenario == 2 ? 500 : 180;
        for (int cycle = 1; cycle <= horizon; cycle++) {
            if (scenario == 0) {
                if (cycle == 5) game.groupMove(25, 9);
                if (cycle == 75) game.groupFollow();
                if (cycle == 130) game.groupMove(18, 7);
            } else if (scenario == 1) {
                if (cycle == 5 || cycle == 15 || cycle == 110) game.follow(1);
                if (cycle == 90) game.move(3, 18, 9);
                if (cycle == 240) game.move(1, 22, 9);
            } else if (scenario == 2) {
                if (cycle == 5 || cycle == 100) game.groupFollow();
                if (cycle == 75) game.move(3, 18, 9);
                if (cycle == 220) game.groupMove(18, 7);
            } else {
                if (cycle == 5 || cycle == 10 || cycle == 11) game.follow(1);
                if (cycle == 70) game.move(1, 22, 9);
            }
            game.mission.tick();
            if (cycle == checkpoint) game.reload();
            if (scenario == 0) {
                // Pinned BNE 2.02b capture f71acde8: Move at 5, friendly
                // right-click at 75, ground at 130. The old stride owns 75;
                // Follow first reserves a new tile at 79. Stopping at the
                // logical neighbour before its pixels landed used to strand
                // the third footman partway through its last stride.
                if (cycle == 75) position(game.units[1], 21, 6, 670, 192, cycle);
                if (cycle == 76 || cycle == 78) position(game.units[1], 21, 6, 672, 192, cycle);
                if (cycle == 79) position(game.units[1], 20, 7, 672, 192, cycle);
                if (cycle == 80) position(game.units[1], 20, 7, 669, 195, cycle);
                if (cycle >= 108 && cycle <= 133) position(game.units[2], 14, 10, 448, 320, cycle);
                if (cycle == 134) position(game.units[2], 15, 9, 448, 320, cycle);
                if (cycle == 135) position(game.units[2], 15, 9, 451, 317, cycle);
            } else if (scenario == 1) {
                // BNE action 7 stays current while catching the leader again.
                // Its arrival at 158 runs Still OP0 immediately; rebuilding
                // action 7 here shifted the ground click's first step at 246.
                if (cycle <= 11) position(game.units[1], 17, 7, 544, 224, cycle);
                if (cycle == 12) position(game.units[1], 16, 8, 544, 224, cycle);
                if (cycle == 13) position(game.units[1], 16, 8, 541, 227, cycle);
                if (cycle >= 158 && cycle <= 245) position(game.units[1], 17, 9, 544, 288, cycle);
                if (cycle == 246) position(game.units[1], 18, 8, 544, 288, cycle);
                if (cycle == 247) position(game.units[1], 18, 8, 547, 285, cycle);
                if (cycle == 400) position(game.units[1], 21, 8, 672, 256, cycle);
            } else if (scenario == 2) {
                // The fourth initial heading is exhausted at 73. Native
                // refills and reserves the next tile on that same visit;
                // the generic empty-route pause used to add ten cycles.
                if (cycle == 72) position(game.units[0], 17, 8, 546, 256, cycle);
                if (cycle == 73) position(game.units[0], 16, 9, 544, 256, cycle);
                if (cycle == 74) position(game.units[0], 16, 9, 541, 259, cycle);
                if (cycle == 113) position(game.units[1], 16, 8, 480, 288, cycle);
                if (cycle == 114) position(game.units[1], 16, 8, 483, 285, cycle);
            } else {
                if (cycle <= 14) position(game.units[1], 17, 7, 544, 224, cycle);
                if (cycle == 15) position(game.units[1], 16, 8, 544, 224, cycle);
                if (cycle == 16) position(game.units[1], 16, 8, 541, 227, cycle);
            }
            result.add(game.frame());
        }
        return result;
    }

    private static void sameContinuation(List<List<Integer>> uninterrupted,
            List<List<Integer>> restored, int checkpoint) {
        assertEquals(uninterrupted.size(), restored.size(), "loading must retain the full observation window");
        for (int cycle = 1; cycle <= uninterrupted.size(); cycle++) {
            assertEquals(uninterrupted.get(cycle - 1), restored.get(cycle - 1),
                    "loading at " + checkpoint + " must preserve movement and random draws at cycle " + cycle);
        }
    }

    private static void position(Unit unit, int x, int y, int px, int py, int cycle) {
        assertEquals(List.of(x, y, px, py, 60), List.of(unit.tileX(), unit.tileY(),
                unit.tileX() * 32 + unit.offsetX(), unit.tileY() * 32 + unit.offsetY(), unit.hitPoints()),
                "the footman's tile, visible position and health must match BNE at cycle " + cycle);
    }

    private static final class Campaign {
        private static final String MAP = "campaigns/human/level01h";
        private final GameData data;
        private final int person;
        private Mission mission;
        private CommandApplier commands;
        private final Unit[] units = new Unit[4];

        Campaign() {
            AssetSource assets = AssetSource.fromEnvironment();
            Assumptions.assumeTrue(assets != null, "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
            data = new GameData(assets);
            person = GameData.personIn(data.campaignMap(MAP));
            mission = data.loadMission(MAP, person, 1);
            assertNotNull(mission, "Human 1 must load");
            units[0] = at(21, 5);
            units[1] = at(17, 7);
            units[2] = at(10, 13);
            units[3] = at(14, 9);
            commands();
            mission.tick();
            mission.tick();
        }

        private Unit at(int x, int y) {
            List<Unit> matches = mission.world().units().stream().filter(unit -> unit.player() == person
                    && unit.tileX() == x && unit.tileY() == y).toList();
            assertEquals(1, matches.size(), "the commanded actor must have one live identity at " + x + "," + y);
            return matches.getFirst();
        }

        private void commands() {
            commands = new CommandApplier(mission.world(), new ArrayList<>(data.unitTypes().types().values()));
            data.configureCommands(commands);
        }

        void move(int actor, int x, int y) {
            assertTrue(commands.apply(GameCommand.move(person, units[actor].id(), x, y)),
                    "the player's ground click must reach the selected unit");
        }

        void follow(int actor) {
            assertTrue(commands.apply(GameCommand.follow(person, units[actor].id(), units[3].id())),
                    "the player's Follow click must retain its live leader");
        }

        void groupMove(int x, int y) {
            for (int actor = 0; actor < 3; actor++) move(actor, x, y);
        }

        void groupFollow() {
            for (int actor = 0; actor < 3; actor++) follow(actor);
        }

        void reload() throws IOException {
            int[][] positions = new int[units.length][2];
            for (int i = 0; i < units.length; i++) positions[i] = new int[] {units[i].tileX(), units[i].tileY()};
            StringWriter saved = new StringWriter();
            SaveGame.writeWithTriggers(mission.world(), MAP, "human", 1, mission.triggers().savedState(), saved);
            mission = data.loadMission(MAP, person, 1);
            World world = mission.world();
            for (Unit initial : new ArrayList<>(world.units())) world.remove(initial);
            LoadGame.apply(world, saved.toString(), data.unitTypes().types());
            mission.triggers().restoreState(LoadGame.triggerState(saved.toString()));
            for (int i = 0; i < units.length; i++) units[i] = at(positions[i][0], positions[i][1]);
            commands();
        }

        List<Integer> frame() {
            World world = mission.world();
            List<Integer> result = new ArrayList<>(List.of(world.randomSeed(), world.battleNetRandomSeed()));
            for (Unit unit : units) {
                result.addAll(List.of(unit.tileX(), unit.tileY(), unit.offsetX(), unit.offsetY(),
                        unit.hitPoints(), unit.order().ordinal(), unit.battleNetSequenceOffset(),
                        unit.battleNetSequenceOffset() < 0 ? 0 : unit.battleNetAnimationTimer()));
            }
            return result;
        }
    }
}
