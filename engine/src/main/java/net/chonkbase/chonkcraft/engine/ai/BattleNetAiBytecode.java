package net.chonkbase.chonkcraft.engine.ai;

/**
 * BNE 2.02 {@code Rez/ai.bin} four-opcode interpreter.
 *
 * <p>Implements {@code FUN_00424f00} / {@code FUN_00424e40} in the retail
 * executable. Each profile record is {@code {listOffset, thresholdOffset,
 * bytecode...}}. Opcodes: 0 set state byte, 1 absolute jump, 2 wait N steps,
 * 3 wait-until predicate. State bytes +0x13..+0x20 are finite unit-family
 * wants consumed by action-33 producers; +0x22 is the live ordered-list scan
 * bound.
 */
public final class BattleNetAiBytecode {

    /** 48-byte AIPlayerState layout used by native. */
    public static final int STATE_BYTES = 48;

    public static final int OFF_WAIT = 0x00;
    public static final int OFF_LAUNCH_GROUND = 0x09;
    public static final int OFF_LAUNCH_NAVAL = 0x0a;
    public static final int OFF_LAUNCH_AIR = 0x0b;
    /**
     * Computer builder-scan latch at {@code +0x0c}.
     *
     * <p>Native {@code 0x428160} reads {@code [owner*48 + 0x004af124]} and
     * skips the map-component walk when the byte is zero. Profiles 1 and 3
     * store 0 here during install; the live computer dump is already 1
     * before the first warmup tick. Leaving the SET 0 in place used to
     * disable that walk.
     */
    public static final int OFF_COMPUTER_ARMED = 0x0c;
    public static final int OFF_GROUND_FORCE_COUNT = 0x0d;
    public static final int OFF_GROUND_FORCE_MULTIPLIER = 0x0e;
    public static final int OFF_NAVAL_FORCE_COUNT = 0x0f;
    public static final int OFF_NAVAL_FORCE_MULTIPLIER = 0x10;
    public static final int OFF_AIR_FORCE_COUNT = 0x11;
    public static final int OFF_AIR_FORCE_MULTIPLIER = 0x12;
    public static final int OFF_WANTED_WORKERS = 0x13;
    public static final int OFF_WANTED_BASIC = 0x14;
    public static final int OFF_WANTED_RANGED = 0x15;
    public static final int OFF_WANTED_SIEGE = 0x16;
    public static final int OFF_WANTED_CAVALRY = 0x17;
    public static final int OFF_WANTED_TANKERS = 0x18;
    public static final int OFF_WANTED_DESTROYERS = 0x19;
    public static final int OFF_WANTED_TRANSPORTS = 0x1a;
    public static final int OFF_WANTED_BATTLESHIPS = 0x1b;
    public static final int OFF_WANTED_SUBS = 0x1c;
    public static final int OFF_WANTED_CASTERS = 0x1d;
    public static final int OFF_WANTED_BALLOONS = 0x1e;
    public static final int OFF_WANTED_FLYERS = 0x20;
    public static final int OFF_LIST_BOUND = 0x22;
    /** Signed min-Y of the 0x4273e0 build box. */
    public static final int OFF_BOUND_MIN_Y = 0x2b;
    /** Signed max-X of the 0x4273e0 build box. */
    public static final int OFF_BOUND_MAX_X = 0x2c;
    /** Signed max-Y of the 0x4273e0 build box. */
    public static final int OFF_BOUND_MAX_Y = 0x2d;
    /** Signed min-X of the 0x4273e0 build box. */
    public static final int OFF_BOUND_MIN_X = 0x2e;

    private static final int MAX_OPS_PER_STEP = 256;

    private BattleNetAiBytecode() {
    }

    /** Unsigned little-endian word. */
    public static int u16(byte[] data, int offset) {
        if (data == null || offset < 0 || offset + 1 >= data.length) {
            return -1;
        }
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    /** Unsigned little-endian dword. */
    public static int u32(byte[] data, int offset) {
        if (data == null || offset < 0 || offset + 3 >= data.length) {
            return 0;
        }
        return (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16)
                | ((data[offset + 3] & 0xff) << 24);
    }

    /**
     * Install profile {@code profile} from maindat entry 277 / ai.bin.
     *
     * @return program counter (absolute file offset of first bytecode), or -1
     */
    public static int install(byte[] ai, int profile, byte[] state) {
        if (ai == null || state == null || state.length < STATE_BYTES
                || profile < 0 || profile >= 83) {
            return -1;
        }
        java.util.Arrays.fill(state, (byte) 0);
        state[OFF_LIST_BOUND] = (byte) 0xff;
        int rec = u16(ai, profile * 2);
        if (rec < 0 || rec + 4 >= ai.length) {
            return -1;
        }
        // Bytecode starts at record + 4 (after list and threshold words).
        int pc = rec + 4;
        // Run until wait is non-zero (native FUN_00424e40 init).
        int next = runUntilWait(ai, state, pc, true);
        // 0x428160 tests this latch before the computer builder scan.
        // Install bytecode may have stored 0; the live computer is armed.
        state[OFF_COMPUTER_ARMED] = 1;
        return next;
    }

    /**
     * Writes the inverted 0x4273e0 build box used when the player has no
     * gold depot. Native loads map size into AL and 0xff into BL, then
     * stores minX=minY=size and maxX=maxY=-1. Orc 1 and Human 1 have no
     * 0x4be264 hall, so the box stays 32,-1,-1,32. Orc 2 and Human 2 keep
     * the 64-tile inverted box because their only building is a tower.
     */
    public static void installEmptyBuildBounds(byte[] state, int mapSize) {
        if (state == null || state.length < STATE_BYTES) {
            return;
        }
        byte size = (byte) mapSize;
        byte empty = (byte) 0xff;
        state[OFF_BOUND_MIN_Y] = size;
        state[OFF_BOUND_MAX_X] = empty;
        state[OFF_BOUND_MAX_Y] = empty;
        state[OFF_BOUND_MIN_X] = size;
    }

    /**
     * Expands the 0x4273e0 box around land buildings, newest first.
     *
     * <p>Native only reaches this walk when {@code 0x439ce0} finds a
     * type-flag {@code 0x1000} hall on the player's list. It then walks
     * {@code 0x4be264[player]} (unit pool inserted at the head, so reverse
     * creation order). A unit is skipped unless flags {@code +0x1e} are
     * clear, type flag {@code 0x20} (building) is set, and type flags
     * {@code 0x10800} are clear -- sea, shore, and water-sited oil
     * platforms stay out. Updating a min skips the max on that axis.
     * After the walk, min subtracts 5 and max adds 8, clamped to
     * {@code [0, mapSize-1]}.
     *
     * @param tilesNewestFirst land-building origins, newest first
     */
    public static void expandLandBuildBounds(byte[] state, int mapSize,
            java.util.List<int[]> tilesNewestFirst) {
        installEmptyBuildBounds(state, mapSize);
        if (state == null || tilesNewestFirst == null
                || tilesNewestFirst.isEmpty()) {
            return;
        }
        for (int[] tile : tilesNewestFirst) {
            if (tile == null || tile.length < 2) {
                continue;
            }
            expandAxis(state, OFF_BOUND_MIN_X, OFF_BOUND_MAX_X, tile[0]);
            expandAxis(state, OFF_BOUND_MIN_Y, OFF_BOUND_MAX_Y, tile[1]);
        }
        padBuildBounds(state, mapSize);
    }

    private static void expandAxis(byte[] state, int minOff, int maxOff,
            int value) {
        int min = state[minOff];
        int max = state[maxOff];
        if (value < min) {
            state[minOff] = (byte) value;
        } else if (value > max) {
            state[maxOff] = (byte) value;
        }
    }

    private static void padBuildBounds(byte[] state, int mapSize) {
        int last = Math.max(0, mapSize - 1);
        state[OFF_BOUND_MIN_X] = padMin(state[OFF_BOUND_MIN_X]);
        state[OFF_BOUND_MIN_Y] = padMin(state[OFF_BOUND_MIN_Y]);
        state[OFF_BOUND_MAX_X] = padMax(state[OFF_BOUND_MAX_X], last);
        state[OFF_BOUND_MAX_Y] = padMax(state[OFF_BOUND_MAX_Y], last);
    }

    private static byte padMin(byte value) {
        int next = value - 5;
        return (byte) (next < 0 ? 0 : next);
    }

    private static byte padMax(byte value, int last) {
        int next = value + 8;
        return (byte) (next > last ? last : next);
    }

    /**
     * One simulation-step tick: decrement wait or execute until wait is set.
     *
     * @return updated program counter
     */
    public static int tick(byte[] ai, byte[] state, int pc,
            PredicateHost predicates) {
        if (ai == null || state == null || pc < 0) {
            return pc;
        }
        if (waitCounter(state) != 0) {
            decrementWait(state);
            return pc;
        }
        return runUntilWait(ai, state, pc, false, predicates);
    }

    private static int runUntilWait(byte[] ai, byte[] state, int pc,
            boolean init) {
        return runUntilWait(ai, state, pc, init, null);
    }

    private static int runUntilWait(byte[] ai, byte[] state, int pc,
            boolean init, PredicateHost predicates) {
        for (int ops = 0; ops < MAX_OPS_PER_STEP; ops++) {
            if (pc < 0 || pc >= ai.length) {
                return pc;
            }
            if (waitCounter(state) != 0) {
                return pc;
            }
            int opcode = ai[pc] & 0xff;
            pc++;
            switch (opcode) {
                case 0 -> {
                    // SET state[offset] = value
                    if (pc + 1 >= ai.length) {
                        return pc;
                    }
                    int offset = ai[pc] & 0xff;
                    int value = ai[pc + 1] & 0xff;
                    pc += 2;
                    if (offset < STATE_BYTES) {
                        state[offset] = (byte) value;
                    }
                }
                case 1 -> {
                    // JUMP absolute file offset
                    if (pc + 1 >= ai.length) {
                        return pc;
                    }
                    pc = u16(ai, pc);
                }
                case 2 -> {
                    // WAIT delay32
                    if (pc + 3 >= ai.length) {
                        return pc;
                    }
                    int delay = u32(ai, pc);
                    pc += 4;
                    writeWait(state, delay);
                }
                case 3 -> {
                    // WAIT-UNTIL predicate
                    if (pc >= ai.length) {
                        return pc;
                    }
                    int predicate = ai[pc] & 0xff;
                    boolean ok = predicates != null
                            && predicates.test(predicate, state);
                    if (ok) {
                        pc++; // advance past predicate
                    } else {
                        // Back up to opcode 3 so the predicate is retried.
                        pc -= 1;
                    }
                    // Native yields for one tick on either predicate result.
                    writeWait(state, 1);
                }
                default -> {
                    // Malformed: stop the program.
                    return -1;
                }
            }
        }
        return pc;
    }

    /**
     * Host for opcode-3 predicates (shipyard present, worker target, etc.).
     */
    public interface PredicateHost {
        boolean test(int predicate, byte[] state);
    }

    /** 32-bit wait counter packed in state[0..3]. */
    public static int waitCounter(byte[] state) {
        if (state == null || state.length < 4) {
            return 0;
        }
        return (state[0] & 0xff)
                | ((state[1] & 0xff) << 8)
                | ((state[2] & 0xff) << 16)
                | ((state[3] & 0xff) << 24);
    }

    /** Decrement the 32-bit wait counter by one if non-zero. */
    public static void decrementWait(byte[] state) {
        int w = waitCounter(state);
        if (w == 0) {
            return;
        }
        writeWait(state, w - 1);
    }

    private static void writeWait(byte[] state, int wait) {
        int w = wait;
        state[0] = (byte) (w & 0xff);
        state[1] = (byte) ((w >>> 8) & 0xff);
        state[2] = (byte) ((w >>> 16) & 0xff);
        state[3] = (byte) ((w >>> 24) & 0xff);
    }
}
