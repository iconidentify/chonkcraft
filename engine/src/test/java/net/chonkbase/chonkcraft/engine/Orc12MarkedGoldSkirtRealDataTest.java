package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated partially drained gold-skirt occupancy from retail Orc 12. */
class Orc12MarkedGoldSkirtRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("orc 12 keeps an older terminal stride hard to route ordering")
    void orc12PeonTakesWestBeforeThePartiallyDrainedGoldSkirt() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/orc/level12o";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Orc 12 is not in the pack");
        World world = mission.world();

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 530) {
            mission.tick();
        }

        Unit peon = unitById(world, 75);
        Unit skirtPeon = unitById(world, 185);
        assertNotNull(peon,
                "Orc 12 has no Java unit 75 / native slot 1525");
        assertNotNull(skirtPeon,
                "Orc 12 has no Java unit 185 / native slot 1415");

        assertEquals(87, peon.tileX());
        assertEquals(41, peon.tileY());
        assertEquals(84, skirtPeon.tileX());
        assertEquals(42, skirtPeon.tileY());
        assertEquals(23, skirtPeon.offsetX(),
                "the blocker is nine pixels into an older west stride");
        assertEquals(0, skirtPeon.offsetY());
        assertEquals(Direction.fromDelta(-1, 0),
                skirtPeon.lastStepHeading());
        assertTrue(skirtPeon.isMoving());
        assertEquals(0, skirtPeon.pathLength());
        assertTrue(skirtPeon.routeSpent());
        assertEquals(0, skirtPeon.battleNetCollisionCounter());
        assertSame(peon.resourceUnit(), skirtPeon.resourceUnit(),
                "both outbound workers belong to the same mine queue");
        assertEquals(UnitType.Resource.GOLD, skirtPeon.carrying());

        mission.tick();
        assertEquals(531, fixtureCycle(world));
        assertEquals(86, peon.tileX(),
                "native opens W,W,W,SW beside the older moving body");
        assertEquals(41, peon.tileY(),
                "a partially drained terminal stride remains hard until the final SW");
        assertEquals(Direction.fromDelta(-1, 0), peon.lastStepHeading());
        assertEquals(3, peon.pathLength());
        assertEquals(Direction.fromDelta(-1, 0), peon.peekHeading());
        assertEquals(Direction.fromDelta(-1, 0),
                peon.peekHeadingAtDepth(1));
        assertEquals(Direction.fromDelta(-1, 1),
                peon.peekHeadingAtDepth(2));
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }

    private static Unit unitById(World world, int id) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.id() == id) {
                return unit;
            }
        }
        return null;
    }
}
