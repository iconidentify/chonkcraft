package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Test;

/** The temporary sight marker inserted at the start of every death animation. */
class DeathVisionTest {

    private static World world() {
        GameMap map = new GameMap(30, 30, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return new World(map);
    }

    private static UnitType dyingScout() {
        UnitType type = new UnitType("unit-scout");
        type.setTileSize(1, 1);
        type.setHitPoints(20);
        type.setLandUnit(true);
        type.setSightRange(5);
        AnimationSet animations = new AnimationSet("animations-scout");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        animations.put(AnimationSet.State.DEATH, Animation.parse("death",
                List.of("spawn-unit unit-dead-vision 0 0 0 l.this", "wait 1")));
        type.setAnimationSet(animations);
        return type;
    }

    private static UnitType deadVision() {
        UnitType type = new UnitType("unit-dead-vision");
        type.setTileSize(1, 1);
        type.setHitPoints(1);
        type.setLandUnit(true);
        type.setSightRange(5);
        type.setVanishes(true);
        type.setRevealer(true);
        type.setNonSolid(true);
        AnimationSet animations = new AnimationSet("animations-dead-vision");
        animations.put(AnimationSet.State.STILL, Animation.parse("still",
                List.of("frame 0", "wait 2",
                        "set-var SightRange.Max = 1", "wait 2", "die")));
        type.setAnimationSet(animations);
        return type;
    }

    @Test
    void deathLeavesTemporaryNonSolidSightAtTheFallenUnitsPosition() {
        World world = world();
        UnitType revealer = deadVision();
        world.setUnitTypes(Map.of(revealer.ident(), revealer));
        Unit scout = world.createUnit(dyingScout(), 0, 15, 15);
        assertTrue(world.fog().isVisible(0, 20, 15), "the living scout should see five tiles");

        world.kill(scout);
        assertFalse(world.fog().isVisible(0, 20, 15), "death should first remove living sight");
        world.tick();

        assertTrue(world.fog().isVisible(0, 20, 15),
                "the death animation did not spawn its temporary revealer");
        assertFalse(world.map().field(15, 15).hasFlag(TileFlag.LAND_UNIT),
                "the revealer should not occupy the square where the unit fell");

        for (int cycle = 0; cycle < 8; cycle++) {
            world.tick();
        }
        assertFalse(world.fog().isVisible(0, 15, 15),
                "the dead-vision marker did not vanish after its animation");
    }

    @Test
    void deathAnimationIgnoresTheWaitOfTheInterruptedOrder() {
        World world = world();
        UnitType revealer = deadVision();
        world.setUnitTypes(Map.of(revealer.ident(), revealer));
        Unit scout = world.createUnit(dyingScout(), 0, 15, 15);
        scout.setWaitCycles(10);

        world.kill(scout);
        world.tick();

        assertTrue(world.units().stream().anyMatch(unit -> unit.type() == revealer),
                "the dead unit served its old order's wait instead of starting to die");
    }
}
