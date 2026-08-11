package net.chonkbase.chonkcraft.engine.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Retail-machine tests for the four-opcode {@code Rez/ai.bin} interpreter. */
class BattleNetAiBytecodeTest {

    @Test
    void installStopsOnAWaitWhoseLowByteIsZero() {
        byte[] ai = new byte[26];
        byte[] state = new byte[BattleNetAiBytecode.STATE_BYTES];
        ai[0] = 8; // Profile-zero record starts at file offset eight.
        int pc = 12; // Two profile words precede the bytecode.
        ai[pc++] = 2; // WAIT 256
        ai[pc++] = 0;
        ai[pc++] = 1;
        ai[pc++] = 0;
        ai[pc++] = 0;
        ai[pc++] = 0; // SET wanted-basic = 9; must not run during install.
        ai[pc++] = BattleNetAiBytecode.OFF_WANTED_BASIC;
        ai[pc++] = 9;
        ai[pc++] = 2; // A later wait makes the old low-byte loop terminate.
        ai[pc++] = 1;

        int next = BattleNetAiBytecode.install(ai, 0, state);

        assertEquals(17, next);
        assertEquals(256, BattleNetAiBytecode.waitCounter(state));
        assertEquals(0, state[BattleNetAiBytecode.OFF_WANTED_BASIC] & 0xff);
    }

    @Test
    void tickDecrementsTheWholeRetailWaitDwordBeforeDispatching() {
        byte[] ai = {
            0, (byte) BattleNetAiBytecode.OFF_WANTED_BASIC, 9
        };
        byte[] state = new byte[BattleNetAiBytecode.STATE_BYTES];
        state[1] = 1; // 0x00000100; its low byte is deliberately zero.

        int next = BattleNetAiBytecode.tick(ai, state, 0, (predicate, bytes) -> true);

        assertEquals(0, next);
        assertEquals(255, BattleNetAiBytecode.waitCounter(state));
        assertEquals(0, state[BattleNetAiBytecode.OFF_WANTED_BASIC] & 0xff);
    }

    @Test
    void satisfiedPredicateStillYieldsForOneRetailTick() {
        byte[] ai = {
            3, 7,
            0, (byte) BattleNetAiBytecode.OFF_WANTED_BASIC, 9
        };
        byte[] state = new byte[BattleNetAiBytecode.STATE_BYTES];

        int next = BattleNetAiBytecode.tick(ai, state, 0, (predicate, bytes) -> true);

        assertEquals(2, next);
        assertEquals(1, BattleNetAiBytecode.waitCounter(state));
        assertEquals(0, state[BattleNetAiBytecode.OFF_WANTED_BASIC] & 0xff);
    }
}
