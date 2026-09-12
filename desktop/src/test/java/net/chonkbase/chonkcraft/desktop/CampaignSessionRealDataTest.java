package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.KeyEvent;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.sound.CdMusic;
import net.chonkbase.chonkcraft.engine.sound.GameAudio;
import net.chonkbase.chonkcraft.engine.sound.SoundServer;
import net.chonkbase.chonkcraft.engine.trigger.TriggerSystem;
import net.chonkbase.runtime.Java2DPipeline;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A hidden mission used to announce attacks and replace a healthy game's screen.
 * Repeated menu launches created independent loops; only the last had controls.
 * These enter through the real menu and Main startup, using retail Human 12's
 * 44-unit player roster, then force the discarded world's defeat. The real
 * window and delayed result run in an isolated JVM under Xvfb, so a headless
 * unit test cannot silently substitute a different lifecycle for the player.
 */
class CampaignSessionRealDataTest {
    private static final String MAP = "campaigns/human/level12h";

    private static GameData data() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null, "No Warcraft II assets; set CHONKCRAFT_ASSET_PACK or WC2_INSTALL_DIR.");
        return new GameData(assets);
    }

    @Test
    @DisplayName("repeated menu keys request only one world while a map loads")
    void repeatedMenuKeysRequestOneWorld() throws Exception {
        GameData data = data();
        List<MenuScreen.Launch> launches = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            MenuScreen menu = new MenuScreen(data, "human", 640, 480, launches::add);
            menu.showMapsForTest(data, List.of(Path.of("ALAMO.PUD")));
            KeyEvent key = new KeyEvent(menu, KeyEvent.KEY_PRESSED, 1L, 0,
                    KeyEvent.VK_1, '1');
            for (int i = 0; i < 3; i++) {
                for (var listener : menu.getKeyListeners()) {
                    listener.keyPressed(key);
                }
            }
        });
        assertEquals(1, launches.size(), "repeated input started multiple game loads");
        assertEquals(Path.of("ALAMO.PUD"), launches.getFirst().map(),
                "the accepted launch must retain the selected map");
    }

    @Test
    @DisplayName("replacing a mission stops its hidden battle and defeat screen")
    void replacingAMissionStopsItsHiddenBattle() throws Exception {
        runWindowCase("overlap");
    }

    @Test
    @DisplayName("a queued defeat cannot replace a newly loaded mission")
    void aQueuedDefeatCannotReplaceANewMission() throws Exception {
        runWindowCase("queued-result");
    }

    @Test
    @DisplayName("menu briefing and result screens route their authentic recorded themes")
    void frontEndScreensRouteTheirRecordedThemes() throws Exception {
        Assumptions.assumeTrue(data().source().isBattleNetEdition(),
                "The recorded screen referee requires an authenticated Battle.net asset pack.");
        runWindowCase("soundtrack");
    }

    private static void runWindowCase(String scenario) throws Exception {
        data();
        Path xvfb = Path.of("/usr/bin/xvfb-run");
        boolean hasDisplay = System.getenv("DISPLAY") != null;
        Assumptions.assumeTrue(Files.isExecutable(xvfb) || hasDisplay,
                "Headless campaign startup requires Xvfb or DISPLAY.");
        List<String> command = new ArrayList<>();
        if (Files.isExecutable(xvfb)) {
            command.add(xvfb.toString());
            command.add("-a");
        }
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Djava.awt.headless=false");
        command.add("-Xmx2g");
        for (String property : List.of("chonkcraft.pack", "wc2.install.dir")) {
            if (System.getProperty(property) != null) {
                command.add("-D" + property + "=" + System.getProperty(property));
            }
        }
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(CampaignSessionRealDataTest.class.getName());
        command.add(scenario);
        Path log = Files.createTempFile("campaign-session-", ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS),
                    "campaign startup did not finish: " + Files.readString(log));
            assertEquals(0, process.exitValue(),
                    "the playable session broke: " + Files.readString(log));
            assertTrue(Files.readString(log).contains("SESSION VERIFIED " + scenario),
                    "the child exited without checking its real window");
        } finally {
            process.descendants().forEach(child -> child.destroyForcibly());
            process.destroyForcibly();
            Files.deleteIfExists(log);
        }
    }

    /** Exercises the production startup in a disposable, real window. */
    public static void main(String[] args) throws Exception {
        try {
            AssetSource assets = AssetSource.fromEnvironment();
            GameData data = new GameData(assets);
            var settings = Main.class.getDeclaredField("savedSettings");
            settings.setAccessible(true);
            settings.set(null, Settings.load(null));
            if ("soundtrack".equals(args[0])) {
                frontEndScreens(data, assets);
                System.out.println("SESSION VERIFIED soundtrack");
                System.exit(0);
            }
            Mission old = data.loadMission(MAP);
            Mission visible = data.loadMission(MAP);
            assertEquals(44, visible.world().unitTypesCount(1, null),
                    "Human 12 must have its authenticated starting roster");
            boolean queued = "queued-result".equals(args[0]);
            if (queued) {
                killPlayer(old);
            }
            start(data, assets, old);
            if (queued) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (old.outcome() == TriggerSystem.Outcome.RUNNING
                        && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertEquals(TriggerSystem.Outcome.DEFEAT, old.outcome(),
                        "the discarded mission must have a real pending defeat");
            }
            start(data, assets, visible);
            AppWindow shell = shell();
            var screen = shell.frame().getContentPane();
            assertTrue(screen instanceof GameScreen, "the new mission was not displayed");
            long oldCycle = old.world().cycle();
            long visibleCycle = visible.world().cycle();
            if (!queued) {
                killPlayer(old);
            }
            // Longer than Main's result delay. A hidden world formerly kept
            // ticking and covered this healthy map with its own ResultsScreen.
            Thread.sleep(3300);
            SwingUtilities.invokeAndWait(() -> assertSame(screen,
                    shell.frame().getContentPane(), "a discarded defeat replaced the new map"));
            assertEquals(oldCycle, old.world().cycle(), "the hidden world is still advancing");
            assertTrue(visible.world().cycle() > visibleCycle, "the visible mission froze");
            assertEquals(TriggerSystem.Outcome.RUNNING, visible.outcome(),
                    "the visible mission has not lost");
            assertEquals(44, visible.world().unitTypesCount(1, null),
                    "the healthy roster changed during the lifecycle check");
            assertEquals(1L, Thread.getAllStackTraces().keySet().stream()
                    .filter(thread -> thread.isAlive()
                            && "chonkcraft-sim".equals(thread.getName())).count(),
                    "there must be exactly one game loop");
            System.out.println("SESSION VERIFIED " + args[0]);
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void killPlayer(Mission mission) {
        for (var unit : List.copyOf(mission.world().units())) {
            if (unit.player() == 1) {
                mission.world().kill(unit);
            }
        }
    }

    private static void frontEndScreens(GameData data, AssetSource assets) throws Exception {
        CdMusic menu = detachedFrontEnd(data, assets);
        Method runMenu = Main.class.getDeclaredMethod("runMenu", GameData.class, AssetSource.class,
                List.class, Java2DPipeline.Choice.class);
        runMenu.setAccessible(true);
        SwingUtilities.invokeAndWait(() -> invoke(runMenu, data, assets, List.of(),
                Java2DPipeline.Choice.SOFTWARE));
        assertEquals("Main Menu", menu.playing(), "the menu bypassed the recorded soundtrack");

        MenuScreen choices = (MenuScreen) shell().frame().getContentPane();
        Path invalidSave = Files.createTempFile("unreadable-campaign-", ".sav");
        try {
            Files.writeString(invalidSave, "This is not a saved game.");
            SwingUtilities.invokeAndWait(() -> {
                choices.showSavesForTest(data, List.of(), List.of(invalidSave));
                choices.pressForTest(0);
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (Thread.getAllStackTraces().keySet().stream().anyMatch(thread ->
                    thread.isAlive() && "chonkcraft-load".equals(thread.getName()))
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            SwingUtilities.invokeAndWait(() -> {
                choices.showMissionsForTest(data, List.of(), "orc", 14);
                choices.pressForTest(0);
            });
        } finally {
            Files.deleteIfExists(invalidSave);
        }
        assertTrue(shell().frame().getContentPane() instanceof SplashScreen,
                "a failed save must leave the menu able to launch Orc 1's act card");
        CdMusic briefing = detachedFrontEnd(data, assets);
        SwingUtilities.invokeAndWait(() -> {
            try {
                ((SplashScreen) shell().frame().getContentPane()).skip();
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
        assertTrue(shell().frame().getContentPane() instanceof BriefingScreen,
                "passing the act card did not show the briefing");
        assertEquals("Orc Briefing", briefing.playing(),
                "the briefing bypassed the recorded soundtrack");

        Method result = Main.class.getDeclaredMethod("showResult", GameData.class,
                AssetSource.class, Java2DPipeline.Choice.class, String.class, int.class,
                boolean.class);
        result.setAccessible(true);
        SwingUtilities.invokeAndWait(() -> invoke(result, data, assets,
                Java2DPipeline.Choice.SOFTWARE, "human", 12, true));
        assertEquals("Human Victory", briefing.playing(),
                "the result screen bypassed the recorded soundtrack");
    }

    /** Uses the real PCM mixer without requiring a speaker on the test runner. */
    private static CdMusic detachedFrontEnd(GameData data, AssetSource assets) throws Exception {
        GameAudio audio = new GameAudio(data.sounds());
        CdMusic disc = new CdMusic(assets, audio.mixer());
        SoundServer server = new SoundServer(audio.mixer(), disc, data.music(),
                SoundServer.Backend.CD);
        var field = Main.class.getDeclaredField("frontEndAudio");
        field.setAccessible(true);
        var constructor = field.getType().getDeclaredConstructor(GameAudio.class, SoundServer.class);
        constructor.setAccessible(true);
        field.set(null, constructor.newInstance(audio, server));
        return disc;
    }

    private static void invoke(Method method, Object... arguments) {
        try {
            method.invoke(null, arguments);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void start(GameData data, AssetSource assets, Mission mission) throws Exception {
        Method start = Main.class.getDeclaredMethod("start", GameData.class, AssetSource.class,
                Java2DPipeline.Choice.class, Mission.class, Path.class, String.class, int.class);
        start.setAccessible(true);
        start.invoke(null, data, assets, Java2DPipeline.Choice.SOFTWARE,
                mission, null, "human", 12);
        SwingUtilities.invokeAndWait(() -> { });
    }

    private static AppWindow shell() throws Exception {
        var field = Main.class.getDeclaredField("window");
        field.setAccessible(true);
        return (AppWindow) field.get(null);
    }
}
