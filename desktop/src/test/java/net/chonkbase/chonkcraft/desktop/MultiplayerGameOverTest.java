package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.trigger.TriggerSystem;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The network result leaves the field available when the player asks. */
class MultiplayerGameOverTest {

    private static GameScreen screen() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null, "No authenticated BNE media configured");
        GameData data = new GameData(assets);
        PudMap source = data.campaignMap("campaigns/human/level01h");
        Assumptions.assumeTrue(source != null, "Human 1 is not in the pack");
        var tileset = data.loadTileset(source.tileset());
        World world = new World(GameMap.from(source, tileset.tileset()), Player.from(source));
        data.configureWorld(world, source);
        CommandApplier applier = new CommandApplier(world,
                new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(applier);
        GameScreen screen = new GameScreen(world, data,
                new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), tileset.palette(),
                "summer", 0, 800, 600, null, null, null, applier,
                CommandSink.local(applier), java.util.List.of(), "human");
        screen.setSize(800, 600);
        screen.setLayout((net.chonkbase.chonkcraft.engine.ui.UiLayout.Layout) null);
        screen.setGameScale(1);
        return screen;
    }

    private static void click(GameScreen screen, Rectangle target) {
        MouseEvent event = new MouseEvent(screen, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), 0,
                target.x + target.width / 2, target.y + target.height / 2,
                1, false, MouseEvent.BUTTON1);
        for (MouseListener listener : screen.getMouseListeners()) {
            listener.mousePressed(event);
        }
    }

    @Test
    @DisplayName("Keep Playing dismisses Game Over without quitting the match")
    void keepPlayingLeavesTheFieldRunning() {
        GameScreen screen = screen();
        AtomicBoolean quit = new AtomicBoolean();
        screen.setMultiplayerQuitAction(() -> quit.set(true));
        screen.setMultiplayerOutcome(TriggerSystem.Outcome.VICTORY);

        assertTrue(screen.hasMultiplayerOutcome());
        assertFalse(screen.multiplayerOutcomeDismissedForTest());
        click(screen, screen.multiplayerKeepPlayingBounds());

        assertTrue(screen.multiplayerOutcomeDismissedForTest());
        assertFalse(quit.get(), "Keep Playing took the Quit Game path");
    }

    @Test
    @DisplayName("Quit Game takes the ordinary synchronized game exit")
    void quitGameUsesTheProvidedSessionExit() {
        GameScreen screen = screen();
        AtomicBoolean quit = new AtomicBoolean();
        screen.setMultiplayerQuitAction(() -> quit.set(true));
        screen.setMultiplayerOutcome(TriggerSystem.Outcome.DEFEAT);

        click(screen, screen.multiplayerQuitBounds());

        assertTrue(quit.get());
        assertTrue(screen.multiplayerOutcomeDismissedForTest());
    }
}
