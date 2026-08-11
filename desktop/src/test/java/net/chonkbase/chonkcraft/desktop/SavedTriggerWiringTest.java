package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import net.chonkbase.chonkcraft.engine.save.LoadGame;
import net.chonkbase.chonkcraft.engine.save.SaveGame;
import net.chonkbase.chonkcraft.engine.trigger.TriggerSystem;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A trigger that has already fired stays fired across a save.
 *
 * <p>The engine side of this was built and tested: {@code TriggerSystem} numbers
 * its triggers by where the mission script installed them,
 * {@code armedTriggers} says which are left, {@code retainArmed} puts a save's
 * list back, and {@code SaveGame} and {@code LoadGame} carry it. None of it was
 * connected. {@code GameScreen.saveGame} called the overload that writes no
 * trigger list at all, and {@code Main.resume} never called
 * {@code retainArmed}, so resuming a campaign reran the mission script, armed
 * every trigger a second time, and delivered the same reinforcements or
 * repeated the same objective the player had already passed.
 *
 * <p>Wiring that nothing exercises is how several of the faults in this
 * backlog survived, so this drives the two ends: the save is written by
 * {@code GameScreen.saveGame} rather than by {@code SaveGame.write}, and the
 * reload does what {@code Main.resume} does -- rerun the script, then hand the
 * list back.
 */
class SavedTriggerWiringTest {

    private static final String MAP = "campaigns/human/level02h";
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    private static final int TILE = 32;

    private record Scene(GameScreen screen, World world, GameData data) {}

    private static GameData data() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No authenticated Warcraft II asset source configured.");
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
        world.setSpells(data.spells().spells());
        world.setMissileTypes(data.missiles().types());
        String tilesetName = pud.tileset() == PudMap.Tileset.FOREST
                ? "summer"
                : pud.tileset().name().toLowerCase(java.util.Locale.ROOT);
        CommandApplier applier = new CommandApplier(world,
                new ArrayList<>(data.unitTypes().types().values()));
        GameScreen screen = new GameScreen(world, data,
                new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_RGB),
                tileset.palette(), tilesetName, 0, WIDTH, HEIGHT,
                new net.chonkbase.chonkcraft.engine.sound.GameAudio(data.sounds()),
                null, null, applier, CommandSink.local(applier),
                java.util.List.of(), "human");
        screen.setSize(WIDTH, HEIGHT);
        return new Scene(screen, world, data);
    }

    /** Arms three native one-shot triggers; the first decides the mission. */
    private static TriggerSystem arm(World world) {
        return new TriggerSystem(world, 0, List.of(
                new TriggerSystem.ProgramSpec("TRUE", "VICTORY"),
                new TriggerSystem.ProgramSpec("FALSE", "NOOP"),
                new TriggerSystem.ProgramSpec("FALSE", "NOOP")));
    }

    /** The file GameScreen.saveGame wrote, read back as its native document. */
    private static String saveAndRead(Scene scene, String campaign) throws Exception {
        scene.screen().setSaveContext(MAP, campaign, 99);
        String said = scene.screen().saveGame();
        Path file = GameScreen.saveDirectory()
                .resolve(campaign + "-mission-99" + SaveGame.SUFFIX);
        assertTrue(Files.isRegularFile(file), "saveGame said \"" + said + "\" and wrote nothing");
        try {
            return LoadGame.read(file);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    @DisplayName("resuming a campaign does not deliver the same reinforcements twice")
    void aFiredTriggerStaysFiredAcrossTheScreensOwnSave() throws Exception {
        Scene scene = scene();
        TriggerSystem before = arm(scene.world());

        before.evaluate();
        assertEquals(TriggerSystem.Outcome.VICTORY, before.outcome(),
                "the first trigger should have fired once by now");
        assertEquals(List.of(1, 2), before.armedTriggers(),
                "the fired trigger is still armed, so there is nothing here to carry");

        scene.screen().setTriggers(before);
        String script = saveAndRead(scene, "trigger-wiring-test");

        List<Integer> armed = LoadGame.armedTriggers(script);
        assertNotNull(armed,
                "GameScreen.saveGame wrote no trigger list, so a reload cannot tell which"
                        + " triggers the player had already used");
        assertEquals(List.of(1, 2), armed, "the save named the wrong triggers as armed");

        // What Main.resume does: rerun the mission script, which arms all
        // three again, and then hand back the list the save carried.
        World second = scene().world();
        TriggerSystem after = arm(second);
        assertEquals(3, after.triggerCount(),
                "rerunning the script should have armed all three again, or this proves"
                        + " nothing about retainArmed");
        after.retainArmed(armed);
        after.evaluate();

        assertEquals(TriggerSystem.Outcome.RUNNING, after.outcome(),
                "the already-used outcome trigger fired again after reload");
    }

    @Test
    @DisplayName("a save written before any of this said nothing, and still means nothing")
    void aSaveWithNoTriggerListLeavesTheMissionAsItsScriptBuiltIt() throws Exception {
        // The control. A screen with no trigger system handed to it writes the
        // old shape of save, and reloading one re-arms everything -- which is
        // exactly the behaviour the test above would show if the wiring came
        // out again, so it also proves that assertion is not passing on its
        // own.
        Scene scene = scene();
        TriggerSystem before = arm(scene.world());
        before.evaluate();
        assertEquals(TriggerSystem.Outcome.VICTORY, before.outcome());

        String script = saveAndRead(scene, "trigger-wiring-control");
        assertNull(LoadGame.armedTriggers(script),
                "a save written with no trigger system should name no triggers");

        World second = scene().world();
        TriggerSystem after = arm(second);
        after.retainArmed(LoadGame.armedTriggers(script));
        after.evaluate();
        assertEquals(TriggerSystem.Outcome.VICTORY, after.outcome(),
                "a save with no trigger list must re-arm every trigger");
    }
}
