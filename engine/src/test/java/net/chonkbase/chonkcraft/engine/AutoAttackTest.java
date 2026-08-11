package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Units and towers picking their own fights.
 *
 * <p>This is what makes a battle a battle rather than a sequence of orders.
 * Without it two armies walk past each other and a tower watches an enemy
 * stroll by, which is exactly what this implementation did before.
 *
 * <p>The probes run on a corridor of open land found on a real map, because a
 * unit that cannot path to its target looks identical to a unit that never
 * noticed one.
 */
class AutoAttackTest {

    private record Fixture(GameData data, Map<String, UnitType> types, int x, int y) {}

    private static Fixture load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");

        GameData data = new GameData(install);
        Assumptions.assumeTrue(data.campaignMap(MAP) != null, "no campaign map available");

        World probe = world(data);
        int[] open = findOpenCorridor(probe);
        Assumptions.assumeTrue(open != null, "no open ground on this map");
        return new Fixture(data, data.unitTypes().types(), open[0], open[1]);
    }

    private static final String MAP = "campaigns/human/level02h";

    private static World world(GameData data) {
        PudMap pud = data.campaignMap(MAP);
        World world = new World(GameMap.from(pud, data.loadTileset(pud.tileset()).tileset()),
                Player.from(pud));
        world.setUpgrades(data.upgrades().upgrades());
        return world;
    }

    /** A run of passable land, so terrain never explains a failure. */
    private static int[] findOpenCorridor(World world) {
        for (int y = 2; y < world.map().height() - 2; y++) {
            for (int x = 2; x < world.map().width() - 14; x++) {
                boolean clear = true;
                for (int i = 0; i < 14; i++) {
                    if (!world.map().field(x + i, y).isLandPassable()) {
                        clear = false;
                        break;
                    }
                }
                if (clear) {
                    return new int[] {x, y};
                }
            }
        }
        return null;
    }

    private static void run(World world, int cycles) {
        for (int i = 0; i < cycles; i++) {
            world.tick();
        }
    }

    @Test
    @DisplayName("the reaction ranges come from the scripts, longer for the computer")
    void reactionRangesAreRead() {
        Fixture fixture = load();
        UnitType footman = fixture.types().get("unit-footman");
        assertNotNull(footman);
        // The scripts give a footman four tiles under a person and six under
        // the computer. Warcraft II gives the computer the longer leash.
        assertEquals(4, footman.reactRangePerson());
        assertEquals(6, footman.reactRangeComputer());

        // A peasant fights back. It has three damage and a reaction range of
        // four in the shipped data, which is why a lone worker is not free to
        // kill and why this is asserted rather than assumed away.
        UnitType peasant = fixture.types().get("unit-peasant");
        assertEquals(4, peasant.reactRangePerson());
        assertTrue(peasant.canAttack());
    }

    @Test
    @DisplayName("two adjacent enemies fight without being told to")
    void adjacentEnemiesFight() {
        Fixture fixture = load();
        World world = world(fixture.data());
        Unit footman = world.createUnit(fixture.types().get("unit-footman"), 0,
                fixture.x(), fixture.y());
        Unit grunt = world.createUnit(fixture.types().get("unit-grunt"), 1,
                fixture.x() + 1, fixture.y());
        world.fog().revealAll(0);
        world.fog().revealAll(1);

        run(world, 300);
        assertTrue(footman.hitPoints() < footman.type().hitPoints()
                        || grunt.hitPoints() < grunt.type().hitPoints(),
                "neither unit reacted to an enemy standing next to it");
    }

    @Test
    @DisplayName("a guard tower fires on an enemy walking past")
    void aTowerDefendsItself() {
        Fixture fixture = load();
        World world = world(fixture.data());
        // The guard tower, not the watch tower: the watch tower has no damage
        // at all in Warcraft II and is a thing you upgrade, not a weapon.
        UnitType tower = fixture.types().get("unit-human-guard-tower");
        Assumptions.assumeTrue(tower != null, "no guard tower in the roster");
        assertTrue(tower.canAttack(), "the guard tower should be armed");

        world.createUnit(tower, 0, fixture.x(), fixture.y());
        Unit grunt = world.createUnit(fixture.types().get("unit-grunt"), 1,
                fixture.x() + 3, fixture.y());
        world.fog().revealAll(0);
        int before = grunt.hitPoints();

        run(world, 300);
        assertTrue(grunt.hitPoints() < before, "the tower ignored an enemy within its range");
    }

    @Test
    @DisplayName("the tower that fires is under still the whole time")
    void aTowerFiresWithoutTakingAnOrder() {
        Fixture fixture = load();
        World world = world(fixture.data());
        UnitType tower = fixture.types().get("unit-human-guard-tower");
        Assumptions.assumeTrue(tower != null && tower.canAttack(), "no armed guard tower");
        Unit guard = world.createUnit(tower, 0, fixture.x(), fixture.y());
        Unit grunt = world.createUnit(fixture.types().get("unit-grunt"), 1,
                fixture.x() + 3, fixture.y());
        world.fog().revealAll(0);
        int before = grunt.hitPoints();

        for (int i = 0; i < 300; i++) {
            world.tick();
            assertEquals(Unit.Order.STILL, guard.order(),
                    "the tower took a real order on cycle " + i
                            + ". Upstream's tower acquires in place: AutoAttackStand is"
                            + " a sub-state of COrder_Still and the trace prints still"
                            + " for the whole fight, where this port's tower used to"
                            + " answer through autoAttack with a whole attack order --"
                            + " campaigns/orc-exp/levelx04o's first finding, at cycle"
                            + " 2, for the zeppelin four tiles from a guard tower");
        }
        assertTrue(grunt.hitPoints() < before,
                "and it must actually fire from that sub-state, not merely refuse the"
                        + " order");
    }

    @Test
    @DisplayName("a standing tower asks its arrows, not its eyes")
    void standingAcquisitionUsesAttackRange() {
        Fixture fixture = load();
        World world = world(fixture.data());
        UnitType tower = fixture.types().get("unit-human-guard-tower");
        Assumptions.assumeTrue(tower != null && tower.canAttack(), "no armed guard tower");
        int arrows = tower.maxAttackRange();
        int eyes = Math.max(tower.reactRangePerson(), tower.reactRangeComputer());
        Assumptions.assumeTrue(eyes > arrows + 1,
                "the data no longer sees further than it shoots; nothing to tell apart");
        world.createUnit(tower, 0, fixture.x(), fixture.y());
        // Beyond the arrows, within the eyes -- and beyond the grunt's own
        // reaction range, so it does not start the fight itself.
        Unit grunt = world.createUnit(fixture.types().get("unit-grunt"), 1,
                fixture.x() + arrows + 2, fixture.y());
        world.fog().revealAll(0);
        int before = grunt.hitPoints();

        run(world, 300);
        assertEquals(before, grunt.hitPoints(),
                "the tower hit something its arrows do not reach. AutoAttackStand"
                        + " searches AttackUnitsInRange -- the attack range -- where"
                        + " the mobile scan searches the reaction range; a standing"
                        + " tower noticing at its reaction range opens fire an"
                        + " engine's-width earlier than the real game");
    }

    @Test
    @DisplayName("a unit told to hold its ground strikes but never steps")
    void standGroundHoldsPosition() {
        Fixture fixture = load();
        World world = world(fixture.data());
        Unit holder = world.createUnit(fixture.types().get("unit-footman"), 0,
                fixture.x(), fixture.y());
        Unit bait = world.createUnit(fixture.types().get("unit-peasant"), 1,
                fixture.x() + 3, fixture.y());
        world.fog().revealAll(0);

        world.orderStandGround(holder);
        run(world, 300);

        assertEquals(fixture.x(), holder.tileX(), "it chased instead of holding");
        assertEquals(fixture.y(), holder.tileY(), "it chased instead of holding");
        // It holds, but it is not passive: what comes within reach is hit.
        Unit adjacent = world.createUnit(fixture.types().get("unit-peasant"), 1,
                fixture.x() + 1, fixture.y());
        int before = adjacent.hitPoints();
        run(world, 200);
        assertTrue(adjacent.hitPoints() < before, "it held its ground and did nothing else");
        assertEquals(fixture.x(), holder.tileX(), "it stepped after all");
        assertTrue(bait != null);
    }

    @Test
    @DisplayName("the one already shooting at you outranks the one merely closer")
    void beingHuntedOutranksCloseness() {
        Fixture fixture = load();
        World world = world(fixture.data());
        UnitType axethrower = fixture.types().get("unit-axethrower");
        Assumptions.assumeTrue(axethrower != null && axethrower.maxAttackRange() >= 4,
                "no ranged hunter to stage the ambush with");
        Unit footman = world.createUnit(fixture.types().get("unit-footman"), 0,
                fixture.x(), fixture.y());
        // The closer enemy: a peasant leaning on the footman, unarmed intent.
        Unit peasant = world.createUnit(fixture.types().get("unit-peasant"), 1,
                fixture.x() + 1, fixture.y());
        // The hunter: an axethrower at reaction range's edge, already told to
        // kill the footman, and near enough that its axes reach.
        Unit hunter = world.createUnit(axethrower, 1, fixture.x() + 4, fixture.y());
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.orderAttack(hunter, footman);

        for (int i = 0; i < 6 && footman.target() == null; i++) {
            world.tick();
        }

        assertEquals(hunter, footman.target(),
                "the footman answered the peasant beside it instead of the axethrower"
                        + " already throwing at it. TargetPriorityCalculate's top bit is"
                        + " AT_ATTACKED_BY_FACTOR -- a candidate whose order names you"
                        + " and whose weapon reaches you outranks priority, distance and"
                        + " health together (unit/unit.cpp:2539-2543) -- and the shipped"
                        + " SimplifiedAutoTargeting setting makes that the game's one"
                        + " target chooser");
    }

    @Test
    @DisplayName("the computer notices what its fog has never lit")
    void theComputerAcquiresThroughFog() {
        Fixture fixture = load();
        World world = world(fixture.data());
        UnitType grunt = fixture.types().get("unit-grunt");
        Assumptions.assumeTrue(grunt != null
                        && grunt.reactRangeComputer() > grunt.sightRange(),
                "the computer's leash no longer outruns its lamp; nothing to tell apart");
        // Player 1 is a computer slot on this map. Its grunt reacts at six and
        // sees at four; the footman stands at five -- inside the leash,
        // beyond the lamp, and nothing else lights it.
        Unit watcher = world.createUnit(grunt, 1, fixture.x(), fixture.y());
        Unit quarry = world.createUnit(fixture.types().get("unit-footman"), 0,
                fixture.x() + grunt.sightRange() + 1, fixture.y());
        assertTrue(quarry != null && watcher != null, "the stage could not be set");

        for (int i = 0; i < 40 && watcher.target() == null; i++) {
            world.tick();
        }

        assertEquals(quarry, watcher.target(),
                "the computer's grunt never noticed a footman its reaction range"
                        + " covers. IsVisibleAsGoal answers yes for a computer player"
                        + " before the fog is consulted (include/unit.h:239) -- the"
                        + " cheat is written into the visibility test itself, and"
                        + " level13h's player 4 destroyer opens fire from the second"
                        + " cycle on a ship fifteen dark squares away because of it");
    }

    @Test
    @DisplayName("no unit reacts further than it can see")
    void reactionNeverExceedsSight() {
        Fixture fixture = load();
        // The acquisition code refuses targets hidden by the fog, and the
        // shipped data never asks it to: no armed type has a reaction range
        // longer than its sight. Asserting the invariant is honest about why
        // there is no scenario that exercises the guard -- and it fails
        // loudly if a later edit gives some unit second sight.
        int armed = 0;
        for (UnitType type : fixture.types().values()) {
            if (!type.canAttack() || type.reactRangePerson() == 0) {
                continue;
            }
            armed++;
            assertTrue(type.reactRangePerson() <= type.sightRange(),
                    type.ident() + " reacts at " + type.reactRangePerson()
                            + " but sees only " + type.sightRange());
        }
        assertTrue(armed > 30, "only " + armed + " types react at all");
    }
}
