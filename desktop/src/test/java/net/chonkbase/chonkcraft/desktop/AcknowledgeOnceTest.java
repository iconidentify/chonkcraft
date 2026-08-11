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

/**
 * One unit answers an order, however many were told.
 *
 * <p>{@code DoRightButton} declares {@code int acknowledged = 0; // to play
 * sound} before its selection loop and passes it by reference through all
 * twenty-six branches, so the first unit that actually takes the order speaks
 * and the rest obey in silence. This implementation called
 * {@code playUnit(..., "acknowledge")} inside the loop, so ten footmen told to
 * move all shouted at once. With one unit selected it was right, and one unit
 * selected is how most testing happens.
 *
 * <p>Counting sounds on a headless machine is not possible directly -- there
 * is no device and nothing is mixed. It is not necessary either. Every unit
 * voice asks exactly once which of its clips to play, deliberately and
 * unconditionally, before any early return: see {@code GameAudio.choose}.
 * {@code GameScreen.soundChoicesForTest} counts those questions, so it counts
 * the voices. Four footmen given one order must produce exactly one, and at
 * the same time all four must actually have the order -- otherwise "one
 * choice" would be satisfied by three units ignoring the click.
 *
 * <p>That counter used to be {@code World.randomDraws}, because the chooser
 * used to be {@code World.syncRand}. It is not any more, and the first test
 * below checks that too: a sound chosen off the synchronised generator inside
 * a branch gated on the local camera is how two machines end up on different
 * numbers.
 */
class AcknowledgeOnceTest {

    private static final String MAP = "campaigns/human/level02h";
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    private static final int TILE = 32;

    private record Scene(GameScreen screen, World world, GameData data) {}

    private static GameData data() {
        AssetSource source = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(source != null,
                "No authenticated BNE asset source configured.");
        return new GameData(source);
    }

    private static Scene scene() {
        GameData data = data();
        PudMap pud = data.campaignMap(MAP);
        Assumptions.assumeTrue(pud != null, "no campaign map available");
        var tileset = data.loadTileset(pud.tileset());
        World world = new World(GameMap.from(pud, tileset.tileset()), Player.from(pud));
        data.configureWorld(world, pud.tileset());
        world.fog().revealAll(0);
        String tilesetName = pud.tileset() == PudMap.Tileset.FOREST
                ? "summer"
                : pud.tileset().name().toLowerCase(java.util.Locale.ROOT);

        CommandApplier applier = new CommandApplier(world,
                new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(applier);

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

    /**
     * A square inside the viewport the squad can actually walk to.
     *
     * <p>Found by giving one of the squad the order for real and then taking
     * it back, which is the only reliable answer: terrain that merely holds a
     * unit is not enough, because a passable square across a lake is
     * unreachable, {@code orderMove} returns false, and the footman told to go
     * there stays {@code STILL} -- which would read as "the order was refused"
     * rather than "the test picked an island".
     */
    private static int[] walkableSquare(World world, List<Unit> squad) {
        Unit prober = squad.get(0);
        for (int x = WIDTH / TILE - 3; x > 2; x--) {
            for (int y = HEIGHT / TILE - 3; y > 2; y--) {
                boolean crowded = false;
                for (Unit unit : squad) {
                    if (Math.abs(unit.tileX() - x) < 3 && Math.abs(unit.tileY() - y) < 3) {
                        crowded = true;
                    }
                }
                if (crowded) {
                    continue;
                }
                boolean reachable = world.orderMove(prober, x, y)
                        && prober.order() == Unit.Order.MOVE;
                world.orderStop(prober);
                if (reachable) {
                    return new int[] {x, y};
                }
            }
        }
        return null;
    }

    private static void rightClick(GameScreen screen, int x, int y) {
        MouseEvent event = new MouseEvent(screen, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), InputEvent.BUTTON3_DOWN_MASK,
                x, y, 1, false, MouseEvent.BUTTON3);
        for (var listener : screen.getMouseListeners()) {
            listener.mousePressed(event);
        }
    }

    @Test
    @DisplayName("four footmen given one order make one acknowledgement between them")
    void exactlyOneUnitSpeaks() {
        Scene scene = scene();
        World world = scene.world();
        UnitType footman = scene.data().unitTypes().types().get("unit-footman");
        assertNotNull(footman, "the shipped data has a footman");

        List<Unit> squad = new ArrayList<>();
        for (int x = 2; x < WIDTH / TILE - 6 && squad.size() < 4; x++) {
            for (int y = 2; y < 8 && squad.size() < 4; y++) {
                Unit made = world.createUnit(footman, 0, x, y);
                if (made != null) {
                    made.setSelected(true);
                    squad.add(made);
                }
            }
        }
        Assumptions.assumeTrue(squad.size() == 4, "nowhere to stand four footmen");

        // Somewhere none of them is, and somewhere they can all walk to.
        // Found by standing a footman there and taking him away again, which
        // is the only question this test needs answered about the terrain:
        // whether a footman can be on it.
        int[] target = walkableSquare(world, squad);
        Assumptions.assumeTrue(target != null, "nowhere on screen for the squad to walk to");
        int targetX = target[0];
        int targetY = target[1];

        long before = scene.screen().soundChoicesForTest();
        long drawsBefore = world.randomDraws();
        rightClick(scene.screen(), targetX * TILE + TILE / 2, targetY * TILE + TILE / 2);

        // All four took the order. Without this the draw count below would be
        // satisfied by three of them ignoring the click entirely.
        for (Unit unit : squad) {
            assertEquals(Unit.Order.MOVE, unit.order(),
                    "a selected footman did not take the move order");
        }

        assertEquals(1, scene.screen().soundChoicesForTest() - before,
                "the sound chooser was asked " + (scene.screen().soundChoicesForTest() - before)
                        + " times, so that many footmen acknowledged one order: every unit voice"
                        + " chooses exactly once, and exactly one unit should have spoken");
        assertEquals(0, world.randomDraws() - drawsBefore,
                "an acknowledgement moved the simulation's synchronised generator on:"
                        + " SimpleChooseSample uses FrameCounter, not a random number, and a"
                        + " draw taken here happens on one machine's camera and not another's");
    }

    @Test
    @DisplayName("a single unit still answers")
    void oneUnitStillSpeaks() {
        Scene scene = scene();
        World world = scene.world();
        UnitType footman = scene.data().unitTypes().types().get("unit-footman");

        Unit lone = null;
        for (int x = 2; x < WIDTH / TILE - 6 && lone == null; x++) {
            for (int y = 2; y < 8 && lone == null; y++) {
                lone = world.createUnit(footman, 0, x, y);
            }
        }
        Assumptions.assumeTrue(lone != null, "nowhere to stand a footman");
        lone.setSelected(true);

        int[] target = walkableSquare(world, List.of(lone));
        Assumptions.assumeTrue(target != null, "nowhere on screen for the footman to walk to");
        long before = scene.screen().soundChoicesForTest();
        rightClick(scene.screen(), target[0] * TILE + TILE / 2, target[1] * TILE + TILE / 2);
        assertEquals(Unit.Order.MOVE, lone.order(), "the lone footman did not take the order");
        assertEquals(1, scene.screen().soundChoicesForTest() - before,
                "a lone footman must still answer its order");
    }

    @Test
    @DisplayName("a selection that takes no order says nothing")
    void nothingSpeaksWhenNothingObeys() {
        Scene scene = scene();
        World world = scene.world();
        long before = scene.screen().soundChoicesForTest();
        // Nothing selected at all, so no branch of the table runs.
        rightClick(scene.screen(), 5 * TILE, 5 * TILE);
        assertEquals(0, scene.screen().soundChoicesForTest() - before,
                "a sound was chosen with nothing selected to acknowledge anything");
    }
}
