package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which backdrop the info panel shows.
 *
 * <p>{@code ui/human/infopanel} is 176 by 704: four frames stacked, not one
 * picture. Drawing the sheet whole paints four panels down the sidebar, buries
 * the button panel under them and leaves the command grid sitting on the
 * second. The frame is chosen the way
 * {@code InfoPanel_draw_single_selection} chooses it.
 */
class InfoPanelFrameTest {

    private static GameData load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    @Test
    @DisplayName("the backdrop follows what is selected")
    void theFrameMatchesTheSelection() {
        GameData data = load();
        var pud = data.campaignMap("campaigns/human/level02h");
        Assumptions.assumeTrue(pud != null, "no campaign map available");
        var tileset = data.loadTileset(pud.tileset());
        World world = new World(GameMap.from(pud, tileset.tileset()), Player.from(pud));
        world.setUpgrades(data.upgrades().upgrades());
        world.setSpells(data.spells().spells());
        world.setMissileTypes(data.missiles().types());
        var types = data.unitTypes().types();
        SidePanel panel = new SidePanel(world, data, 0, "human", "winter",
                data.uiLayout("human", 640, 480));

        // Nothing selected: the empty backdrop.
        assertEquals(0, panel.infoPanelFrame(null));

        Unit footman = world.createUnit(types.get("unit-footman"), 0, 6, 16);
        Unit mage = world.createUnit(types.get("unit-mage"), 0, 8, 16);
        Unit hall = world.createUnit(types.get("unit-town-hall"), 0, 12, 16);
        Unit enemy = world.createUnit(types.get("unit-footman"), 1, 20, 16);

        assertEquals(1, panel.infoPanelFrame(footman), "a plain unit of your own");
        assertEquals(2, panel.infoPanelFrame(mage),
                "a caster, whose backdrop has the mana bar cut into it");
        assertEquals(0, panel.infoPanelFrame(enemy), "another player's unit");

        // A building at work takes the fourth, and being under construction
        // counts as at work.
        for (int i = 0; i < 3; i++) {
            world.createUnit(types.get("unit-farm"), 0, 6 + i * 3, 22);
        }
        world.player(0).set(UnitType.Resource.GOLD, 5000);
        world.recalculateSupply();
        assertEquals(1, panel.infoPanelFrame(hall), "an idle building is still a plain unit");
        world.orderTrain(hall, types.get("unit-peasant"));
        assertEquals(3, panel.infoPanelFrame(hall), "a building training something");
    }
}
