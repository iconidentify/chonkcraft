package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Human 8 attack-peasant 1520 dest-arms south onto the hall skirt.
 *
 * <p>Authenticated native dest-arm at fixture 583 consumes NewPath's south
 * byte from 77,68 onto 77,69 with remaining S,SE,S. Dest-arm leftover used
 * to treat the peasant's death-vision marker at 77,70 as a hostile sitting
 * two tiles along that cardinal leftover and rewrite the first heading to
 * south-west onto 76,69.</p>
 */
class Human08PeasantDestArmSouthRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("human 8's attack peasant dest-arms south onto 77,69 at fixture 583")
    void human8sAttackPeasantDestArmsSouthOnto7769AtFixture583() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level08h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 8 is not in the pack");
        World world = mission.world();
        Unit peasant = unitById(world, 80);
        assertNotNull(peasant, "Human 8 has no Java twin for native peasant 1520");
        assertEquals("unit-attack-peasant", peasant.type().ident());

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 582) {
            mission.tick();
        }
        assertEquals(77, peasant.tileX(),
                "retail is still on 77,68 before dest-arm, not "
                        + peasant.tileX() + "," + peasant.tileY());
        assertEquals(68, peasant.tileY(),
                "retail is still on 77,68 before dest-arm, not "
                        + peasant.tileX() + "," + peasant.tileY());

        mission.tick();
        assertEquals(583, fixtureCycle(world));
        assertEquals(77, peasant.tileX(),
                "retail dest-arms south onto 77,69, not "
                        + peasant.tileX() + "," + peasant.tileY());
        assertEquals(69, peasant.tileY(),
                "retail dest-arms south onto 77,69, not "
                        + peasant.tileX() + "," + peasant.tileY());
        assertEquals(Direction.fromDelta(0, 1), peasant.lastStepHeading(),
                "retail consumes NewPath's south byte, not leftover south-west");
        assertEquals(3, peasant.pathLength(),
                "native remaining after dest-arm south is S,SE,S");
        assertEquals(Direction.fromDelta(0, 1), peasant.peekHeadingAtDepth(0),
                "the leftover heading after dest-arm south is due south");
        assertEquals(Direction.fromDelta(1, 1), peasant.peekHeadingAtDepth(1),
                "the second leftover heading is south-east");
        assertEquals(Direction.fromDelta(0, 1), peasant.peekHeadingAtDepth(2),
                "the third leftover heading is due south");
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
