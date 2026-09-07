package net.chonkbase.chonkcraft.desktop;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.map.PudReader;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.MapRenderer;
import net.chonkbase.chonkcraft.engine.network.GameLobby;
import net.chonkbase.chonkcraft.engine.network.SyncHash;

/**
 * Reproducible paint, allocation and graphics-lifetime measurements.
 *
 * <p>Run explicitly, never as a wall-clock assertion in the unit suite. The
 * display mode paints to a real Java2D surface and synchronizes submission;
 * it measures painting cost, not scanout or input-to-photon latency. No game
 * saves, recordings, preferences or external network peers are created.
 * See docs/render-performance.md for commands and comparison conditions.
 */
public final class RenderBenchmark {
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static int frames = 180;
    private static int warmup = 40;
    private static int width = 2560;
    private static int height = 1600;
    private static int sessions = 30;
    private static int battleUnits;
    private static JFrame window;
    private static BufferedImage canvas;
    private static JPanel current;
    private static long witness;

    private RenderBenchmark() {}

    public static void main(String[] args) throws Exception {
        boolean display = Arrays.asList(args).contains("--display");
        String mode = "all";
        for (String arg : args) {
            if (arg.startsWith("--frames=")) {
                frames = positive(arg);
            } else if (arg.startsWith("--warmup=")) {
                warmup = positive(arg);
            } else if (arg.startsWith("--width=")) {
                width = positive(arg);
            } else if (arg.startsWith("--height=")) {
                height = positive(arg);
            } else if (arg.startsWith("--sessions=")) {
                sessions = positive(arg);
            } else if (arg.startsWith("--battle=")) {
                battleUnits = positive(arg);
            } else if (arg.startsWith("--mode=")) {
                mode = arg.substring(arg.indexOf('=') + 1);
            } else if (!arg.equals("--display")) {
                throw new IllegalArgumentException("unknown benchmark argument: " + arg);
            }
        }
        if (!Arrays.asList("all", "menus", "game", "stone", "soak").contains(mode)) {
            throw new IllegalArgumentException("mode must be all, menus, game, stone or soak");
        }
        System.out.printf(Locale.ROOT, "java=%s os=%s arch=%s max_heap_MiB=%d display=%s size=%dx%d%n",
                System.getProperty("java.runtime.version"), System.getProperty("os.name"),
                System.getProperty("os.arch"), Runtime.getRuntime().maxMemory() / 1048576,
                display, width, height);
        GameData data = mode.equals("stone") ? null : new GameData(AssetSource.fromEnvironment());
        String selected = mode;
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    if (display) {
                        window = new JFrame("ChonkCraft rendering benchmark");
                        window.setSize(width, height);
                        window.setVisible(true);
                        System.out.println("graphics=" + window.getGraphicsConfiguration());
                    }
                    if (selected.equals("all") || selected.equals("menus")) {
                        menus(data);
                    }
                    if (selected.equals("all") || selected.equals("game")) {
                        game(data);
                    }
                    if (selected.equals("all") || selected.equals("stone")) {
                        stone();
                    }
                } catch (Exception failed) {
                    throw new RuntimeException(failed);
                }
            });
            if (selected.equals("soak")) {
                soak(data);
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                current = null;
                if (canvas != null) {
                    canvas.flush();
                    canvas = null;
                }
                if (window != null) {
                    window.dispose();
                }
            });
        }
        SwingUtilities.invokeAndWait(() -> {});
        System.gc();
        System.out.printf(Locale.ROOT, "heap_after_gc_MiB=%d witness=%016x%n",
                ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1048576, witness);
    }

    private static int positive(String arg) {
        int value = Integer.parseInt(arg.substring(arg.indexOf('=') + 1));
        if (value <= 0) {
            throw new IllegalArgumentException("benchmark values must be positive: " + arg);
        }
        return value;
    }

    private static void attach(JPanel panel) {
        current = panel;
        panel.setPreferredSize(new Dimension(width, height));
        panel.setSize(width, height);
        if (window != null) {
            window.setContentPane(panel);
            window.pack();
            window.validate();
        } else if (canvas == null) {
            canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        }
        paint();
    }

    private static void paint() {
        Graphics2D g = window == null ? canvas.createGraphics() : (Graphics2D) current.getGraphics();
        try {
            current.paint(g);
        } finally {
            g.dispose();
        }
        if (window != null) {
            Toolkit.getDefaultToolkit().sync();
        }
    }

    private static void measure(String name, Runnable action) {
        measure(name, action, warmup, frames);
    }

    private static void measure(String name, Runnable action, int warm, int count) {
        for (int frame = 0; frame < warm; frame++) {
            action.run();
        }
        long[] times = new long[count];
        long thread = Thread.currentThread().threadId();
        long allocated = THREADS.getThreadAllocatedBytes(thread);
        for (int frame = 0; frame < count; frame++) {
            long start = System.nanoTime();
            action.run();
            times[frame] = System.nanoTime() - start;
        }
        allocated = THREADS.getThreadAllocatedBytes(thread) - allocated;
        Arrays.sort(times);
        System.out.printf(Locale.ROOT, "%s median_ms=%.3f p95_ms=%.3f allocated_B/op=%d%n",
                name, times[count / 2] / 1_000_000.0,
                times[Math.min(count - 1, (int) (count * .95))] / 1_000_000.0, allocated / count);
    }

    private static void menus(GameData data) throws Exception {
        MenuScreen menu = new MenuScreen(data, "human", width, height, launch -> {});
        menu.showMainMenu(data, Main.findMaps(data.source()));
        attach(menu);
        measure("menu", RenderBenchmark::paint);
        menu.showMapsForTest(data, Main.findMaps(data.source()));
        measure("map-menu", RenderBenchmark::paint);
        try (GameLobby lobby = GameLobby.host("Benchmark", "ALAMO.PUD", 8, 0)) {
            lobby.setOccupant(1, GameLobby.Occupant.COMPUTER);
            attach(new LobbyScreen(data, lobby, "ALAMO.PUD", new LobbyScreen.Listener() {
                public void onStart(GameLobby started) {}
                public void onCancel() {}
                public void onUpdateRequired() {}
            }));
            measure("lobby", RenderBenchmark::paint);
        }
        GameFont font = GameFont.load(data, GameFont.Face.GAME);
        measure("font-width", () -> witness = font.widthOf("Lumber: 1200 Gold: 400"), 2000, 20000);
        measure("font-fitted", () -> witness = font.fitted("A long multiplayer lobby name", 90).length(),
                2000, 20000);
    }

    private record Scene(World world, GameScreen screen) {}

    private static Scene scene(GameData data) {
        String map = data.source().mapNames().stream().filter(name -> name.equalsIgnoreCase("ALAMO.PUD")
                || name.toUpperCase(Locale.ROOT).endsWith("/ALAMO.PUD")).findFirst().orElseThrow();
        PudMap pud = PudReader.read(data.source().map(map));
        var tiles = data.loadTileset(pud.tileset());
        World world = new World(GameMap.from(pud, tiles.tileset()), Player.forSoloGame(pud));
        data.configureWorld(world, pud);
        data.populate(world, pud);
        world.recalculateSupply();
        BattleShowcase.Result battle = battleUnits == 0 ? null
                : BattleShowcase.deploy(world, data.unitTypes().types(), battleUnits);
        var terrain = new MapRenderer(tiles.tileset(), tiles.sheet()).render(
                world.map().width(), world.map().height(), world.map().tileCodes())
                .toIndexedBufferedImage(tiles.palette());
        SidePanel side = new SidePanel(world, data, 0, "human", "summer", data.uiLayout("human", 640, 480));
        GameScreen screen = new GameScreen(world, data, terrain, tiles.palette(), "summer", 0,
                width, height, null, side, null, null, null, tiles.cyclingRanges(), "human");
        screen.setFogOpacity(data.fogOfWar().levels());
        screen.setFogTiles(FogTiles.from(tiles.sheet(), data.fogOfWar().levels()));
        int[] start = pud.startLocation(0);
        if (battle != null) {
            screen.centreOn(battle.centreX(), battle.centreY());
            System.out.println("battle_units=" + battle.deployed());
        } else if (start != null) {
            screen.centreOn(start[0], start[1]);
        }
        return new Scene(world, screen);
    }

    private static void game(GameData data) {
        Scene scene = scene(data);
        attach(scene.screen());
        for (int zoom : new int[] {1, 2}) {
            scene.screen().setGameScale(zoom);
            measure("game-zoom-" + zoom, RenderBenchmark::paint);
            measure("game-palette-zoom-" + zoom, () -> {
                scene.screen().cycleStep();
                paint();
            });
        }
        int[] movement = {0};
        measure("game-camera-pan", () -> {
            boolean right = movement[0]++ / 30 % 2 == 0;
            scene.screen().keyDown(java.awt.event.KeyEvent.VK_RIGHT, right);
            scene.screen().keyDown(java.awt.event.KeyEvent.VK_LEFT, !right);
            scene.screen().scrollStep();
            scene.screen().cycleStep();
            paint();
        });
        scene.screen().keyDown(java.awt.event.KeyEvent.VK_RIGHT, false);
        scene.screen().keyDown(java.awt.event.KeyEvent.VK_LEFT, false);
        measure("game-tick-and-paint", () -> {
            scene.world().tick();
            scene.screen().cycleStep();
            paint();
        });
        witness = SyncHash.of(scene.world());
        System.out.printf(Locale.ROOT, "world_cycle=%d world_hash=%016x%n", scene.world().cycle(), witness);
        BufferedImage reference = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = reference.createGraphics();
        scene.screen().paint(g);
        g.dispose();
        java.util.zip.CRC32 pixels = new java.util.zip.CRC32();
        for (int pixel : reference.getRGB(0, 0, width, height, null, 0, width)) {
            for (int shift = 0; shift <= 24; shift += 8) {
                pixels.update(pixel >> shift & 255);
            }
        }
        System.out.printf(Locale.ROOT, "frame_crc32=%08x%n", pixels.getValue());
    }

    private static void stone() {
        int[] next = {200};
        measure("stone-resize", () -> {
            BufferedImage image = StoneTexture.of(176, next[0]++, StoneTexture.Tint.STONE, 3);
            witness = 1;
            for (int y = 0; y < image.getHeight(); y += 7) {
                for (int x = 0; x < image.getWidth(); x += 7) {
                    witness = 31 * witness + image.getRGB(x, y);
                }
            }
        }, 3, 24);
    }

    private static void soak(GameData data) throws Exception {
        for (int session = 0; session < sessions; session++) {
            SwingUtilities.invokeAndWait(() -> {
                Scene scene = scene(data);
                attach(scene.screen());
                for (int cycle = 0; cycle < 120; cycle++) {
                    scene.world().tick();
                    scene.screen().cycleStep();
                    if (cycle % 3 == 0) {
                        scene.screen().centreOn(12 + cycle / 3, 12 + cycle / 4);
                        paint();
                    }
                }
                witness = SyncHash.of(scene.world());
                attach(new JPanel());
            });
            // Each real transition returns to the event queue. Running thirty
            // sessions inside one event callback instead retains all their
            // pending resize/focus events and measures the blocked queue.
            SwingUtilities.invokeAndWait(() -> {});
            if ((session + 1) % 5 == 0) {
                System.out.printf(Locale.ROOT, "sessions=%d heap_MiB=%d%n", session + 1,
                        ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1048576);
            }
        }
    }
}
