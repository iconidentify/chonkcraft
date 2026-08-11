package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.ai.AiForce;
import net.chonkbase.chonkcraft.engine.ai.AiPlayer;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The game the menus never touched plays at map-default difficulty.
 *
 * <p>{@code GameSettings.Difficulty} is {@code SettingsPresetMapDefault},
 * minus one, in every game the campaign menu has not written it
 * ({@code settings.h:75}, {@code Settings::Init}) -- and the value is wired
 * into the shipped scripts, not just read: {@code scripts/ai.legacy-declaration} wraps
 * {@code AiForce}, {@code AiSleep} and {@code AiCalc} in difficulty
 * arithmetic, and its force wrapper begins by swallowing
 * {@code AiForce(0...)} whole at any difficulty but minus one. A port
 * that said 3 -- a sensible-looking middle -- silently unplugged every
 * personality that assembles its army in force nought: on
 * campaigns/human-exp/levelx09h the sixth slot's script fell through its
 * vanished AiForce call into {@code AiSleep(65000)} and never trained the
 * grunt and axethrower upstream pays for at cycle 13.
 */
class ScriptDifficultyRealDataTest {

    private static GameData load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    @Test
    @DisplayName("a personality's force nought reaches the engine with its counts unscaled")
    void aForceNoughtDeclarationReachesTheEngineUnscaled() {
        Mission mission = load().loadMission("campaigns/human-exp/levelx09h");
        Assumptions.assumeTrue(mission != null, "the mission did not load");
        World world = mission.world();

        // Through the sixth slot's first thought at cycle 13, where its
        // script reads all the way down to AiForce(0, {AiSoldier(), 4...}).
        for (int cycle = 0; cycle < 14; cycle++) {
            mission.tick();
        }

        AiPlayer ai = world.ais().get(6);
        Assumptions.assumeTrue(ai != null, "slot six has no AI; the fixture changed");
        AiForce force = null;
        for (AiForce candidate : ai.forces()) {
            if (candidate.index() == 0) {
                force = candidate;
            }
        }
        assertTrue(force != null && !force.wanted().isEmpty(),
                "AiForce(0, {...}) never reached the engine: ai.legacy-declaration's difficulty"
                        + " wrapper swallows force nought at any difficulty but the"
                        + " map-default minus one, and the script falls through into"
                        + " AiSleep(65000) -- the slot plays dead for the mission");
        assertEquals(4, (int) force.wantedByIdent().getOrDefault("unit-grunt", 0),
                "and the soldier count must arrive unscaled: the wrapper adds"
                        + " Difficulty - 3 to every want at the difficulties the menu"
                        + " can pick, and minus one adds nothing");
    }
}
