package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.unit.UnitType.Resource;
import net.chonkbase.chonkcraft.engine.upgrade.AllowState;
import net.chonkbase.chonkcraft.engine.upgrade.DependencyRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A typed train, research or build order still has to pass the mission.
 */
class AllowLegalityTest {

    @Test
    @DisplayName("a hall that can train a knight still refuses one the mission hid")
    void aHallThatCanTrainAKnightStillRefusesOneTheMissionHid() {
        World world = new World(grass(16));
        UnitType hallType = building("unit-town-hall", 4);
        hallType.setSupply(5);
        UnitType footman = soldier("unit-footman");
        UnitType knight = soldier("unit-knight");
        world.setTrainers(Map.of(
                "unit-footman", Set.of("unit-town-hall"),
                "unit-knight", Set.of("unit-town-hall")));
        AllowState allowed = new AllowState();
        allowed.define("unit-footman", "AAAAAAAAAAAAAAAA");
        allowed.define("unit-knight", "FFFFFFFFFFFFFFFF");
        world.setAllowed(allowed);

        Unit hall = world.createUnit(hallType, 0, 4, 4);
        world.player(0).set(Resource.GOLD, 10_000);

        assertEquals("forbidden", world.productionRefusal(0, "unit-knight"),
                "Human 1's allow string must name why the knight is refused");
        assertFalse(world.orderTrain(hall, knight),
                "the hall trained a knight the first mission has not taught");
        assertTrue(world.orderTrain(hall, footman),
                "the hall refused the footman the mission already allows");
    }

    @Test
    @DisplayName("a stables-gated knight is refused until the stables stands")
    void aStablesGatedKnightIsRefusedUntilTheStablesStands() {
        World world = new World(grass(24));
        UnitType hallType = building("unit-town-hall", 4);
        hallType.setSupply(5);
        UnitType stablesType = building("unit-stables", 3);
        UnitType knight = soldier("unit-knight");
        world.setTrainers(Map.of("unit-knight", Set.of("unit-town-hall")));
        DependencyRules tree = new DependencyRules();
        tree.define("unit-knight", List.of(List.of("unit-stables")));
        world.setDependencies(tree);

        Unit hall = world.createUnit(hallType, 0, 4, 4);
        world.player(0).set(Resource.GOLD, 10_000);

        assertEquals("unmet-dependency", world.productionRefusal(0, "unit-knight"),
                "the tree must name why the knight is still locked");
        assertFalse(world.orderTrain(hall, knight),
                "the hall trained a knight before its stables existed");
        world.createUnit(stablesType, 0, 12, 4);
        assertTrue(world.orderTrain(hall, knight),
                "the hall still refused the knight after the stables went up");
    }

    @Test
    @DisplayName("a peasant cannot found a barracks the mission hid")
    void aPeasantCannotFoundABarracksTheMissionHid() {
        World world = new World(grass(16));
        UnitType peasant = new UnitType("unit-peasant");
        peasant.setTileSize(1, 1);
        peasant.setHitPoints(30);
        peasant.setLandUnit(true);
        UnitType barracks = building("unit-human-barracks", 3);
        barracks.costs().put(Resource.GOLD, 700);
        barracks.costs().put(Resource.WOOD, 450);
        world.setBuilders(Map.of("unit-human-barracks", Set.of("unit-peasant")));
        AllowState allowed = new AllowState();
        allowed.define("unit-human-barracks", "FFFFFFFFFFFFFFFF");
        world.setAllowed(allowed);

        Unit worker = world.createUnit(peasant, 0, 2, 2);
        world.player(0).set(Resource.GOLD, 10_000);
        world.player(0).set(Resource.WOOD, 10_000);

        assertEquals("forbidden", world.productionRefusal(0, "unit-human-barracks"));
        assertFalse(world.orderBuild(worker, barracks, 6, 6),
                "the peasant founded a barracks Human 1 has not taught");
    }

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static UnitType building(String ident, int size) {
        UnitType type = new UnitType(ident);
        type.setTileSize(size, size);
        type.setHitPoints(800);
        type.setBuilding(true);
        return type;
    }

    private static UnitType soldier(String ident) {
        UnitType type = new UnitType(ident);
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setDemand(1);
        type.costs().put(Resource.GOLD, 600);
        type.costs().put(Resource.TIME, 60);
        return type;
    }
}
