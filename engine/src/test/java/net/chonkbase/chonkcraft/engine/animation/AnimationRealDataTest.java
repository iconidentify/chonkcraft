package net.chonkbase.chonkcraft.engine.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Loads the real animation definitions.
 *
 * <p>These decide how units look and how fast they walk, since a move
 * animation's {@code move} instructions are the unit's speed.
 */
class AnimationRealDataTest {

    private static GameData gameData() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    @Test
    @DisplayName("the scripts define an animation set for the whole roster")
    void theScriptsDefineAnimationsForTheRoster() {
        GameData data = gameData();
        assertTrue(data.animationSets().sets().size() >= 50,
                "expected the full animation bank, got " + data.animationSets().sets().size());

        List<String> unresolved = new ArrayList<>();
        for (UnitType type : data.unitTypes().types().values()) {
            if (type.animations().isEmpty()) {
                continue;
            }
            if (type.animationSet() == null) {
                unresolved.add(type.ident() + " -> " + type.animations());
            }
        }
        assertEquals(List.of(), unresolved,
                unresolved.size() + " unit types name an animation set that does not exist");
    }

    @Test
    @DisplayName("the footman's animations are the ones the script declares")
    void theFootmansAnimationsMatchTheScript() {
        GameData data = gameData();
        UnitType footman = data.unitTypes().types().get("unit-footman");
        Assumptions.assumeTrue(footman != null, "unit-footman not defined");

        AnimationSet set = footman.animationSet();
        assertTrue(set != null, "the footman has no animation set");
        assertTrue(set.get(AnimationSet.State.STILL) != null, "no still animation");
        assertTrue(set.get(AnimationSet.State.MOVE) != null, "no move animation");
        assertTrue(set.get(AnimationSet.State.ATTACK) != null, "no attack animation");
        assertTrue(set.get(AnimationSet.State.DEATH) != null, "no death animation");

        // The attack swing lands a blow and is unbreakable, straight from
        // scripts/human/anim.legacy-declaration.
        Animation attack = set.get(AnimationSet.State.ATTACK);
        assertTrue(attack.instructions().stream()
                        .anyMatch(i -> i.kind() == Animation.Kind.ATTACK),
                "the attack animation never lands a blow");
        assertTrue(attack.instructions().stream()
                        .anyMatch(i -> i.kind() == Animation.Kind.UNBREAKABLE_BEGIN),
                "the attack swing should be unbreakable");
    }

    @Test
    @DisplayName("a move animation covers exactly one tile per lap")
    void aMoveAnimationCoversExactlyOneTile() {
        GameData data = gameData();

        // This is the invariant that ties animation to movement: one lap of a
        // unit's move animation advances it by one tile, 32 pixels. If it did
        // not, sprites would slide relative to the ground.
        for (String ident : List.of("unit-footman", "unit-grunt", "unit-peasant", "unit-knight")) {
            UnitType type = data.unitTypes().types().get(ident);
            if (type == null || type.animationSet() == null) {
                continue;
            }
            Animation move = type.animationSet().get(AnimationSet.State.MOVE);
            if (move == null) {
                continue;
            }
            int total = move.instructions().stream()
                    .filter(i -> i.kind() == Animation.Kind.MOVE)
                    .mapToInt(Animation.Instruction::value)
                    .sum();
            assertEquals(32, total,
                    ident + "'s move animation advances " + total + " pixels, not one tile");
        }
    }

    @Test
    @DisplayName("stepping a real move animation yields movement and frames")
    void steppingARealMoveAnimationWorks() {
        GameData data = gameData();
        UnitType footman = data.unitTypes().types().get("unit-footman");
        Assumptions.assumeTrue(footman != null && footman.animationSet() != null, "no footman");

        AnimationState state = new AnimationState();
        state.switchTo(footman.animationSet().get(AnimationSet.State.MOVE));
        AnimationRunner runner = new AnimationRunner(new Random(0));

        int travelled = 0;
        java.util.Set<Integer> framesSeen = new java.util.TreeSet<>();
        int frame = 0;
        for (int cycle = 0; cycle < 200 && travelled < 32; cycle++) {
            AnimationRunner.Step step = runner.step(state, 1, frame);
            frame = step.frame();
            travelled += step.move();
            framesSeen.add(frame);
        }

        assertEquals(32, travelled, "one lap should cover exactly one tile");
        assertTrue(framesSeen.size() > 1,
                "a walk cycle should show more than one frame, saw " + framesSeen);
        // Frames name sheet rows, so they come in multiples of the five stored
        // facings.
        for (int seen : framesSeen) {
            assertEquals(0, seen % 5, "frame " + seen + " is not the start of a sheet row");
        }
    }
}
