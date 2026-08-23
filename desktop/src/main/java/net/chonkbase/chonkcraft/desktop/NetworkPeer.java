package net.chonkbase.chonkcraft.desktop;

import java.net.InetAddress;
import java.net.URI;
import java.awt.image.BufferedImage;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.chonkbase.chonkcraft.data.graphic.IndexedImage;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.map.PudReader;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.MapRenderer;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.network.GameLobby;
import net.chonkbase.chonkcraft.engine.network.LockstepScheduler;
import net.chonkbase.chonkcraft.engine.network.NetworkGame;
import net.chonkbase.chonkcraft.engine.network.NetworkSession;
import net.chonkbase.chonkcraft.engine.network.SyncHash;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingClient;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.CreateGameRequest;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.JoinGameRequest;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.Visibility;
import net.chonkbase.chonkcraft.matchmaking.RelayDatagramSocket;

/**
 * A headless peer for a networked game.
 *
 * <p>Exists so multiplayer can be tested the way it is actually played:
 * separate operating-system processes talking over real sockets, each running
 * its own simulation. A test that runs both sides inside one JVM proves the
 * scheduling but not the wire, and the two failure modes are different.
 *
 * <pre>
 *   NetworkPeer --player 0 --port 7100 --peer 1@127.0.0.1:7101 --cycles 600
 *   NetworkPeer --player 1 --port 7101 --peer 0@127.0.0.1:7100 --cycles 600
 * </pre>
 *
 * <p>The lobby form also proves map synchronization between separate JVMs.
 * The joiner below deliberately hides its local map, receives the host's, and
 * only then enters the same lockstep game:
 *
 * <pre>
 *   NetworkPeer --lobby-host 7100 --map ALAMO.PUD --cycles 600
 *   NetworkPeer --lobby-join 127.0.0.1:7100 --without-map true --cycles 600
 * </pre>
 *
 * <p>Prints its final sync hash. Two peers that agree ran identical games; two
 * that disagree diverged, and the exit status says which.
 */
public final class NetworkPeer {

    private NetworkPeer() {
    }

    public static void main(String[] args) throws Exception {
        int localPlayer = 0;
        int port = 7100;
        int cycles = 600;
        List<String> peers = new ArrayList<>();
        String mapName = null;
        String lobbyHost = null;
        String lobbyJoin = null;
        String onlineHost = null;
        String onlineJoin = null;
        String matchmakerUrl = "https://match.chonkbase.net";
        boolean withoutMap = false;
        boolean computerPlayer = false;
        GameLobby.GameTemplate gameTemplate = GameLobby.GameTemplate.MELEE;

        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--player" -> localPlayer = Integer.parseInt(args[i + 1]);
                case "--port" -> port = Integer.parseInt(args[i + 1]);
                case "--cycles" -> cycles = Integer.parseInt(args[i + 1]);
                case "--peer" -> peers.add(args[i + 1]);
                case "--map" -> mapName = args[i + 1];
                case "--lobby-host" -> lobbyHost = args[i + 1];
                case "--lobby-join" -> lobbyJoin = args[i + 1];
                case "--online-host" -> onlineHost = args[i + 1];
                case "--online-join" -> onlineJoin = args[i + 1];
                case "--matchmaker-url" -> matchmakerUrl = args[i + 1];
                case "--without-map" -> withoutMap = Boolean.parseBoolean(args[i + 1]);
                case "--computer-player" -> computerPlayer = Boolean.parseBoolean(args[i + 1]);
                case "--game-template" -> gameTemplate = "top-vs-bottom".equalsIgnoreCase(
                        args[i + 1]) ? GameLobby.GameTemplate.TOP_VS_BOTTOM
                                : GameLobby.GameTemplate.MELEE;
                default -> { }
            }
        }

        AssetSource assets = AssetSource.fromEnvironment();
        if (assets == null) {
            System.err.println("Set WC2_INSTALL_DIR to a Warcraft II installation, "
                    + "or CHONKCRAFT_ASSET_PACK to an asset pack.");
            System.exit(2);
        }
        // The map by name. Both peers have to simulate the same bytes, and a
        // path is the one thing the two machines cannot agree on: they are
        // different machines. --map has always been a name.
        String wantedMap = mapName;
        boolean provingTopVsBottom = wantedMap == null
                && gameTemplate == GameLobby.GameTemplate.TOP_VS_BOTTOM
                && (lobbyHost != null || onlineHost != null);
        mapName = provingTopVsBottom
                ? findTopVsBottomMap(assets) : findMap(assets, wantedMap);
        if (mapName == null && lobbyJoin == null) {
            System.err.println("No map found.");
            System.exit(2);
        }
        if (mapName == null) {
            mapName = wantedMap == null ? "" : wantedMap;
        }
        if (lobbyJoin == null && onlineJoin == null) {
            System.out.println("selected map " + mapName);
        }
        int lobbyModes = (lobbyHost == null ? 0 : 1) + (lobbyJoin == null ? 0 : 1)
                + (onlineHost == null ? 0 : 1) + (onlineJoin == null ? 0 : 1);
        if (lobbyModes > 1) {
            throw new IllegalArgumentException("choose one direct or online lobby mode");
        }

        LobbyRun lobbyRun = null;
        byte[] selectedMap = assets.map(mapName);
        if (onlineHost != null || onlineJoin != null) {
            URI service = URI.create(onlineHost != null ? onlineHost : matchmakerUrl);
            lobbyRun = meetOnline(assets, mapName, selectedMap, service,
                    onlineJoin, withoutMap, computerPlayer, gameTemplate);
            localPlayer = lobbyRun.localPlayer();
            mapName = lobbyRun.mapName();
            selectedMap = lobbyRun.mapBytes();
        } else if (lobbyHost != null || lobbyJoin != null) {
            lobbyRun = meetInLobby(assets, mapName, selectedMap,
                    lobbyHost, lobbyJoin, withoutMap, computerPlayer, gameTemplate);
            localPlayer = lobbyRun.localPlayer();
            mapName = lobbyRun.mapName();
            selectedMap = lobbyRun.mapBytes();
        }

        GameData data = new GameData(assets);
        PudMap source = PudReader.read(selectedMap);
        GameMap map = GameMap.from(source, data.loadTileset(source.tileset()).tileset());
        Set<Integer> directPeers = lobbyRun == null
                ? peerPlayers(localPlayer, peers) : Set.of();
        LobbySetup lobbySetup = lobbyRun == null ? null
                : new LobbySetup(Paths.get(mapName), lobbyRun.lobby());
        World world = new World(map, lobbySetup == null
                ? directPlayers(source, directPeers)
                : lobbySetup.players(source));
        data.configureWorld(world, source);
        data.populate(world, source);
        if (lobbySetup != null) {
            lobbySetup.applyGameTemplate(world, source);
        }
        world.recalculateSupply();
        int activeComputerAis = world.enableAiForComputerPlayers();
        var aiAssignments = data.attachRetailAi(world, source, java.util.Map.of());
        if (lobbySetup != null
                && lobbyRun.lobby().state().gameTemplate()
                        == GameLobby.GameTemplate.TOP_VS_BOTTOM) {
            System.out.printf("peer %d team: allies=%s shared-vision=%s enemies=%s%n",
                    localPlayer,
                    relatedPlayers(lobbyRun.lobby(), world, localPlayer,
                            Relation.ALLIED),
                    relatedPlayers(lobbyRun.lobby(), world, localPlayer,
                            Relation.SHARED_VISION),
                    relatedPlayers(lobbyRun.lobby(), world, localPlayer,
                            Relation.ENEMY));
            proveTopVsBottomScenario(lobbyRun.lobby(), world, localPlayer,
                    activeComputerAis, aiAssignments.size());
        }
        int visibleTiles = visibleTiles(world, localPlayer);
        if (visibleTiles == 0) {
            throw new IllegalStateException("player " + localPlayer
                    + " has an all-black initial view");
        }
        int nonBlackPixels = renderedPixels(data, source, world, localPlayer);
        if (nonBlackPixels == 0) {
            throw new IllegalStateException("player " + localPlayer
                    + " has an all-black rendered frame");
        }
        System.out.printf("peer %d initial view: visible=%d start=%s%n",
                localPlayer, visibleTiles,
                java.util.Arrays.toString(source.startLocation(localPlayer)));
        System.out.printf("peer %d rendered frame: nonblack=%d%n",
                localPlayer, nonBlackPixels);

        // The roster in a fixed order, so a unit type can travel as an index
        // and mean the same thing on both machines.
        NetworkGame game;
        int listeningPort;
        int peerCount;
        if (lobbyRun != null) {
            // Exercise the exact desktop lobby-to-game seam. Keeping a second
            // implementation here once hid a production startup deadlock.
            listeningPort = lobbyRun.lobby().localPort();
            peerCount = lobbyRun.lobby().peers().size();
            game = lobbySetup.start(data, world).game();
        } else {
            List<UnitType> roster = new ArrayList<>(data.unitTypes().types().values());
            CommandApplier applier = new CommandApplier(world, roster);
            data.configureCommands(applier);
            NetworkSession session = new NetworkSession(localPlayer, port);
            for (String peer : peers) {
                // player@host:port
                int at = peer.indexOf('@');
                int colon = peer.lastIndexOf(':');
                session.addPeer(Integer.parseInt(peer.substring(0, at)),
                        InetAddress.getByName(peer.substring(at + 1, colon)),
                        Integer.parseInt(peer.substring(colon + 1)));
            }
            LockstepScheduler scheduler = new LockstepScheduler(Player.MAX);
            markAbsentPlayers(scheduler, directPeers);
            game = new NetworkGame(world, session, scheduler, applier, localPlayer);
            game.start();
            listeningPort = session.localPort();
            peerCount = session.peers().size();
        }

        System.out.printf("peer %d listening on %d, %d peers, map %s%n",
                localPlayer, listeningPort, peerCount, mapName);

        // A scripted order, identical on both machines by construction: each
        // sends its own units somewhere at a fixed cycle. Both machines then
        // execute both sets, and must agree.
        int ordered = 0;
        long deadline = System.currentTimeMillis() + 60_000;
        int advanced = 0;

        while (advanced < cycles && System.currentTimeMillis() < deadline) {
            if (advanced == 60 && ordered == 0) {
                for (Unit unit : world.units()) {
                    if (unit.player() == localPlayer && !unit.type().building()
                            && unit.type().speed() > 0 && ordered < 3) {
                        game.issue(GameCommand.move(localPlayer, unit.id(),
                                unit.tileX() + 3, unit.tileY() + 3));
                        ordered++;
                    }
                }
            }
            NetworkGame.Step step = game.update();
            if (step == NetworkGame.Step.ADVANCED) {
                advanced++;
            } else if (step == NetworkGame.Step.DESYNC) {
                System.out.printf("DESYNC at net cycle %d with player %d%n",
                        game.desyncCycle(), game.desyncPlayer());
                game.close();
                System.exit(3);
            } else {
                // Nothing to do until a packet arrives. A short sleep keeps
                // this from spinning a core while a peer catches up.
                Thread.sleep(1);
            }
        }

        long hash = SyncHash.of(world);
        System.out.printf("peer %d finished: cycles=%d units=%d hash=%016x%n",
                localPlayer, world.cycle(), world.units().size(), hash);
        game.close();
    }

    /**
     * Marks every slot with nobody behind it as gone.
     *
     * <p>A scheduler waits for all active players, and a sixteen-slot map has
     * fourteen empty ones. Without this the game would wait forever for
     * players who do not exist.
     */
    private static Set<Integer> peerPlayers(int localPlayer, List<String> peers) {
        Set<Integer> present = new LinkedHashSet<>();
        present.add(localPlayer);
        for (String peer : peers) {
            present.add(Integer.parseInt(peer.substring(0, peer.indexOf('@'))));
        }
        return present;
    }

    private static Player[] directPlayers(PudMap source, Set<Integer> present) {
        PudMap.PlayerType[] types = new PudMap.PlayerType[Player.MAX];
        java.util.Arrays.fill(types, PudMap.PlayerType.NOBODY);
        for (int player = 0; player < Player.MAX; player++) {
            if (source.players()[player] == PudMap.PlayerType.NEUTRAL) {
                types[player] = PudMap.PlayerType.NEUTRAL;
            } else if (present.contains(player)) {
                types[player] = PudMap.PlayerType.PERSON;
            }
        }
        return Player.forNetworkGame(source, types, source.races());
    }

    private static void markAbsentPlayers(LockstepScheduler scheduler, Set<Integer> present) {
        for (int slot = 0; slot < Player.MAX; slot++) {
            if (!present.contains(slot)) {
                scheduler.submit(0, slot, List.of(GameCommand.quit(slot)));
            }
        }
    }

    private static int visibleTiles(World world, int player) {
        int visible = 0;
        for (int y = 0; y < world.map().height(); y++) {
            for (int x = 0; x < world.map().width(); x++) {
                if (world.isVisibleTo(player, x, y)) {
                    visible++;
                }
            }
        }
        return visible;
    }

    /** Paints the actual battlefield renderer and rejects the reported black-client failure. */
    private static int renderedPixels(GameData data, PudMap source, World world,
            int localPlayer) {
        GameData.LoadedTileset tileset = data.loadTileset(source.tileset());
        IndexedImage rendered = new MapRenderer(tileset.tileset(), tileset.sheet())
                .render(world.map().width(), world.map().height(), world.map().tileCodes());
        BufferedImage terrain = rendered.toIndexedBufferedImage(tileset.palette());
        String tilesetName = source.tileset() == PudMap.Tileset.FOREST
                ? "summer" : source.tileset().name().toLowerCase(java.util.Locale.ROOT);
        String race = source.races()[localPlayer] == PudMap.Race.ORC ? "orc" : "human";
        GameScreen screen = new GameScreen(world, data, terrain, tileset.palette(),
                tilesetName, localPlayer, 640, 480, null, null, null, null, null,
                List.of(), race);
        screen.setSize(640, 480);
        screen.setLayout((net.chonkbase.chonkcraft.engine.ui.UiLayout.Layout) null);
        screen.setGameScale(1);
        screen.setFogTiles(FogTiles.from(tileset.sheet(), data.fogOfWar().levels()));
        screen.setFogOpacity(data.fogOfWar().levels());
        int[] start = source.startLocation(localPlayer);
        if (start != null) {
            screen.centreOn(start[0], start[1]);
        }
        BufferedImage frame = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
        var graphics = frame.createGraphics();
        screen.paint(graphics);
        graphics.dispose();
        int nonBlack = 0;
        for (int y = 0; y < frame.getHeight(); y++) {
            for (int x = 0; x < frame.getWidth(); x++) {
                if ((frame.getRGB(x, y) & 0x00ff_ffff) != 0) {
                    nonBlack++;
                }
            }
        }
        return nonBlack;
    }

    /** A lobby, its agreed map and the slot it assigned this process. */
    private record LobbyRun(GameLobby lobby, int localPlayer, String mapName, byte[] mapBytes) {}

    /** Meets another process through the same public HTTPS/WSS path as the desktop menus. */
    private static LobbyRun meetOnline(AssetSource assets, String mapName, byte[] selectedMap,
            URI service, String joinCode, boolean withoutMap,
            boolean computerPlayer, GameLobby.GameTemplate gameTemplate) throws Exception {
        boolean hosting = joinCode == null;
        MatchmakingClient client = new MatchmakingClient(service);
        MatchmakingProtocol.Seat seat;
        RelayDatagramSocket relay;
        GameLobby lobby;
        if (hosting) {
            int capacity = Math.max(2, Math.min(8,
                    PudReader.read(selectedMap).playableSlots()));
            String digest = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(selectedMap));
            seat = client.create(new CreateGameRequest("Real client deployment proof",
                    mapName, digest, capacity, Visibility.PRIVATE,
                    MatchmakingProtocol.gameBuild()));
            relay = new RelayDatagramSocket(URI.create(seat.relayUri()),
                    seat.relayTicket(), seat.endpointId());
            lobby = GameLobby.hostRelayed("Harness Host", mapName, selectedMap,
                    capacity, relay);
            System.out.println("online room " + seat.game().code());
        } else {
            seat = client.join(joinCode,
                    new JoinGameRequest("Harness Client", MatchmakingProtocol.gameBuild()));
            relay = new RelayDatagramSocket(URI.create(seat.relayUri()),
                    seat.relayTicket(), seat.endpointId());
            lobby = GameLobby.joinRelayed("Harness Client", relay,
                    seat.hostEndpointId(), wanted -> {
                        if (withoutMap) {
                            return null;
                        }
                        String local = findMap(assets, wanted);
                        return local == null ? null : assets.map(local);
                    });
        }

        long deadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < deadline && !lobby.isStarted()) {
            lobby.poll();
            if (hosting && lobby.humanCount() >= 2 && lobby.state().allPlayersReady()) {
                settleAndStart(lobby, computerPlayer, gameTemplate,
                        PudReader.read(selectedMap));
                relay.markRoomStarted();
                lobby.start();
            }
            Thread.sleep(2);
        }
        if (!lobby.isStarted() || lobby.mapBytes() == null) {
            lobby.close();
            throw new IllegalStateException("the online lobby did not synchronize and start");
        }
        System.out.printf("online lobby slot %d map %s bytes=%d source=%s template=%s%n",
                lobby.state().localSlot(), lobby.state().map(), lobby.mapBytes().length,
                lobby.mapWasTransferred() ? "host-transfer" : "local",
                lobby.state().gameTemplate());
        return new LobbyRun(lobby, lobby.state().localSlot(), lobby.state().map(),
                lobby.mapBytes());
    }

    /** Meets one other process, synchronizes the map, and leaves the socket ready for play. */
    private static LobbyRun meetInLobby(AssetSource assets, String mapName, byte[] selectedMap,
            String hostPort, String joinAddress, boolean withoutMap,
            boolean computerPlayer, GameLobby.GameTemplate gameTemplate) throws Exception {
        boolean hosting = hostPort != null;
        GameLobby lobby;
        if (hosting) {
            int capacity = Math.max(2, Math.min(8,
                    PudReader.read(selectedMap).playableSlots()));
            lobby = GameLobby.host("Harness Host", mapName, selectedMap, capacity,
                    Integer.parseInt(hostPort));
        } else {
            int colon = joinAddress.lastIndexOf(':');
            InetAddress address = InetAddress.getByName(joinAddress.substring(0, colon));
            int port = Integer.parseInt(joinAddress.substring(colon + 1));
            lobby = GameLobby.join("Harness Client", address, port, wanted -> {
                if (withoutMap) {
                    return null;
                }
                String local = findMap(assets, wanted);
                return local == null ? null : assets.map(local);
            });
        }

        long deadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < deadline && !lobby.isStarted()) {
            lobby.poll();
            if (hosting && lobby.humanCount() >= 2 && lobby.state().allPlayersReady()) {
                settleAndStart(lobby, computerPlayer, gameTemplate,
                        PudReader.read(selectedMap));
                lobby.start();
            }
            Thread.sleep(2);
        }
        if (!lobby.isStarted() || lobby.mapBytes() == null) {
            lobby.close();
            throw new IllegalStateException("the lobby did not synchronize and start");
        }
        System.out.printf("lobby slot %d map %s bytes=%d source=%s template=%s%n",
                lobby.state().localSlot(), lobby.state().map(), lobby.mapBytes().length,
                lobby.mapWasTransferred() ? "host-transfer" : "local",
                lobby.state().gameTemplate());
        return new LobbyRun(lobby, lobby.state().localSlot(), lobby.state().map(),
                lobby.mapBytes());
    }

    private static void settleAndStart(GameLobby lobby, boolean computerPlayer,
            GameLobby.GameTemplate gameTemplate, PudMap source) {
        lobby.setGameTemplate(gameTemplate);
        LobbyTeams teams = LobbyTeams.from(source, lobby.capacity());
        int host = lobby.state().hostSlot();
        if (gameTemplate == GameLobby.GameTemplate.TOP_VS_BOTTOM) {
            // The production gate deliberately exercises the user's setup:
            // two people on one displayed team, one computer on the other.
            // A joining human initially takes the first colour slot, which
            // can belong to the opposite area on an interleaved retail map.
            for (GameLobby.Slot slot : lobby.state().slots()) {
                if (slot.occupant() != GameLobby.Occupant.HUMAN
                        || slot.index() == host || teams.together(host, slot.index())) {
                    continue;
                }
                for (GameLobby.Slot target : lobby.state().slots()) {
                    if (target.occupant() == GameLobby.Occupant.OPEN
                            && teams.together(host, target.index())) {
                        lobby.move(slot.index(), target.index());
                        break;
                    }
                }
            }
        }
        if (computerPlayer) {
            for (GameLobby.Slot slot : lobby.state().slots()) {
                if (slot.occupant() == GameLobby.Occupant.OPEN
                        && (gameTemplate != GameLobby.GameTemplate.TOP_VS_BOTTOM
                                || !teams.together(host, slot.index()))) {
                    lobby.setOccupant(slot.index(), GameLobby.Occupant.COMPUTER);
                    break;
                }
            }
        }
        for (GameLobby.Slot slot : lobby.state().slots()) {
            if (slot.occupant() == GameLobby.Occupant.OPEN) {
                lobby.setOccupant(slot.index(), GameLobby.Occupant.CLOSED);
            }
        }
    }

    private enum Relation {
        ALLIED,
        SHARED_VISION,
        ENEMY
    }

    /**
     * Proves the exact player-reported layout rather than merely finding some alliance.
     *
     * <p>The release referee creates two people on one displayed team and one
     * computer on the other. Its old regular expression accepted any non-empty
     * ally/enemy lists, so it passed even if the human was allied to the
     * computer and the other human was the enemy. This assertion names the
     * occupants and also starts the computer's retail AI before allowing the
     * two-process proof to continue.
     */
    private static void proveTopVsBottomScenario(GameLobby lobby, World world,
            int localPlayer, int activeComputerAis, int attachedComputerAis) {
        long humanAllies = lobby.state().slots().stream()
                .filter(slot -> slot.index() != localPlayer)
                .filter(slot -> slot.occupant() == GameLobby.Occupant.HUMAN)
                .filter(slot -> world.isAllied(localPlayer, slot.index())
                        && world.isAllied(slot.index(), localPlayer))
                .count();
        long sharedHumanAllies = lobby.state().slots().stream()
                .filter(slot -> slot.index() != localPlayer)
                .filter(slot -> slot.occupant() == GameLobby.Occupant.HUMAN)
                .filter(slot -> world.sharesVisionWith(localPlayer, slot.index())
                        && world.sharesVisionWith(slot.index(), localPlayer))
                .count();
        long computerEnemies = lobby.state().slots().stream()
                .filter(slot -> slot.occupant() == GameLobby.Occupant.COMPUTER)
                .filter(slot -> world.isEnemyPlayer(localPlayer, slot.index())
                        && world.isEnemyPlayer(slot.index(), localPlayer))
                .count();
        if (humanAllies != 1 || sharedHumanAllies != 1 || computerEnemies != 1
                || activeComputerAis != 1 || attachedComputerAis != 1) {
            throw new IllegalStateException("Top vs Bottom did not produce one human ally "
                    + "with shared sight and one live computer enemy: human-allies="
                    + humanAllies + " shared-human-allies=" + sharedHumanAllies
                    + " computer-enemies=" + computerEnemies + " active-computer-ais="
                    + activeComputerAis + " attached-computer-ais=" + attachedComputerAis);
        }
        System.out.printf("peer %d team-proof: human-allies=%d shared-human-allies=%d "
                        + "computer-enemies=%d active-computer-ais=%d attached-computer-ais=%d%n",
                localPlayer, humanAllies, sharedHumanAllies, computerEnemies,
                activeComputerAis, attachedComputerAis);
    }

    /** A machine-readable account of the relationships applied before cycle zero. */
    private static List<Integer> relatedPlayers(GameLobby lobby, World world,
            int localPlayer, Relation relation) {
        List<Integer> related = new ArrayList<>();
        for (GameLobby.Slot slot : lobby.state().slots()) {
            if (!slot.isPlaying() || slot.index() == localPlayer) {
                continue;
            }
            boolean matches = switch (relation) {
                case ALLIED -> world.isAllied(localPlayer, slot.index());
                case SHARED_VISION -> world.sharesVisionWith(localPlayer, slot.index());
                case ENEMY -> world.isEnemyPlayer(localPlayer, slot.index());
            };
            if (matches) {
                related.add(slot.index());
            }
        }
        return List.copyOf(related);
    }

    /**
     * The map to play, by the name the source knows it by.
     *
     * <p>Nothing named on the command line takes the first map there is, which
     * is how the two-process test is launched: neither side names one, and both
     * take the same first because the source hands them out in one order.
     */
    private static String findMap(AssetSource assets, String wanted) {
        for (String candidate : assets.mapNames()) {
            if (wanted == null || candidate.equalsIgnoreCase(wanted)
                    || Paths.get(candidate).getFileName().toString().equalsIgnoreCase(wanted)) {
                return candidate;
            }
        }
        return null;
    }

    /** A real map capable of the release gate's two-people-versus-one-AI proof. */
    private static String findTopVsBottomMap(AssetSource assets) {
        for (String candidate : assets.mapNames()) {
            byte[] bytes = assets.map(candidate);
            if (bytes == null) {
                continue;
            }
            PudMap source;
            try {
                source = PudReader.read(bytes);
            } catch (RuntimeException malformed) {
                continue;
            }
            int capacity = Math.max(2, Math.min(8, source.playableSlots()));
            if (capacity < 3 || source.startLocation(0) == null) {
                continue;
            }
            LobbyTeams teams = LobbyTeams.from(source, capacity);
            boolean openAlly = false;
            boolean openEnemy = false;
            for (int slot = 1; slot < capacity; slot++) {
                if (source.startLocation(slot) == null) {
                    continue;
                }
                if (teams.together(0, slot)) {
                    openAlly = true;
                } else {
                    openEnemy = true;
                }
            }
            if (openAlly && openEnemy) {
                return candidate;
            }
        }
        return null;
    }
}
