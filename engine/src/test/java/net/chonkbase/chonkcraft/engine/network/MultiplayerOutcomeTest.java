package net.chonkbase.chonkcraft.engine.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.trigger.TriggerSystem;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Team elimination uses the same real-unit census as BNE's opponent query. */
class MultiplayerOutcomeTest {

    private static World world() {
        GameMap map = new GameMap(32, 32, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        Player[] players = new Player[Player.MAX];
        for (int player = 0; player < players.length; player++) {
            PudMap.PlayerType type = player < 2
                    ? PudMap.PlayerType.PERSON
                    : player == 2 ? PudMap.PlayerType.COMPUTER
                            : PudMap.PlayerType.NOBODY;
            players[player] = new Player(player, type, PudMap.Race.HUMAN);
        }
        World world = new World(map, players);
        world.setAllied(0, 1, true);
        world.setAllied(1, 0, true);
        world.setAllied(0, 2, false);
        world.setAllied(2, 0, false);
        world.setAllied(1, 2, false);
        world.setAllied(2, 1, false);
        return world;
    }

    private static UnitType soldier() {
        UnitType type = new UnitType("unit-footman");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        AnimationSet animations = new AnimationSet("soldier");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", java.util.List.of("frame 0", "wait 1")));
        type.setAnimationSet(animations);
        return type;
    }

    @Test
    @DisplayName("two allied humans win when the computer's last unit is gone")
    void alliedHumansVersusComputerEndsForTheWholeTeam() {
        World world = world();
        UnitType soldier = soldier();
        Unit chris = world.createUnit(soldier, 0, 5, 5);
        Unit connor = world.createUnit(soldier, 1, 6, 5);
        Unit computer = world.createUnit(soldier, 2, 20, 20);

        assertEquals(TriggerSystem.Outcome.RUNNING,
                MultiplayerOutcome.evaluate(world, 0));
        world.kill(computer);
        assertEquals(TriggerSystem.Outcome.VICTORY,
                MultiplayerOutcome.evaluate(world, 0));
        assertEquals(TriggerSystem.Outcome.VICTORY,
                MultiplayerOutcome.evaluate(world, 1));

        world.kill(chris);
        assertEquals(TriggerSystem.Outcome.VICTORY,
                MultiplayerOutcome.evaluate(world, 0),
                "an eliminated player still shares the surviving teammate's result");
        world.kill(connor);
        assertEquals(TriggerSystem.Outcome.DRAW,
                MultiplayerOutcome.evaluate(world, 0));
    }

    @Test
    @DisplayName("an allied survivor prevents defeat until the whole team is gone")
    void defeatCountsTheAllianceRatherThanOnlyTheLocalSlot() {
        World world = world();
        UnitType soldier = soldier();
        Unit chris = world.createUnit(soldier, 0, 5, 5);
        Unit connor = world.createUnit(soldier, 1, 6, 5);
        world.createUnit(soldier, 2, 20, 20);

        world.kill(chris);
        assertEquals(TriggerSystem.Outcome.RUNNING,
                MultiplayerOutcome.evaluate(world, 0));
        world.kill(connor);
        assertEquals(TriggerSystem.Outcome.DEFEAT,
                MultiplayerOutcome.evaluate(world, 0));
    }
}
