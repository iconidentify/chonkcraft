package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Human 7 catapult 1519's east ground click constructs at fixture 13.
 */
class Human07AttackGroundRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("human 7's eastern ground click constructs its rock on fixture 13")
    void human7sEasternGroundClickConstructsItsRockOnFixture13() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No Warcraft II installation configured (-Dwc2.install.dir). ");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human/level07h", 0, 1);
        Assumptions.assumeTrue(mission != null, "Human 7 is not in the pack");
        World world = mission.world();
        List<UnitType> roster = List.copyOf(data.unitTypes().types().values());
        CommandApplier applier = new CommandApplier(world, roster);
        data.configureCommands(applier);

        Unit catapult = unitAt(world, "unit-catapult", 9, 65);
        assertNotNull(catapult, "Human 7 has no catapult on 9,65");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        for (int fixture = 1; fixture <= 13; fixture++) {
            if (fixture == 5) {
                assertTrue(applier.apply(GameCommand.attackGround(
                        0, catapult.id(), 13, 65)),
                        "the eastern ground click must be accepted");
            }
            mission.tick();
        }

        Integer constructed = null;
        for (Missile missile : world.missiles()) {
            if ("missile-catapult-rock".equals(missile.type().ident())
                    && missile.source() == catapult
                    && world.savedProjectileStartCycle(missile) >= 0) {
                constructed = (int) world.savedProjectileStartCycle(missile)
                        - BNE_INITIALIZATION_TICKS;
            }
        }
        assertEquals(13, constructed,
                "native constructs at fixture 13 (seq 503@9); Java leftover "
                        + "turn after the Still wait held sequence "
                        + catapult.battleNetSequenceOffset()
                        + " facing " + catapult.direction()
                        + " pending " + catapult.pendingRotation());
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
