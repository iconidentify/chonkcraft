package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
 * Human 13's dest-arm axe from 118,34 throws on fixture 99.
 *
 * <p>That thrower dest-arms onto 119,33 and names the wise-man on the same
 * visit Attack opens at 887/3. Re-arming construction on a later OP0
 * delayed the axe to fixture 102 and left the live-shot count short at 99.
 */
class Human13Axe1483ConstructRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("human 13's dest-arm axe from 118,34 throws on fixture 99")
    void human13sDestArmAxeFrom11834ThrowsOnFixture99() {
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

        Unit destArm = unitAt(world, "unit-axethrower", 118, 34);
        Unit thrower = unitAt(world, "unit-axethrower", 118, 29);
        Unit quarry = unitAt(world, "unit-knight", 120, 29);
        assertNotNull(destArm, "Human 13 has no dest-arm axe on 118,34");
        assertNotNull(thrower, "Human 13 has no commanded axethrower on 118,29");
        assertNotNull(quarry, "Human 13 has no commanded knight on 120,29");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Integer constructed = null;
        for (int fixture = 1; fixture <= 99; fixture++) {
            if (fixture == 5) {
                applier.apply(GameCommand.attack(
                        thrower.player(), thrower.id(), quarry.id()));
            }
            mission.tick();
            for (Missile missile : world.missiles()) {
                if (missile.source() == destArm
                        && world.battleNetProjectileConstructed(missile)
                        && missile.type() != null
                        && "missile-axe".equals(missile.type().ident())) {
                    constructed = (int) world.savedProjectileStartCycle(missile)
                            - BNE_INITIALIZATION_TICKS;
                }
            }
        }

        assertEquals(119, destArm.tileX(),
                "the dest-arm leftover lands on 119,33");
        assertEquals(33, destArm.tileY(),
                "the dest-arm leftover lands on 119,33");
        assertEquals(99, constructed,
                "native constructs that dest-arm axe at fixture 99; Java "
                        + "used to re-arm construction after a late free-scan "
                        + "and throw at " + constructed);
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
