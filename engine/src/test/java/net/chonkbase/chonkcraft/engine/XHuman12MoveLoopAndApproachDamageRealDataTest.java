package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Locks XHuman 12's first post-cycle-52 native movement/combat boundary. */
class XHuman12MoveLoopAndApproachDamageRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("xhuman 12 pays the move-loop OP0 and approach-damage hold")
    void xhuman12PaysMoveLoopOp0AndApproachDamageHold() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx12h",
                GameData.personIn(data.campaignMap(
                        "campaigns/human-exp/levelx12h")), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        Unit loopGrunt = unitAt(world, "unit-grunt", 26, 39);
        Unit approachGrunt = unitAt(world, "unit-grunt", 23, 60);
        Unit footman = unitAt(world, "unit-footman", 26, 59);
        assertNotNull(loopGrunt, "XHuman 12 has no native-slot-1494 grunt");
        assertNotNull(approachGrunt, "XHuman 12 has no native-slot-1448 grunt");
        assertNotNull(footman, "XHuman 12 has no native-slot-1449 footman");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        int loopX53 = -1;
        int loopY53 = -1;
        int loopSequence53 = -1;
        int loopX54 = -1;
        int loopY54 = -1;
        int approachTimer43 = -1;
        int footmanHp57 = -1;
        int footmanHp58 = -1;
        while (world.cycle() < 61) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 43) {
                approachTimer43 = approachGrunt.battleNetAnimationTimer();
            }
            if (fixture == 53) {
                loopX53 = loopGrunt.tileX();
                loopY53 = loopGrunt.tileY();
                loopSequence53 = loopGrunt.battleNetSequenceOffset();
            }
            if (fixture == 54) {
                loopX54 = loopGrunt.tileX();
                loopY54 = loopGrunt.tileY();
            }
            if (fixture == 57) {
                footmanHp57 = footman.hitPoints();
            }
            if (fixture == 58) {
                footmanHp58 = footman.hitPoints();
            }
        }

        assertEquals(23, approachTimer43,
                "damaged approach must arm native's Attack body hold");
        assertEquals(27, loopX53, "Move-loop goto must not step on fixture 53");
        assertEquals(39, loopY53, "Move-loop goto holds the old logical tile");
        assertEquals(2482, loopSequence53,
                "native yields on the grunt Move opening OP0");
        assertEquals(28, loopX54, "the following OP0 visit takes the next step");
        assertEquals(40, loopY54,
                "the delayed replan observes traffic and chooses southeast");
        assertEquals(60, footmanHp57,
                "the held grunt must not land Java's early fixture-53 blow");
        assertEquals(55, footmanHp58,
                "the first native damage at fixture 58 comes from the axe");
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
