package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * XHuman 9's opening melee after the skeleton's first blow.
 *
 * <p>Authenticated campaign-1800 fixture {@code retail-xhuman-09-idle}:
 * skeleton 1431 starts at 15,118, arrives at 13,120 on cycle 26, and lands
 * its first blow on footman 1427 at cycle 55 (60 to 52). The footman has
 * already been on stationary Attack since the arrival (cycle 27) and
 * stays on Attack at cycle 56 before answering on cycle 57 (skeleton 30 to
 * 24). Separate neighboring-footman reactions make cycle 56 the case's next
 * coarse frontier after this focal exchange.
 */
class Xhuman09DefensiveReactionRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("xhuman 9's struck footman is still attacking on cycle 56")
    void xhuman9sStruckFootmanIsStillAttackingOnCycle56() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx09h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 9 is not in the pack");
        World world = mission.world();

        Unit skeleton = unitAt(world, "unit-skeleton", 15, 118);
        Unit footman = unitAt(world, "unit-footman", 13, 121);
        assertNotNull(skeleton, "XHuman 9 has no skeleton on 15,118");
        assertNotNull(footman, "XHuman 9 has no footman on 13,121");
        int skeletonOpened = skeleton.hitPoints();
        int footmanOpened = footman.hitPoints();

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        Integer skeletonOffsetXAt41 = null;
        Integer skeletonOffsetYAt41 = null;
        Integer skeletonOffsetXAt45 = null;
        Integer skeletonOffsetYAt45 = null;
        Integer skeletonOffsetXAt46 = null;
        Integer skeletonOffsetYAt46 = null;
        Integer footmanHpAt54 = null;
        Integer footmanHpAt55 = null;
        Unit.Order footmanOrderAt56 = null;
        Integer skeletonHpAt57 = null;
        while (fixtureCycle(world) < 57) {
            mission.tick();
            if (fixtureCycle(world) == 41) {
                skeletonOffsetXAt41 = skeleton.offsetX();
                skeletonOffsetYAt41 = skeleton.offsetY();
            }
            if (fixtureCycle(world) == 45) {
                skeletonOffsetXAt45 = skeleton.offsetX();
                skeletonOffsetYAt45 = skeleton.offsetY();
            }
            if (fixtureCycle(world) == 46) {
                skeletonOffsetXAt46 = skeleton.offsetX();
                skeletonOffsetYAt46 = skeleton.offsetY();
            }
            if (fixtureCycle(world) == 54) {
                footmanHpAt54 = footman.hitPoints();
            }
            if (fixtureCycle(world) == 55) {
                footmanHpAt55 = footman.hitPoints();
            }
            if (fixtureCycle(world) == 56) {
                footmanOrderAt56 = footman.order();
            }
            if (fixtureCycle(world) == 57) {
                skeletonHpAt57 = skeleton.hitPoints();
            }
        }

        assertEquals(7, skeletonOffsetXAt41,
                "script.bin leaves seven horizontal walk pixels on cycle 41");
        assertEquals(-7, skeletonOffsetYAt41,
                "the south-west chase still owes seven vertical pixels on cycle 41");
        assertEquals(2, skeletonOffsetXAt45,
                "the skeleton still owes two horizontal pixels on cycle 45");
        assertEquals(-2, skeletonOffsetYAt45,
                "the skeleton still owes two vertical pixels on cycle 45");
        assertEquals(0, skeletonOffsetXAt46,
                "the native Move body reaches the tile anchor on cycle 46");
        assertEquals(0, skeletonOffsetYAt46,
                "the native Move body reaches the tile anchor on cycle 46");
        assertEquals(footmanOpened, footmanHpAt54,
                "the skeleton must finish its borrowed walk pixels before attacking; "
                        + "retail leaves the footman untouched through cycle 54");
        assertEquals(52, footmanHpAt55,
                "retail's skeleton first blow lands on cycle 55");
        assertEquals(Unit.Order.ATTACK, footmanOrderAt56,
                "retail's struck footman stays on Attack at cycle 56, not "
                        + footmanOrderAt56);
        assertTrue(skeletonHpAt57 != null && skeletonHpAt57 < skeletonOpened,
                "retail's footman answers on cycle 57; the skeleton is still at "
                        + skeletonHpAt57 + " of " + skeletonOpened);
        assertEquals(13, skeleton.tileX(),
                "the skeleton must still stand on 13,120 after the first exchange");
        assertEquals(120, skeleton.tileY(),
                "the skeleton must still stand on 13,120 after the first exchange");
        assertEquals(13, footman.tileX(),
                "the footman must still stand on 13,121 after the first exchange");
        assertEquals(121, footman.tileY(),
                "the footman must still stand on 13,121 after the first exchange");
    }

    @Test
    @DisplayName("xhuman 9's Data-marked melee neighbors wake and pursue on cycle 56")
    void xhuman9sDataMarkedMeleeNeighborsWakeAndPursueOnCycle56() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx09h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 9 is not in the pack");
        World world = mission.world();

        Unit skeleton = unitAt(world, "unit-skeleton", 15, 118);
        Unit secondSkeleton = unitAt(world, "unit-skeleton", 17, 119);
        Unit eastFootman = unitAt(world, "unit-footman", 13, 123);
        Unit westFootman = unitAt(world, "unit-footman", 12, 122);
        Unit northKnight = unitAt(world, "unit-knight", 16, 125);
        assertNotNull(skeleton, "XHuman 9 has no skeleton on 15,118");
        assertNotNull(secondSkeleton, "XHuman 9 has no skeleton on 17,119");
        assertNotNull(eastFootman, "XHuman 9 has no footman on 13,123");
        assertNotNull(westFootman, "XHuman 9 has no footman on 12,122");
        assertNotNull(northKnight, "XHuman 9 has no knight on 16,125");
        assertTrue(eastFootman.battleNetPudData() != 0,
                "the east witness must retain its native UNIT.Data marker");
        assertTrue(westFootman.battleNetPudData() != 0,
                "the west witness must retain its native UNIT.Data marker");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        Unit.Order eastOrderAt55 = null;
        Unit.Order westOrderAt55 = null;
        Unit.Order eastOrderAt56 = null;
        Unit.Order westOrderAt56 = null;
        Integer eastXAt58 = null;
        Integer eastYAt58 = null;
        Integer westXAt58 = null;
        Integer westYAt58 = null;
        Unit.Order knightOrderAt58 = null;
        Unit.Order knightOrderAt59 = null;
        while (fixtureCycle(world) < 59) {
            mission.tick();
            if (fixtureCycle(world) == 55) {
                eastOrderAt55 = eastFootman.currentAction();
                westOrderAt55 = westFootman.currentAction();
            }
            if (fixtureCycle(world) == 56) {
                eastOrderAt56 = eastFootman.currentAction();
                westOrderAt56 = westFootman.currentAction();
            }
            if (fixtureCycle(world) == 58) {
                eastXAt58 = eastFootman.tileX();
                eastYAt58 = eastFootman.tileY();
                westXAt58 = westFootman.tileX();
                westYAt58 = westFootman.tileY();
                knightOrderAt58 = northKnight.currentAction();
            }
            if (fixtureCycle(world) == 59) {
                knightOrderAt59 = northKnight.currentAction();
            }
        }

        assertEquals(Unit.Order.STILL, eastOrderAt55,
                "retail exposes the east footman's queued acquisition as Still at cycle 55");
        assertEquals(Unit.Order.STILL, westOrderAt55,
                "retail exposes the west footman's queued acquisition as Still at cycle 55");
        assertEquals(Unit.Order.ATTACK, eastOrderAt56,
                "the east Data-marked melee defender must wake on cycle 56");
        assertEquals(Unit.Order.ATTACK, westOrderAt56,
                "the west Data-marked melee defender must wake on cycle 56");
        assertEquals(secondSkeleton, eastFootman.target(),
                "the east defender accepts the better second close-hit offer at cycle 59");
        assertEquals(skeleton, westFootman.target(),
                "the west defender retains the closer first skeleton");
        assertEquals(13, eastXAt58,
                "the east defender holds its native tile through cycle 58");
        assertEquals(123, eastYAt58,
                "the east defender holds its native tile through cycle 58");
        assertEquals(12, westXAt58,
                "the west defender holds its native tile through cycle 58");
        assertEquals(122, westYAt58,
                "the west defender holds its native tile through cycle 58");
        assertEquals(13, eastFootman.tileX(),
                "the east defender keeps its column on the first chase step");
        assertEquals(122, eastFootman.tileY(),
                "the east defender steps north on native cycle 59");
        assertEquals(12, westFootman.tileX(),
                "the west defender keeps its column on the first chase step");
        assertEquals(121, westFootman.tileY(),
                "the west defender steps north on native cycle 59");
        assertEquals(Unit.Order.STILL, knightOrderAt58,
                "the nearby knight's timer-two next order remains hidden through cycle 58");
        assertEquals(Unit.Order.ATTACK, knightOrderAt59,
                "the nearby knight promotes only on its own cycle-59 idle boundary");
    }

    @Test
    @DisplayName("xhuman 9 attributes consecutive arrival draws to knight then footman")
    void xhuman9AttributesConsecutiveArrivalDrawsToKnightThenFootman() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx09h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 9 is not in the pack");
        World world = mission.world();

        Unit footman = unitAt(world, "unit-footman", 12, 122);
        Unit knight = unitAt(world, "unit-knight", 16, 125);
        assertNotNull(footman, "XHuman 9 has no footman on 12,122");
        assertNotNull(knight, "XHuman 9 has no knight on 16,125");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        Integer seedAt73 = null;
        Integer seedAt74 = null;
        Integer seedAt75 = null;
        Integer offsetYAt74 = null;
        Integer offsetYAt75 = null;
        Integer sequenceAt74 = null;
        Integer footmanSequenceAt75 = null;
        Integer knightSequenceAt74 = null;
        Boolean footmanPendingAt74 = null;
        Boolean footmanPendingAt75 = null;
        Boolean knightPendingAt74 = null;
        while (fixtureCycle(world) < 75) {
            mission.tick();
            if (fixtureCycle(world) == 73) {
                seedAt73 = world.randomSeed();
            }
            if (fixtureCycle(world) == 74) {
                seedAt74 = world.randomSeed();
                offsetYAt74 = footman.offsetY();
                sequenceAt74 = footman.battleNetSequenceOffset();
                knightSequenceAt74 = knight.battleNetSequenceOffset();
                footmanPendingAt74 = footman.battleNetPendingMeleeSyncRand();
                knightPendingAt74 = knight.battleNetPendingMeleeSyncRand();
            }
            if (fixtureCycle(world) == 75) {
                seedAt75 = world.randomSeed();
                offsetYAt75 = footman.offsetY();
                footmanSequenceAt75 = footman.battleNetSequenceOffset();
                footmanPendingAt75 = footman.battleNetPendingMeleeSyncRand();
            }
        }

        assertEquals(0xbf54bc7e, seedAt73,
                "the authenticated sync stream is unchanged before the tail debit");
        assertEquals(0x0ff6d5df, seedAt74,
                "knight 1414's direct residual landing owns fixture-74 FUN_004234b0");
        assertEquals(0x0abd322c, seedAt75,
                "footman 1423 owns the next draw when its residual settles");
        assertEquals(2, offsetYAt74,
                "the footman still owes two pixels during the knight's draw");
        assertEquals(0, offsetYAt75,
                "the footman reaches its tile anchor on fixture 75");
        assertEquals(2534, sequenceAt74,
                "the footman remains on Move while the knight lands");
        assertEquals(1923, knightSequenceAt74,
                "the direct knight arrival opens Attack past OP0");
        assertEquals(true, footmanPendingAt74,
                "the footman's draw remains pending through fixture 74");
        assertEquals(false, knightPendingAt74,
                "the knight consumes its first draw on arrival");
        assertEquals(2540, footmanSequenceAt75,
                "the footman opens Attack past OP0 on its landing visit");
        assertEquals(false, footmanPendingAt75,
                "the footman's pending draw clears on fixture 75");
    }

    @Test
    @DisplayName("xhuman 9 queues a better close-hit target behind the live chase residual")
    void xhuman9QueuesBetterCloseHitTargetBehindLiveChaseResidual() {
        // Authenticated native slot 1420:
        //   f56 Attack toward skeleton 1431 (13,120);
        //   f59 accepts the later close-hit offer for skeleton 1430 (15,121),
        //       first-steps N and retains NE as route index one;
        //   f75-f77 promotes the queued Attack as construction 3,2,1 while
        //       keeping that residual route;
        //   f78 free-scans back to 1431, drops the stale face, and first-steps
        //       W from a fresh W,NW,NE route.
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx09h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 9 is not in the pack");
        World world = mission.world();

        Unit firstSkeleton = unitAt(world, "unit-skeleton", 15, 118);
        Unit offeredSkeleton = unitAt(world, "unit-skeleton", 17, 119);
        Unit footman = unitAt(world, "unit-footman", 13, 123);
        assertNotNull(firstSkeleton, "XHuman 9 has no skeleton 1431 witness");
        assertNotNull(offeredSkeleton, "XHuman 9 has no skeleton 1430 witness");
        assertNotNull(footman, "XHuman 9 has no footman 1420 witness");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        Unit targetAt56 = null;
        Unit targetAt59 = null;
        Unit targetAt75 = null;
        Unit targetAt78 = null;
        Integer xAt59 = null;
        Integer yAt59 = null;
        Integer xAt75 = null;
        Integer xAt76 = null;
        Integer xAt77 = null;
        Integer xAt78 = null;
        Integer yAt78 = null;
        Integer sequenceAt75 = null;
        Integer timerAt75 = null;
        while (fixtureCycle(world) < 78) {
            mission.tick();
            int fixture = fixtureCycle(world);
            if (fixture == 56) {
                targetAt56 = footman.target();
            } else if (fixture == 59) {
                targetAt59 = footman.target();
                xAt59 = footman.tileX();
                yAt59 = footman.tileY();
            } else if (fixture == 75) {
                targetAt75 = footman.target();
                xAt75 = footman.tileX();
                sequenceAt75 = footman.battleNetSequenceOffset();
                timerAt75 = footman.battleNetAnimationTimer();
            } else if (fixture == 76) {
                xAt76 = footman.tileX();
            } else if (fixture == 77) {
                xAt77 = footman.tileX();
            } else if (fixture == 78) {
                targetAt78 = footman.target();
                xAt78 = footman.tileX();
                yAt78 = footman.tileY();
            }
        }

        assertEquals(firstSkeleton, targetAt56,
                "the first recruited Attack owns skeleton 1431");
        assertEquals(offeredSkeleton, targetAt59,
                "the better second hit offer promotes at the action callback");
        assertEquals(13, xAt59, "the callback first-steps north, not sideways");
        assertEquals(122, yAt59, "the callback owns native's north step");
        assertEquals(offeredSkeleton, targetAt75,
                "the offered quarry remains current through residual settlement");
        assertEquals(13, xAt75, "the residual settles before construction");
        assertEquals(2539, sequenceAt75,
                "the queued Attack promotes at footman Attack-start");
        assertEquals(3, timerAt75,
                "the promotion exposes native construction timer three");
        assertEquals(13, xAt76, "timer two retains the landing square");
        assertEquals(13, xAt77, "timer one retains the landing square");
        assertEquals(firstSkeleton, targetAt78,
                "timer-one target selection returns to skeleton 1431");
        assertEquals(12, xAt78,
                "the fresh route consumes west rather than stale north-east");
        assertEquals(122, yAt78,
                "the replacement route stays on the native row");
    }

    @Test
    @DisplayName("xhuman 9's knight retains Attack through a dying quarry's tail")
    void xhuman9KnightKeepsTheTerminalAttackVisitAfterItsQuarryDies() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx09h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 9 is not in the pack");
        World world = mission.world();

        Unit knight = unitAt(world, "unit-knight", 15, 124);
        Unit skeleton = unitAt(world, "unit-skeleton", 18, 121);
        assertNotNull(knight, "XHuman 9 has no knight on 15,124");
        assertNotNull(skeleton, "XHuman 9 has no skeleton on 18,121");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (fixtureCycle(world) < 108) {
            mission.tick();
        }
        assertEquals(Unit.Order.DYING, skeleton.order(),
                "the quarry must already be in its death program");
        assertEquals(Unit.Order.ATTACK, knight.order(),
                "native retains Attack for the terminal timer-one visit");
        assertEquals(1945, knight.battleNetSequenceOffset(),
                "the knight remains on its authenticated Attack tail");
        assertEquals(1, knight.battleNetAnimationTimer(),
                "the tail reaches timer one before OP0 may end the order");

        mission.tick();
        assertEquals(109, fixtureCycle(world));
        assertEquals(Unit.Order.STILL, knight.order(),
                "the following OP0 visit may finally restore Still");
    }

    @Test
    @DisplayName("xhuman 9 drops a dead quarry on script.bin's tail boundary")
    void xhuman9DropsADeadQuarryOnTheScriptTailBoundary() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx09h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 9 is not in the pack");
        World world = mission.world();

        // Native slot 1423 / Java 177 has already landed its last blow and its
        // skeleton quarry is dying. Retail keeps Attack through fixture 124,
        // then script.bin reaches the OP0 validity boundary and changes the
        // order to Still on fixture 125. Java's presentation animation remains
        // unbreakable for another visit, but it must not pin order execution to
        // a corpse behind that renderer-only flag.
        Unit footman = unitAt(world, "unit-footman", 12, 122);
        Unit skeleton = unitAt(world, "unit-skeleton", 15, 118);
        assertNotNull(footman, "XHuman 9 has no native-slot-1423 footman");
        assertNotNull(skeleton, "XHuman 9 has no native-slot-1431 skeleton");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 125) {
            mission.tick();
            if (fixtureCycle(world) == 124) {
                assertEquals(Unit.Order.ATTACK, footman.order(),
                        "the committed attack tail survives through fixture 124");
                assertEquals(Unit.Order.DYING, skeleton.order(),
                        "the quarry is already in its death program");
            }
        }

        assertEquals(Unit.Order.STILL, footman.order(),
                "script.bin validates the dead goal on fixture 125");
    }

    @Test
    @DisplayName("xhuman 9 drops a dying quarry when its chase residual settles")
    void xhuman9DropsADyingQuarryWhenItsChaseResidualSettles() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx09h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 9 is not in the pack");
        World world = mission.world();

        // Native slot 1414 / Java 186 commits its north step while skeleton
        // 1430 is still live. The skeleton begins dying on fixture 119, but
        // MoveToTarget must finish the already-borrowed pixels through 125.
        // When those final three pixels land on fixture 126, retail clears the
        // expired goal, parks route index 20 and installs Still 1869/3. Java
        // used to interpret that drain as an unconditional PF_REACHED and
        // opened a complete new Attack program against the corpse.
        Unit knight = unitAt(world, "unit-knight", 16, 125);
        Unit skeleton = unitAt(world, "unit-skeleton", 17, 119);
        assertNotNull(knight, "XHuman 9 has no native-slot-1414 knight");
        assertNotNull(skeleton, "XHuman 9 has no native-slot-1430 skeleton");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 126) {
            mission.tick();
            if (fixtureCycle(world) == 125) {
                assertEquals(Unit.Order.DYING, skeleton.order(),
                        "the quarry has been dying since fixture 119");
                assertEquals(Unit.Order.ATTACK, knight.order(),
                        "the committed residual retains Attack through fixture 125");
                assertEquals(skeleton, knight.target(),
                        "the residual still owns its expired quarry before landing");
                assertTrue(knight.isMoving(),
                        "native still owes three north pixels on fixture 125");
                assertEquals(3, knight.offsetY(),
                        "fixture 125 must expose the final three residual pixels");
            }
        }

        assertEquals(Unit.Order.STILL, knight.order(),
                "the settled residual must not open another corpse attack");
        assertNull(knight.target(),
                "EndActionAttack releases the expired CUnitPtr on fixture 126");
        assertTrue(!knight.isMoving(),
                "fixture 126 reaches the native pixel anchor");
        assertEquals(15, knight.tileX());
        assertEquals(122, knight.tileY());
        assertEquals(0, knight.offsetY());
        assertEquals(1869, knight.battleNetSequenceOffset(),
                "retail installs the knight Still constructor");
        assertEquals(3, knight.battleNetAnimationTimer(),
                "the Still constructor begins at timer three");
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
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
