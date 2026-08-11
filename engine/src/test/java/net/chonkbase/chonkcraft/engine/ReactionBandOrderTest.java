package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What an idle troop can and cannot see when the marching order lies.
 *
 * <p>Battle.net's reaction scan does not walk the units and measure them. It
 * binary searches one persistent index for the entries whose tile Y falls in
 * a band around its rectangle ({@code FUN_0040a2b0} reached from
 * {@code FUN_00409ff0}), and that index is ordered by pixel Y, not tile Y --
 * two different numbers for anybody part-way between squares. A unit whose
 * tile has snapped north ahead of its feet sorts behind a unit whose tile Y
 * has already left the band, and the search stops at that inversion.</p>
 *
 * <p>Human 13's ogre in native pool slot 1519 is the case that proved it: it
 * stands still at fixture 29 with a knight six squares away and takes the
 * same knight, on the same square at the same 84 hit points, at 34. At 29 an
 * axethrower at pixel Y 803 with tile Y 26 stands in front of the knight at
 * pixel Y 822 with tile Y 25; by 34 the knight has fallen to pixel Y 809 and
 * sorts ahead of it. Replaying the index over the sealed fixture reproduces
 * all three native scans, 42, 41 and 42 entries.</p>
 */
class ReactionBandOrderTest {

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static UnitType troop(String ident) {
        UnitType type = new UnitType(ident);
        type.setTileSize(1, 1);
        type.setHitPoints(90);
        type.setSpeed(13);
        type.setLandUnit(true);
        type.setNumDirections(8);
        type.setSightRange(4);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setMaxAttackRange(1);
        type.setReactRangePerson(6);
        type.setReactRangeComputer(6);
        type.setBasicDamage(8);
        type.setPiercingDamage(4);
        type.setPriority(60);
        return type;
    }

    private static UnitType knight() {
        UnitType type = troop("unit-knight");
        type.setHitPoints(84);
        return type;
    }

    /**
     * The unit that only ever holds a place in the index.
     *
     * <p>It cannot fight, so it never acquires anything of its own and never
     * leaves the square that puts it in front of the knight.</p>
     */
    private static UnitType bystander() {
        UnitType type = troop("unit-peon");
        type.setCanAttack(false);
        type.setCanTargetLand(false);
        type.setMaxAttackRange(0);
        type.setReactRangePerson(0);
        type.setReactRangeComputer(0);
        return type;
    }

    /**
     * Places the three units, then lets the once-a-cycle index sort settle.
     *
     * <p>The blocker stands one square below the band and has already snapped
     * its tile, so its pixel Y is the lower of the two; the knight stands
     * inside the rectangle with its feet still trailing behind its tile.</p>
     */
    private static Unit scanWithKnightOffset(int knightOffsetY) {
        World world = new World(grass(48));
        Unit ogre = world.createUnit(troop("unit-ogre"), 0, 24, 10);
        Unit blocker = world.createUnit(troop("unit-axethrower"), 0, 24, 17);
        Unit prey = world.createUnit(knight(), 1, 20, 16);
        blocker.setOffset(0, -29);
        prey.setOffset(0, knightOffsetY);
        for (int cycle = 0; cycle < 6 && ogre.target() == null; cycle++) {
            world.tick();
        }
        return ogre.target();
    }

    @Test
    @DisplayName("an idle ogre cannot see a knight sorted behind a neighbour that has left the band")
    void aTargetBehindTheBandEdgeIsInvisible() {
        // Tile Y 16 is inside the rectangle and the band; pixel Y 534 is
        // behind the blocker's 515, whose tile Y 17 is already outside it.
        assertNull(scanWithKnightOffset(22),
                "the ogre acquired a knight the native band search never reaches."
                        + " FUN_0040a2b0 binary searches the pixel-Y index on tile Y"
                        + " and stops at the first entry whose tile Y has left the"
                        + " band, so a unit whose tile snapped ahead of its feet is"
                        + " invisible that cycle; walking the whole index instead"
                        + " turns Human 13's ogre 1519 Attack at fixture 29 where"
                        + " retail stands still until 34");
    }

    @Test
    @DisplayName("the same knight is taken once its feet catch up and it sorts ahead again")
    void theSameTargetIsTakenOnceItSortsAhead() {
        // The only change is the knight's pixel Y: 512 now sorts ahead of the
        // blocker's 515, exactly as slot 1500 falls from 822 to 809 by 34.
        Unit target = scanWithKnightOffset(0);
        assertNotNull(target,
                "the ogre found nobody once the inversion cleared, so the band"
                        + " window is refusing a candidate native does reach");
        assertEquals("unit-knight", target.type().ident(),
                "the ogre took something other than the knight standing in its"
                        + " rectangle once the index order stopped hiding it");
    }
}
