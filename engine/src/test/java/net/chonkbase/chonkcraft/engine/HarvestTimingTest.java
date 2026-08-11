package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.TreeMap;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Felling a tree takes as long as Warcraft II says it does.
 *
 * <p>A peasant's wood entry declares a capacity of a hundred, a step of two and
 * a wait of twenty-four cycles. That is fifty swings and forty seconds, and the
 * square it is working loses two wood a swing so the tree comes down as it is
 * felled.
 *
 * <p>The implementation used to hand over a full load and clear the square on the first
 * touch. The data had been parsed since the beginning -- step, wait and the
 * square's own value were all there -- but gathering was modelled as a trip
 * rather than a repeated action, so none of it had anywhere to be used.
 */
class HarvestTimingTest {

    private static GameData load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    private record Field(World world, GameMap map, Unit peasant) {}

    private static Field mission() {
        GameData data = load();
        PudMap pud = data.campaignMap("campaigns/human/level01h");
        Assumptions.assumeTrue(pud != null, "no campaign map available");
        GameMap map = GameMap.from(pud, data.loadTileset(pud.tileset()).tileset());
        World world = new World(map, Player.from(pud));
        world.setUpgrades(data.upgrades().upgrades());
        world.setMissileTypes(data.missiles().types());
        data.populate(world, pud);
        Unit peasant = null;
        for (Unit unit : world.unitsSnapshot()) {
            if ("unit-peasant".equals(unit.type().ident())) {
                peasant = unit;
            }
        }
        Assumptions.assumeTrue(peasant != null, "no peasant on this map");
        return new Field(world, map, peasant);
    }

    private static int[] nearestForest(GameMap map, Unit from) {
        int bestX = -1;
        int bestY = -1;
        int best = Integer.MAX_VALUE;
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                if (!map.field(x, y).isForest()) {
                    continue;
                }
                int distance = Math.abs(x - from.tileX()) + Math.abs(y - from.tileY());
                if (distance < best) {
                    best = distance;
                    bestX = x;
                    bestY = y;
                }
            }
        }
        return new int[] {bestX, bestY};
    }

    @Test
    @DisplayName("a forest square starts with wood in it")
    void forestSquaresHoldWood() {
        Field field = mission();
        int[] tree = nearestForest(field.map(), field.peasant());
        assertEquals(GameMap.WOOD_PER_FOREST_TILE, field.map().field(tree[0], tree[1]).value(),
                "a square with no wood in it is felled by the first swing");
    }

    @Test
    @DisplayName("a full load of wood takes forty seconds of chopping")
    void aLoadTakesFortySeconds() {
        Field field = mission();
        Unit peasant = field.peasant();
        var wood = peasant.type().gathering().get(UnitType.Resource.WOOD);
        assertNotNull(wood);
        assertEquals(100, wood.capacity());
        assertEquals(2, wood.step());
        assertEquals(24, wood.waitAtResource());

        int[] tree = nearestForest(field.map(), peasant);
        assertTrue(field.world().orderHarvest(peasant, tree[0], tree[1]));

        int firstWood = -1;
        int full = -1;
        for (int cycle = 1; cycle <= World.CYCLES_PER_SECOND * 120; cycle++) {
            field.world().tick();
            if (firstWood < 0 && peasant.carried() > 0) {
                firstWood = cycle;
            }
            if (peasant.carried() >= wood.capacity()) {
                full = cycle;
                break;
            }
        }
        assertTrue(firstWood > 0, "the peasant never picked up any wood");
        assertTrue(full > 0, "the peasant never filled up");

        // Fifty steps of twenty-four cycles is 1200, plus the walk out. The
        // walk is short on this map; the point is that it is not instant and
        // not far off forty seconds of work.
        int chopping = full - firstWood;
        assertTrue(chopping > World.CYCLES_PER_SECOND * 30,
                "chopping took only " + chopping + " cycles, which is not felling a tree");
        assertTrue(chopping < World.CYCLES_PER_SECOND * 50,
                "chopping took " + chopping + " cycles, which is longer than the data allows");

    }

    @Test
    @DisplayName("a forest tile keeps the PUD's explicit forty value")
    void aForestTileKeepsItsEncodedValue() {
        GameData data = load();
        PudMap pud = data.campaignMap("campaigns/human-exp/levelx10h");
        Assumptions.assumeTrue(pud != null, "no expansion campaign map available");
        GameMap map = GameMap.from(pud, data.loadTileset(pud.tileset()).tileset());

        assertTrue(map.field(62, 2).isForest(), "the overlap tile must remain a tree");
        assertEquals(40, PudMap.wallValue(pud.tileAt(62, 2)),
                "the PUD tile code carries the value pudconvert gives SetTile");
        assertEquals(40, map.field(62, 2).value(),
                "forest classification must not overwrite an explicit map value");
    }

    @Test
    @DisplayName("the square empties as it is worked and clears only when spent")
    void theTreeComesDownWhenItIsSpent() {
        Field field = mission();
        Unit peasant = field.peasant();
        int[] tree = nearestForest(field.map(), peasant);
        var square = field.map().field(tree[0], tree[1]);
        assertTrue(field.world().orderHarvest(peasant, tree[0], tree[1]));

        boolean sawPartial = false;
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 120; cycle++) {
            field.world().tick();
            int left = square.value();
            if (left > 0 && left < GameMap.WOOD_PER_FOREST_TILE) {
                sawPartial = true;
                assertTrue(square.isForest(),
                        "a square with wood left in it is still a tree");
            }
            if (left == 0) {
                break;
            }
        }
        assertTrue(sawPartial, "the square went from full to empty in one step");
        assertEquals(0, square.value());
        assertFalse(square.isForest(), "a spent square is open ground");
    }

    @Test
    @DisplayName("chopping and fighting are audible")
    void theAnimationsAskForTheirSounds() {
        // Every blow in Warcraft II is a sound instruction inside an attack
        // animation, and the chopping noise is one inside Harvest_wood. The
        // instruction was parsed from the first day and its result discarded,
        // which is why a battle was silent.
        Field field = mission();
        World world = field.world();
        Unit peasant = field.peasant();
        int[] tree = nearestForest(field.map(), peasant);
        world.orderHarvest(peasant, tree[0], tree[1]);

        Unit footman = null;
        Unit grunt = null;
        for (Unit unit : world.unitsSnapshot()) {
            if (footman == null && "unit-footman".equals(unit.type().ident())) {
                footman = unit;
            }
            if (grunt == null && "unit-grunt".equals(unit.type().ident())) {
                grunt = unit;
            }
        }
        Assumptions.assumeTrue(footman != null && grunt != null, "no combatants on this map");
        grunt.setTile(footman.tileX() + 1, footman.tileY());
        // Directly changing the tile bypasses the world's normal sight
        // bookkeeping. Grant the two adjacent squares explicitly so this
        // sound test does not also simulate a target disappearing into fog.
        world.fog().addSight(footman.player(), footman.tileX(), footman.tileY(), 1, 1, 2);
        world.fog().addSight(grunt.player(), grunt.tileX(), grunt.tileY(), 1, 1, 2);
        world.orderAttack(footman, grunt);
        world.orderAttack(grunt, footman);

        Map<String, Integer> heard = new TreeMap<>();
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 120; cycle++) {
            world.tick();
            for (World.SoundEvent event : world.drainSoundEvents()) {
                if (event.named()) {
                    heard.merge(event.event(), 1, Integer::sum);
                }
            }
        }
        assertTrue(heard.containsKey("tree-chopping"), "no axe on the tree: " + heard);
        assertTrue(heard.get("tree-chopping") > 20,
                "one chop does not fell a tree: " + heard);
        assertTrue(heard.containsKey("footman-attack"), "silent swords: " + heard);
        assertTrue(heard.containsKey("grunt-attack"), "the orc swings silently: " + heard);
    }
}
