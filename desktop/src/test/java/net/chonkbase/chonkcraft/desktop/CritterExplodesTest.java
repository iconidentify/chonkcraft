package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.InstallSource;
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

/**
 * Ten clicks on a sheep blow it up.
 *
 * <p>{@code ClicksToExplode = 10} is set on {@code unit-critter} and on nothing
 * else in the game. It sat in the parser's unmodelled set, so nothing counted
 * the clicks and the easter egg had never worked in this implementation.
 *
 * <p>{@code HandleSuicideClick} counts
 * only while the critter is the sole selected unit and resets to one when
 * anything else is clicked, which is the half worth testing: a counter that
 * ignored what else was clicked would let a player blow up a sheep by clicking
 * ten different ones.
 */
class CritterExplodesTest {

    private static final String MAP = "campaigns/human/level02h";
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    private static final int TILE = 32;

    private record Scene(GameScreen screen, World world, GameData data) {}

    private static GameData data() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
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

        CommandApplier applier = new CommandApplier(world,
                new ArrayList<>(data.unitTypes().types().values()));
        GameScreen screen = new GameScreen(world, data,
                new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_RGB), tileset.palette(),
                tilesetName, 0, WIDTH, HEIGHT,
                new net.chonkbase.chonkcraft.engine.sound.GameAudio(data.sounds()),
                null, null, applier, CommandSink.local(applier),
                java.util.List.of(), "human");
        screen.setSize(WIDTH, HEIGHT);
        screen.setLayout((net.chonkbase.chonkcraft.engine.ui.UiLayout.Layout) null);
        screen.setGameScale(1);
        return new Scene(screen, world, data);
    }

    private static void leftClick(GameScreen screen, int x, int y) {
        MouseEvent event = new MouseEvent(screen, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), InputEvent.BUTTON1_DOWN_MASK,
                x, y, 1, false, MouseEvent.BUTTON1);
        for (var listener : screen.getMouseListeners()) {
            listener.mousePressed(event);
        }
    }

    private static void clickOn(GameScreen screen, Unit unit) {
        leftClick(screen, unit.tileX() * TILE + TILE / 2, unit.tileY() * TILE + TILE / 2);
    }

    /** A critter of the neutral player's, standing inside the viewport. */
    private static Unit critter(Scene scene, int fromColumn) {
        UnitType type = scene.data().unitTypes().types().get("unit-critter");
        assertNotNull(type, "the shipped data has a critter");
        for (int x = fromColumn; x < WIDTH / TILE - 2; x++) {
            for (int y = 2; y < HEIGHT / TILE - 2; y++) {
                Unit made = scene.world().createUnit(type, 0, x, y);
                if (made != null) {
                    return made;
                }
            }
        }
        Assumptions.assumeTrue(false, "nowhere on this map to put a critter");
        return null;
    }

    @Test
    @DisplayName("the tenth consecutive click on a critter kills it")
    void tenClicksExplodeIt() {
        Scene scene = scene();
        Unit sheep = critter(scene, 2);
        UnitType type = sheep.type();

        assertEquals(10, (int) Double.parseDouble(
                        String.valueOf(type.rawProperties().get("ClicksToExplode"))),
                "the shipped critter declares ten clicks");

        for (int click = 1; click < 10; click++) {
            clickOn(scene.screen(), sheep);
            // Nine is not ten. A counter off by one here would be invisible in
            // play and is the most likely way to get this wrong.
            assertTrue(sheep.isAlive(),
                    "the critter blew up on click " + click + " of ten");
        }

        clickOn(scene.screen(), sheep);
        // The blow travels as the critter's own missile, so it lands on the
        // next step rather than on the click.
        for (int i = 0; i < 60 && sheep.isAlive(); i++) {
            scene.world().tick();
        }
        assertTrue(!sheep.isAlive(), "ten clicks on a critter left it standing");
    }

    @Test
    @DisplayName("clicking something else in between starts the count again")
    void thecountResetsOnAnotherUnit() {
        Scene scene = scene();
        Unit first = critter(scene, 2);
        Unit second = critter(scene, 10);
        Assumptions.assumeTrue(first != second && second != null);

        // Nine on the first, then one on the second, then nine more on the
        // first. Eighteen clicks on the first sheep, none of them the tenth in
        // a row, so it must still be standing.
        for (int click = 0; click < 9; click++) {
            clickOn(scene.screen(), first);
        }
        clickOn(scene.screen(), second);
        for (int click = 0; click < 9; click++) {
            clickOn(scene.screen(), first);
        }
        for (int i = 0; i < 60; i++) {
            scene.world().tick();
        }
        assertTrue(first.isAlive(),
                "the count carried across a click on another unit: IsOnlySelected is the whole"
                        + " point of upstream's counter");
        assertTrue(second.isAlive(), "the second critter should be untouched");
    }
}
