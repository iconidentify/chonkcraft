package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A unit is trained where its button says, and nowhere else.
 *
 * <p>The {@code train-unit} buttons are the whole of the relation, which is
 * how upstream knows it too: {@code AiHelpers.Train()} is built from them and
 * {@code AiTrainUnit} offers a training nowhere else. This implementation's
 * {@code World.orderTrain} asked nothing about the pair, so an AI walking its
 * buildings for a willing trainer took the first idle one whatever it was: on
 * {@code campaigns/human/level05h} the enemy's oil tanker was trained at a
 * pig farm while the shipyard the button names stood idle beside the water,
 * and the pig farm's bank entry was the mission's first divergence.
 */
class TrainerTableTest {

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

    private static UnitType tanker() {
        UnitType type = new UnitType("unit-oil-tanker");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(90);
        type.setSpeed(10);
        type.setSeaUnit(true);
        return type;
    }

    @Test
    @DisplayName("a pig farm does not train an oil tanker, and a shipyard does")
    void theButtonTableDecidesWhoTrains() {
        World world = new World(grass(30));
        world.setTrainers(Map.of("unit-oil-tanker", Set.of("unit-shipyard")));
        Unit farm = world.createUnit(building("unit-pig-farm"), 0, 5, 5);
        Unit shipyard = world.createUnit(building("unit-shipyard"), 0, 15, 15);
        world.player(0).add(net.chonkbase.chonkcraft.engine.unit.UnitType.Resource.GOLD, 1000);

        assertFalse(world.orderTrain(farm, tanker()),
                "the pig farm took the tanker. The train-unit buttons are the whole of"
                        + " the relation and no button trains a tanker at a farm; an AI"
                        + " walking its buildings for a willing trainer must be refused"
                        + " here or the shipyard stands idle while the farm works");
        assertTrue(world.orderTrain(shipyard, tanker()),
                "the shipyard refused the tanker its own button trains");
    }

    @Test
    @DisplayName("and a world with no table keeps training, so fixtures stay simple")
    void anEmptyTableAsksNothing() {
        World world = new World(grass(30));
        Unit farm = world.createUnit(building("unit-pig-farm"), 0, 5, 5);
        world.player(0).add(net.chonkbase.chonkcraft.engine.unit.UnitType.Resource.GOLD, 1000);

        assertTrue(world.orderTrain(farm, tanker()),
                "an empty table means the question was never asked rather than answered"
                        + " no -- every hand-built fixture in the suite trains without one");
    }
}
