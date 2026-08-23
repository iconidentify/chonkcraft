package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.missile.Missile;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Test;

/** Native Human 13 cycle-103 combat frontier. */
class Human13Cycle103ParityRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    void rangedCadenceAndCycle103FrontierMatchNative() {
        AssetSource assets = AssetSource.fromEnvironment();
        assumeTrue(assets != null, "BNE asset pack required");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human/level13h", 0, 1);
        assumeTrue(mission != null, "Human 13 must load");
        World world = mission.world();
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        Unit wise = unitAt(world, "unit-wise-man", 123, 28);
        Unit axe = unitAt(world, "unit-axethrower", 113, 29);
        Unit axe1483 = unitAt(world, "unit-axethrower", 118, 34);
        Unit axe1505 = unitAt(world, "unit-axethrower", 125, 24);
        assertTrue(wise != null && axe != null
                        && axe1483 != null && axe1505 != null,
                "the native Human13 combat witnesses must have Java twins");

        Integer axeX103 = null;
        Integer wiseHp103 = null;
        Integer wiseHp104 = null;
        Integer axe1483Hold26 = null;
        Integer axe1505Hold47 = null;
        Integer axe1483Shot = null;
        Integer axe1505Shot = null;
        for (int fixture = 1; fixture <= 114; fixture++) {
            mission.tick();
            if (fixture == 26) {
                axe1483Hold26 = axe1483.battleNetAnimationTimer();
            } else if (fixture == 47) {
                axe1505Hold47 = axe1505.battleNetAnimationTimer();
            } else if (fixture == 103) {
                axeX103 = axe.tileX();
                wiseHp103 = wise.hitPoints();
            } else if (fixture == 104) {
                wiseHp104 = wise.hitPoints();
            }
            for (Missile missile : world.missiles()) {
                if (!world.battleNetProjectileConstructed(missile)) {
                    continue;
                }
                int constructed = (int) world.savedProjectileStartCycle(missile)
                        - BNE_INITIALIZATION_TICKS;
                if (missile.source() == axe1483 && axe1483Shot == null) {
                    axe1483Shot = constructed;
                } else if (missile.source() == axe1505 && axe1505Shot == null) {
                    axe1505Shot = constructed;
                }
            }
        }

        assertEquals(63, axe1483Hold26,
                "native slot 1483 carries its ranged cadence into the hold");
        assertEquals(99, axe1483Shot,
                "native slot 1483 constructs its projectile on fixture 99");
        assertEquals(44, axe1505Hold47,
                "native slot 1505 keeps counting cadence while chasing");
        assertEquals(101, axe1505Shot,
                "native slot 1505 constructs its projectile on fixture 101");
        assertEquals(116, axeX103,
                "native axethrower 1495 first-steps east on fixture 103");
        assertEquals(34, wiseHp103,
                "native wise-man 1496 has 34 hit points on fixture 103");
        assertEquals(31, wiseHp104,
                "native wise-man 1496 has 31 hit points on fixture 104");
    }

    @Test
    void residualOneHeadingWithAFreeApproachKeepsTheCooperativeRefusalBand() {
        AssetSource assets = AssetSource.fromEnvironment();
        assumeTrue(assets != null, "BNE asset pack required");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human/level13h", 0, 1);
        assumeTrue(mission != null, "Human 13 must load");
        World world = mission.world();
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        Unit ogre = unitAt(world, "unit-ogre", 120, 22);
        assertTrue(ogre != null,
                "Human 13 must contain native ogre 1510 / Java 90");
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 120) {
            mission.tick();
        }

        assertEquals(120, ogre.tileX());
        assertEquals(25, ogre.tileY());
        assertEquals(1, ogre.pathLength(),
                "the blocked southeast byte remains cached during the refusal band");
        assertEquals(15, ogre.battleNetOrderDelay(),
                "Java retains the restart visit which the Move cursor itself consumes");
        assertEquals(586, ogre.battleNetSequenceOffset());
        assertEquals(15, ogre.battleNetAnimationTimer());

        mission.tick();
        assertEquals(120, ogre.tileX());
        assertEquals(25, ogre.tileY(),
                "the free south alternative cannot bypass the paid cooperative hold");
        assertEquals(14, ogre.battleNetAnimationTimer());
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
