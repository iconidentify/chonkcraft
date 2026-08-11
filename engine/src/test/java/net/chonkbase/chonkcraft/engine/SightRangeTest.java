package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * How far a unit can actually see.
 *
 * <p>Sight was the type's flat {@code SightRange} wherever it was asked for,
 * which ignores both halves of {@code UpdateUnitSightRange}.
 *
 * <p>A building under construction is supposed to see one square. Without that
 * a Watch Tower lights the neighbourhood the instant it is sited, before a
 * peasant has swung a hammer, which is free scouting for the price of a
 * cancelled build.
 *
 * <p>And upgrades count. {@code upgrade-ranger-scouting} is
 * {@code {"SightRange", 3}} over a base of four -- it is most of what the
 * upgrade is bought for -- and {@code UpgradeState.sightRange} had worked that
 * out correctly for as long as it had existed without a single caller.
 */
class SightRangeTest {

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
        return world;
    }

    /**
     * Puts a building back into its scaffolding.
     *
     * <p>Setting the order alone is not enough: a site with no work left to do
     * finishes itself on the next tick, so the goal has to be set as well or
     * the test measures a finished tower and passes for the wrong reason.
     */
    private static void underConstruction(Unit site) {
        site.setOrder(Unit.Order.UNDER_CONSTRUCTION);
        site.setProgress(0);
        site.setProgressGoal(10_000);
    }

    /** How far from a unit the fog has been lifted, in squares. */
    private static int reach(World world, int player, int atX, int atY) {
        int reach = 0;
        while (world.fog().isVisible(player, atX + reach + 1, atY)) {
            reach++;
        }
        return reach;
    }

    @Test
    @DisplayName("A sight upgrade actually widens what a unit sees")
    void anUpgradeWidensSight() {
        GameData data = load();
        World world = plain(data, 64);
        UnitType archer = data.unitTypes().types().get("unit-archer");
        assertNotNull(archer);
        Assumptions.assumeTrue(
                data.upgrades().upgrades().all().containsKey("upgrade-ranger-scouting"),
                "no scouting upgrade in this installation");

        Unit unit = world.createUnit(archer, 0, 30, 30);
        assertNotNull(unit);
        world.tick();
        int before = reach(world, 0, 30, 30);
        assertTrue(before > 0, "the archer sees nothing at all");

        world.upgrades(0).complete("upgrade-ranger-scouting");
        // The sweep that re-grants sight runs once a second, as upstream's own
        // update does.
        for (int cycle = 0; cycle <= World.CYCLES_PER_SECOND; cycle++) {
            world.tick();
        }
        int after = reach(world, 0, 30, 30);
        assertTrue(after > before,
                "ranger scouting is worth three squares of sight and this archer still sees "
                        + before + "; the upgrade was computed and never applied to the fog");
    }

    /**
     * The counterpart, and the reason the range a unit was marked with is
     * remembered rather than recomputed: taking sight away at the new range
     * after granting it at the old one leaves squares lit that nothing can put
     * out.
     */
    @Test
    @DisplayName("Sight granted before an upgrade is taken away cleanly after it")
    void anUpgradeMidLifeLeavesNothingBehind() {
        GameData data = load();
        World world = plain(data, 64);
        UnitType archer = data.unitTypes().types().get("unit-archer");
        Assumptions.assumeTrue(
                data.upgrades().upgrades().all().containsKey("upgrade-ranger-scouting"),
                "no scouting upgrade in this installation");

        Unit unit = world.createUnit(archer, 0, 30, 30);
        world.tick();
        world.upgrades(0).complete("upgrade-ranger-scouting");
        for (int cycle = 0; cycle <= World.CYCLES_PER_SECOND; cycle++) {
            world.tick();
        }
        world.remove(unit);
        world.tick();

        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                assertFalse(world.fog().isVisible(0, x, y),
                        x + "," + y + " is still lit after the only unit that could see it"
                                + " was removed");
            }
        }
    }

    @Test
    @DisplayName("A building under construction sees one square")
    void aBuildingSiteIsNearlyBlind() {
        GameData data = load();
        World world = plain(data, 64);
        UnitType tower = data.unitTypes().types().get("unit-human-watch-tower");
        Assumptions.assumeTrue(tower != null, "no watch tower in this installation");
        Assumptions.assumeTrue(tower.sightRange() > 2,
                "the tower's finished sight is not wide enough for this to prove anything");

        Unit site = world.createUnit(tower, 0, 30, 30);
        assertNotNull(site);
        underConstruction(site);
        for (int cycle = 0; cycle <= World.CYCLES_PER_SECOND; cycle++) {
            world.tick();
        }
        Assumptions.assumeTrue(site.order() == Unit.Order.UNDER_CONSTRUCTION,
                "the site finished itself before it could be looked at");

        int reach = reach(world, 0, 30, 30);
        assertTrue(reach <= 2,
                "a building site is seeing " + reach + " squares out, against a finished"
                        + " tower's " + tower.sightRange() + ": siting a tower should not"
                        + " scout for you");
    }

    /** And it opens up once it is finished. */
    @Test
    @DisplayName("A finished building sees its full range")
    void aFinishedBuildingSeesProperly() {
        GameData data = load();
        World world = plain(data, 64);
        UnitType tower = data.unitTypes().types().get("unit-human-watch-tower");
        Assumptions.assumeTrue(tower != null, "no watch tower in this installation");

        Unit site = world.createUnit(tower, 0, 30, 30);
        underConstruction(site);
        for (int cycle = 0; cycle <= World.CYCLES_PER_SECOND; cycle++) {
            world.tick();
        }
        int building = reach(world, 0, 30, 30);

        site.setOrder(Unit.Order.STILL);
        for (int cycle = 0; cycle <= World.CYCLES_PER_SECOND; cycle++) {
            world.tick();
        }
        int finished = reach(world, 0, 30, 30);

        assertTrue(finished > building,
                "the tower saw " + building + " squares under construction and " + finished
                        + " when finished; it never opened up");
    }
}
