package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import net.chonkbase.chonkcraft.engine.missile.Missile;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where a shot starts and where it lands.
 *
 * <p>Both ends used to be the middle of the top-left tile, which for anything
 * bigger than one square is a corner. A shot at a four-by-four keep landed on
 * the corner of it, and since splash is measured from where the shot lands,
 * that put the blast a tile and a half from where it belonged.
 */
class MissileGeometryTest {

    private static GameData load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    private static World field(GameData data) {
        int size = 48;
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
        return world;
    }

    /**
     * An explosion is a missile that does not travel. Ours launched it where
     * it landed and treated that as arrival, so it was gone before a single
     * frame could be drawn -- every impact and every sapper going up.
     */
    @Test
    @DisplayName("An explosion stays put long enough to be seen")
    void anExplosionLingers() {
        GameData data = load();
        var types = data.missiles().types();
        net.chonkbase.chonkcraft.engine.missile.MissileType explosion = null;
        for (var candidate : types.values()) {
            if (candidate.missileClass()
                    == net.chonkbase.chonkcraft.engine.missile.MissileClass.STAY
                    && candidate.animationSteps() > 1) {
                explosion = candidate;
                break;
            }
        }
        Assumptions.assumeTrue(explosion != null, "no stay-in-place missile shipped");

        Missile blast = new Missile(explosion, null, null, 320, 320, 320, 320);
        int drawn = 0;
        for (int cycle = 0; cycle < 200 && !blast.hasArrived(); cycle++) {
            blast.step();
            drawn++;
        }
        assertTrue(drawn >= explosion.animationSteps(),
                "the blast was over in " + drawn + " cycles; its animation is "
                        + explosion.animationSteps() + " frames long and every one of"
                        + " them should get a chance to be drawn");
        assertEquals(320, (int) blast.x(), "an explosion must not wander");
        assertEquals(320, (int) blast.y(), "an explosion must not wander");
    }

    @Test
    @DisplayName("A shot at a big building is aimed at its middle, not its corner")
    void aimedAtTheMiddle() {
        GameData data = load();
        World world = field(data);
        UnitType archer = data.unitTypes().types().get("unit-archer");
        UnitType keep = data.unitTypes().types().get("unit-keep");
        assertNotNull(archer);
        assertNotNull(keep);
        assertEquals(4, keep.tileWidth(), "the fixture wants a four-by-four target");

        Unit target = world.createUnit(keep, 1, 20, 20);
        Unit shooter = world.createUnit(archer, 0, 18, 21);
        world.orderAttack(shooter, target);

        // Follow one shot to where it lands. The impact point is what splash
        // is measured from, so it is the thing worth asserting on.
        int landedX = -1;
        int landedY = -1;
        Missile shot = null;
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 30 && landedX < 0; cycle++) {
            world.tick();
            if (shot == null && !world.missiles().isEmpty()) {
                shot = world.missiles().get(0);
            }
            if (shot != null) {
                landedX = shot.tileX();
                landedY = shot.tileY();
                if (!shot.hasArrived()) {
                    landedX = -1;
                    landedY = -1;
                }
            }
        }
        Assumptions.assumeTrue(shot != null, "the archer never loosed");
        Assumptions.assumeTrue(landedX >= 0, "the shot never arrived");

        // The keep occupies tiles 20 to 23. Its middle is 21 or 22; its
        // top-left corner, where every shot used to land, is 20.
        assertTrue(landedX >= 21 && landedX <= 22,
                "the shot landed on column " + landedX + "; the keep spans 20 to 23 and"
                        + " its middle is 21 or 22, its corner 20");
        assertTrue(landedY >= 21 && landedY <= 22,
                "the shot landed on row " + landedY + ", not the keep's middle");
    }
}
