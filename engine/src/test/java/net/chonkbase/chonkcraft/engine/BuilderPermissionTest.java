package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A worker may only build what its own buttons declare.
 *
 * <p>{@code World.orderBuild} rejected a building as builder and a non-building
 * as target and asked nothing else, so any gatherer would accept any building
 * -- and an oil tanker is a gatherer. Found through the AI: with its build
 * queue unfrozen, {@code orc-14-green} sent a tanker to put five pig farms on
 * dry land.
 *
 * <p>The relation is upstream's {@code AiHelpers.Build()}, which
 * {@code InitAiHelper} reads off the button
 * table: the building is a build button's value and the workers allowed to
 * raise it are its {@code ForUnit} mask. Nothing else declares it, upstream or
 * here, which is why the engine reads the same table rather than trusting the
 * command panel to have hidden the button.
 */
class BuilderPermissionTest {

    private static final String MAP = "campaigns/orc/level01o";

    private static GameData load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    /** A patch of dry buildable ground with room for a farm and a worker beside it. */
    private static int[] drySite(World world, UnitType farm) {
        for (int y = 1; y < world.map().height() - 5; y++) {
            for (int x = 1; x < world.map().width() - 5; x++) {
                if (world.canPlaceBuilding(farm, x, y)
                        && world.canPlaceBuilding(farm, x + 3, y)) {
                    return new int[] {x, y};
                }
            }
        }
        return null;
    }

    @Test
    @DisplayName("an oil tanker cannot be sent to build a pig farm, and a peon can")
    void aTankerIsNotABuilder() {
        GameData data = load();
        Mission mission = data.loadMission(MAP);
        Assumptions.assumeTrue(mission != null, MAP + " will not load");
        World world = mission.world();

        UnitType tanker = data.unitTypes().types().get("unit-orc-oil-tanker");
        UnitType peon = data.unitTypes().types().get("unit-peon");
        UnitType farm = data.unitTypes().types().get("unit-pig-farm");
        assertNotNull(tanker);
        assertNotNull(peon);
        assertNotNull(farm);

        int[] site = drySite(world, farm);
        Assumptions.assumeTrue(site != null, "no buildable ground on this map");

        world.players()[0].set(UnitType.Resource.GOLD, 10_000);
        world.players()[0].set(UnitType.Resource.WOOD, 10_000);
        int goldBefore = world.players()[0].get(UnitType.Resource.GOLD);

        Unit boat = world.createUnit(tanker, 0, site[0] + 3, site[1]);
        assertNotNull(boat, "the fixture needs a tanker to give the order to");
        assertFalse(world.orderBuild(boat, farm, site[0], site[1]),
                "an oil tanker gathers oil; it has no pig farm button and the engine"
                        + " must not take the order on the panel's word");
        assertFalse(boat.order() == Unit.Order.BUILD,
                "and it must not be left walking to a site it may not build on");
        assertTrue(world.players()[0].get(UnitType.Resource.GOLD) == goldBefore,
                "a refused order must not have reserved the cost");

        // The control: the same order, the same square, a worker that may.
        Unit worker = world.createUnit(peon, 0, site[0] + 3, site[1] + 1);
        assertNotNull(worker);
        assertTrue(world.orderBuild(worker, farm, site[0], site[1]),
                "a peon builds pig farms; refusing this would mean the check is simply off");
        assertTrue(worker.order() == Unit.Order.BUILD);
    }

    @Test
    @DisplayName("the one thing a tanker may build is an oil platform")
    void aTankerBuildsPlatforms() {
        GameData data = load();
        UnitType tanker = data.unitTypes().types().get("unit-orc-oil-tanker");
        UnitType platform = data.unitTypes().types().get("unit-orc-oil-platform");
        UnitType farm = data.unitTypes().types().get("unit-pig-farm");
        Assumptions.assumeTrue(tanker != null && platform != null && farm != null);

        World world = data.loadMission(MAP).world();
        assertTrue(world.mayBuild(tanker, platform),
                "the tanker's own build button names the oil platform");
        assertFalse(world.mayBuild(tanker, farm));
    }

    /**
     * A world nobody gave a button table to keeps taking build orders. Every
     * hand-built fixture in this suite is that shape and the editor is too, so
     * an empty table means the question was never asked rather than answered
     * no.
     */
    @Test
    @DisplayName("a world with no button table does not refuse everything")
    void anUnconfiguredWorldStillBuilds() {
        World world = new World(new net.chonkbase.chonkcraft.engine.map.GameMap(16, 16,
                new net.chonkbase.chonkcraft.engine.map.Tileset()));
        UnitType worker = new UnitType("unit-test-worker");
        UnitType hut = new UnitType("unit-test-hut");
        hut.setBuilding(true);
        assertTrue(world.mayBuild(worker, hut));
    }
}
