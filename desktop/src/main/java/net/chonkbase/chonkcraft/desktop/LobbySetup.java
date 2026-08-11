package net.chonkbase.chonkcraft.desktop;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.network.GameLobby;
import net.chonkbase.chonkcraft.engine.network.LockstepScheduler;
import net.chonkbase.chonkcraft.engine.network.NetworkGame;
import net.chonkbase.chonkcraft.engine.network.NetworkSession;
import net.chonkbase.chonkcraft.engine.unit.UnitType;

/**
 * A settled lobby becoming a running game.
 *
 * <p>The hard part of starting a lockstep game is not the sockets. It is that
 * every machine must begin from the same world: the same map, the same roster
 * in the same order, and the same account of who is playing which slot. One
 * disagreement about any of that is a desync on the first cycle, and one that
 * looks like a network fault rather than a setup mistake.
 *
 * <p>All of that was agreed in the lobby, so nothing is decided here. This is
 * the seam, and keeping it thin is the point: everything it needs it takes
 * from {@link GameLobby}, including the socket, so there is no second copy of
 * the agreement to drift out of step with the first.
 */
record LobbySetup(Path map, GameLobby lobby) {

    /** The lockstep driver, ready to run, with its world alongside. */
    record Started(NetworkGame game, World world, CommandApplier applier) {}

    /** Which slot this machine plays, as the lobby settled it. */
    int localPlayer() {
        return Math.max(0, lobby.state().localSlot());
    }

    /** The lobby's verified map, falling back only for an older protocol-only caller. */
    byte[] mapBytes(AssetSource assets) throws IOException {
        byte[] synchronizedMap = lobby.mapBytes();
        return synchronizedMap != null ? synchronizedMap : Main.mapBytes(assets, map);
    }

    /** The exact player table the settled lobby describes. */
    Player[] players(PudMap source) {
        PudMap.PlayerType[] types = new PudMap.PlayerType[Player.MAX];
        PudMap.Race[] races = source.races().clone();
        java.util.Arrays.fill(types, PudMap.PlayerType.NOBODY);
        for (int player = 0; player < Player.MAX; player++) {
            if (source.players()[player] == PudMap.PlayerType.NEUTRAL) {
                types[player] = PudMap.PlayerType.NEUTRAL;
            }
        }
        for (GameLobby.Slot slot : lobby.state().slots()) {
            types[slot.index()] = switch (slot.occupant()) {
                case HUMAN -> PudMap.PlayerType.PERSON;
                case COMPUTER -> PudMap.PlayerType.COMPUTER;
                case OPEN, CLOSED -> PudMap.PlayerType.NOBODY;
            };
            races[slot.index()] = "orc".equalsIgnoreCase(slot.race())
                    ? PudMap.Race.ORC : PudMap.Race.HUMAN;
        }
        return Player.forNetworkGame(source, types, races);
    }

    /**
     * Opens the game on the lobby's own socket.
     *
     * <p>Slots nobody holds are quit at cycle zero. Left active, the scheduler
     * waits forever for commands from a machine that does not exist, and that
     * wait is indistinguishable from a lost packet -- which is why it is
     * settled here rather than diagnosed later.
     */
    Started start(net.chonkbase.chonkcraft.engine.GameData data, World world) throws IOException {
        List<UnitType> roster = new ArrayList<>(data.unitTypes().types().values());
        CommandApplier applier = new CommandApplier(world, roster);
        data.configureCommands(applier);

        GameLobby.State state = lobby.state();
        int localPlayer = localPlayer();

        // The socket is taken over rather than reopened. Closing the lobby's
        // and binding the same port again leaves a window in which the implementation is
        // free, and on a machine running two copies the other one takes it.
        NetworkSession session = new NetworkSession(localPlayer, lobby.releaseSocket());
        session.setTrustedRelay(lobby.relayAddress());
        for (Map.Entry<Integer, SocketAddress> peer : lobby.peers().entrySet()) {
            session.addPeer(peer.getKey(), peer.getValue());
        }

        LockstepScheduler scheduler = new LockstepScheduler(Player.MAX);
        for (int player = 0; player < Player.MAX; player++) {
            boolean hasNetworkPeer = player < state.slots().size()
                    && state.slots().get(player).sendsNetworkCommands();
            if (!hasNetworkPeer) {
                scheduler.submit(0, player, List.of(GameCommand.quit(player)));
            }
        }

        NetworkGame game = new NetworkGame(world, session, scheduler, applier, localPlayer);
        // Only the host relays. It is the machine everybody can reach, so it
        // is the machine that carries traffic between players who cannot reach
        // each other.
        game.setRelaying(lobby.isHost());
        game.setHostPlayer(state.hostSlot());
        java.util.Map<Integer, String> names = new java.util.LinkedHashMap<>();
        for (GameLobby.Slot slot : state.slots()) {
            if (slot.occupant() == GameLobby.Occupant.HUMAN) {
                names.put(slot.index(), slot.name());
            }
        }
        game.setPlayerNames(names);
        game.start();
        return new Started(game, world, applier);
    }
}
