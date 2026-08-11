package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The eighth human mission's peasant slot is not really a computer.
 *
 * <p>level08h's map calls slot four rescue-active and its setup file then
 * says {@code DefinePlayerTypes} with "computer" in that seat -- but
 * {@code CclDefinePlayerTypes} writes {@code Map.Info.PlayerType} and
 * nothing else, and the players were
 * created from that array before the setup script ran, so upstream's slot
 * keeps the type it was born with. Probed from the running binary: the
 * {@code GOALDROP} hook prints {@code ptype=7}, rescue-active, for the
 * slot's peasant at cycle 147. What makes it a war at all is the mission's
 * opening trigger, three {@code SetDiplomacy} calls fired by the game
 * loop's every-cycle trigger pass before any unit acts
 * and its AI runs regardless, because
 * upstream enables AI for computer and rescue-active alike
 *
 */
class RescueSlotRealDataTest {

    private static GameData load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    @Test
    @DisplayName("the peasant slot keeps its rescue-active birth type through the setup's retype")
    void thePeasantSlotStaysRescueActive() {
        Mission mission = load().loadMission("campaigns/human/level08h");
        Assumptions.assumeTrue(mission != null, "the mission did not load");
        World world = mission.world();

        assertEquals(PudMap.PlayerType.RESCUE_ACTIVE, world.player(4).type(),
                "the setup file's DefinePlayerTypes is a dead store upstream -- the"
                        + " players were created from the map's own types before it ran --"
                        + " and a slot retyped into a computer sees the whole map:"
                        + " IsVisibleAsGoal answers yes before the fog is consulted, and"
                        + " the drafted peasant never drops the unseen mine at 21,28");
        assertEquals(PudMap.PlayerType.RESCUE_ACTIVE, world.player(2).type(),
                "and the rescuable town at slot two is untouched");
    }

    @Test
    @DisplayName("the opening trigger has declared the siege's enemies before the first thought")
    void theOpeningTriggerDeclaresTheWarBeforeTheFirstThought() {
        Mission mission = load().loadMission("campaigns/human/level08h");
        Assumptions.assumeTrue(mission != null, "the mission did not load");
        World world = mission.world();

        mission.tick();

        assertTrue(world.isEnemyPlayer(4, 2),
                "SetDiplomacy(4, \"enemy\", 2) must be in force after one cycle: the"
                        + " game loop fires triggers before the units act, so the"
                        + " peasant slot's first thought at cycle 11 already hates the"
                        + " town it besieges");
        assertTrue(world.isEnemyPlayer(4, 6),
                "and SetDiplomacy(4, \"enemy\", 6) with it");
        assertTrue(world.isEnemyPlayer(4, 1),
                "the born enmity stands too: rescue-active hates the computers"
                        + " by type, player.cpp:734-739");

        // The war is real: the slot's AI launches the seven-peasant siege.
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND; cycle++) {
            mission.tick();
        }
        long marching = world.unitsSnapshot().stream()
                .filter(unit -> unit.player() == 4 && unit.isAlive()
                        && !unit.type().building()
                        && unit.order() != Unit.Order.STILL)
                .count();
        assertTrue(marching > 0,
                "a rescue-active slot runs its AI -- player.cpp:767-771 enables"
                        + " computer and rescue-active alike -- so the peasants must"
                        + " be under orders after the first thought");
    }
}
