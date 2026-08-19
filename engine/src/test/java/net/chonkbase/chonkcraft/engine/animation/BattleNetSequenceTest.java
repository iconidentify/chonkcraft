package net.chonkbase.chonkcraft.engine.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BattleNetSequenceTest {

    @Test
    void followsStillMarkersWaitAndLoopFromTheTypeTable() {
        byte[] program = new byte[64];
        putWord(program, 0, 16); // unit type zero's animation-offset table
        putWord(program, 20, 32); // animation two (Still)

        program[32] = 4; // set frame zero
        program[33] = 0;
        program[34] = 0; // action
        program[35] = 0; // adjacent action
        program[36] = 3; // jump to common tail
        putWord(program, 37, 48);
        program[48] = 0; // common action
        program[49] = 1; // wait four
        program[50] = 4;
        program[51] = 3;
        putWord(program, 52, 48);

        BattleNetSequence sequence = new BattleNetSequence(program);
        assertTrue(sequence.usable());
        assertEquals(32, sequence.sequenceStart(0, BattleNetSequence.STILL_ANIMATION));

        BattleNetSequence.Tick delayed = sequence.tick(32, 2);
        assertEquals(new BattleNetSequence.Tick(32, 1, false, false, true), delayed);

        BattleNetSequence.Tick first = sequence.tick(delayed.offset(), delayed.timer());
        assertEquals(new BattleNetSequence.Tick(
                35, 1, true, false, true, 0, false, 0), first);
        BattleNetSequence.Tick second = sequence.tick(first.offset(), first.timer());
        assertEquals(new BattleNetSequence.Tick(36, 1, true, false, true), second);
        BattleNetSequence.Tick common = sequence.tick(second.offset(), second.timer());
        assertEquals(new BattleNetSequence.Tick(49, 1, true, false, true), common);

        BattleNetSequence.Tick wait = sequence.tick(common.offset(), common.timer());
        assertEquals(new BattleNetSequence.Tick(51, 4, false, false, true), wait);
        assertEquals(3, sequence.quietTicksUntilActionMarker(
                wait.offset(), wait.timer()),
                "three quiet countdown visits remain after WAIT-4 is armed");
        assertEquals(0, sequence.quietTicksUntilActionMarker(48, 1),
                "a command already parked on OP0 pops this visit");
        wait = sequence.tick(wait.offset(), wait.timer());
        wait = sequence.tick(wait.offset(), wait.timer());
        wait = sequence.tick(wait.offset(), wait.timer());
        BattleNetSequence.Tick looped = sequence.tick(wait.offset(), wait.timer());
        assertEquals(new BattleNetSequence.Tick(49, 1, true, false, true), looped);
    }

    @Test
    void consumesMovementFrameAndEffectOpcodesAtTheirNativeWidths() {
        byte[] program = new byte[96];
        putWord(program, 0, 16);
        putWord(program, 20, 64);
        int at = 64;
        program[at++] = 4;
        program[at++] = 9;
        program[at++] = 5;
        program[at++] = 1;
        program[at++] = 6;
        program[at++] = 2;
        program[at++] = 9;
        program[at++] = 13;
        program[at++] = 3;
        program[at++] = 14;
        program[at++] = 4;
        program[at++] = 10;
        program[at++] = 11;
        program[at++] = 15;
        program[at++] = 7;
        program[at] = 6;

        BattleNetSequence.Tick tick = new BattleNetSequence(program).tick(64, 1);

        // op5 1 + op6 2 + op13 3 + op14 4 = 10 pixel-motion arguments
        assertEquals(new BattleNetSequence.Tick(
                at + 1, 6, false, false, true, 10, false, 10), tick);
    }

    @Test
    void opcodeTwelveIdentifiesItsInclusiveMovementDelay() {
        byte[] program = new byte[40];
        putWord(program, 0, 16);
        putWord(program, 20, 32);
        program[32] = 5;
        program[33] = 3;
        program[34] = 12;
        program[35] = 2;

        BattleNetSequence.Tick tick = new BattleNetSequence(program).tick(32, 1);

        assertEquals(new BattleNetSequence.Tick(36, 2, false, false, true, 3, true),
                tick);
    }

    @Test
    void preservesTheNativeUnsignedTimerWrapAndRejectsBadData() {
        byte[] program = new byte[32];
        putWord(program, 0, 16);
        putWord(program, 20, 24);
        program[24] = 2;

        BattleNetSequence sequence = new BattleNetSequence(program);
        BattleNetSequence.Tick stopped = sequence.tick(24, 1);
        assertEquals(new BattleNetSequence.Tick(25, 0, false, false, true), stopped);
        assertEquals(new BattleNetSequence.Tick(25, 255, false, false, true),
                sequence.tick(stopped.offset(), stopped.timer()));

        assertFalse(new BattleNetSequence(new byte[] {1}).usable());
        program[31] = 3;
        sequence = new BattleNetSequence(program);
        assertFalse(sequence.tick(31, 1).valid());
        assertEquals(-1, sequence.quietTicksUntilActionMarker(31, 1));
    }

    private static void putWord(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }
}
