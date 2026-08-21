package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.IdentityHashMap;
import java.util.Map;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.missile.Missile;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Locks XHuman 4's opening blocked chase and projectile combat cluster. */
class Xhuman04OpeningCombatParityRealDataTest {

    private static final int INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("xhuman 4's blocked attackers keep native combat cadence")
    void xhuman4BlockedAttackersKeepNativeCombatCadence() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission(
                "campaigns/human-exp/levelx04h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 4 is not in the pack");
        World world = mission.world();

        Unit southAxe = unitAt(world, "unit-axethrower", 78, 61);
        Unit middleAxe = unitAt(world, "unit-axethrower", 78, 60);
        Unit northAxe = unitAt(world, "unit-axethrower", 78, 59);
        Unit blockedGrunt = unitAt(world, "unit-grunt", 77, 61);
        Unit defender = unitAt(world, "unit-footman", 72, 60);
        Unit adjacentGrunt = unitAt(world, "unit-grunt", 77, 60);
        Unit mineApproachPeon = unitAt(world, "unit-peon", 116, 14);
        Unit residualRetargetFootman = unitAt(world, "unit-footman", 71, 62);
        assertNotNull(southAxe);
        assertNotNull(middleAxe);
        assertNotNull(northAxe);
        assertNotNull(blockedGrunt);
        assertNotNull(defender);
        assertNotNull(adjacentGrunt);
        assertNotNull(mineApproachPeon);
        assertNotNull(residualRetargetFootman);

        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        Map<Unit, Integer> axeCreation = new IdentityHashMap<>();
        Integer gruntTimer45 = null;
        Integer gruntTimer48 = null;
        Integer gruntTimer51 = null;
        Integer gruntY54 = null;
        Boolean gruntMoving54 = null;
        Integer defenderTimer60 = null;
        Integer adjacentHp68 = null;
        Integer adjacentHp70 = null;
        Integer mineApproachX72 = null;
        Integer mineApproachY72 = null;
        Integer syncSeed82 = null;
        Integer syncSeed83 = null;
        Integer syncSeed85 = null;
        Integer retargetFootmanX83 = null;
        Integer retargetFootmanY83 = null;
        Integer retargetFootmanX85 = null;
        Integer retargetFootmanY85 = null;
        Integer retargetFootmanX86 = null;
        Integer retargetFootmanY86 = null;
        Boolean retargetFootmanMoving86 = null;
        for (int fixture = 1; fixture <= 86; fixture++) {
            mission.tick();
            for (Missile missile : world.missiles()) {
                if (missile.source() != null
                        && world.battleNetProjectileConstructed(missile)
                        && missile.type() != null
                        && "missile-axe".equals(missile.type().ident())) {
                    axeCreation.putIfAbsent(missile.source(),
                            (int) world.savedProjectileStartCycle(missile)
                                    - INITIALIZATION_TICKS);
                }
            }
            if (fixture == 45) {
                gruntTimer45 = blockedGrunt.battleNetAnimationTimer();
            } else if (fixture == 48) {
                gruntTimer48 = blockedGrunt.battleNetAnimationTimer();
            } else if (fixture == 51) {
                gruntTimer51 = blockedGrunt.battleNetAnimationTimer();
            } else if (fixture == 54) {
                gruntY54 = blockedGrunt.tileY();
                gruntMoving54 = blockedGrunt.isMoving();
            } else if (fixture == 60) {
                defenderTimer60 = defender.battleNetAnimationTimer();
            } else if (fixture == 68) {
                adjacentHp68 = adjacentGrunt.hitPoints();
            } else if (fixture == 70) {
                adjacentHp70 = adjacentGrunt.hitPoints();
            } else if (fixture == 72) {
                mineApproachX72 = mineApproachPeon.tileX();
                mineApproachY72 = mineApproachPeon.tileY();
            }
            if (fixture == 83) {
                retargetFootmanX83 = residualRetargetFootman.tileX();
                retargetFootmanY83 = residualRetargetFootman.tileY();
            } else if (fixture == 85) {
                retargetFootmanX85 = residualRetargetFootman.tileX();
                retargetFootmanY85 = residualRetargetFootman.tileY();
            } else if (fixture == 86) {
                retargetFootmanX86 = residualRetargetFootman.tileX();
                retargetFootmanY86 = residualRetargetFootman.tileY();
                retargetFootmanMoving86 = residualRetargetFootman.isMoving();
            }
            if (fixture == 82) {
                syncSeed82 = world.randomSeed();
            } else if (fixture == 83) {
                syncSeed83 = world.randomSeed();
            } else if (fixture == 85) {
                syncSeed85 = world.randomSeed();
            }
        }

        assertEquals(48, axeCreation.get(southAxe),
                "the south axethrower must not freeze before its first axe");
        assertEquals(65, axeCreation.get(middleAxe),
                "the one-heading residual must open past OP0 and throw at 65");
        assertEquals(51, axeCreation.get(northAxe),
                "the north axethrower must not freeze before its first axe");
        assertEquals(3, gruntTimer45,
                "the full refusal band wakes into Attack construction");
        assertEquals(3, gruntTimer48,
                "a blocked retry re-arms Attack without another long sleep");
        assertEquals(3, gruntTimer51,
                "the second blocked retry keeps the three-cycle cadence");
        assertEquals(60, gruntY54,
                "the north square frees and the blocked grunt resumes moving");
        assertEquals(Boolean.TRUE, gruntMoving54,
                "the accepted fixture-54 retry must own a live walk");
        assertEquals(23, defenderTimer60,
                "the delayed melee retarget must enter the native OP0 hold");
        assertEquals(51, adjacentHp68,
                "the defender must not land Java's former early fixture-68 hit");
        assertEquals(36, adjacentHp70,
                "the other native attacks still land through fixture 70");
        assertEquals(120, mineApproachX72,
                "the mine approach detour must reach native's north skirt");
        assertEquals(12, mineApproachY72,
                "the moving gold sibling stays solid, preserving NE,NE,SE");
        assertEquals(0xf94bdf32, syncSeed82,
                "the residual retarget must not spend SyncRand before Attack 3,2,1");
        assertEquals(0xd9e2b600, syncSeed83,
                "the two standing melee loops retain native fixture-83 cadence");
        assertEquals(0xbf54bc7e, syncSeed85,
                "the residual retarget spends at OP0 alongside the native loop");
        assertEquals(72, retargetFootmanX83,
                "the settled footman keeps its old route during Attack construction");
        assertEquals(63, retargetFootmanY83,
                "the settled footman must not take Java's former early NE step");
        assertEquals(72, retargetFootmanX85,
                "Attack construction 3,2,1 owns fixtures 83 through 85");
        assertEquals(63, retargetFootmanY85,
                "the retarget handoff retains the settled square through timer one");
        assertEquals(73, retargetFootmanX86,
                "the completed handoff first-steps NE on native fixture 86");
        assertEquals(62, retargetFootmanY86,
                "the replacement route must begin with native's NE heading");
        assertEquals(Boolean.TRUE, retargetFootmanMoving86,
                "fixture 86 must own the live residual of the accepted NE step");
        assertTrue(defender.battleNetMeleeSyncRemaining() > 0,
                "the delayed target handoff must retain its melee RNG cadence");
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
