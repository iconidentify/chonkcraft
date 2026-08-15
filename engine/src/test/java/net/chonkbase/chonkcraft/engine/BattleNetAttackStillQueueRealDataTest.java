package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 * A player Attack click from Still queues order 8 when Still wait remains.
 *
 * <p>Authenticated attack-1/00: Orc 1 grunt 1592 is Still with next_order
 * 9 through fixture 8 and Attack at 9, then Attack at 8,18 at 160.
 * Installing Attack on the issue cycle first-progressed at 5; leftover
 * then stole the chase into Attack Ground. attack-1/01 is already on the
 * Still marker and installs Attack at 5.
 */
class BattleNetAttackStillQueueRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("an orc 1 grunt stays Still until fixture 9 on a far attack")
    void anOrc1GruntStaysStillUntilFixture9OnAFarAttack() {
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
        Unit target = atTile(world, 2, 17);
        assertNotNull(grunt, "Orc 1 has no soldier on 18,23");
        assertNotNull(target, "Orc 1 has no enemy on 2,17");
        boolean issued = false;
        Integer promoted = null;
        Integer destArm = null;
        Unit.Order atNinety = null;
        while (fixtureCycle(world) <= 160) {
            if (fixtureCycle(world) == 4 && !issued) {
                assertTrue(commands.apply(GameCommand.attack(
                                grunt.player(), grunt.id(), target.id())),
                        "GiveOrder 8 must accept the grunt told to attack");
                issued = true;
            }
            mission.tick();
            if (issued && promoted == null
                    && grunt.order() == Unit.Order.ATTACK) {
                promoted = fixtureCycle(world);
            }
            if (issued && destArm == null
                    && (grunt.tileX() != 18 || grunt.tileY() != 23
                    || grunt.offsetX() != 0 || grunt.offsetY() != 0)) {
                destArm = fixtureCycle(world);
            }
            if (fixtureCycle(world) == 90) {
                atNinety = grunt.order();
            }
        }
        assertTrue(issued, "the attack click must be issued");
        assertEquals(9, promoted,
                "retail keeps Still then Attack at fixture 9, not " + promoted);
        assertEquals(12, destArm,
                "retail dest-arms the popped Attack at fixture 12, not " + destArm);
        assertEquals(Unit.Order.ATTACK, atNinety,
                "retail is still Attack at fixture 90, not " + atNinety);
        assertNotEquals(Unit.Order.ATTACK_GROUND, grunt.order(),
                "leftover dest-arm must not steal a commanded Attack into Attack Ground");
        assertEquals(Unit.Order.ATTACK, grunt.order(),
                "retail is Attack at 8,18 at the window, not " + grunt.order());
        assertEquals(8, grunt.tileX(),
                "retail's grunt is on 8,18 at the window, not "
                        + grunt.tileX() + "," + grunt.tileY());
        assertEquals(18, grunt.tileY(),
                "retail's grunt is on 8,18 at the window, not "
                        + grunt.tileX() + "," + grunt.tileY());
        assertEquals(23, grunt.offsetX(),
                "retail is Attack 8,18 offset 23 at the window, not "
                        + grunt.offsetX());
    }

    @Test
    @DisplayName("an orc 1 grunt already on Still marker attacks on the issue visit")
    void anOrc1GruntAlreadyOnStillMarkerAttacksOnTheIssueVisit() {
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
        Unit grunt = atTile(world, 20, 27);
        Unit target = atTile(world, 2, 29);
        assertNotNull(grunt, "Orc 1 has no soldier on 20,27");
        assertNotNull(target, "Orc 1 has no enemy on 2,29");
        boolean issued = false;
        Integer promoted = null;
        while (fixtureCycle(world) <= 16) {
            if (fixtureCycle(world) == 4 && !issued) {
                assertTrue(commands.apply(GameCommand.attack(
                                grunt.player(), grunt.id(), target.id())),
                        "GiveOrder 8 must accept the marker-ready grunt");
                issued = true;
            }
            mission.tick();
            if (issued && promoted == null
                    && grunt.order() == Unit.Order.ATTACK) {
                promoted = fixtureCycle(world);
            }
        }
        assertTrue(issued, "the attack click must be issued");
        assertEquals(5, promoted,
                "a grunt already on the Still marker installs Attack on the issue visit, not "
                        + promoted);
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
