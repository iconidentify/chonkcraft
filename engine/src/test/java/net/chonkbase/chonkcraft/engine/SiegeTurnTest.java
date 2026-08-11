package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A siege engine that has to turn round stands still while it does.
 *
 * <p>Two shipped types set {@code RotationSpeed}, and they are the ballista
 * and the catapult: both are {@code 4}, against a default of 128 -- half a
 * circle a cycle, which for eight facings is "at once"
 * ({@code include/unittype.h:668}, {@code scripts/human/units.legacy-declaration:200},
 * {@code scripts/orc/units.legacy-declaration:209}). Both are also the only types whose Move
 * animation asks about it:
 *
 * <pre>
 * "if-var R &gt;= 60 turn", "if-var R &lt;= -60 turn", "goto go",
 * "label turn", "unbreakable begin", "frame 0", "wait 30", "unbreakable end", "wait 1",
 * "label go"...
 * </pre>
 *
 * <p>{@code R} is {@code Anim.Rotate}, the turn a step has left to make.
 * {@code UnitHeadingFromDeltaXY} does not
 * snap a unit round: it works out the shorter way, stores it, and sets the
 * heading, and {@code UnitShowAnimationScaled} walks it back to nought by
 * {@code RotationSpeed} each cycle. A quarter turn is 64 of 256, so a catapult
 * that swings a right angle or more spends thirty cycles standing before it
 * starts walking.
 *
 * <p>This implementation read {@code R} as nought -- an unmodelled name -- so both siege
 * engines went straight past the {@code turn} label. On
 * {@code maps/demo/demo03} upstream's catapult steps to 20,3 on cycle 2 and is
 * still there with its offset untouched at cycle 34, where this implementation's had
 * arrived and taken a second step; that map's first divergence moved from
 * cycle 34 to 38.
 */
class SiegeTurnTest {

    /** ChonkCraft's catapult Move, turn stall and all. */
    private static final List<String> CATAPULT_MOVE = List.of(
            "if-var R >= 60 turn", "if-var R <= -60 turn", "goto go",
            "label turn", "unbreakable begin", "frame 0", "wait 30",
            "unbreakable end", "wait 1",
            "label go",
            "unbreakable begin", "frame 0", "wait 1",
            "frame 5", "move 8", "wait 2",
            "frame 0", "move 8", "wait 2",
            "frame 5", "move 8", "wait 2",
            "frame 0", "move 8", "unbreakable end", "wait 2");

    /** ChonkCraft's opening catapult Attack branches, through the damage frame. */
    private static final List<String> CATAPULT_ATTACK = List.of(
            "if-var R >= 30 turn", "if-var R <= -30 turn", "goto go",
            "label turn", "unbreakable begin", "frame 0", "wait 30",
            "unbreakable end", "wait 1",
            "label go",
            "unbreakable begin", "frame 15", "attack", "wait 4",
            "frame 0", "unbreakable end", "wait 1");

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static UnitType catapult() {
        UnitType type = new UnitType("unit-catapult");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(110);
        type.setSpeed(5);
        type.setLandUnit(true);
        type.setNumDirections(8);
        type.setRotationSpeed(4);
        AnimationSet set = new AnimationSet("catapult");
        set.put(AnimationSet.State.STILL, Animation.parse("still",
                List.of("frame 0", "wait 4", "frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", CATAPULT_MOVE));
        type.setAnimationSet(set);
        return type;
    }

    /** How long the turn label holds it, plus the cycle that reads the label. */
    private static final int TURN_CYCLES = 30;

    @Test
    @DisplayName("a freshly acquired in-range target is turned to before the first swing")
    void aFreshInRangeAcquisitionLeavesTheTurnForTheAttackAnimation() {
        World world = new World(grass(30));
        world.setAllied(0, 1, false);
        world.fog().revealAll(0);
        UnitType engineType = catapult();
        engineType.setCanAttack(true);
        engineType.setCanTargetLand(true);
        engineType.setBasicDamage(20);
        engineType.setMaxAttackRange(4);
        engineType.setSightRange(9);
        engineType.setReactRangePerson(9);
        engineType.animationSet().put(AnimationSet.State.ATTACK,
                Animation.parse("attack", CATAPULT_ATTACK));
        Unit engine = world.createUnit(engineType, 0, 10, 10);
        // Make the quarter turn exact instead of retaining the map-placement
        // heading drawn from the synchronized random stream.
        engine.setDirection(128);
        engine.setPendingRotation(0);
        UnitType targetType = catapult();
        targetType.setHitPoints(600);
        Unit target = world.createUnit(targetType, 1, 13, 10);

        assertTrue(world.orderAttackMove(engine, 20, 10), "the order was refused");
        world.tick();

        assertEquals(600, target.hitPoints(),
                "FIRST_ENTRY acquired in range and fired before TurnToTarget left Anim.Rotate"
                        + " for the attack script's thirty-cycle turn branch");
        assertEquals(-60, engine.pendingRotation(),
                "the attack animation did not consume one RotationSpeed beat of the eastward"
                        + " quarter turn");
        assertTrue(engine.animation().unbreakable(),
                "the fresh acquisition did not enter the turn branch of Attack");
        assertEquals(29, engine.animation().waitCycles(),
                "the turn branch did not begin its thirty-cycle wait");
    }

    @Test
    @DisplayName("a catapult that swings a right angle stands still for thirty cycles")
    void abigTurnCostsTheStall() {
        World world = new World(grass(30));
        Unit engine = world.createUnit(catapult(), 0, 10, 10);
        // Units are built facing south, so a step due north is a half turn and
        // a step due west is a quarter -- both past the sixty the animation
        // asks about.
        assertTrue(world.orderMove(engine, 10, 4), "the order was refused");

        int stillAt = -1;
        for (int cycle = 1; cycle <= TURN_CYCLES; cycle++) {
            world.tick();
            if (engine.offsetY() != 0 && Math.abs(engine.offsetY()) < 32) {
                stillAt = cycle;
                break;
            }
        }

        assertEquals(-1, stillAt,
                "the catapult started covering ground on cycle " + stillAt + ", inside the"
                        + " thirty its own animation spends turning. Reading R as nought is"
                        + " what walks straight past the turn label");
        assertEquals(10, engine.tileY() + 1,
                "and it should have taken its first step's tile at once, as upstream does --"
                        + " the stall is in the drawing, not in the order");
    }

    @Test
    @DisplayName("a siege engine stands down ten cycles after its walk ends, with its pixels caught up")
    void anArrivedMarchServesTheWalkOut() {
        World world = new World(grass(30));
        UnitType type = catapult();
        // The real catapult's gun cannot reach its own feet, and that minimum
        // is what refuses upstream's waiting-in-range arrival and makes the
        // spent route's pause observable ({@code scripts/orc/units.legacy-declaration:160}).
        type.setCanAttack(true);
        type.setMinAttackRange(2);
        type.setMaxAttackRange(8);
        Unit engine = world.createUnit(type, 0, 10, 10);
        assertTrue(world.orderAttackMove(engine, 10, 9), "the order was refused");

        int pixelsDoneAt = -1;
        int endedAt = -1;
        for (int cycle = 1; cycle <= 120; cycle++) {
            world.tick();
            if (pixelsDoneAt < 0 && engine.tileY() == 9
                    && engine.offsetX() == 0 && engine.offsetY() == 0) {
                pixelsDoneAt = cycle;
            }
            if (engine.order() != Unit.Order.ATTACK_MOVE) {
                endedAt = cycle;
                break;
            }
        }

        assertTrue(endedAt > 0, "the march never ended in 120 cycles");
        assertTrue(pixelsDoneAt > 0 && pixelsDoneAt < endedAt,
                "the order ended with the step's pixels still owed: offset "
                        + engine.offsetX() + "," + engine.offsetY() + " -- the march was"
                        + " called over at the animation's first breakable moment and the"
                        + " engine is drawn a tile from its square for ever after");
        assertTrue(endedAt - pixelsDoneAt >= 10,
                "the order ended " + (endedAt - pixelsDoneAt) + " cycle(s) after the walk"
                        + " let go. A catapult on its own square is under its MinAttackRange"
                        + " of it, so upstream refuses the waiting-in-range arrival and the"
                        + " spent route costs its ten-cycle pause first");
    }

    @Test
    @DisplayName("and one whose gun reaches its own feet stands down at once")
    void aMinlessArriverDoesNotServeThePause() {
        World world = new World(grass(30));
        UnitType type = catapult();
        // The same engine with no minimum: distance nought is in range, the
        // waiting arrival converts, and the pause is never served. The pair
        // differs only in MinAttackRange, so a pause applied to everything or
        // to nothing fails one of the two.
        type.setCanAttack(true);
        type.setMaxAttackRange(8);
        Unit engine = world.createUnit(type, 0, 10, 10);
        assertTrue(world.orderAttackMove(engine, 10, 9), "the order was refused");

        int pixelsDoneAt = -1;
        int endedAt = -1;
        for (int cycle = 1; cycle <= 120; cycle++) {
            world.tick();
            if (pixelsDoneAt < 0 && engine.tileY() == 9
                    && engine.offsetX() == 0 && engine.offsetY() == 0) {
                pixelsDoneAt = cycle;
            }
            if (engine.order() != Unit.Order.ATTACK_MOVE) {
                endedAt = cycle;
                break;
            }
        }

        assertTrue(endedAt > 0, "the march never ended in 120 cycles");
        assertTrue(pixelsDoneAt > 0, "the pixels were never walked off");
        assertTrue(endedAt - pixelsDoneAt <= 2,
                "the order outlived the walk by " + (endedAt - pixelsDoneAt) + " cycles."
                        + " With no minimum range the unit is in attack range of its own"
                        + " square, upstream's waiting arrival converts to REACHED, and"
                        + " the ten-cycle pause belongs only to the refused case");
    }

    @Test
    @DisplayName("and one that barely turns does not")
    void asmallTurnDoesNot() {
        World world = new World(grass(30));
        Unit engine = world.createUnit(catapult(), 0, 10, 10);
        // South-west is one facing from south, which is 32 of 256 and under
        // the sixty the animation asks about.
        assertTrue(world.orderMove(engine, 4, 16), "the order was refused");

        boolean moved = false;
        for (int cycle = 1; cycle <= TURN_CYCLES && !moved; cycle++) {
            world.tick();
            moved = engine.offsetX() != 0 && Math.abs(engine.offsetX()) < 32;
        }

        assertTrue(moved,
                "the catapult stood still for thirty cycles for a turn of one facing. The"
                        + " animation only stalls at sixty and a facing is thirty-two, so a"
                        + " test that passes both ways would say nothing");
    }
}
