package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.animation.BattleNetSequence;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.pathfinder.PathFinder;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Residual of the first step after a melee chase replan holds before the next
 * heading.
 *
 * <p>Human 13 ogre 1482 tears up its knight path for the equal-score wise-man,
 * first-steps NW onto (124,32), drains residual, then stands through fixture
 * 31-33 (Attack animation four, timer 3) before SW at 34. Free-approach
 * continuous multi-step walks (ogre 1511) never hold between residual settles.
 * The hold is charged for tearing up a live route: flag set when a retarget
 * wipes multi-step path, armed when that residual settles.
 */
class MeleeChaseReplanResidualTest {

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static UnitType ogre() {
        UnitType type = new UnitType("unit-ogre");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(90);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(8);
        type.setPiercingDamage(4);
        type.setMaxAttackRange(1);
        type.setSightRange(6);
        type.setReactRangeComputer(6);
        type.setReactRangePerson(6);
        type.setNumDirections(8);
        AnimationSet set = new AnimationSet("ogre");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        // 32 pixels in two move ops so residual drains quickly in tests.
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 16", "wait 1",
                "frame 5", "move 16", "unbreakable end", "wait 1")));
        set.put(AnimationSet.State.ATTACK, Animation.parse("attack", List.of(
                "unbreakable begin", "frame 0", "attack", "unbreakable end",
                "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    private static UnitType prey() {
        UnitType type = new UnitType("unit-wise-man");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(90);
        type.setLandUnit(true);
        type.setNumDirections(8);
        AnimationSet set = new AnimationSet("prey");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    @Test
    @DisplayName("residual after a melee replan holds three visits before the next heading")
    void residualAfterAMeleeReplanHoldsThreeVisitsBeforeTheNextHeading() {
        // Ogre 1482 shape: first new-path step already taken, leftover headings
        // remain, replan-residual flag set, residual still owed on the landing
        // tile. Native holds three quiet visits after residual settles before
        // the next heading.
        GameMap map = grass(40);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        Unit chaser = world.createUnit(ogre(), 0, 20, 20);
        Unit quarry = world.createUnit(prey(), 1, 17, 17);
        assertTrue(chaser != null && quarry != null, "units must place");
        assertTrue(world.orderAttack(chaser, quarry), "attack accepted");

        int nw = Direction.fromDelta(-1, -1);
        // Landing tile residual of a completed NW step; two leftover headings.
        chaser.setTile(20, 20);
        chaser.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {nw, nw}));
        chaser.setPathGoal(quarry.tileX(), quarry.tileY());
        chaser.setTarget(quarry);
        chaser.setChasing(true);
        chaser.setAutoTargeting(true);
        chaser.setOffset(16, 16);
        chaser.setLastStepHeading(nw);
        chaser.setWalkHolding(true);
        chaser.setBattleNetChaseReplanResidualHold(true);
        chaser.setBattleNetOrderDelay(0);
        chaser.animation().switchTo(
                chaser.type().animationSet().get(AnimationSet.State.MOVE));

        int landX = chaser.tileX();
        int landY = chaser.tileY();
        Integer residualSettled = null;
        Integer nextStep = null;
        for (int call = 0; call < 40; call++) {
            world.tick();
            if (residualSettled == null
                    && !chaser.isMoving()
                    && chaser.tileX() == landX
                    && chaser.tileY() == landY) {
                residualSettled = call;
            }
            if (chaser.tileX() != landX || chaser.tileY() != landY) {
                nextStep = call;
                break;
            }
        }
        assertTrue(residualSettled != null,
                "replan residual must settle on the landing tile");
        assertTrue(nextStep != null,
                "chaser must eventually take the leftover heading");
        int hold = nextStep - residualSettled;
        // Settle visit arms delay 2; two quiet delay visits; step on the next.
        // Measured gap from first settled cycle to tile change is 3.
        assertEquals(3, hold,
                "melee replan residual must hold three visits after settle "
                        + "(native 1482: settle 31, step 34); held "
                        + hold + " from settle " + residualSettled
                        + " to step " + nextStep);
        assertEquals(landX + Direction.deltaX(nw), chaser.tileX(),
                "leftover heading must be the replan path's next NW");
        assertEquals(landY + Direction.deltaY(nw), chaser.tileY(),
                "leftover heading must be the replan path's next NW");
    }

    private static byte[] retailScriptBin() throws IOException {
        String packProp = System.getProperty("chonkcraft.pack");
        Path pack = packProp != null && !packProp.isBlank()
                ? Path.of(packProp)
                : Path.of(System.getProperty("user.home"),
                        ".chonkcraft/work",
                        "warcraft-ii-battle-net-edition-usa.pre-full-media-2026-07-30.chonkpack");
        assumeTrue(Files.isRegularFile(pack),
                "BNE asset pack required for retail Attack sequence");
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            var entry = zip.getEntry("assets/archives/maindat/0278.bin");
            assumeTrue(entry != null, "pack must contain maindat entry 278");
            try (var in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    @Test
    @DisplayName("an in-range leftover residual opens attack start the settle visit")
    void anInRangeLeftoverResidualOpensAttackStartTheSettleVisit()
            throws Exception {
        // Human 13 grunt 1485 residual-lands beside wise-man 1496 with one
        // leftover heading. Native opens Attack start 2539/3 that visit and
        // keeps the leftover through 3,2,1. Out-of-range replan residual
        // still pays delay 2 (1482).
        byte[] script = retailScriptBin();
        GameMap map = grass(24);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.setBattleNetSequenceData(script);

        Unit chaser = world.createUnit(ogre(), 0, 10, 10);
        Unit quarry = world.createUnit(prey(), 1, 10, 9);
        assertTrue(chaser != null && quarry != null, "units must place");
        assertTrue(world.orderAttack(chaser, quarry), "attack accepted");

        int north = Direction.fromDelta(0, -1);
        chaser.setTile(10, 10);
        chaser.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {north}));
        chaser.setPathGoal(quarry.tileX(), quarry.tileY());
        chaser.setTarget(quarry);
        chaser.setChasing(true);
        chaser.setFighting(false);
        chaser.setAutoTargeting(true);
        chaser.setOffset(16, 16);
        chaser.setLastStepHeading(north);
        chaser.setWalkHolding(true);
        chaser.setBattleNetChaseReplanResidualHold(true);
        chaser.setBattleNetOrderDelay(0);
        chaser.animation().switchTo(
                chaser.type().animationSet().get(AnimationSet.State.MOVE));
        int moveStart = world.idle.battleNetSequenceStart(chaser,
                BattleNetSequence.MOVE_ANIMATION);
        int attackStart = world.idle.battleNetSequenceStart(chaser,
                BattleNetSequence.ATTACK_ANIMATION);
        assertTrue(moveStart >= 0 && attackStart >= 0,
                "retail script must name Move and Attack starts");
        chaser.setBattleNetSequenceOffset(moveStart + 4);
        chaser.setBattleNetAnimationTimer(1);

        Integer settled = null;
        for (int call = 0; call < 12; call++) {
            world.tick();
            if (!chaser.isMoving()
                    && chaser.offsetX() == 0 && chaser.offsetY() == 0) {
                settled = call;
                break;
            }
        }
        assertTrue(settled != null,
                "leftover residual must settle on the landing tile");
        assertEquals(0, chaser.battleNetOrderDelay(),
                "in-range leftover residual must not pay Attack-four delay 2; "
                        + "delay=" + chaser.battleNetOrderDelay());
        assertEquals(attackStart, chaser.battleNetSequenceOffset(),
                "native opens Attack start construction on the settle visit");
        assertEquals(3, chaser.battleNetAnimationTimer(),
                "Attack start construction is timer 3 on the settle visit");
        assertEquals(1, chaser.pathLength(),
                "the leftover heading stays through construction 3");
        assertTrue(chaser.chasing(),
                "the leftover heading stays a chase leftover through 3,2,1");

        world.tick();
        assertEquals(1, chaser.pathLength(),
                "the leftover heading stays through construction 2");
        assertEquals(attackStart, chaser.battleNetSequenceOffset(),
                "construction 2 stays on Attack start");
        assertEquals(2, chaser.battleNetAnimationTimer(),
                "construction ticks 3 to 2");

        world.tick();
        assertEquals(1, chaser.pathLength(),
                "the leftover heading stays through construction 1");
        assertEquals(attackStart, chaser.battleNetSequenceOffset(),
                "construction 1 stays on Attack start");
        assertEquals(1, chaser.battleNetAnimationTimer(),
                "construction ticks 2 to 1");
        assertEquals(10, chaser.tileX(),
                "in-range leftover must not take the leftover heading");
        assertEquals(10, chaser.tileY(),
                "in-range leftover must not take the leftover heading");
    }

    @Test
    @DisplayName("a continuous melee chase residual does not hold three visits between headings")
    void aContinuousMeleeChaseResidualDoesNotHoldThreeVisitsBetweenHeadings() {
        // Ogre 1511 shape: multi-step path without replan flag. Residual
        // settle must not invent Attack-four holds between headings.
        GameMap map = grass(40);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        Unit chaser = world.createUnit(ogre(), 0, 20, 20);
        Unit quarry = world.createUnit(prey(), 1, 17, 17);
        assertTrue(chaser != null && quarry != null, "units must place");
        assertTrue(world.orderAttack(chaser, quarry), "attack accepted");

        int nw = Direction.fromDelta(-1, -1);
        chaser.setTile(20, 20);
        chaser.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {nw, nw}));
        chaser.setPathGoal(quarry.tileX(), quarry.tileY());
        chaser.setTarget(quarry);
        chaser.setChasing(true);
        chaser.setAutoTargeting(true);
        chaser.setOffset(16, 16);
        chaser.setLastStepHeading(nw);
        chaser.setWalkHolding(true);
        chaser.setBattleNetChaseReplanResidualHold(false);
        chaser.setBattleNetOrderDelay(0);
        chaser.animation().switchTo(
                chaser.type().animationSet().get(AnimationSet.State.MOVE));

        int landX = chaser.tileX();
        int landY = chaser.tileY();
        Integer residualSettled = null;
        Integer nextStep = null;
        for (int call = 0; call < 40; call++) {
            world.tick();
            if (residualSettled == null
                    && !chaser.isMoving()
                    && chaser.tileX() == landX
                    && chaser.tileY() == landY) {
                residualSettled = call;
            }
            if (chaser.tileX() != landX || chaser.tileY() != landY) {
                nextStep = call;
                break;
            }
        }
        assertTrue(residualSettled != null, "residual must settle");
        assertTrue(nextStep != null, "continuous chase must take the next heading");
        int hold = nextStep - residualSettled;
        assertTrue(hold < 3,
                "continuous multi-step residual must not invent a three-visit "
                        + "replan hold; held " + hold);
    }

    @Test
    @DisplayName("a residual pathn-three refuse survives the allied chase visit-order boundary")
    void aResidualPathnThreeRefuseSurvivesTheAlliedChaseVisitOrderBoundary() {
        // retail-xhuman-10-idle axe 1478: pathn 3 NE onto grunt 1482. Java
        // visits the axe before the grunt at the drained route boundary;
        // retail visits the corresponding slots in the opposite order. Hard
        // replan found E; native retained NE and took it after the grunt left.
        GameMap map = grass(40);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        UnitType axeType = new UnitType("unit-axethrower");
        axeType.setTileSize(1, 1);
        axeType.setBoxSize(31, 31);
        axeType.setHitPoints(40);
        axeType.setSpeed(10);
        axeType.setLandUnit(true);
        axeType.setCanAttack(true);
        axeType.setCanTargetLand(true);
        axeType.setBasicDamage(3);
        axeType.setPiercingDamage(4);
        axeType.setMaxAttackRange(4);
        axeType.setSightRange(5);
        axeType.setReactRangeComputer(5);
        axeType.setReactRangePerson(5);
        axeType.setNumDirections(8);
        AnimationSet set = new AnimationSet("axe");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 16", "wait 1",
                "frame 5", "move 16", "unbreakable end", "wait 1")));
        set.put(AnimationSet.State.ATTACK, Animation.parse("attack", List.of(
                "unbreakable begin", "frame 0", "attack", "unbreakable end",
                "wait 1")));
        axeType.setAnimationSet(set);
        Unit chaser = world.createUnit(axeType, 0, 20, 20);
        Unit quarry = world.createUnit(prey(), 1, 24, 18);
        Unit ally = world.createUnit(ogre(), 0, 21, 19);
        assertTrue(chaser != null && quarry != null && ally != null,
                "units must place");
        assertTrue(world.orderAttack(chaser, quarry), "attack accepted");
        int ne = Direction.fromDelta(1, -1);
        int east = Direction.fromDelta(1, 0);
        chaser.setTile(20, 20);
        chaser.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {east, ne, ne}));
        chaser.setPathGoal(quarry.tileX(), quarry.tileY());
        chaser.setTarget(quarry);
        chaser.setChasing(true);
        chaser.setOffset(0, 0);
        chaser.setStepDrained(true);
        chaser.setBattleNetOrderDelay(0);
        chaser.animation().switchTo(
                chaser.type().animationSet().get(AnimationSet.State.MOVE));
        // The allied chaser has drained the previous route and is about to
        // rebuild. Retail has already made that visit when it asks about the
        // axe; Java has not, solely because its pool is traversed oppositely.
        ally.clearPath();
        ally.setChasing(true);
        ally.setStepDrained(true);
        ally.setOffset(0, 0);
        ally.setStepDrained(true);
        ally.animation().switchTo(
                ally.type().animationSet().get(AnimationSet.State.MOVE));
        world.movement.stepMove(chaser, false);
        assertEquals(1, chaser.battleNetOrderDelay(),
                "pathn-3 refuse at the allied chase visit-order boundary must quiet once; "
                        + "delay=" + chaser.battleNetOrderDelay());
        assertEquals(3, chaser.pathLength(),
                "must keep the NE leftover for the next visit");
        assertEquals(20, chaser.tileX(), "must not step early");
        assertEquals(20, chaser.tileY(), "must not step early");
    }

    @Test
    @DisplayName("a nearly-full ranged soft-wait wakes when its planned next cell frees")
    void aNearlyFullRangedSoftWaitWakesWhenItsPlannedNextCellFrees() {
        // retail-xhuman-04-idle axe 1490: pathn 6 W under delay 14 while ally
        // occupies 77,62. Native steps W at fixture 40 once free; blind
        // countdown expired at 54. pathn < 6 must not wake (1496 REG).
        GameMap map = grass(40);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        UnitType axeType = new UnitType("unit-axethrower");
        axeType.setTileSize(1, 1);
        axeType.setBoxSize(31, 31);
        axeType.setHitPoints(40);
        axeType.setSpeed(10);
        axeType.setLandUnit(true);
        axeType.setCanAttack(true);
        axeType.setCanTargetLand(true);
        axeType.setBasicDamage(3);
        axeType.setPiercingDamage(4);
        axeType.setMaxAttackRange(4);
        axeType.setSightRange(5);
        axeType.setReactRangeComputer(5);
        axeType.setReactRangePerson(5);
        axeType.setNumDirections(8);
        AnimationSet set = new AnimationSet("axe");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 16", "wait 1",
                "frame 5", "move 16", "unbreakable end", "wait 1")));
        set.put(AnimationSet.State.ATTACK, Animation.parse("attack", List.of(
                "unbreakable begin", "frame 0", "attack", "unbreakable end",
                "wait 1")));
        axeType.setAnimationSet(set);
        Unit chaser = world.createUnit(axeType, 0, 20, 20);
        Unit quarry = world.createUnit(prey(), 1, 14, 20);
        Unit ally = world.createUnit(ogre(), 0, 19, 20);
        assertTrue(chaser != null && quarry != null && ally != null,
                "units must place");
        assertTrue(world.orderAttack(chaser, quarry), "attack accepted");
        int west = Direction.fromDelta(-1, 0);
        chaser.setTile(20, 20);
        chaser.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {west, west, west, west, west, west}));
        chaser.setPathGoal(quarry.tileX(), quarry.tileY());
        chaser.setTarget(quarry);
        chaser.setChasing(true);
        chaser.setOffset(0, 0);
        chaser.setStepDrained(true);
        chaser.setBattleNetCollisionCounter(1);
        chaser.setBattleNetOrderDelay(10);
        chaser.animation().switchTo(
                chaser.type().animationSet().get(AnimationSet.State.MOVE));
        // Ally blocks W.
        ally.setOffset(0, 0);
        ally.animation().switchTo(
                ally.type().animationSet().get(AnimationSet.State.STILL));
        world.tick();
        assertTrue(chaser.battleNetOrderDelay() > 0,
                "must keep soft-wait while W is occupied");
        world.markOccupancy(ally, false);
        ally.setTile(18, 20);
        world.markOccupancy(ally, true);
        // Ensure the soft-wait arm still sees a remaining delay and coll.
        if (chaser.battleNetOrderDelay() <= 0) {
            chaser.setBattleNetOrderDelay(8);
        }
        if (chaser.battleNetCollisionCounter() <= 0) {
            chaser.setBattleNetCollisionCounter(1);
        }
        world.tick();
        assertEquals(0, chaser.battleNetOrderDelay(),
                "nearly-full ranged soft-wait must clear when planned W is "
                        + "free; delay=" + chaser.battleNetOrderDelay());
    }

    @Test
    @DisplayName("a three-heading axis soft-wait free-detours on a closer diagonal")
    void aThreeHeadingAxisSoftWaitFreeDetoursOnACloserDiagonal() {
        // retail-xhuman-10-idle grunt 1482: leftover EEE under soft-wait at
        // (78,89) with free SE onto (79,88). Native steps SE at fixture 40;
        // pathn==1 vacating early-wake never saw the free diagonal.
        GameMap map = grass(40);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        Unit chaser = world.createUnit(ogre(), 0, 20, 20);
        Unit quarry = world.createUnit(prey(), 1, 24, 19);
        Unit ally = world.createUnit(ogre(), 0, 21, 20);
        assertTrue(chaser != null && quarry != null && ally != null,
                "units must place");
        assertTrue(world.orderAttack(chaser, quarry), "attack accepted");
        int east = Direction.fromDelta(1, 0);
        chaser.setTile(20, 20);
        chaser.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {east, east, east}));
        chaser.setPathGoal(quarry.tileX(), quarry.tileY());
        chaser.setTarget(quarry);
        chaser.setChasing(true);
        chaser.setOffset(0, 0);
        chaser.setStepDrained(true);
        chaser.setBattleNetCollisionCounter(1);
        chaser.setBattleNetOrderDelay(10);
        chaser.animation().switchTo(
                chaser.type().animationSet().get(AnimationSet.State.MOVE));
        ally.animation().switchTo(
                ally.type().animationSet().get(AnimationSet.State.STILL));

        // Soft-wait visit with free SE closer: orderAttack path installs the
        // free diagonal and clears delay.
        world.tick();
        assertEquals(0, chaser.battleNetOrderDelay(),
                "axis pathn-3 soft-wait must clear delay when a free diagonal "
                        + "closer exists; delay=" + chaser.battleNetOrderDelay());
    }

    @Test
    @DisplayName("a chaser that sidesteps around a blocking ally drops the rest of its old route")
    void aSidestepAroundABlockingAllyDropsTheRestOfTheOldRoute() {
        // retail-xhuman-12-idle grunt 1507 used to carry the remainder of its
        // old route underneath the sidestep it took past a blocking ally. The
        // carried remainder pointed south-east into a cell that was still
        // occupied, so the grunt settled, replanned and stepped east on the
        // same cycle -- three cycles before the retail engine, which reaches
        // that same tile at cycle 55 and not at 52. A sidestep replaces the
        // route it detoured around; it does not prepend itself to it.
        GameMap map = grass(40);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        Unit chaser = world.createUnit(ogre(), 0, 20, 20);
        Unit quarry = world.createUnit(prey(), 1, 25, 20);
        Unit ally = world.createUnit(ogre(), 0, 21, 20);
        assertTrue(chaser != null && quarry != null && ally != null,
                "units must place");
        assertTrue(world.orderAttack(chaser, quarry), "attack accepted");
        int east = Direction.fromDelta(1, 0);
        int north = Direction.fromDelta(0, -1);
        ally.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {east, east}));
        ally.setPathGoal(23, 20);
        ally.setOffset(1, 0);
        ally.setWalkHolding(true);
        ally.animation().switchTo(
                ally.type().animationSet().get(AnimationSet.State.MOVE));
        chaser.setTile(20, 20);
        // Multi leftover with E first (ally-blocked).
        chaser.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {east, east, east, east}));
        chaser.setPathGoal(quarry.tileX(), quarry.tileY());
        chaser.setTarget(quarry);
        chaser.setChasing(true);
        chaser.setOffset(0, 0);
        chaser.setStepDrained(true);
        chaser.setBattleNetCollisionCounter(0);
        chaser.setBattleNetChaseEmptyRouteReplan(true);
        chaser.setBattleNetOrderDelay(0);
        chaser.animation().switchTo(
                chaser.type().animationSet().get(AnimationSet.State.MOVE));

        world.movement.stepMove(chaser, false);
        // The sidestep may still be pending as a single heading, or already
        // spent on this cycle. Either way nothing of the old eastward route
        // survives under it.
        assertTrue(chaser.pathLength() <= 1,
                "a sidestep past a blocking ally must drop the rest of the "
                        + "old route, not carry it underneath; the chaser "
                        + "still holds " + chaser.pathLength() + " headings");
        if (chaser.pathLength() == 1) {
            assertEquals(north, chaser.peekHeading(),
                    "with east held by the ally the chaser sidesteps north");
        } else {
            assertEquals(20, chaser.tileX(),
                    "a northward sidestep does not change the chaser's column");
            assertEquals(19, chaser.tileY(),
                    "the chaser sidesteps onto the open cell to its north");
        }
    }

    @Test
    @DisplayName("a single heading chase replans after one cooperative soft wait")
    void aSingleHeadingChaseReplansAfterOneCooperativeSoftWait() {
        // retail-xhuman-12-idle grunt 1503: SE leftover soft-waits once, then
        // native route_index 20 and multi-step E. Retrying SE with delay 14
        // forever left Java at 31,38 until ~79. setPath resets the collision
        // counter so each generation soft-waits once (counter 1) then replans
        // on the second refuse (counter 2).
        GameMap map = grass(40);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        Unit chaser = world.createUnit(ogre(), 0, 20, 20);
        Unit quarry = world.createUnit(prey(), 1, 25, 22);
        Unit ally = world.createUnit(ogre(), 0, 21, 21);
        assertTrue(chaser != null && quarry != null && ally != null,
                "units must place");
        assertTrue(world.orderAttack(chaser, quarry), "attack accepted");
        int se = Direction.fromDelta(1, 1);
        int east = Direction.fromDelta(1, 0);
        ally.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {east, east, east}));
        ally.setPathGoal(24, 21);
        ally.setChasing(true);
        ally.setOffset(0, 0);
        ally.animation().switchTo(
                ally.type().animationSet().get(AnimationSet.State.MOVE));
        chaser.setTile(20, 20);
        chaser.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {se}));
        // Soft-wait already spent on this generation.
        chaser.setBattleNetCollisionCounter(1);
        chaser.setPathGoal(quarry.tileX(), quarry.tileY());
        chaser.setTarget(quarry);
        chaser.setChasing(true);
        chaser.setOffset(0, 0);
        chaser.setStepDrained(true);
        chaser.setBattleNetOrderDelay(0);
        chaser.animation().switchTo(
                chaser.type().animationSet().get(AnimationSet.State.MOVE));

        world.movement.stepMove(chaser, false);
        assertEquals(0, chaser.pathLength(),
                "second refuse on a one-heading leftover must clear for replan");
        assertTrue(chaser.battleNetChaseEmptyRouteReplan(),
                "empty-route replan arms the next chase path consult");
        assertEquals(0, chaser.battleNetOrderDelay(),
                "must not re-arm delay 14 after the soft-wait; delay="
                        + chaser.battleNetOrderDelay());
        assertEquals(20, chaser.tileX(), "replan must not step early");
        assertEquals(20, chaser.tileY(), "replan must not step early");
    }

    @Test
    @DisplayName("a multi-step residual soft-wait replans only while peek stays blocked")
    void aMultiStepResidualSoftWaitReplansOnlyWhilePeekStaysBlocked() {
        // retail-xhuman-12-idle grunt 1510: residual W,SW,S,S soft-waits pure
        // W onto ally 1512; after one wait W is still blocked so native
        // route_index 20 + Attack-four then SE@41. Replanning when peek has
        // freed REG'd grunt 1453 at fixture 36.
        GameMap map = grass(40);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        // Geometry matches sealed 1510: chaser at (33,39) equiv, quarry SW,
        // ally on W, S occupied, SW wall, free SE closer by Chebyshev.
        Unit chaser = world.createUnit(ogre(), 0, 20, 20);
        Unit quarry = world.createUnit(prey(), 1, 17, 25);
        Unit ally = world.createUnit(ogre(), 0, 19, 20);
        Unit southAlly = world.createUnit(ogre(), 0, 20, 21);
        assertTrue(chaser != null && quarry != null && ally != null
                        && southAlly != null,
                "units must place");
        assertTrue(world.orderAttack(chaser, quarry), "attack accepted");
        int west = Direction.fromDelta(-1, 0);
        int sw = Direction.fromDelta(-1, 1);
        int south = Direction.fromDelta(0, 1);
        int se = Direction.fromDelta(1, 1);
        ally.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {west, west, west}));
        ally.setPathGoal(16, 20);
        ally.setChasing(true);
        ally.setOffset(0, 0);
        ally.animation().switchTo(
                ally.type().animationSet().get(AnimationSet.State.MOVE));
        southAlly.animation().switchTo(
                southAlly.type().animationSet().get(AnimationSet.State.STILL));
        // SW wall like native 0x31b.
        map.field(19, 21).setFlags(TileFlag.UNPASSABLE | TileFlag.WALL);
        chaser.setTile(20, 20);
        chaser.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {south, south, sw, west}));
        chaser.setBattleNetCollisionCounter(1);
        chaser.setPathGoal(quarry.tileX(), quarry.tileY());
        chaser.setTarget(quarry);
        chaser.setChasing(true);
        chaser.setOffset(0, 0);
        chaser.setStepDrained(true);
        chaser.setBattleNetOrderDelay(0);
        chaser.animation().switchTo(
                chaser.type().animationSet().get(AnimationSet.State.MOVE));

        // Second refuse, W still occupied: free-compass SE (closer) + A4.
        world.movement.stepMove(chaser, false);
        assertEquals(1, chaser.pathLength(),
                "still-blocked multi-step residual installs one free heading");
        assertEquals(se, chaser.peekHeading(),
                "closer free-compass must pick SE; heading="
                        + chaser.peekHeading());
        assertEquals(3, chaser.battleNetOrderDelay(),
                "still-blocked multi-step residual pays Attack-four 3; delay="
                        + chaser.battleNetOrderDelay());
        assertEquals(20, chaser.tileX(), "replan must not step early");
        assertEquals(20, chaser.tileY(), "replan must not step early");

        // Peek freed during the wait: keep residual, do not replan.
        Unit chaser2 = world.createUnit(ogre(), 0, 25, 25);
        Unit quarry2 = world.createUnit(prey(), 1, 22, 29);
        assertTrue(chaser2 != null && quarry2 != null, "second pair places");
        assertTrue(world.orderAttack(chaser2, quarry2), "second attack ok");
        chaser2.setTile(25, 25);
        chaser2.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {south, south, sw, west}));
        chaser2.setBattleNetCollisionCounter(1);
        chaser2.setPathGoal(quarry2.tileX(), quarry2.tileY());
        chaser2.setTarget(quarry2);
        chaser2.setChasing(true);
        chaser2.setOffset(0, 0);
        chaser2.setStepDrained(true);
        chaser2.setBattleNetOrderDelay(0);
        chaser2.animation().switchTo(
                chaser2.type().animationSet().get(AnimationSet.State.MOVE));
        world.movement.stepMove(chaser2, false);
        assertTrue(chaser2.pathLength() > 0,
                "freed multi-step residual must keep the route, not replan");
        assertFalse(chaser2.battleNetChaseEmptyRouteReplan(),
                "freed peek must not arm empty-route replan");
    }

    @Test
    @DisplayName("a replan residual cooperative refuse waits seventeen visits not fourteen")
    void aReplanResidualCooperativeRefuseWaitsSeventeenVisitsNotFourteen() {
        // XHuman 12 grunt 1495: residual settles with replan armed and the
        // next tile holds a cooperative ally. FUN_004379e0's fourteen quiet
        // visits alone stepped at fixture 34; native also owes Attack-four
        // (three more) before the first new heading at 37.
        assertEquals(14, World.battleNetCooperativeRefuseQuietVisits(false),
                "ordinary cooperative refuse is fourteen quiet visits");
        assertEquals(17, World.battleNetCooperativeRefuseQuietVisits(true),
                "melee replan residual + cooperative refuse is seventeen "
                        + "quiet visits (fourteen + three Attack-four); "
                        + "XHuman 12 grunt 1495 was three short at fixture 34");
    }

    @Test
    @DisplayName("a residual nearly-full leftover free-compasses north past a standing ally")
    void aResidualNearlyFullLeftoverFreeCompassesNorthPastAStandingAlly() {
        // retail-xhuman-12-idle grunt 1506: residual of SE onto (28,40) leaves
        // pathn 5 starting NE onto standing ally 105 at (29,39). N at (28,39)
        // is free after that ally vacated. Non-MOVE allies are not cooperative,
        // so hard clear+replan looped NE until fixture 52; native steps N at 39.
        // Only the free cardinal component of the blocked diagonal is taken --
        // open free-compass REG'd grunt 1512 (blocked N, free NE early at 35).
        GameMap map = grass(40);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        Unit chaser = world.createUnit(ogre(), 0, 20, 20);
        Unit quarry = world.createUnit(prey(), 1, 22, 16);
        // Standing ally on NE -- Still anim, not cooperative soft-wait.
        Unit ally = world.createUnit(ogre(), 0, 21, 19);
        assertTrue(chaser != null && quarry != null && ally != null,
                "units must place");
        assertTrue(world.orderAttack(chaser, quarry), "attack accepted");

        int ne = Direction.fromDelta(1, -1);
        int se = Direction.fromDelta(1, 1);
        int south = Direction.fromDelta(0, 1);
        chaser.setTile(20, 20);
        // Nearly-full leftover after one prior step (pathn 5). Path storage
        // is reverse order: last element is the next heading to take.
        chaser.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {south, south, south, se, ne}));
        chaser.setPathGoal(quarry.tileX(), quarry.tileY());
        chaser.setTarget(quarry);
        chaser.setChasing(true);
        chaser.setOffset(0, 0);
        chaser.setLastStepHeading(se);
        chaser.setWalkHolding(false);
        chaser.setStepDrained(true);
        chaser.setBattleNetCollisionCounter(0);
        chaser.setBattleNetOrderDelay(0);
        chaser.animation().switchTo(
                chaser.type().animationSet().get(AnimationSet.State.MOVE));
        // Ally is standing (Still), so cooperative soft-wait does not arm.
        ally.animation().switchTo(
                ally.type().animationSet().get(AnimationSet.State.STILL));

        world.movement.stepMove(chaser, false);
        assertEquals(20, chaser.tileX(),
                "first residual refuse must not step early");
        assertEquals(20, chaser.tileY(),
                "first residual refuse quiets one visit; native 1506 still "
                        + "at (28,40) on fixture 38");
        assertEquals(1, chaser.battleNetCollisionCounter(),
                "collision marks the residual refuse so the next visit steps");
        assertTrue(chaser.pathLength() >= 5,
                "path must be kept for the next-visit component step");

        world.movement.stepMove(chaser, false);
        assertEquals(20, chaser.tileX(),
                "free-compass north must keep the east column");
        assertEquals(19, chaser.tileY(),
                "second visit steps the free N component onto the vacated "
                        + "cell; y=" + chaser.tileY());
    }

    @Test
    @DisplayName("a replan residual off-axis free-compass pays attack-four remainder before stepping")
    void aReplanResidualOffAxisFreeCompassPaysAttackFourRemainderBeforeStepping() {
        // retail-xhuman-12-idle grunt 1512: residual retarget leaves pathn 5+
        // starting SE onto standing ally with S terrain-blocked and E occupied
        // while free NE exists. Free-component finds nothing; open free-compass
        // same-cycle REG'd NE at fixture 35; delay 2 stepped at 38; native NE
        // at 40. Replan residual hold arms Attack-four plus one quiet refuse
        // as delay 4 so the step lands at 40.
        GameMap map = grass(40);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        Unit chaser = world.createUnit(ogre(), 0, 20, 20);
        Unit quarry = world.createUnit(prey(), 1, 18, 24);
        // Standing ally on SE -- Still anim, not cooperative.
        Unit ally = world.createUnit(ogre(), 0, 21, 21);
        assertTrue(chaser != null && quarry != null && ally != null,
                "units must place");
        assertTrue(world.orderAttack(chaser, quarry), "attack accepted");

        int se = Direction.fromDelta(1, 1);
        int ne = Direction.fromDelta(1, -1);
        int south = Direction.fromDelta(0, 1);
        int east = Direction.fromDelta(1, 0);
        // Block S with terrain so free-component of SE cannot take S.
        map.field(20, 21).setFlags(TileFlag.WATER_ALLOWED);
        // Block N and E with standing allies so free-component has no axis
        // rescue and free-compass's first free dir is NE (dir order N..).
        Unit allyNorth = world.createUnit(ogre(), 0, 20, 19);
        Unit allyEast = world.createUnit(ogre(), 0, 21, 20);
        assertTrue(allyNorth != null && allyEast != null,
                "blocking allies must place");
        allyNorth.animation().switchTo(
                allyNorth.type().animationSet().get(AnimationSet.State.STILL));
        allyEast.animation().switchTo(
                allyEast.type().animationSet().get(AnimationSet.State.STILL));

        chaser.setTile(20, 20);
        chaser.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {south, south, south, east, se}));
        chaser.setPathGoal(quarry.tileX(), quarry.tileY());
        chaser.setTarget(quarry);
        chaser.setChasing(true);
        chaser.setOffset(0, 0);
        chaser.setStepDrained(true);
        chaser.setBattleNetChaseReplanResidualHold(true);
        chaser.setBattleNetCollisionCounter(0);
        chaser.setBattleNetOrderDelay(0);
        chaser.animation().switchTo(
                chaser.type().animationSet().get(AnimationSet.State.MOVE));
        ally.animation().switchTo(
                ally.type().animationSet().get(AnimationSet.State.STILL));

        world.movement.stepMove(chaser, false);
        assertEquals(20, chaser.tileX(),
                "off-axis free-compass must not step same-cycle");
        assertEquals(20, chaser.tileY(),
                "off-axis free-compass must not step same-cycle");
        assertEquals(4, chaser.battleNetOrderDelay(),
                "replan residual off-axis free-compass pays delay 4; delay="
                        + chaser.battleNetOrderDelay());
        assertEquals(ne, chaser.peekHeading(),
                "must install free NE while paying Attack-four remainder");
        assertFalse(chaser.battleNetChaseReplanResidualHold(),
                "replan residual hold is spent with the free-compass arm");

        // Combat blind-countdown burns the delay without stepping.
        chaser.setBattleNetOrderDelay(0);
        world.movement.stepMove(chaser, false);
        assertEquals(21, chaser.tileX(),
                "after the Attack-four remainder free NE must step east");
        assertEquals(19, chaser.tileY(),
                "after the Attack-four remainder free NE must step north; y="
                        + chaser.tileY());
    }

    @Test
    @DisplayName("a free residual replan hold is not free-woken by a free leftover heading")
    void aFreeResidualReplanHoldIsNotFreeWokenByAFreeLeftoverHeading() {
        // retail-xhuman-12-idle grunt 1505: replan residual Attack-four (delay 2,
        // coll 0) lands with free leftover S. Pathn1 free-wake used to clear
        // that hold and step at fixture 42 while native holds through 43 and
        // steps only at 44. Soft-wait free-wake still requires coll>=1.
        GameMap map = grass(40);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        Unit chaser = world.createUnit(ogre(), 0, 20, 20);
        Unit quarry = world.createUnit(prey(), 1, 20, 24);
        assertTrue(chaser != null && quarry != null, "units must place");
        assertTrue(world.orderAttack(chaser, quarry), "attack accepted");

        int south = Direction.fromDelta(0, 1);
        chaser.setTile(20, 20);
        chaser.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {south}));
        chaser.setPathGoal(quarry.tileX(), quarry.tileY());
        chaser.setTarget(quarry);
        chaser.setChasing(true);
        chaser.setOffset(0, 0);
        chaser.setStepDrained(true);
        chaser.setBattleNetCollisionCounter(0);
        chaser.setBattleNetOrderDelay(2);
        chaser.animation().switchTo(
                chaser.type().animationSet().get(AnimationSet.State.MOVE));

        world.combat.stepAttack(chaser);
        assertEquals(20, chaser.tileX(),
                "Attack-four free residual must not step on the first quiet");
        assertEquals(20, chaser.tileY(),
                "Attack-four free residual must not step on the first quiet");
        assertEquals(1, chaser.battleNetOrderDelay(),
                "free-wake must not clear delay 2 when coll is 0; delay="
                        + chaser.battleNetOrderDelay());

        world.combat.stepAttack(chaser);
        assertEquals(20, chaser.tileX(),
                "Attack-four free residual must not step on the second quiet");
        assertEquals(20, chaser.tileY(),
                "Attack-four free residual must not step on the second quiet");
        assertEquals(0, chaser.battleNetOrderDelay(),
                "second quiet burns delay 2 to 0; delay="
                        + chaser.battleNetOrderDelay());
    }

    @Test
    @DisplayName("a residual settled one-heading refuse free-compasses north with one quiet")
    void aResidualSettledOneHeadingRefuseFreeCompassesNorthWithOneQuiet() {
        // retail-xhuman-12-idle grunt 1514: residual of E onto 28,38 leaves
        // pathn 1 SE onto ally; no free-progress toward 32,43; native RI20 and
        // free-compass N@42. Soft-wait/PF_WAIT held Java until ~52. Coll quiet
        // after free-compass N matches residual settle then N@42.
        GameMap map = grass(40);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        Unit chaser = world.createUnit(ogre(), 0, 20, 20);
        // Goal SE so free N is not free-progress.
        Unit quarry = world.createUnit(prey(), 1, 24, 24);
        // Standing ally on SE; wall progressive free neighbours so free-
        // progress cannot steal the free-compass N step.
        Unit allySe = world.createUnit(ogre(), 0, 21, 21);
        assertTrue(chaser != null && quarry != null && allySe != null,
                "units must place");
        assertTrue(world.orderAttack(chaser, quarry), "attack accepted");
        for (int[] cell : new int[][] {
                {21, 20}, {20, 21}, {21, 19}, {19, 21}}) {
            map.field(cell[0], cell[1]).setFlags(
                    TileFlag.UNPASSABLE | TileFlag.WALL);
        }

        int se = Direction.fromDelta(1, 1);
        int north = Direction.fromDelta(0, -1);
        chaser.setTile(20, 20);
        chaser.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {se}));
        chaser.setPathGoal(quarry.tileX(), quarry.tileY());
        chaser.setTarget(quarry);
        chaser.setChasing(true);
        chaser.setAutoTargeting(true);
        // Residual of the prior E step has just settled: one leftover SE onto
        // the standing ally, offsets already zero, Move at OP0 boundary so
        // the residual-settled pathn1 arm can free-compass N.
        chaser.setOffset(1, 0);
        chaser.setLastStepHeading(Direction.fromDelta(1, 0));
        chaser.setWalkHolding(true);
        chaser.setStepDrained(false);
        chaser.setBattleNetCollisionCounter(0);
        chaser.setBattleNetOrderDelay(0);
        chaser.setBattleNetChaseStepReady(true);
        chaser.animation().switchTo(
                chaser.type().animationSet().get(AnimationSet.State.MOVE));
        allySe.animation().switchTo(
                allySe.type().animationSet().get(AnimationSet.State.STILL));

        // One walkPixels of the leftover offset marks actionMoveWalked and
        // settles residual so free-scan sees residual-settled pathn1.
        world.combat.stepAttack(chaser);
        assertEquals(20, chaser.tileX(),
                "residual-settled pathn1 free-compass must quiet one visit");
        assertEquals(20, chaser.tileY(),
                "residual-settled pathn1 free-compass must quiet one visit");
        assertEquals(1, chaser.pathLength(),
                "must install free N leftover; pathn=" + chaser.pathLength());
        assertEquals(north, chaser.peekHeading(),
                "free-compass first free neighbour is N; heading="
                        + chaser.peekHeading());
        assertEquals(1, chaser.battleNetCollisionCounter(),
                "collision marks the residual free-compass quiet");
        // Step timing of the free residual N is covered by the sealed case
        // cadence (native N@42); the quiet refuse is the new residual arm.
    }

    @Test
    @DisplayName("a residual one-heading leftover free-progresses past a standing ally")
    void aResidualOneHeadingLeftoverFreeProgressesPastAStandingAlly() {
        // retail-xhuman-12-idle grunt 1375: residual of E onto (11,85) leaves
        // pathn 1 E onto standing ally 1379 at (12,85); free SE onto (12,86)
        // is native's third step at fixture 40. PF_WAIT 10 popped E and slept
        // until fixture 50. One coll quiet then free-progress SE.
        GameMap map = grass(40);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        Unit chaser = world.createUnit(ogre(), 0, 20, 20);
        Unit quarry = world.createUnit(prey(), 1, 24, 22);
        Unit ally = world.createUnit(ogre(), 0, 21, 20);
        assertTrue(chaser != null && quarry != null && ally != null,
                "units must place");
        assertTrue(world.orderAttack(chaser, quarry), "attack accepted");

        int east = Direction.fromDelta(1, 0);
        int se = Direction.fromDelta(1, 1);
        chaser.setTile(20, 20);
        chaser.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {east}));
        chaser.setPathGoal(quarry.tileX(), quarry.tileY());
        chaser.setTarget(quarry);
        chaser.setChasing(true);
        chaser.setOffset(0, 0);
        chaser.setStepDrained(true);
        chaser.setBattleNetCollisionCounter(0);
        chaser.setBattleNetOrderDelay(0);
        chaser.animation().switchTo(
                chaser.type().animationSet().get(AnimationSet.State.MOVE));
        ally.animation().switchTo(
                ally.type().animationSet().get(AnimationSet.State.STILL));

        world.movement.stepMove(chaser, false);
        assertEquals(20, chaser.tileX(),
                "first residual refuse must not step early");
        assertEquals(20, chaser.tileY(),
                "first residual refuse quiets one visit");
        assertEquals(1, chaser.battleNetCollisionCounter(),
                "collision marks the residual pathn-1 refuse");
        assertEquals(1, chaser.pathLength(),
                "must keep the leftover for the free-progress step");

        world.movement.stepMove(chaser, false);
        assertEquals(21, chaser.tileX(),
                "free-progress SE must step east past the standing ally");
        assertEquals(21, chaser.tileY(),
                "free-progress SE must step south; y=" + chaser.tileY());
        assertEquals(se, chaser.lastStepHeading(),
                "step heading must be free-progress SE not the blocked E");
    }

    @Test
    @DisplayName("residual path-two chase refuse with collision pays timer fifteen before replan")
    void residualPathTwoChaseRefuseWithCollisionPaysTimerFifteenBeforeReplan() {
        // retail-xhuman-10-idle grunt 1486: residual-settled leftover EE with
        // collision already 1 refuses E solid, native writes route_index 20
        // then arms timer 15 before the empty-route replan steps (SE at
        // fixture 53). Same-cycle clear+replan free-detoured N at 38.
        GameMap map = grass(40);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        Unit chaser = world.createUnit(ogre(), 0, 20, 20);
        Unit quarry = world.createUnit(prey(), 1, 25, 20);
        // Solid ally on the leftover E cell (not cooperative-moving).
        Unit ally = world.createUnit(ogre(), 0, 21, 20);
        assertTrue(chaser != null && quarry != null && ally != null,
                "units must place");
        assertTrue(world.orderAttack(chaser, quarry), "attack accepted");

        int east = Direction.fromDelta(1, 0);
        chaser.setTile(20, 20);
        chaser.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {east, east}));
        chaser.setPathGoal(quarry.tileX(), quarry.tileY());
        chaser.setTarget(quarry);
        chaser.setChasing(true);
        chaser.setOffset(0, 0);
        chaser.setLastStepHeading(east);
        chaser.setWalkHolding(false);
        chaser.setStepDrained(true);
        chaser.setBattleNetCollisionCounter(1);
        chaser.setBattleNetOrderDelay(0);
        chaser.animation().switchTo(
                chaser.type().animationSet().get(AnimationSet.State.MOVE));

        // Chase MoveToTarget uses replanOnExhaustion false so multi-step hard
        // refuse (not the ordinary walk replan) is the arm under test.
        world.movement.stepMove(chaser, false);
        assertEquals(20, chaser.tileX(),
                "residual short leftover refuse must keep the chaser on 20,20");
        assertEquals(20, chaser.tileY(),
                "residual short leftover refuse must not free-detour early");
        assertEquals(15, chaser.battleNetOrderDelay(),
                "native pays timer 15 after residual path-two refuse before replan");
        assertEquals(0, chaser.pathLength(),
                "hard refuse clears the short leftover for the delayed replan");
    }

    @Test
    @DisplayName("move residual refills a usable route before acquiring a hostile")
    void moveResidualRefillsAUsableRouteBeforeAcquiringAHostile() {
        // XHuman 2 ogres 1547/1549 exhaust their opening routes with hostiles
        // already in react range. Native 0x44fbd0 finds another route and
        // commits its first step before Still-acquisition can replace Move.
        // XHuman 12 grunt 1358 still becomes Attack at this boundary because
        // its boxed-in home route refills empty, which dispatches Still OP0.
        GameMap map = grass(40);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        Unit walker = world.createUnit(ogre(), 0, 20, 20);
        Unit hostile = world.createUnit(prey(), 1, 22, 20);
        assertTrue(walker != null && hostile != null, "units must place");
        walker.type().setReactRangeComputer(6);
        walker.type().setReactRangePerson(4);
        walker.type().setCanAttack(true);
        walker.type().setCanTargetLand(true);
        walker.type().setBasicDamage(8);
        walker.type().setMaxAttackRange(1);
        walker.setOrder(Unit.Order.MOVE);
        walker.setOrderTarget(30, 20);
        // Path already exhausted; residual of the last E has just settled.
        walker.clearPath();
        walker.setRouteSpent(true);
        walker.setOffset(0, 0);
        walker.setLastStepHeading(Direction.fromDelta(1, 0));
        walker.setWalkHolding(false);
        walker.setStepDrained(true);
        walker.setBattleNetOrderDelay(0);
        walker.animation().switchTo(
                walker.type().animationSet().get(AnimationSet.State.MOVE));
        // Mark that this visit drained residual (walkedThisCycle path).
        world.actionMoveWalked = true;

        int asyncBefore = world.battleNetRandomSeed();
        world.movement.stepMove(walker, true);
        assertEquals(Unit.Order.MOVE, walker.order(),
                "a usable continuation keeps Move ahead of target acquisition");
        assertEquals(21, walker.tileX(),
                "the refilled route commits its first east step immediately");
        assertEquals(19, walker.tileY(),
                "the refilled course detours north-east around the hostile");
        assertEquals(asyncBefore, world.battleNetRandomSeed(),
                "route refill must not spend Still OP0's idle-random draw");
    }

    private static UnitType tower() {
        UnitType type = new UnitType("unit-human-guard-tower");
        type.setTileSize(2, 2);
        type.setBoxSize(63, 63);
        type.setHitPoints(130);
        type.setBuilding(true);
        type.setLandUnit(true);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(4);
        type.setPiercingDamage(12);
        type.setMaxAttackRange(5);
        type.setSightRange(9);
        type.setNumDirections(1);
        AnimationSet set = new AnimationSet("tower");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    @Test
    @DisplayName("a nearly-full diagonal residual blocked on a building free-compasses before free-scan")
    void aNearlyFullDiagonalResidualBlockedOnABuildingFreeCompassesBeforeFreeScan() {
        // retail-xhuman-12-idle grunt 1480 at (23,41): residual NE onto ally
        // at (24,40), E/SE wall, S held by ally, free SW. pathGoal still the
        // tower at (25,42) while free-scan prefers footman (29,43). Free-scan
        // first pathfound NW to (22,40); native free-compasses SW to (22,42)
        // before order_x retarget. pathn>=6 + diagonal residual + building
        // pathGoal: free-compass after the blocked residual heading, skip
        // free-scan, step SW same visit.
        GameMap map = grass(48);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        Unit chaser = world.createUnit(ogre(), 0, 23, 41);
        Unit tower = world.createUnit(tower(), 1, 25, 42);
        Unit footman = world.createUnit(prey(), 1, 29, 43);
        Unit neAlly = world.createUnit(ogre(), 0, 24, 40);
        Unit southAlly = world.createUnit(ogre(), 0, 23, 42);
        assertTrue(chaser != null && tower != null && footman != null
                        && neAlly != null && southAlly != null,
                "units must place");
        assertTrue(world.orderAttack(chaser, tower), "attack tower accepted");
        map.field(24, 41).setFlags(TileFlag.UNPASSABLE | TileFlag.WALL);
        map.field(24, 42).setFlags(TileFlag.UNPASSABLE | TileFlag.WALL);
        neAlly.animation().switchTo(
                neAlly.type().animationSet().get(AnimationSet.State.STILL));
        southAlly.animation().switchTo(
                southAlly.type().animationSet()
                        .get(AnimationSet.State.STILL));
        int ne = Direction.fromDelta(1, -1);
        int sw = Direction.fromDelta(-1, 1);
        int east = Direction.fromDelta(1, 0);
        // Reverse storage: last entry is next heading (NE). pathn 6.
        chaser.setTile(23, 41);
        chaser.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {east, east, east, east, east, ne}));
        chaser.setPathGoal(tower.tileX(), tower.tileY());
        chaser.setTarget(tower);
        chaser.setChasing(true);
        chaser.setAutoTargeting(true);
        chaser.setOffset(0, 0);
        chaser.setStepDrained(true);
        chaser.setBattleNetOrderDelay(0);
        chaser.setBattleNetCollisionCounter(0);
        chaser.animation().switchTo(
                chaser.type().animationSet().get(AnimationSet.State.MOVE));
        world.actionMoveWalked = false;

        world.combat.stepAttack(chaser);

        assertEquals(22, chaser.tileX(),
                "1480 free-compasses SW before free-scan; x="
                        + chaser.tileX());
        assertEquals(42, chaser.tileY(),
                "1480 free-compasses SW before free-scan; y="
                        + chaser.tileY());
        assertEquals(sw, chaser.lastStepHeading(),
                "first free after blocked NE residual is SW; heading="
                        + chaser.lastStepHeading());
        assertSame(tower, chaser.target(),
                "free-scan is deferred; target stays the building this visit");
    }
}
