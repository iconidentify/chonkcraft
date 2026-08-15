package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import net.chonkbase.chonkcraft.engine.network.SyncHash;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Alt-right-click is a real defend order, not a silent walk.
 */
class DefendOrderTest {

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
        type.setMaxAttackRange(1);
        type.setSightRange(9);
        type.setMissile("missile-none");
        AnimationSet set = new AnimationSet("soldier");
        set.put(AnimationSet.State.STILL, Animation.parse("still", List.of("frame 0", "wait 1")));
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
        World world = new World(openField(24));
        world.setUnitTypes(java.util.Map.of(type.ident(), type));
        return world;
    }

    @Test
    @DisplayName("a defend click is a defend order that survives the wire")
    void aDefendClickIsADefendOrderThatSurvivesTheWire() {
        UnitType type = soldier("unit-footman");
        World world = world(type);
        Unit guard = world.createUnit(type, 0, 4, 4);
        Unit ward = world.createUnit(type, 0, 8, 4);
        CommandApplier applier = new CommandApplier(world, List.of(type));

        assertTrue(applier.apply(GameCommand.defend(0, guard.id(), ward.id())),
                "defend was refused");
        assertEquals(Unit.Order.DEFEND, guard.order(),
                "the unit is not on defend");
        assertEquals(ward, guard.target(),
                "the ward is not the defend target");

        long before = SyncHash.of(world);
        assertTrue(applier.apply(GameCommand.defend(0, guard.id(), ward.id()).withQueued(true)),
                "a queued defend was refused");
        assertNotEquals(before, SyncHash.of(world),
                "queued defend must change the sync hash");
    }

    @Test
    @DisplayName("a dead ward ends defend rather than leaving a frozen guard")
    void aDeadWardEndsDefendRatherThanLeavingAFrozenGuard() {
        UnitType type = soldier("unit-footman");
        World world = world(type);
        Unit guard = world.createUnit(type, 0, 4, 4);
        Unit ward = world.createUnit(type, 0, 6, 4);
        CommandApplier applier = new CommandApplier(world, List.of(type));
        assertTrue(applier.apply(GameCommand.defend(0, guard.id(), ward.id())));

        ward.setHitPoints(0);
        for (int i = 0; i < 8; i++) {
            world.tick();
        }
        assertEquals(Unit.Order.STILL, guard.order(),
                "the guard stayed on defend after the ward died");
        assertFalse(applier.apply(GameCommand.defend(0, guard.id(), ward.id())),
                "defend accepted a dead ward");
    }
}
