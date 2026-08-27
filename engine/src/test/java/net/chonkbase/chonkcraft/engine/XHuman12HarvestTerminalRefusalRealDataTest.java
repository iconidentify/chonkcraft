package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.animation.BattleNetSequence;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Locks terrain-harvest terminal-refusal recovery to sealed XHuman 12. */
class XHuman12HarvestTerminalRefusalRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("a blocked terminal wood byte pays construction then advances")
    void blockedTerminalWoodByteAdvancesAfterConstruction() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit peon = unitById(world, 236);
        Unit paidPrefixPeon = unitById(world, 240);
        Unit longForestWallPeon = unitById(world, 214);
        Unit ladenReturnPeon = unitById(world, 50);
        Unit lateralDepotReturnPeon = unitById(world, 45);
        assertNotNull(peon, "XHuman 12 has no native-slot-1364 peon");
        assertNotNull(paidPrefixPeon,
                "XHuman 12 has no native-slot-1360 peon");
        assertNotNull(longForestWallPeon,
                "XHuman 12 has no native-slot-1386 peon");
        assertNotNull(ladenReturnPeon,
                "XHuman 12 has no native-slot-1550 peon");
        assertNotNull(lateralDepotReturnPeon,
                "XHuman 12 has no native-slot-1555 peon");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 163) {
            mission.tick();
        }

        assertEquals(12, peon.tileX(),
                "the terminal refusal must not strand the worker at route index 20");
        assertEquals(91, peon.tileY());
        assertEquals(Direction.fromDelta(1, 1), peon.lastStepHeading());

        // Native slot 1386 later plans through a queued regroup body but not
        // through the same body after its Move constructor has been promoted.
        // Keeping that active constructor solid produces this exact sixteen
        // byte route; treating every regroup body as soft shortens it to
        // thirteen bytes and reaches the wrong fixture-268 tile.
        while (fixtureCycle(world) < 203) {
            mission.tick();
        }
        assertEquals(14, longForestWallPeon.tileX());
        assertEquals(84, longForestWallPeon.tileY());
        assertEquals(0, longForestWallPeon.pathLength());
        assertEquals(2657, longForestWallPeon.battleNetSequenceOffset());
        assertEquals(1, longForestWallPeon.battleNetAnimationTimer());

        mission.tick();
        assertEquals(204, fixtureCycle(world));
        assertEquals(13, longForestWallPeon.tileX());
        assertEquals(84, longForestWallPeon.tileY());
        assertEquals(Direction.fromDelta(-1, 0),
                longForestWallPeon.lastStepHeading());
        assertEquals(16, longForestWallPeon.battleNetPathInitialLength());
        assertEquals(1, longForestWallPeon.battleNetPathStepsTaken());
        assertEquals(15, longForestWallPeon.pathLength());
        int[] exactTail = {
                Direction.fromDelta(-1, 0),
                Direction.fromDelta(-1, 0),
                Direction.fromDelta(-1, 1),
                Direction.fromDelta(-1, 1),
                Direction.fromDelta(0, 1),
                Direction.fromDelta(1, 1),
                Direction.fromDelta(1, 0),
                Direction.fromDelta(1, 0),
                Direction.fromDelta(1, -1),
                Direction.fromDelta(1, -1),
                Direction.fromDelta(1, 0),
                Direction.fromDelta(1, -1),
                Direction.fromDelta(0, -1),
                Direction.fromDelta(0, -1),
                Direction.fromDelta(0, -1)
        };
        for (int depth = 0; depth < exactTail.length; depth++) {
            assertEquals(exactTail[depth],
                    longForestWallPeon.peekHeadingAtDepth(depth),
                    "native slot 1386 route byte " + depth);
        }

        // A paid direct return face survives later route redraws. Native
        // raises its packed refusal generation on each wake and immediately
        // purchases another complete Move band while north remains occupied.
        while (fixtureCycle(world) < 253) {
            mission.tick();
        }
        assertEquals(6, ladenReturnPeon.tileX());
        assertEquals(29, ladenReturnPeon.tileY());
        assertEquals(10, ladenReturnPeon.battleNetCollisionCounter());
        assertEquals(0, ladenReturnPeon.pathLength());
        assertEquals(14, ladenReturnPeon.battleNetOrderDelay());
        assertEquals(15, ladenReturnPeon.battleNetAnimationTimer());

        // The same worker later finishes two north-west residuals with a
        // cached north-east byte held by native slot 1376. The NW+NE
        // simplifier exposes free north, but that byte makes no progress
        // toward the old (14,89) terrain point. Retail therefore parks the
        // six-byte prefix, selects the reachable (13,89) skirt, counts the
        // resource action's 3,2,1 construction, then writes SE,E and consumes
        // SE on fixture 266.
        while (fixtureCycle(world) < 261) {
            mission.tick();
        }
        assertEquals(10, peon.tileX());
        assertEquals(89, peon.tileY());
        assertEquals(4, peon.pathLength());
        assertEquals(6, peon.battleNetPathInitialLength());
        assertEquals(2, peon.battleNetPathStepsTaken());
        assertEquals(2, peon.battleNetCollisionCounter());
        assertEquals(Direction.fromDelta(-1, -1), peon.lastStepHeading());
        assertEquals(Direction.fromDelta(1, -1), peon.peekHeading());
        assertEquals(1, peon.battleNetAnimationTimer());

        mission.tick();
        assertEquals(262, fixtureCycle(world));
        assertEquals(10, peon.tileX());
        assertEquals(89, peon.tileY());
        assertEquals(0, peon.pathLength());
        assertEquals(3, peon.battleNetCollisionCounter());
        assertEquals(13, peon.battleNetWoodOrderX());
        assertEquals(89, peon.battleNetWoodOrderY());
        assertEquals(2600, peon.battleNetSequenceOffset());
        assertEquals(1, peon.battleNetAnimationTimer());

        mission.tick();
        assertEquals(263, fixtureCycle(world));
        assertEquals(10, peon.tileX());
        assertEquals(89, peon.tileY());
        assertEquals(0, peon.pathLength());
        assertEquals(0, peon.battleNetCollisionCounter());
        assertEquals(0, peon.battleNetRefusals());
        assertEquals(2657, peon.battleNetSequenceOffset());
        assertEquals(3, peon.battleNetAnimationTimer());
        assertEquals(12, paidPrefixPeon.tileX());
        assertEquals(87, paidPrefixPeon.tileY());
        assertEquals(2, paidPrefixPeon.pathLength());
        assertEquals(6, paidPrefixPeon.battleNetPathInitialLength());
        assertEquals(4, paidPrefixPeon.battleNetPathStepsTaken());
        assertEquals(Direction.fromDelta(1, 0),
                paidPrefixPeon.peekHeading());

        mission.tick();
        assertEquals(264, fixtureCycle(world));
        assertEquals(2, peon.battleNetAnimationTimer());
        assertEquals(12, paidPrefixPeon.tileX());
        assertEquals(87, paidPrefixPeon.tileY(),
                "the occupied lateral face must retain the terrain route");
        assertEquals(2, paidPrefixPeon.pathLength());
        assertEquals(Direction.fromDelta(1, 0),
                paidPrefixPeon.peekHeading());
        assertEquals(Direction.fromDelta(1, 1),
                paidPrefixPeon.peekHeadingAtDepth(1));
        assertEquals(1, paidPrefixPeon.battleNetCollisionCounter());
        assertEquals(14, paidPrefixPeon.battleNetOrderDelay());
        assertEquals(world.idle.battleNetSequenceStart(paidPrefixPeon,
                        BattleNetSequence.MOVE_ANIMATION),
                paidPrefixPeon.battleNetSequenceOffset());
        assertEquals(15, paidPrefixPeon.battleNetAnimationTimer());
        mission.tick();
        assertEquals(265, fixtureCycle(world));
        assertEquals(1, peon.battleNetAnimationTimer());
        assertEquals(12, paidPrefixPeon.tileX());
        assertEquals(87, paidPrefixPeon.tileY());
        assertEquals(2, paidPrefixPeon.pathLength());
        assertEquals(13, paidPrefixPeon.battleNetOrderDelay());
        assertEquals(14, paidPrefixPeon.battleNetAnimationTimer(),
                "the retained terminal prefix owns Move 15,14,...,1");
        mission.tick();
        assertEquals(266, fixtureCycle(world));
        assertEquals(11, peon.tileX());
        assertEquals(90, peon.tileY());
        assertEquals(Direction.fromDelta(1, 1), peon.lastStepHeading());
        assertEquals(1, peon.pathLength());
        assertEquals(Direction.fromDelta(1, 0), peon.peekHeading());
        assertEquals(1, peon.battleNetAnimationTimer());

        mission.tick();
        mission.tick();
        assertEquals(268, fixtureCycle(world));
        assertEquals(9, longForestWallPeon.tileX());
        assertEquals(86, longForestWallPeon.tileY());
        assertEquals(Direction.fromDelta(-1, 1),
                longForestWallPeon.lastStepHeading());
        assertEquals(Direction.fromDelta(0, 1),
                longForestWallPeon.peekHeading());
        assertEquals(16, longForestWallPeon.battleNetPathInitialLength());
        assertEquals(5, longForestWallPeon.battleNetPathStepsTaken());
        assertEquals(11, longForestWallPeon.pathLength());
        assertEquals(6, ladenReturnPeon.tileX());
        assertEquals(28, ladenReturnPeon.tileY(),
                "the inherited refusal band wakes on the first free visit");
        assertEquals(Direction.fromDelta(0, -1),
                ladenReturnPeon.lastStepHeading());
        assertEquals(10, ladenReturnPeon.battleNetCollisionCounter());

        while (fixtureCycle(world) < 275) {
            mission.tick();
        }
        assertEquals(7, lateralDepotReturnPeon.tileX());
        assertEquals(29, lateralDepotReturnPeon.tileY());
        assertEquals(3, lateralDepotReturnPeon.pathLength());
        assertEquals(Direction.fromDelta(1, -1),
                lateralDepotReturnPeon.lastStepHeading());
        assertEquals(6, lateralDepotReturnPeon.orderTargetX());
        assertEquals(23, lateralDepotReturnPeon.orderTargetY());

        mission.tick();
        assertEquals(276, fixtureCycle(world));
        assertEquals(7, lateralDepotReturnPeon.tileX(),
                "the settled residual parks before the replacement route");
        assertEquals(29, lateralDepotReturnPeon.tileY());
        assertEquals(0, lateralDepotReturnPeon.pathLength());
        assertEquals(1,
                lateralDepotReturnPeon.battleNetCollisionCounter());
        assertEquals(8, lateralDepotReturnPeon.orderTargetX(),
                "crossing the depot midpoint publishes its opposite edge");
        assertEquals(23, lateralDepotReturnPeon.orderTargetY());
        assertEquals(2600,
                lateralDepotReturnPeon.battleNetSequenceOffset());
        assertEquals(1,
                lateralDepotReturnPeon.battleNetAnimationTimer());

        mission.tick();
        assertEquals(277, fixtureCycle(world));
        assertEquals(7, lateralDepotReturnPeon.tileX());
        assertEquals(28, lateralDepotReturnPeon.tileY(),
                "the fresh depot-edge route commits north one visit later");
        assertEquals(Direction.fromDelta(0, -1),
                lateralDepotReturnPeon.lastStepHeading());
        assertEquals(2, lateralDepotReturnPeon.pathLength());
        assertEquals(Direction.fromDelta(-1, -1),
                lateralDepotReturnPeon.peekHeading());
        assertEquals(Direction.fromDelta(1, -1),
                lateralDepotReturnPeon.peekHeadingAtDepth(1));

    }

    @Test
    @DisplayName("a laden return redraws immediately behind a refused mover")
    void ladenReturnRedrawsImmediatelyBehindARefusedMover() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit peon = unitById(world, 48);
        assertNotNull(peon, "XHuman 12 has no native-slot-1552 peon");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        // Native slot 1552 consumes N,N,NW toward the stronghold. When the NW
        // residual settles at (5,25), the cached N is occupied by a peon whose
        // own route already carries refusal one. That is a hard blocker even
        // though it remains visibly mid-step: fixture 302 parks the cursor at
        // twenty under Move/1, and fixture 303 redraws NE,NW and commits NE.
        // Treating the blocker as a clean cooperative mover retains the stale
        // N byte under Move/15 and leaves this carrier at (5,25).
        while (fixtureCycle(world) < 301) {
            mission.tick();
        }
        assertEquals(5, peon.tileX());
        assertEquals(25, peon.tileY());
        assertEquals(2, peon.pathLength());
        assertEquals(Direction.fromDelta(0, -1), peon.peekHeading());

        mission.tick();
        assertEquals(302, fixtureCycle(world));
        assertEquals(5, peon.tileX());
        assertEquals(25, peon.tileY());
        assertEquals(0, peon.pathLength(),
                "the collided blocker parks native route index at twenty");
        assertEquals(1, peon.battleNetCollisionCounter());
        assertEquals(2600, peon.battleNetSequenceOffset());
        assertEquals(1, peon.battleNetAnimationTimer());

        mission.tick();
        assertEquals(303, fixtureCycle(world));
        assertEquals(6, peon.tileX());
        assertEquals(24, peon.tileY(),
                "the following return visit commits the replacement NE head");
        assertEquals(Direction.fromDelta(1, -1), peon.lastStepHeading());
        assertEquals(2, peon.battleNetPathInitialLength());
        assertEquals(1, peon.battleNetPathStepsTaken());
        assertEquals(1, peon.pathLength());
        assertEquals(Direction.fromDelta(-1, -1), peon.peekHeading());
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
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
