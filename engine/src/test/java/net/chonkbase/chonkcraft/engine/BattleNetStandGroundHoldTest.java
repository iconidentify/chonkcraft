package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Native 0x4368b0 pops order 15 for three animation ticks, then order 13.
 * Order 13 ticks the still handler but its flag word is 0x0082 -- no
 * 0x1000 -- so a person does not take the 0x4368c0 chase.
 */
class BattleNetStandGroundHoldTest {

    private static GameMap openField(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static UnitType soldier(String ident) {
        UnitType type = new UnitType(ident);
        type.setName(ident);
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(6);
        type.setPiercingDamage(3);
        type.setMaxAttackRange(1);
        type.setSightRange(9);
        type.setReactRangePerson(4);
        type.setReactRangeComputer(4);
        type.setMissile("missile-none");
        AnimationSet set = new AnimationSet("soldier");
        set.put(AnimationSet.State.STILL, Animation.parse("still",
                List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 16", "wait 1",
                "frame 5", "move 16", "unbreakable end", "wait 1")));
        set.put(AnimationSet.State.ATTACK, Animation.parse("attack",
                List.of("unbreakable begin", "frame 5", "wait 1",
                        "frame 10", "attack", "unbreakable end", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    private static World world(UnitType type) {
        World world = new World(openField(32));
        world.setUnitTypes(java.util.Map.of(type.ident(), type));
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        return world;
    }

    @Test
    @DisplayName("stand-ground stays a hold after the three-tick opening")
    void standGroundStaysAHoldAfterTheThreeTickOpening() {
        UnitType type = soldier("unit-grunt");
        World world = world(type);
        Unit grunt = world.createUnit(type, 0, 8, 8);
        grunt.setBattleNetAnimationTimer(1);
        CommandApplier applier = new CommandApplier(world, List.of(type));
        assertTrue(applier.apply(GameCommand.standGround(0, grunt.id())),
                "the grunt refused to hold");
        assertEquals(Unit.Order.STAND_GROUND, grunt.order(),
                "the opening is not stand-ground");
        for (int tick = 0; tick < 8; tick++) {
            world.tick();
        }
        assertEquals(Unit.Order.STAND_GROUND, grunt.order(),
                "after the three-tick opening a person used to go Still and chase");
        assertEquals(8, grunt.tileX(), "the hold walked away");
        assertEquals(8, grunt.tileY(), "the hold walked away");
    }

    @Test
    @DisplayName("a person on the stand-ground hold does not chase a blow")
    void aPersonOnTheStandGroundHoldDoesNotChaseABlow() {
        UnitType type = soldier("unit-footman");
        World world = world(type);
        Unit holder = world.createUnit(type, 0, 8, 8);
        holder.setBattleNetAnimationTimer(1);
        Unit shooter = world.createUnit(type, 1, 16, 8);
        CommandApplier applier = new CommandApplier(world, List.of(type));
        assertTrue(applier.apply(GameCommand.standGround(0, holder.id())),
                "the footman refused to hold");
        for (int tick = 0; tick < 8; tick++) {
            world.tick();
        }
        world.hit(shooter, holder);
        for (int tick = 0; tick < 20; tick++) {
            world.tick();
        }
        assertEquals(Unit.Order.STAND_GROUND, holder.order(),
                "a person on the order-13 hold chased the blow");
        assertEquals(8, holder.tileX(), "a person told to hold left the tile");
        assertEquals(8, holder.tileY(), "a person told to hold left the tile");
    }

    @Test
    @DisplayName("a person standing idle does chase a blow")
    void aPersonStandingIdleDoesChaseABlow() {
        UnitType type = soldier("unit-grunt");
        World world = world(type);
        Unit idle = world.createUnit(type, 0, 8, 8);
        Unit shooter = world.createUnit(type, 1, 16, 8);
        world.hit(shooter, idle);
        for (int tick = 0; tick < 20 && idle.tileX() == 8; tick++) {
            world.tick();
        }
        assertNotEquals(8, idle.tileX(),
                "a person Still did not take the 0x1000 chase a stand-ground hold must skip");
    }
}