package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/** Demolish follows the pinned BNE 2.02b player dispatcher and action-20/21 captures. */
class BattleNetDemolitionRealDataTest {
    @Test
    @DisplayName("dwarves detonate on the native action callback and do not chain neighbouring squads")
    void dwarvesDetonateOnTheNativeActionCallbackAndDoNotChainNeighbouringSquads() {
        Campaign game = new Campaign(false);
        Unit near = game.at(34, 113);
        Unit far = game.at(35, 112);
        for (int cycle = 1; cycle <= 30; cycle++) {
            if (cycle == 5) game.point(33, 113);
            game.mission.tick();
            assertEquals(cycle < 12, game.actor.isAlive(),
                    "Human 12's commanded squad must detonate at BNE cycle 12");
            assertEquals(cycle < 12, near.isAlive(), "the adjacent squad dies in the same blast");
            assertTrue(far.isAlive(), "a killed squad must not trigger a second blast into the next squad");
            var sounds = game.mission.world().drainSoundEvents().stream()
                    .filter(event -> event.unit() == game.actor
                            && (event.named() || "dead".equals(event.event()))).toList();
            assertEquals(cycle == 12 ? 1 : 0, sounds.size(),
                    "the squad's native death must announce one explosion, without a duplicate spell sound");
        }
    }

    @Test
    @DisplayName("goblin sappers obey their zero-mana demolition command on the native callback")
    void goblinSappersObeyTheirZeroManaDemolitionCommandOnTheNativeCallback() {
        Campaign game = new Campaign(true);
        for (int cycle = 1; cycle <= 20; cycle++) {
            if (cycle == 5) game.point(78, 86);
            game.mission.tick();
            assertEquals(cycle < 10, game.actor.isAlive(),
                    "Orc 10's commanded sapper must detonate at BNE cycle 10");
        }
    }

    @Test
    @DisplayName("a ground demolition reaches the selected tile before exploding")
    void aGroundDemolitionReachesTheSelectedTileBeforeExploding() {
        Campaign game = new Campaign(false);
        for (int cycle = 1; cycle <= 100; cycle++) {
            if (cycle == 5) game.point(38, 111);
            game.mission.tick();
            assertEquals(cycle < 96, game.actor.isAlive(),
                    "the squad must finish its native route before detonating at cycle 96");
            if (cycle == 11) game.position(33, 113, 1056, 3616);
            if (cycle == 12) game.position(33, 112, 1056, 3616);
            if (cycle == 13) game.position(33, 112, 1056, 3613);
            if (cycle == 26) game.position(34, 111, 1056, 3584);
            if (cycle == 96) game.position(38, 111, 1216, 3552);
        }
    }

    @Test
    @DisplayName("demolition stops beside a building and deals four hundred damage once")
    void demolitionStopsBesideABuildingAndDealsFourHundredDamageOnce() {
        Campaign game = new Campaign(false);
        Unit building = game.at(23, 112);
        assertEquals(500, building.hitPoints(), "Human 12's blacksmith is the native damage witness");
        for (int cycle = 1; cycle <= 120; cycle++) {
            if (cycle == 5) game.target(building);
            game.mission.tick();
            assertEquals(cycle < 110, game.actor.isAlive(),
                    "the squad must finish its approach beside the blacksmith on cycle 110");
            assertEquals(cycle < 110 ? 500 : 100, building.hitPoints(),
                    "multiple occupied blast cells must not multiply the building's damage");
            if (cycle == 12) game.position(32, 114, 1056, 3616);
            if (cycle == 96) game.position(26, 114, 864, 3680);
            if (cycle == 110) game.position(26, 114, 832, 3648);
        }
    }

    @Test
    @DisplayName("stop cancels a demolition after its committed stride without detonating")
    void stopCancelsADemolitionAfterItsCommittedStrideWithoutDetonating() {
        Campaign game = new Campaign(false);
        for (int cycle = 1; cycle <= 180; cycle++) {
            if (cycle == 5) game.point(38, 111);
            if (cycle == 30) assertTrue(game.commands.apply(GameCommand.stop(game.person, game.actor.id())),
                    "Stop must reach a demolition squad during its approach");
            game.mission.tick();
            assertTrue(game.actor.isAlive(), "the cancelled demolition must never explode");
            if (cycle >= 40) {
                game.position(34, 111, 1088, 3552);
                assertEquals(Unit.Order.STILL, game.actor.order(),
                        "BNE promotes Stop when the committed diagonal stride finishes at cycle 40");
            }
        }
    }

    @Test
    @DisplayName("a demolition unit cannot target itself as a unit")
    void aDemolitionUnitCannotTargetItselfAsAUnit() {
        Campaign game = new Campaign(false);
        assertFalse(game.commands.apply(GameCommand.cast(game.person, game.actor.id(),
                        game.actor.id(), game.ability)),
                "BNE 0x47606a refuses a selected unit targeting itself");
        for (int cycle = 0; cycle < 60; cycle++) game.mission.tick();
        assertTrue(game.actor.isAlive(), "a refused unit target must preserve the squad");
    }

    @Test
    @DisplayName("rapid demolition retargeting waits for the current constructor and follows only the last click")
    void rapidDemolitionRetargetingWaitsForTheCurrentConstructorAndFollowsOnlyTheLastClick() {
        Campaign game = new Campaign(false);
        for (int cycle = 1; cycle <= 60; cycle++) {
            if (cycle == 5) game.point(38, 111);
            if (cycle == 10) game.point(31, 113);
            game.mission.tick();
            assertEquals(cycle < 43, game.actor.isAlive(), "the replacement explodes at native cycle 43");
            if (cycle >= 10 && cycle <= 14) game.position(33, 113, 1056, 3616);
            if (cycle == 15) game.position(32, 113, 1056, 3616);
            if (cycle == 43) game.position(31, 113, 992, 3616);
        }
    }

    @Test
    @DisplayName("a move cancels demolition and begins after the native replacement pause")
    void aMoveCancelsDemolitionAndBeginsAfterTheNativeReplacementPause() {
        Campaign game = new Campaign(false);
        for (int cycle = 1; cycle <= 180; cycle++) {
            if (cycle == 5) game.point(38, 111);
            if (cycle == 30) assertTrue(game.commands.apply(GameCommand.move(game.person, game.actor.id(), 31, 113)),
                    "the squad must accept Move during its demolition approach");
            game.mission.tick();
            assertTrue(game.actor.isAlive(), "Move must cancel the explosion");
            if (cycle >= 40 && cycle <= 42) game.position(34, 111, 1088, 3552);
            if (cycle == 43) game.position(33, 112, 1088, 3552);
            if (cycle == 180) game.position(31, 113, 992, 3616);
        }
    }

    @Test
    @DisplayName("demolition opens the native forest cells without clearing the next trees")
    void demolitionOpensTheNativeForestCellsWithoutClearingTheNextTrees() {
        Campaign game = new Campaign(false);
        for (int cycle = 1; cycle <= 26; cycle++) {
            if (cycle == 5) game.point(31, 111);
            game.mission.tick();
            assertEquals(cycle < 26, game.actor.isAlive(), "the squad reaches the forest edge on native cycle 26");
        }
        // 0x44e270 visits the eight neighbours and the four cardinal cells
        // at distance two for forest/rock. Walls and units use eight only.
        for (int[] cell : new int[][] {{32, 110}, {31, 111}, {32, 111}, {30, 112}, {31, 112}}) {
            assertFalse(game.mission.world().map().field(cell[0], cell[1]).isForest(),
                    "the native demolition must open forest at " + cell[0] + "," + cell[1]);
        }
        for (int[] cell : new int[][] {{31, 110}, {33, 110}, {30, 111}, {32, 109}}) {
            assertTrue(game.mission.world().map().field(cell[0], cell[1]).isForest(),
                    "the native blast must retain forest at " + cell[0] + "," + cell[1]);
        }
    }

    @Test
    @DisplayName("saving demolition preserves its approach target and a later cancellation")
    void savingDemolitionPreservesItsApproachTargetAndALaterCancellation() throws java.io.IOException {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null, "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        GameData data = new GameData(assets);
        for (boolean cancel : new boolean[] {false, true}) {
            List<Long> expected = savedRun(data, cancel, -1);
            assertEquals(240, expected.size(), "every continuation must cover the approach and outcome");
            for (int checkpoint : new int[] {5, 9, 20, 30, 40}) {
                assertEquals(expected, savedRun(data, cancel, checkpoint),
                        "saving at " + checkpoint + " must preserve all demolition state, damage and random draws");
            }
        }
    }

    private static List<Long> savedRun(GameData data, boolean cancel, int checkpoint) throws java.io.IOException {
        World world = plainWorld(data);
        Unit actor = world.createUnit(data.unitTypes().types().get("unit-goblin-sappers"), 0, 4, 8);
        Unit target = world.createUnit(data.unitTypes().types().get("unit-fortress"), 0, 18, 8);
        int actorId = actor.id();
        int ability = data.spells().spells().all().keySet().stream().sorted().toList().indexOf("spell-suicide-bomber");
        List<Long> result = new ArrayList<>();
        for (int cycle = 1; cycle <= 240; cycle++) {
            CommandApplier commands = new CommandApplier(world, new ArrayList<>(data.unitTypes().types().values()));
            data.configureCommands(commands);
            if (cycle == 5) assertTrue(commands.apply(GameCommand.cast(0, actorId, target.id(), ability)),
                    "the sapper must retain its target through the command path");
            if (cycle == 30 && cancel) assertTrue(commands.apply(GameCommand.move(0, actorId, 4, 14)),
                    "the later Move must cancel the demolition");
            world.tick();
            world.recalculateSupply();
            if (cycle == checkpoint) {
                java.io.StringWriter saved = new java.io.StringWriter();
                net.chonkbase.chonkcraft.engine.save.SaveGame.write(world, "demolition-referee", "orc", 1, saved);
                world = plainWorld(data);
                net.chonkbase.chonkcraft.engine.save.LoadGame.apply(world, saved.toString(), data.unitTypes().types());
            }
            result.add(net.chonkbase.chonkcraft.engine.network.SyncHash.of(world));
        }
        Unit restored = world.units().stream().filter(u -> u.id() == actorId).findFirst().orElse(null);
        assertEquals(cancel, restored != null && restored.isAlive(),
                "only the cancelled demolition leaves its sapper alive");
        return result;
    }

    private static World plainWorld(GameData data) {
        var map = new net.chonkbase.chonkcraft.engine.map.GameMap(32, 32,
                new net.chonkbase.chonkcraft.engine.map.Tileset());
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
            map.field(x, y).setFlags(net.chonkbase.chonkcraft.engine.map.TileFlag.LAND_ALLOWED);
        }
        World world = new World(map);
        data.configureWorld(world, net.chonkbase.chonkcraft.data.map.PudMap.Tileset.FOREST);
        return world;
    }

    private static final class Campaign {
        final Mission mission;
        final int person;
        final CommandApplier commands;
        final Unit actor;
        final int ability;

        Campaign(boolean orc) {
            AssetSource assets = AssetSource.fromEnvironment();
            Assumptions.assumeTrue(assets != null, "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
            GameData data = new GameData(assets);
            String map = orc ? "campaigns/orc/level10o" : "campaigns/human/level12h";
            person = GameData.personIn(data.campaignMap(map));
            mission = data.loadMission(map, person, 1);
            assertNotNull(mission, "the authenticated demolition scenario must load");
            actor = at(orc ? 78 : 33, orc ? 86 : 113);
            commands = new CommandApplier(mission.world(), new ArrayList<>(data.unitTypes().types().values()));
            data.configureCommands(commands);
            ability = data.spells().spells().all().keySet().stream().sorted().toList().indexOf("spell-suicide-bomber");
            assertTrue(ability >= 0, "Demolish must be in the shared command catalog");
            mission.tick();
            mission.tick();
        }

        Unit at(int x, int y) {
            var units = mission.world().units().stream()
                    .filter(u -> u.player() == person && u.tileX() == x && u.tileY() == y).toList();
            assertEquals(1, units.size(), "the native scenario must have one actor at " + x + "," + y);
            return units.getFirst();
        }

        void point(int x, int y) {
            assertTrue(commands.apply(GameCommand.castAt(person, actor.id(), x, y, ability)),
                    "the demolition squad must accept the player's ground target");
        }

        void target(Unit target) {
            assertTrue(commands.apply(GameCommand.cast(person, actor.id(), target.id(), ability)),
                    "the demolition squad must accept the player's live target");
        }

        void position(int x, int y, int px, int py) {
            assertEquals(List.of(x, y, px, py), List.of(actor.tileX(), actor.tileY(), actor.pixelX(), actor.pixelY()),
                    "the demolition squad's reserved tile and visible position must match retail BNE");
        }
    }
}
