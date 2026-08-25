package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Locks Human 12's map-edge forest approach and occupied-face fallback. */
class Human12BoundaryForestTipRealDataTest {

    private static final int INITIALIZATION_TICKS = 2;

    @Test
    void freeInteriorFacePrecedesTheBoundaryCornerUntilItIsOccupied() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 12 is not in the pack");
        World world = mission.world();
        Unit peon = unitById(world, 29);
        assertNotNull(peon, "Human 12 must contain native peon 1571 / Java 29");

        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        for (int fixture = 1; fixture <= 164; fixture++) {
            mission.tick();
        }

        assertEquals(99, peon.tileX());
        assertEquals(4, peon.tileY());
        assertEquals(Direction.fromDelta(1, -1), peon.lastStepHeading(),
                "fixture 164 commits the first north-east heading");
        assertEquals(4, peon.pathLength(),
                "native retains three north-east headings followed by east");
        assertEquals(Direction.fromDelta(1, -1), peon.peekHeadingAtDepth(0));
        assertEquals(Direction.fromDelta(1, -1), peon.peekHeadingAtDepth(1));
        assertEquals(Direction.fromDelta(1, -1), peon.peekHeadingAtDepth(2));
        assertEquals(Direction.fromDelta(1, 0), peon.peekHeadingAtDepth(3));
        assertEquals(5, peon.battleNetGoldFreePrefixLength());

        for (int fixture = 165; fixture <= 227; fixture++) {
            mission.tick();
        }
        assertEquals(102, peon.tileX());
        assertEquals(1, peon.tileY());
        assertEquals(-2, peon.offsetX());
        assertEquals(2, peon.offsetY());
        assertEquals(Direction.fromDelta(1, 0), peon.peekHeading(),
                "the interior west face remains the cached final heading");

        mission.tick();
        assertEquals(102, peon.tileX(),
                "fixture 228 parks when the interior face becomes occupied");
        assertEquals(1, peon.tileY());
        assertEquals(1, peon.battleNetCollisionCounter());
        assertEquals(0, peon.pathLength(),
                "native route index twenty retires the occupied axial tail");
        assertEquals(0, peon.battleNetOrderDelay(),
                "the boundary collision does not buy a cooperative wait band");

        mission.tick();
        assertEquals(103, peon.tileX(),
                "fixture 229 may fall back to the now-reachable boundary corner");
        assertEquals(0, peon.tileY());
        assertEquals(Direction.fromDelta(1, -1), peon.lastStepHeading());
    }

    private static Unit unitById(World world, int id) {
        return world.unitsSnapshot().stream()
                .filter(unit -> unit.id() == id)
                .findFirst().orElse(null);
    }
}
