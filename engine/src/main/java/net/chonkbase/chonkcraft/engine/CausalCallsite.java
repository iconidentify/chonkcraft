package net.chonkbase.chonkcraft.engine;

import java.util.List;

/**
 * Which engine method asked for a random number, named so a refactor keeps it.
 *
 * <p>There is no upstream construct for this: BNE's tracer records a native
 * return address, and the Java side needs something a cross-engine ledger can
 * put beside one. The deviation is deliberate and bounded -- a return address
 * and a method name are reported side by side and never treated as equal.
 *
 * <p>The identity is the class and method that asked, because that is what
 * survives being edited. The reader used to keep only {@code World} frames and
 * only a method name with its line number, so after the {@code BattleNet*}
 * subsystems moved out of {@code World} every projectile, idle and
 * construction draw in the ledger read {@code ?}, and every draw that stayed
 * in {@code World} changed identity whenever a line moved above it. The line
 * survives here as diagnostic metadata, which is why nothing keys off it.
 */
record CausalCallsite(String caller, String chain, int line) {

    /** How many callers deep the chain records before it stops being useful. */
    private static final int CHAIN_LIMIT = 4;

    /** What a walk finds when no frame below the generator is the engine's. */
    static final CausalCallsite UNKNOWN = new CausalCallsite("?", null, -1);

    /**
     * The nearest frame that consumed a draw, plus its callers.
     *
     * <p>Only reached when causal tracing is on. With tracing off no draw
     * walks the stack at all, which is the point of every guard around the
     * calls to this.
     */
    static CausalCallsite resolve() {
        List<StackWalker.StackFrame> frames = StackWalker.getInstance().walk(
                stream -> stream
                        .filter(frame -> !plumbing(frame))
                        .filter(frame -> !platform(frame))
                        .limit(CHAIN_LIMIT)
                        .toList());
        if (frames.isEmpty()) {
            return UNKNOWN;
        }
        StringBuilder chain = new StringBuilder();
        for (StackWalker.StackFrame frame : frames) {
            if (!chain.isEmpty()) {
                chain.append('<');
            }
            chain.append(name(frame));
        }
        StackWalker.StackFrame immediate = frames.getFirst();
        return new CausalCallsite(name(immediate),
                frames.size() > 1 ? chain.toString() : null,
                immediate.getLineNumber());
    }

    /** {@code SimpleClass.method}, which no line edit and no move rewrites. */
    private static String name(StackWalker.StackFrame frame) {
        String type = frame.getClassName();
        return type.substring(type.lastIndexOf('.') + 1)
                + "." + frame.getMethodName();
    }

    /**
     * The generator itself and the trace writer, which never consume a draw.
     *
     * <p>{@code syncRand(int)} delegates to {@code syncRand()}, so both arms of
     * the pair are named here and the caller reported is whoever wanted the
     * bounded number.
     */
    private static boolean plumbing(StackWalker.StackFrame frame) {
        String type = frame.getClassName();
        if (type.equals(CausalCallsite.class.getName())
                || type.equals(CausalTrace.class.getName())) {
            return true;
        }
        if (!type.equals(World.class.getName())) {
            return false;
        }
        return switch (frame.getMethodName()) {
            case "syncRand", "battleNetRand", "battleNetRandomForAi",
                 "causalCaller", "causalCallsite", "traceDraw" -> true;
            default -> false;
        };
    }

    private static boolean platform(StackWalker.StackFrame frame) {
        String type = frame.getClassName();
        return type.startsWith("java.") || type.startsWith("jdk.")
                || type.startsWith("sun.");
    }
}
