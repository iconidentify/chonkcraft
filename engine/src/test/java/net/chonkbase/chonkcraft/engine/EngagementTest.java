package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Units ordered to attack actually reach the enemy and hit it.
 *
 * <p>They did not. A chase asked the pathfinder for a route to the square the
 * target was standing on, and that square is occupied by the target. The
 * planner is allowed to end a route on an occupied square -- a move order
 * aimed at somebody's head should still set off -- and the walk then stops
 * short of it. So the attacker halted one square out, the attack order looked
 * at the distance, decided it was still out of reach, threw the route away and
 * asked for the identical one again. Every cycle, forever, with the walk
 * animation running the whole time: units jogging on the spot next to an enemy
 * they never swing at.
 *
 * <p>It was worst with more than one attacker, because the squares a second
 * one could stop on were taken by the first, and worst of all for anything
 * with reach, whose range meant it stopped further out and had further to
 * re-plan across.
 *
 * <p>Upstream never asks for that route.
 * {@code COrder_Attack::UpdatePathFinderData} hands the pathfinder the
 * target's <em>footprint</em> and the attack range together --
 * {@code SetGoal(goal->tilePos, tileSize)} with {@code SetMaxRange(Range)} and
 * {@code SetMinRange(MinRange)} -- so the route ends anywhere the unit could
 * strike from, and arriving means being in reach.
 */
class EngagementTest {

    private static GameData load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    private static World plain(GameData data, int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i,
                    i < 2 ? PudMap.PlayerType.PERSON : PudMap.PlayerType.NOBODY,
                    PudMap.Race.HUMAN);
        }
        World world = new World(map, players);
        world.setUnitTypes(data.unitTypes().types());
        world.setUpgrades(data.upgrades().upgrades());
        world.setMissileTypes(data.missiles().types());
        world.establishDiplomacy();
        return world;
    }

    /** Runs the world until the victim is hurt, or gives up. */
    private static boolean landsABlow(World world, Unit victim, int seconds) {
        int before = victim.hitPoints();
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * seconds; cycle++) {
            world.tick();
            if (victim.hitPoints() < before || !victim.isAlive()) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("A single attacker walks up to its target and hits it")
    void oneAttackerEngages() {
        GameData data = load();
        World world = plain(data, 48);
        UnitType footman = data.unitTypes().types().get("unit-footman");
        assertNotNull(footman);

        Unit attacker = world.createUnit(footman, 0, 10, 20);
        Unit victim = world.createUnit(footman, 1, 30, 20);
        assertNotNull(attacker);
        assertNotNull(victim);
        world.orderAttack(attacker, victim);

        assertTrue(landsABlow(world, victim, 60),
                "the attacker never landed a blow; it ended at " + attacker.tileX() + ","
                        + attacker.tileY() + ", " + attacker.distanceTo(victim)
                        + " squares from a target it was ordered to attack");
    }

    /**
     * The reported case: two attackers on one target. The second one's
     * stopping squares are taken by the first, which is exactly when a route
     * aimed at the target's own square has nowhere to end.
     */
    @Test
    @DisplayName("A second attacker on the same target also engages")
    void twoAttackersBothEngage() {
        GameData data = load();
        World world = plain(data, 48);
        UnitType footman = data.unitTypes().types().get("unit-footman");
        UnitType hall = data.unitTypes().types().get("unit-town-hall");
        assertNotNull(hall);

        // A big, immobile target so neither attacker can be blamed for chasing
        // something that moved, and so both have to find their own square.
        Unit victim = world.createUnit(hall, 1, 30, 20);
        Unit first = world.createUnit(footman, 0, 10, 20);
        Unit second = world.createUnit(footman, 0, 10, 21);
        assertNotNull(victim);
        assertNotNull(first);
        assertNotNull(second);
        world.orderAttack(first, victim);
        world.orderAttack(second, victim);

        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 90; cycle++) {
            world.tick();
        }
        assertTrue(first.distanceTo(victim) <= Math.max(1, footman.maxAttackRange()),
                "the first attacker stopped " + first.distanceTo(victim) + " squares out");
        assertTrue(second.distanceTo(victim) <= Math.max(1, footman.maxAttackRange()),
                "the second attacker never got into reach: it is "
                        + second.distanceTo(victim) + " squares out at " + second.tileX()
                        + "," + second.tileY() + ". Its stopping squares are the ones the"
                        + " first attacker is standing on, which is when a route aimed at"
                        + " the target's own square has nowhere to end");
        assertTrue(victim.hitPoints() < hall.hitPoints(), "neither attacker landed a blow");
    }

    /**
     * The jogging itself, stated as a property: an attacker that is not making
     * progress must not be running its walk animation. This is what the player
     * actually sees, and it is what distinguishes "closing in" from "stuck".
     */
    @Test
    @DisplayName("An attacker that cannot get closer stops walking")
    void aStuckAttackerDoesNotJog() {
        GameData data = load();
        World world = plain(data, 48);
        UnitType footman = data.unitTypes().types().get("unit-footman");
        UnitType hall = data.unitTypes().types().get("unit-town-hall");

        // Something with enough hit points to outlast the watching, or the
        // test skips itself and proves nothing.
        Unit victim = world.createUnit(hall, 1, 24, 24);
        Unit attacker = world.createUnit(footman, 0, 20, 24);
        world.orderAttack(attacker, victim);

        // Let it arrive and settle.
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 30; cycle++) {
            world.tick();
        }
        Assumptions.assumeTrue(victim.isAlive(), "the victim died before it could be watched");

        // Over the next stretch the attacker should be either standing or
        // striking, and its tile should not be churning.
        int startX = attacker.tileX();
        int startY = attacker.tileY();
        int moves = 0;
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 10; cycle++) {
            world.tick();
            if (!victim.isAlive()) {
                return;
            }
            if (attacker.tileX() != startX || attacker.tileY() != startY) {
                moves++;
                startX = attacker.tileX();
                startY = attacker.tileY();
            }
        }
        assertTrue(attacker.distanceTo(victim) <= Math.max(1, footman.maxAttackRange()),
                "the attacker is stuck " + attacker.distanceTo(victim) + " squares away");
        assertTrue(moves <= 4,
                "the attacker changed square " + moves + " times in ten seconds while"
                        + " already in reach of its target, which is the shuffling the"
                        + " player sees as running on the spot");
    }

    /** Something with reach should stop at its reach, not walk into contact. */
    @Test
    @DisplayName("A ranged unit stops at its range and shoots")
    void aRangedAttackerStopsAtRange() {
        GameData data = load();
        World world = plain(data, 48);
        UnitType archer = data.unitTypes().types().get("unit-archer");
        UnitType footman = data.unitTypes().types().get("unit-footman");
        Assumptions.assumeTrue(archer != null && archer.maxAttackRange() > 1,
                "no ranged unit to test with");

        Unit shooter = world.createUnit(archer, 0, 10, 20);
        Unit victim = world.createUnit(footman, 1, 30, 20);
        world.orderAttack(shooter, victim);

        assertTrue(landsABlow(world, victim, 90),
                "the archer never got a shot away; it ended "
                        + shooter.distanceTo(victim) + " squares out");
        assertTrue(shooter.distanceTo(victim) <= archer.maxAttackRange(),
                "the archer is out of its own range at the moment it fired");
    }
}
