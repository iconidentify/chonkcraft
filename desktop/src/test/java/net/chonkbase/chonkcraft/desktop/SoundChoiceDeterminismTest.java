package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Two machines watching the same battle from different places stay in step.
 *
 * <p>Sound selection used to draw from {@code World.syncRand}, and several of
 * the branches it drew from are local by definition:
 * {@code isOnScreen(unit) && isUnitVisible(unit)} asks about this machine's
 * camera and this machine's fog, and {@code unit.player() != localPlayer} asks
 * who is sitting here. In lockstep every machine must consume the synchronised
 * generator the same number of times in the same order, so a death heard on one
 * screen and off the other put the two games on different numbers from that
 * cycle -- and the next thing to disagree was a damage roll, not a sound.
 *
 * <p>Upstream uses no random number here at all. {@code SimpleChooseSample}
 * The game is {@code FrameCounter % sound.Number}.
 *
 * <p>The fixture is two worlds run from the same seed over the same cycles,
 * watched by two screens whose cameras are pointed at different places. The
 * generator must end on the same number for both. The second assertion is what
 * stops that being vacuous: the two screens must actually have chosen a
 * different number of sounds, or the cameras were not far enough apart for the
 * locally-gated branch to be taken on one and not the other, and the first
 * assertion would hold for a fixture that could not tell the rules apart.
 */
class SoundChoiceDeterminismTest {

    private static final String MAP = "campaigns/human/level02h";
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    private static final int TILE = 32;

    private record Peer(GameScreen screen, World world) {}

    private static GameData data() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    /** One machine's copy of the same game. */
    private static Peer peer(GameData data) {
        PudMap pud = data.campaignMap(MAP);
        Assumptions.assumeTrue(pud != null, "no campaign map available");
        var tileset = data.loadTileset(pud.tileset());
        World world = new World(GameMap.from(pud, tileset.tileset()), Player.from(pud));
        world.setUnitTypes(data.unitTypes().types());
        world.setUpgrades(data.upgrades().upgrades());
        world.setSpells(data.spells().spells());
        world.setMissileTypes(data.missiles().types());
        world.fog().revealAll(0);
        String tilesetName = pud.tileset() == PudMap.Tileset.FOREST
                ? "summer"
                : pud.tileset().name().toLowerCase(java.util.Locale.ROOT);
        CommandApplier applier = new CommandApplier(world,
                new ArrayList<>(data.unitTypes().types().values()));
        GameScreen screen = new GameScreen(world, data,
                new BufferedImage(TILE * 64, TILE * 64, BufferedImage.TYPE_INT_RGB),
                tileset.palette(), tilesetName, 0, WIDTH, HEIGHT,
                new net.chonkbase.chonkcraft.engine.sound.GameAudio(data.sounds()),
                null, null, applier, CommandSink.local(applier),
                java.util.List.of(), "human");
        screen.setSize(WIDTH, HEIGHT);
        screen.setLayout((net.chonkbase.chonkcraft.engine.ui.UiLayout.Layout) null);
        screen.setGameScale(1);
        return new Peer(screen, world);
    }

    /** Stands a fight up at the same two squares in whichever world is given. */
    private static int[] stageTheFight(World world, UnitType footman) {
        Unit mine = null;
        Unit theirs = null;
        for (int y = 3; y < 24 && theirs == null; y++) {
            for (int x = 3; x < 24 && theirs == null; x++) {
                if (mine == null) {
                    mine = world.createUnit(footman, 0, x, y);
                    continue;
                }
                theirs = world.createUnit(footman, 1, x, y);
            }
        }
        if (mine == null || theirs == null) {
            return null;
        }
        world.orderAttack(mine, theirs);
        world.orderAttack(theirs, mine);
        return new int[] {mine.tileX(), mine.tileY()};
    }

    private static void click(GameScreen screen, int x, int y, boolean right) {
        java.awt.event.MouseEvent event = new java.awt.event.MouseEvent(screen,
                java.awt.event.MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                right ? java.awt.event.InputEvent.BUTTON3_DOWN_MASK
                        : java.awt.event.InputEvent.BUTTON1_DOWN_MASK,
                x, y, 1, false,
                right ? java.awt.event.MouseEvent.BUTTON3
                        : java.awt.event.MouseEvent.BUTTON1);
        for (var listener : screen.getMouseListeners()) {
            listener.mousePressed(event);
        }
    }

    @Test
    @DisplayName("a battle heard on one screen and not the other leaves both on the same number")
    void theGeneratorDoesNotFollowTheCamera() {
        GameData data = data();
        UnitType footman = data.unitTypes().types().get("unit-footman");
        assertNotNull(footman, "the shipped data has a footman");

        Peer watching = peer(data);
        Peer lookingAway = peer(data);

        int[] fight = stageTheFight(watching.world(), footman);
        int[] same = stageTheFight(lookingAway.world(), footman);
        Assumptions.assumeTrue(fight != null && same != null, "nowhere to stage a fight");
        assertEquals(fight[0] + "," + fight[1], same[0] + "," + same[1],
                "the two copies of the game put the fight in different places");

        // One camera on the fight, the other in the far corner. Nothing else
        // about the two games differs.
        watching.screen().centreOn(fight[0], fight[1]);
        lookingAway.screen().centreOn(
                watching.world().map().width() - 2, watching.world().map().height() - 2);

        for (int cycle = 0; cycle < 900; cycle++) {
            watching.world().tick();
            watching.screen().playAnnouncements();
            lookingAway.world().tick();
            lookingAway.screen().playAnnouncements();
        }

        assertEquals(watching.world().randomDraws(), lookingAway.world().randomDraws(),
                "the machine watching the fight drew "
                        + watching.world().randomDraws() + " times and the one looking away "
                        + lookingAway.world().randomDraws() + ": sound selection is reaching"
                        + " the synchronised generator from a branch gated on the camera,"
                        + " which is a desync over a sound effect");

        // And the fixture could have shown it. Sounds were chosen, and the two
        // cameras chose a different number of them, so the branch that used to
        // draw really was taken on one machine and not the other.
        long heard = watching.screen().soundChoicesForTest();
        long missed = lookingAway.screen().soundChoicesForTest();
        assertTrue(heard > 0,
                "no sound was chosen at all on the screen watching the fight, so this"
                        + " fixture cannot tell the two rules apart");
        assertTrue(heard != missed,
                "both screens chose " + heard + " sounds, so the camera made no difference"
                        + " and the locally-gated branch was never the thing under test");
    }

    @Test
    @DisplayName("clicking about the map never moves the simulation's generator")
    void clickingIsFree() {
        GameData data = data();
        UnitType footman = data.unitTypes().types().get("unit-footman");
        Peer peer = peer(data);
        World world = peer.world();

        java.util.List<Unit> squad = new ArrayList<>();
        for (int y = 3; y < 12 && squad.size() < 3; y++) {
            for (int x = 3; x < 12 && squad.size() < 3; x++) {
                Unit made = world.createUnit(footman, 0, x, y);
                if (made != null) {
                    squad.add(made);
                }
            }
        }
        Assumptions.assumeTrue(squad.size() == 3, "nowhere to stand three footmen");

        long draws = world.randomDraws();
        // Through the mouse, because that is the path that draws: a unit
        // answers when it is clicked and again when it is ordered.
        for (Unit unit : squad) {
            click(peer.screen(), unit.tileX() * TILE + TILE / 2,
                    unit.tileY() * TILE + TILE / 2, false);
            click(peer.screen(), (unit.tileX() + 4) * TILE + TILE / 2,
                    (unit.tileY() + 4) * TILE + TILE / 2, true);
        }
        // Selecting is a sound too, and it is the one with the pestering
        // counter behind it: three clicks on the same unit and it starts
        // grumbling, which used to be three more draws.
        Unit first = squad.get(0);
        for (int again = 0; again < 6; again++) {
            click(peer.screen(), first.tileX() * TILE + TILE / 2,
                    first.tileY() * TILE + TILE / 2, false);
        }

        assertTrue(peer.screen().soundChoicesForTest() > 0,
                "no sound was chosen, so nothing here could have drawn either");
        assertEquals(draws, world.randomDraws(),
                "an order given with the mouse moved the synchronised generator on");
    }
}
