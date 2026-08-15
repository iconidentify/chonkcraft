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
 * A player Attack Ground click from Still queues order 17.
 *
 * <p>Authenticated commanded fixtures keep Still and next_order 17 through
 * the remaining Still wait, then AttackGround: Human 7 catapult 1519 at
 * fixture 9, Orc 8 catapult 1576 at fixture 8. Java used to install
 * AttackGround on the issue cycle.
 */
class BattleNetAttackGroundStillQueueRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("a human 7 catapult stays Still until fixture 9")
    void aHuman7CatapultStaysStillUntilFixture9() {
        assertAttackGroundPromotesAt("campaigns/human/level07h", 9, 65, 13, 65, 9);
    }

    @Test
    @DisplayName("an orc 8 catapult stays Still until fixture 8")
    void anOrc8CatapultStaysStillUntilFixture8() {
        assertAttackGroundPromotesAt("campaigns/orc/level08o", 20, 12, 24, 12, 8);
    }

    @Test
    @DisplayName("a still grunt still waits before its first dest-arm")
    void aStillGruntStillWaitsBeforeItsFirstDestArm() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human/level04h", 0);
        Assumptions.assumeTrue(mission != null, "Human 4 is not in the pack");
        World world = mission.world();
        CommandApplier commands = new CommandApplier(
                world, new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Unit grunt = nearest(world, 87, 94, ident ->
                ident.contains("footman") || ident.contains("grunt"));
        assertNotNull(grunt, "Human 4 has no fighter near 87,94");
        Integer destArm = null;
        boolean issued = false;
        while (fixtureCycle(world) <= 20) {
            if (fixtureCycle(world) == 4 && !issued) {
                assertTrue(commands.apply(GameCommand.move(
                                grunt.player(), grunt.id(), 89, 92)),
                        "the still grunt click must be accepted");
                issued = true;
            }
            mission.tick();
            if (issued && destArm == null
                    && (grunt.tileX() != 87 || grunt.tileY() != 94
                    || grunt.offsetX() != 0 || grunt.offsetY() != 0)) {
                destArm = fixtureCycle(world);
            }
        }
        assertTrue(issued, "the move click must be issued");
        assertTrue(destArm != null && destArm >= 10,
                "a still grunt still pays the Still queue before dest-arm, not fixture "
                        + destArm);
    }

    private static void assertAttackGroundPromotesAt(String map, int x, int y,
            int destX, int destY, int promoteFixture) {
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
        Unit catapult = nearest(world, x, y, ident -> ident.contains("catapult"));
        assertNotNull(catapult, map + " has no catapult near " + x + "," + y);
        Integer promoted = null;
        boolean issued = false;
        while (fixtureCycle(world) <= 16) {
            if (fixtureCycle(world) == 4 && !issued) {
                assertTrue(commands.apply(GameCommand.attackGround(
                                catapult.player(), catapult.id(), destX, destY)),
                        "the attack-ground click must be accepted");
                issued = true;
            }
            mission.tick();
            if (issued && promoted == null
                    && catapult.order() == Unit.Order.ATTACK_GROUND) {
                promoted = fixtureCycle(world);
            }
        }
        assertTrue(issued, "the attack-ground click must be issued");
        assertEquals(promoteFixture, promoted,
                "retail keeps Still then AttackGround at fixture "
                        + promoteFixture + ", not " + promoted);
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }

    private static Unit nearest(World world, int x, int y,
            java.util.function.Predicate<String> wanted) {
        Unit best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Unit unit : world.unitsSnapshot()) {
            if (!unit.isAlive() || !unit.isOnMap() || unit.type() == null) {
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
