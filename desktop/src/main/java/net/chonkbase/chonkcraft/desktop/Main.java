package net.chonkbase.chonkcraft.desktop;

import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import net.chonkbase.runtime.FixedStepLoop;
import net.chonkbase.runtime.Java2DPipeline;
import net.chonkbase.runtime.PlatformFullscreen;
import net.chonkbase.chonkcraft.data.graphic.IndexedImage;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.map.PudReader;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.campaign.Campaign;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameDiscovery;
import net.chonkbase.chonkcraft.engine.network.GameLobby;
import net.chonkbase.chonkcraft.engine.save.LoadGame;
import net.chonkbase.chonkcraft.engine.trigger.TriggerSystem;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.MapRenderer;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingClient;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.CreateGameRequest;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.JoinGameRequest;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.Visibility;
import net.chonkbase.chonkcraft.matchmaking.RelayDatagramSocket;

/**
 * Runs the implementation: loads a Warcraft II map, populates it, and plays it.
 *
 * <p>Everything comes from the player's own copy of the game: terrain, units,
 * sprites, sounds, campaign declarations, and the tech tree come from the
 * authenticated pack and native game JAR. The simulation advances on a fixed
 * thirty-cycle second, which is what makes a lockstep multiplayer game and a
 * reproducible test possible at all.
 *
 * <p>This javadoc used to list combat, resource gathering, construction, fog
 * of war, AI, sound and the interface panels as things that did not work yet.
 * All of them do. The list is not replaced with a new one: a class comment
 * that inventories the implementation's progress goes stale the week after it is
 * written, and a stale one is worse than none because it is believed. What is
 * missing lives in focused tests and in the issue tracker, both of which are kept
 * current on purpose.
 *
 * <pre>
 *   -Dchonkcraft.pack=/path/to/chonkcraft.chonkpack   an asset pack, if there is one
 *   -Dwc2.install.dir=/path/to/Warcraft   the game directory holding DATA/
 *   -Dchonkcraft.map=ALAMO.PUD                which map to open
 *   -Dchonkcraft.music=xmi                    the synthesised score, not the discs
 * </pre>
 *
 * <p>A map is named rather than located from here down. It used to be a
 * {@link Path} throughout, which is the one identity a pack cannot supply: a
 * map inside a pack is an entry with no place on the disk. The paths this
 * class passes about now spell the name the source knows the map by, and the
 * bytes are asked of the source rather than read.
 */
public final class Main {

    /**
     * The window every screen is shown in.
     *
     * <p>Built once and never replaced, so the size, position and full-screen
     * state the player chose survive moving between the title sequence, the
     * menu, a briefing and the game.
     */
    private static AppWindow window;

    /** The window, opening it if this is the first screen to ask. */
    private static synchronized AppWindow window() {
        if (window == null) {
            // -Dchonkcraft.window=1920x1200 opens at a stated size, so the same
            // screen can be looked at at every scale it will be played at
            // rather than only the one this machine happens to give it.
            int width = WINDOW_WIDTH;
            int height = WINDOW_HEIGHT;
            String wanted = System.getProperty("chonkcraft.window", "");
            int cross = wanted.indexOf('x');
            if (cross > 0) {
                try {
                    width = Integer.parseInt(wanted.substring(0, cross).trim());
                    height = Integer.parseInt(wanted.substring(cross + 1).trim());
                } catch (NumberFormatException ignored) {
                    width = WINDOW_WIDTH;
                    height = WINDOW_HEIGHT;
                }
            }
            window = new AppWindow(DesktopApplicationIdentity.NAME, width, height);
        }
        return window;
    }

    private static final int WINDOW_WIDTH = 1280;
    private static final int WINDOW_HEIGHT = 800;

    private Main() {
    }

    public static void main(String[] args) {
        // Must run before AWT initialises.
        DesktopApplicationIdentity.initialize();
        Java2DPipeline.Choice pipeline = Java2DPipeline.apply();

        // Either way in: a pack if one is named or lying beside the game, and
        // the 1995 installation otherwise. The failure below names both,
        // because a player who has a pack and no installation was told to go
        // and find an installation.
        AssetSource assets = AssetSource.fromEnvironment();
        if (assets == null) {
            System.err.println("""
                    No Warcraft II installation found.
                    Point -Dwc2.install.dir (or WC2_INSTALL_DIR) at the game directory,
                    the one containing DATA/MAINDAT.WAR.
                    Or point -Dchonkcraft.pack (or CHONKCRAFT_ASSET_PACK) at an asset pack,
                    which is the same data in one file and needs no installation.""");
            System.exit(2);
        }
        GameData data = new GameData(assets);

        // A campaign mission or a skirmish map. The campaign path brings its
        // own world, already populated and with its triggers armed, because a
        // mission's rules are half of what it is.
        Mission mission = resolveMission(data);
        Path mapFile = mission != null ? null : resolveMap(assets);
        if (mission == null && mapFile == null && BattleShowcase.requested()) {
            String showcaseMap = BattleShowcase.defaultMapName(assets);
            if (showcaseMap == null) {
                System.err.println("The battle showcase needs at least one skirmish map.");
                System.exit(2);
                return;
            }
            mapFile = Paths.get(showcaseMap);
        }

        // Nothing named on the command line, so ask. The menu is the normal
        // way in; the flags are for getting straight to a particular game.
        if (mission == null && mapFile == null) {
            List<Path> maps = findMaps(assets);
            MenuScreen menu = new MenuScreen(data, "human", WINDOW_WIDTH, WINDOW_HEIGHT, null);
            if (menu.isAvailable() && (!maps.isEmpty() || !data.campaigns().isEmpty())) {
                // The native sequence owns application presentation; its
                // media entries resolve from the authenticated BNE pack.
                SwingUtilities.invokeLater(() -> playTitles(data, assets, () ->
                        runMenu(data, assets, maps, pipeline)));
                return;
            }
            System.err.println("No .PUD map found in " + assets.describe());
            System.exit(2);
            return;
        }
        // The flags that chose the mission are also what says where it came
        // from, so they travel with it: without them nothing downstream knows
        // this is a campaign game and the save has no path to record.
        if (mission != null) {
            start(data, assets, pipeline, mission, mapFile,
                    campaignSetting(), missionSetting());
        } else {
            start(data, assets, pipeline, mission, mapFile);
        }
    }

    /** The campaign named by {@code -Dchonkcraft.campaign}, or null. */
    private static String campaignSetting() {
        String name = setting("chonkcraft.campaign", "CHONKCRAFT_CAMPAIGN");
        return name == null || name.isBlank() ? null : name.toLowerCase(Locale.ROOT);
    }

    /** The mission number named by {@code -Dchonkcraft.mission}, defaulting to one. */
    private static int missionSetting() {
        String requested = setting("chonkcraft.mission", "CHONKCRAFT_MISSION");
        if (requested == null || requested.isBlank()) {
            return 1;
        }
        try {
            return Integer.parseInt(requested.trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * Plays the title sequence, then opens the menu.
     *
     * <p>Four native entries: a Java-painted black frame, the Blizzard logo,
     * the game's opening cinematic and the title picture. The latter three
     * resolve from the authenticated BNE pack.
     *
     * <p>Skipped entirely with {@code -Dchonkcraft.skipTitles}, which is what a
     * developer starting the game for the fortieth time wants.
     */
    private static void playTitles(GameData data, AssetSource assets, Runnable next) {
        if (Boolean.getBoolean("chonkcraft.skipTitles")) {
            next.run();
            return;
        }
        var screens = data.titleScreens();
        if (screens.isEmpty()) {
            next.run();
            return;
        }
        FrontEndAudio sound = frontEndAudio(data, assets);
        showTitle(data, sound, screens, 0, next);
    }

    /** Shows one title screen and chains to the next. */
    private static void showTitle(GameData data,
            FrontEndAudio sound,
            java.util.List<net.chonkbase.chonkcraft.engine.ui.TitleSequence.Screen> screens,
            int at, Runnable done) {
        if (at >= screens.size()) {
            done.run();
            return;
        }
        var screen = screens.get(at);
        Runnable andThen = () -> showTitle(data, sound, screens, at + 1, done);

        if (screen.kind() == net.chonkbase.chonkcraft.engine.ui.TitleSequence.Kind.VIDEO) {
            var video = data.video(screen.assetPath());
            if (video == null) {
                andThen.run();
                return;
            }
            // A cutscene carries its own soundtrack, so the music gets out of
            // its way. Upstream does the same, and
            // disables the finished-callback while it plays so the playlist
            // does not start a track over the film.
            sound.server().stopMusic();
            VideoScreen player = new VideoScreen(video, WINDOW_WIDTH, WINDOW_HEIGHT,
                    andThen, sound.audio());
            window().setKeyListener(null);
            window().show(player);
            player.play();
            return;
        }

        if (screen.menuMusic()) {
            sound.server().playMenuMusic();
        }
        if (screen.kind() == net.chonkbase.chonkcraft.engine.ui.TitleSequence.Kind.BLACK) {
            SplashScreen black = new SplashScreen(null, true,
                    Math.max(1, screen.timeoutSeconds()), WINDOW_WIDTH, WINDOW_HEIGHT, andThen);
            window().setKeyListener(null);
            window().show(black);
            black.begin();
            return;
        }

        var indexed = data.image(screen.assetPath());
        var palette = indexed == null ? null : data.paletteFor(screen.assetPath());
        if (indexed == null || palette == null) {
            // ChonkCraft supplies a couple of these as its own PNGs rather than
            // from the archive; a missing one is skipped, not fatal.
            andThen.run();
            return;
        }
        SplashScreen picture = new SplashScreen(indexed.toBufferedImage(palette),
                screen.stretch(), Math.max(1, screen.timeoutSeconds()),
                WINDOW_WIDTH, WINDOW_HEIGHT, andThen);
        window().setKeyListener(null);
        window().show(picture);
        picture.begin();
    }

    /** Shows the menu and starts whatever the player chooses. */
    /**
     * The music player, opened on first use.
     *
     * <p>{@code GameData.music()} memoises, so the menu, the briefings and the
     * mission all drive one sequencer rather than three. Upstream has the same
     * single player and simply changes its playlist at each transition.
     *
     * <p>Returns null when there is no synthesiser, which is a normal state on
     * a machine with no MIDI device; every caller here tolerates it.
     */
    private static net.chonkbase.chonkcraft.engine.sound.MusicPlayer music(GameData data) {
        var music = data.music();
        music.start();
        // The saved music volume, on the menus and the briefings as well as on
        // a running game. The slider used to exist only inside a game and its
        // effect died with it, so a player who turned the music down heard the
        // menu theme come back at full volume the moment they left.
        music.setVolume(settings().musicVolume());
        return music.isAvailable() ? music : null;
    }

    /**
     * The player's own settings, read once.
     *
     * <p>Once, and shared, so that a volume moved inside a game is the volume
     * the next menu and the next briefing use. Reading the file again in each
     * place would give each of them its own copy and put them out of step, and
     * out of step is what this whole area was.
     */
    private static Settings savedSettings;

    static synchronized Settings settings() {
        if (savedSettings == null) {
            savedSettings = Settings.load();
        }
        return savedSettings;
    }

    /**
     * Puts the saved volumes on a device opened outside a running game.
     *
     * <p>The title sequence, the act cards and the briefings each open a
     * device of their own, because the game's is not built until a map is
     * loaded. Each one is a separate mixer whose buses sit where they were
     * built, so without this a cutscene plays at full volume for a player who
     * has turned everything down -- which is half of "the cutscene music was
     * also super loud".
     */
    private static void applySavedVolumes(net.chonkbase.chonkcraft.engine.sound.GameAudio audio) {
        var volumes = new net.chonkbase.chonkcraft.engine.sound.SoundServer(
                audio.mixer(), null, null, null);
        volumes.setEffectVolume(settings().effectVolume());
        volumes.setMusicVolume(settings().musicVolume());
    }

    private static void runMenu(GameData data, AssetSource assets, List<Path> maps,
            Java2DPipeline.Choice pipeline) {
        // One front-end owner covers titles and menus, and honours the recorded
        // soundtrack default rather than unconditionally starting XMI here.
        FrontEndAudio sound = frontEndAudio(data, assets);
        if (!sound.server().isPlaying()) {
            sound.server().playMenuMusic();
        }
        MenuScreen menu = new MenuScreen(data, "human", WINDOW_WIDTH, WINDOW_HEIGHT, launch -> {
            if (launch.multiplayer() == MenuScreen.Launch.Multiplayer.HOST_PUBLIC
                    || launch.multiplayer() == MenuScreen.Launch.Multiplayer.HOST_PRIVATE) {
                openHostLobby(data, assets, maps, pipeline, launch.map(),
                        launch.multiplayer() == MenuScreen.Launch.Multiplayer.HOST_PRIVATE
                                ? Visibility.PRIVATE : Visibility.PUBLIC);
            } else if (launch.multiplayer() == MenuScreen.Launch.Multiplayer.HOST_DIRECT) {
                openDirectHostSetup(data, assets, maps, pipeline, launch.map());
            } else if (launch.multiplayer() == MenuScreen.Launch.Multiplayer.JOIN) {
                openJoinScreen(data, assets, maps, pipeline, false);
            } else if (launch.multiplayer() == MenuScreen.Launch.Multiplayer.JOIN_DIRECT) {
                openJoinScreen(data, assets, maps, pipeline, true);
            } else if (launch.lobby() != null) {
                new Thread(() -> startMultiplayer(data, assets, pipeline, launch.lobby()),
                        "chonkcraft-load").start();
            } else if (launch.save() != null) {
                new Thread(() -> resume(data, assets, pipeline, launch.save()),
                        "chonkcraft-load").start();
            } else if (launch.campaign() != null) {
                startCampaignMission(data, assets, pipeline,
                        launch.campaign(), launch.mission());
            } else {
                // Off the event thread: loading a map reads and decodes
                // several megabytes, and doing that here would freeze the menu
                // on the frame the player pressed.
                new Thread(() -> start(data, assets, pipeline, null, launch.map()),
                        "chonkcraft-load").start();
            }
        });
        menu.showMainMenu(data, maps);
        window().setKeyListener(null);
        window().setTitle("chonkcraft");
        window().show(menu);
    }

    /** What to call this machine to the other players. */
    private static String playerName() {
        String name = System.getProperty("chonkcraft.player.name",
                System.getProperty("user.name", "Player"));
        return name == null || name.isBlank() ? "Player" : name;
    }

    /**
     * Opens a lobby and waits for people, announcing on the local network.
     *
     * <p>The announcement runs alongside the lobby rather than being a
     * separate step: a game that has to be advertised by hand is a game
     * discovered by nobody.
     */
    private static void openHostLobby(GameData data, AssetSource assets,
            List<Path> maps, Java2DPipeline.Choice pipeline, Path map, Visibility visibility) {
        byte[] hostedMap;
        int capacity;
        try {
            hostedMap = mapBytes(assets, map);
            capacity = playerSlots(hostedMap);
        } catch (java.io.IOException | RuntimeException unreadable) {
            System.err.println("Cannot host " + map.getFileName() + ": "
                    + unreadable.getMessage());
            runMenu(data, assets, maps, pipeline);
            return;
        }
        OnlineHostScreen[] holder = new OnlineHostScreen[1];
        OnlineHostScreen status = new OnlineHostScreen(data, map.getFileName().toString(),
                new OnlineHostScreen.Listener() {
                    @Override
                    public void onRetry() {
                        connectOnlineHost(data, assets, maps, pipeline, map, hostedMap, capacity,
                                visibility, holder[0]);
                    }

                    @Override
                    public void onLocal() {
                        holder[0].cancelAttempt();
                        showDirectHostSetup(data, assets, maps, pipeline, map, hostedMap, capacity);
                    }

                    @Override
                    public void onCancel() {
                        holder[0].cancelAttempt();
                        runMenu(data, assets, maps, pipeline);
                    }
                });
        holder[0] = status;
        window().setKeyListener(null);
        window().show(status);
        connectOnlineHost(data, assets, maps, pipeline, map, hostedMap, capacity, visibility,
                status);
    }

    private static void connectOnlineHost(GameData data, AssetSource assets, List<Path> maps,
            Java2DPipeline.Choice pipeline, Path map, byte[] hostedMap, int capacity,
            Visibility visibility, OnlineHostScreen status) {
        long attempt = status.connecting();
        Thread.startVirtualThread(() -> {
            MatchmakingClient client = null;
            net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.Seat seat = null;
            RelayDatagramSocket relay = null;
            GameLobby lobby = null;
            try {
                // Class loading belongs inside the failure boundary. Packaged
                // runtimes load java.net.http lazily at this exact line.
                client = new MatchmakingClient();
                seat = client.create(new CreateGameRequest(playerName() + "'s Game",
                        map.getFileName().toString(), sha256(hostedMap), capacity,
                        visibility, MatchmakingProtocol.gameBuild()));
                SwingUtilities.invokeLater(() -> status.progress(attempt,
                        "Connecting the secure relay..."));
                relay = new RelayDatagramSocket(
                        java.net.URI.create(seat.relayUri()), seat.relayTicket(), seat.endpointId());
                SwingUtilities.invokeLater(() -> status.progress(attempt,
                        "Opening the game lobby..."));
                lobby = GameLobby.hostRelayed(playerName(),
                        map.getFileName().toString(), hostedMap, capacity, relay);
                OnlineLobby online = new OnlineLobby(client, seat, relay);
                GameLobby openedLobby = lobby;
                SwingUtilities.invokeLater(() -> {
                    if (!status.complete(attempt)) {
                        closeAbandonedOnlineLobby(openedLobby, online);
                        return;
                    }
                    try {
                        showLobby(data, assets, maps, pipeline, openedLobby, map, null, online);
                    } catch (RuntimeException | LinkageError transitionFailure) {
                        transitionFailure.printStackTrace(System.err);
                        closeAbandonedOnlineLobby(openedLobby, online);
                        window().setKeyListener(null);
                        window().show(status);
                        status.failed(attempt, onlineFailure(transitionFailure));
                    }
                });
            } catch (Exception | LinkageError unavailable) {
                unavailable.printStackTrace(System.err);
                if (lobby != null) {
                    lobby.close();
                } else if (relay != null) {
                    relay.close();
                }
                if (client != null && seat != null && seat.hostToken() != null) {
                    try {
                        client.close(seat.game().code(), seat.hostToken());
                    } catch (Exception ignored) {
                        // The unconnected allocation also expires after host silence.
                    }
                }
                String message = onlineFailure(unavailable);
                SwingUtilities.invokeLater(() -> status.failed(attempt, message));
            }
        });
    }

    /** Closes every layer of a room that finished after its screen was cancelled or expired. */
    private static void closeAbandonedOnlineLobby(GameLobby lobby, OnlineLobby online) {
        try {
            online.close();
        } finally {
            lobby.close();
        }
    }

    /** A player-facing message with enough type information to diagnose silent linkage failures. */
    private static String onlineFailure(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            message = failure.getClass().getSimpleName();
        }
        return "Could not open the online lobby: " + message;
    }

    /** Opens the explicit server-free setup before binding a direct UDP lobby. */
    private static void openDirectHostSetup(GameData data, AssetSource assets,
            List<Path> maps, Java2DPipeline.Choice pipeline, Path map) {
        byte[] hostedMap;
        int capacity;
        try {
            hostedMap = mapBytes(assets, map);
            capacity = playerSlots(hostedMap);
        } catch (java.io.IOException | RuntimeException unreadable) {
            System.err.println("Cannot host " + map.getFileName() + ": "
                    + unreadable.getMessage());
            runMenu(data, assets, maps, pipeline);
            return;
        }
        showDirectHostSetup(data, assets, maps, pipeline, map, hostedMap, capacity);
    }

    private static void showDirectHostSetup(GameData data, AssetSource assets,
            List<Path> maps, Java2DPipeline.Choice pipeline, Path map, byte[] hostedMap,
            int capacity) {
        DirectHostScreen[] holder = new DirectHostScreen[1];
        DirectHostScreen screen = new DirectHostScreen(data, map.getFileName().toString(),
                new DirectHostScreen.Listener() {
                    @Override
                    public void onHost(int port) {
                        openDirectHostLobby(data, assets, maps, pipeline, map, hostedMap, capacity,
                                port, holder[0]);
                    }

                    @Override
                    public void onCancel() {
                        runMenu(data, assets, maps, pipeline);
                    }
                });
        holder[0] = screen;
        window().setKeyListener(null);
        window().show(screen);
        screen.requestFocusInWindow();
    }

    /** Binds the chosen UDP port and advertises the same direct lobby on the LAN. */
    private static void openDirectHostLobby(GameData data, AssetSource assets,
            List<Path> maps, Java2DPipeline.Choice pipeline, Path map, byte[] hostedMap,
            int capacity, int port, DirectHostScreen status) {
        GameLobby lobby;
        try {
            lobby = GameLobby.host(playerName(), map.getFileName().toString(), hostedMap,
                    capacity, port);
        } catch (java.io.IOException e) {
            status.showError("UDP " + port + " is unavailable. Close the app using it or choose"
                    + " another port.");
            return;
        }
        GameDiscovery announcer;
        try {
            announcer = new GameDiscovery(false);
        } catch (java.io.IOException e) {
            // Discovery is a convenience over typing an address, so failing to
            // announce is not a reason to refuse to host.
            announcer = null;
            System.err.println("Cannot announce the game: " + e.getMessage());
        }
        showLobby(data, assets, maps, pipeline, lobby, map, announcer, null);
    }

    /**
     * How many players a map is for.
     *
     * <p>Read from the map rather than assumed, because opening eight slots on
     * a map with four starting positions produces four players with nowhere to
     * begin -- and they find out after the game has started.
     */
    private static int playerSlots(byte[] map) {
        PudMap source = PudReader.read(map);
        return Math.max(2, Math.min(8, source.playableSlots()));
    }

    /** The screen that goes looking for a game to join. */
    private static void openJoinScreen(GameData data, AssetSource assets,
            List<Path> maps, Java2DPipeline.Choice pipeline, boolean directOnly) {
        GameDiscovery discovery;
        try {
            discovery = new GameDiscovery(true);
        } catch (java.io.IOException e) {
            discovery = null;
            System.err.println("Cannot listen for games: " + e.getMessage()
                    + " -- an address can still be typed.");
        }
        final GameDiscovery listening = discovery;
        MatchmakingClient matchmaking = directOnly ? null : new MatchmakingClient();
        JoinScreen[] holder = new JoinScreen[1];
        JoinScreen screen = new JoinScreen(data, listening, matchmaking, directOnly,
                new JoinScreen.Listener() {
                    @Override
                    public void onJoinOnline(String code) {
                        screenJoinOnline(code, data, assets, maps, pipeline, matchmaking, listening,
                                holder[0]);
                    }

                    @Override
                    public void onJoin(String host, int port) {
                        holder[0].showNotice("Contacting "
                                + new DirectAddress(host, port).display() + "...");
                        Thread.startVirtualThread(() -> {
                            try {
                                GameLobby lobby = GameLobby.join(playerName(),
                                        java.net.InetAddress.getByName(host), port,
                                        mapProvider(assets, maps));
                                if (listening != null) {
                                    listening.close();
                                }
                                SwingUtilities.invokeLater(() -> showLobby(data, assets, maps,
                                        pipeline, lobby, null, null, null));
                            } catch (java.io.IOException e) {
                                SwingUtilities.invokeLater(() -> holder[0].showNotice(
                                        "Could not reach that direct game on UDP " + port + "."));
                            }
                        });
                    }

                    @Override
                    public void onCancel() {
                        if (listening != null) {
                            listening.close();
                        }
                        runMenu(data, assets, maps, pipeline);
                    }

                    @Override
                    public void onUpdateRequired() {
                        if (listening != null) {
                            listening.close();
                        }
                        window().frame().dispose();
                        System.exit(0);
                    }
                });
        holder[0] = screen;
        window().setKeyListener(null);
        window().show(screen);
        drive(screen::tick, screen);
    }

    private static void screenJoinOnline(String code, GameData data, AssetSource assets,
            List<Path> maps, Java2DPipeline.Choice pipeline, MatchmakingClient client,
            GameDiscovery discovery, JoinScreen screen) {
        screen.showNotice("Joining " + code + "...");
        Thread.startVirtualThread(() -> {
            try {
                var seat = client.join(code,
                        new JoinGameRequest(playerName(), MatchmakingProtocol.gameBuild()));
                RelayDatagramSocket relay = new RelayDatagramSocket(
                        java.net.URI.create(seat.relayUri()), seat.relayTicket(), seat.endpointId());
                GameLobby lobby = GameLobby.joinRelayed(playerName(), relay,
                        seat.hostEndpointId(), mapProvider(assets, maps));
                if (discovery != null) {
                    discovery.close();
                }
                OnlineLobby online = new OnlineLobby(client, seat, relay);
                SwingUtilities.invokeLater(() -> showLobby(data, assets, maps, pipeline, lobby,
                        null, null, online));
            } catch (Exception failure) {
                SwingUtilities.invokeLater(() -> {
                    if (failure instanceof net.chonkbase.chonkcraft.matchmaking.MatchmakingException
                            matchmakingFailure
                            && "version_mismatch".equals(matchmakingFailure.code())) {
                        screen.showUpdateRequired(failure.getMessage());
                    } else {
                        screen.showNotice(failure.getMessage());
                    }
                });
            }
        });
    }

    private static GameLobby.MapProvider mapProvider(AssetSource assets, List<Path> maps) {
        return name -> {
            Path local = findMap(maps, name);
            return local == null ? null : mapBytes(assets, local);
        };
    }

    /** Puts the lobby up and keeps it talking until it starts or is left. */
    private static void showLobby(GameData data, AssetSource assets, List<Path> maps,
            Java2DPipeline.Choice pipeline, GameLobby lobby, Path map,
            GameDiscovery announcer, OnlineLobby online) {
        LobbyScreen screen = new LobbyScreen(data, lobby,
                map == null ? null : map.getFileName().toString(),
                online,
                new LobbyScreen.Listener() {
                    @Override
                    public void onStart(GameLobby settled) {
                        if (announcer != null) {
                            announcer.close();
                        }
                        Path playing = map != null ? map : findMap(maps, settled.state().map());
                        if (playing == null) {
                            playing = receivedMapName(settled.state().map());
                        }
                        Path selectedMap = playing;
                        new Thread(() -> startMultiplayer(data, assets, pipeline,
                                new LobbySetup(selectedMap, settled)), "chonkcraft-load").start();
                    }

                    @Override
                    public void onCancel() {
                        if (announcer != null) {
                            announcer.close();
                        }
                        runMenu(data, assets, maps, pipeline);
                    }

                    @Override
                    public void onUpdateRequired() {
                        if (announcer != null) {
                            announcer.close();
                        }
                        window().frame().dispose();
                        System.exit(0);
                    }
                });
        window().setKeyListener(null);
        window().show(screen);
        drive(() -> {
            if (announcer != null && lobby.isHost()) {
                GameLobby.State state = lobby.state();
                announcer.announce(playerName(), state.map(), lobby.humanCount(),
                        lobby.capacity(), lobby.connectionPort());
            }
            screen.tick();
        }, screen);
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("This Java runtime has no SHA-256", impossible);
        }
    }

    /** A safe local identity for a map that exists only in the lobby's verified bytes. */
    private static Path receivedMapName(String announced) {
        try {
            Path name = Paths.get(announced == null ? "" : announced).getFileName();
            return name == null || name.toString().isBlank() ? Paths.get("multiplayer.pud") : name;
        } catch (RuntimeException malformed) {
            return Paths.get("multiplayer.pud");
        }
    }

    /**
     * The joiner's copy of the map, found by name.
     *
     * <p>By name and not by path: the host's copy is on the host's disk. Two
     * players with the same map in different folders should still be able to
     * play. This is only the local candidate: the lobby compares its SHA-256
     * with the host's and transfers the host's bytes when they differ, so a
     * matching filename can no longer hide a different map until cycle one.
     */
    private static Path findMap(List<Path> maps, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (Path candidate : maps) {
            if (candidate.getFileName().toString().equalsIgnoreCase(name)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Runs something every frame until the screen it belongs to is put away.
     *
     * <p>A timer rather than a thread, so the work happens on the thread that
     * owns the components it touches.
     */
    private static void drive(Runnable step, javax.swing.JComponent screen) {
        javax.swing.Timer timer = new javax.swing.Timer(50, null);
        timer.addActionListener(event -> {
            if (window().frame().getContentPane() != screen) {
                timer.stop();
                return;
            }
            step.run();
        });
        timer.start();
        screen.requestFocusInWindow();
    }

    /**
     * Starts a networked game.
     *
     * <p>The world is built first and the lockstep driver wrapped round it,
     * because both machines have to reach the same world before a single cycle
     * runs: the same map, the same roster in the same order, and the same view
     * of who is playing. A disagreement about any of those is a desync on the
     * first cycle that looks like a network fault.
     */
    private static void startMultiplayer(GameData data, AssetSource assets,
            Java2DPipeline.Choice pipeline, LobbySetup lobby) {
        PudMap source;
        try {
            source = PudReader.read(lobby.mapBytes(assets));
        } catch (java.io.IOException e) {
            System.err.println("Cannot read " + lobby.map() + ": " + e.getMessage());
            return;
        }
        World world = new World(GameMap.from(source, data.loadTileset(source.tileset()).tileset()),
                lobby.players(source));
        data.configureWorld(world, source);
        data.populate(world, source);
        world.recalculateSupply();

        LobbySetup.Started started;
        try {
            started = lobby.start(data, world);
        } catch (java.io.IOException e) {
            System.err.println("Cannot open the network socket: " + e.getMessage());
            return;
        }
        System.out.printf("multiplayer: slot %d, map %s%n",
                lobby.localPlayer(), lobby.map().getFileName());

        start(data, assets, pipeline, null, lobby.map(), null, 0,
                started.game(), world, lobby.localPlayer());
    }

    /**
     * Opens a saved game.
     *
     * <p>Two passes, because the save cannot rebuild its own map: it says
     * which one, and only this side knows whether that means the archive or
     * the disk. The world is built first and the script then replays the state
     * onto it.
     */
    private static void resume(GameData data, AssetSource assets,
            Java2DPipeline.Choice pipeline, Path file) {
        String script;
        try {
            script = LoadGame.read(file);
        } catch (java.io.IOException e) {
            System.err.println("Cannot read " + file + ": " + e.getMessage());
            return;
        }
        LoadGame.Header header = LoadGame.header(script);
        if (header == null || header.mapPath() == null) {
            System.err.println(file + " is not a saved game");
            return;
        }

        Mission mission = null;
        Path mapFile = null;
        if (header.campaign() != null) {
            campaignLabel = header.campaign() + " mission " + header.mission();
            mission = data.loadMission(header.mapPath());
            if (mission == null) {
                System.err.println("The map this save names is missing: " + header.mapPath());
                return;
            }
            // The mission script has just installed every trigger the map
            // declares, armed. Some of them had already fired when the game
            // was saved, and a one-shot trigger that fires twice can win or
            // lose the same mission on a condition it has already spent, so
            // the save's list of survivors is put back before anything else
            // runs.
            mission.triggers().restoreState(LoadGame.triggerState(script));
            // The mission script populated the world from the map; the save is
            // about to say where everything actually stands, so clear it.
            clearUnits(mission.world());
            LoadGame.apply(mission.world(), script, data.unitTypes().types());
            start(data, assets, pipeline, mission, null,
                    header.campaign(), header.mission());
            return;
        }

        mapFile = savedMap(assets, header.mapPath());
        if (mapFile == null) {
            System.err.println("The map this save names is missing: " + header.mapPath());
            return;
        }
        // A skirmish save carries its own units too, so the world starts bare
        // and the script fills it.
        savedScript = script;
        start(data, assets, pipeline, null, mapFile, null, 0);
        savedScript = null;
    }

    /**
     * The map a skirmish save names, from the source or from the disk.
     *
     * <p>Saves written before this wrote {@code mapFile.toAbsolutePath()}, and
     * a map served out of a pack has no absolute path to write: the entry is
     * inside the pack file and there is nothing on the disk to point at. Every
     * skirmish save ever written would have stopped loading the day the data
     * moved into a pack, with "the map this save names is missing" and a full,
     * correct-looking path underneath it, because {@code Files.isRegularFile}
     * was the only question asked.
     *
     * <p>So the recorded string is tried as a name the source knows first and
     * as a path second. New saves record the name and load anywhere; old ones
     * record a path, miss the first test, and load off the disk as they always
     * did. Neither needs the save format to say which kind it is, which is why
     * there is no version to bump: the two spellings cannot be confused, since
     * no source names a map by an absolute path.
     */
    static Path savedMap(AssetSource assets, String recorded) {
        if (recorded == null || recorded.isBlank()) {
            return null;
        }
        String name = sourceMapName(assets, recorded);
        if (name != null) {
            return Paths.get(name);
        }
        Path file = Paths.get(recorded);
        return Files.isRegularFile(file) ? file : null;
    }

    /**
     * The source's own spelling of a map name, or null when it has no such map.
     *
     * <p>The source's spelling rather than the caller's, because a map asked
     * for as {@code alamo.pud} has to come back as {@code ALAMO.PUD}: the name
     * is what a save records and what the lobby sends the other player, and two
     * spellings of one map read as two maps.
     */
    private static String sourceMapName(AssetSource assets, String wanted) {
        for (String candidate : assets.mapNames()) {
            if (candidate.equalsIgnoreCase(wanted)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The bytes of a chosen map.
     *
     * <p>Named maps come from the source. An absolute path is what an old
     * skirmish save carries, and it is read from the disk, which is what this
     * did for every map before packs existed.
     */
    static byte[] mapBytes(AssetSource assets, Path map) throws java.io.IOException {
        byte[] bytes = assets.map(map.toString());
        return bytes != null ? bytes : Files.readAllBytes(map);
    }

    /**
     * A save waiting to be replayed onto the world start() is about to build.
     *
     * <p>Passed this way rather than as a parameter because start() has one
     * job, and threading a save through the whole skirmish path for the one
     * case that uses it would obscure it.
     */
    private static String savedScript;

    /** Empties a world of units so a save can say what is really there. */
    private static void clearUnits(World world) {
        for (var unit : new java.util.ArrayList<>(world.units())) {
            world.remove(unit);
        }
    }

    /**
     * Runs one mission of a campaign: briefing, then the game.
     *
     * <p>The briefing is shown in the same window rather than printed, because
     * it is how the game tells the player what winning means. Its prose is the
     * same sentence the mission's victory condition checks, since neither was
     * retyped.
     */
    private static void startCampaignMission(GameData data, AssetSource assets,
            Java2DPipeline.Choice pipeline, String campaignName, int number) {
        Campaign campaign = null;
        for (Campaign candidate : data.campaigns()) {
            if (candidate.name().equals(campaignName)) {
                campaign = candidate;
            }
        }
        if (campaign == null || number < 1 || number > campaign.missions().size()) {
            return;
        }
        campaignLabel = campaignName + " mission " + number;
        String race = campaignName.startsWith("orc") ? "orc" : "human";
        String path = campaign.missions().get(number - 1).mapArchivePath();

        Mission mission = data.loadMission(path);
        if (mission == null) {
            return;
        }

        // A campaign leaves the menu before its act card, movie and briefing,
        // not only when the map eventually finishes loading. In particular,
        // the menu's recorded theme owns a separate PCM device; leaving that
        // owner alive lets it play underneath every intervening screen and no
        // later in-game source switch or volume slider can reach it.
        closeFrontEndAudio();

        // Whatever introduces this mission plays first, in the order the
        // campaign declares it: act four of the human campaign is a title
        // card and then a cutscene before a shot is fired.
        //
        // Both kinds, not only the videos. The title cards were being dropped
        // here, which is why the first four missions of every campaign opened
        // on nothing: they have no cutscene, only the card announcing the act,
        // and it was read out of the campaign, resolved, and thrown away.
        java.util.List<net.chonkbase.chonkcraft.engine.campaign.CampaignStep> intro =
                new java.util.ArrayList<>();
        boolean anyVideo = false;
        for (var step : campaign.introducing(number)) {
            switch (step.kind()) {
                case VIDEO -> {
                    if (data.video(step.path()) != null) {
                        intro.add(step);
                        anyVideo = true;
                    }
                }
                case PICTURE -> {
                    if (data.image(step.path()) != null) {
                        intro.add(step);
                    }
                }
                default -> { }
            }
        }
        String heading = campaignTitle(campaignName) + " - Mission " + number;
        // The act cutscenes have their own soundtrack; the game's audio device
        // does not exist until the map after them is loaded.
        // Opened for a title card as well as for a cutscene. The card carries
        // a fanfare of its own -- CreatePictureStep names one -- and an act
        // announced in silence announces nothing. And for the briefing that
        // follows: every mission script names two voice-over files, and all
        // fifty-two briefings were read in silence because nothing had a device
        // open by the time the screen was up.
        boolean anySound = anyVideo || !mission.voices().isEmpty() || intro.stream().anyMatch(
                step -> step.sound() != null && !step.sound().isBlank());
        var cutsceneAudio = anySound
                ? new net.chonkbase.chonkcraft.engine.sound.GameAudio(data.sounds())
                : null;
        if (cutsceneAudio != null) {
            cutsceneAudio.start();
            applySavedVolumes(cutsceneAudio);
        }
        Runnable showBriefing = () -> {
            // The briefing theme, played once and then silence -- upstream
            // assigns an empty playlist before calling PlayMusic, so nothing
            // follows it and the narration is not talked over.
            var briefingMusic = music(data);
            if (briefingMusic != null) {
                briefingMusic.playPlaylist(briefingMusic.available(
                        net.chonkbase.chonkcraft.engine.sound.MusicPlayer
                                .briefingTracks("orc".equals(race))));
                briefingMusic.setPlaylist(java.util.List.of());
            }
            BriefingScreen briefing = new BriefingScreen(data, race, WINDOW_WIDTH, WINDOW_HEIGHT,
                    mission.background(), heading, mission.briefing(), "Continue", () -> {
                        if (cutsceneAudio != null) {
                            cutsceneAudio.close();
                        }
                        new Thread(() -> start(data, assets, pipeline, mission, null,
                                campaignName, number), "chonkcraft-load").start();
                    });
            window().setKeyListener(null);
            window().show(briefing);
            briefing.speak(cutsceneAudio, mission.voices());
        };
        playThen(data, cutsceneAudio, intro, 0, race, showBriefing);
    }

    /**
     * Plays the cutscenes in turn, then does whatever comes next.
     *
     * <p>Chained rather than looped because each one finishes on its own
     * clock, and because any of them can be skipped: the next thing has to
     * start from wherever the player left off.
     *
     * @param race whose interface an ending among these steps is drawn in
     */
    private static void playThen(GameData data,
            net.chonkbase.chonkcraft.engine.sound.GameAudio audio,
            java.util.List<net.chonkbase.chonkcraft.engine.campaign.CampaignStep> steps, int at,
            String race, Runnable next) {
        if (at >= steps.size()) {
            next.run();
            return;
        }
        var playing = music(data);
        if (playing != null) {
            playing.silence();
        }
        var step = steps.get(at);
        Runnable after = () -> playThen(data, audio, steps, at + 1, race, next);
        window().setKeyListener(null);
        if (step.kind() == net.chonkbase.chonkcraft.engine.campaign.CampaignStep.Kind.VICTORY) {
            showEnding(data, audio, step, race, after);
            return;
        }
        if (step.kind() == net.chonkbase.chonkcraft.engine.campaign.CampaignStep.Kind.PICTURE) {
            SplashScreen shown = actCard(data, audio, step, after);
            if (shown == null) {
                after.run();
                return;
            }
            window().show(shown);
            shown.begin();
            return;
        }
        var video = data.video(step.path());
        if (video == null) {
            after.run();
            return;
        }
        VideoScreen screen = new VideoScreen(video, WINDOW_WIDTH, WINDOW_HEIGHT, after, audio);
        window().show(screen);
        screen.play();
    }

    /**
     * The card that announces an act: the map, the words on it, the fanfare.
     *
     * <p>{@code CreatePictureStep} in {@code scripts/menus/campaign.legacy-declaration:160-164}
     * carries all three and this drew only the picture: a parchment map with no
     * words on it and no sound is indistinguishable from something that failed
     * to load, which is exactly what it was taken for.
     *
     * <p>The fanfare is handed to the card rather than started here. Started
     * here it was started and forgotten -- {@code playMusicClip} hands back a
     * voice so the sound can be stopped again and this threw it away -- so a
     * player who clicked past the card, which is what a player does with a
     * still picture, carried the brass into the cutscene or the briefing behind
     * it. The card runs six seconds and the fanfare five, which is why letting
     * it time out never showed the fault.
     *
     * <p>Named and package-private so a test can put a real act card up and
     * pass it, rather than reaching into the chain that shows one.
     *
     * @return the card, or null when this installation has no such picture
     */
    static SplashScreen actCard(GameData data,
            net.chonkbase.chonkcraft.engine.sound.GameAudio audio,
            net.chonkbase.chonkcraft.engine.campaign.CampaignStep step, Runnable after) {
        var picture = data.image(step.path());
        var palette = picture == null ? null : data.paletteFor(step.path());
        if (picture == null || palette == null) {
            return null;
        }
        java.awt.image.BufferedImage card = withTitle(data,
                picture.toBufferedImage(palette), step.title(), step.subtitle());
        net.chonkbase.runtime.audio.PcmClip fanfare =
                step.sound() == null || step.sound().isBlank()
                        ? null
                        : data.sounds().clip(step.sound());
        // Long enough to read the act's name and short enough not to be in the
        // way. A click or a key passes it, as the original does.
        return new SplashScreen(card, false, ACT_CARD_SECONDS,
                WINDOW_WIDTH, WINDOW_HEIGHT, after, audio, fanfare);
    }

    /** How long an act's title card stays up before the game moves on. */
    private static final int ACT_CARD_SECONDS = 6;

    /**
     * The act's name and place, lettered over the card.
     *
     * <p>Drawn into the picture rather than over the screen, so the words
     * scale with the map they belong to instead of floating in front of it at
     * whatever size the window happens to be.
     *
     * <p>Low on the card and centred, in the game's own inks, with a dark
     * plate behind them: the parchment is pale and busy, and yellow lettering
     * laid straight on it is hard to read exactly where the map is most
     * detailed.
     */
    private static java.awt.image.BufferedImage withTitle(GameData data,
            java.awt.image.BufferedImage picture, String title, String subtitle) {
        boolean hasTitle = title != null && !title.isBlank();
        boolean hasSubtitle = subtitle != null && !subtitle.isBlank();
        if (!hasTitle && !hasSubtitle) {
            return picture;
        }
        java.awt.image.BufferedImage card = new java.awt.image.BufferedImage(
                picture.getWidth(), picture.getHeight(),
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g2 = card.createGraphics();
        g2.drawImage(picture, 0, 0, null);

        GameFont large = GameFont.load(data, GameFont.Face.LARGE);
        GameFont body = GameFont.load(data, GameFont.Face.GAME);
        int centre = card.getWidth() / 2;
        int height = (hasTitle ? large.height() : 0) + (hasSubtitle ? body.height() + 6 : 0);
        int top = card.getHeight() - height - 40;

        int widest = 0;
        if (hasTitle) {
            widest = large.widthOf(title);
        }
        if (hasSubtitle) {
            widest = Math.max(widest, body.widthOf(subtitle));
        }
        g2.setColor(new java.awt.Color(0, 0, 0, 150));
        g2.fillRect(centre - widest / 2 - 24, top - 12, widest + 48, height + 24);

        int y = top;
        if (hasTitle) {
            large.drawCentred(g2, title, centre, y, GameFont.Ink.YELLOW);
            y += large.height() + 6;
        }
        if (hasSubtitle) {
            body.drawCentred(g2, subtitle, centre, y, GameFont.Ink.WHITE);
        }
        g2.dispose();
        return card;
    }

    /**
     * The campaign's ending: the picture, the closing narration, the voices.
     *
     * <p>{@code CreateVictoryStep} names all three and nothing had ever asked
     * for any of them. Fourteen missions ended on a line of text this implementation
     * wrote itself -- "You have completed the Human Campaign." -- while the
     * page Blizzard drew, the paragraph they wrote and the recording they made
     * sat in the archive, resolved and unread.
     *
     * <p>Shown on the briefing screen because that is what it is: upstream's
     * {@code CreateVictoryStep} calls {@code Briefing} with no title and no
     * objectives, which is this screen with its heading left off.
     */
    private static void showEnding(GameData data,
            net.chonkbase.chonkcraft.engine.sound.GameAudio audio,
            net.chonkbase.chonkcraft.engine.campaign.CampaignStep step, String race,
            Runnable after) {
        String prose = data.briefingText(step.textPath());
        BriefingScreen ending = new BriefingScreen(data, race, WINDOW_WIDTH, WINDOW_HEIGHT,
                step.path(), "", prose, "Continue", after);
        window().setKeyListener(null);
        window().setTitle("chonkcraft");
        window().show(ending);
        ending.speak(audio, step.voices());
    }

    /**
     * Shows the result of a mission and offers the next one.
     *
     * <p>Winning moves the campaign on; losing offers the same mission again.
     * That is the loop Warcraft II runs on, and without it a campaign is a
     * list of missions rather than a campaign.
     */
    private static void showResult(GameData data, AssetSource assets,
            Java2DPipeline.Choice pipeline, String campaignName, int number,
            boolean won) {
        Campaign campaign = null;
        for (Campaign candidate : data.campaigns()) {
            if (candidate.name().equals(campaignName)) {
                campaign = candidate;
            }
        }
        if (campaign == null) {
            return;
        }
        int total = campaign.missions().size();
        boolean finished = won && number >= total;
        int next = won ? number + 1 : number;
        String race = campaignName.startsWith("orc") ? "orc" : "human";

        // Victory and defeat each have their own theme per race, from
        // scripts/menus/results.legacy-declaration. The end screen used to be silent.
        var resultMusic = music(data);
        if (resultMusic != null) {
            resultMusic.playPlaylist(resultMusic.available(
                    net.chonkbase.chonkcraft.engine.sound.MusicPlayer
                            .resultTracks("orc".equals(race), won)));
            resultMusic.setPlaylist(java.util.List.of());
        }

        // The last mission is not the end of the campaign. What follows it is
        // in the script -- a closing cutscene and an ending with its own
        // picture, prose and narration -- and it is reached by asking for the
        // steps after the final map, which is a question nothing used to ask.
        if (finished) {
            playEnding(data, assets, pipeline, campaign, race);
            return;
        }

        String caption = won ? "Next Mission" : "Try Again";
        Campaign chosen = campaign;
        Runnable onward = () -> startCampaignMission(data, assets, pipeline,
                chosen.name(), next);

        Result result = lastResult;
        lastResult = null;
        if (result != null) {
            // The rank is read off the score with the victory bonus added, as
            // results.legacy-declaration:64 does before it looks the tier up.
            int score = result.score() + (won ? ResultsScreen.VICTORY_BONUS : 0);
            ResultsScreen.Outcome shown = switch (result.outcome()) {
                case VICTORY -> ResultsScreen.Outcome.VICTORY;
                case DEFEAT -> ResultsScreen.Outcome.DEFEAT;
                default -> ResultsScreen.Outcome.DRAW;
            };
            ResultsScreen screen = new ResultsScreen(data, race, WINDOW_WIDTH, WINDOW_HEIGHT,
                    shown, score, result.rows(), caption, onward);
            window().setKeyListener(null);
            window().setTitle("chonkcraft");
            window().show(screen);
            return;
        }

        // Nothing was lifted -- a mission ended by something other than the
        // trigger system, which is how a debug launch finishes. The old
        // sentence is better than a blank screen.
        String heading = won ? "Mission Accomplished" : "Mission Failed";
        String body = won
                ? "Mission " + number + " is complete. Mission " + next + " of " + total
                        + " awaits."
                : "Mission " + number + " was lost. It can be attempted again.";
        BriefingScreen fallback = new BriefingScreen(data, race, WINDOW_WIDTH, WINDOW_HEIGHT,
                null, heading, body, caption, onward);
        window().setKeyListener(null);
        window().setTitle("chonkcraft");
        window().show(fallback);
    }

    /**
     * Runs everything the campaign has left, then returns to the menu.
     *
     * <p>A device is opened for it for the same reason the act cards need one:
     * the game's own is closed by the time the last mission has been given up,
     * and the ending is a recording read over a picture.
     */
    private static void playEnding(GameData data, AssetSource assets,
            Java2DPipeline.Choice pipeline, Campaign campaign, String race) {
        var closing = campaign.ending();
        var audio = new net.chonkbase.chonkcraft.engine.sound.GameAudio(data.sounds());
        audio.start();
        applySavedVolumes(audio);
        playThen(data, audio, closing, 0, race, () -> {
            audio.close();
            SwingUtilities.invokeLater(() ->
                    runMenu(data, assets, findMaps(assets), pipeline));
        });
    }

    private static String campaignTitle(String name) {
        return switch (name) {
            case "human" -> "Human Campaign";
            case "orc" -> "Orc Campaign";
            case "human-exp" -> "Human Expansion";
            case "orc-exp" -> "Orc Expansion";
            default -> name;
        };
    }

    /**
     * The newest file in the save directory, or null when there is none.
     *
     * <p>What F12 loads. Warcraft II offers a list; this takes the last one
     * written, which is what a player pressing load in a hurry means.
     */
    private static Path mostRecentSave() {
        Path directory = GameScreen.saveDirectory();
        if (!java.nio.file.Files.isDirectory(directory)) {
            return null;
        }
        try (var entries = java.nio.file.Files.list(directory)) {
            return entries
                    .filter(path -> path.toString().endsWith(
                            net.chonkbase.chonkcraft.engine.save.SaveGame.SUFFIX))
                    .max(java.util.Comparator.comparingLong(path -> {
                        try {
                            return java.nio.file.Files.getLastModifiedTime(path).toMillis();
                        } catch (java.io.IOException e) {
                            return 0L;
                        }
                    }))
                    .orElse(null);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /**
     * The hook that gives up this game's sound if the window is simply closed.
     *
     * <p>One, and the previous one is taken off first. Loading a save tears the
     * game down and starts another, and each start used to add three hooks that
     * held the audio device, the disc and the sequencer of a game that no
     * longer existed. Five loads left fifteen.
     */
    private static Thread shutdownHook;

    /** Audio retained across the title sequence and every menu screen. */
    private record FrontEndAudio(
            net.chonkbase.chonkcraft.engine.sound.GameAudio audio,
            net.chonkbase.chonkcraft.engine.sound.SoundServer server) {}

    private static FrontEndAudio frontEndAudio;

    private static synchronized FrontEndAudio frontEndAudio(GameData data, AssetSource assets) {
        if (frontEndAudio != null) {
            return frontEndAudio;
        }
        var audio = new net.chonkbase.chonkcraft.engine.sound.GameAudio(data.sounds());
        audio.start();
        var preferred = audio.isAvailable()
                ? settings().musicBackend()
                : net.chonkbase.chonkcraft.engine.sound.SoundServer.Backend.XMI;
        var server = new net.chonkbase.chonkcraft.engine.sound.SoundServer(
                audio.mixer(),
                new net.chonkbase.chonkcraft.engine.sound.CdMusic(assets, audio.mixer()),
                data.music(), preferred);
        server.setEffectVolume(settings().effectVolume());
        server.setMusicVolume(settings().musicVolume());
        frontEndAudio = new FrontEndAudio(audio, server);
        registerShutdown(Main::closeFrontEndAudio);
        return frontEndAudio;
    }

    private static synchronized void closeFrontEndAudio() {
        if (frontEndAudio == null) {
            return;
        }
        frontEndAudio.server().close();
        frontEndAudio.audio().close();
        frontEndAudio = null;
    }

    private static synchronized void registerShutdown(Runnable close) {
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException alreadyShuttingDown) {
                // The JVM is on its way out and will run what is registered.
                return;
            }
        }
        shutdownHook = new Thread(close, "chonkcraft-audio-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    /** Loads a game and hands it to the window. */
    private static void start(GameData data, AssetSource assets,
            Java2DPipeline.Choice pipeline, Mission mission, Path mapFileOrNull) {
        start(data, assets, pipeline, mission, mapFileOrNull, null, 0, null);
    }

    private static void start(GameData data, AssetSource assets,
            Java2DPipeline.Choice pipeline, Mission mission, Path mapFileOrNull,
            String campaignName, int missionNumber) {
        start(data, assets, pipeline, mission, mapFileOrNull, campaignName, missionNumber, null);
    }

    /**
     * @param network the lockstep driver when this is a multiplayer game, or
     *                null when nobody else is playing
     */
    private static void start(GameData data, AssetSource assets,
            Java2DPipeline.Choice pipeline, Mission mission, Path mapFileOrNull,
            String campaignName, int missionNumber,
            net.chonkbase.chonkcraft.engine.network.NetworkGame network) {
        start(data, assets, pipeline, mission, mapFileOrNull, campaignName,
                missionNumber, network, null, -1);
    }

    /**
     * Loads a game with an optional world already settled by a network lobby.
     *
     * <p>The prepared world and local slot travel as arguments because startup
     * hands the finished screen to Swing asynchronously. Keeping either in a
     * mutable static lets a later load replace or inherit another game's slot;
     * a joiner that inherited an unused slot owned no units, had no sight, and
     * saw a completely black battlefield.
     */
    private static void start(GameData data, AssetSource assets,
            Java2DPipeline.Choice pipeline, Mission mission, Path mapFileOrNull,
            String campaignName, int missionNumber,
            net.chonkbase.chonkcraft.engine.network.NetworkGame network,
            World preparedNetworkWorld, int preparedLocalPlayer) {
        closeFrontEndAudio();
        Path mapFile = mapFileOrNull;
        PudMap source;
        World world;
        int placed;
        BattleShowcase.Result showcase = null;
        if (mission != null) {
            source = mission.source();
            world = mission.world();
            placed = world.units().size();
        } else {
            try {
                source = PudReader.read(mapBytes(assets, mapFile));
            } catch (java.io.IOException e) {
                System.err.println("Cannot read " + mapFile + ": " + e.getMessage());
                System.exit(2);
                return;
            }
            if (preparedNetworkWorld != null) {
                // Already built, and already wrapped in the lockstep driver.
                // Building a second one here would leave the driver advancing
                // a world nobody was looking at.
                world = preparedNetworkWorld;
                placed = world.units().size();
            } else if (savedScript != null) {
                world = new World(
                        GameMap.from(source, data.loadTileset(source.tileset()).tileset()),
                        net.chonkbase.chonkcraft.engine.Player.from(source));
                data.configureWorld(world, source);
                // The save says what is on the map; the map's own placements
                // are a starting position this game has long since left.
                LoadGame.apply(world, savedScript, data.unitTypes().types());
                placed = world.units().size();
            } else {
                // The other people on the map become computers, because
                // there is only one person here. Every skirmish map is drawn
                // for two or more, so without this all twenty-eight of the
                // ones this data ships opened as an empty field: the enemy
                // town stood where the map put it, gathered nothing, built
                // nothing and never came. CPlayer::Init has said so all
                // along -- "Take first slot for person on this computer,
                // fill other with computer players".
                world = new World(
                        GameMap.from(source, data.loadTileset(source.tileset()).tileset()),
                        net.chonkbase.chonkcraft.engine.Player.forSoloGame(source));
                data.configureWorld(world, source);
                placed = data.populate(world, source);
            }
        }

        if (BattleShowcase.requested() && mission == null
                && network == null && savedScript == null) {
            showcase = BattleShowcase.deploy(
                    world, data.unitTypes().types(), BattleShowcase.requestedUnits());
            placed = showcase.deployed();
        }

        GameData.LoadedTileset tileset = data.loadTileset(source.tileset());
        GameMap map = world.map();

        // Terrain does not change, so rasterise it once.
        IndexedImage scene = new MapRenderer(tileset.tileset(), tileset.sheet())
                .render(map.width(), map.height(), map.tileCodes());
        // Indexed rather than true colour, so the palette can walk without the
        // three-thousand-pixel-square map being redrawn.
        BufferedImage terrain = scene.toIndexedBufferedImage(tileset.palette());

        world.recalculateSupply();
        int computers = showcase == null ? world.enableAiForComputerPlayers() : 0;
        // Retail computer behavior comes from main archive entry 277 and the
        // PUD's per-slot AIPL profile byte. Campaign labels are retained only
        // as diagnostics; no community personality is executed or overrideable.
        List<net.chonkbase.chonkcraft.engine.ai.AiAssignment> aiSlots;
        if (showcase != null) {
            aiSlots = java.util.List.of();
        } else if (mission != null) {
            // Already settled while the mission was constructed.
            aiSlots = mission.ai();
        } else {
            aiSlots = data.attachRetailAi(world, source, java.util.Map.of());
        }
        // In a networked game the slot is the one the lobby agreed, not the
        // first the map happens to mark playable: both machines read the same
        // map and would otherwise both command the same units.
        int localPlayer = localPlayerForStart(source, preparedLocalPlayer);

        System.out.printf("Java2D pipeline: %s%n", pipeline);
        System.out.printf("Map %s: %dx%d tiles, %s tileset%n",
                mission != null ? missionLabel() : mapFile.getFileName(),
                map.width(), map.height(), source.tileset());
        if (mission != null) {
            System.out.printf("Mission: %d triggers, %d entries in the allow table%n",
                    mission.triggers().triggerCount(), mission.allowed().size());
            if (mission.briefing() != null) {
                System.out.println();
                System.out.println(mission.briefing().trim());
                System.out.println();
            }
        }
        long scripted = aiSlots.stream().filter(slot -> slot.attached() != null).count();
        if (showcase != null) {
            System.out.printf("Massive battle showcase: %d units (%d human, %d orc), "
                            + "camera %d,%d%n",
                    showcase.deployed(), showcase.humanUnits(), showcase.orcUnits(),
                    showcase.centreX(), showcase.centreY());
        } else {
            System.out.printf("Placed %d of %d units; commanding player %d against %d computer players "
                            + "(%d running the game's own AI script)%n",
                    placed, source.units().size(), localPlayer, computers, scripted);
        }
        if (!aiSlots.isEmpty()) {
            System.out.println("Computer players: " + aiSlots);
        }
        // Assets the game asked for and did not get. Recorded since the act
        // title cards turned out to have been resolving to nothing for
        // months, and printed here because a record nobody reads is the same
        // as no record: an image that cannot be found and a mission that has
        // no picture look identical at the call site, and this is the only
        // place that can tell them apart.
        java.util.List<String> unresolved = data.unresolvedPaths();
        if (!unresolved.isEmpty()) {
            System.out.println("Assets asked for and not found (" + unresolved.size() + "):");
            for (String path : unresolved) {
                System.out.println("  " + path);
            }
        }

        net.chonkbase.chonkcraft.engine.sound.GameAudio audio =
                new net.chonkbase.chonkcraft.engine.sound.GameAudio(data.sounds());
        audio.start();
        System.out.printf("Audio: %s%n",
                audio.isAvailable() ? "ready" : "unavailable (" + audio.unavailableReason() + ")");

        // The soundtrack exists twice: as XMI for the synthesiser and as red
        // book audio on the disc. Which of the two plays used to be one line
        // here -- the disc won whenever there was one -- and that line is also
        // where the reported bug lived: the disc branch started a red book
        // track and never touched the sequencer, so the briefing theme the
        // campaign had just started, or the menu theme a skirmish had left
        // looping, went on playing over the map with nothing able to stop it or
        // turn it down. SoundServer owns both, so starting one silences the
        // other and one slider reaches both.
        Settings settings = settings();
        // Without a sound device the recordings cannot be heard at all: they
        // are samples and the samples have nowhere to go. The sequencer has an
        // output of its own and never touches the mixer, which is the whole
        // reason the two need joining, so on a machine with no device the
        // synthesised score is the only soundtrack there is.
        var wanted = audio.isAvailable()
                ? settings.musicBackend()
                : net.chonkbase.chonkcraft.engine.sound.SoundServer.Backend.XMI;
        var server = new net.chonkbase.chonkcraft.engine.sound.SoundServer(
                audio.mixer(),
                new net.chonkbase.chonkcraft.engine.sound.CdMusic(assets, audio.mixer()),
                data.music(),
                wanted);
        server.setEffectVolume(settings.effectVolume());
        server.setMusicVolume(settings.musicVolume());
        // Unconditionally, even when nothing can start. It is the silencing
        // that matters: this is where the briefing theme or the menu theme the
        // last screen left running has to stop, and skipping the call because
        // there is no device is how it went on playing over the map.
        server.playBattleMusic(source.races()[localPlayer] == PudMap.Race.ORC);
        System.out.printf("Music: %s%n", server.describe());
        // One hook, replacing the last game's. A game is put down through
        // stopPlaying below; these are only for a player who closes the window,
        // and registering three of them per launch meant loading five saves
        // left fifteen registered.
        registerShutdown(() -> {
            server.close();
            audio.close();
        });

        String tilesetName = tilesetSpriteKey(source.tileset());
        Mission running = mission;
        String title = showcase != null
                ? "Massive Battle Showcase"
                : mission != null || mapFile == null
                    ? missionLabel()
                    : mapFile.getFileName().toString();
        // The mission is over when its outcome is decided, and the campaign
        // moves on from there. Skirmish games have nowhere to move on to.
        java.util.function.BiConsumer<JFrame, Boolean> onFinished = campaignName == null
                ? null
                : (gameFrame, won) -> showResult(data, assets, pipeline,
                        campaignName, missionNumber, won);
        // Where to find this game again. A campaign mission is an archive
        // path; a skirmish map is a name the source knows. Both go in the save
        // so loading knows which of the two it is looking at.
        // A mission started with -Dchonkcraft.campaign has no campaign name to
        // pass along and no map file either, so neither branch below had
        // anything to say and the launch ended in a null pointer. Its own map
        // path is the answer.
        //
        // A name and not the absolute path this used to write. The path was
        // where the map sat on the machine that saved, which is a fact about
        // one disk: a save carried to another machine, or made before the data
        // moved into a pack, names a file that is not there. The name is the
        // one identity a pack entry and a loose file both have. A game
        // resumed from an old save saves itself out under the new spelling,
        // because the name is looked up first and only falls back to the path.
        String savePath;
        if (campaignName != null) {
            savePath = campaignMapPathOf(data, campaignName, missionNumber);
        } else if (mapFile != null) {
            String name = sourceMapName(assets, mapFile.toString());
            if (name == null) {
                name = sourceMapName(assets, mapFile.getFileName().toString());
            }
            savePath = name != null ? name : mapFile.toAbsolutePath().toString();
        } else {
            savePath = null;
        }
        BattleShowcase.Result openingShowcase = showcase;
        SwingUtilities.invokeLater(() -> show(
                title, world, data, terrain,
                tileset.palette(), tilesetName, localPlayer, source, audio, server, settings,
                running, onFinished,
                savePath, campaignName, missionNumber, network, tileset.cyclingRanges(),
                assets, pipeline,
                FogTiles.from(tileset.sheet(), data.fogOfWar().levels()),
                openingShowcase,
                openingShowcase == null ? null
                        : new int[] {openingShowcase.centreX(), openingShowcase.centreY()}));
    }

    /**
     * The campaign mission to play, or null for a skirmish map.
     *
     * <p>Chosen with {@code -Dchonkcraft.campaign=human -Dchonkcraft.mission=1}, where
     * the mission number counts from one over the campaign's missions rather
     * than over all its steps: a player thinks in missions, not in title cards.
     */
    private static Mission resolveMission(GameData data) {
        String name = setting("chonkcraft.campaign", "CHONKCRAFT_CAMPAIGN");
        if (name == null || name.isBlank()) {
            return null;
        }
        Campaign campaign = null;
        for (Campaign candidate : data.campaigns()) {
            if (candidate.name().equalsIgnoreCase(name)) {
                campaign = candidate;
                break;
            }
        }
        if (campaign == null) {
            System.err.println("No campaign called " + name + ". Available: "
                    + data.campaigns().stream().map(Campaign::name).toList());
            System.exit(2);
        }
        int number = 1;
        String requested = setting("chonkcraft.mission", "CHONKCRAFT_MISSION");
        if (requested != null && !requested.isBlank()) {
            try {
                number = Integer.parseInt(requested.trim());
            } catch (NumberFormatException e) {
                System.err.println("Mission must be a number, not " + requested);
                System.exit(2);
            }
        }
        var missions = campaign.missions();
        if (number < 1 || number > missions.size()) {
            System.err.println("Campaign " + campaign.name() + " has missions 1 to "
                    + missions.size());
            System.exit(2);
        }
        campaignLabel = campaign.name() + " mission " + number;
        String path = missions.get(number - 1).mapArchivePath();
        Mission mission = data.loadMission(path);
        if (mission == null) {
            System.err.println("Could not load " + path);
            System.exit(2);
        }
        return mission;
    }

    /**
     * What the last mission came to, lifted before the world was put down.
     *
     * <p>Static for the same reason {@code savedScript} is: the screen that
     * shows it is built from a callback that runs after the game has been torn
     * down, and threading a snapshot through every overload of {@code start}
     * for the one place that reads it is worse than saying so here.
     */
    private record Result(TriggerSystem.Outcome outcome,
            java.util.List<ResultsScreen.Row> rows, int score) {}

    private static Result lastResult;

    /** Set once a campaign mission is chosen, for the window title. */
    private static String campaignLabel;

    private static String missionLabel() {
        return campaignLabel == null ? "campaign" : campaignLabel;
    }

    /** A setting from a system property, falling back to the environment. */
    static String setting(String property, String variable) {
        String value = System.getProperty(property);
        return value != null && !value.isBlank() ? value : System.getenv(variable);
    }

    /** The archive path of a campaign mission, or null. */
    private static String campaignMapPathOf(GameData data, String campaignName, int number) {
        for (Campaign campaign : data.campaigns()) {
            if (campaign.name().equals(campaignName)
                    && number >= 1 && number <= campaign.missions().size()) {
                return campaign.missions().get(number - 1).mapArchivePath();
            }
        }
        return null;
    }

    /**
     * Every map the source has, in the order the menu shows them.
     *
     * <p>Sorted by name so the menu reads the same twice running: a directory
     * walk does not promise an order, and a list that shuffles between
     * launches is the sort of thing a player notices and cannot explain. The
     * sort lives in the source now, and sorts on the same key this did, so the
     * seventy-seven maps of a 1995 installation come up in the places a player
     * already knows them by. {@code MapDiscoverySeamTest} holds the two orders
     * against each other rather than trusting that sentence.
     *
     * <p>Paths of one segment, holding a name: the menu shows and returns a
     * {@link Path}, and a map in a pack has no directory to put in front of
     * its name. {@link #mapBytes} takes them back apart.
     */
    static List<Path> findMaps(AssetSource assets) {
        List<Path> maps = new ArrayList<>();
        for (String name : assets.mapNames()) {
            maps.add(Paths.get(name));
        }
        return List.copyOf(maps);
    }

    /** The first slot a person plays, or 0. */
    private static int firstHumanPlayer(PudMap source) {
        for (int i = 0; i < source.players().length; i++) {
            if (source.players()[i] == PudMap.PlayerType.PERSON) {
                return i;
            }
        }
        return 0;
    }

    /** Keeps a lobby's player slot scoped to that one game startup. */
    static int localPlayerForStart(PudMap source, int preparedLocalPlayer) {
        return preparedLocalPlayer >= 0
                ? preparedLocalPlayer : firstHumanPlayer(source);
    }

    /**
     * The key the scripts use for a tileset's per-terrain sprites.
     *
     * <p>The forest tileset is called "summer" in the script tree, which is
     * the name Blizzard's own data used.
     */
    private static String tilesetSpriteKey(PudMap.Tileset tileset) {
        return tileset == PudMap.Tileset.FOREST
                ? "summer"
                : tileset.name().toLowerCase(Locale.ROOT);
    }

    private static void show(String title, World world, GameData data, BufferedImage terrain,
            net.chonkbase.chonkcraft.data.graphic.Palette palette, String tilesetName,
            int localPlayer, PudMap source,
            net.chonkbase.chonkcraft.engine.sound.GameAudio audio,
            net.chonkbase.chonkcraft.engine.sound.SoundServer server, Settings settings,
            Mission mission,
            java.util.function.BiConsumer<JFrame, Boolean> onFinished,
            String saveMapPath, String campaignName, int missionNumber,
            net.chonkbase.chonkcraft.engine.network.NetworkGame network,
            java.util.List<int[]> cyclingRanges,
            AssetSource assets, Java2DPipeline.Choice pipeline,
            FogTiles fogTiles, BattleShowcase.Result openingShowcase, int[] openingView) {

        AppWindow shell = window();
        shell.setTitle("chonkcraft - " + title);
        // The size the player has the window at, not the size this file wishes
        // it were. The interface is laid out for the window it is going into.
        int viewWidth = Math.max(320, shell.contentWidth());
        int viewHeight = Math.max(240, shell.contentHeight());

        // The sidebar is drawn from the race's own interface art.
        String race = source.races()[localPlayer] == PudMap.Race.ORC ? "orc" : "human";

        // Every coordinate in the sidebar comes out of the race's own layout
        // script, computed for the size of window it is about to be drawn in:
        // the map area, the status line and half the resource bar are measured
        // back from the right and bottom edges.
        //
        // In design pixels, not window pixels. The chrome is drawn through a
        // scaling transform, so what the script has to be told is the window
        // divided by that scale -- which is what GameScreen tells it from its
        // first paint onwards. Told the window instead, Video.Width comes out
        // two or three times too large and every position measured back from
        // the right edge -- the food, the score and the idle worker count --
        // lands off the end of the bar until the first frame is drawn.
        double startingScale = GameScreen.naturalScale(viewWidth, viewHeight);
        var layout = data.uiLayout(race,
                (int) Math.floor(viewWidth / startingScale),
                (int) Math.floor(viewHeight / startingScale));
        SidePanel panel = new SidePanel(world, data, localPlayer, race, tilesetName, layout);

        // The command grid is built from the game's own button definitions,
        // so it needs the interface scripts loaded for this tileset.
        CommandPanel commands = new CommandPanel(world, data, data.userInterface(tilesetName),
                mission == null ? data.upgrades().dependencies() : mission.dependencies(),
                localPlayer, tilesetName, race,
                data.unitTypes().types(), layout);

        // Every order goes through the applier, in single player as well as
        // multiplayer, so the two share one path and cannot drift apart.
        CommandApplier applier = new CommandApplier(world, new ArrayList<>(
                data.unitTypes().types().values()));
        data.configureCommands(applier);
        CommandSink sink = network != null
                ? CommandSink.networked(network)
                : CommandSink.local(applier);

        GameScreen screen = new GameScreen(world, data, terrain, palette, tilesetName,
                localPlayer, viewWidth, viewHeight, audio, panel, commands, applier, sink,
                cyclingRanges, race);
        screen.setNetworkChat(network);
        // The fog draws its edges with the tileset's own masks, and the screen
        // is handed the map already rasterised rather than the sheet they live
        // in, so they come across separately.
        screen.setFogTiles(fogTiles);
        // How dark the fog is, from SetFogOfWarOpacityLevels in the prelude.
        // The masks above are baked at the same levels; these cover the parts
        // that are filled rather than masked.
        screen.setFogOpacity(data.fogOfWar().levels());
        // The minimap veils remembered ground more lightly than the main view
        // does, and says so itself: SetMMFogOfWarOpacityLevels, two lines below
        // the other call.
        if (panel != null) {
            panel.setFogOpacity(data.fogOfWar().minimapLevels());
        }
        // Only the launcher knows where the world came from, so it is what
        // tells the screen how to write itself back out.
        screen.setLayout(layout);
        screen.setSaveContext(saveMapPath, campaignName, missionNumber);
        // And which triggers are still armed, so a save can put them back the
        // way they were rather than the way the map script installed them.
        if (mission != null) {
            screen.setTriggers(mission.triggers());
        }
        shell.show(screen);
        // The pointer has to be put back after a full-screen transition; AppKit
        // resets it and tells nobody.
        shell.onFullscreenChange(nowFullscreen -> {
            screen.refreshCursor();
            // Edge scrolling only makes sense when the edge of the game is the
            // edge of the screen.
            screen.setFullscreen(nowFullscreen);
        });
        JFrame frame = shell.frame();

        PlatformFullscreen fullscreen = shell.fullscreen();
        shell.setKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                // Alt-F for full screen, which is where the keystroke help puts
                // it. F11 belongs to saving.
                if (event.getKeyCode() == KeyEvent.VK_F && event.isAltDown()) {
                    fullscreen.toggle();
                    return;
                }
                screen.keyPressed(event);
            }

            @Override
            public void keyReleased(KeyEvent event) {
                screen.keyDown(event.getKeyCode(), false);
            }
        });
        frame.requestFocusInWindow();

        // Open looking at the local player's start position, as the game does.
        int[] start = openingView != null ? openingView : source.startLocation(localPlayer);
        if (start != null) {
            screen.centreOn(start[0], start[1]);
        }

        // -Dchonkcraft.screenshot=path writes one frame and exits. Used to
        // verify the real rendering without needing a window server, and to
        // capture what the game actually looks like.
        // Read from the environment as well as a system property: a packaged
        // app is launched by its own binary, which does not forward -D flags
        // to the JVM, so the environment is the only channel a test has.
        String shotProperty = System.getProperty("chonkcraft.screenshot");
        final String shot = shotProperty != null && !shotProperty.isBlank()
                ? shotProperty
                : System.getenv("CHONKCRAFT_SCREENSHOT");
        if (shot != null && !shot.isBlank()) {
            int afterCycles = Integer.getInteger("chonkcraft.screenshot.cycles", 60);
            // Pinned rather than left to follow the window, so a screenshot can
            // show a scale this window size would not have chosen.
            Integer pinnedGame = Integer.getInteger("chonkcraft.gamescale");
            if (pinnedGame != null) {
                screen.setGameScale(pinnedGame);
            }
            Integer pinnedUi = Integer.getInteger("chonkcraft.uiscale");
            if (pinnedUi != null) {
                screen.setInterfaceScale(pinnedUi);
            }
            new Thread(() -> {
                try {
                    for (int i = 0; i < afterCycles; i++) {
                        world.tick();
                    }
                    // -Dchonkcraft.screenshot.select=unit-peasant picks that unit
                    // before the picture is taken. Without it a screenshot can
                    // only ever show the empty panel, and the half of the
                    // interface that answers "what is selected" -- the
                    // portrait, the statistics, the command grid, the progress
                    // bar -- cannot be looked at at all.
                    String wanted = System.getProperty("chonkcraft.screenshot.select");
                    if (wanted != null && !wanted.isBlank()) {
                        screen.selectForScreenshot(wanted,
                                Integer.getInteger("chonkcraft.screenshot.select.count", 1));
                    }
                    Thread.sleep(500);
                    java.awt.image.BufferedImage shotImage = new java.awt.image.BufferedImage(
                            screen.getWidth(), screen.getHeight(),
                            java.awt.image.BufferedImage.TYPE_INT_RGB);
                    java.awt.Graphics2D g = shotImage.createGraphics();
                    // -Dchonkcraft.paint.repeats=N times the drawing before the
                    // picture is taken, so the cost of a frame can be measured
                    // rather than guessed at.
                    int repeats = Integer.getInteger("chonkcraft.paint.repeats", 0);
                    if (repeats > 0) {
                        javax.swing.SwingUtilities.invokeAndWait(() -> screen.paint(g));
                        long began = System.nanoTime();
                        for (int i = 0; i < repeats; i++) {
                            javax.swing.SwingUtilities.invokeAndWait(() -> screen.paint(g));
                        }
                        long each = (System.nanoTime() - began) / repeats / 1000;
                        System.out.println("paint " + each + " microseconds a frame");
                        // Two frames of an unchanged world must be identical.
                        // Anything else is the renderer disagreeing with
                        // itself, which on the screen is a shimmer.
                        java.awt.image.BufferedImage again =
                                new java.awt.image.BufferedImage(screen.getWidth(),
                                        screen.getHeight(),
                                        java.awt.image.BufferedImage.TYPE_INT_RGB);
                        java.awt.Graphics2D other = again.createGraphics();
                        javax.swing.SwingUtilities.invokeAndWait(() -> screen.paint(other));
                        other.dispose();
                        int differing = 0;
                        for (int y = 0; y < shotImage.getHeight(); y++) {
                            for (int x = 0; x < shotImage.getWidth(); x++) {
                                if (shotImage.getRGB(x, y) != again.getRGB(x, y)) {
                                    differing++;
                                }
                            }
                        }
                        System.out.println("two frames differ in " + differing + " pixels");
                    }
                    javax.swing.SwingUtilities.invokeAndWait(() -> screen.paint(g));
                    g.dispose();
                    javax.imageio.ImageIO.write(shotImage, "png", new java.io.File(shot));
                    System.out.println("Wrote " + shot);
                } catch (Exception e) {
                    System.err.println("Screenshot failed: " + e);
                }
                System.exit(0);
            }, "chonkcraft-screenshot").start();
            return;
        }

        // Guards the handover: the loop keeps running after the outcome is
        // decided, so without this the result screen would open every cycle.
        java.util.concurrent.atomic.AtomicBoolean finishing =
                new java.util.concurrent.atomic.AtomicBoolean();

        // The simulation runs at its own fixed rate, independent of repaint.
        FixedStepLoop loop = new FixedStepLoop("chonkcraft-sim", World.CYCLES_PER_SECOND);

        /*
         * Everything this game holds, given up in one place.
         *
         * <p>There is exactly one way out of a game that does not go through
         * here, and that is quitting the application. Every other exit --
         * winning, losing, giving up, loading another save -- has to put down
         * the simulation thread, the sound device and the socket, and putting
         * them down in one named place is what stops the next exit forgetting
         * one of them. Leaving the loop running left a battle being fought
         * behind the main menu, audible and invisible; leaving the sound
         * device open left the sounds it had already queued playing over
         * whatever came next.
         */
        Runnable stopPlaying = () -> {
            loop.close();
            // The soundtrack before the device, and both of them here rather
            // than only the device. The disc was a local of the loader and was
            // put down by nothing but a shutdown hook: ending a scenario closed
            // the device underneath a track that was still playing and left the
            // player holding a voice that no longer existed.
            server.close();
            audio.close();
            if (network != null) {
                network.close();
            }
        };

        // Loudness is kept in the sound server rather than read back from the
        // mixer, because a gain in decibels does not convert back to a slider
        // position without rounding the ends off. It is written to the settings
        // file as it changes, so the next game starts where this one was left:
        // both volumes used to be locals of this method, so every new game
        // began at full whatever the player had chosen last time.
        Runnable remember = () -> {
            // Not while the game is silenced. Ctrl+S and Ctrl+M mute by
            // dragging the volume to nothing and putting it back afterwards
            // (GameScreen.toggleSound and toggleMusic, after UiToggleSound and
            // UiToggleMusic and :706), and a player
            // who mutes for a phone call and then quits must not come back to a
            // game that is silent for good with nothing on the slider to
            // explain it. Upstream keeps the two apart in the same way:
            // wc2.preferences carries MusicEnabled beside MusicVolume.
            if (screen.musicMuted() || screen.soundMuted()) {
                return;
            }
            settings.setVolumes(server.effectVolume(), server.musicVolume());
            settings.setMusicBackend(server.preferred());
            settings.save();
        };

        // What the in-game menu and the keyboard can ask of the running game:
        // stop it, speed it up, write it out, or give it up. Held here because
        // this is the only place that has all four of the loop, the frame, the
        // installation and the way back to the main menu.
        GameMenu.Session session = new GameMenu.Session() {
            @Override
            public void setPaused(boolean paused) {
                loop.setPaused(paused);
            }

            @Override
            public boolean isPaused() {
                return loop.isPaused();
            }

            @Override
            public int speed() {
                return loop.hertz();
            }

            @Override
            public void setSpeed(int cyclesPerSecond) {
                loop.setHertz(Math.max(1, cyclesPerSecond));
            }

            @Override
            public double interfaceScale() {
                return screen.interfaceScale();
            }

            @Override
            public void setInterfaceScale(double scale) {
                screen.setInterfaceScale(scale);
            }

            @Override
            public double gameScale() {
                return screen.gameScale();
            }

            @Override
            public void setGameScale(double scale) {
                screen.setGameScale(scale);
            }

            @Override
            public boolean wheelZoom() {
                return screen.wheelZoomEnabled();
            }

            @Override
            public void setWheelZoom(boolean enabled) {
                screen.setWheelZoom(enabled);
            }

            @Override
            public float effectVolume() {
                return server.effectVolume();
            }

            @Override
            public void setEffectVolume(float volume) {
                server.setEffectVolume(volume);
                remember.run();
            }

            @Override
            public float musicVolume() {
                return server.musicVolume();
            }

            @Override
            public void setMusicVolume(float volume) {
                server.setMusicVolume(volume);
                remember.run();
            }

            @Override
            public boolean synthesisedMusic() {
                // What is actually playing, not what was asked for. An
                // installation with no discs cannot honour a request for them,
                // and a caption that says otherwise is a caption that lies.
                return server.backend()
                        == net.chonkbase.chonkcraft.engine.sound.SoundServer.Backend.XMI;
            }

            @Override
            public void setSynthesisedMusic(boolean synthesised) {
                server.setBackend(synthesised
                        ? net.chonkbase.chonkcraft.engine.sound.SoundServer.Backend.XMI
                        : net.chonkbase.chonkcraft.engine.sound.SoundServer.Backend.CD);
                remember.run();
            }

            @Override
            public String save() {
                // The result belongs in the game's status line, where every
                // other report goes. Stamping it on the menu panel left it
                // sitting under an unrelated page until something else
                // overwrote it.
                String said = screen.saveGame();
                screen.setStatus(said);
                return said;
            }

            @Override
            public String load() {
                java.nio.file.Path file = mostRecentSave();
                if (file == null) {
                    return "no saved game";
                }
                // Loading rebuilds the world from the file, so the running
                // game is torn down rather than mutated underneath itself.
                // The window is not: it is the player's, and it stays.
                stopPlaying.run();
                new Thread(() -> resume(data, assets, pipeline, file), "chonkcraft-load").start();
                return "loading";
            }

            @Override
            public void endScenario() {
                stopPlaying.run();
                SwingUtilities.invokeLater(() ->
                        runMenu(data, assets, findMaps(assets), pipeline));
            }

            @Override
            public java.util.List<String> objectives() {
                if (mission == null || mission.objectives() == null
                        || mission.objectives().isBlank()) {
                    return java.util.List.of();
                }
                return java.util.List.of(mission.objectives().split("\n"));
            }

            @Override
            public boolean isNetworked() {
                return network != null;
            }
        };
        screen.setSession(session);
        screen.setMenu(new GameMenu(data, race, session));
        BattleShowcase.Director showcaseDirector = openingShowcase == null
                ? null : new BattleShowcase.Director(world, openingShowcase);

        // A departure deserves enough screen time to be read. The ordinary
        // WAITING message is updated every frame and would otherwise erase it
        // immediately after the synchronized QUIT was applied.
        final long[] networkNoticeUntil = {0L};
        loop.register(() -> {
            if (network != null) {
                // Lockstep: the world advances only when every machine's
                // commands for the cycle have arrived, so this may do nothing
                // at all for a while and that is correct rather than a stall.
                try {
                    var step = network.update();
                    for (var message : network.drainChatEvents()) {
                        screen.acceptChat(message);
                    }
                    for (var departure : network.drainDepartureEvents()) {
                        String message;
                        if (departure.hostLeft()) {
                            message = departure.playerName() + " (host) left the game";
                        } else if (departure.reason()
                                == net.chonkbase.chonkcraft.engine.network.GameCommand
                                        .DepartureReason.TIMEOUT) {
                            message = departure.playerName() + " lost connection and was dropped";
                        } else {
                            message = departure.playerName() + " left the game";
                        }
                        if ((departure.controlMask() & (1 << localPlayer)) != 0) {
                            message += "; you can now command their forces";
                        }
                        screen.setStatus(message);
                        networkNoticeUntil[0] = System.currentTimeMillis() + 5_000L;
                    }
                    if (step == net.chonkbase.chonkcraft.engine.network.NetworkGame.Step.DESYNC) {
                        screen.setStatus("desynchronised at cycle " + network.desyncCycle()
                                + " with player " + network.desyncPlayer());
                    } else if (step == net.chonkbase.chonkcraft.engine.network.NetworkGame
                            .Step.HOST_LEFT) {
                        if (System.currentTimeMillis() >= networkNoticeUntil[0]) {
                            screen.setStatus("The host left the game; this match has ended");
                        }
                        screen.repaint();
                        return;
                    } else if (step != net.chonkbase.chonkcraft.engine.network.NetworkGame
                            .Step.ADVANCED) {
                        if (System.currentTimeMillis() >= networkNoticeUntil[0]) {
                            java.util.List<Integer> waiting = network.waitingOn();
                            if (waiting.isEmpty()) {
                                screen.setStatus("waiting for the other player");
                            } else {
                                int player = waiting.getFirst();
                                long left = network.millisBeforeDropping(player);
                                screen.setStatus(left < 0
                                        ? "waiting for player " + (player + 1)
                                        : "waiting for player " + (player + 1) + ": "
                                                + ((left + 999) / 1_000) + "s");
                            }
                        }
                        screen.repaint();
                        return;
                    }
                } catch (java.io.IOException e) {
                    screen.setStatus("network error: " + e.getMessage());
                    return;
                } catch (RuntimeException broken) {
                    // Defensive rather than a fix for anything known. This is
                    // the render thread's own callback: an exception that
                    // escapes it stops the loop, and what the player sees is a
                    // window that has frozen with no message in it. Saying so
                    // and carrying on leaves a game that can at least be saved
                    // and quit.
                    screen.setStatus("network fault: " + broken);
                    return;
                }
            } else {
                world.tick();
            }
            screen.observePlayerIntents();
            if (mission != null) {
                // The mission's own victory and defeat conditions, run once a
                // second by the trigger system rather than every cycle.
                mission.triggers().tick();
                var outcome = mission.outcome();
                if (outcome != TriggerSystem.Outcome.RUNNING) {
                    screen.setOutcome(outcome);
                    // The eight columns, read while the world is still
                    // standing. The results screen is built after the game has
                    // been put down, so the figures have to be lifted now or
                    // there is nothing left to ask.
                    lastResult = new Result(outcome,
                            ResultsScreen.statisticsOf(world, localPlayer),
                            world.player(localPlayer) == null
                                    ? 0 : world.player(localPlayer).score());
                    // Let the player see the field for a moment before the
                    // result covers it, the way the original does.
                    if (onFinished != null && !finishing.getAndSet(true)) {
                        boolean won = outcome == TriggerSystem.Outcome.VICTORY;
                        javax.swing.Timer delay = new javax.swing.Timer(2500, event -> {
                            // Put the game down before showing what happened.
                            // Without this the simulation went on running
                            // behind the result screen and behind the main
                            // menu after it: the battle carried on being
                            // fought, out of sight, and could still be heard.
                            stopPlaying.run();
                            onFinished.accept(frame, won);
                        });
                        delay.setRepeats(false);
                        delay.start();
                    }
                }
            }
            screen.playAnnouncements();
            if (showcaseDirector != null) {
                BattleShowcase.Status status = showcaseDirector.update();
                if (status.message() != null) {
                    screen.setStatus(status.message());
                }
            }
            screen.scrollStep();
            screen.cycleStep();
            screen.repaint();
        });
        loop.start();
    }

    // --------------------------------------------------------------- sources

    /** The map named by {@code chonkcraft.map} or {@code CHONKCRAFT_MAP}, or none. */
    private static Path resolveMap(AssetSource assets) {
        final String wanted = setting("chonkcraft.map", "CHONKCRAFT_MAP");
        if (wanted == null || wanted.isBlank()) {
            // Nothing asked for, so nothing chosen. Returning the first map
            // that happens to be on disk would mean the menu never appeared.
            return null;
        }
        String name = sourceMapName(assets, wanted);
        if (name == null) {
            // The flag has always named a file rather than a folder and a
            // file, and a source that has told two maps of one name apart by
            // the folder they came from spells one of them with its folder on
            // the front. Matching the tail as well keeps -Dchonkcraft.map=ALAMO.PUD
            // meaning what it meant.
            for (String candidate : assets.mapNames()) {
                if (Paths.get(candidate).getFileName().toString().equalsIgnoreCase(wanted)) {
                    name = candidate;
                    break;
                }
            }
        }
        return name == null ? null : Paths.get(name);
    }
}
