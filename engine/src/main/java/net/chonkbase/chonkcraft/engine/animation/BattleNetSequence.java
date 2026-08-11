package net.chonkbase.chonkcraft.engine.animation;

import java.util.Arrays;

/**
 * Retail Battle.net Edition's compact unit-animation program.
 *
 * <p>The original game reads this table from {@code Rez\\script.bin}.  Each
 * unit type begins with a table of little-endian sequence offsets, and each
 * sequence is a bytecode program.  This is not cosmetic: opcode zero hands
 * control back to the unit's current order, so the exact placement of waits
 * and jumps decides when idle units scan, turn, or consume the independent
 * random stream.</p>
 *
 * <p>The interpreter reports the order-action boundary and the sum of pixel
 * motion opcodes consumed on each call. Residual drain for BNE walks uses that
 * sum so ship and land Move waits match {@code script.bin} rather than the
 * ChonkCraft Move animation, which diverges for gnomish submarines (irregular
 * wait-1 stretches that step a double-step tile one cycle early).</p>
 */
public final class BattleNetSequence {

    /** Still is animation slot two in BNE's action-to-animation table. */
    public static final int STILL_ANIMATION = 2;

    /**
     * Move is animation slot three in BNE's action-to-animation table.
     *
     * <p>The move program opens with frame + opcode-zero, then the pixel/wait
     * body, and jumps back so the next step is another opcode-zero. Chase
     * step cadence follows that loop, not the ChonkCraft Move wait total.
     */
    public static final int MOVE_ANIMATION = 3;

    /** Attack is animation slot four in BNE's action-to-animation table. */
    public static final int ATTACK_ANIMATION = 4;

    private static final int MAX_INSTRUCTIONS_PER_TICK = 1024;

    private final byte[] program;

    /**
     * One native animation call.
     *
     * @param offset next bytecode offset
     * @param timer native unsigned-byte countdown after the call
     * @param actionMarker whether opcode zero asked the order to act
     * @param inlineActionMarker whether opcode ten invoked the current order
     *                           without ending the animation
     * @param valid whether the input and all visited instructions were valid
     * @param pixels sum of pixel-motion opcode arguments consumed this call
     *               ({@code op5}/{@code op6}/{@code op13}/{@code op14}). Hold
     *               ticks report zero. Callers scale ship {@code op13} for the
     *               doubled movement-delta table when unit+0x1c bit 2 is set.
     */
    public record Tick(int offset, int timer, boolean actionMarker,
            boolean inlineActionMarker, boolean valid, int pixels) {
        /** Compatibility constructor for callers that ignore pixel motion. */
        public Tick(int offset, int timer, boolean actionMarker,
                boolean inlineActionMarker, boolean valid) {
            this(offset, timer, actionMarker, inlineActionMarker, valid, 0);
        }

        private static Tick invalid() {
            return new Tick(-1, 0, false, false, false, 0);
        }
    }

    public BattleNetSequence(byte[] program) {
        this.program = program == null ? new byte[0] : Arrays.copyOf(program, program.length);
    }

    /** The sequence offset for one unit type and animation, or {@code -1}. */
    public int sequenceStart(int unitType, int animation) {
        if (unitType < 0 || animation < 0) {
            return -1;
        }
        int typeEntry = unitType * 2;
        if (!contains(typeEntry, 2)) {
            return -1;
        }
        int table = unsignedShort(typeEntry);
        long animationEntry = (long) table + animation * 2L;
        if (animationEntry > Integer.MAX_VALUE
                || !contains((int) animationEntry, 2)) {
            return -1;
        }
        int sequence = unsignedShort((int) animationEntry);
        return contains(sequence, 1) ? sequence : -1;
    }

    /** Whether this data contains at least the ordinary type-zero Still program. */
    public boolean usable() {
        return sequenceStart(0, STILL_ANIMATION) >= 0;
    }

    /**
     * Sum of wait timers in one Attack swing body after the opening opcode
     * zero, through the goto that returns to that same OP0.
     *
     * <p>Human 13 knight 1490 takes a catapult splash while parked on Attack
     * OP0. Native keeps the cursor on that OP0 and arms timer
     * {@code bodyWaitSum - 1} (knight Attack@1922: waits 3+3+3+5+10 = 24, so
     * timer 23) instead of walking into windup/OP10. Returns {@code -1} when
     * the program is not a well-formed OP0-headed Attack loop.</p>
     */
    public int attackBodyWaitSum(int attackStart) {
        if (!contains(attackStart, 1)
                || Byte.toUnsignedInt(program[attackStart]) != 0) {
            return -1;
        }
        int cursor = attackStart + 1;
        int sum = 0;
        for (int instructions = 0;
                instructions < MAX_INSTRUCTIONS_PER_TICK;
                instructions++) {
            if (!contains(cursor, 1)) {
                return -1;
            }
            int opcode = Byte.toUnsignedInt(program[cursor]);
            switch (opcode) {
                case 0 -> {
                    return sum;
                }
                case 1, 7, 8, 9, 12 -> {
                    if (!contains(cursor, 2)) {
                        return -1;
                    }
                    sum += Byte.toUnsignedInt(program[cursor + 1]);
                    cursor += 2;
                }
                case 2 -> cursor += 1;
                case 3 -> {
                    if (!contains(cursor, 3)) {
                        return -1;
                    }
                    int target = unsignedShort(cursor + 1);
                    if (target == attackStart) {
                        return sum;
                    }
                    cursor = target;
                }
                case 4 -> {
                    if (!contains(cursor, 2)) {
                        return -1;
                    }
                    cursor += 2;
                }
                case 10, 11, 15 -> cursor += 1;
                case 5, 13 -> {
                    if (!contains(cursor, 2)) {
                        return -1;
                    }
                    cursor += 2;
                }
                case 6, 14 -> {
                    if (!contains(cursor, 3)) {
                        return -1;
                    }
                    cursor += 3;
                }
                default -> {
                    return -1;
                }
            }
        }
        return -1;
    }

    /**
     * Advances the native unsigned-byte timer and interprets until a wait or
     * order-action marker returns control.
     */
    public Tick tick(int offset, int timer) {
        if (!contains(offset, 1)) {
            return Tick.invalid();
        }

        int nextTimer = (timer - 1) & 0xff;
        if (nextTimer != 0) {
            return new Tick(offset, nextTimer, false, false, true, 0);
        }

        int cursor = offset;
        boolean inlineActionMarker = false;
        int pixels = 0;
        for (int instructions = 0;
                instructions < MAX_INSTRUCTIONS_PER_TICK;
                instructions++) {
            if (!contains(cursor, 1)) {
                return Tick.invalid();
            }
            int opcode = Byte.toUnsignedInt(program[cursor]);
            switch (opcode) {
                case 0 -> {
                    // FUN_00402440 increments the just-expired timer, leaving
                    // one call before the instruction following the marker.
                    return new Tick(cursor + 1, 1, true,
                            inlineActionMarker, true, pixels);
                }
                case 1, 7, 8, 9, 12 -> {
                    if (!contains(cursor, 2)) {
                        return Tick.invalid();
                    }
                    return new Tick(cursor + 2,
                            Byte.toUnsignedInt(program[cursor + 1]), false,
                            inlineActionMarker, true, pixels);
                }
                case 2 -> {
                    // Zero is meaningful in the native byte field: its next
                    // decrement wraps to 255 rather than immediately acting.
                    return new Tick(cursor + 1, 0, false,
                            inlineActionMarker, true, pixels);
                }
                case 3 -> {
                    if (!contains(cursor, 3)) {
                        return Tick.invalid();
                    }
                    cursor = unsignedShort(cursor + 1);
                }
                case 4 -> {
                    // Set frame. The frame byte is an operand; treating it as
                    // the next opcode stops on frame zero one instruction
                    // early and changes which unit draws the next RNG value.
                    if (!contains(cursor, 2)) {
                        return Tick.invalid();
                    }
                    cursor += 2;
                }
                case 10 -> {
                    // Unlike opcode zero, this calls the current order and
                    // then continues through the animation. Attack programs
                    // use it for the actual projectile boundary.
                    inlineActionMarker = true;
                    cursor++;
                }
                case 11, 15 -> cursor++;
                case 5, 13 -> {
                    // Pixel motion: op5 land, op13 sea/air. Argument is the
                    // native table step; ship double-step multiplies later.
                    if (!contains(cursor, 2)) {
                        return Tick.invalid();
                    }
                    pixels += Byte.toUnsignedInt(program[cursor + 1]);
                    cursor += 2;
                }
                case 6, 14 -> {
                    // Two-byte pixel motion with a frame operand (op6 land,
                    // op14 flyer). First operand is the step size.
                    if (!contains(cursor, 3)) {
                        return Tick.invalid();
                    }
                    pixels += Byte.toUnsignedInt(program[cursor + 1]);
                    cursor += 3;
                }
                default -> {
                    // Native skips unknown bytes, but accepting them here
                    // would make arbitrary or truncated pack data look like a
                    // valid timing program. Real BNE bytecode uses 0..15.
                    return Tick.invalid();
                }
            }
        }
        // A malformed self-jump must not hang the game loop.
        return Tick.invalid();
    }

    /**
     * Counts the quiet animation visits before the next order-action marker.
     *
     * <p>Player commands in BNE replace the queued order immediately, but the
     * old current order remains visible until its animation reaches opcode
     * zero. The unsigned timer alone is insufficient: a timer-one visit may
     * first execute a frame and arm another wait before reaching that marker.
     * This dry run follows the same bytecode and timer rules as {@link #tick}
     * without changing the live unit.</p>
     *
     * @return the number of non-marker visits, or {@code -1} for malformed or
     *         non-terminating bytecode
     */
    public int quietTicksUntilActionMarker(int offset, int timer) {
        int cursor = offset;
        int countdown = timer;
        for (int quiet = 0; quiet < MAX_INSTRUCTIONS_PER_TICK; quiet++) {
            Tick next = tick(cursor, countdown);
            if (!next.valid()) {
                return -1;
            }
            if (next.actionMarker()) {
                return quiet;
            }
            cursor = next.offset();
            countdown = next.timer();
        }
        return -1;
    }

    private int unsignedShort(int offset) {
        return Byte.toUnsignedInt(program[offset])
                | Byte.toUnsignedInt(program[offset + 1]) << 8;
    }

    /**
     * Opcode byte at {@code offset}, or {@code -1} if out of range.
     */
    public int opcodeAt(int offset) {
        if (!contains(offset, 1)) {
            return -1;
        }
        return Byte.toUnsignedInt(program[offset]);
    }

    private boolean contains(int offset, int length) {
        return offset >= 0 && length >= 0 && offset <= program.length - length;
    }
}
