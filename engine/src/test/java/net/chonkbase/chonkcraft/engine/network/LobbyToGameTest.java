package net.chonkbase.chonkcraft.engine.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The whole way through: three people meet in a lobby and end up playing.
 *
 * <p>The pieces are each tested on their own, and each of them worked while
 * the join was still a dialogue box asking for {@code player@host:port}. What
 * was never tested is the seam -- that the slots the lobby handed out are the
 * slots the game runs, that the addresses it collected are the ones the
 * commands go to, and that a client's order reaches the other client through
 * a host that has to pass it on. Every one of those is a way to produce a
 * desync on the first cycle that looks like a network fault.
 */
class LobbyToGameTest {

    /** A small plain map, built identically on every machine. */
    private static World newWorld() {
        int size = 32;
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i,
                    i < 3 ? PudMap.PlayerType.PERSON : PudMap.PlayerType.NOBODY,
                    PudMap.Race.HUMAN);
        }
        return new World(map, players);
    }

    private static void pollUntil(String what, java.util.function.BooleanSupplier condition,
            GameLobby... lobbies) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            for (GameLobby lobby : lobbies) {
                lobby.poll();
            }
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("timed out waiting: " + what);
    }

    /** One player's running game, as the desktop would build it. */
    private record Machine(NetworkGame game, World world, int slot) {}

    /**
     * Turns a settled lobby into a running game, the way the desktop does.
     *
     * <p>Slots nobody holds are quit at cycle zero. Left active the scheduler
     * waits forever for commands from a machine that does not exist, and the
     * wait is indistinguishable from a lost packet.
     */
    private static Machine begin(GameLobby lobby, World world, List<UnitType> roster)
            throws Exception {
        GameLobby.State state = lobby.state();
        NetworkSession session = new NetworkSession(state.localSlot(), lobby.releaseSocket());
        session.setTrustedRelay(lobby.relayAddress());
        for (Map.Entry<Integer, SocketAddress> peer : lobby.peers().entrySet()) {
            java.net.InetSocketAddress where = (java.net.InetSocketAddress) peer.getValue();
            session.addPeer(peer.getKey(), where.getAddress(), where.getPort());
        }

        LockstepScheduler scheduler = new LockstepScheduler(Player.MAX);
        for (int player = 0; player < Player.MAX; player++) {
            boolean hasNetworkPeer = player < state.slots().size()
                    && state.slots().get(player).sendsNetworkCommands();
            if (!hasNetworkPeer) {
                scheduler.submit(0, player, List.of(GameCommand.quit(player)));
            }
        }

        CommandApplier applier = new CommandApplier(world, roster);
        NetworkGame game = new NetworkGame(world, session, scheduler, applier,
                state.localSlot());
        game.setRelaying(lobby.isHost());
        game.setHostPlayer(state.hostSlot());
        game.start();
        return new Machine(game, world, state.localSlot());
    }

    @Test
    @DisplayName("Three direct UDP players transfer the map and run the same game")
    void threePlayersMeetAndPlay() throws Exception {
        InetAddress local = InetAddress.getLoopbackAddress();
        List<UnitType> roster = List.of();
        byte[] hostedMap = new byte[122_000];
        for (int i = 0; i < hostedMap.length; i++) {
            hostedMap[i] = (byte) (i * 13 + 5);
        }
        byte[] staleMap = hostedMap.clone();
        staleMap[staleMap.length / 2]++;

        GameLobby hostLobby = GameLobby.host("Chris", "garden.pud", hostedMap, 8, 0);
        int directPort = hostLobby.connectionPort();
        GameLobby annLobby = GameLobby.join("Ann", local, directPort, name -> null);
        GameLobby bobLobby = GameLobby.join("Bob", local, directPort, name -> staleMap);
        assertTrue(directPort > 0,
                "the host must expose the actual UDP port that direct players should enter");

        int annSlot;
        int bobSlot;
        List<Machine> machines = new ArrayList<>();
        try {
            pollUntil("everyone seated", () -> hostLobby.humanCount() == 3,
                    hostLobby, annLobby, bobLobby);
            annSlot = annLobby.state().localSlot();
            bobSlot = bobLobby.state().localSlot();
            assertNotEquals(annSlot, bobSlot);

            pollUntil("both clients received the host's exact map",
                    () -> hostLobby.state().allPlayersReady()
                            && annLobby.state().mapReady() && bobLobby.state().mapReady(),
                    hostLobby, annLobby, bobLobby);
            assertArrayEquals(hostedMap, annLobby.mapBytes(),
                    "the client with no map did not receive the host's bytes");
            assertArrayEquals(hostedMap, bobLobby.mapBytes(),
                    "the client with a stale map kept it because the filename matched");

            // The computer participates in every deterministic simulation but
            // owns no socket and must never be awaited as a network peer.
            hostLobby.setOccupant(3, GameLobby.Occupant.COMPUTER);
            pollUntil("the clients saw the computer slot",
                    () -> annLobby.state().slots().get(3).occupant()
                            == GameLobby.Occupant.COMPUTER,
                    hostLobby, annLobby, bobLobby);

            hostLobby.start();
            pollUntil("both clients heard the start",
                    () -> annLobby.isStarted() && bobLobby.isStarted(),
                    hostLobby, annLobby, bobLobby);

            machines.add(begin(hostLobby, newWorld(), roster));
            machines.add(begin(annLobby, newWorld(), roster));
            machines.add(begin(bobLobby, newWorld(), roster));
        } finally {
            hostLobby.close();
            annLobby.close();
            bobLobby.close();
        }

        // Each machine plays the slot the lobby gave it, not the one it
        // guessed. This is the seam: the lobby's bookkeeping becoming the
        // game's.
        assertEquals(0, machines.get(0).slot(), "the host plays slot zero");
        assertEquals(annSlot, machines.get(1).slot());
        assertEquals(bobSlot, machines.get(2).slot());

        // One client gives an order. It has to reach the other client, and the
        // only way there is through the host.
        machines.get(1).game().issue(GameCommand.move(annSlot, 1, 10, 10));

        long deadline = System.currentTimeMillis() + 20_000L;
        int target = 200;
        while (System.currentTimeMillis() < deadline) {
            boolean allThere = true;
            for (Machine machine : machines) {
                NetworkGame.Step step = machine.game().update();
                assertNotEquals(NetworkGame.Step.DESYNC, step,
                        "the machines diverged at cycle " + machine.game().desyncCycle()
                                + " (player " + machine.game().desyncPlayer() + ")");
                if (machine.world().cycle() < target) {
                    allThere = false;
                }
            }
            if (allThere) {
                break;
            }
        }

        for (Machine machine : machines) {
            assertTrue(machine.world().cycle() >= target,
                    "slot " + machine.slot() + " only reached cycle "
                            + machine.world().cycle() + " waiting="
                            + machine.game().waitingOn() + "; a stall here means a batch never"
                            + " arrived, which through a relay means it was never passed on");
        }

        // Ran the same game, which is the only claim that matters.
        long first = SyncHash.of(machines.get(0).world());
        for (Machine machine : machines) {
            assertEquals(first, SyncHash.of(machine.world()),
                    "slot " + machine.slot() + " ended up in a different world");
        }
    }
}
