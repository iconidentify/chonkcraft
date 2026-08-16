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
 * A player Attack click on empty ground uses GiveOrder table 8's dest path.
 *
 * <p>Authenticated attack-move-1/00: Orc 1 grunt 1592 stays Still with
 * next_order 10 through fixture 8, order 10 at 9, dest-arms at 12, and
 * settles 22,18. Installing the march on the issue cycle first-progressed
 * at 5. attack-move-1/01's Human 1 soldier is already on the Still marker
 * and dest-arms at 8.
 */
class BattleNetAttackMoveStillQueueRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("an orc 1 grunt stays Still until fixture 9 on a far ground attack")
    void anOrc1GruntStaysStillUntilFixture9OnAFarGroundAttack() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/orc/level01o",
                GameData.personIn(data.campaignMap("campaigns/orc/level01o")), 1);
        Assumptions.assumeTrue(mission != null, "Orc 1 is not in the pack");
        World world = mission.world();
        CommandApplier commands = new CommandApplier(
                world, new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Unit grunt = atTile(world, 18, 23);
        assertNotNull(grunt, "Orc 1 has no soldier on 18,23");
        boolean issued = false;
        Integer promoted = null;
        Integer destArm = null;
        Integer arrived = null;
        Integer settled = null;
        while (fixtureCycle(world) <= 160) {
            if (fixtureCycle(world) == 4 && !issued) {
                assertTrue(commands.apply(GameCommand.attackMove(
                                grunt.player(), grunt.id(), 22, 18)),
                        "GiveOrder 8 dest path must accept the grunt told to attack ground");
                issued = true;
            }
            mission.tick();
            if (issued && promoted == null
                    && grunt.order() == Unit.Order.ATTACK_MOVE) {
                promoted = fixtureCycle(world);
            }
            if (issued && destArm == null
                    && (grunt.tileX() != 18 || grunt.tileY() != 23
                    || grunt.offsetX() != 0 || grunt.offsetY() != 0)) {
                destArm = fixtureCycle(world);
            }
            if (arrived == null && grunt.tileX() == 22 && grunt.tileY() == 18) {
                arrived = fixtureCycle(world);
            }
            if (settled == null && grunt.order() == Unit.Order.STILL
                    && grunt.tileX() == 22 && grunt.tileY() == 18) {
                settled = fixtureCycle(world);
            }
        }
        assertTrue(issued, "the ground attack click must be issued");
        assertEquals(9, promoted,
                "retail keeps Still then dest-attack at fixture 9, not " + promoted);
        assertEquals(12, destArm,
                "retail dest-arms the popped dest-attack at fixture 12, not " + destArm);
        assertEquals(22, grunt.tileX(),
                "retail's grunt settles 22,18, not "
                        + grunt.tileX() + "," + grunt.tileY());
        assertEquals(18, grunt.tileY(),
                "retail's grunt settles 22,18, not "
                        + grunt.tileX() + "," + grunt.tileY());
        assertEquals(76, arrived,
                "retail's dest leftover starts on 22,18 at fixture 76, not " + arrived);
        assertEquals(92, settled,
                "retail Stills dest leftover when its last heading lands at 92, not " + settled);
    }

    @Test
    @DisplayName("a human 1 soldier already on Still marker dest-arms a ground attack at 8")
    void aHuman1SoldierAlreadyOnStillMarkerDestArmsAGroundAttackAt8() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human/level01h",
                GameData.personIn(data.campaignMap("campaigns/human/level01h")), 1);
        Assumptions.assumeTrue(mission != null, "Human 1 is not in the pack");
        World world = mission.world();
        CommandApplier commands = new CommandApplier(
                world, new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Unit soldier = atTile(world, 20, 31);
        assertNotNull(soldier, "Human 1 has no soldier on 20,31");
        boolean issued = false;
        Integer promoted = null;
        Integer destArm = null;
        Integer arrived = null;
        Integer settled = null;
        while (fixtureCycle(world) <= 160) {
            if (fixtureCycle(world) == 4 && !issued) {
                assertTrue(commands.apply(GameCommand.attackMove(
                                soldier.player(), soldier.id(), 24, 31)),
                        "GiveOrder 8 dest path must accept the marker-ready soldier");
                issued = true;
            }
            mission.tick();
            if (issued && promoted == null
                    && soldier.order() == Unit.Order.ATTACK_MOVE) {
                promoted = fixtureCycle(world);
            }
            if (issued && destArm == null
                    && (soldier.tileX() != 20 || soldier.tileY() != 31
                    || soldier.offsetX() != 0 || soldier.offsetY() != 0)) {
                destArm = fixtureCycle(world);
            }
            if (arrived == null && soldier.tileX() == 24 && soldier.tileY() == 31) {
                arrived = fixtureCycle(world);
            }
            if (settled == null && soldier.order() == Unit.Order.STILL
                    && soldier.tileX() == 24 && soldier.tileY() == 31) {
                settled = fixtureCycle(world);
            }
        }
        assertTrue(issued, "the ground attack click must be issued");
        assertEquals(5, promoted,
                "a soldier already on the Still marker installs dest-attack on the issue visit, not "
                        + promoted);
        assertEquals(8, destArm,
                "retail dest-arms that issue-visit dest-attack at fixture 8, not " + destArm);
        assertEquals(24, soldier.tileX(),
                "retail's soldier settles 24,31, not "
                        + soldier.tileX() + "," + soldier.tileY());
        assertEquals(31, soldier.tileY(),
                "retail's soldier settles 24,31, not "
                        + soldier.tileX() + "," + soldier.tileY());
        assertEquals(56, arrived,
                "retail's dest leftover starts on 24,31 at fixture 56, not " + arrived);
        assertEquals(72, settled,
                "retail Stills dest leftover when its last heading lands at 72, not " + settled);
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }

    private static Unit atTile(World world, int x, int y) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.isAlive() && unit.isOnMap() && unit.type() != null
                    && !unit.type().building()
                    && unit.tileX() == x && unit.tileY() == y) {
                return unit;
            }
        }
        return null;
    }
}
