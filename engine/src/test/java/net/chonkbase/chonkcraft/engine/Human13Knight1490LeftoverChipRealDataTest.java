package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
 * Human 13's dest-arm knight first chips the dest-arm ogre at fixture 54.
 *
 * <p>Knight 1490 dest-arms leftover SE,S onto 125,31. Native keeps one
 * leftover heading through Attack start 1922/3,2,1 and first chips ogre
 * 1482 at fixture 54. A two-heading leftover residual-opened past OP0
 * and landed that blow at 51.
 */
class Human13Knight1490LeftoverChipRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("human 13's dest-arm knight first chips the dest-arm ogre at fixture 54")
    void human13sDestArmKnightFirstChipsTheDestArmOgreAtFixture54() {
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

        Unit knight = unitAt(world, "unit-knight", 124, 30);
        Unit ogre = unitAt(world, "unit-ogre", 126, 34);
        Unit thrower = unitAt(world, "unit-axethrower", 118, 29);
        Unit quarry = unitAt(world, "unit-knight", 120, 29);
        assertNotNull(knight, "Human 13 has no dest-arm knight on 124,30");
        assertNotNull(ogre, "Human 13 has no dest-arm ogre on 126,34");
        assertNotNull(thrower, "Human 13 has no commanded axethrower on 118,29");
        assertNotNull(quarry, "Human 13 has no commanded knight on 120,29");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Integer firstChip = null;
        for (int fixture = 1; fixture <= 54; fixture++) {
            if (fixture == 5) {
                applier.apply(GameCommand.attack(
                        thrower.player(), thrower.id(), quarry.id()));
            }
            int hpBefore = ogre.hitPoints();
            mission.tick();
            if (fixture == 29) {
                assertEquals(1, knight.pathLength(),
                        "dest-arm leftover remaining is one heading at 29");
            }
            if (fixture == 41) {
                assertEquals(1, knight.pathLength(),
                        "the leftover heading stays through Attack start 3");
            }
            if (firstChip == null && ogre.hitPoints() < hpBefore) {
                firstChip = fixture;
            }
        }

        assertEquals(125, knight.tileX(),
                "the dest-arm leftover lands the knight on 125,31");
        assertEquals(31, knight.tileY(),
                "the dest-arm leftover lands the knight on 125,31");
        assertEquals(54, firstChip,
                "native first chips that ogre at fixture 54 after Attack "
                        + "start 3,2,1; Java used to residual-open past OP0 "
                        + "and land the blow at " + firstChip);
        assertEquals(84, ogre.hitPoints(),
                "native's dest-arm leftover melee is 90 to 84 at fixture 54");
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
