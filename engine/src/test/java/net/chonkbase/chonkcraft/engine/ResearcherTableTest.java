package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.upgrade.UpgradeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An upgrade is researched where its button says, and nowhere else.
 *
 * <p>The sibling of {@link TrainerTableTest}, one button action over:
 * {@code AiHelpers.Research()} is built from the {@code research} buttons --
 * {@code upgrade-sword1} carries {@code ForUnit =
 * {"unit-human-blacksmith"}} -- and {@code AiCheckResearchRequests} offers a
 * research nowhere else. This implementation's AI walked its buildings for the first
 * that would accept, so player 2 on {@code campaigns/orc-exp/levelx04o}
 * researched its weapon upgrades at two pig farms while owning no blacksmith
 * at all; upstream's request stands unpayable until the blacksmith exists,
 * and its pig farms were still pig farms at cycle 39.
 */
class ResearcherTableTest {

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static UnitType building(String ident) {
        UnitType type = new UnitType(ident);
        type.setTileSize(3, 3);
        type.setBoxSize(95, 95);
        type.setHitPoints(400);
        type.setBuilding(true);
        return type;
    }

    private static World world() {
        World world = new World(grass(30));
        UpgradeSet upgrades = new UpgradeSet();
        upgrades.getOrCreate("upgrade-sword1");
        world.setUpgrades(upgrades);
        world.player(0).add(UnitType.Resource.GOLD, 5000);
        return world;
    }

    @Test
    @DisplayName("a pig farm does not research swords, and a blacksmith does")
    void theButtonTableDecidesWhoResearches() {
        World world = world();
        world.setResearchers(Map.of("upgrade-sword1", Set.of("unit-human-blacksmith")));
        Unit farm = world.createUnit(building("unit-pig-farm"), 0, 5, 5);
        Unit smith = world.createUnit(building("unit-human-blacksmith"), 0, 15, 15);

        assertFalse(world.orderResearch(farm, "upgrade-sword1"),
                "the pig farm took the research. The research buttons are the whole"
                        + " of the relation and swords are a blacksmith's business; an"
                        + " AI whose blacksmith does not exist yet must be left with a"
                        + " standing request, not a working farm");
        assertTrue(world.orderResearch(smith, "upgrade-sword1"),
                "the blacksmith refused the research its own button carries");
    }

    @Test
    @DisplayName("and a world with no table keeps researching, so fixtures stay simple")
    void anEmptyTableAsksNothing() {
        World world = world();
        Unit farm = world.createUnit(building("unit-pig-farm"), 0, 5, 5);

        assertTrue(world.orderResearch(farm, "upgrade-sword1"),
                "an empty table means the question was never asked rather than"
                        + " answered no -- every hand-built fixture researches without"
                        + " one");
    }

    @Test
    @DisplayName("a blacksmith reads still for the rest of the cycle it was told")
    void theResearchLabelWaitsACycle() {
        World world = world();
        Unit smith = world.createUnit(building("unit-human-blacksmith"), 0, 5, 5);

        assertTrue(world.orderResearch(smith, "upgrade-sword1"), "the order was refused");
        assertTrue(smith.reportsActionBeforeQueued(),
                "the blacksmith answered for its new work at once. CommandResearch is"
                        + " a queued command like training -- the building reads still"
                        + " for the rest of the cycle and research from the next --"
                        + " and ten missions' first finding was this one label, at the"
                        + " right building on the right thought with the right money"
                        + " already paid");
        assertEquals(Unit.Order.STILL, smith.currentAction(),
                "and what it reports is what it was doing before the command");
    }
}
