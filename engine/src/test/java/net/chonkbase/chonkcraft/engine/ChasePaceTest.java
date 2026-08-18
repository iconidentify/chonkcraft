package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
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
 * How fast a unit chasing something crosses the ground.
 *
 * <p>At its own animation's pace, and no faster. A move animation is a list of
 * {@code move} amounts adding to one tile of pixels and {@code wait} amounts
 * adding to the cycles that takes; ChonkCraft's knight covers 32 pixels over 12
 * cycles ({@code scripts/human/anim.legacy-declaration:134-138}) and that is the whole of how
 * fast a knight is.
 *
 * <p>Upstream runs {@code DoActionMove} exactly once per {@code Execute}, and
 * everything after it in {@code COrder_Attack::MoveToTarget} decides rather
 * than walks: the "waiting or on the way" arm returns without touching the
 * unit's position. This implementation called
 * the walk twice -- once because a chase steps before it reconsiders, and again
 * at the range check. Eleven cycles out of twelve the second call did nothing,
 * because the move animation was mid-stride and unbreakable. On the twelfth,
 * the cycle the step lands, the animation had just ended and the second call
 * started the next one, so a chaser crossed a square in eleven cycles instead
 * of twelve and gained a square every dozen.
 *
 * <p>Found on {@code maps/demo/demo03} at cycle 13, where a knight, a
 * gryphon-rider and a dragon were each one square ahead of upstream's and
 * nothing else on the map disagreed at all.
 */
class ChasePaceTest {

    /** ChonkCraft's own KnightMove: 32 pixels of travel over 12 cycles of wait. */
    private static final List<String> KNIGHT_MOVE = List.of(
            "unbreakable begin", "frame 0", "move 3", "wait 1",
            "frame 5", "move 3", "wait 1",
            "frame 5", "move 4", "wait 2",
            "frame 10", "move 3", "wait 1",
            "frame 10", "move 3", "wait 1",
            "frame 15", "move 3", "wait 1",
            "frame 15", "move 4", "wait 2",
            "frame 20", "move 3", "wait 1",
            "frame 20", "move 3", "wait 1",
            "frame 0", "move 3", "unbreakable end", "wait 1");

    private static final int CYCLES_A_SQUARE = 12;

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static AnimationSet knightAnimations() {
        AnimationSet set = new AnimationSet("knight");
        set.put(AnimationSet.State.STILL, Animation.parse("still", List.of(
                "frame 0", "wait 4", "random-goto 99 no-rotate",
                "random-rotate 1", "label no-rotate", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", KNIGHT_MOVE));
        set.put(AnimationSet.State.ATTACK, Animation.parse("attack", List.of(
                "unbreakable begin", "frame 25", "wait 3", "frame 40", "attack",
                "wait 5", "frame 0", "unbreakable end", "wait 1")));
        return set;
    }

    private static UnitType knight() {
        UnitType type = new UnitType("unit-knight");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(90);
        type.setSpeed(13);
        type.setLandUnit(true);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(8);
        type.setMaxAttackRange(1);
        type.setSightRange(12);
        type.setNumDirections(8);
        type.setAnimationSet(knightAnimations());
        return type;
    }

    /**
     * Skeleton Move from ChonkCraft (21 wait total) -- longer than retail's OP0
     * body (20 quiet calls). The domain property is the retail cadence.
     */
    private static final List<String> SKELETON_MOVE = List.of(
            "unbreakable begin", "frame 0", "move 3", "wait 2",
            "frame 10", "move 3", "wait 2",
            "frame 10", "move 3", "wait 1", "frame 25", "move 2", "wait 2",
            "frame 25", "move 3", "wait 3", "frame 0", "move 2", "wait 1",
            "frame 0", "move 3", "wait 2", "frame 40", "move 3", "wait 2",
            "frame 40", "move 3", "wait 1", "frame 55", "move 2", "wait 2",
            "frame 55", "move 3", "wait 2", "frame 0", "move 2",
            "unbreakable end", "wait 1");

    private static byte[] retailScriptBin() throws IOException {
        String packProp = System.getProperty("chonkcraft.pack");
        Path pack = packProp != null && !packProp.isBlank()
                ? Path.of(packProp)
                : Path.of(System.getProperty("user.home"),
                        ".chonkcraft/work",
                        "warcraft-ii-battle-net-edition-usa.pre-full-media-2026-07-30.chonkpack");
        assumeTrue(Files.isRegularFile(pack),
                "BNE asset pack required for retail Move sequence");
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            var entry = zip.getEntry("assets/archives/maindat/0278.bin");
            assumeTrue(entry != null, "pack must contain maindat entry 278");
            try (var in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    @Test
    @DisplayName("a skeleton chase takes the next heading on the retail Move OP0, not one wait later")
    void aSkeletonChaseTakesTheNextHeadingOnTheRetailMoveOp0() throws Exception {
        // XHuman 9 skeleton 1431: path SW,SW,S toward (13,121). First step
        // to (14,119) is shared; native's second SW lands fixture 26 while
        // ChonkCraft atMoveBoundary alone steps at 27. Retail Move slot 3 fires
        // opcode zero after twenty quiet body calls.
        byte[] script = retailScriptBin();
        GameMap map = grass(32);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.setBattleNetSequenceData(script);
        UnitType skeletonType = new UnitType("unit-skeleton");
        skeletonType.setTileSize(1, 1);
        skeletonType.setHitPoints(40);
        skeletonType.setSpeed(10);
        skeletonType.setLandUnit(true);
        skeletonType.setCanAttack(true);
        skeletonType.setCanTargetLand(true);
        skeletonType.setBasicDamage(6);
        skeletonType.setMaxAttackRange(1);
        skeletonType.setSightRange(5);
        AnimationSet animations = new AnimationSet("skeleton");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", SKELETON_MOVE));
        animations.put(AnimationSet.State.ATTACK,
                Animation.parse("attack", List.of(
                        "unbreakable begin", "frame 15", "wait 4",
                        "frame 30", "wait 4", "frame 45", "attack", "wait 4",
                        "unbreakable end", "wait 1")));
        skeletonType.setAnimationSet(animations);
        UnitType preyType = new UnitType("unit-footman");
        preyType.setTileSize(1, 1);
        preyType.setHitPoints(60);
        preyType.setLandUnit(true);
        Unit skeleton = world.createUnit(skeletonType, 0, 10, 10);
        Unit prey = world.createUnit(preyType, 1, 8, 13);
        assertTrue(skeleton != null, "skeleton must place at (10,10)");
        assertTrue(prey != null, "prey must place at (8,13)");
        assertTrue(world.orderAttack(skeleton, prey),
                "skeleton accepts the chase");
        int sw = net.chonkbase.chonkcraft.engine.map.Direction.fromDelta(-1, 1);
        int south = net.chonkbase.chonkcraft.engine.map.Direction.fromDelta(0, 1);
        // Stack: SW, SW, S (first consumed is last index).
        skeleton.setPath(new net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Path(
                net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Result.FOUND,
                new int[] {south, sw, sw}));
        skeleton.setOffset(0, 0);
        skeleton.setWalkHolding(false);
        skeleton.setBattleNetOrderDelay(0);

        Integer firstStep = null;
        Integer secondStep = null;
        for (int call = 0; call < 40; call++) {
            int xBefore = skeleton.tileX();
            int yBefore = skeleton.tileY();
            world.tick();
            if (skeleton.tileX() != xBefore || skeleton.tileY() != yBefore) {
                if (firstStep == null) {
                    firstStep = call;
                } else if (secondStep == null) {
                    secondStep = call;
                    break;
                }
            }
        }
        assertTrue(firstStep != null, "skeleton must take the first SW step");
        assertTrue(secondStep != null,
                "skeleton must take the second SW step; first at " + firstStep);
        int gap = secondStep - firstStep;
        // Retail Move body is 20 quiet calls between OP0s (first step arms
        // the body; second OP0 is twenty ticks later). ChonkCraft-only boundary
        // is 21.
        assertEquals(20, gap,
                "second chase heading must fire on retail Move OP0 (gap 20), not ChonkCraft wait-total 21");
        assertEquals(8, skeleton.tileX(),
                "two SW steps from (10,10) must land on (8,12)");
        assertEquals(12, skeleton.tileY(),
                "two SW steps from (10,10) must land on (8,12)");
    }

    @Test
    @DisplayName("a melee route ending on its quarry enters attack instead of refusal wait")
    void aMeleeRouteEndingOnItsQuarryEntersAttackInsteadOfRefusalWait()
            throws Exception {
        // XHuman 9 skeleton 1431 arrives beside footman 1427 while its last
        // cached S points at the occupied quarry square. Native changes from
        // Move to Attack@1188 immediately; it does not classify the target as
        // a movement blocker and serve the 23-cycle refusal hold.
        byte[] script = retailScriptBin();
        GameMap map = grass(128);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.setBattleNetSequenceData(script);

        UnitType skeletonType = new UnitType("unit-skeleton");
        skeletonType.setTileSize(1, 1);
        skeletonType.setHitPoints(40);
        skeletonType.setSpeed(10);
        skeletonType.setLandUnit(true);
        skeletonType.setCanAttack(true);
        skeletonType.setCanTargetLand(true);
        skeletonType.setBasicDamage(6);
        skeletonType.setMaxAttackRange(1);
        skeletonType.setSightRange(5);
        skeletonType.setNumDirections(8);
        AnimationSet animations = new AnimationSet("skeleton");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", SKELETON_MOVE));
        animations.put(AnimationSet.State.ATTACK,
                Animation.parse("attack", List.of(
                        "unbreakable begin", "frame 15", "wait 4",
                        "frame 30", "wait 4", "frame 45", "attack", "wait 4",
                        "unbreakable end", "wait 1")));
        skeletonType.setAnimationSet(animations);
        UnitType preyType = new UnitType("unit-footman");
        preyType.setTileSize(1, 1);
        preyType.setHitPoints(60);
        preyType.setLandUnit(true);

        Unit skeleton = world.createUnit(skeletonType, 0, 13, 120);
        Unit prey = world.createUnit(preyType, 1, 13, 121);
        assertTrue(skeleton != null && prey != null, "fighters must place");
        assertTrue(world.orderAttack(skeleton, prey), "attack accepted");
        int south = net.chonkbase.chonkcraft.engine.map.Direction.fromDelta(0, 1);
        skeleton.setPath(new net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Path(
                net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Result.FOUND,
                new int[] {south}));
        skeleton.setPathGoal(prey.tileX(), prey.tileY());
        skeleton.setChasing(true);
        skeleton.setStepDrained(true);
        skeleton.setOffset(0, 0);
        skeleton.animation().switchTo(
                skeleton.type().animationSet().get(AnimationSet.State.MOVE));
        int hpBefore = prey.hitPoints();

        world.movement.stepMove(skeleton, false);

        assertTrue(skeleton.fighting(), "occupied quarry is an attack arrival");
        assertTrue(!skeleton.chasing(), "arrival leaves chase state");
        assertEquals(0, skeleton.pathLength(), "target-ending route is consumed");
        assertEquals(0, skeleton.waitCycles(), "arrival does not arm refusal wait");
        assertEquals(1188, skeleton.battleNetSequenceOffset(),
                "retail arrival enters the attack body at post-OP0");
        assertEquals(1, skeleton.battleNetAnimationTimer(),
                "the attack body opens on the arrival visit");
        assertEquals(hpBefore, prey.hitPoints(),
                "arrival arms the attack; it does not strike in the same visit");
    }

    @Test
    @DisplayName("a melee arrival pays every borrowed walk pixel before attack owns the unit")
    void aMeleeArrivalPaysEveryBorrowedWalkPixelBeforeAttackOwnsTheUnit()
            throws Exception {
        // Pinned Orc 1 commanded fight, grunt 1592 versus footman 1595:
        // native carries ten final approach pixels at fixture 200, drains
        // 10,7,4,1,0, and opens the visible Attack animation at 204. The old
        // eight-pixel arrival band snapped that debt and opened at 201.
        byte[] script = retailScriptBin();
        GameMap map = grass(32);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.setBattleNetSequenceData(script);

        UnitType attackerType = knight();
        UnitType preyType = new UnitType("unit-footman");
        preyType.setTileSize(1, 1);
        preyType.setHitPoints(60);
        preyType.setLandUnit(true);
        Unit attacker = world.createUnit(attackerType, 0, 10, 10);
        Unit prey = world.createUnit(preyType, 1, 11, 10);
        assertTrue(attacker != null && prey != null, "fighters must place");
        assertTrue(world.orderAttack(attacker, prey), "attack accepted");

        int east = net.chonkbase.chonkcraft.engine.map.Direction.fromDelta(1, 0);
        attacker.setPath(new net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Path(
                net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Result.FOUND,
                new int[] {east}));
        attacker.setPathGoal(prey.tileX(), prey.tileY());
        attacker.setChasing(true);
        attacker.setFighting(false);
        attacker.setOffset(-8, 0);
        attacker.animation().switchTo(
                attacker.type().animationSet().get(AnimationSet.State.MOVE));

        world.movement.stepMove(attacker, false);
        assertTrue(attacker.chasing() && !attacker.fighting(),
                "a nonzero approach residual must remain owned by Move");
        assertTrue(attacker.offsetX() != 0,
                "the approach must not snap an eight-pixel residual to zero");

        // Model the retail residual-zero consult directly. The transition is
        // the property under test; animation pacing owns how the debt reached
        // zero and is covered independently above.
        attacker.setOffset(0, 0);
        world.movement.stepMove(attacker, false);
        assertTrue(attacker.fighting() && !attacker.chasing(),
                "the zero-debt consult must transfer action ownership");
        assertEquals(attacker.type().animationSet().get(AnimationSet.State.ATTACK),
                attacker.animation().current(),
                "the visible Attack animation changes on the ownership beat");
        assertEquals(0, attacker.pathLength(),
                "the occupied quarry heading is consumed at attack handoff");
    }

    @Test
    @DisplayName("a chaser crosses a square in the cycles its own move animation asks for")
    void aChaseKeepsTheAnimationsPace() {
        GameMap map = grass(40);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        Unit chaser = world.createUnit(knight(), 0, 5, 20);
        // Far enough off that the whole run is chasing rather than arriving,
        // and standing still so the route never changes underneath the walk.
        Unit quarry = world.createUnit(knight(), 1, 30, 20);
        assertTrue(world.orderAttack(chaser, quarry), "the attack was refused");

        List<Integer> arrivals = new ArrayList<>();
        int wasX = chaser.tileX();
        for (int cycle = 1; cycle <= 120; cycle++) {
            world.tick();
            if (chaser.tileX() != wasX) {
                arrivals.add(cycle);
                wasX = chaser.tileX();
            }
        }

        assertTrue(arrivals.size() >= 6,
                "the knight took " + arrivals.size() + " steps in 120 cycles, which is not"
                        + " enough of a run to measure a pace with");

        List<Integer> gaps = new ArrayList<>();
        for (int i = 1; i < arrivals.size(); i++) {
            gaps.add(arrivals.get(i) - arrivals.get(i - 1));
        }
        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < gaps.size(); i++) {
            expected.add(CYCLES_A_SQUARE);
        }

        assertEquals(expected, gaps,
                "the knight's steps landed " + gaps + " cycles apart. Its move animation"
                        + " spends 32 pixels over 12 cycles of wait, so 12 is the only"
                        + " answer; anything shorter is the walk being run more than once"
                        + " in a cycle, which shows up only on the cycle a step lands and"
                        + " the animation is briefly breakable again");
    }
}
