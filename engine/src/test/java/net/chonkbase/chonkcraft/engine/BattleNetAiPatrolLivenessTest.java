package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.pathfinder.PathFinder;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BattleNetAiPatrolLivenessTest {

    private record Pixel(int x, int y) {
    }

    @Test
    @DisplayName("the native Orc 11 assault moves and every member engages")
    void nativeOrcElevenAssaultMovesAndEveryMemberEngages() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No BNE asset pack; set CHONKCRAFT_ASSET_PACK");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/orc/level11o");
        Assumptions.assumeTrue(mission != null,
                "Orc mission 11 is not in the pack");
        World world = mission.world();

        // Sealed BNE fixture retail-orc-11-idle records four player-one
        // behavior-two attackers on cycle one. Every member moves, and the
        // untouched 2.02b binary puts them into real attack orders between
        // cycles 324 and 397. This is the native end-to-end liveness witness;
        // the old Human X10 referee incorrectly treated a Java-only cycle-6201
        // launch as retail behavior even though a 6,500-cycle native capture
        // records no behavior-two X10 unit at that boundary.
        Map<Integer, Pixel> firstWave = new HashMap<>();
        Set<Integer> moved = new HashSet<>();
        Set<Integer> engaged = new HashSet<>();
        for (int elapsed = 1; elapsed <= 600; elapsed++) {
            mission.tick();
            if (elapsed == 1) {
                world.playerUnits(1).stream()
                        .filter(Unit::isAlive)
                        .filter(Unit::isOnMap)
                        .filter(unit -> unit.battleNetAiBehavior() == 2)
                        .forEach(unit -> firstWave.put(unit.id(),
                                new Pixel(unit.pixelX(), unit.pixelY())));
            }
            if (firstWave.isEmpty()) {
                continue;
            }
            for (Unit unit : world.playerUnits(1)) {
                Pixel start = firstWave.get(unit.id());
                if (start == null) {
                    continue;
                }
                if (unit.pixelX() != start.x() || unit.pixelY() != start.y()) {
                    moved.add(unit.id());
                }
                if (unit.target() != null
                        || unit.order() == Unit.Order.ATTACK
                        || unit.order() == Unit.Order.ATTACK_MOVE
                        || unit.currentAction() == Unit.Order.ATTACK
                        || unit.currentAction() == Unit.Order.ATTACK_MOVE) {
                    engaged.add(unit.id());
                }
            }
        }

        assertEquals(4, firstWave.size(),
                "the sealed BNE four-member Orc 11 assault was not present");
        assertEquals(firstWave.keySet(), moved,
                "a launched behavior-two unit remained visibly frozen");
        assertEquals(firstWave.keySet(), engaged,
                "a launched behavior-two unit never reached real combat");
    }

    @Test
    @DisplayName("an AI assault patrol routes around a neutral mine")
    void aiAssaultPatrolRoutesAroundANeutralMine() {
        World world = new World(grass(32));
        world.fog().revealAll(0);
        Unit grunt = world.createUnit(grunt(), 0, 9, 9);
        Unit mine = world.createUnit(building("unit-gold-mine", 3, 3),
                15, 10, 10);
        assertNotNull(grunt, "grunt places north-west of the mine");
        assertNotNull(mine, "neutral mine places across the direct ray");

        grunt.setBattleNetAiBehavior(2);
        grunt.setBattleNetAiHome(24, 24);
        assertTrue(world.orderPatrol(grunt, 24, 24),
                "the launched assault accepts its travelling patrol order");

        int startX = grunt.tileX();
        int startY = grunt.tileY();
        for (int cycle = 0; cycle < 180
                && grunt.tileX() == startX && grunt.tileY() == startY;
                cycle++) {
            world.tick();
        }

        assertTrue(grunt.tileX() != startX || grunt.tileY() != startY,
                "a permanent neutral footprint must stay in the path map; "
                        + "otherwise every replan chooses the mine and the "
                        + "assault loops through refusal waits forever");
    }

    @Test
    @DisplayName("an assault's long-route recovery retains the target footprint")
    void assaultLongRouteRecoveryRetainsTheTargetFootprint() {
        GameMap map = grass(32);
        // Seal the north and west firing skirts of a 2x2 tower. Its south and
        // east skirts remain reachable from the grunt, but both are two tiles
        // from the building's stored top-left assault home.
        for (int x = 11; x <= 13; x++) {
            map.field(x, 11).setFlags(TileFlag.UNPASSABLE);
        }
        for (int y = 12; y <= 13; y++) {
            map.field(11, y).setFlags(TileFlag.UNPASSABLE);
        }

        World world = new World(map);
        world.fog().revealAll(0);
        Unit grunt = world.createUnit(grunt(), 0, 20, 20);
        Unit tower = world.createUnit(building("unit-human-cannon-tower", 2, 2),
                1, 12, 12);
        assertNotNull(grunt, "grunt places in the south-east region");
        assertNotNull(tower, "tower places behind its sealed near skirts");

        PathFinder.Path pointOnly = world.findMovementPath(grunt,
                new PathFinder.Goal(12, 12, 1, 1, 0, 1));
        assertEquals(PathFinder.Result.UNREACHABLE, pointOnly.result(),
                "the old 1x1 recovery cannot reach a legal skirt");
        PathFinder.Path footprint = world.findMovementPath(grunt,
                new PathFinder.Goal(12, 12, 2, 2, 0, 1));
        assertEquals(PathFinder.Result.FOUND, footprint.result(),
                "the target's south/east skirt is reachable");

        grunt.setBattleNetAiBehavior(2);
        grunt.setBattleNetAiHome(12, 12);
        assertTrue(world.orderPatrol(grunt, 12, 12),
                "the assault accepts the target's stored home");

        int startX = grunt.tileX();
        int startY = grunt.tileY();
        for (int cycle = 0; cycle < 240
                && grunt.tileX() == startX && grunt.tileY() == startY;
                cycle++) {
            world.tick();
        }
        assertTrue(grunt.tileX() != startX || grunt.tileY() != startY,
                "the patrol must use the reachable full-footprint recovery "
                        + "instead of standing down on the 1x1 answer");
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

    private static UnitType grunt() {
        UnitType type = new UnitType("unit-grunt");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(6);
        type.setMaxAttackRange(1);
        type.setSightRange(4);
        type.setNumDirections(8);
        AnimationSet animations = new AnimationSet("grunt");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", List.of(
                        "unbreakable begin", "frame 0", "move 16", "wait 1",
                        "frame 5", "move 16", "unbreakable end", "wait 1")));
        type.setAnimationSet(animations);
        return type;
    }

    private static UnitType building(String ident, int width, int height) {
        UnitType type = new UnitType(ident);
        type.setTileSize(width, height);
        type.setHitPoints(25_000);
        type.setBuilding(true);
        type.setLandUnit(true);
        return type;
    }
}
