package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.pathfinder.BattleNetPathFinder;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated Attack construction after XHuman 12's saturated route park. */
class Xhuman12SaturatedResidualConstructionRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("xhuman 12's saturated residual pays attack construction before refill")
    void xhuman12GruntPaysNativeConstructionBeforeItsEastRefill() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit grunt = unitById(world, 119);
        assertNotNull(grunt,
                "XHuman 12 has no Java unit 119 / native grunt 1481");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 215) {
            mission.tick();
        }

        assertEquals(28, grunt.tileX(), "the third chase stride settles at 28,39");
        assertEquals(39, grunt.tileY(), "the third chase stride settles at 28,39");
        assertEquals(0, grunt.pathLength(),
                "native parks the saturated residual at route index twenty");
        assertEquals(3, grunt.battleNetCollisionCounter(),
                "the settled park advances native's collision generation");
        assertEquals(1, grunt.battleNetRefusals(),
                "the occupied replacement head owns one hard refusal");
        assertEquals(Direction.fromDelta(1, 0),
                grunt.battleNetParkedRefusalHeading(),
                "the parked native wall face remains east");

        mission.tick();

        assertEquals(0, grunt.pathLength(),
                "fixture 216 must not pre-plan a one-byte free detour");
        assertEquals(3, grunt.battleNetAnimationTimer(),
                "fixture 216 opens native Attack construction at timer three");
        assertEquals(0, grunt.battleNetOrderDelay(),
                "the constructor owns the wait instead of a generic delay");
        assertEquals(0, grunt.battleNetCollisionCounter(),
                "Attack construction clears the completed collision generation");
        assertEquals(0, grunt.battleNetRefusals(),
                "Attack construction clears the completed refusal generation");
        assertEquals(0x793e7025, world.battleNetRandomSeed(),
                "the active-order promotion owns native's fixture-216 idle draw");

        mission.tick();
        assertEquals(2, grunt.battleNetAnimationTimer(),
                "fixture 217 retains Attack construction timer two");
        mission.tick();
        assertEquals(1, grunt.battleNetAnimationTimer(),
                "fixture 218 retains Attack construction timer one");
        mission.tick();

        assertEquals(29, grunt.tileX(),
                "fixture 219 consumes native's replacement east heading");
        assertEquals(39, grunt.tileY(),
                "the replacement heading is cardinal east");
        assertEquals(19, grunt.pathLength(),
                "native retains nineteen bytes after its first east step");
        assertEquals(Direction.fromDelta(1, 1), grunt.peekHeading(),
                "the second replacement byte is south-east");
    }

    @Test
    @DisplayName("a paid mobile retarget retains its first wall buffer through attack construction")
    void paidMobileRetargetDrainsConstructionBeforeItsNextWallHeading() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit grunt = unitById(world, 94);
        assertNotNull(grunt,
                "XHuman 12 has no Java unit 94 / native grunt 1506");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 266) {
            mission.tick();
        }

        assertEquals(33, grunt.tileX());
        assertEquals(38, grunt.tileY());
        assertEquals(BattleNetPathFinder.MAX_PATH - 1, grunt.pathLength(),
                "the consumed south-west opening retains the complete wall buffer");
        assertEquals(BattleNetPathFinder.MAX_PATH,
                grunt.battleNetPathInitialLength());
        assertEquals(1, grunt.battleNetPathStepsTaken());
        assertEquals(Direction.fromDelta(-1, 1), grunt.lastStepHeading(),
                "the paid writer consumes south-west immediately");
        assertEquals(Direction.fromDelta(0, 1), grunt.peekHeading(),
                "south remains at the retained route cursor");

        while (fixtureCycle(world) < 282) {
            mission.tick();
        }

        int attackStart = world.idle.battleNetSequenceStart(grunt,
                net.chonkbase.chonkcraft.engine.animation.BattleNetSequence
                        .ATTACK_ANIMATION);
        assertEquals(attackStart, grunt.battleNetSequenceOffset());
        assertEquals(3, grunt.battleNetAnimationTimer(),
                "residual settlement opens native Attack construction");
        assertEquals(0, grunt.battleNetOrderDelay(),
                "the visible constructor owns the delay");
        assertEquals(BattleNetPathFinder.MAX_PATH - 1, grunt.pathLength());

        mission.tick();
        assertEquals(2, grunt.battleNetAnimationTimer(),
                "fixture 283 drains Attack construction timer two");
        mission.tick();
        assertEquals(1, grunt.battleNetAnimationTimer(),
                "fixture 284 drains Attack construction timer one");
        mission.tick();

        assertEquals(33, grunt.tileX());
        assertEquals(39, grunt.tileY(),
                "timer one's fixture-285 callback consumes south");
        assertEquals(BattleNetPathFinder.MAX_PATH - 2, grunt.pathLength());
        assertEquals(Direction.fromDelta(0, 1), grunt.lastStepHeading());
        assertEquals(Direction.fromDelta(1, 1), grunt.peekHeading(),
                "south-east follows the committed south wall face");
    }

    @Test
    @DisplayName("a parked cardinal chase drains every paid Move band before Still")
    void parkedCardinalChaseKeepsNativeMoveBandOwnership() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit grunt = unitById(world, 143);
        assertNotNull(grunt,
                "XHuman 12 has no Java unit 143 / native grunt 1457");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 172) {
            mission.tick();
        }

        int moveStart = world.idle.battleNetSequenceStart(grunt,
                net.chonkbase.chonkcraft.engine.animation.BattleNetSequence
                        .MOVE_ANIMATION);
        int attackStart = world.idle.battleNetSequenceStart(grunt,
                net.chonkbase.chonkcraft.engine.animation.BattleNetSequence
                        .ATTACK_ANIMATION);
        assertEquals(moveStart, grunt.battleNetSequenceOffset(),
                "the seventh direct refusal remains on Move");
        assertEquals(1, grunt.battleNetAnimationTimer(),
                "the seventh direct refusal is still a one-count probe");

        mission.tick();
        assertEquals(173, fixtureCycle(world));
        assertEquals(moveStart, grunt.battleNetSequenceOffset());
        assertEquals(15, grunt.battleNetAnimationTimer(),
                "refusal eight opens the first complete Move band");

        while (fixtureCycle(world) < 188) {
            mission.tick();
        }
        assertEquals(14, grunt.battleNetOrderDelay(),
                "the replacement cardinal pair owns a second Move band");

        while (fixtureCycle(world) < 203) {
            mission.tick();
        }
        assertEquals(moveStart, grunt.battleNetSequenceOffset());
        assertEquals(15, grunt.battleNetAnimationTimer(),
                "the retained blocked pair owns its final Move band");

        while (fixtureCycle(world) < 218) {
            mission.tick();
        }
        assertEquals(attackStart, grunt.battleNetSequenceOffset(),
                "only the paid-band wake enters active-order Attack");
        assertEquals(3, grunt.battleNetAnimationTimer(),
                "the wake opens native Attack construction at timer three");
        assertEquals(0x218bb5ed, world.battleNetRandomSeed(),
                "the wake owns native's fixture-218 idle draw");

        while (fixtureCycle(world) < 221) {
            mission.tick();
        }
        assertEquals(3, grunt.battleNetAnimationTimer(),
                "the blocked Still retry reopens construction on fixture 221");
        assertEquals(0x55659d45, world.battleNetRandomSeed(),
                "fixture 221 retains the native asynchronous ledger");

        while (fixtureCycle(world) < 224) {
            mission.tick();
        }
        assertEquals(3, grunt.battleNetAnimationTimer(),
                "the blocked Still retry reopens construction on fixture 224");
        assertEquals(0xcae662be, world.battleNetRandomSeed(),
                "fixture 224 retains the native asynchronous ledger");
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
