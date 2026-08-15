package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * A Stop click does not freeze dest-arm leftover pixels.
 *
 * <p>Authenticated commanded fixtures {@code stop-1/00} and {@code stop-1/02}
 * keep Move through leftover dest-arm after Stop at fixture 20, then stand
 * Still when the leftover lands. Java used to Still immediately and park
 * leftover offsets.
 */
class BattleNetStopLeftoverRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("a peon stop keeps leftover walking until the pixels land")
    void aPeonStopKeepsLeftoverWalkingUntilThePixelsLand() {
        assertStopDrainsLeftover("campaigns/orc/level01o", 25, 18, 22, 18);
    }

    @Test
    @DisplayName("a footman stop keeps leftover walking until the pixels land")
    void aFootmanStopKeepsLeftoverWalkingUntilThePixelsLand() {
        assertStopDrainsLeftover("campaigns/human/level01h", 23, 31, 24, 31);
    }

    private static void assertStopDrainsLeftover(String missionIdent,
            int nearX, int nearY, int destX, int destY) {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission(missionIdent, 0);
        Assumptions.assumeTrue(mission != null, missionIdent + " is not in the pack");
        World world = mission.world();
        CommandApplier commands = new CommandApplier(
                world, new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        Unit unit = nearestMover(world, nearX, nearY);
        assertNotNull(unit, missionIdent + " has no mover near " + nearX + "," + nearY);
        boolean moved = false;
        boolean stopped = false;
        Integer leftoverAfterStop = null;
        Integer stillAt = null;
        int stopPixelX = 0;
        int stopPixelY = 0;
        while (fixtureCycle(world) <= 40) {
            int fixture = fixtureCycle(world);
            if (fixture == 5 && !moved) {
                assertTrue(commands.apply(GameCommand.move(
                                unit.player(), unit.id(), destX, destY)),
                        "the opening click must be accepted");
                moved = true;
            }
            if (fixture == 20 && !stopped) {
                stopPixelX = unit.pixelX();
                stopPixelY = unit.pixelY();
                commands.apply(GameCommand.stop(unit.player(), unit.id()));
                stopped = true;
            }
            mission.tick();
            if (stopped && leftoverAfterStop == null
                    && (unit.pixelX() != stopPixelX || unit.pixelY() != stopPixelY)) {
                leftoverAfterStop = fixtureCycle(world);
            }
            if (stopped && stillAt == null && unit.order() == Unit.Order.STILL
                    && unit.offsetX() == 0 && unit.offsetY() == 0) {
                stillAt = fixtureCycle(world);
            }
        }
        assertTrue(moved && stopped, "the move and stop clicks must both be issued");
        assertNotNull(leftoverAfterStop,
                "retail keeps leftover dest-arm walking after Stop, not frozen pixels");
        assertTrue(leftoverAfterStop > 20,
                "retail leftover walks after fixture 20, not " + leftoverAfterStop);
        assertNotNull(stillAt,
                "retail stands Still once leftover pixels land");
        assertEquals(0, unit.offsetX(),
                "retail leftover lands before Still, not offset " + unit.offsetX());
        assertEquals(0, unit.offsetY(),
                "retail leftover lands before Still, not offset " + unit.offsetY());
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }

    private static Unit nearestMover(World world, int x, int y) {
        Unit best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Unit unit : world.unitsSnapshot()) {
            if (!unit.isAlive() || !unit.isOnMap() || unit.type() == null
                    || unit.type().building() || unit.type().speed() <= 0) {
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
