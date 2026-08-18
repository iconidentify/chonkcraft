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
 * Human 13's dest-arm ogre does not land its first melee at fixture 53.
 *
 * <p>The ogre that starts on 115,30 dest-arms leftover toward the north
 * knight, lands on 118,27, and free-scans onto the knight now on 119,28.
 * Native parks Attack on its start wait (body wait 23) and first chips
 * that knight at fixture 76. Java used to walk into opcode 10 at 53.
 */
class Human13OgreDestArmHoldRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("human 13's dest-arm ogre first chips the landed knight at fixture 76")
    void human13sDestArmOgreFirstChipsTheLandedKnightAtFixture76() {
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

        Unit ogre = unitAt(world, "unit-ogre", 115, 30);
        Unit knight = unitAt(world, "unit-knight", 120, 29);
        Unit thrower = unitAt(world, "unit-axethrower", 118, 29);
        assertNotNull(ogre, "Human 13 has no dest-arm ogre on 115,30");
        assertNotNull(knight, "Human 13 has no knight on 120,29");
        assertNotNull(thrower, "Human 13 has no commanded axethrower on 118,29");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Integer firstChip = null;
        for (int fixture = 1; fixture <= 76; fixture++) {
            if (fixture == 5) {
                applier.apply(GameCommand.attack(
                        thrower.player(), thrower.id(), knight.id()));
            }
            int hpBefore = knight.hitPoints();
            mission.tick();
            if (firstChip == null && knight.hitPoints() < hpBefore
                    && fixture > 25) {
                firstChip = fixture;
            }
            if (fixture == 53) {
                assertEquals(87, knight.hitPoints(),
                        "native still has 87 on the dest-arm leftover land "
                                + "through fixture 53; Java used to land the "
                                + "ogre's first melee there");
            }
        }

        assertEquals(118, ogre.tileX(),
                "the dest-arm leftover lands the ogre on 118,27");
        assertEquals(27, ogre.tileY(),
                "the dest-arm leftover lands the ogre on 118,27");
        assertEquals(76, firstChip,
                "native first chips that knight at fixture 76 after the "
                        + "Attack start body hold, not " + firstChip);
        assertEquals(81, knight.hitPoints(),
                "native's first dest-arm melee is 87 to 81 at fixture 76");
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
