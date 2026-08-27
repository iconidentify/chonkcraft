package net.chonkbase.chonkcraft.engine.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A running network game exposing an observational command record.
 *
 * <p>A recorder attached before the lockstep release would be able to retain
 * commands that another peer had not accepted, while one called from the
 * desktop frame loop would miss cycles during stalls and headless play. These
 * drive the real network update boundary and check the state after it, because
 * a callback tested in isolation would not establish either ordering fact.
 */
class NetworkCycleSinkTest {

    @Test
    @DisplayName("a completed lockstep cycle carries its commands and resulting hash")
    void aCompletedCycleReachesThePassiveSink() throws Exception {
        World world = new World(new GameMap(8, 8, new Tileset()));
        List<String> observations = new ArrayList<>();
        try (NetworkSession session = new NetworkSession(0, 0)) {
            NetworkGame game = new NetworkGame(world, session,
                    new LockstepScheduler(1, 1, 0),
                    new CommandApplier(world, List.of()), 0);
            game.setCycleSink((netCycle, worldCycle, commands, syncHash) ->
                    observations.add(netCycle + ":" + worldCycle + ":"
                            + commands + ":" + syncHash));
            game.start();
            GameCommand command = GameCommand.stop(0, 9_999);
            game.issue(command);

            assertEquals(NetworkGame.Step.ADVANCED, game.update(),
                    "the observer stopped the lockstep boundary from advancing");
            assertEquals(List.of("0:1:[" + command + "]:" + SyncHash.of(world)), observations,
                    "the observer did not receive the accepted batch and post-cycle world");
            game.close();
        }
    }

    @Test
    @DisplayName("a broken recorder cannot freeze the multiplayer game")
    void aBrokenSinkIsRemovedWithoutStoppingTheWorld() throws Exception {
        World world = new World(new GameMap(8, 8, new Tileset()));
        try (NetworkSession session = new NetworkSession(0, 0)) {
            NetworkGame game = new NetworkGame(world, session,
                    new LockstepScheduler(1, 1, 0),
                    new CommandApplier(world, List.of()), 0);
            game.setCycleSink((netCycle, worldCycle, commands, syncHash) -> {
                throw new IllegalStateException("full disk");
            });
            game.start();

            assertEquals(NetworkGame.Step.ADVANCED, game.update(),
                    "a recorder exception escaped the network update and froze play");
            assertEquals(1, world.cycle(),
                    "the world did not finish the cycle whose record failed");
            assertEquals(NetworkGame.Step.ADVANCED, game.update(),
                    "the failed recorder remained installed on the next cycle");
            game.close();
        }
    }
}
