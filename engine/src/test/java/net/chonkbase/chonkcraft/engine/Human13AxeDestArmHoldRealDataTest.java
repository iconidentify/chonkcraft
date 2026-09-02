package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.missile.Missile;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Human 13's dest-arm axe does not throw after leftover residual lands.
 *
 * <p>The axethrower that starts on 125,24 dest-arms onto 124,25. Native
 * parks Attack on its start wait after that residual and has no extra axe
 * through fixture 42. Java used to walk into opcode 10 and construct at 38,
 * which is the first ranged causal mismatch after the certified first shot.
 */
class Human13AxeDestArmHoldRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("human 13's dest-arm axe does not throw through fixture 42")
    void human13sDestArmAxeDoesNotThrowThroughFixture42() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No Warcraft II installation configured (-Dwc2.install.dir). ");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human/level13h", 0, 1);
        Assumptions.assumeTrue(mission != null, "Human 13 is not in the pack");
        World world = mission.world();
        List<UnitType> roster = new ArrayList<>(data.unitTypes().types().values());
        CommandApplier applier = new CommandApplier(world, roster);
        data.configureCommands(applier);

        Unit destArm = unitAt(world, "unit-axethrower", 125, 24);
        Unit thrower = unitAt(world, "unit-axethrower", 118, 29);
        Unit quarry = unitAt(world, "unit-knight", 120, 29);
        assertNotNull(destArm, "Human 13 has no dest-arm axe on 125,24");
        assertNotNull(thrower, "Human 13 has no commanded axethrower on 118,29");
        assertNotNull(quarry, "Human 13 has no commanded knight on 120,29");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        for (int fixture = 1; fixture <= 42; fixture++) {
            if (fixture == 5) {
                applier.apply(GameCommand.attack(
                        thrower.player(), thrower.id(), quarry.id()));
            }
            mission.tick();
        }

        assertEquals(124, destArm.tileX(),
                "the dest-arm leftover lands on 124,25, not "
                        + destArm.tileX() + "," + destArm.tileY());
        assertEquals(25, destArm.tileY(),
                "the dest-arm leftover lands on 124,25");

        Integer constructed = null;
        for (Missile missile : world.missiles()) {
            if (missile.source() == destArm
                    && world.battleNetProjectileConstructed(missile)
                    && missile.type() != null
                    && "missile-axe".equals(missile.type().ident())) {
                constructed = (int) world.savedProjectileStartCycle(missile)
                        - BNE_INITIALIZATION_TICKS;
            }
        }
        assertNull(constructed,
                "native never constructs that dest-arm axe through fixture 42; "
                        + "Java used to throw at " + constructed
                        + " and add a third live shot");
    }

    @Test
    @DisplayName("human 13's draining-stride ranged retarget spends its route")
    void human13sDrainingStrideRetargetDoesNotBecomeSettledRecovery() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No Warcraft II installation configured (-Dwc2.install.dir). ");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human/level13h", 0, 1);
        Assumptions.assumeTrue(mission != null, "Human 13 is not in the pack");
        World world = mission.world();

        Unit axe = unitAt(world, "unit-axethrower", 125, 24);
        assertNotNull(axe, "Human 13 has no native-slot-1505 axethrower");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        for (int fixture = 1; fixture <= 28; fixture++) {
            mission.tick();
        }

        assertEquals(123, axe.tileX(),
                "the draining-stride retarget spends its fresh route");
        assertEquals(26, axe.tileY(),
                "the draining-stride retarget does not become a settled "
                        + "recovery constructor");
    }

    private static Unit unitAt(World world, String ident, int x, int y) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.isAlive() && unit.isOnMap() && unit.type() != null
                    && ident.equals(unit.type().ident())
                    && unit.tileX() == x && unit.tileY() == y) {
                return unit;
            }
        }
        return null;
    }
}
