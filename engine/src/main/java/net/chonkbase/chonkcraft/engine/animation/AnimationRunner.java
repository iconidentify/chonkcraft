package net.chonkbase.chonkcraft.engine.animation;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Steps a unit's animation one cycle.
 *
 * <p>Implements {@code UnitShowAnimationScaled}. The shape is unusual and worth
 * stating: instructions run in a loop until one of them <em>waits</em>, and
 * everything they did along the way is accumulated. So a single cycle may set
 * a frame, request movement, fire an attack and play a sound before it stops.
 * The wait is what ends the cycle, not the instruction count.
 *
 * <p>The accumulated movement is the return value, because that is what the
 * move action needs: the animation decides how far the unit travels.
 */
public final class AnimationRunner {

    /** The label an {@code if-var} jumps to: the last of its four words. */
    /** Whether the unit being stepped is under Slow, and under Haste. */
    private boolean slowed;
    private boolean hasted;

    /**
     * One wait, stretched by Slow and compressed by Haste.
     *
     * <p>{@code CAnimation_Wait::Action},, including
     * its floor of one: a wait of nought would spin the instruction loop.
     */
    private int spellAdjusted(int wait) {
        int adjusted = wait;
        if (slowed) {
            adjusted <<= 1;
        }
        if (hasted && adjusted > 1) {
            adjusted >>= 1;
        }
        return Math.max(1, adjusted);
    }

    private static String labelOf(String operand) {
        String[] parts = operand == null ? new String[0] : operand.trim().split("\\s+");
        return parts.length >= 4 ? parts[parts.length - 1] : "";
    }

    /**
     * Whether an {@code if-var} comparison is true.
     *
     * <p>{@code CAnimation_IfVar::Init} reads four words -- left, operator,
     * right, label -- and compares the two sides as numbers.
     *
     * <p>A name this implementation does not model reads as zero, which is what upstream
     * gives an unset variable. That is deliberate rather than a shortcut, and
     * it is worth knowing what it costs: the gold mine's script asks
     * {@code v.ResourceActive.Value >= 1}, so a mine with somebody working
     * inside it shows its idle frame until that variable is modelled. An
     * idle-looking mine is a far smaller error than what this replaced, which
     * was running both branches and flashing between them almost six times a
     * second.
     */
    private boolean ifVarHolds(String operand) {
        String[] parts = operand == null ? new String[0] : operand.trim().split("\\s+");
        if (parts.length < 4) {
            return false;
        }
        int left = valueOf(parts[0]);
        int right = valueOf(parts[2]);
        return switch (parts[1]) {
            case ">=" -> left >= right;
            case ">" -> left > right;
            case "<=" -> left <= right;
            case "<" -> left < right;
            case "==" -> left == right;
            case "!=" -> left != right;
            case "&" -> (left & right) != 0;
            case "|" -> (left | right) != 0;
            default -> false;
        };
    }

    /**
     * A literal number, or a variable this implementation models, or nought.
     *
     * <p>{@code R} is the one named variable here, and it is the pending
     * rotation.
     * The ballista's and catapult's Move animation opens
     * {@code "if-var R >= 60 turn"}, and reading it as nought -- which is what
     * an unmodelled name gets -- sent both of them straight past the thirty
     * cycles they should have spent turning.
     */
    private int valueOf(String word) {
        String trimmed = word.trim();
        if (trimmed.equals("R")) {
            return pendingRotation;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException notALiteral) {
            return 0;
        }
    }

    /** The unit's pending rotation, for {@code if-var R}. */
    private int pendingRotation;

    /** How far the unit may turn in a cycle, its type's RotationSpeed. */
    private int rotationSpeed = 128;
    /** What one cycle of animation produced. */
    public record Step(int move, boolean attacked, String sound, int frame,
            List<Animation.Instruction> effects, int rotation, boolean yielded) {

        Step(int move, boolean attacked, String sound, int frame,
                List<Animation.Instruction> effects) {
            this(move, attacked, sound, frame, effects, 0, false);
        }

        Step(int move, boolean attacked, String sound, int frame,
                List<Animation.Instruction> effects, int rotation) {
            this(move, attacked, sound, frame, effects, rotation, false);
        }
    }

    private final RandomGenerator random;

    /**
     * Whether LegacyEngine's script-level random animation instructions run.
     *
     * <p>ChonkCraft adds {@code random-goto}/{@code random-rotate} to its shared
     * idle animation. Retail Warcraft II BNE does not consume synchronized
     * random numbers for that idle flourish. Worlds targeting the BNE oracle
     * therefore leave the rest of the animation program intact while treating
     * those two ChonkCraft instructions as no-ops.
     */
    private boolean randomInstructionsEnabled = false;

    /**
     * @param random used for the {@code random-goto} and {@code random-rotate}
     *               instructions. The simulation supplies a seeded generator
     *               so that two machines running the same game stay in step.
     */
    public AnimationRunner(RandomGenerator random) {
        this.random = random;
    }

    /** Selects whether script-level random animation instructions execute. */
    public void setRandomInstructionsEnabled(boolean enabled) {
        randomInstructionsEnabled = enabled;
    }

    /**
     * Advances one cycle.
     *
     * @param state the unit's animation position, updated in place
     * @param scale terrain move cost, which stretches movement instructions;
     *              1 leaves them as written
     * @param frame the unit's current base frame, returned unchanged if no
     *              frame instruction runs
     * @return what the cycle produced
     */
    public Step step(AnimationState state, int scale, int frame) {
        return step(state, scale, frame, false, false);
    }

    /**
     * The same, for a unit under Haste or Slow.
     *
     * <p>{@code CAnimation_Wait::Action}
     * is where the two spells actually live:
     *
     * <pre>
     * if (unit.Variable[SLOW_INDEX].Value) { unit.Anim.Wait &lt;&lt;= 1; }
     * if (unit.Variable[HASTE_INDEX].Value &amp;&amp; unit.Anim.Wait &gt; 1) { unit.Anim.Wait &gt;&gt;= 1; }
     * </pre>
     *
     * <p>So they are not a speed multiplier on movement -- they stretch or
     * compress the wait between animation instructions, which slows or
     * quickens <em>everything</em> the unit does: walking, swinging, chopping.
     * That is why a hasted peasant gathers faster and a slowed catapult
     * reloads slower, and it is why doing this at the animation rather than at
     * the move would have been the wrong shape.
     *
     * <p>Both are applied in upstream's order and Slow is not symmetric with
     * Haste: Slow doubles unconditionally, Haste halves only a wait above one,
     * so the two together leave the wait where it started rather than
     * cancelling to nothing.
     *
     * @param slowed  whether SLOW is on the unit
     * @param hasted  whether HASTE is on the unit
     */
    public Step step(AnimationState state, int scale, int frame, boolean slowed, boolean hasted) {
        return step(state, scale, frame, slowed, hasted, 0, 128);
    }

    /**
     * The same, told how far the unit still has to turn.
     *
     * @param rotation the unit's pending rotation in 256ths, which
     *     {@code if-var R} reads and which this walks back towards nought
     * @return the step, whose {@code rotation} is what is left of it
     */
    public Step step(AnimationState state, int scale, int frame, boolean slowed,
            boolean hasted, int rotation, int rotationSpeed) {
        this.slowed = slowed;
        this.hasted = hasted;
        this.pendingRotation = rotation;
        this.rotationSpeed = rotationSpeed;
        Animation animation = state.current();
        if (animation == null || animation.size() == 0) {
            return new Step(0, false, null, frame, List.of(), pendingRotation);
        }

        // The turn runs down first, before anything else this cycle.
        // UnitShowAnimationScaled opens with it (:
        // 372-383): the pending rotation is walked towards nought by the
        // type's RotationSpeed and stops dead when it crosses over.
        if (pendingRotation != 0 && rotationSpeed > 0) {
            int before = pendingRotation;
            pendingRotation += pendingRotation < 0 ? rotationSpeed : -rotationSpeed;
            if ((pendingRotation ^ before) < 0) {
                pendingRotation = 0;
            }
        }

        // Mid-wait: burn a cycle and, when it runs out, move to the next
        // instruction. Nothing else happens.
        if (state.waitCycles() > 0) {
            state.setWaitCycles(state.waitCycles() - 1);
            if (state.waitCycles() == 0) {
                state.setIndex((state.index() + 1) % animation.size());
            }
            return new Step(0, false, null, frame, List.of(), pendingRotation);
        }

        int move = 0;
        boolean attacked = false;
        boolean yielded = false;
        String sound = null;
        int currentFrame = frame;
        List<Animation.Instruction> effects = null;

        // Run instructions until one waits. The guard on iterations is a
        // backstop: an animation whose loop contains no wait would spin here
        // forever, and a bad script should not hang the simulation.
        int guard = 0;
        while (state.waitCycles() == 0) {
            if (++guard > 1000) {
                state.setWaitCycles(1);
                break;
            }
            Animation.Instruction instruction = animation.at(state.index());
            switch (instruction.kind()) {
                case FRAME -> currentFrame = instruction.value();
                case WAIT -> {
                    int wait = spellAdjusted(instruction.value());
                    state.setWaitCycles(wait);
                    // Native 0x402440 opcode 0 yields to the unit order and
                    // sets the animation timer to 1. Building Train scripts
                    // write that yield as wait 1 after wait 4.
                    if (wait == 1) {
                        yielded = true;
                    }
                }
                // Scaled by terrain cost, as UnitShowAnimationScaled does.
                case MOVE -> move += instruction.value() * scale;
                case ATTACK -> attacked = true;
                case SOUND -> sound = instruction.operand();
                case UNBREAKABLE_BEGIN -> state.setUnbreakable(true);
                case UNBREAKABLE_END -> state.setUnbreakable(false);
                case SPAWN_UNIT, SET_VAR, WIGGLE -> {
                    if (effects == null) {
                        effects = new ArrayList<>(2);
                    }
                    effects.add(instruction);
                }
                case DIE -> {
                    if (effects == null) {
                        effects = new ArrayList<>(1);
                    }
                    effects.add(instruction);
                    // Upstream throws out of the animation interpreter here.
                    // The world consumes the effect and removes the unit.
                    return new Step(move, attacked, sound, currentFrame,
                            List.copyOf(effects), pendingRotation);
                }
                case RANDOM_GOTO -> {
                    if (!randomInstructionsEnabled) {
                        break;
                    }
                    if (random.nextInt(100) < instruction.value()) {
                        int target = animation.labelIndex(instruction.operand());
                        if (target >= 0) {
                            // -1 because the advance below moves on by one.
                            state.setIndex(target);
                            continue;
                        }
                    }
                }
                case GOTO -> {
                    int target = animation.labelIndex(instruction.operand());
                    if (target >= 0) {
                        state.setIndex(target);
                        continue;
                    }
                }
                case IF_VAR -> {
                    if (ifVarHolds(instruction.operand())) {
                        int target = animation.labelIndex(labelOf(instruction.operand()));
                        if (target >= 0) {
                            state.setIndex(target);
                            continue;
                        }
                    }
                }
                case RANDOM_ROTATE -> {
                    if (!randomInstructionsEnabled) {
                        break;
                    }
                    // The unit turns on the spot, one way or the other.
                    // {@code CAnimation_RandomRotate::Action}
                    // The game draws once and
                    // reads bit eight of the draw for the direction.
                    //
                    // This arm was empty, and it cost more than the turn.
                    // Every unit's idle animation is
                    // {@code UnitStill = {"frame 0", "wait 4",
                    // "random-goto 99 no-rotate", "random-rotate 1",
                    // "label no-rotate", "wait 1"}} (scripts/anim.legacy-declaration:31), so
                    // one idle unit in a hundred turns a step each time round
                    // -- which is the small shifting-about a Warcraft II army
                    // does while it waits, and this implementation's armies stood frozen
                    // facing one way for ever. Worse, the missing draw put the
                    // shared random stream out of step with upstream's: the
                    // differential harness caught it as 40 draws against 41 on
                    // the fifth cycle of a map where nothing was happening,
                    // and a stream that has drifted gives different damage
                    // rolls from then on.
                    //
                    // The rotation itself belongs to the world -- the runner
                    // is given an animation and a state, never a unit -- so it
                    // travels as an effect with its sign already decided, the
                    // way spawn-unit and die travel.
                    int step = instruction.value();
                    int turn = ((random.nextInt() >> 8) & 1) == 1 ? -step : step;
                    if (effects == null) {
                        effects = new ArrayList<>(1);
                    }
                    effects.add(new Animation.Instruction(
                            Animation.Kind.RANDOM_ROTATE, turn, instruction.operand()));
                }
                case LABEL, OTHER -> { }
            }
            if (state.waitCycles() == 0) {
                state.setIndex((state.index() + 1) % animation.size());
            }
        }

        // Consume this cycle's share of the wait.
        state.setWaitCycles(state.waitCycles() - 1);
        if (state.waitCycles() == 0) {
            state.setIndex((state.index() + 1) % animation.size());
        }
        return new Step(move, attacked, sound, currentFrame,
                effects == null ? List.of() : List.copyOf(effects), pendingRotation,
                yielded);
    }
}
