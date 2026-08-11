package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * A Town Hall becoming a Keep.
 *
 * <p>This used to be wired to {@code orderTrain}, which charges the cost and
 * then puts a whole new building on the ground beside the old one. The spot
 * search only ever tested a single square, so a four by four Keep was jammed
 * against the Town Hall it was supposed to have become, overlapping whatever
 * stood there, with the original still standing and still yours. Every tier of
 * both tech trees did that: Keep, Castle, Stronghold, Fortress, and both
 * towers.
 *
 * <p>So the first thing asserted here is a count. The old behaviour produced
 * two buildings, and any test that only checked "is there a Keep now" would
 * have passed on it.
 */
class UpgradeToTest {

    private static World plain(int size) {
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
        return new World(map, players);
    }

    private static GameData load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    /** Enough of everything. A Keep wants oil as well as gold and lumber. */
    private static void wealthy(World world) {
        world.player(0).set(UnitType.Resource.GOLD, 10000);
        world.player(0).set(UnitType.Resource.WOOD, 10000);
        world.player(0).set(UnitType.Resource.OIL, 10000);
    }

    private static int countAlive(World world) {
        int count = 0;
        for (Unit unit : world.units()) {
            if (unit.isAlive() && unit.type().building()) {
                count++;
            }
        }
        return count;
    }

    @Test
    @DisplayName("A Town Hall becomes a Keep in place, and there is still only one building")
    void theHallBecomesAKeep() {
        GameData data = load();
        var types = data.unitTypes().types();
        UnitType hall = types.get("unit-town-hall");
        UnitType keep = types.get("unit-keep");
        assertNotNull(hall);
        assertNotNull(keep);

        World world = plain(32);
        world.setUnitTypes(types);
        Unit building = world.createUnit(hall, 0, 10, 10);
        assertNotNull(building);
        wealthy(world);

        int tileX = building.tileX();
        int tileY = building.tileY();
        assertTrue(world.orderUpgradeTo(building, keep), "the upgrade was refused");
        assertEquals(1, countAlive(world), "the upgrade should not have built anything yet");

        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 300; cycle++) {
            world.tick();
            if (building.type() == keep) {
                break;
            }
        }

        assertEquals(keep, building.type(), "the hall never became a keep");
        assertEquals(1, countAlive(world),
                "there are " + countAlive(world) + " buildings; an upgrade must transform"
                        + " the one that was there, not stand a second one beside it");
        assertEquals(tileX, building.tileX(), "it moved");
        assertEquals(tileY, building.tileY(), "it moved");
        assertEquals(keep, building.upgradingTo(),
                "the finished UpgradeTo order did not remain current on its completion cycle");
        world.tick();
        assertNull(building.upgradingTo());
    }

    @Test
    @DisplayName("Damage carries across in proportion, not in points")
    void damageIsProportional() {
        GameData data = load();
        var types = data.unitTypes().types();
        UnitType hall = types.get("unit-town-hall");
        UnitType keep = types.get("unit-keep");

        World world = plain(32);
        world.setUnitTypes(types);
        Unit building = world.createUnit(hall, 0, 10, 10);
        building.setHitPoints(hall.hitPoints() / 2);

        assertTrue(world.transformInto(building, keep));
        assertEquals(keep.hitPoints() / 2, building.hitPoints(), 2,
                "a half-ruined hall should be a half-ruined keep; keeping the raw number"
                        + " would make every upgrade a repair or leave the keep nearly dead");
    }

    @Test
    @DisplayName("An upgrade must be paid for, and cancelling gives most of it back")
    void payingAndCancelling() {
        GameData data = load();
        var types = data.unitTypes().types();
        UnitType hall = types.get("unit-town-hall");
        UnitType keep = types.get("unit-keep");

        World world = plain(32);
        world.setUnitTypes(types);
        Unit building = world.createUnit(hall, 0, 10, 10);

        world.player(0).set(UnitType.Resource.GOLD, 0);
        world.player(0).set(UnitType.Resource.WOOD, 0);
        world.player(0).set(UnitType.Resource.OIL, 0);
        assertFalse(world.orderUpgradeTo(building, keep), "an upgrade was granted for nothing");

        wealthy(world);
        int goldBefore = world.player(0).get(UnitType.Resource.GOLD);
        assertTrue(world.orderUpgradeTo(building, keep));
        assertTrue(world.player(0).get(UnitType.Resource.GOLD) < goldBefore,
                "the upgrade was not paid for");

        assertTrue(world.cancelUpgradeTo(building));
        assertNull(building.upgradingTo());
        assertEquals(hall, building.type(), "cancelling should leave it as it was");
        assertTrue(world.player(0).get(UnitType.Resource.GOLD) > goldBefore
                - keep.costs().getOrDefault(UnitType.Resource.GOLD, 0),
                "cancelling refunded nothing");
    }

    @Test
    @DisplayName("A building already busy will not also upgrade")
    void oneThingAtATime() {
        GameData data = load();
        var types = data.unitTypes().types();
        UnitType hall = types.get("unit-town-hall");
        UnitType keep = types.get("unit-keep");
        UnitType peasant = types.get("unit-peasant");

        World world = plain(32);
        world.setUnitTypes(types);
        Unit building = world.createUnit(hall, 0, 10, 10);
        wealthy(world);
        for (int i = 0; i < 4; i++) {
            world.createUnit(types.get("unit-farm"), 0, 2 + i * 3, 2);
        }
        world.recalculateSupply();

        assertTrue(world.orderTrain(building, peasant));
        assertFalse(world.orderUpgradeTo(building, keep),
                "a building training a peasant also started upgrading");
    }
}
