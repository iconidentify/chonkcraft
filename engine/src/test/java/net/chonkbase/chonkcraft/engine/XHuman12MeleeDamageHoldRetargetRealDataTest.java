package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated melee free-scan timing across an attack-start damage hold. */
class XHuman12MeleeDamageHoldRetargetRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("a melee damage hold defers free-scan and SyncRand to op0")
    void aMeleeDamageHoldDefersFreeScanAndSyncRandToOp0() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        // Java 152 is native slot 1448. Native holds the footman goal while
        // Attack@2539 counts 23..1, then free-scans the adjacent knight and
        // calls FUN_004234b0 on fixture 66.
        Unit grunt = unitById(world, 152);
        Unit footman = unitById(world, 151);
        Unit knight = unitById(world, 154);
        assertNotNull(grunt, "XHuman 12 has no native-slot-1448 grunt");
        assertNotNull(footman, "XHuman 12 has no paired footman");
        assertNotNull(knight, "XHuman 12 has no paired knight");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 66) {
            mission.tick();
            int fixture = fixtureCycle(world);
            if (fixture == 62 || fixture == 65) {
                assertSame(footman, grunt.target(),
                        "free-scan must not interrupt the attack-start damage hold");
            }
            if (fixture == 66) {
                assertSame(knight, grunt.target(),
                        "the completed hold free-scans on native OP0");
                assertEquals(2539, grunt.battleNetSequenceOffset());
                assertEquals(3, grunt.battleNetAnimationTimer(),
                        "OP0 retarget restarts melee construction");
                assertEquals(0x31dff4f5, world.randomSeed(),
                        "the OP0 retarget must debit native FUN_004234b0");
            }
        }
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
