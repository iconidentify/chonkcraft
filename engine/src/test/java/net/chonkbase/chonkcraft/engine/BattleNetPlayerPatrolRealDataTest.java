package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.save.LoadGame;
import net.chonkbase.chonkcraft.engine.save.SaveGame;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Player Patrol uses the original movement program and its acquisition markers. */
class BattleNetPlayerPatrolRealDataTest {

    @Test
    @DisplayName("a human patrol finishes its endpoint before acquiring an approaching enemy")
    void humanPatrolFinishesItsEndpointBeforeAcquiringAnApproachingEnemy() throws IOException {
        verifyPatrol(-1);
    }

    @Test
    @DisplayName("saving during a patrol fight preserves the return leg")
    void savingDuringAPatrolFightPreservesTheReturnLeg() throws IOException {
        for (int checkpoint : new int[] {10, 89, 92, 109, 120, 200, 346, 362, 389}) {
            verifyPatrol(checkpoint);
        }
    }

    @Test
    @DisplayName("a patrol joining a fight strikes when its committed chase finishes")
    void patrolJoiningAFightStrikesWhenItsCommittedChaseFinishes() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null, "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level01h";
        int person = GameData.personIn(data.campaignMap(map));
        Mission mission = data.loadMission(map, person, 1);
        assertNotNull(mission, "Human 1 must load");
        World world = mission.world();
        Unit first = world.units().stream().filter(unit -> unit.tileX() == 10
                && unit.tileY() == 13).findFirst().orElseThrow();
        Unit second = world.units().stream().filter(unit -> unit.tileX() == 17
                && unit.tileY() == 7).findFirst().orElseThrow();
        Unit grunt = world.units().stream().filter(unit -> unit.tileX() == 29
                && unit.tileY() == 14).findFirst().orElseThrow();
        CommandApplier commands = new CommandApplier(world,
                new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);
        mission.tick();
        mission.tick();
        // Pinned capture 32a437f7: both human footmen patrol toward 25,9.
        // The late arrival pays its first Attack callback on the final
        // chase pixel, without constructing an additional three-call wait.
        for (int cycle = 1; cycle <= 400; cycle++) {
            if (cycle == 5) {
                assertTrue(commands.apply(GameCommand.patrol(person, first.id(), 25, 9)),
                        "the first footman must accept Patrol");
                assertTrue(commands.apply(GameCommand.patrol(person, second.id(), 25, 9)),
                        "the second footman must accept Patrol");
            }
            mission.tick();
            if (cycle == 284) {
                assertEquals(2540, first.battleNetSequenceOffset(),
                        "the patrol's chase arrival must execute Attack OP0 immediately");
                assertEquals(1, first.battleNetAnimationTimer(), "the first swing must retain its native timer");
            }
            if (cycle == 294) {
                assertEquals(31, grunt.hitPoints(),
                        "the joining footman's first blow must land on the native cycle");
            }
        }
        assertEquals(60, first.hitPoints(), "the joining footman must remain unharmed");
        assertEquals(16, second.hitPoints(), "the complete group fight must preserve native damage");
        assertEquals(Unit.Order.PATROL, first.order(), "the joining footman must resume Patrol");
        assertEquals(Unit.Order.PATROL, second.order(), "the first combatant must resume Patrol");
        assertEquals(26, first.tileX(), "the resumed patrol must retain its native column");
        assertEquals(9, first.tileY(), "the resumed patrol must retain its native row");
    }

    private static void verifyPatrol(int resumeCycle) throws IOException {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null, "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level01h";
        int person = GameData.personIn(data.campaignMap(map));
        Mission mission = data.loadMission(map, person, 1);
        assertNotNull(mission, "Human 1 must load");
        World world = mission.world();
        Unit footman = world.units().stream().filter(unit -> unit.player() == person
                && unit.tileX() == 21 && unit.tileY() == 5).findFirst().orElseThrow();
        CommandApplier commands = new CommandApplier(world,
                new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);
        mission.tick();
        mission.tick();
        // Pinned 2.02b: Human 1 slot 1598, Patrol at cycle 5 to 25,9.
        // Native finishes the outbound pixels at 89, banks Attack at 92,
        // and retains Patrol for the next committed stride.
        for (int cycle = 1; cycle <= 400; cycle++) {
            if (cycle == 5) {
                assertTrue(commands.apply(GameCommand.patrol(person, footman.id(), 25, 9)),
                        "the human's Patrol click must be accepted");
            }
            mission.tick();
            if (cycle == resumeCycle) {
                int x = footman.tileX();
                int y = footman.tileY();
                StringWriter checkpoint = new StringWriter();
                SaveGame.writeWithTriggers(world, map, "human", 1,
                        mission.triggers().savedState(), checkpoint);
                mission = data.loadMission(map, person, 1);
                world = mission.world();
                for (Unit initial : new ArrayList<>(world.units())) {
                    world.remove(initial);
                }
                LoadGame.apply(world, checkpoint.toString(), data.unitTypes().types());
                mission.triggers().restoreState(LoadGame.triggerState(checkpoint.toString()));
                // The save loader remaps unit ids when rebuilding a populated
                // campaign. Pair the restored actor by its unique saved square.
                footman = world.units().stream().filter(unit -> unit.player() == person
                                && unit.tileX() == x && unit.tileY() == y)
                        .findFirst().orElseThrow();
                if (footman.savedOrder() == Unit.Order.PATROL) {
                    assertEquals(21, footman.savedOrderGoalX(),
                            "the saved patrol must retain its destination through load");
                    assertEquals(5, footman.savedOrderGoalY(),
                            "the saved patrol must retain its destination row through load");
                }
            }
            if (cycle == 89) {
                assertEquals(800, footman.tileX() * 32 + footman.offsetX(),
                        "BNE finishes the last eastward pixel before scanning");
                assertEquals(288, footman.tileY() * 32 + footman.offsetY(),
                        "BNE finishes the last southward pixel before scanning");
                assertEquals(Unit.Order.PATROL, footman.order(),
                        "the endpoint still belongs to Patrol");
                assertEquals(2477, footman.battleNetSequenceOffset(),
                        "the endpoint restarts the footman's Still constructor");
                assertEquals(3, footman.battleNetAnimationTimer(),
                        "the new Patrol leg starts with the native three-call timer");
            }
            if (cycle == 92) {
                assertEquals(Unit.Order.PATROL, footman.order(),
                        "the acquired Attack must wait for the committed Patrol step");
                assertNotNull(footman.pendingAttack(), "the native marker must bank its quarry");
            }
        }
        assertTrue(footman.isAlive(), "BNE's footman survives this patrol encounter");
        assertEquals(1, footman.hitPoints(), "the complete patrol fight must preserve native damage after checkpoint " + resumeCycle);
        assertEquals(Unit.Order.PATROL, footman.order(), "the patrol must resume after the fight");
        assertEquals(24, footman.tileX(), "the restored patrol must return toward its original endpoint");
        assertEquals(7, footman.tileY(), "the restored patrol must retain its original row goal");
        assertEquals(247, footman.tileY() * 32 + footman.offsetY(),
                "the return leg must retain the native constructor and pixel timing");
    }
}
