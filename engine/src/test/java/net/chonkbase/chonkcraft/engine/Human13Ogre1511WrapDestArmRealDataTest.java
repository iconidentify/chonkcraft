package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
 * Human 13's northern ogre dest-arms leftover after its quarry dies.
 *
 * <p>Ogre 1511 arrives on 120,26 chasing knight 1500. That knight goes
 * DYING at fixture 112. Native wraps Attack 666/1 onto 643/3 at 115, names
 * knight 1493, and dest-arms leftover SW,S onto 119,27 at 118. Java used
 * to Still on 120,26, so 1493's wrap at 123 could not see it.
 */
class Human13Ogre1511WrapDestArmRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("human 13's northern ogre dest-arms leftover onto 119,27 after the north knight dies")
    void human13sNorthernOgreDestArmsLeftoverOnto11927AfterTheNorthKnightDies() {
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

        Unit ogre = unitAt(world, "unit-ogre", 125, 22);
        Unit north = unitAt(world, "unit-knight", 120, 26);
        Unit south = unitAt(world, "unit-knight", 120, 29);
        Unit thrower = unitAt(world, "unit-axethrower", 118, 29);
        assertNotNull(ogre, "Human 13 has no northern ogre on 125,22");
        assertNotNull(north, "Human 13 has no north knight on 120,26");
        assertNotNull(south, "Human 13 has no south knight on 120,29");
        assertNotNull(thrower, "Human 13 has no commanded axethrower on 118,29");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        for (int fixture = 1; fixture <= 118; fixture++) {
            if (fixture == 5) {
                applier.apply(GameCommand.attack(
                        thrower.player(), thrower.id(), south.id()));
            }
            mission.tick();
            if (fixture == 115) {
                assertEquals(Unit.Order.ATTACK, ogre.order(),
                        "native wraps Attack after the north knight dies, "
                                + "not Still");
                assertSame(south, ogre.target(),
                        "the wrap names the south knight on Attack@643/3");
                assertEquals(120, ogre.tileX(),
                        "construction 3 still stands on 120,26");
                assertEquals(26, ogre.tileY(),
                        "construction 3 still stands on 120,26");
                assertEquals(643, ogre.battleNetSequenceOffset(),
                        "native wrap restarts Attack construction at 643");
                assertEquals(3, ogre.battleNetAnimationTimer(),
                        "native wrap restarts Attack construction timer 3");
            }
        }

        assertEquals(119, ogre.tileX(),
                "the dest-arm leftover lands the ogre on 119,27");
        assertEquals(27, ogre.tileY(),
                "the dest-arm leftover lands the ogre on 119,27");
        assertEquals(1, ogre.pathLength(),
                "native dest-arm leftover is SW,S with SW already spent");
        assertSame(south, ogre.target(),
                "the dest-arm leftover still belongs to the south knight");
        assertTrue(north.isDying(),
                "the north knight is already DYING when leftover dest-arms");
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
