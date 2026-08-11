package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import net.chonkbase.chonkcraft.engine.missile.MissileClass;
import net.chonkbase.chonkcraft.engine.missile.MissileType;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.pathfinder.PathFinder;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a unit picks to shoot at.
 *
 * <p>Implements {@code ComputeCost}, weights and all.
 * Nearest is not the right answer: a catapult two squares further off than a
 * peasant is the thing that will kill you, and every type carries a Priority
 * saying so. The implementation took whatever was closest and let the siege engine fire.
 */
class TargetChoiceTest {

    private static World world() {
        GameMap map = new GameMap(40, 40, new Tileset());
        for (int y = 0; y < 40; y++) {
            for (int x = 0; x < 40; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        return world;
    }

    private static UnitType fighter(String ident, int priority, int hitPoints) {
        UnitType type = new UnitType(ident);
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(hitPoints);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setMaxAttackRange(4);
        type.setPriority(priority);
        type.setReactRangePerson(12);
        type.setReactRangeComputer(12);
        type.setBasicDamage(6);
        // Without a sight range a unit sees nothing, reacts to nothing, and
        // every assertion below fails for a reason that is not the one under
        // test.
        type.setSightRange(12);
        return type;
    }

    /** Steps the world until the unit has picked something, then reports it. */
    private static Unit chosenBy(World world, Unit attacker) {
        for (int cycle = 0; cycle < 40 && attacker.target() == null; cycle++) {
            world.tick();
        }
        return attacker.target();
    }

    @Test
    @DisplayName("between two it can reach, the dangerous one is taken")
    void priorityDecidesAmongReachableTargets() {
        World world = world();
        Unit footman = world.createUnit(fighter("unit-footman", 60, 60), 0, 20, 20);

        // Both the same distance and both within reach, so the only thing
        // between them is what the data says they are worth shooting: the real
        // numbers are 50 for a peasant and 70 for a catapult.
        world.createUnit(fighter("unit-peasant", 50, 60), 1, 22, 20);
        Unit catapult = world.createUnit(fighter("unit-catapult", 70, 60), 1, 20, 22);

        Unit chosen = chosenBy(world, footman);
        assertNotNull(chosen, "the footman picked nothing at all");
        assertSame(catapult, chosen, "priority was not consulted");
    }

    @Test
    @DisplayName("inside the leash, worth still beats reach")
    void priorityHoldsInsideTheReactionLeash() {
        // This asserted the opposite once, on the other branch's arithmetic:
        // ComputeCost's INRANGE_BONUS outweighs PRIORITY_FACTOR, so a target
        // in reach beat a better one further off. The shipped game runs
        // TargetPriorityCalculate instead -- SimplifiedAutoTargeting is true
        // in scripts/legacyEngine.legacy-declaration:441 -- and there the type's priority sits
        // at bit fifteen against a distance byte at bit seven: anywhere
        // inside the reaction leash, the catapult is worth walking to over
        // the peasant already in reach. Only past the leash does
        // AT_FARAWAY_REDUCE collapse a candidate's worth.
        World world = world();
        Unit footman = world.createUnit(fighter("unit-footman", 60, 60), 0, 20, 20);
        world.createUnit(fighter("unit-peasant", 50, 60), 1, 22, 20);
        Unit far = world.createUnit(fighter("unit-catapult", 70, 60), 1, 28, 20);

        assertSame(far, chosenBy(world, footman),
                "stayed on the peasant in reach; the shipped chooser walks to the"
                        + " catapult its priority names, and demo03's mob judged the"
                        + " hunted peasant by the same bits");
    }

    @Test
    @DisplayName("between equals, the nearer one is taken")
    void distanceStillDecidesBetweenEquals() {
        World world = world();
        Unit footman = world.createUnit(fighter("unit-footman", 60, 60), 0, 20, 20);
        Unit near = world.createUnit(fighter("unit-grunt", 60, 60), 1, 22, 20);
        world.createUnit(fighter("unit-grunt", 60, 60), 1, 27, 20);

        assertSame(near, chosenBy(world, footman),
                "with nothing to choose between them it should take the closer");
    }

    @Test
    @DisplayName("an enemy across water is never chosen")
    void unreachableTargetsAreRejected() {
        // ComputeCost scores an unreachable target INT_MAX. Without that the
        // unit locks on, walks to the shore, fails, drops to still, and picks
        // the same target again next cycle, for ever.
        GameMap map = new GameMap(40, 40, new Tileset());
        for (int y = 0; y < 40; y++) {
            for (int x = 0; x < 40; x++) {
                map.field(x, y).setFlags(x == 20 ? TileFlag.WATER_ALLOWED : TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);

        Unit footman = world.createUnit(fighter("unit-footman", 60, 60), 0, 15, 20);
        UnitType preyType = fighter("unit-peasant", 50, 60);
        preyType.setCanAttack(false);
        Unit prey = world.createUnit(preyType, 1, 25, 20);
        assertTrue(footman.distanceTo(prey) <= 12, "the fixture should be within reaction range");

        PathFinder.nodesExpanded = 0;
        world.tick();
        long firstSearch = PathFinder.nodesExpanded;
        assertTrue(firstSearch > 0, "the far bank was never searched, so the cache proves nothing");

        // Checked every cycle, because the failure this guards against is not
        // a unit that ends up stuck: it is one that acquires, walks to the
        // shore, gives up, and acquires the same target again next scan.
        for (int cycle = 1; cycle < 60; cycle++) {
            world.tick();
            assertNull(footman.target(), "it set off after something on the far bank");
        }
        // Once a scan, not once a cycle. This used to read "exactly once ever",
        // because the answer was cached until the terrain changed -- and that
        // is not upstream's, which asks {@code UnitReachable} afresh inside
        // {@code ComputeCost} every time it scores a candidate. Keeping the
        // answer that long makes "no" permanent, and a unit walled in by its
        // own side never notices them walk away; see
        // {@link ReachabilityGoesStaleTest}. What bounds the cost instead is
        // the cadence {@code COrder_Still::Execute} scans on, half a second,
        // so sixty cycles is four searches and not sixty.
        long searches = PathFinder.nodesExpanded / Math.max(1, firstSearch);
        assertTrue(searches <= 6, "the far bank was searched " + searches + " times over"
                + " sixty cycles. An idle unit looks for a fight twice a second, so four is"
                + " the shape of it; once a cycle is the flooding this guards against");
    }

    @Test
    @DisplayName("a splash weapon looks further than its reaction range")
    void aSplashWeaponSearchesItsMissileRangeAsWell() {
        // AttackUnitsInDistance selects over Missile->Range + range - 1 when
        // the missile splashes and the two together come to less than fifteen
        // because what a splash weapon chooses is a
        // place to land rather than a body to hit. On demo03 that is a
        // catapult at 21,4 and the peasant at 9,2 that every unit on the map
        // wants: twelve apart, reaction range eleven, splash two.
        World world = world();
        MissileType shell = new MissileType("missile-splash", null,
                MissileClass.POINT_TO_POINT, 32, 32, 1, 1, 16, 1, 2, 2, 0,
                null, null, false, 0, 0, false);
        assertEquals(2, shell.range(), "the fixture's missile must splash to reach the branch");
        world.setMissileTypes(Map.of(shell.ident(), shell));

        UnitType gunner = fighter("unit-catapult", 60, 60);
        gunner.setMissile(shell.ident());
        gunner.setReactRangePerson(6);
        gunner.setReactRangeComputer(6);
        Unit catapult = world.createUnit(gunner, 0, 10, 10);

        // Seven away: outside the reaction range of six, inside the six plus
        // two minus one that a splashing missile searches.
        UnitType preyType = fighter("unit-peasant", 50, 60);
        preyType.setCanAttack(false);
        Unit prey = world.createUnit(preyType, 1, 17, 10);
        assertEquals(7, catapult.distanceTo(prey), "the fixture must sit in the gap");

        for (int cycle = 0; cycle < 40 && catapult.target() == null; cycle++) {
            world.tick();
        }
        assertSame(prey, catapult.target(),
                "the catapult never saw a target seven squares off, so it searched its"
                        + " reaction range alone and not the missile's reach as well");
    }

    @Test
    @DisplayName("candidates are gathered in a box, not a circle")
    void theSearchIsASquareEvenThoughTheDistanceIsNot() {
        // SelectAroundUnit takes everything whose tiles fall between
        // tilePos - range and tilePos + typeSize + range (unit/unit_find.h),
        // and only the finders that score what it collected measure a real
        // distance. MapDistanceBetweenTypes is Euclidean, so filtering on it
        // makes the search a circle and loses the corners: on demo03 an
        // axethrower at 16,6 is seven squares from the peasant at 9,2 across
        // the box and eight by the hypotenuse, and with a reaction range of
        // seven upstream sees it and this implementation did not.
        World world = world();
        UnitType hunterType = fighter("unit-axethrower", 60, 60);
        hunterType.setReactRangePerson(7);
        hunterType.setReactRangeComputer(7);
        Unit hunter = world.createUnit(hunterType, 0, 10, 10);

        // Seven across and four down: inside the box of seven, and eight away
        // by the distance the engine measures everywhere else.
        UnitType preyType = fighter("unit-peasant", 50, 60);
        preyType.setCanAttack(false);
        Unit prey = world.createUnit(preyType, 1, 17, 14);
        assertEquals(8, hunter.distanceTo(prey),
                "the fixture must sit outside the circle, or it proves nothing");

        for (int cycle = 0; cycle < 40 && hunter.target() == null; cycle++) {
            world.tick();
        }
        assertSame(prey, hunter.target(),
                "the corner of the box was never searched, so the reaction range was read"
                        + " as a radius");
    }

    @Test
    @DisplayName("an idle unit looks for a fight every fifteen cycles, not every one")
    void idleScanningHasACadence() {
        World world = world();
        Unit footman = world.createUnit(fighter("unit-footman", 60, 60), 0, 20, 20);
        // The first cycle scans an empty map and then sleeps for half a second.
        world.tick();

        UnitType preyType = fighter("unit-peasant", 50, 60);
        preyType.setCanAttack(false);
        Unit prey = world.createUnit(preyType, 1, 22, 20);

        for (int cycle = 0; cycle < 5; cycle++) {
            world.tick();
        }
        assertNull(footman.target(), "it noticed the instant the enemy appeared");

        for (int cycle = 0; cycle < 20; cycle++) {
            world.tick();
        }
        assertSame(prey, footman.target(), "it went to sleep and never woke up");
    }

    @Test
    @DisplayName("a unit already fighting still notices something worse arriving")
    void anAttackingUnitReconsidersItsTarget() {
        // The implementation picked a target once and never looked again. Upstream's
        // COrder_Attack re-runs the choice every six cycles, which is what
        // makes an attack-move work at all.
        World world = world();
        Unit footman = world.createUnit(fighter("unit-footman", 60, 60), 0, 20, 20);
        UnitType preyType = fighter("unit-peasant", 50, 600);
        preyType.setCanAttack(false);
        Unit prey = world.createUnit(preyType, 1, 22, 20);

        assertSame(prey, chosenBy(world, footman), "it never started on anything");

        Unit catapult = world.createUnit(fighter("unit-catapult", 200, 60), 1, 20, 22);
        // These types carry no attack animation, so no swing ever starves
        // the reconsideration and the counter ticks every beat -- the scan
        // comes on the sixth. A real fighter's calls ride its swing tails
        // and take whole swings between scans, which the campaign maps
        // measure; what this fixture asserts is only that the choice is
        // re-run at all.
        for (int cycle = 0; cycle < 20; cycle++) {
            world.tick();
        }
        assertSame(catapult, footman.target(),
                "it kept hitting a peasant while a siege engine set up beside it");
    }

    @Test
    @DisplayName("a wounded target is worth finishing")
    void woundedTargetsAreFinished() {
        World world = world();
        Unit footman = world.createUnit(fighter("unit-footman", 60, 60), 0, 20, 20);
        Unit hurt = world.createUnit(fighter("unit-grunt", 60, 60), 1, 22, 20);
        Unit whole = world.createUnit(fighter("unit-grunt", 60, 60), 1, 20, 22);
        hurt.setHitPoints(6);

        assertSame(hurt, chosenBy(world, footman),
                "left the nearly-dead one and started on a fresh one");
        assertTrue(whole.isAlive());
    }
}
