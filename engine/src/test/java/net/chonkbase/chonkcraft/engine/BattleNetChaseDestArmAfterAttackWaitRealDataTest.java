package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A computer grunt dest-arms leftover the visit its spent Attack wait
 * refills the route.
 *
 * <p>Authenticated Human 1 field walk: grunt 1591 dest-arms 28,23 at 223,
 * 28,22 at 239, 27,21 at 255, then sits the leftover-land fight and Attack
 * wait through 320. Native dest-arms 26,22 at 321 and 25,27 at 401.
 * {@code 0x437c80} dest-arms leftover the same visit {@code 0x44fbd0}
 * answers. Java rebuilt path=2 at 321 and dest-armed at 322, then stayed
 * one visit late through 402.
 */
class BattleNetChaseDestArmAfterAttackWaitRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;
    private static final String MAP = "campaigns/human/level01h";

    @Test
    @DisplayName("a computer grunt dest-arms leftover the visit the spent attack wait refills")
    void aComputerGruntDestArmsLeftoverTheVisitTheSpentAttackWaitRefills() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission(MAP,
                GameData.personIn(data.campaignMap(MAP)), 1);
        Assumptions.assumeTrue(mission != null, "Human 1 is not in the pack");
        World world = mission.world();
        CommandApplier commands = new CommandApplier(
                world, new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Unit walker = atTile(world, 21, 5);
        Unit grunt = atTile(world, 29, 24);
        assertNotNull(walker, "Human 1 has no footman on 21,5");
        assertNotNull(grunt, "Human 1 has no grunt on 29,24");
        boolean issued = false;
        Integer arm2622 = null;
        Integer arm2527 = null;
        Integer arm223 = null;
        Integer arm239 = null;
        Integer arm255 = null;
        int lastX = grunt.tileX();
        int lastY = grunt.tileY();
        while (fixtureCycle(world) <= 410) {
            if (fixtureCycle(world) == 4 && !issued) {
                assertTrue(commands.apply(GameCommand.move(
                                walker.player(), walker.id(), 25, 28)),
                        "the field click to 25,28 must be accepted");
                issued = true;
            }
            mission.tick();
            int cycle = fixtureCycle(world);
            if (grunt.tileX() == lastX && grunt.tileY() == lastY) {
                continue;
            }
            lastX = grunt.tileX();
            lastY = grunt.tileY();
            if (lastX == 28 && lastY == 23 && arm223 == null) {
                arm223 = cycle;
            }
            if (lastX == 28 && lastY == 22 && arm239 == null) {
                arm239 = cycle;
            }
            if (lastX == 27 && lastY == 21 && arm255 == null) {
                arm255 = cycle;
            }
            if (lastX == 26 && lastY == 22 && arm2622 == null) {
                arm2622 = cycle;
            }
            if (lastX == 25 && lastY == 27 && arm2527 == null) {
                arm2527 = cycle;
            }
        }
        assertTrue(issued, "the field click must be issued");
        assertEquals(223, arm223,
                "retail dest-arms the chase onto 28,23 at 223, not " + arm223);
        assertEquals(239, arm239,
                "retail dest-arms onto 28,22 at 239, not " + arm239);
        assertEquals(255, arm255,
                "retail dest-arms onto 27,21 at 255, not " + arm255);
        assertEquals(321, arm2622,
                "retail dest-arms leftover onto 26,22 the visit the spent "
                        + "Attack wait refills, not " + arm2622);
        assertEquals(401, arm2527,
                "retail dest-arms onto 25,27 at 401, not " + arm2527);
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }

    private static Unit atTile(World world, int x, int y) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.isAlive() && unit.isOnMap() && unit.tileX() == x
                    && unit.tileY() == y && unit.type() != null
                    && !unit.type().building() && unit.type().speed() > 0) {
                return unit;
            }
        }
        return null;
    }
}
