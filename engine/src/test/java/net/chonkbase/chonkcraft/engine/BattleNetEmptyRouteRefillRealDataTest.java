package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A spent 20-byte BNE route that is still short of dest dest-arms the next
 * leftover immediately.
 *
 * <p>Sealed Human 1 field twins keep a 16-cycle leftover after every tile
 * once the first dest-arm starts. Java used to sit 27 cycles on the tile
 * where the first buffer emptied -- 23,13 on the 21,5 walk to 25,28 and
 * 19,12 on the 17,7 walk to the same square -- because {@code stepMove}
 * paid PF_WAIT 10 before asking {@code 0x44fbd0}. Native {@code 0x437c80}
 * calls the pathfinder on the same visit {@code 0x44fab0} fails. The
 * 21,5 walk onto 25,12 never empties mid-route and already matches.
 */
class BattleNetEmptyRouteRefillRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;
    private static final String MAP = "campaigns/human/level01h";

    @Test
    @DisplayName("a spent human walk dest-arms the next leftover in sixteen cycles")
    void aSpentHumanWalkDestArmsTheNextLeftoverInSixteenCycles() {
        assertDwell(21, 5, 25, 28, 23, 13);
        assertDwell(17, 7, 25, 28, 19, 12);
    }

    @Test
    @DisplayName("a short walk on the same corridor does not insert a twenty-seven cycle leftover")
    void aShortWalkOnTheSameCorridorDoesNotInsertATwentySevenCycleLeftover() {
        List<int[]> steps = walk(21, 5, 25, 12, 160);
        assertTrue(steps.size() > 2,
                "the short walk must take more than the opening dest-arm");
        for (int i = 2; i < steps.size(); i++) {
            int dwell = steps.get(i)[0] - steps.get(i - 1)[0];
            assertEquals(16, dwell,
                    "retail keeps a 16-cycle leftover after the first dest-arm, not "
                            + dwell + " between " + steps.get(i - 1)[1] + ","
                            + steps.get(i - 1)[2] + " and " + steps.get(i)[1]
                            + "," + steps.get(i)[2]);
        }
        int[] last = steps.getLast();
        assertEquals(25, last[1],
                "the short walk settles on 25,12, not " + last[1] + "," + last[2]);
        assertEquals(12, last[2],
                "the short walk settles on 25,12, not " + last[1] + "," + last[2]);
    }

    private static void assertDwell(int startX, int startY, int destX, int destY,
            int fromX, int fromY) {
        List<int[]> steps = walk(startX, startY, destX, destY, 220);
        Integer from = null;
        Integer next = null;
        for (int[] step : steps) {
            if (from == null && step[1] == fromX && step[2] == fromY) {
                from = step[0];
                continue;
            }
            if (from != null) {
                next = step[0];
                break;
            }
        }
        assertNotNull(from, "the walk from " + startX + "," + startY
                + " never dest-armed " + fromX + "," + fromY);
        assertNotNull(next, "the spent buffer at " + fromX + "," + fromY
                + " must dest-arm the next leftover, not sit through PF_WAIT 10");
        assertEquals(16, next - from,
                "retail dest-arms the leftover after " + fromX + "," + fromY
                        + " in 16 cycles, not " + (next - from));
    }

    private static List<int[]> walk(int startX, int startY, int destX, int destY,
            int lastFixture) {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission(MAP,
                GameData.personIn(data.campaignMap(MAP)), 1);
        Assumptions.assumeTrue(mission != null, "Human 1 is not in the pack");
        World world = mission.world();
        CommandApplier commands = new CommandApplier(
                world, new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Unit footman = atTile(world, startX, startY);
        assertNotNull(footman, "Human 1 has no footman on " + startX + "," + startY);
        boolean issued = false;
        List<int[]> steps = new ArrayList<>();
        int lastX = Integer.MIN_VALUE;
        int lastY = Integer.MIN_VALUE;
        while (fixtureCycle(world) <= lastFixture) {
            if (fixtureCycle(world) == 5 && !issued) {
                assertTrue(commands.apply(GameCommand.move(
                                footman.player(), footman.id(), destX, destY)),
                        "the field click to " + destX + "," + destY + " must be accepted");
                issued = true;
            }
            mission.tick();
            if (footman.tileX() != lastX || footman.tileY() != lastY) {
                lastX = footman.tileX();
                lastY = footman.tileY();
                steps.add(new int[] {fixtureCycle(world), lastX, lastY});
            }
        }
        assertTrue(issued, "the field click must be issued");
        return steps;
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }

    private static Unit atTile(World world, int x, int y) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.isAlive() && unit.isOnMap() && unit.tileX() == x
                    && unit.tileY() == y && unit.type() != null
                    && !unit.type().building() && unit.type().speed() > 0) {
                return unit;
            }
        }
        return null;
    }
}
