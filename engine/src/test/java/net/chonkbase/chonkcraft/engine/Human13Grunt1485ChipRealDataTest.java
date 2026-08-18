package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Human 13's leftover grunt first chips the wise-man at fixture 54.
 *
 * <p>The grunt that starts on 122,33 dest-arms leftover onto 122,31, keeps
 * one leftover heading through Attack start 3,2,1, and native first chips
 * 1496 at fixture 54 (84 to 80). Java used to pay the out-of-range replan
 * Attack-four delay after that residual landed in range, then re-arm
 * construction and land the blow at 57.
 */
class Human13Grunt1485ChipRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("human 13's leftover grunt first chips the wise-man at fixture 54")
    void human13sLeftoverGruntFirstChipsTheWiseManAtFixture54() {
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

        Unit grunt = unitAt(world, "unit-grunt", 122, 33);
        Unit wise = unitAt(world, "unit-wise-man", 123, 28);
        Unit thrower = unitAt(world, "unit-axethrower", 118, 29);
        Unit knight = unitAt(world, "unit-knight", 120, 29);
        assertNotNull(grunt, "Human 13 has no leftover grunt on 122,33");
        assertNotNull(wise, "Human 13 has no wise-man on 123,28");
        assertNotNull(thrower, "Human 13 has no commanded axethrower on 118,29");
        assertNotNull(knight, "Human 13 has no commanded knight on 120,29");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Integer firstChip = null;
        int hpAtChip = -1;
        for (int fixture = 1; fixture <= 54; fixture++) {
            if (fixture == 5) {
                applier.apply(GameCommand.attack(
                        thrower.player(), thrower.id(), knight.id()));
            }
            int hpBefore = wise.hitPoints();
            mission.tick();
            if (firstChip == null && wise.hitPoints() < hpBefore
                    && fixture > 38) {
                firstChip = fixture;
                hpAtChip = wise.hitPoints();
            }
            if (fixture == 38) {
                assertEquals(84, wise.hitPoints(),
                        "ogre 1484's first melee is 90 to 84 at fixture 38");
            }
        }

        assertEquals(122, grunt.tileX(),
                "the leftover residual lands the grunt on 122,31");
        assertEquals(31, grunt.tileY(),
                "the leftover residual lands the grunt on 122,31");
        assertEquals(54, firstChip,
                "native first chips the wise-man at fixture 54 after Attack "
                        + "start 3,2,1; Java used to land that blow at "
                        + firstChip);
        assertTrue(hpAtChip < 84,
                "the leftover grunt must chip the wise-man at fixture 54; "
                        + "hp after that blow is " + hpAtChip);
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
