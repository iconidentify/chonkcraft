package net.chonkbase.chonkcraft.engine.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Native 0x439bb6 founds an oil platform on the nearest patch to the depot.
 *
 * <p>Retail's tanker ready path harvests a reachable platform when one
 * exists. Otherwise it picks the oil-patch nearest the 0x438f40
 * shipyard/refinery and issues action 28. Human 14 player 5's refinery
 * at 93,71 therefore founds on 105,49 (not 15,57). Human 7 already has
 * platforms, so its tankers keep harvesting and do not plant a second.
 */
class BattleNetAiOilPlatformRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;
    private static final String SKIP =
            "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir";

    @Test
    @DisplayName("a human 14 tanker founds on the oil patch nearest the refinery")
    void aHuman14TankerFoundsOnTheOilPatchNearestTheRefinery() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null, SKIP);
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human/level14h", 0, 1);
        Assumptions.assumeTrue(mission != null, "Human 14 is not in the pack");
        World world = mission.world();
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Unit tanker = null;
        for (int cycle = 1; cycle <= 700 && tanker == null; cycle++) {
            mission.tick();
            tanker = firstTanker(world, 5);
        }
        assertTrue(tanker != null,
                "Human 14 player 5 trains an oil tanker before retail founds at 675");
        Unit patch = world.findBattleNetReadyOilPatch(tanker);
        assertTrue(patch != null,
                "Human 14 player 5 has a reachable oil patch from its refinery");
        assertEquals(105, patch.tileX(),
                "retail's first Human 14 platform sits on the patch at 105,49");
        assertEquals(49, patch.tileY(),
                "retail's first Human 14 platform sits on the patch at 105,49");
    }

    @Test
    @DisplayName("a human 14 tanker plants the first platform on that patch")
    void aHuman14TankerPlantsTheFirstPlatformOnThatPatch() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null, SKIP);
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human/level14h", 0, 1);
        Assumptions.assumeTrue(mission != null, "Human 14 is not in the pack");
        World world = mission.world();
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        boolean founded = false;
        boolean walking = false;
        for (int cycle = 1; cycle <= 700; cycle++) {
            mission.tick();
            for (Unit unit : world.units()) {
                if (unit == null || unit.player() != 5 || !unit.isAlive()
                        || unit.type() == null) {
                    continue;
                }
                if (unit.type().ident().contains("oil-platform")
                        && unit.tileX() == 105 && unit.tileY() == 49) {
                    founded = true;
                }
                if (unit.order() == Unit.Order.BUILD
                        && unit.buildGoalX() == 105 && unit.buildGoalY() == 49) {
                    walking = true;
                }
            }
            if (founded || walking) {
                break;
            }
        }
        assertTrue(founded || walking,
                "retail founds Human 14's first platform at 105,49, so a tanker "
                        + "must be walking there or the platform must already stand");
    }

    @Test
    @DisplayName("a human 7 tanker keeps the existing platform instead of planting another")
    void aHuman7TankerKeepsTheExistingPlatformInsteadOfPlantingAnother() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null, SKIP);
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human/level07h", 0, 1);
        Assumptions.assumeTrue(mission != null, "Human 7 is not in the pack");
        World world = mission.world();
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        mission.tick();
        int platforms = countPlatforms(world);
        assertTrue(platforms > 0,
                "Human 7 starts with oil platforms the tanker can harvest");
        Unit tanker = firstTanker(world, -1);
        if (tanker != null) {
            Unit existing = world.findBattleNetReadyOilPlatform(tanker);
            assertTrue(existing != null,
                    "a Human 7 tanker with a naval base must still see a platform");
        }
        for (int cycle = 2; cycle <= 50; cycle++) {
            mission.tick();
        }
        assertEquals(platforms, countPlatforms(world),
                "Human 7's ready tankers harvest; they do not found a second platform");
    }

    @Test
    @DisplayName("an XHuman 8 tanker builds instead of using another player's platform")
    void anXHuman8TankerBuildsInsteadOfUsingAnotherPlayersPlatform() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null, SKIP);
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx08h", 0, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 8 is not in the pack");
        World world = mission.world();
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 536) {
            mission.tick();
        }

        Unit tanker = firstTanker(world, 3);
        assertTrue(tanker != null,
                "XHuman 8 has no player-three tanker at fixture 536");
        assertEquals(Unit.Order.BUILD, tanker.order(),
                "native ignores player seven's platform and orders a new one");
        assertTrue(tanker.pendingBuild() != null
                        && "unit-orc-oil-platform".equals(tanker.pendingBuild().ident()),
                "the tanker's build order must own the orc oil platform");
        Player player = world.player(3);
        assertEquals(50, player.get(UnitType.Resource.GOLD),
                "fixture 536 debits the platform's 700 gold");
        assertEquals(950, player.get(UnitType.Resource.WOOD),
                "fixture 536 debits the platform's 450 wood");
    }

    private static Unit firstTanker(World world, int player) {
        for (Unit unit : world.units()) {
            if (unit == null || !unit.isAlive() || unit.type() == null) {
                continue;
            }
            if (player >= 0 && unit.player() != player) {
                continue;
            }
            String ident = unit.type().ident();
            if ("unit-orc-oil-tanker".equals(ident)
                    || "unit-human-oil-tanker".equals(ident)) {
                return unit;
            }
        }
        return null;
    }

    private static int countPlatforms(World world) {
        int count = 0;
        for (Unit unit : world.units()) {
            if (unit != null && unit.isAlive() && unit.type() != null
                    && unit.type().ident().contains("oil-platform")) {
                count++;
            }
        }
        return count;
    }
}
