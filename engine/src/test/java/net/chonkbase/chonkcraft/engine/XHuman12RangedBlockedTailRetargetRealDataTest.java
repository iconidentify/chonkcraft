package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** XHuman 12's ranged chase replaces an exhausted blocked route immediately. */
class XHuman12RangedBlockedTailRetargetRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("xhuman 12 axe spends its replacement route on fixture 54")
    void xhuman12AxeSpendsReplacementRouteOnFixture54() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx12h",
                GameData.personIn(data.campaignMap(
                        "campaigns/human-exp/levelx12h")), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        Unit axe = unitAt(world, "unit-axethrower", 34, 35);
        Unit footman = unitAt(world, "unit-footman", 32, 43);
        assertNotNull(axe, "XHuman 12 has no focus axe on 34,35");
        assertNotNull(footman, "XHuman 12 has no footman on 32,43");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        Unit targetAt53 = null;
        int pathLengthAt53 = -1;
        int xAt54 = -1;
        int yAt54 = -1;
        int delayAt54 = -1;
        while (world.cycle() < 56) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 53) {
                targetAt53 = axe.target();
                pathLengthAt53 = axe.pathLength();
            }
            if (fixture == 54) {
                xAt54 = axe.tileX();
                yAt54 = axe.tileY();
                delayAt54 = axe.battleNetOrderDelay();
            }
        }

        assertNotNull(targetAt53, "the axe must still have its tower quarry");
        assertTrue(targetAt53.type().building(),
                "fixture 53 still names the tower before the free scan");
        assertEquals(1, pathLengthAt53,
                "only the blocked south tail remains at fixture 53");
        assertSame(footman, axe.target(),
                "fixture 54 retargets the footman exactly as native BNE does");
        assertEquals(36, xAt54,
                "the replacement southwest heading is spent on fixture 54");
        assertEquals(37, yAt54,
                "the axe lands on native BNE's 36,37 on fixture 54");
        assertEquals(0, delayAt54,
                "an exhausted blocked tail does not buy a ranged teardown hold");
    }

    @Test
    @DisplayName("xhuman 12 knight takes native melee damage on fixture 132")
    void xhuman12KnightTakesNativeMeleeDamageOnFixture132() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx12h",
                GameData.personIn(data.campaignMap(
                        "campaigns/human-exp/levelx12h")), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        // Java unit 125 pairs with sealed-native slot 1475. Native remains at
        // 90 HP through fixture 131 and the grunt hit lowers it to 87 at 132.
        Unit knight = unitById(world, 125);
        Unit offeredAxe = unitById(world, 76);
        Unit selectedGrunt = unitById(world, 95);
        Unit detourOgre = unitById(world, 102);
        Unit longRouteAxe = unitById(world, 77);
        Unit retainedRouteGrunt = unitById(world, 137);
        assertNotNull(knight, "XHuman 12 has no Java/native-paired knight 125");
        assertNotNull(offeredAxe,
                "XHuman 12 has no Java/native-paired offered axe 76");
        assertNotNull(selectedGrunt,
                "XHuman 12 has no Java/native-paired selected grunt 95");
        assertNotNull(detourOgre,
                "XHuman 12 has no Java/native-paired detour ogre 102");
        assertNotNull(longRouteAxe,
                "XHuman 12 has no Java/native-paired long-route axe 77");
        assertNotNull(retainedRouteGrunt,
                "XHuman 12 has no Java/native-paired retained-route grunt 137");
        assertEquals("unit-knight", knight.type().ident(),
                "the stable fixture ID must still identify the focus knight");
        assertEquals("unit-ogre", detourOgre.type().ident(),
                "the stable fixture ID must still identify the detour ogre");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        int hpAt131 = -1;
        int hpAt132 = -1;
        int hpAt138 = -1;
        int axeSequenceAt124 = -1;
        Unit knightTargetAt84 = null;
        int knightPathLengthAt84 = -1;
        Unit knightTargetAt96 = null;
        int syncSeedAt99 = 0;
        int retainedGruntXAt139 = -1;
        int retainedGruntYAt139 = -1;
        Unit.Order ogreOrderAt133 = null;
        while (world.cycle() < BNE_INITIALIZATION_TICKS + 139) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 124) {
                axeSequenceAt124 = longRouteAxe.battleNetSequenceOffset();
            }
            if (fixture == 84) {
                knightTargetAt84 = knight.target();
                knightPathLengthAt84 = knight.pathLength();
            }
            if (fixture == 96) {
                knightTargetAt96 = knight.target();
            }
            if (fixture == 99) {
                syncSeedAt99 = world.randomSeed();
            }
            if (fixture == 131) {
                hpAt131 = knight.hitPoints();
            }
            if (fixture == 132) {
                hpAt132 = knight.hitPoints();
            }
            if (fixture == 133) {
                ogreOrderAt133 = detourOgre.order();
            }
            if (fixture == 138) {
                hpAt138 = knight.hitPoints();
            }
            if (fixture == 139) {
                retainedGruntXAt139 = retainedRouteGrunt.tileX();
                retainedGruntYAt139 = retainedRouteGrunt.tileY();
            }
        }

        assertEquals(90, hpAt131,
                "the focus knight must be undamaged through fixture 131");
        assertSame(offeredAxe, knightTargetAt84,
                "projectile HitUnit help keeps its source through the first "
                        + "chase residual");
        assertEquals(4, knightPathLengthAt84,
                "fixture 84 retains the axe route behind the spent north byte");
        assertSame(selectedGrunt, knightTargetAt96,
                "the settled residual owns the later AutoSelectTarget scan");
        assertEquals(0xe4880eeb, syncSeedAt99,
                "knight 1475 pays its authenticated melee draw on fixture 99");
        assertEquals(87, hpAt132,
                "native damage draw 12252 deals three on fixture 132");
        assertEquals(Unit.Order.STILL, ogreOrderAt133,
                "a one-heading detour ending beside an occupied Move goal "
                        + "must stand down instead of gliding along stale tail bytes");
        assertEquals(888, axeSequenceAt124,
                "a nearly-full ranged route that lands in range has already "
                        + "paid OP0 and must enter the attack body");
        assertEquals(83, hpAt138,
                "the resulting projectile constructor must preserve BNE's "
                        + "later combat-damage ownership");
        assertEquals(21, retainedGruntXAt139,
                "the first cooperative refusal of a retained route must not "
                        + "insert an extra staged-refill callback");
        assertEquals(58, retainedGruntYAt139);
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
