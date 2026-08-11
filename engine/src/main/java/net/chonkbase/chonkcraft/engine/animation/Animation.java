package net.chonkbase.chonkcraft.engine.animation;

import java.util.List;

/**
 * One animation: a program of instructions the engine steps through.
 *
 * <p>Implements the animation lists in {@code src/animation}. Each instruction
 * is written in the scripts as a short string, for example
 * {@code {"frame 0", "move 3", "wait 1", "frame 5", "move 2", "wait 1"}}.
 *
 * <p>The important thing about this format is that it drives movement as well
 * as pictures. A {@code move} instruction says how many pixels the unit
 * advances, so a unit's walking speed is a property of its animation rather
 * than a separate number, and the footfalls line up with the ground covered.
 */
public record Animation(String name, List<Instruction> instructions) {

    /** What an instruction does. */
    public enum Kind {
        /** Set the base sprite frame. */
        FRAME,
        /** Stop for a number of cycles, returning control to the caller. */
        WAIT,
        /** Advance the unit by a number of pixels. */
        MOVE,
        /** Deal the attack's damage at this point in the swing. */
        ATTACK,
        /** Play a sound. */
        SOUND,
        /** Begin a stretch that a new order may not interrupt. */
        UNBREAKABLE_BEGIN,
        /** End it. */
        UNBREAKABLE_END,
        /** A jump target. */
        LABEL,
        /** Jump to a label with a given chance. */
        RANDOM_GOTO,
        GOTO,
        IF_VAR,
        /** Turn towards the target with a given chance. */
        RANDOM_ROTATE,
        WIGGLE,
        /** Create a unit as an animation side effect. */
        SPAWN_UNIT,
        /** Change an instance variable, such as a revealer's sight range. */
        SET_VAR,
        /** Remove the animated unit immediately. */
        DIE,
        /** Recognised in the data but not acted on yet. */
        OTHER
    }

    /**
     * One step.
     *
     * @param kind    what it does
     * @param value   its numeric argument, or 0
     * @param operand its textual argument, such as a label or sound name
     */
    public record Instruction(Kind kind, int value, String operand) {}

    /** How many instructions the program has. */
    /**
     * How many cycles one pass through this animation takes.
     *
     * <p>The sum of its waits. Branches are not followed, so a looping
     * animation reports the length of one lap, which is what a caller wanting
     * to know "how long until this has played" means.
     */
    public int cycles() {
        int total = 0;
        for (Instruction instruction : instructions) {
            if (instruction.kind() == Kind.WAIT) {
                total += Math.max(0, instruction.value());
            }
        }
        return total;
    }

    public int size() {
        return instructions.size();
    }

    /** The instruction at an index. */
    public Instruction at(int index) {
        return instructions.get(index);
    }

    /** The index of a label, or {@code -1}. */
    public int labelIndex(String label) {
        for (int i = 0; i < instructions.size(); i++) {
            Instruction instruction = instructions.get(i);
            if (instruction.kind() == Kind.LABEL && label.equals(instruction.operand())) {
                return i;
            }
        }
        return -1;
    }

    /** Parses a script's instruction list. */
    public static Animation parse(String name, List<String> lines) {
        List<Instruction> parsed = new java.util.ArrayList<>(lines.size());
        for (String line : lines) {
            parsed.add(parseInstruction(line));
        }
        return new Animation(name, List.copyOf(parsed));
    }

    private static Instruction parseInstruction(String line) {
        String text = line.trim();
        int space = text.indexOf(' ');
        String head = space < 0 ? text : text.substring(0, space);
        String tail = space < 0 ? "" : text.substring(space + 1).trim();

        return switch (head) {
            case "frame" -> new Instruction(Kind.FRAME, number(tail), tail);
            case "wait" -> new Instruction(Kind.WAIT, number(tail), tail);
            case "move" -> new Instruction(Kind.MOVE, number(tail), tail);
            case "attack" -> new Instruction(Kind.ATTACK, 0, tail);
            case "sound" -> new Instruction(Kind.SOUND, 0, tail);
            case "label" -> new Instruction(Kind.LABEL, 0, tail);
            case "random-rotate" -> new Instruction(Kind.RANDOM_ROTATE, number(tail), tail);
            case "random-goto" -> {
                // "random-goto <chance> <label>"
                int split = tail.indexOf(' ');
                if (split < 0) {
                    yield new Instruction(Kind.OTHER, 0, text);
                }
                yield new Instruction(Kind.RANDOM_GOTO,
                        number(tail.substring(0, split)), tail.substring(split + 1).trim());
            }
            // "goto <label>". Without this the runner walked straight through
            // a branch it was told to jump over. The gold mine's Still script
            // is "if-var ... active / frame 0 / wait 4 / frame 0 / wait 1 /
            // goto start / label active / frame 1 ...", so falling through ran
            // the idle frames and the working frames alternately: the light at
            // the mine door changed 79 times in 400 cycles, close to six times
            // a second.
            case "goto" -> new Instruction(Kind.GOTO, 0, tail);
            // "if-var <left> <op> <right> <label>", as CAnimation_IfVar::Init
            // reads it. The operand keeps the whole tail; the runner splits it,
            // because the comparison has to be made against the unit at the
            // moment it runs rather than at parse time.
            case "if-var" -> new Instruction(Kind.IF_VAR, 0, tail);
            case "spawn-unit" -> new Instruction(Kind.SPAWN_UNIT, 0, tail);
            case "set-var" -> new Instruction(Kind.SET_VAR, 0, tail);
            case "die" -> new Instruction(Kind.DIE, 0, tail);
            // "wiggle <x> <y> absolute|heading" -- a pixel nudge to the
            // unit's displacement, which is how a ship bobs at anchor

            case "wiggle" -> new Instruction(Kind.WIGGLE, 0, tail);
            case "unbreakable" -> "begin".equals(tail)
                    ? new Instruction(Kind.UNBREAKABLE_BEGIN, 0, tail)
                    : new Instruction(Kind.UNBREAKABLE_END, 0, tail);
            // rotate, wiggle, spawn-missile and the rest are recognised so an
            // unknown word is still a parse rather than a crash; they become
            // no-ops until the systems that consume them exist.
            default -> new Instruction(Kind.OTHER, 0, text);
        };
    }

    private static int number(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
