package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Human 13's northern ogre dest-arms leftover after its quarry dies.
 *
 * <p>Ogre 1511 arrives on 120,26 chasing knight 1500. That knight goes
 * DYING at fixture 112. Native wraps Attack 666/1 onto 643/3 at 115, names
 * knight 1493, and dest-arms leftover SW,S onto 119,27 at 118. Java used
 * to Still on 120,26, so 1493's wrap at 123 could not see it. After that paid
 * tail reaches a dying wise-man, a new one-heading route to knight 1493 owns a
 * fresh queued Attack constructor when its residual settles at fixture 154.
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

    @Test
    @DisplayName("human 13 retains its paid Attack tail but freshly constructs after its later retarget")
    void human13sIdleOgreRetainsTheNativeFourHeadingAttackTailRoute() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No Warcraft II installation configured (-Dwc2.install.dir). ");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human/level13h", 0, 1);
        Assumptions.assumeTrue(mission != null, "Human 13 is not in the pack");
        World world = mission.world();

        Unit ogre = unitAt(world, "unit-ogre", 125, 22);
        Unit thrower = unitById(world, 114);
        Unit wiseMan = unitById(world, 104);
        Unit southKnight = unitById(world, 107);
        assertNotNull(ogre, "Human 13 has no northern ogre on 125,22");
        assertNotNull(thrower, "Human 13 has no eastern axethrower on 124,33");
        assertNotNull(wiseMan, "Human 13 has no eastern wise man on 122,30");
        assertNotNull(southKnight, "Human 13 has no south knight on 121,30");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        int throwerMissilesBefore = -1;
        int seedAt147 = 0;
        Missile throwerShotAt147 = null;
        for (int fixture = 1; fixture <= 164; fixture++) {
            mission.tick();
            if (fixture == 118) {
                assertEquals(121, ogre.tileX(),
                        "the first native SE heading lands on 121,27");
                assertEquals(27, ogre.tileY(),
                        "the first native SE heading lands on 121,27");
                assertEquals(3, ogre.pathLength(),
                        "native retains S,SE,S after spending the first SE");
            } else if (fixture == 130) {
                assertEquals(121, ogre.tileX(),
                        "the retained south heading lands on 121,28");
                assertEquals(28, ogre.tileY(),
                        "the retained south heading lands on 121,28");
                assertEquals(2, ogre.pathLength(),
                        "native still owns SE,S after the second stride");
            } else if (fixture == 142) {
                assertEquals(Unit.Order.ATTACK, ogre.order(),
                        "retargeting keeps Attack ownership");
                assertEquals(122, ogre.tileX(),
                        "the retained third heading advances on the retarget tick");
                assertEquals(29, ogre.tileY(),
                        "the retained third heading advances on the retarget tick");
                assertSame(southKnight, ogre.target(),
                        "the dying wise-man is replaced by knight 1493");
                assertTrue(ogre.battleNetChaseReplanResidualHold(),
                        "the new one-heading route owns a queued Attack");
                assertTrue(ogre.battleNetAttackWrapDestArmPending(),
                        "the old paid tail remains distinguishable until settlement");
            } else if (fixture == 146) {
                assertEquals(Unit.Order.DYING, wiseMan.order(),
                        "the retained ranged target has entered Die");
                assertSame(wiseMan, thrower.target(),
                        "the committed Attack body keeps its CUnitPtr");
                assertEquals(900, thrower.battleNetSequenceOffset(),
                        "fixture 146 is the wait-one visit before ranged OP10");
                assertEquals(1, thrower.battleNetAnimationTimer(),
                        "ranged OP10 must execute on the next visit");
                assertEquals(0xb4cef525, world.battleNetRandomSeed(),
                        "the authenticated async stream reaches the OP10 boundary");
                throwerMissilesBefore = (int) world.missiles().stream()
                        .filter(missile -> missile.source() == thrower)
                        .count();
            } else if (fixture == 147) {
                seedAt147 = world.battleNetRandomSeed();
                List<Missile> throwerMissiles = world.missiles().stream()
                        .filter(missile -> missile.source() == thrower)
                        .toList();
                assertEquals(throwerMissilesBefore + 1,
                        throwerMissiles.size(),
                        "dying-target OP10 creates the visible eastern axe");
                throwerShotAt147 = throwerMissiles.get(
                        throwerMissiles.size() - 1);
            } else if (fixture >= 154 && fixture <= 156) {
                assertSame(southKnight, ogre.target(),
                        "the replacement Attack belongs to knight 1493");
                assertEquals(643, ogre.battleNetSequenceOffset(),
                        "the fresh queued Attack starts at construction");
                assertEquals(157 - fixture,
                        ogre.battleNetAnimationTimer(),
                        "native exposes construction 3,2,1 on fixtures 154-156");
            } else if (fixture == 157) {
                assertEquals(644, ogre.battleNetSequenceOffset(),
                        "OP0 follows the fresh queued Attack constructor");
                assertEquals(1, ogre.battleNetAnimationTimer(),
                        "OP0 is entered with native timer one");
            } else if (fixture == 162) {
                assertEquals(76, southKnight.hitPoints(),
                        "the retargeted swing has not landed two fixtures early");
            } else if (fixture == 164) {
                assertEquals(68, southKnight.hitPoints(),
                        "native lands the eight-damage blow on fixture 164");
            }
        }

        assertEquals(0x51323ee9, seedAt147,
                "fixture 147 spends damage, two constructor and later callbacks "
                        + "in authenticated BNE order");
        assertNotNull(throwerShotAt147);
        assertSame(wiseMan, throwerShotAt147.target());
        assertEquals(5, throwerShotAt147.damage(),
                "native fixture 147 rolls five damage before constructor jitter");
        assertTrue(throwerShotAt147.battleNetConstructorDrawn());
        assertTrue(throwerShotAt147.battleNetMotion());
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

    private static Unit unitById(World world, int id) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.id() == id) {
                return unit;
            }
        }
        return null;
    }
}
