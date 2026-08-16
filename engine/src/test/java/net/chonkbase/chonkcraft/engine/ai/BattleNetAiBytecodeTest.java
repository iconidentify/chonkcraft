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
        assertEquals(1, state[BattleNetAiBytecode.OFF_COMPUTER_ARMED] & 0xff,
                "0x428160 skips the builder scan when +0x0c is left at the SET 0");
    }

    @Test
    void installArmsTheComputerLatchAfterBytecodeStoresZero() {
        byte[] ai = new byte[20];
        byte[] state = new byte[BattleNetAiBytecode.STATE_BYTES];
        ai[0] = 8;
        int pc = 12;
        ai[pc++] = 0;
        ai[pc++] = (byte) BattleNetAiBytecode.OFF_COMPUTER_ARMED;
        ai[pc++] = 0;
        ai[pc++] = 2;
        ai[pc++] = 1;
        ai[pc++] = 0;
        ai[pc++] = 0;
        ai[pc++] = 0;

        BattleNetAiBytecode.install(ai, 0, state);

        assertEquals(1, state[BattleNetAiBytecode.OFF_COMPUTER_ARMED] & 0xff,
                "a profile that SET +0x0c=0 is still armed for 0x428160");
        assertEquals(1, BattleNetAiBytecode.waitCounter(state));
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

    @Test
    void installEmptyBuildBoundsWritesTheInvertedNativeBox() {
        byte[] state = new byte[BattleNetAiBytecode.STATE_BYTES];
        BattleNetAiBytecode.installEmptyBuildBounds(state, 32);
        assertEquals(32, state[BattleNetAiBytecode.OFF_BOUND_MIN_Y] & 0xff);
        assertEquals(0xff, state[BattleNetAiBytecode.OFF_BOUND_MAX_X] & 0xff);
        assertEquals(0xff, state[BattleNetAiBytecode.OFF_BOUND_MAX_Y] & 0xff);
        assertEquals(32, state[BattleNetAiBytecode.OFF_BOUND_MIN_X] & 0xff);
    }

    @Test
    void expandLandBuildBoundsPadsADecreasingYWalk() {
        byte[] state = new byte[BattleNetAiBytecode.STATE_BYTES];
        java.util.List<int[]> newestFirst = java.util.List.of(
                new int[] {18, 31},
                new int[] {10, 23},
                new int[] {18, 19},
                new int[] {5, 11},
                new int[] {8, 2});
        BattleNetAiBytecode.expandLandBuildBounds(state, 96, newestFirst);
        assertEquals(0, state[BattleNetAiBytecode.OFF_BOUND_MIN_Y] & 0xff);
        assertEquals(26, state[BattleNetAiBytecode.OFF_BOUND_MAX_X] & 0xff);
        assertEquals(7, state[BattleNetAiBytecode.OFF_BOUND_MAX_Y] & 0xff);
        assertEquals(0, state[BattleNetAiBytecode.OFF_BOUND_MIN_X] & 0xff);
    }

    @Test
    void expandLandBuildBoundsOnA128TileMapPadsAnUnexpandedMinTo123() {
        byte[] state = new byte[BattleNetAiBytecode.STATE_BYTES];
        BattleNetAiBytecode.expandLandBuildBounds(
                state, 128, java.util.List.of(new int[] {46, 109}));
        assertEquals(0x7b, state[BattleNetAiBytecode.OFF_BOUND_MIN_Y] & 0xff,
                "a 128-tile town wraps the unused min of 128 minus 5 to 123");
        assertEquals(54, state[BattleNetAiBytecode.OFF_BOUND_MAX_X] & 0xff,
                "the 128-tile max-X still adds 8 when it stays under 127");
        assertEquals(117, state[BattleNetAiBytecode.OFF_BOUND_MAX_Y] & 0xff,
                "the 128-tile max-Y still adds 8 when it stays under 127");
        assertEquals(0x7b, state[BattleNetAiBytecode.OFF_BOUND_MIN_X] & 0xff,
                "a 128-tile town wraps the unused min-X of 128 minus 5 to 123");
    }

    @Test
    void expandLandBuildBoundsOnA128TileMapKeepsAWrappedMax() {
        byte[] state = new byte[BattleNetAiBytecode.STATE_BYTES];
        BattleNetAiBytecode.expandLandBuildBounds(
                state, 128, java.util.List.of(new int[] {126, 60}));
        assertEquals(0x86, state[BattleNetAiBytecode.OFF_BOUND_MAX_X] & 0xff,
                "126 plus 8 wraps to 134 and a signed compare against 127 keeps it");
        assertEquals(68, state[BattleNetAiBytecode.OFF_BOUND_MAX_Y] & 0xff,
                "a mid-map 128-tile max-Y still adds 8");
        assertEquals(0x7b, state[BattleNetAiBytecode.OFF_BOUND_MIN_X] & 0xff,
                "the unused 128-tile min still wraps to 123 beside a wrapped max");
    }
}
