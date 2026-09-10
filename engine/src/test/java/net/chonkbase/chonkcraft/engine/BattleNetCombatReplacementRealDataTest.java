package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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

/** Accepted combat replacements must survive the old swing and its automatic scan. */
class BattleNetCombatReplacementRealDataTest {

    @Test
    @DisplayName("a replacement on the firing visit prevents another shot at the old quarry")
    void aReplacementOnTheFiringVisitPreventsAnotherShotAtTheOldQuarry() throws IOException {
        for (String kind : List.of("attack", "move")) {
            cancelledShot(kind, 370, -1);
            cancelledShot(kind, 387, -1);
        }
    }

    @Test
    @DisplayName("saving a cancelled shot preserves the replacement and leaves the old quarry unharmed")
    void savingACancelledShotPreservesTheReplacementAndLeavesTheOldQuarryUnharmed() throws IOException {
        for (String kind : List.of("attack", "move")) {
            List<List<Integer>> expected = cancelledShot(kind, 387, -1);
            for (int checkpoint : new int[] {387, 575, 576}) {
                List<List<Integer>> restored = cancelledShot(kind, 387, checkpoint);
                assertEquals(expected.size(), restored.size(), "the restored replacement must finish every visit");
                for (int cycle = 0; cycle < expected.size(); cycle++) {
                    assertEquals(expected.get(cycle), restored.get(cycle),
                            "saving cancelled " + kind + " at " + checkpoint
                                    + " must preserve its handoff and first hit at " + (cycle + 1));
                }
            }
        }
    }

    private static List<List<Integer>> cancelledShot(String kind, int click, int checkpoint) throws IOException {
        Campaign game = new Campaign();
        List<List<Integer>> frames = new ArrayList<>();
        for (int cycle = 1; cycle <= 1000; cycle++) {
            if (cycle == 5) game.apply(GameCommand.move(game.person, game.dragon.id(), 16, 54));
            if (cycle == 200) game.apply(GameCommand.attack(game.person, game.dragon.id(), game.refinery.id()));
            if (cycle == click) game.apply(kind.equals("attack")
                    ? GameCommand.attack(game.person, game.dragon.id(), game.tower.id())
                    : GameCommand.move(game.person, game.dragon.id(), 8, 54));
            game.mission.tick();
            if (cycle == checkpoint) game.reload();
            // Fresh pinned-executable captures dragon-interrupt-{attack,move}
            // -{370,387}-20260910 enter through the native click handler.
            // OP0 runs at 386 and OP10 at 387. Even a replacement arriving
            // on 387 suppresses the old shot while the body drains to 576.
            // Earlier attacks by other units leave the refinery at 569 HP.
            if (cycle >= 206) assertEquals(569, game.refinery.hitPoints(),
                    "the cancelled dragon shot must never damage its old quarry at " + cycle);
            int firstHit = click == 370 ? 499 : 689;
            assertEquals(kind.equals("attack") && cycle >= firstHit, game.tower.hitPoints() < 160,
                    "the replacement tower must first take damage on its native visit at " + cycle);
            if (cycle == 1000) {
                if (kind.equals("attack")) {
                    assertSame(game.tower, game.dragon.target(), "the replacement must retain the clicked tower");
                    assertTrue(game.tower.hitPoints() < 160, "the replacement attack must actually damage the tower");
                } else {
                    position(game.dragon, 8, 54, 256, 1728, cycle);
                    assertEquals(Unit.Order.STILL, game.dragon.order(), "the replacement march must finish");
                    assertEquals(160, game.tower.hitPoints(), "moving must leave the replacement tower unharmed");
                }
            }
            List<Integer> frame = new ArrayList<>(game.frame());
            frame.add(game.refinery.hitPoints());
            frame.add(game.tower.hitPoints() < 160 ? 1 : 0);
            frames.add(frame);
        }
        assertEquals(1000, frames.size(), "the cancelled shot and completed replacement must both be observed");
        return frames;
    }

    @Test
    @DisplayName("a dragon leaves its old building and attacks the player's replacement quarry")
    void aDragonLeavesItsOldBuildingAndAttacksThePlayersReplacementQuarry() throws IOException {
        run("attack", -1);
    }

    @Test
    @DisplayName("a dragon finishes its committed attack and moves to the clicked ground")
    void aDragonFinishesItsCommittedAttackAndMovesToTheClickedGround() throws IOException {
        run("move", -1);
    }

    @Test
    @DisplayName("saving a pending combat replacement preserves its release and subsequent actions")
    void savingAPendingCombatReplacementPreservesItsReleaseAndSubsequentActions() throws IOException {
        for (String kind : List.of("attack", "move")) {
            List<List<Integer>> expected = run(kind, -1);
            for (int checkpoint : new int[] {400, 575, 576, 603, 688}) {
                List<List<Integer>> restored = run(kind, checkpoint);
                assertEquals(expected.size(), restored.size(), "the restored fight must finish all observed cycles");
                for (int cycle = 0; cycle < expected.size(); cycle++) {
                    assertEquals(expected.get(cycle), restored.get(cycle),
                            "saving pending " + kind + " at " + checkpoint
                                    + " must preserve the fight at cycle " + (cycle + 1));
                }
            }
        }
    }

    private static List<List<Integer>> run(String kind, int checkpoint) throws IOException {
        Campaign game = new Campaign();
        List<List<Integer>> frames = new ArrayList<>();
        int towerHp = game.tower.hitPoints();
        int firstTowerHit = -1;
        for (int cycle = 1; cycle <= 1000; cycle++) {
            if (cycle == 5) game.apply(GameCommand.move(game.person, game.dragon.id(), 16, 54));
            if (cycle == 200) game.apply(GameCommand.attack(game.person, game.dragon.id(), game.refinery.id()));
            if (cycle == 400) {
                game.apply(switch (kind) {
                    case "attack" -> GameCommand.attack(game.person, game.dragon.id(), game.tower.id());
                    case "move" -> GameCommand.move(game.person, game.dragon.id(), 8, 54);
                    default -> throw new AssertionError("unexpected command kind: " + kind);
                });
            }
            game.mission.tick();
            if (cycle == checkpoint) game.reload();
            if (firstTowerHit < 0 && game.tower.hitPoints() < towerHp) firstTowerHit = cycle;
            if (cycle == 424) assertTrue(game.refinery.hitPoints() < 569,
                    "a shot already in flight before the replacement click must finish damaging the old quarry");

            // Authenticated dragon-retarget-{attack,move}-20260909
            // captures run XOrc 11 seed one through the native click handler
            // command path. Order 9 promotes at 359;
            // both replacements promote at 576, after script.bin OP0.
            // The visual wingbeat loop used to release at 527 and the free
            // scan switched the new quarry back to the refinery at 529.
            if (cycle >= 359 && cycle < 400) {
                assertSame(game.refinery, game.dragon.target(),
                        "the first explicit attack must retain the clicked quarry at " + cycle);
            }
            if (cycle >= 359 && cycle < 576) {
                position(game.dragon, 16, 54, 512, 1728, cycle);
            }
            if (cycle == 576) {
                assertEquals(kind.equals("attack") ? Unit.Order.ATTACK
                                : Unit.Order.MOVE,
                        game.dragon.order(), "the replacement must take ownership on native cycle 576");
                assertTrue(game.dragon.queuedOrders().isEmpty(), "the accepted replacement must leave the queue");
            }
            if (kind.equals("attack")) {
                if (cycle >= 576) assertSame(game.tower, game.dragon.target(),
                        "an automatic scan must not steal the player's new target at " + cycle);
                if (cycle == 603) position(game.dragon, 18, 56, 512, 1728, cycle);
                if (cycle >= 651) position(game.dragon, 18, 58, 576, 1856, cycle);
            } else {
                if (cycle == 584) position(game.dragon, 14, 54, 512, 1728, cycle);
                if (cycle >= 680) position(game.dragon, 8, 54, 256, 1728, cycle);
            }
            assertEquals(100, game.dragon.hitPoints(), "the commanded dragon must remain unharmed");
            frames.add(game.frame());
        }
        assertEquals(1000, frames.size(), "the referee must cover the completed handoff and subsequent attack body");
        assertEquals(kind.equals("attack") ? 689 : -1, firstTowerHit,
                "only the replacement Attack may damage the tower, on its native first-hit cycle");
        return frames;
    }

    private static void position(Unit unit, int x, int y, int px, int py, int cycle) {
        assertEquals(List.of(x, y, px, py), List.of(unit.tileX(), unit.tileY(), unit.pixelX(), unit.pixelY()),
                "the dragon must occupy the native tile and pixels at " + cycle);
    }

    private static final class Campaign {
        static final String MAP = "campaigns/orc-exp/levelx11o";
        final GameData data;
        final int person;
        Mission mission;
        CommandApplier commands;
        Unit dragon;
        Unit refinery;
        Unit tower;

        Campaign() {
            AssetSource source = AssetSource.fromEnvironment();
            Assumptions.assumeTrue(source != null, "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
            data = new GameData(source);
            person = GameData.personIn(data.campaignMap(MAP));
            mission = data.loadMission(MAP, person, 1);
            identify(6, 52);
            mission.tick();
            mission.tick();
        }

        void identify(int x, int y) {
            List<Unit> dragons = mission.world().units().stream().filter(unit -> unit.player() == person
                    && unit.type().ident().equals("unit-dragon")
                    && unit.tileX() == x && unit.tileY() == y).toList();
            assertEquals(1, dragons.size(), "the commanded dragon must remain identifiable across a save");
            dragon = dragons.getFirst();
            refinery = building(20, 56);
            tower = building(21, 61);
            commands = new CommandApplier(mission.world(), new ArrayList<>(data.unitTypes().types().values()));
            data.configureCommands(commands);
        }

        Unit building(int x, int y) {
            return mission.world().units().stream().filter(unit -> unit.type().building()
                    && unit.tileX() == x && unit.tileY() == y).findFirst().orElseThrow();
        }

        void apply(GameCommand command) {
            assertTrue(commands.apply(command), "the player's legal combat replacement must be accepted");
        }

        void reload() throws IOException {
            int x = dragon.tileX();
            int y = dragon.tileY();
            StringWriter saved = new StringWriter();
            SaveGame.writeWithTriggers(mission.world(), MAP, "orc", 11, mission.triggers().savedState(), saved);
            mission = data.loadMission(MAP, person, 1);
            for (Unit unit : new ArrayList<>(mission.world().units())) mission.world().remove(unit);
            LoadGame.apply(mission.world(), saved.toString(), data.unitTypes().types());
            mission.triggers().restoreState(LoadGame.triggerState(saved.toString()));
            identify(x, y);
        }

        List<Integer> frame() {
            Unit target = dragon.target();
            return List.of(dragon.tileX(), dragon.tileY(), dragon.pixelX(), dragon.pixelY(), dragon.hitPoints(),
                    dragon.order().ordinal(), target == null ? -1 : target.tileX(),
                    target == null ? -1 : target.tileY(),
                    dragon.queuedOrders().size());
        }
    }
}
