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
 * Where a catapult decides to fire.
 *
 * <p>A splash weapon is not choosing a target, it is choosing a place to land,
 * and what else is standing there is the whole question. Upstream switches to
 * a different finder for exactly this. Without it the implementation scored a catapult's
 * options the same way it scores a footman's, and would happily drop a boulder
 * on the enemy standing in the middle of your own men.
 */
class SplashTargetTest {

    private static GameData load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    private static World field(GameData data) {
        int size = 64;
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
        world.setMissileTypes(data.missiles().types());
        world.setUpgrades(data.upgrades().upgrades());
        // The avoidance below is the FillBadGood walk, and the shipped
        // SimplifiedAutoTargeting bypasses it outright -- under the data's
        // own setting a catapult picks by priority like everyone else

        // This fixture turns the setting off to keep the older branch
        // honest for the games that configure it.
        world.setSimplifiedAutoTargeting(false);
        return world;
    }

    /**
     * Two enemies equally far away and equally worth hitting. One of them is
     * surrounded by your own footmen. The catapult should take the other.
     */
    @Test
    @DisplayName("A catapult does not fire into its own side")
    void itAvoidsItsOwnMen() {
        GameData data = load();
        World world = field(data);
        UnitType catapult = data.unitTypes().types().get("unit-catapult");
        UnitType footman = data.unitTypes().types().get("unit-footman");
        UnitType grunt = data.unitTypes().types().get("unit-grunt");
        assertNotNull(catapult);
        assertNotNull(footman);

        Unit siege = world.createUnit(catapult, 0, 30, 30);
        assertTrue(siege.type().firesMissile(), "the fixture wants a missile weapon");

        // One enemy to the west, alone. One to the east, standing among four
        // of your own footmen. Both the same distance from the catapult.
        Unit alone = world.createUnit(grunt, 1, 26, 30);
        Unit amongOurs = world.createUnit(grunt, 1, 34, 30);
        // Wounded, so the ordinary cost function actively prefers it: a
        // damaged target is cheaper by the health term and would be chosen on
        // every other measure. Only weighing the friendly fire changes the
        // answer, which is what makes this test worth having -- with both
        // grunts at full health the plain path picked the lone one anyway and
        // the test passed with the change switched off.
        amongOurs.setHitPoints(Math.max(1, grunt.hitPoints() / 4));
        world.createUnit(footman, 0, 33, 30);
        world.createUnit(footman, 0, 35, 30);
        world.createUnit(footman, 0, 34, 29);
        world.createUnit(footman, 0, 34, 31);

        // Let it pick for itself.
        Unit chosen = null;
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 20; cycle++) {
            world.tick();
            if (siege.target() != null) {
                chosen = siege.target();
                break;
            }
        }
        assertNotNull(chosen, "the catapult never chose anything to shoot at");
        assertTrue(chosen == alone,
                "it aimed at the grunt standing in the middle of four of your own"
                        + " footmen when an identical one was standing by itself the"
                        + " same distance away");
    }
}
