package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.MapField;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.missile.Missile;
import net.chonkbase.chonkcraft.engine.missile.MissileClass;
import net.chonkbase.chonkcraft.engine.missile.MissileType;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Walls come down.
 *
 * <p>A wall in Warcraft II is terrain, not a unit: its hit points ride in the
 * map square's spare value and {@code CMap::HitWall} takes them off. The implementation
 * read those hit points out of the PUD, gave {@link MapField} an
 * {@code isWall()}, and then had exactly one reader for either -- the info
 * panel. There was no {@code MissileHitsWall}, so a wall was indestructible
 * terrain and a walled base could only be entered through a gap the mapper had
 * left.
 */
class WallDamageTest {

    /**
     * An isolated human wall: {@code humanWallTable[0]}.
     *
     * <p>Was {@code 0x0800}, the group for a wall joined south, chosen when
     * the implementation picked a wall's picture by adding a fixed offset to whatever
     * code was already there. It re-derives the group from the neighbours now
     * -- {@code MapFixWallTile} -- and a wall on its own belongs to the group
     * for no neighbours at all.
     */
    private static final int WALL_TILE = net.chonkbase.chonkcraft.engine.map.WallTileset.HUMAN[0];

    private static GameMap walledField(int size) {
        // A tileset that actually declares the wall groups. With an empty one
        // every lookup finds nothing and the picture never changes, which
        // looks exactly like the bug this is here to catch.
        GameMap map = new GameMap(size, size,
                net.chonkbase.chonkcraft.engine.map.WallTileset.withWalls());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    /** Puts a human wall on a square, as the PUD reader would. */
    private static void wall(GameMap map, int x, int y) {
        MapField field = map.field(x, y);
        field.setTile(WALL_TILE);
        field.setFlags(TileFlag.LAND_ALLOWED | TileFlag.WALL | TileFlag.HUMAN
                | TileFlag.UNPASSABLE);
        field.setValue(GameMap.WALL_HIT_POINTS);
    }

    private static UnitType siege(String ident, String missile) {
        UnitType type = new UnitType(ident);
        type.setName(ident);
        type.setTileSize(1, 1);
        type.setHitPoints(120);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(25);
        type.setMaxAttackRange(8);
        type.setMissile(missile);
        AnimationSet set = new AnimationSet("walker");
        set.put(AnimationSet.State.STILL, Animation.parse("still", List.of("frame 0", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    private static UnitType melee(String ident) {
        UnitType type = siege(ident, "missile-none");
        type.setMaxAttackRange(1);
        AnimationSet set = new AnimationSet("wall-breaker");
        set.put(AnimationSet.State.STILL, Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move",
                List.of("frame 0", "move 32", "wait 1")));
        set.put(AnimationSet.State.ATTACK, Animation.parse("attack",
                List.of("unbreakable begin", "frame 5", "wait 1",
                        "frame 10", "attack", "unbreakable end", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    private static MissileType boulder() {
        return new MissileType("missile-catapult-rock", null, MissileClass.POINT_TO_POINT,
                32, 32, 1, 1, 16, 1, 2, 4, 0, null, null, false, 0, 0, false, null, 0);
    }

    @Test
    @DisplayName("a catapult shelling a wall breaks it and opens the ground")
    void aWallCanBeBroken() {
        GameMap map = walledField(40);
        wall(map, 20, 20);
        World world = new World(map);
        world.setMissileTypes(Map.of("missile-catapult-rock", boulder()));

        Unit catapult = world.createUnit(siege("unit-catapult", "missile-catapult-rock"),
                0, 14, 20);
        assertTrue(world.orderAttackGround(catapult, 20, 20));

        MapField field = map.field(20, 20);
        int before = field.value();
        for (int cycle = 0; cycle < 120 && field.value() == before; cycle++) {
            world.tick();
        }
        assertTrue(field.value() < before,
                "the wall took no damage at all: there is no counterpart to MissileHitsWall");

        for (int cycle = 0; cycle < 2000 && field.isWall(); cycle++) {
            world.tick();
        }
        assertFalse(field.isWall(), "the wall never came down");
        assertFalse(field.hasFlag(TileFlag.UNPASSABLE),
                "RemoveWall clears the unpassable flag: a breach is something an army "
                        + "walks through");
        assertTrue(field.hasFlag(TileFlag.LAND_ALLOWED),
                "and it stays land, so the ground is walkable rather than a hole");
        assertEquals(0, field.value());
    }

    @Test
    @DisplayName("a footman walks up to a wall and hacks a breach through it")
    void meleeCanBreakAWall() {
        GameMap map = walledField(40);
        wall(map, 20, 20);
        World world = new World(map);
        Unit footman = world.createUnit(melee("unit-footman"), 0, 14, 20);

        assertTrue(world.orderAttackGround(footman, 20, 20),
                "a wall tile was refused because the footman fires no missile");

        MapField field = map.field(20, 20);
        for (int cycle = 0; cycle < 400 && field.isWall(); cycle++) {
            world.tick();
        }
        assertFalse(field.isWall(),
                "the footman reached the wall and swung without damaging the terrain");
        assertEquals(19, footman.tileX(),
                "a melee attacker should stop beside the wall rather than stand in it");
        assertTrue(field.hasFlag(TileFlag.LAND_ALLOWED),
                "the breach left by a melee blow is walkable land");
    }

    @Test
    @DisplayName("melee attack-ground on empty grass faces without swinging")
    void meleeFacesEmptyAttackGroundWithoutSwinging() {
        GameMap map = walledField(20);
        World world = new World(map);
        Unit footman = world.createUnit(melee("unit-footman"), 0, 10, 10);

        assertTrue(world.orderAttackGround(footman, 11, 10));
        for (int cycle = 0; cycle < 40; cycle++) {
            world.tick();
            assertEquals(Unit.Order.ATTACK_GROUND, footman.order(),
                    "retail holds GiveOrder 17 on grass");
            assertSame(footman.type().animationSet().get(AnimationSet.State.STILL),
                    footman.animation().current(),
                    "empty ground started a visible attack swing");
            assertFalse(footman.animation().unbreakable(),
                    "empty ground committed an invisible-enemy blow");
        }
    }

    @Test
    @DisplayName("a legacy stale-goal attack-ground state heals to still")
    void legacyStaleGoalAttackGroundHeals() {
        World world = new World(walledField(20));
        Unit footman = world.createUnit(melee("unit-footman"), 0, 10, 10);
        footman.setAttackGoal(11, 10);
        footman.setOrderTarget(11, 10);
        footman.setOrder(Unit.Order.ATTACK_GROUND);
        footman.setBattleNetPlayerCommandMove(true);
        footman.setBattleNetAttackGroundMove(false);

        world.tick();

        assertEquals(Unit.Order.STILL, footman.order(),
                "the pre-fix save state kept fighting empty ground");
        assertFalse(footman.battleNetAttackGroundMove());
    }

    @Test
    @DisplayName("an ordinary move cannot promote a stale attack goal")
    void ordinaryMoveDoesNotPromoteAStaleAttackGoal() {
        World world = new World(walledField(30));
        Unit footman = world.createUnit(melee("unit-footman"), 0, 5, 10);
        footman.setAttackGoal(6, 9);

        assertTrue(world.orderCommandMove(footman, 20, 10));
        assertFalse(footman.battleNetAttackGroundMove(),
                "an ordinary move inherited GiveOrder 17 provenance");
        for (int cycle = 0; cycle < 400; cycle++) {
            world.tick();
            assertFalse(footman.order() == Unit.Order.ATTACK_GROUND,
                    "the move was rewritten to attack stale empty ground at cycle " + cycle);
        }
    }

    @Test
    @DisplayName("the splash of a shot aimed elsewhere still chips the wall beside it")
    void splashReachesTheWall() {
        GameMap map = walledField(40);
        wall(map, 21, 20);
        World world = new World(map);
        world.setMissileTypes(Map.of("missile-catapult-rock", boulder()));

        Unit catapult = world.createUnit(siege("unit-catapult", "missile-catapult-rock"),
                0, 14, 20);
        assertTrue(world.orderAttackGround(catapult, 20, 20));

        MapField beside = map.field(21, 20);
        int before = beside.value();
        for (int cycle = 0; cycle < 60 && beside.value() == before; cycle++) {
            world.tick();
        }
        assertTrue(beside.value() < before,
                "MissileHit runs its blast box over the ground as well as over the units");
    }

    @Test
    @DisplayName("BNE artillery splash damages the wall struck and its neighbour")
    void battleNetArtillerySplashReachesWalls() {
        List<MissileType> artillery = List.of(
                new MissileType("missile-catapult-rock", null, MissileClass.PARABOLIC,
                        32, 32, 15, 9, 16, 1, 2, 4, 0, null, null,
                        false, 0, 0, false, null, 0),
                new MissileType("missile-ballista-bolt", null, MissileClass.PARABOLIC,
                        32, 32, 15, 9, 16, 1, 2, 4, 0, null, null,
                        false, 0, 0, false, null, 0),
                new MissileType("missile-small-cannon", null, MissileClass.PARABOLIC,
                        32, 32, 15, 9, 16, 1, 2, 4, 0, null, null,
                        false, 0, 0, false, null, 0));

        for (MissileType type : artillery) {
            GameMap map = walledField(40);
            for (int y = 18; y <= 23; y++) {
                for (int x = 18; x <= 23; x++) {
                    wall(map, x, y);
                }
            }
            World world = new World(map);
            Unit attacker = world.createUnit(siege("unit-artillery", type.ident()),
                    0, 14, 20);
            Missile shot = world.projectiles.launchGround(attacker, 20, 20, type);
            world.prepareBattleNetProjectile(shot, true);

            int before = map.field(20, 20).value();
            for (int cycle = 0; cycle < 120
                    && !shot.hasArrived(); cycle++) {
                world.tick();
            }

            int impactX = shot.tileX();
            int impactY = shot.tileY();
            assertEquals(25, shot.damage(), "the fixture's stored BNE damage changed");
            assertEquals(before - 12, map.field(impactX, impactY).value(),
                    type.ident() + " did not apply six native damage steps to the impact wall");
            for (int[] offset : List.of(
                    new int[] {1, 0}, new int[] {-1, 0},
                    new int[] {0, 1}, new int[] {0, -1})) {
                assertEquals(before - 2,
                        map.field(impactX + offset[0], impactY + offset[1]).value(),
                        type.ident() + " did not apply one native damage step to a cardinal wall");
            }
            for (int[] offset : List.of(
                    new int[] {1, 1}, new int[] {-1, 1},
                    new int[] {1, -1}, new int[] {-1, -1})) {
                assertEquals(before,
                        map.field(impactX + offset[0], impactY + offset[1]).value(),
                        type.ident() + " spread BNE wall damage into a diagonal tile");
            }
        }
    }

    @Test
    @DisplayName("BNE artillery converts its 20-step wall counter to 40 wall hit points")
    void battleNetArtilleryWallDamageBreakTimingUsesNativeCounterSteps() {
        GameMap map = walledField(12);
        wall(map, 5, 5);
        wall(map, 6, 5);
        World world = new World(map);
        Missile shot = new Missile(boulder(), null, null, 0, 0, 0, 0);
        shot.setDamage(20);

        for (int hit = 1; hit <= 3; hit++) {
            world.hitBattleNetFixedSplashWall(shot, 5, 5, 0);
            assertEquals(GameMap.WALL_HIT_POINTS - hit * 10, map.field(5, 5).value(),
                    "stored 20 should be five of the native wall's twenty damage steps");
            assertTrue(map.field(5, 5).isWall());
        }
        world.hitBattleNetFixedSplashWall(shot, 5, 5, 0);
        assertFalse(map.field(5, 5).isWall(),
                "four five-step center hits should consume the native 20-step counter");

        for (int hit = 1; hit <= 19; hit++) {
            world.hitBattleNetFixedSplashWall(shot, 6, 5, 2);
            assertEquals(GameMap.WALL_HIT_POINTS - hit * 2, map.field(6, 5).value(),
                    "quarter 20 becomes one native counter step");
            assertTrue(map.field(6, 5).isWall());
        }
        world.hitBattleNetFixedSplashWall(shot, 6, 5, 2);
        assertFalse(map.field(6, 5).isWall(),
                "twenty one-step cardinal hits should consume the native counter");
    }

    @Test
    @DisplayName("BNE artillery wall splash is clipped safely at the map edge")
    void battleNetArtilleryWallSplashClipsAtMapEdge() {
        GameMap map = walledField(12);
        wall(map, 0, 0);
        wall(map, 1, 0);
        wall(map, 0, 1);
        wall(map, 1, 1);
        World world = new World(map);
        MissileType type = boulder();
        Unit attacker = world.createUnit(siege("unit-catapult", type.ident()), 0, 6, 0);
        Missile shot = world.projectiles.launchGround(attacker, 0, 0, type);
        world.prepareBattleNetProjectile(shot, true);

        int before = map.field(0, 0).value();
        for (int cycle = 0; cycle < 120 && map.field(0, 0).value() == before; cycle++) {
            world.tick();
        }

        assertEquals(before - 12, map.field(0, 0).value());
        assertEquals(before - 2, map.field(1, 0).value());
        assertEquals(before - 2, map.field(0, 1).value());
        assertEquals(before, map.field(1, 1).value(),
                "the clipped cardinal cross must not turn into a square");
    }

    @Test
    @DisplayName("a broken wall draws as broken and then as rubble")
    void theWallsGraphicFollowsItsHealth() {
        GameMap map = walledField(10);
        wall(map, 5, 5);
        MapField field = map.field(5, 5);
        assertEquals(WALL_TILE, field.tile(), "a whole wall draws its whole picture");

        // Below half, which is where getWallTile switches to the broken
        // section of the slot: the graphic two places along from the whole one.
        map.hitWall(5, 5, GameMap.WALL_HIT_POINTS / 2 + 1, GameMap.WALL_HIT_POINTS);
        assertEquals(WALL_TILE + 2, field.tile(),
                "a wall beaten below half its strength still drew as though whole");
        assertTrue(field.isWall(), "and it is still standing");

        map.hitWall(5, 5, GameMap.WALL_HIT_POINTS, GameMap.WALL_HIT_POINTS);
        assertEquals(WALL_TILE + 4, field.tile(), "a spent wall draws as rubble");
        assertFalse(field.isWall());
    }

    @Test
    @DisplayName("a shot that misses the wall leaves it alone")
    void openGroundIsNotAWall() {
        GameMap map = walledField(10);
        // No wall anywhere. hitWall must be a no-op rather than writing a
        // value onto plain grass.
        map.hitWall(5, 5, 40, GameMap.WALL_HIT_POINTS);
        assertEquals(0, map.field(5, 5).value());
        assertTrue(map.field(5, 5).hasFlag(TileFlag.LAND_ALLOWED));
    }
}
