package net.chonkbase.chonkcraft.engine.missile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Projectiles: travel time and splash.
 *
 * <p>Before these, every attack landed on the frame it was made and a catapult
 * hit exactly one unit. Both are wrong, and the second is the difference
 * between a siege weapon and an expensive archer.
 */
class MissileTest {

    private static final String MAP = "campaigns/human/level02h";

    private record Fixture(GameData data, Map<String, UnitType> types, int x, int y) {}

    private static Fixture load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");

        GameData data = new GameData(install);
        Assumptions.assumeTrue(data.campaignMap(MAP) != null, "no campaign map available");
        int[] open = findOpenArea(world(data));
        Assumptions.assumeTrue(open != null, "no open ground on this map");
        return new Fixture(data, data.unitTypes().types(), open[0], open[1]);
    }

    private static World world(GameData data) {
        PudMap pud = data.campaignMap(MAP);
        World world = new World(GameMap.from(pud, data.loadTileset(pud.tileset()).tileset()),
                Player.from(pud));
        world.setUpgrades(data.upgrades().upgrades());
        world.setMissileTypes(data.missiles().types());
        return world;
    }

    /** Open ground three rows deep, so a cluster has somewhere to stand. */
    private static int[] findOpenArea(World world) {
        for (int y = 3; y < world.map().height() - 3; y++) {
            for (int x = 3; x < world.map().width() - 16; x++) {
                boolean clear = true;
                for (int i = 0; i < 16 && clear; i++) {
                    for (int d = -1; d <= 1; d++) {
                        if (!world.map().field(x + i, y + d).isLandPassable()) {
                            clear = false;
                            break;
                        }
                    }
                }
                if (clear) {
                    return new int[] {x, y};
                }
            }
        }
        return null;
    }

    @Test
    @DisplayName("the shipped missile types load with their speeds and splash")
    void theMissileTypesLoad() {
        Fixture fixture = load();
        Map<String, MissileType> types = fixture.data().missiles().types();
        assertEquals(35, types.size(), "missiles.legacy-declaration defines 35 types");

        MissileType bolt = types.get("missile-ballista-bolt");
        assertNotNull(bolt);
        assertTrue(bolt.speed() > 0, "a bolt has to travel");
        assertTrue(bolt.splashes(), "a ballista bolt spreads its damage");

        // An arrow does not: it hurts what it hit and nothing else.
        MissileType arrow = types.get("missile-arrow");
        assertNotNull(arrow);
        assertFalse(arrow.splashes());

        // The unit types name them, which is how a firer finds its projectile.
        assertEquals("missile-ballista-bolt", fixture.types().get("unit-ballista").missile());
        assertTrue(fixture.types().get("unit-footman").firesMissile() == false,
                "a footman strikes rather than throwing");
    }

    @Test
    @DisplayName("damage falls off with distance from the impact")
    void splashFallsOff() {
        MissileType type = new MissileType("test", null, MissileClass.POINT_TO_POINT,
                32, 32, 1, 1, 8, 1, 3, 2, 0, null, null, false, 0, 0, false);
        // The square struck takes it whole; each tile out divides by the
        // factor again.
        assertEquals(1, type.falloffAt(0));
        assertEquals(2, type.falloffAt(1));
        assertEquals(4, type.falloffAt(2));
    }

    @Test
    @DisplayName("a shot spends its first action measuring the journey, not flying it")
    void theFirstActionOnAMissileTakesNoneOfTheJourney() {
        // Speed 16, and a hundred and sixty pixels to cover: ten steps of
        // flying, and one before them that does not fly.
        MissileType type = new MissileType("test", null, MissileClass.POINT_TO_POINT,
                32, 32, 1, 1, 16, 1, 3, 2, 0, null, null, false, 0, 0, false);
        Missile shot = new Missile(type, null, null, 0, 0, 160, 0);

        assertEquals(0, shot.x(), "a missile starts at its muzzle");
        shot.step();
        assertEquals(0, shot.x(),
                "the first action moved the shot. MissileInitMove splits on"
                        + " missile.State's low bit: the even pass sets CurrentStep to nought,"
                        + " works out TotalStep and returns, and only from the next action does"
                        + " CurrentStep += Type->Speed happen (missile/missile.cpp:613-635)");

        shot.step();
        assertEquals(16, shot.x(), "the second action is the first that flies");

        // Ten flying steps in all, so it lands on the eleventh action. Landing
        // on the tenth is what this implementation used to do, and on demo02 that killed
        // a peasant a cycle early and took every draw after it with it.
        int actions = 2;
        while (shot.x() < 160 && actions < 40) {
            shot.step();
            actions++;
        }
        assertEquals(11, actions,
                "a hundred and sixty pixels at sixteen a step should land on the eleventh"
                        + " action, not the tenth");
    }

    @Test
    @DisplayName("a shot takes time to arrive")
    void aShotTravels() {
        Fixture fixture = load();
        World world = world(fixture.data());
        Unit ballista = world.createUnit(fixture.types().get("unit-ballista"), 0,
                fixture.x(), fixture.y());
        Unit victim = world.createUnit(fixture.types().get("unit-grunt"), 1,
                fixture.x() + 4, fixture.y());
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.orderAttack(ballista, victim);

        // A projectile that arrives on the cycle it was fired would never be
        // seen in the air. Seeing one is the check.
        boolean inFlight = false;
        for (int i = 0; i < 400 && !inFlight; i++) {
            world.tick();
            inFlight = !world.missiles().isEmpty();
        }
        assertTrue(inFlight, "the shot arrived instantly");
    }

    @Test
    @DisplayName("one siege shot damages a whole cluster")
    void splashHitsMoreThanOne() {
        Fixture fixture = load();
        World world = world(fixture.data());
        Unit ballista = world.createUnit(fixture.types().get("unit-ballista"), 0,
                fixture.x(), fixture.y());
        world.fog().revealAll(0);
        world.fog().revealAll(1);

        UnitType grunt = fixture.types().get("unit-grunt");
        Unit aimed = world.createUnit(grunt, 1, fixture.x() + 5, fixture.y());
        Unit beside = world.createUnit(grunt, 1, fixture.x() + 6, fixture.y());
        Unit below = world.createUnit(grunt, 1, fixture.x() + 5, fixture.y() + 1);
        int full = grunt.hitPoints();

        world.orderAttack(ballista, aimed);
        for (int i = 0; i < 400; i++) {
            world.tick();
        }

        int hurt = 0;
        for (Unit unit : new Unit[] {aimed, beside, below}) {
            if (unit.hitPoints() < full) {
                hurt++;
            }
        }
        assertTrue(hurt > 1, "only " + hurt + " unit took damage: this is not a siege weapon");
    }
}
