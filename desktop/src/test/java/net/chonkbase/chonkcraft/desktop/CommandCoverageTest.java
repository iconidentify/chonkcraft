package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.chonkbase.chonkcraft.engine.generated.GeneratedButtons;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every command button does something.
 *
 * <p>The panel drew 214 buttons long before it could carry out what a fifth of
 * them promised. Patrol, repair, explore, stand-ground, unload, attack-ground
 * and return-goods all passed their availability checks, drew their icon, and
 * set a status line when pressed.
 *
 * <p>This needs no installation: the button definitions are compiled in, so
 * the check runs anywhere and fails the moment a new action appears in the
 * scripts that nothing here handles.
 */
class CommandCoverageTest {

    /**
     * Every action a shipped button carries must have a branch that carries it
     * out.
     *
     * <p>This used to ask only whether the action string appeared in a set of
     * names, and it passed for a year while twenty-three spell buttons did
     * nothing at all: "cast-spell" was in the set and had no case in the
     * switch. A list of names is not a behaviour, and a test that checks the
     * list certifies the list.
     *
     * <p>So it now reads the switch itself. Crude -- it is looking at source
     * text -- but it is checking the thing that actually decides what happens,
     * and it fails the moment somebody adds a name to the set without adding
     * the branch.
     */
    @Test
    @DisplayName("every button action has a branch that carries it out")
    void everyButtonActs() {
        String source;
        try {
            source = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "src/main/java/net/chonkbase/chonkcraft/desktop/GameScreen.java"));
        } catch (java.io.IOException unreadable) {
            throw new AssertionError("cannot read GameScreen to check its branches", unreadable);
        }

        Map<String, Integer> unhandled = new TreeMap<>();
        for (GeneratedButtons.Row row : GeneratedButtons.ROWS) {
            String action = row.action();
            if (!GameScreen.handles(action)) {
                unhandled.merge(action, 1, Integer::sum);
                continue;
            }
            // "button" is the page switcher and is handled before the switch
            // is reached, by switchesToLevel(). It is the one action that
            // legitimately has no case, and it is named here rather than
            // skipped by a wildcard so that a second such action cannot creep
            // in unnoticed.
            if ("button".equals(action)) {
                continue;
            }
            // A targeted action is carried out when the target is clicked; an
            // immediate one when the icon is. Either way there must be a
            // `case "name"` somewhere that does it.
            if (!source.contains("case \"" + action + "\"")) {
                unhandled.merge(action + " (declared handled, no case)", 1, Integer::sum);
            }
        }
        assertTrue(unhandled.isEmpty(),
                "these button actions do nothing when pressed: " + unhandled);
    }

    @Test
    @DisplayName("the whole panel is covered, and the count has not shrunk")
    void everyButtonIsAccountedFor() {
        // 214 rather than 232: the two race button files declare nine buttons
        // each behind `if (chonkcraft.extensions)`, and this implementation sets that flag
        // itself and sets it false -- see GameData.EXTENDED_FEATURES. Warcraft
        // II has 214; ChonkCraft with its extra features on loads 232. The number
        // has been 214 before for a quite different reason, the prelude
        // stopping at line 142 of scripts/legacyEngine.legacy-declaration and never reaching the
        // assignment on line 162, so check which it is before moving it.
        assertEquals(214, GeneratedButtons.ROWS.size(),
                "the shipped scripts define 214 buttons");
        int acted = 0;
        for (GeneratedButtons.Row row : GeneratedButtons.ROWS) {
            if (GameScreen.handles(row.action())) {
                acted++;
            }
        }
        // Against the roster's own size rather than against a second copy of
        // the number. What this test is called is "every button is accounted
        // for", and that is a statement about all of them, whatever there are;
        // written as a literal it was really two assertions of the count and
        // it went red twice when the count moved once.
        assertEquals(GeneratedButtons.ROWS.size(), acted,
                "of " + GeneratedButtons.ROWS.size() + " buttons the command"
                        + " panel draws, " + acted + " have an action GameScreen"
                        + " handles; the rest do nothing when pressed");
    }

    @Test
    @DisplayName("the two sets do not overlap")
    void anActionIsEitherImmediateOrTargeted() {
        // A command that is both would be armed and carried out at once, and
        // the arming would win silently.
        List<String> both = new ArrayList<>(GameScreen.TARGETED_ACTIONS);
        both.retainAll(GameScreen.IMMEDIATE_ACTIONS);
        assertTrue(both.isEmpty(), "actions in both sets: " + both);

        // The conditional set decides for itself which of the two it is at the
        // moment of the press, so it must not appear in either.
        List<String> fixed = new ArrayList<>(GameScreen.TARGETED_ACTIONS);
        fixed.addAll(GameScreen.IMMEDIATE_ACTIONS);
        List<String> conditional = new ArrayList<>(GameScreen.CONDITIONAL_ACTIONS);
        conditional.retainAll(fixed);
        assertTrue(conditional.isEmpty(), "conditional actions also fixed: " + conditional);
    }

    @Test
    @DisplayName("nothing is claimed that the scripts never ask for")
    void nothingIsHandledInVain() {
        // A handler for an action no button carries is dead code pretending to
        // be coverage.
        List<String> declared = new ArrayList<>();
        for (GeneratedButtons.Row row : GeneratedButtons.ROWS) {
            if (!declared.contains(row.action())) {
                declared.add(row.action());
            }
        }
        List<String> claimed = new ArrayList<>(GameScreen.TARGETED_ACTIONS);
        claimed.addAll(GameScreen.IMMEDIATE_ACTIONS);
        claimed.addAll(GameScreen.CONDITIONAL_ACTIONS);
        claimed.removeAll(declared);
        assertTrue(claimed.isEmpty(), "handled but never used by any button: " + claimed);
    }
}
