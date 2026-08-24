package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.ui.UiLayout;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The BNE wall command belongs to network games, not the debug build. */
class MultiplayerWallButtonTest {

    private record Fixture(GameData data, World world, Unit worker, UiLayout.Layout layout,
            String race) {}

    private static Fixture fixture(String race, String workerIdent) {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set -Dchonkcraft.pack=... or wc2.install.dir");
        GameData data = new GameData(assets);
        GameMap map = new GameMap(12, 12, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map);
        data.configureWorld(world, PudMap.Tileset.FOREST);
        UnitType type = data.unitTypes().types().get(workerIdent);
        assertNotNull(type, "the authenticated BNE catalog must contain " + workerIdent);
        Unit worker = world.createUnit(type, 0, 4, 4);
        assertNotNull(worker, "the worker fixture must fit on its open field");
        worker.setSelected(true);
        UiLayout.Layout layout = data.uiLayout(race, 640, 480);
        assertNotNull(layout, "the authenticated BNE interface layout must be available");
        return new Fixture(data, world, worker, layout, race);
    }

    private static CommandPanel panel(Fixture fixture, boolean networked) {
        return new CommandPanel(fixture.world(), fixture.data(),
                fixture.data().userInterface("summer"),
                fixture.data().upgrades().dependencies(), 0, "summer", fixture.race(),
                fixture.data().unitTypes().types(), fixture.layout(), networked);
    }

    private static java.util.List<String> buildPage(Fixture fixture, boolean networked) {
        CommandPanel panel = panel(fixture, networked);
        panel.setLevel(1);
        BufferedImage frame = new BufferedImage(640, 480, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = frame.createGraphics();
        try {
            panel.draw(graphics, fixture.worker());
        } finally {
            graphics.dispose();
        }
        return panel.describeForTest();
    }

    @Test
    @DisplayName("both races can build BNE walls in multiplayer but not single player")
    void multiplayerShowsTheNetworkOnlyWallCommand() {
        Fixture human = fixture("human", "unit-peasant");
        Fixture orc = fixture("orc", "unit-peon");

        assertFalse(buildPage(human, false).contains("build:unit-human-wall"),
                "BNE's network-only wall command leaked into a single-player game");
        assertTrue(buildPage(human, true).contains("build:unit-human-wall"),
                "the multiplayer build page hid BNE's wall command from the player");
        assertFalse(buildPage(orc, false).contains("build:unit-orc-wall"),
                "BNE's orc wall command leaked into a single-player game");
        assertTrue(buildPage(orc, true).contains("build:unit-orc-wall"),
                "the multiplayer build page hid BNE's orc wall command from the player");
    }
}
