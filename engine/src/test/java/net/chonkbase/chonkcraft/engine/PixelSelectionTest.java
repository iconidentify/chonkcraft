package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Clicking a unit hits the box you can see, not the square it stands on.
 *
 * <p>Implements {@code UnitOnScreen}. The two answers agree for a unit standing
 * still and disagree for one that is moving: a unit part way between squares is
 * drawn where its sprite is, up to a whole tile from the square the click would
 * otherwise resolve to. Everything worth clicking in a fight is moving, so the
 * symptom was a right click on an enemy that walked your soldiers past it.
 */
class PixelSelectionTest {

    private static World world() {
        GameMap map = new GameMap(32, 32, new Tileset());
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return new World(map);
    }

    private static UnitType footman() {
        UnitType type = new UnitType("unit-footman");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        return type;
    }

    private static UnitType hall() {
        UnitType type = new UnitType("unit-town-hall");
        type.setTileSize(4, 4);
        type.setBoxSize(126, 126);
        type.setHitPoints(1200);
        type.setBuilding(true);
        return type;
    }

    private static UnitType gryphon() {
        UnitType type = new UnitType("unit-gryphon-rider");
        type.setTileSize(2, 2);
        type.setBoxSize(63, 63);
        type.setHitPoints(100);
        type.setSpeed(14);
        type.setAirUnit(true);
        return type;
    }

    @Test
    @DisplayName("a standing unit is found under its own square")
    void aStandingUnitIsFound() {
        World world = world();
        Unit footman = world.createUnit(footman(), 0, 5, 5);
        // The middle of its square.
        assertSame(footman, world.unitAtPixel(5 * 32 + 16, 5 * 32 + 16, null));
        // Well outside the box.
        assertNull(world.unitAtPixel(9 * 32, 9 * 32, null));
    }

    @Test
    @DisplayName("a moving unit is found where it is drawn, not where its square is")
    void aMovingUnitFollowsItsSprite() {
        World world = world();
        Unit footman = world.createUnit(footman(), 0, 5, 5);
        // Half a square along, as a walking unit is for most of its journey.
        footman.setOffset(16, 0);

        int drawnCentreX = footman.pixelX() + 16;
        int drawnCentreY = footman.pixelY() + 16;
        assertSame(footman, world.unitAtPixel(drawnCentreX, drawnCentreY, null),
                "the unit must be clickable where it is drawn");

        // And the square it logically occupies no longer covers its box, which
        // is precisely the gap: a tile lookup would still answer here.
        assertEquals(5, footman.tileX());
        assertNull(world.unitAtPixel(5 * 32 + 1, 5 * 32 + 16, null),
                "the left edge of the old square is now empty ground");
    }

    @Test
    @DisplayName("a building is found anywhere across its footprint")
    void aBuildingHasABigBox() {
        World world = world();
        Unit hall = world.createUnit(hall(), 0, 10, 10);
        assertSame(hall, world.unitAtPixel(10 * 32 + 64, 10 * 32 + 64, null));
        assertSame(hall, world.unitAtPixel(10 * 32 + 8, 10 * 32 + 8, null));
        assertNull(world.unitAtPixel(10 * 32 - 40, 10 * 32 + 64, null));
    }

    @Test
    @DisplayName("an edge flyer is clickable on the visible part of its box")
    void anEdgeFlyerRemainsClickableInsideTheMap() {
        World world = world();
        Unit gryphon = world.createUnit(gryphon(), 0, 29, 31);

        assertSame(gryphon, world.unitAt(30, 31),
                "the visible map row must retain the flyer's occupancy");
        assertSame(gryphon, world.unitAtPixel(30 * 32 + 16, 31 * 32 + 16, null),
                "the clipped visible half of the selection box must accept a click");
    }

    @Test
    @DisplayName("a unit the caller rules out is skipped")
    void theFilterIsHonoured() {
        World world = world();
        Unit footman = world.createUnit(footman(), 0, 5, 5);
        assertNotNull(world.unitAtPixel(5 * 32 + 16, 5 * 32 + 16, unit -> true));
        assertNull(world.unitAtPixel(5 * 32 + 16, 5 * 32 + 16, unit -> unit != footman),
                "the visibility filter has to be able to hide a unit");
    }
}
