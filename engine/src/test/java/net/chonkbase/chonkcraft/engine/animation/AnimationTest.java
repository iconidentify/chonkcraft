package net.chonkbase.chonkcraft.engine.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import net.chonkbase.chonkcraft.engine.unit.SpriteFrame;
import org.junit.jupiter.api.Test;

/** Tests for animation parsing, stepping, and the heading-to-frame mapping. */
class AnimationTest {

    private final AnimationRunner runner = new AnimationRunner(new Random(0));

    private static AnimationState running(String... lines) {
        AnimationState state = new AnimationState();
        state.switchTo(Animation.parse("test", List.of(lines)));
        return state;
    }

    // --------------------------------------------------------------- parsing

    @Test
    void parsesTheInstructionVocabulary() {
        Animation animation = Animation.parse("test", List.of(
                "unbreakable begin", "frame 25", "wait 3", "move 2",
                "attack", "sound footman-attack", "label loop",
                "random-goto 99 loop", "unbreakable end",
                "spawn-unit unit-revealer 0 0 0 l.this",
                "set-var SightRange.Max = 1", "die"));

        assertEquals(Animation.Kind.UNBREAKABLE_BEGIN, animation.at(0).kind());
        assertEquals(Animation.Kind.FRAME, animation.at(1).kind());
        assertEquals(25, animation.at(1).value());
        assertEquals(Animation.Kind.WAIT, animation.at(2).kind());
        assertEquals(3, animation.at(2).value());
        assertEquals(Animation.Kind.MOVE, animation.at(3).kind());
        assertEquals(Animation.Kind.ATTACK, animation.at(4).kind());
        assertEquals("footman-attack", animation.at(5).operand());
        assertEquals(Animation.Kind.LABEL, animation.at(6).kind());
        assertEquals(Animation.Kind.RANDOM_GOTO, animation.at(7).kind());
        assertEquals(99, animation.at(7).value());
        assertEquals("loop", animation.at(7).operand());
        assertEquals(Animation.Kind.UNBREAKABLE_END, animation.at(8).kind());
        assertEquals(Animation.Kind.SPAWN_UNIT, animation.at(9).kind());
        assertEquals("unit-revealer 0 0 0 l.this", animation.at(9).operand());
        assertEquals(Animation.Kind.SET_VAR, animation.at(10).kind());
        assertEquals(Animation.Kind.DIE, animation.at(11).kind());
    }

    @Test
    void anUnknownInstructionParsesRatherThanThrowing() {
        // The data contains instructions this implementation has no use for yet; they
        // must not stop a unit from loading. Wiggle used to be the example
        // here, until the ships' bob at anchor turned out to feed the walk's
        // arithmetic and it grew a reader.
        Animation animation = Animation.parse("test",
                List.of("luminesce 3", "frame 0", "wait 1"));
        assertEquals(Animation.Kind.OTHER, animation.at(0).kind());
        assertEquals(3, animation.size());
    }

    @Test
    void findsLabels() {
        Animation animation = Animation.parse("test", List.of("frame 0", "label here", "wait 1"));
        assertEquals(1, animation.labelIndex("here"));
        assertEquals(-1, animation.labelIndex("elsewhere"));
    }

    // -------------------------------------------------------------- stepping

    @Test
    void runsInstructionsUntilAWait() {
        // One cycle sets the frame, banks the movement, and stops on the wait.
        AnimationState state = running("frame 5", "move 3", "wait 1", "frame 10", "move 2", "wait 1");

        AnimationRunner.Step step = runner.step(state, 1, 0);
        assertEquals(5, step.frame());
        assertEquals(3, step.move());
    }

    @Test
    void aWaitSpansSeveralCycles() {
        AnimationState state = running("frame 5", "wait 3", "frame 10", "wait 1");

        // The first cycle runs up to the wait and consumes one of its three.
        assertEquals(5, runner.step(state, 1, 0).frame());
        // The next two cycles do nothing but count down.
        assertEquals(0, runner.step(state, 1, 5).move());
        assertEquals(0, runner.step(state, 1, 5).move());
        // Then the animation moves on.
        assertEquals(10, runner.step(state, 1, 5).frame());
    }

    @Test
    void movementAccumulatesAcrossACycle() {
        // Several move instructions before a single wait all count.
        AnimationState state = running("move 2", "move 3", "wait 1");
        assertEquals(5, runner.step(state, 1, 0).move());
    }

    @Test
    void terrainCostScalesMovement() {
        AnimationState state = running("move 3", "wait 1");
        assertEquals(6, runner.step(state, 2, 0).move(), "a scale of two should double the step");
    }

    @Test
    void theProgramLoops() {
        AnimationState state = running("frame 0", "wait 1", "frame 5", "wait 1");

        assertEquals(0, runner.step(state, 1, 0).frame());
        assertEquals(5, runner.step(state, 1, 0).frame());
        // Back to the top.
        assertEquals(0, runner.step(state, 1, 5).frame());
    }

    @Test
    void reportsAnAttackAndASound() {
        AnimationState state = running("frame 40", "attack", "sound footman-attack", "wait 5");
        AnimationRunner.Step step = runner.step(state, 1, 0);

        assertTrue(step.attacked());
        assertEquals("footman-attack", step.sound());
        assertEquals(40, step.frame());
    }

    @Test
    void tracksUnbreakableStretches() {
        AnimationState state = running("unbreakable begin", "frame 25", "wait 1",
                "frame 0", "unbreakable end", "wait 1");

        runner.step(state, 1, 0);
        assertTrue(state.unbreakable(), "should be inside the swing");
        runner.step(state, 1, 25);
        assertFalse(state.unbreakable(), "the swing should have finished");
    }

    @Test
    void anAnimationWithNoWaitDoesNotHangTheSimulation() {
        // A malformed script must not spin forever.
        AnimationState state = running("frame 0", "frame 5");
        AnimationRunner.Step step = runner.step(state, 1, 0);
        assertEquals(0, step.move());
    }

    @Test
    void bneProfileIgnoresChonkCraftRandomIdleInstructionsWithoutSkippingFrames() {
        AnimationRunner bne = new AnimationRunner(new Random(0));
        bne.setRandomInstructionsEnabled(false);
        AnimationState state = running(
                "random-goto 100 skipped", "frame 5", "random-rotate 1",
                "label skipped", "wait 1");

        AnimationRunner.Step step = bne.step(state, 1, 0);

        assertEquals(5, step.frame());
        assertTrue(step.effects().isEmpty());
    }

    @Test
    void switchingAnimationsRestarts() {
        AnimationState state = running("frame 0", "wait 5");
        runner.step(state, 1, 0);
        assertTrue(state.waitCycles() > 0);

        assertTrue(state.switchTo(Animation.parse("other", List.of("frame 9", "wait 1"))));
        assertEquals(0, state.index());
        assertEquals(0, state.waitCycles());
        // Switching to the same animation is not a restart.
        assertFalse(state.switchTo(state.current()));
    }

    @Test
    void waitingStillPreservesTheCommittedAnimationsGuard() {
        AnimationState state = running("unbreakable begin", "frame 25", "wait 3",
                "unbreakable end", "wait 1");
        Animation committed = state.current();
        runner.step(state, 1, 0);
        assertTrue(state.unbreakable(), "the fixture never entered its committed span");

        state.beginWait();
        assertTrue(state.switchTo(Animation.parse("still", List.of("frame 0", "wait 1"))));
        assertTrue(state.unbreakable(),
                "UnitShowAnimation cleared Anim.Unbreakable while IsWaiting played Still");

        state.endWait();
        assertEquals(committed, state.current());
        assertTrue(state.unbreakable(), "StopWaiting did not restore the committed guard");
    }

    // ----------------------------------------------------------- sprite frame

    @Test
    void theFiveStoredFacingsCoverEightHeadings() {
        // Warcraft II stores north, north-east, east, south-east and south.
        assertEquals(5, SpriteFrame.storedFacings(8));

        assertEquals(new SpriteFrame.Resolved(0, false), SpriteFrame.resolve(0, 0, 8)); // north
        assertEquals(new SpriteFrame.Resolved(1, false), SpriteFrame.resolve(0, 1, 8)); // north-east
        assertEquals(new SpriteFrame.Resolved(2, false), SpriteFrame.resolve(0, 2, 8)); // east
        assertEquals(new SpriteFrame.Resolved(3, false), SpriteFrame.resolve(0, 3, 8)); // south-east
        assertEquals(new SpriteFrame.Resolved(4, false), SpriteFrame.resolve(0, 4, 8)); // south
    }

    @Test
    void theWesternFacingsMirrorTheEasternOnes() {
        // South-west is south-east flipped, west is east flipped, and so on.
        assertEquals(new SpriteFrame.Resolved(3, true), SpriteFrame.resolve(0, 5, 8));
        assertEquals(new SpriteFrame.Resolved(2, true), SpriteFrame.resolve(0, 6, 8));
        assertEquals(new SpriteFrame.Resolved(1, true), SpriteFrame.resolve(0, 7, 8));
    }

    @Test
    void theAnimationFrameSelectsTheSheetRow() {
        // A sheet row holds all five facings of one animation step, which is
        // why the scripts advance frames in multiples of five.
        assertEquals(new SpriteFrame.Resolved(5, false), SpriteFrame.resolve(5, 0, 8));
        assertEquals(new SpriteFrame.Resolved(7, false), SpriteFrame.resolve(5, 2, 8));
        assertEquals(new SpriteFrame.Resolved(8, true), SpriteFrame.resolve(5, 5, 8));
        assertEquals(new SpriteFrame.Resolved(25, false), SpriteFrame.resolve(25, 0, 8));
    }

    @Test
    void aFrameInTheMiddleOfARowSnapsToItsStart() {
        // Feeding an already-resolved index back in must not drift.
        assertEquals(new SpriteFrame.Resolved(5, false), SpriteFrame.resolve(7, 0, 8));
    }
}
