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
 * A computer Still grunt waits for its own Still marker to acquire.
 *
 * <p>Authenticated Human 1 field walk: grunt 1591 at 29,24 is still Still
 * when the clicked footman dest-arms onto 26,18 at cycle 217 (react 6).
 * Native's last Still OP0 was 215 at dist 7. The next marker is 220, which
 * installs Attack; dest-arm is 223. Java's neighbour dest-arm scan used to
 * acquire at 217 and dest-arm at 220, so the first blow landed at 278
 * instead of 281.
 */
class BattleNetChaseAcquireStillMarkerRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;
    private static final String MAP = "campaigns/human/level01h";

    @Test
    @DisplayName("a human 1 grunt waits for its Still marker before chasing the walk")
    void aHuman1GruntWaitsForItsStillMarkerBeforeChasingTheWalk() {
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
        Integer acquired = null;
        Integer destArm = null;
        Integer firstHp = null;
        int startHp = walker.hitPoints();
        int startX = grunt.tileX();
        int startY = grunt.tileY();
        Unit.Order at217 = null;
        while (fixtureCycle(world) <= 285) {
            if (fixtureCycle(world) == 4 && !issued) {
                assertTrue(commands.apply(GameCommand.move(
                                walker.player(), walker.id(), 25, 28)),
                        "the field click to 25,28 must be accepted");
                issued = true;
            }
            mission.tick();
            int cycle = fixtureCycle(world);
            if (cycle == 217) {
                at217 = grunt.order();
            }
            if (acquired == null && grunt.order() == Unit.Order.ATTACK) {
                acquired = cycle;
            }
            if (issued && destArm == null
                    && (grunt.tileX() != startX || grunt.tileY() != startY
                    || grunt.offsetX() != 0 || grunt.offsetY() != 0)) {
                destArm = cycle;
            }
            if (issued && firstHp == null && walker.hitPoints() < startHp) {
                firstHp = cycle;
            }
        }
        assertTrue(issued, "the field click must be issued");
        assertEquals(Unit.Order.STILL, at217,
                "retail is still Still at cycle 217 when the walk first enters react, not "
                        + at217);
        assertEquals(220, acquired,
                "retail acquires Attack on the grunt's Still marker at 220, not "
                        + acquired);
        assertEquals(223, destArm,
                "retail dest-arms the chase at 223, not " + destArm);
        assertEquals(281, firstHp,
                "retail's first blow lands at dest-arm 281, not " + firstHp);
        assertEquals(26, walker.tileX(),
                "retail first hurts the walker on 26,22, not "
                        + walker.tileX() + "," + walker.tileY());
        assertEquals(22, walker.tileY(),
                "retail first hurts the walker on 26,22, not "
                        + walker.tileX() + "," + walker.tileY());
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
