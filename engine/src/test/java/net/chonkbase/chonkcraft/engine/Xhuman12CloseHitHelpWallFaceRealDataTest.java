package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated first wall face for XHuman 12's close ranged HitUnit help. */
class Xhuman12CloseHitHelpWallFaceRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("xhuman 12's ranged close-hit helper opens on the clockwise wall face")
    void xhuman12FootmanKeepsTheNativeFirstSuccessfulWallFace() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit footman = unitById(world, 123);
        Unit axethrower = unitById(world, 77);
        assertNotNull(footman,
                "XHuman 12 has no Java unit 123 / native footman 1477");
        assertNotNull(axethrower,
                "XHuman 12 has no Java unit 77 / native axethrower 1523");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 216) {
            mission.tick();
        }

        assertEquals(32, footman.tileX(),
                "the helper remains at its native construction anchor");
        assertEquals(43, footman.tileY(),
                "the helper remains at its native construction anchor");
        assertSame(axethrower, footman.target(),
                "the queued close-hit help names native slot 1523");
        assertTrue(footman.battleNetRangedCloseHitHelpWallFace(),
                "the first chase retains ranged close-hit provenance");

        mission.tick();

        assertEquals(33, footman.tileX(),
                "native first-steps south-east, not north-west");
        assertEquals(44, footman.tileY(),
                "native first-steps south-east, not north-west");
        assertEquals(Direction.fromDelta(1, 1), footman.lastStepHeading(),
                "the close-hit chase consumes the clockwise face's SE byte");
        assertEquals(19, footman.pathLength(),
                "native retains the bounded twenty-byte first wall face");
        assertEquals(Direction.fromDelta(1, 0), footman.peekHeading(),
                "the second native route byte is east");
        assertFalse(footman.battleNetRangedCloseHitHelpWallFace(),
                "the clockwise wall-face provenance is one-shot");
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
