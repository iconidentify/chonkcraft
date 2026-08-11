package net.chonkbase.chonkcraft.engine.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
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
 * A computer player shoves its own units out of each other's way.
 *
 * <p>{@code DoActionMove} calls {@code AiCanNotMove} whenever the walk answers
 * PF_UNREACHABLE and the unit belongs to a player with an AI
 * That asks {@code PlaceReachable}
 * whether the destination is reachable at all -- at a range of 255, so the
 * answer is yes unless the ground itself is cut off -- and takes the yes to
 * mean "Path probably closed by unit here".
 *
 * <p>{@code AiMoveUnitInTheWay} then looks at every unit the manager holds,
 * keeps the ones that are allied, of the same movement kind, standing still
 * and close enough to be in the way, and <em>draws a number for each of
 * them</em> to choose which of eight directions to start looking for a free
 * square in. Then it draws once more to choose which of the ones it found to
 * move, and moves exactly that one, with a flushing {@code CommandMove}
 *
 *
 * <p>Both draws come off the same stream the damage rolls do, which is why
 * this cannot be left out of a port that wants the same simulation. On
 * {@code maps/demo/demo03} a grunt at 11,1 marching on 13,3 finds a friendly
 * axethrower standing on it: upstream spends two numbers shoving somebody
 * aside on cycle 61, this implementation spent none, and every roll either engine made
 * afterwards was a different number.
 */
class AiShovesBlockersTest {

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    /**
     * A walker that does nothing on its own account.
     *
     * <p>No attack, so no target scan; no {@code RandomMovementProbability},
     * so no wandering. Both spend numbers from the stream this test counts.
     */
    private static UnitType walker() {
        UnitType type = new UnitType("unit-peasant");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setNumDirections(8);
        AnimationSet set = new AnimationSet("walker");
        set.put(AnimationSet.State.STILL, Animation.parse("still",
                List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 16", "wait 1",
                "frame 5", "move 16", "unbreakable end", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    /** One for the direction to start looking in, one for which unit to move. */
    private static final int DRAWS_PER_SHOVE = 2;

    @Test
    @DisplayName("a stuck computer unit does not shove a neutral critter")
    void aNeutralUnitIsNotAnAlliedShoveCandidate() {
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            PudMap.PlayerType kind = switch (i) {
                case 0 -> PudMap.PlayerType.COMPUTER;
                case World.NEUTRAL_PLAYER -> PudMap.PlayerType.NEUTRAL;
                default -> PudMap.PlayerType.NOBODY;
            };
            players[i] = new Player(i, kind, PudMap.Race.HUMAN);
        }
        World world = new World(grass(24), players);
        world.establishDiplomacy();
        world.enableAi(0);
        for (int cycle = 0; cycle < 10; cycle++) {
            world.tick();
        }
        for (int y = 9; y <= 13; y++) {
            world.map().field(9, y).setFlags(TileFlag.UNPASSABLE);
            world.map().field(11, y).setFlags(TileFlag.UNPASSABLE);
        }
        Unit stuck = world.createUnit(walker(), 0, 10, 10);
        Unit critter = world.createUnit(walker(), World.NEUTRAL_PLAYER, 10, 11);

        assertTrue(world.orderMove(stuck, 10, 13));
        long before = world.randomDraws();
        world.tick();

        assertFalse(world.isAllied(World.NEUTRAL_PLAYER, 0));
        assertEquals(before, world.randomDraws(),
                "AiMoveUnitInTheWay spent numbers considering a neutral unit");
        assertEquals(Unit.Order.STILL, critter.order(),
                "the stuck computer shoved a neutral unit as though it were an ally");
        assertFalse(critter.hasQueuedOrders(),
                "the neutral unit was given a queued shove move");
    }

    @Test
    @DisplayName("an unreachable attack chase asks the AI to clear its way")
    void aBlockedAttackChaseMovesTheUnitInTheWay() {
        World world = new World(grass(24));
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.enableAi(0);

        UnitType fighter = walker();
        fighter.setCanAttack(true);
        fighter.setCanTargetLand(true);
        fighter.setBasicDamage(1);
        fighter.setMaxAttackRange(1);

        // Seal the attacker into a one-tile northbound corridor. The target's
        // range ring is ordinary reachable ground, but the allied blocker in
        // the corridor makes the actual chase answer PF_UNREACHABLE.
        for (int y = 9; y <= 11; y++) {
            for (int x = 9; x <= 11; x++) {
                if ((x != 10 || y != 10) && (x != 10 || y != 9)) {
                    world.map().field(x, y).setFlags(TileFlag.UNPASSABLE);
                }
            }
        }
        Unit target = world.createUnit(walker(), 1, 10, 7);
        Unit blocker = world.createUnit(walker(), 0, 10, 9);
        Unit attacker = world.createUnit(fighter, 0, 10, 10);
        world.orderStandGround(target);
        world.orderStandGround(blocker);

        int shovedAt = -1;
        long spent = 0;
        for (int cycle = 1; cycle <= 20 && shovedAt < 0; cycle++) {
            assertTrue(world.orderAttack(attacker, target));
            long start = world.randomDraws();
            world.tick();
            if (blocker.hasQueuedOrders()) {
                shovedAt = cycle;
                spent = world.randomDraws() - start;
            }
        }

        assertEquals(11, shovedAt,
                "DoActionMove's PF_UNREACHABLE chase did not call AiCanNotMove"
                        + " on the first cycle its shove gate allowed");
        assertEquals(DRAWS_PER_SHOVE, spent);
    }

    @Test
    @DisplayName("an unreachable chase restores the clone made by its own shove")
    void anUnreachableChaseRestoresItsShoveCloneBeforeGivingUp() {
        World world = new World(grass(24));
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.enableAi(0);
        for (int cycle = 0; cycle < 10; cycle++) {
            world.tick();
        }

        UnitType fighter = walker();
        fighter.setCanAttack(true);
        fighter.setCanTargetLand(true);
        fighter.setBasicDamage(1);
        fighter.setMaxAttackRange(1);
        for (int y = 9; y <= 11; y++) {
            for (int x = 9; x <= 11; x++) {
                if ((x != 10 || y != 10) && (x != 10 || y != 9)) {
                    world.map().field(x, y).setFlags(TileFlag.UNPASSABLE);
                }
            }
        }
        Unit target = world.createUnit(walker(), 1, 10, 7);
        Unit blocker = world.createUnit(walker(), 0, 10, 9);
        Unit attacker = world.createUnit(fighter, 0, 10, 10);
        world.orderStandGround(target);
        world.orderStandGround(blocker);
        assertTrue(world.orderAttackMove(attacker, 4, 10));
        attacker.setTarget(target);
        attacker.setChasing(true);
        attacker.setAutoTargeting(true);
        attacker.setPathGoal(target.tileX(), target.tileY());

        // AiCanNotMove clones the stuck unit's current COrder_Attack when the
        // blocker it chose is busy. MoveToTarget then clears the unreachable
        // goal and EndActionAttack immediately restores that clone.
        world.tick();
        assertSame(target, attacker.target(),
                "the first unreachable answer did not restore the cloned chase");
        assertNull(attacker.savedOrder(),
                "RestoreOrder left the shove's clone banked behind the chase");

        // The restored clone gets the same unreachable answer next cycle.
        // The shove is rate-limited now, so there is no second clone and
        // EndActionAttack resumes this weak order's saved destination.
        world.tick();
        assertNull(attacker.target(),
                "the restored chase kept an unreachable goal with no clone left");
        assertEquals(Unit.Order.ATTACK_MOVE, attacker.order());
        assertEquals(4, attacker.attackMoveX());
        assertEquals(10, attacker.attackMoveY());
    }

    @Test
    @DisplayName("nobody is shoved in the game's first ten cycles")
    void theOpeningCyclesNeverShove() {
        World world = new World(grass(24));
        world.fog().revealAll(0);
        world.enableAi(0);
        world.createUnit(walker(), 0, 10, 13);
        Unit blocker = world.createUnit(walker(), 0, 10, 11);
        Unit stuck = world.createUnit(walker(), 0, 10, 10);
        assertTrue(world.orderMove(stuck, 10, 13), "the order was refused");
        world.orderStandGround(blocker);

        // LastCanNotMoveGameCycle is nought at birth and the gate is
        // "GameCycle <= LastCanNotMoveGameCycle + 10",
        // so the first shove any player can make lands on cycle eleven. On
        // campaigns/orc/level11o the opening force jams in its own column
        // at cycle 9 and upstream stands and widens; a port whose counter
        // started at a distant negative threw an archer out of line with
        // two draws upstream never made.
        for (int cycle = 1; cycle <= 10; cycle++) {
            // Ordered afresh each cycle, so the ask stays the same
            // unreachable question rather than widening its way to an
            // answer.
            world.orderMove(stuck, 10, 13);
            world.tick();
            assertTrue(!blocker.hasQueuedOrders(),
                    "a blocker was shoved on cycle " + cycle + "; the counter's nought"
                            + " suppresses every shove through cycle ten");
        }
        world.orderMove(stuck, 10, 13);
        world.tick();
        assertTrue(blocker.hasQueuedOrders(),
                "and the first eligible cycle, the eleventh, should shove at last");
    }

    @Test
    @DisplayName("a stuck computer unit shoves an ally aside, at two numbers")
    void aBlockedAiUnitMovesTheUnitInTheWay() {
        World world = new World(grass(24));
        world.fog().revealAll(0);
        world.enableAi(0);

        // Where the stuck unit is sent: a square one of its own side is
        // standing on, so the search can only answer unreachable.
        Unit onTheSpot = world.createUnit(walker(), 0, 10, 13);
        Unit blocker = world.createUnit(walker(), 0, 10, 11);
        Unit stuck = world.createUnit(walker(), 0, 10, 10);
        assertTrue(world.orderMove(stuck, 10, 13), "the order was refused");
        // The blocker is busy rather than idle: sent at the same occupied
        // square, so it stands there under a move order it cannot serve.
        // {@code AiMoveUnitInTheWay} asks
        // {@code movableunits[index]->IsIdle() == false} before it stores
        // anything, so an idle blocker measures only half of this.
        world.orderStandGround(blocker);
        assertEquals(Unit.Order.STAND_GROUND, blocker.order(),
                "the blocker should be holding its ground, which is busy and not walking");
        assertTrue(!blocker.hasQueuedOrders(), "the blocker starts with nothing queued");

        long before = world.randomDraws();
        int shovedAt = -1;
        long spent = 0;
        for (int cycle = 1; cycle <= 40 && shovedAt < 0; cycle++) {
            // Re-ordered every cycle: a single move order gives up or widens
            // its way to an answer long before the shove gate first opens at
            // cycle eleven.
            world.orderMove(stuck, 10, 13);
            long start = world.randomDraws();
            world.tick();
            // A shove is a flushing CommandMove, which this implementation models as
            // "finish what you were doing and take this next" -- so a queued
            // order is the mark of it, whatever the blocker was doing before.
            if (blocker.hasQueuedOrders()) {
                shovedAt = cycle;
                spent = world.randomDraws() - start;
            }
        }

        assertTrue(shovedAt > 0,
                "nothing was shoved in forty cycles. A computer player whose unit cannot move"
                        + " asks AiMoveUnitInTheWay to clear the way for it, and this port had"
                        + " no counterpart at all");
        assertEquals(DRAWS_PER_SHOVE, spent,
                "the shove spent " + spent + " numbers where upstream spends two -- one to"
                        + " choose which of the eight directions to start looking for a free"
                        + " square in, and one to choose which of the units it found to move");
        assertTrue(world.randomDraws() > before, "nothing was drawn at all");

        // And the one it moved is the one that was in the way, not the one
        // standing on the destination: upstream only considers units within
        // the stuck unit's own width.
        assertEquals(Unit.Order.STILL, onTheSpot.order(),
                "the unit standing on the destination was shoved. It is two squares away, and"
                        + " AiMoveUnitInTheWay only looks at what is within TileWidth of the"
                        + " unit that is stuck");

        // And the stuck unit is left holding its own order. It reads like a
        // slip and it is the behaviour: the clone is of {@code unit}'s current
        // order rather than the blocker's, and it goes to
        // {@code unit.SavedOrder}. That is what a
        // march comes back to when it ends -- on {@code maps/demo/demo03} a
        // grunt that shoved somebody aside on cycle 61 is marching at the same
        // square again on 84 because of it.
        assertEquals(Unit.Order.MOVE, stuck.savedOrder(),
                "the stuck unit was left with " + stuck.savedOrder() + " stored behind it,"
                        + " having just had to shove a busy blocker out of its way");
    }

    @Test
    @DisplayName("a blocker with an attack queued is not idle to the shove")
    void aBlockerWithAnAttackQueuedMakesTheStuckUnitSaveItsOrder() {
        World world = new World(grass(24));
        world.fog().revealAll(0);
        world.enableAi(0);
        for (int cycle = 0; cycle < 10; cycle++) {
            world.tick();
        }
        for (int y = 9; y <= 13; y++) {
            world.map().field(9, y).setFlags(TileFlag.UNPASSABLE);
            world.map().field(11, y).setFlags(TileFlag.UNPASSABLE);
        }

        Unit onTheSpot = world.createUnit(walker(), 0, 10, 13);
        Unit stuck = world.createUnit(walker(), 0, 10, 10);
        Unit blocker = world.createUnit(walker(), 0, 10, 11);
        assertTrue(world.orderMove(stuck, 10, 13));
        blocker.setPendingAttack(onTheSpot, Unit.Order.STILL,
                onTheSpot.tileX(), onTheSpot.tileY());

        world.tick();

        assertEquals(Unit.Order.MOVE, stuck.savedOrder(),
                "CUnit::IsIdle asks for one order as well as CurrentAction"
                        + " Still; the blocker's queued attack makes it busy"
                        + " before that attack becomes current");
    }

    @Test
    @DisplayName("a shove cannot replace an order the stuck unit already saved")
    void aShoveKeepsAnExistingSavedOrder() {
        World world = new World(grass(24));
        world.fog().revealAll(0);
        world.enableAi(0);

        world.createUnit(walker(), 0, 10, 13);
        Unit blocker = world.createUnit(walker(), 0, 10, 11);
        Unit stuck = world.createUnit(walker(), 0, 10, 10);
        world.orderStandGround(blocker);
        stuck.setSavedOrder(Unit.Order.ATTACK_MOVE);
        stuck.setSavedAttackMove(7, 8);

        for (int cycle = 1; cycle <= 40 && !blocker.hasQueuedOrders(); cycle++) {
            assertTrue(world.orderMove(stuck, 10, 13), "the blocked move was refused");
            world.tick();
        }

        assertTrue(blocker.hasQueuedOrders(), "the fixture never reached the shove");
        assertEquals(Unit.Order.ATTACK_MOVE, stuck.savedOrder(),
                "AiMoveUnitInTheWay replaced a non-empty SavedOrder");
        assertEquals(7, stuck.savedAttackMoveX());
        assertEquals(8, stuck.savedAttackMoveY());
    }

    @Test
    @DisplayName("a shove waits for the blocker's unbreakable attack to finish")
    void aShovedUnitFinishesItsCommittedSwingBeforeTheMovePops() {
        World world = new World(grass(24));
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.enableAi(0);

        UnitType fighterType = walker();
        fighterType.setCanAttack(true);
        fighterType.setCanTargetLand(true);
        fighterType.setBasicDamage(1);
        fighterType.setMaxAttackRange(4);
        fighterType.animationSet().put(AnimationSet.State.ATTACK,
                Animation.parse("attack", List.of(
                        "unbreakable begin", "frame 0", "wait 30",
                        "attack", "unbreakable end", "wait 1")));

        world.createUnit(walker(), 0, 10, 13);
        Unit blocker = world.createUnit(fighterType, 0, 10, 11);
        Unit stuck = world.createUnit(walker(), 0, 10, 10);
        Unit enemy = world.createUnit(walker(), 1, 12, 11);
        assertTrue(world.orderAttack(blocker, enemy), "the attack was refused");

        for (int cycle = 1; cycle <= 11; cycle++) {
            assertTrue(world.orderMove(stuck, 10, 13), "the blocked move was refused");
            world.tick();
        }

        assertTrue(blocker.hasQueuedOrders(), "the eligible blocker was not shoved");
        assertTrue(blocker.animation().unbreakable(),
                "the fixture's committed broadside ended before the shove");
        assertEquals(Unit.Order.ATTACK, blocker.order(),
                "the flushed move replaced an order whose animation was still unbreakable");
    }
}
