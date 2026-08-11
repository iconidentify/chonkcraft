package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where a unit the map cannot fit ends up standing.
 *
 * <p>{@code maps/demo/demo02} asks for four units on squares that will not
 * take them -- an oil platform at 13,21, an orc destroyer at 12,24, a human
 * destroyer at 10,26 and another orc destroyer at 10,28 -- and each is moved
 * to a free square beside the one asked for by {@code DropOutOnSide}. Which
 * square depends entirely on the heading it is given, because the heading
 * decides which leg of the search runs first: from 10,26 the north leg finds
 * 10,27 and the east leg finds 9,26, and both are one square from the same
 * starting point.
 *
 * <p>The heading is drawn, and the draws were read off the real binary rather
 * than reasoned about. Logging {@code SyncRand} through a whole load of this
 * map shows upstream making exactly eighteen draws -- fourteen from the
 * heading {@code CUnit::Init} gives every unit that faces more than one way,
 * and four from {@code CclCreateUnit}'s displacement, which is fourteen
 * non-building units and four that do not fit -- and the seed before the
 * first of them is {@code 0x87654321}. {@code InitSyncRand} runs before a map
 * script as well as after it, so the sequence is reproducible; a generator
 * started at nought was tried first, on the reasoning that
 * {@code SyncRandSeed} is a global with no initialiser, and it put every
 * displaced unit in the wrong place.
 *
 * <p>The four squares below are upstream's own, taken from its trace of this
 * map. Getting them wrong is not cosmetic: a ship a square out at load is a
 * ship in a different fight a hundred cycles later, and it took this map's
 * divergence from 2,907 findings to 1,711 and its pairing from sixteen units
 * of eighteen to all eighteen.
 */
class MapLoadPlacementTest {

    private static World demo02() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");

        GameData data = new GameData(install);
        PudMap pud = data.campaignMap("maps/demo/demo02");
        Assumptions.assumeTrue(pud != null, "maps/demo/demo02 is not in this installation");

        World world = new World(GameMap.from(pud, data.loadTileset(pud.tileset()).tileset()),
                Player.forSoloGame(pud));
        world.setUnitTypes(data.unitTypes().types());
        data.populate(world, pud);
        return world;
    }

    @Test
    @DisplayName("a unit the map cannot fit lands where upstream puts it")
    void displacedUnitsLandOnUpstreamsSquares() {
        World world = demo02();

        assertEquals(18, world.units().size(),
                "the fixture is this map's own eighteen units; if the map or the loader has"
                        + " changed, the squares below mean nothing");

        assertEquals(1, world.units().stream().filter(u -> u.type() != null
                && "unit-human-oil-platform".equals(u.type().ident())
                && u.tileX() == 12 && u.tileY() == 22).count(),
                "the oil platform asked for 13,21 and upstream puts it at 12,22");
        assertEquals(1, world.units().stream().filter(u -> u.type() != null
                && "unit-orc-destroyer".equals(u.type().ident())
                && u.tileX() == 11 && u.tileY() == 25).count(),
                "the first orc destroyer asked for 12,24 and upstream puts it at 11,25");
        assertEquals(1, world.units().stream().filter(u -> u.type() != null
                && "unit-human-destroyer".equals(u.type().ident())
                && u.tileX() == 10 && u.tileY() == 27).count(),
                "the human destroyer asked for 10,26 and upstream puts it at 10,27, which is the"
                        + " north leg of the search. 9,26 is what the east leg finds, and that is"
                        + " where this port used to put it -- a heading band out.");
        assertEquals(1, world.units().stream().filter(u -> u.type() != null
                && "unit-orc-destroyer".equals(u.type().ident())
                && u.tileX() == 10 && u.tileY() == 29).count(),
                "the second orc destroyer asked for 10,28 and upstream puts it at 10,29");
    }
}
