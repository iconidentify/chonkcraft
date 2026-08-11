package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Control and Alt are two keys, and the game's own help page says so.
 *
 * <p>{@code GameScreen.keyPressed} read {@code alt || control} as one
 * modifier. For most of the letters that is generosity; for M it is a
 * collision. {@code commands.legacy-declaration:25} binds Alt-M to the game menu and the
 * shipped keystroke table ({@code menus/help.legacy-declaration:19}) reserves Ctrl-M for
 * muting the music, so this implementation had Ctrl-M opening a menu and no way to mute
 * the music from the keyboard at all. Ctrl-S, Ctrl-T, Tab and the period were
 * not bound to anything.
 *
 * <p>Each check presses the key on the real screen and asks what changed,
 * because the fault was never in what the branches did -- it was in which
 * branch a keystroke reached.
 */
class HotkeyBindingTest {

    private static final String MAP = "campaigns/human/level02h";
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;

    /** A session that records what the keys asked of it. */
    private static final class Recording implements GameMenu.Session {
        private boolean paused;
        private int speed = 30;
        private double scale = 1;
        private double game = 1;
        private boolean wheel = true;
        private float effects = 0.8f;
        private float music = 0.6f;
        private boolean synthesised;
        private final List<String> asked = new ArrayList<>();

        @Override
        public void setPaused(boolean value) {
            paused = value;
        }

        @Override
        public boolean isPaused() {
            return paused;
        }

        @Override
        public int speed() {
            return speed;
        }

        @Override
        public void setSpeed(int cyclesPerSecond) {
            speed = cyclesPerSecond;
        }

        @Override
        public double interfaceScale() {
            return scale;
        }

        @Override
        public void setInterfaceScale(double value) {
            scale = value;
        }

        @Override
        public double gameScale() {
            return game;
        }

        @Override
        public void setGameScale(double value) {
            game = value;
        }

        @Override
        public boolean wheelZoom() {
            return wheel;
        }

        @Override
        public void setWheelZoom(boolean enabled) {
            wheel = enabled;
        }

        @Override
        public float effectVolume() {
            return effects;
        }

        @Override
        public void setEffectVolume(float volume) {
            effects = volume;
        }

        @Override
        public float musicVolume() {
            return music;
        }

        @Override
        public void setMusicVolume(float volume) {
            music = volume;
        }

        @Override
        public String save() {
            asked.add("save");
            return "saved";
        }

        @Override
        public String load() {
            asked.add("load");
            return "loaded";
        }

        @Override
        public void endScenario() {
            asked.add("end");
        }

        @Override
        public List<String> objectives() {
            return List.of();
        }

        @Override
        public boolean isNetworked() {
            return false;
        }

        @Override
        public boolean synthesisedMusic() {
            return synthesised;
        }

        @Override
        public void setSynthesisedMusic(boolean value) {
            synthesised = value;
        }
    }

    private record Scene(GameScreen screen, World world, GameData data,
            SidePanel panel, GameMenu menu, Recording session) {}

    private static GameData data() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set -Dchonkcraft.pack=... or wc2.install.dir");
        return new GameData(assets);
    }

    private static Scene scene() {
        GameData data = data();
        PudMap pud = data.campaignMap(MAP);
        Assumptions.assumeTrue(pud != null, "no campaign map available");
        var tileset = data.loadTileset(pud.tileset());
        World world = new World(GameMap.from(pud, tileset.tileset()), Player.from(pud));
        world.setUnitTypes(data.unitTypes().types());
        world.setUpgrades(data.upgrades().upgrades());
        world.setMissileTypes(data.missiles().types());
        world.fog().revealAll(0);
        String tilesetName = pud.tileset() == PudMap.Tileset.FOREST
                ? "summer"
                : pud.tileset().name().toLowerCase(java.util.Locale.ROOT);
        var layout = data.uiLayout("human", WIDTH, HEIGHT);
        SidePanel panel = new SidePanel(world, data, 0, "human", tilesetName, layout);
        CommandApplier applier = new CommandApplier(world,
                new ArrayList<>(data.unitTypes().types().values()));
        GameScreen screen = new GameScreen(world, data,
                new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), tileset.palette(),
                tilesetName, 0, WIDTH, HEIGHT,
                new net.chonkbase.chonkcraft.engine.sound.GameAudio(data.sounds()),
                panel, null, applier, CommandSink.local(applier),
                java.util.List.of(), "human");
        screen.setSize(WIDTH, HEIGHT);
        Recording session = new Recording();
        GameMenu menu = new GameMenu(data, "human", session);
        screen.setSession(session);
        screen.setMenu(menu);
        return new Scene(screen, world, data, panel, menu, session);
    }

    private static void press(GameScreen screen, int code, boolean control, boolean alt) {
        int mask = (control ? InputEvent.CTRL_DOWN_MASK : 0)
                | (alt ? InputEvent.ALT_DOWN_MASK : 0);
        screen.keyPressed(new KeyEvent(screen, KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(), mask, code, KeyEvent.CHAR_UNDEFINED));
    }

    @Test
    @DisplayName("Command-Shift-E writes one complete playtest evidence packet and confirms it")
    void commandShiftEWritesACompleteEvidencePacket(@TempDir Path home) throws Exception {
        Scene scene = scene();
        UnitType footman = scene.data().unitTypes().types().get("unit-footman");
        assertNotNull(footman);
        Unit witness = null;
        for (int y = 1; y < scene.world().map().height() - 1 && witness == null; y++) {
            for (int x = 1; x < scene.world().map().width() - 1; x++) {
                witness = scene.world().createUnit(footman, 0, x, y);
                if (witness != null) {
                    break;
                }
            }
        }
        assertNotNull(witness, "the campaign map had nowhere for a witness unit");
        witness.setSelected(true);
        scene.screen().setSaveContext(MAP, "human", 2);

        String oldHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", home.toString());
            int modifiers = InputEvent.META_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK;
            boolean handled = scene.screen().keyPressed(new KeyEvent(
                    scene.screen(), KeyEvent.KEY_PRESSED, System.currentTimeMillis(),
                    modifiers, KeyEvent.VK_E, 'E'));
            assertTrue(handled, "the Mac evidence shortcut was ignored");
            assertTrue(scene.screen().status().startsWith("evidence saved playtest-"),
                    "there was no visible success confirmation: " + scene.screen().status());

            Path root = home.resolve(".chonkcraft/evidence");
            List<Path> packets;
            try (var entries = Files.list(root)) {
                packets = entries.filter(Files::isDirectory).toList();
            }
            assertEquals(1, packets.size(), "one keypress must make one packet directory");
            Path packet = packets.getFirst();
            Path screenshot = packet.resolve("screen.png");
            Path save = packet.resolve("state.sav.gz");
            Path evidence = packet.resolve("evidence.json");
            assertTrue(Files.size(screenshot) > 100, "packet screenshot is empty");
            assertTrue(Files.size(save) > 100, "packet save is empty");
            assertTrue(Files.size(evidence) > 100, "packet forensic JSON is empty");
            BufferedImage image = javax.imageio.ImageIO.read(screenshot.toFile());
            assertEquals(WIDTH, image.getWidth());
            assertEquals(HEIGHT, image.getHeight());

            String json = Files.readString(evidence);
            assertTrue(json.contains("\"map_path\": \"" + MAP + "\""));
            assertTrue(json.contains("\"cycle\": 0"));
            assertTrue(json.contains("\"id\": " + witness.id()),
                    "selected/nearby witness absent from JSON");
            assertTrue(json.contains("\"visual_tile\""),
                    "visual terrain codes absent from JSON");
            assertTrue(json.contains("\"sync_rng\"") && json.contains("\"async_rng\""),
                    "replay RNG state absent from JSON");
        } finally {
            if (oldHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", oldHome);
            }
        }
    }

    @Test
    @DisplayName("Ctrl-M mutes the music and Alt-M opens the menu")
    void theTwoMKeysAreDifferentKeys() {
        Scene scene = scene();
        float before = scene.session().musicVolume();
        Assumptions.assumeTrue(before > 0, "the fixture must start with the music audible");

        press(scene.screen(), KeyEvent.VK_M, true, false);
        assertTrue(scene.screen().musicMuted(),
                "Ctrl-M did not mute the music, which is what help.legacy-declaration:19 promises it does");
        assertEquals(0f, scene.session().musicVolume(),
                "the music was left playing after Ctrl-M");
        assertFalse(scene.menu().isOpen(),
                "Ctrl-M opened the game menu: alt and control were read as one modifier,"
                        + " so the key upstream reserves for the music opened Alt-M's page");

        press(scene.screen(), KeyEvent.VK_M, true, false);
        assertFalse(scene.screen().musicMuted(), "Ctrl-M is a switch and did not switch back");
        assertEquals(before, scene.session().musicVolume(),
                "unmuting put the music at a different volume from the one it was at");

        press(scene.screen(), KeyEvent.VK_M, false, true);
        assertTrue(scene.menu().isOpen(), "Alt-M did not open the game menu");
    }

    @Test
    @DisplayName("Ctrl-S mutes the effects rather than stopping a unit")
    void controlSIsTheSoundSwitch() {
        Scene scene = scene();
        float before = scene.session().effectVolume();
        Assumptions.assumeTrue(before > 0, "the fixture must start with the effects audible");
        press(scene.screen(), KeyEvent.VK_S, true, false);
        assertTrue(scene.screen().soundMuted(), "Ctrl-S did not mute the effects");
        assertEquals(0f, scene.session().effectVolume());
        press(scene.screen(), KeyEvent.VK_S, true, false);
        assertEquals(before, scene.session().effectVolume(),
                "the effects came back at a different volume");
    }

    @Test
    @DisplayName("Tab hides the minimap's ground")
    void tabTogglesTheMinimapTerrain() {
        Scene scene = scene();
        assertTrue(scene.panel().minimapTerrain(), "the minimap starts with its ground on");
        press(scene.screen(), KeyEvent.VK_TAB, false, false);
        assertFalse(scene.panel().minimapTerrain(),
                "Tab did not hide the minimap terrain: UiToggleTerrain is what the help"
                        + " page's TAB entry means");
        press(scene.screen(), KeyEvent.VK_TAB, false, false);
        assertTrue(scene.panel().minimapTerrain(), "Tab did not put the ground back");
    }

    @Test
    @DisplayName("Ctrl-T pins the camera to the selected unit and pressing it again lets go")
    void controlTFollowsAUnit() {
        Scene scene = scene();
        UnitType footman = scene.data().unitTypes().types().get("unit-footman");
        assertNotNull(footman);
        Unit unit = null;
        for (int x = 4; x < 16 && unit == null; x++) {
            for (int y = 4; y < 12 && unit == null; y++) {
                unit = scene.world().createUnit(footman, 0, x, y);
            }
        }
        Assumptions.assumeTrue(unit != null, "nowhere to stand a footman");
        unit.setSelected(true);
        scene.screen().selectForTest(unit);

        press(scene.screen(), KeyEvent.VK_T, true, false);
        assertSame(unit, scene.screen().trackedForTest(),
                "Ctrl-T did not put the camera on the selected unit");
        press(scene.screen(), KeyEvent.VK_T, true, false);
        assertEquals(null, scene.screen().trackedForTest(),
                "Ctrl-T is a switch and did not let go");
    }

    @Test
    @DisplayName("the period finds an idle worker, as Alt-I does")
    void thePeriodFindsAnIdleWorker() {
        Scene scene = scene();
        UnitType peasant = scene.data().unitTypes().types().get("unit-peasant");
        assertNotNull(peasant);
        Unit worker = null;
        for (int x = 4; x < 16 && worker == null; x++) {
            for (int y = 4; y < 12 && worker == null; y++) {
                worker = scene.world().createUnit(peasant, 0, x, y);
            }
        }
        Assumptions.assumeTrue(worker != null, "nowhere to stand a peasant");

        press(scene.screen(), KeyEvent.VK_PERIOD, false, false);
        assertTrue(worker.selected(),
                "the period did not select the idle peasant: help.legacy-declaration lists it beside"
                        + " Alt-I and nothing answered it");
    }
}
