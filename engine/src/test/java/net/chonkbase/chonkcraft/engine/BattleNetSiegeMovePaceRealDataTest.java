package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A standing siege Move dest-arms on script.bin pace after the Still wait.
 *
 * <p>Authenticated batch-1/27 Orc 8 catapult 1576 first stands on 21,11 at
 * fixture 11 and dest-arms 2px/2cycles from 13. ChonkCraft Move used to
 * snap at 8 and freeze leftover -32 for the thirty-cycle {@code if-var R}
 * turn, so the window sat one tile short.
 */
class BattleNetSiegeMovePaceRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("an orc 8 north-east click dest-arms 21,11 by fixture 16")
    void anOrc8NorthEastClickDestArms2111ByFixture16() {
        assertSiegeDestArm("campaigns/orc/level08o", 20, 12, 23, 9,
                21, 11, 16, 22, 9, 75);
    }

    @Test
    @DisplayName("an orc 8 south-east click dest-arms 21,13 by fixture 16")
    void anOrc8SouthEastClickDestArms2113ByFixture16() {
        assertSiegeDestArm("campaigns/orc/level08o", 20, 12, 23, 15,
                21, 13, 16, 23, 15, 75);
    }

    @Test
    @DisplayName("an orc 9 south-east click dest-arms 82,57 by fixture 16")
    void anOrc9SouthEastClickDestArms8257ByFixture16() {
        assertSiegeDestArm("campaigns/orc/level09o", 81, 58, 84, 61,
                82, 57, 16, 84, 58, 75);
    }

    @Test
    @DisplayName("a gold click still vanishes into the mine after its wait")
    void aGoldClickStillVanishesIntoTheMineAfterItsWait() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/orc/level01o", 0);
        Assumptions.assumeTrue(mission != null, "Orc 1 is not in the pack");
        World world = mission.world();
        CommandApplier commands = new CommandApplier(
                world, new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Unit peon = nearest(world, 25, 18, ident -> ident.contains("peon"));
        assertNotNull(peon, "Orc 1 has no peon near 25,18");
        boolean issued = false;
        Integer offMap = null;
        while (fixtureCycle(world) <= 80) {
            if (fixtureCycle(world) == 4 && !issued) {
                assertTrue(commands.apply(GameCommand.harvest(
                                peon.player(), peon.id(), 26, 13)),
                        "the gold click must be accepted");
                issued = true;
            }
            mission.tick();
            if (issued && offMap == null && !peon.isOnMap()) {
                offMap = fixtureCycle(world);
            }
        }
        assertTrue(issued, "the gold click must be issued");
        assertTrue(offMap != null && offMap >= 50,
                "a gold harvest still pays the mine wait, not fixture " + offMap);
    }

    private static void assertSiegeDestArm(String map, int x, int y,
            int destX, int destY, int firstX, int firstY, int destArmBy,
            int laterX, int laterY, int laterBy) {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission(map, 0);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        World world = mission.world();
        CommandApplier commands = new CommandApplier(
                world, new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Unit engine = nearest(world, x, y, ident ->
                ident.contains("catapult") || ident.contains("ballista"));
        assertNotNull(engine, map + " has no siege engine near " + x + "," + y);
        int startPx = engine.pixelX();
        int startPy = engine.pixelY();
        boolean issued = false;
        Integer firstTile = null;
        Integer destArmed = null;
        Integer laterTile = null;
        while (fixtureCycle(world) <= laterBy + 10) {
            if (fixtureCycle(world) == 4 && !issued) {
                startPx = engine.pixelX();
                startPy = engine.pixelY();
                assertTrue(commands.apply(GameCommand.move(
                                engine.player(), engine.id(), destX, destY)),
                        "the siege move click must be accepted");
                issued = true;
            }
            mission.tick();
            if (!issued) {
                continue;
            }
            if (firstTile == null
                    && engine.tileX() == firstX && engine.tileY() == firstY) {
                firstTile = fixtureCycle(world);
            }
            if (destArmed == null
                    && (engine.pixelX() != startPx || engine.pixelY() != startPy)) {
                destArmed = fixtureCycle(world);
            }
            if (laterTile == null
                    && engine.tileX() == laterX && engine.tileY() == laterY) {
                laterTile = fixtureCycle(world);
            }
        }
        assertTrue(issued, "the siege move click must be issued");
        assertTrue(firstTile != null && firstTile >= 10 && firstTile <= 12,
                "retail first stands on " + firstX + "," + firstY
                        + " after the Still queue, not fixture " + firstTile);
        assertTrue(destArmed != null && destArmed <= destArmBy,
                "retail dest-arms the leftover instead of turning for thirty, not fixture "
                        + destArmed);
        assertTrue(laterTile != null && laterTile <= laterBy,
                "retail reaches " + laterX + "," + laterY
                        + " on script.bin pace, not fixture " + laterTile);
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }

    private static Unit nearest(World world, int x, int y,
            java.util.function.Predicate<String> wanted) {
        Unit best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Unit unit : world.unitsSnapshot()) {
            if (!unit.isAlive() || !unit.isOnMap() || unit.type() == null
                    || unit.type().building()) {
                continue;
            }
            if (!wanted.test(unit.type().ident())) {
                continue;
            }
            int dist = Math.max(Math.abs(unit.tileX() - x),
                    Math.abs(unit.tileY() - y));
            if (dist < bestDist) {
                best = unit;
                bestDist = dist;
            }
        }
        return best;
    }
}
