package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A click on a ship moored over an oil patch lands on the ship.
 *
 * <p>Reported from play the day sailing over patches became possible: "I
 * cannot directly click units who are on the slick -- I had to select with
 * the band box." The click resolver walked the roster in creation order and
 * stopped at the first box containing the point, and the patch, placed by
 * the map, sits earlier in the roster than any ship a player builds -- so
 * the patch took every first click, under a box ninety-five pixels square
 * that the whole ship fits inside.
 *
 * <p>LegacyEngine's {@code UnitOnScreen} has the same walk, and its own comment
 * disagrees with it: "More units on same position ... First take highest
 * unit." The original game sides with the comment -- the ship above the
 * slick always takes the click -- so the resolver now reads its hits from
 * the top of the draw order down, which is the documented deviation from
 * the letter of the walk. The cycle survives: clicking the thing already
 * selected hands back what is underneath it, so the patch under a selected
 * ship is still one click away, and the amount of oil in it still readable.
 */
class StackedClickTest {

    private static GameData load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    @Test
    @DisplayName("the ship above the slick takes the click, and a second click reads the oil")
    void theShipAboveTheSlickTakesTheClick() {
        GameData data = load();
        Mission mission = data.loadMission("campaigns/human/level03h");
        Assumptions.assumeTrue(mission != null, "no campaign map available");
        World world = mission.world();
        world.fog().revealAll(0);

        Unit patch = null;
        for (Unit unit : world.units()) {
            if (unit.type() != null && "unit-oil-patch".equals(unit.type().ident())) {
                patch = unit;
                break;
            }
        }
        assertNotNull(patch, "the third human mission has no oil patch");

        UnitType tankerType = data.unitTypes().types().get("unit-human-oil-tanker");
        Unit tanker = world.createUnit(tankerType, 0, patch.tileX() + 1, patch.tileY() + 1);
        assertNotNull(tanker, "the patch's own squares stopped taking a ship,"
                + " which is the occupancy fix coming undone");

        // Squarely on the ship, which is also inside the patch's 95-pixel box
        // -- the fixture is only a fixture if both boxes contain the point.
        int clickX = tanker.pixelX() + 16;
        int clickY = tanker.pixelY() + 16;

        assertEquals(tanker, world.unitAtPixel(clickX, clickY, null),
                "the first click on a ship moored over an oil patch went to the patch:"
                        + " the resolver takes the first hit in roster order where the"
                        + " game takes the highest drawn");

        for (Unit unit : world.units()) {
            unit.setSelected(unit == tanker);
        }
        assertEquals(patch, world.unitAtPixel(clickX, clickY, null),
                "with the ship selected, the click did not cycle down to the patch,"
                        + " so the oil left in it can no longer be read at all");

        for (Unit unit : world.units()) {
            unit.setSelected(unit == patch);
        }
        assertEquals(tanker, world.unitAtPixel(clickX, clickY, null),
                "with the patch selected, the click did not cycle back up to the ship");
    }
}
