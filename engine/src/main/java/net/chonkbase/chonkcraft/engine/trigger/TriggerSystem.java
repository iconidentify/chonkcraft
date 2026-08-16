package net.chonkbase.chonkcraft.engine.trigger;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.unit.Unit;

/**
 * Campaign triggers compiled into a small, fail-closed native program format.
 *
 * <p>Conditions run once per cycle, every cycle, exactly as
 * {@code TriggersEachCycle} is called from the game loop before the units
 * act. This used to run once a second
 * under a comment claiming upstream did the same, and level08h is why it
 * cannot: the mission's opening trigger declares the peasant slot's
 * enemies, upstream has it fired before any unit has moved, and a
 * once-a-second port left the seven-peasant siege aiming at the wrong war
 * for its whole first think.
 */
public final class TriggerSystem {

    /** One armed delayed action whose countdown has already started. */
    public record SavedDelay(int trigger, int remaining) {
        public SavedDelay {
            if (trigger < 0 || remaining < 0) {
                throw new IllegalArgumentException("invalid saved trigger delay");
            }
        }
    }

    /** All mutable trigger state needed to resume the same mission timeline. */
    public record SavedState(List<Integer> armed, List<String> flags,
            List<SavedDelay> delays) {
        public SavedState {
            armed = List.copyOf(armed == null ? List.of() : armed);
            flags = List.copyOf(flags == null ? List.of() : flags);
            delays = List.copyOf(delays == null ? List.of() : delays);
        }
    }

    /** One interpreter-free postfix condition and typed action declaration. */
    public record ProgramSpec(String condition, String action) {
        public ProgramSpec {
            if (condition == null || condition.isBlank() || action == null || action.isBlank()) {
                throw new IllegalArgumentException("empty native trigger program");
            }
        }
    }

    /** How the mission ended, or that it has not. */
    public enum Outcome {
        RUNNING,
        VICTORY,
        DEFEAT,
        DRAW
    }

    /** Mutable execution state for one native trigger. */
    private static final class NativeTrigger {
        private final int installedAt;
        private final String[] condition;
        private final String[] action;
        private int remaining = -1;

        private NativeTrigger(int installedAt, ProgramSpec program) {
            this.installedAt = installedAt;
            this.condition = program.condition().split("\\s+");
            this.action = program.action().split("\\s+");
        }
    }

    /** How many triggers the script has ever added, armed or since fired. */
    private int installed;

    private final World world;
    private final List<NativeTrigger> nativeTriggers = new ArrayList<>();
    private final java.util.Set<String> nativeFlags = new java.util.LinkedHashSet<>();
    private Outcome outcome = Outcome.RUNNING;

    /** Which trigger decided the mission, and out of how many. Both -1 until one does. */
    private int decidedBy = -1;

    private int decidedOfHowMany = -1;

    /** The index of the trigger that ended the mission, or -1. */
    public int decidedBy() {
        return decidedBy;
    }

    /** How many triggers there were when one of them ended it, or -1. */
    public int decidedOfHowMany() {
        return decidedOfHowMany;
    }

    /**
     * Every trigger that failed and was dropped.
     *
     * <p>Kept rather than logged so a test can assert on it. A mission whose
     * victory condition threw is a mission that cannot be completed, and that
     * should be loud.
     */
    private final java.util.Set<String> failures = new java.util.LinkedHashSet<>();

    /** What went wrong in any trigger, in the order it first went wrong. */
    public java.util.List<String> failures() {
        return List.copyOf(failures);
    }

    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /** Which player the {@code GetThisPlayer} calls refer to. */
    private int thisPlayer;

    /** Creates a trigger system from the sealed native mission catalog. */
    public TriggerSystem(World world, int thisPlayer, List<ProgramSpec> programs) {
        this.world = world;
        this.thisPlayer = thisPlayer;
        for (ProgramSpec program : programs) {
            nativeTriggers.add(new NativeTrigger(installed++, program));
        }
    }

    public Outcome outcome() {
        return outcome;
    }

    public int triggerCount() {
        return nativeTriggers.size();
    }

    public void setThisPlayer(int thisPlayer) {
        this.thisPlayer = thisPlayer;
    }

    /**
     * Evaluates every trigger once.
     *
     * <p>A trigger that fires is removed, matching upstream: a victory
     * condition should not fire twice, and a one-shot reinforcement trigger
     * should not keep spawning.
     */
    public void evaluate() {
        if (outcome != Outcome.RUNNING) {
            return;
        }
        List<NativeTrigger> completed = new ArrayList<>();
        for (NativeTrigger trigger : nativeTriggers) {
            try {
                if (!evaluateProgram(trigger.condition)) {
                    continue;
                }
                Outcome before = outcome;
                boolean keep = executeAction(trigger);
                if (!keep) {
                    completed.add(trigger);
                }
                if (outcome != before) {
                    decidedBy = trigger.installedAt;
                    decidedOfHowMany = installed;
                }
            } catch (RuntimeException error) {
                failures.add(describe(error));
                completed.add(trigger);
            }
        }
        nativeTriggers.removeAll(completed);
    }

    /**
     * Which triggers have not fired yet, by their place in the script.
     *
     * <p>What a saved game has to carry. Resuming a campaign save reruns the
     * mission script, and the script's own {@code AddTrigger} calls arm every
     * trigger again -- including the ones the player had already used. A
     * one-shot reinforcement trigger delivered its troops a second time, and a
     * dialogue or objective-update trigger repeated itself; the mission was no
     * longer the one that had been saved. Upstream avoids it by saving the
     * surviving trigger list rather than rebuilding it from the script
     *
     */
    public List<Integer> armedTriggers() {
        List<Integer> armed = new ArrayList<>();
        for (NativeTrigger trigger : nativeTriggers) {
            armed.add(trigger.installedAt);
        }
        java.util.Collections.sort(armed);
        return armed;
    }

    /** Captures survivor identity, one-shot flags, and in-flight countdowns. */
    public SavedState savedState() {
        List<Integer> armed = armedTriggers();
        List<String> flags = new ArrayList<>(nativeFlags);
        java.util.Collections.sort(flags);
        List<SavedDelay> delays = new ArrayList<>();
        for (NativeTrigger trigger : nativeTriggers) {
            if (trigger.remaining >= 0) {
                delays.add(new SavedDelay(trigger.installedAt, trigger.remaining));
            }
        }
        delays.sort(java.util.Comparator.comparingInt(SavedDelay::trigger));
        return new SavedState(armed, flags, delays);
    }

    /**
     * Drops every trigger the save says had already fired.
     *
     * <p>Called after a mission script has re-armed the lot, with the list the
     * save carried. A null list means the save predates this and says nothing
     * about triggers, in which case the mission keeps what its script gave it
     * -- the old behaviour, which is the right reading of "no information".
     */
    public void retainArmed(java.util.Collection<Integer> armed) {
        if (armed == null) {
            return;
        }
        java.util.Set<Integer> keep = new java.util.HashSet<>(armed);
        nativeTriggers.removeIf(trigger -> !keep.contains(trigger.installedAt));
    }

    /**
     * Restores a complete version-four trigger checkpoint after the mission
     * script has recreated its original program list.
     *
     * <p>Unknown or duplicate trigger indices fail closed. Silently applying
     * a countdown to a different program is worse than refusing the save: it
     * changes which condition wins the campaign.</p>
     */
    public void restoreState(SavedState state) {
        if (state == null) {
            return;
        }
        java.util.Map<Integer, NativeTrigger> byIndex = new java.util.HashMap<>();
        for (NativeTrigger trigger : nativeTriggers) {
            byIndex.put(trigger.installedAt, trigger);
        }
        java.util.Set<Integer> armed = new java.util.HashSet<>();
        for (int index : state.armed()) {
            if (!byIndex.containsKey(index) || !armed.add(index)) {
                throw new IllegalArgumentException(
                        "saved trigger index is unknown or duplicated: " + index);
            }
        }
        nativeTriggers.removeIf(trigger -> !armed.contains(trigger.installedAt));
        nativeFlags.clear();
        nativeFlags.addAll(state.flags());
        java.util.Set<Integer> restoredDelays = new java.util.HashSet<>();
        for (SavedDelay delay : state.delays()) {
            NativeTrigger trigger = byIndex.get(delay.trigger());
            if (trigger == null || !armed.contains(delay.trigger())
                    || !restoredDelays.add(delay.trigger())) {
                throw new IllegalArgumentException(
                        "saved trigger delay is unknown, spent, or duplicated: "
                                + delay.trigger());
            }
            trigger.remaining = delay.remaining();
        }
    }

    /** Runs the triggers, as every cycle does. */
    public void tick() {
        evaluate();
    }

    // ------------------------------------------------------- native programs

    private enum Marker {
        THIS,
        NIL
    }

    private boolean evaluateProgram(String[] tokens) {
        List<Object> stack = new ArrayList<>();
        for (String token : tokens) {
            if (token.isEmpty()) {
                continue;
            }
            if (token.startsWith("N") && token.length() > 1
                    && (Character.isDigit(token.charAt(1)) || token.charAt(1) == '-')) {
                stack.add(Integer.parseInt(token.substring(1)));
            } else if (token.startsWith("Q")) {
                stack.add(decode(token.substring(1)));
            } else if (token.startsWith("F")) {
                String flag = decode(token.substring(1));
                stack.add(nativeFlags.contains(flag) ? Boolean.TRUE : Marker.NIL);
            } else {
                switch (token) {
                    case "THIS" -> stack.add(Marker.THIS);
                    case "NIL" -> stack.add(Marker.NIL);
                    case "TRUE" -> stack.add(Boolean.TRUE);
                    case "FALSE" -> stack.add(Boolean.FALSE);
                    case "TOTAL" -> stack.add(countUnits(player(pop(stack)), null));
                    case "UNIT_COUNT" -> {
                        String type = string(pop(stack));
                        int owner = player(pop(stack));
                        stack.add(countUnits(owner, type));
                    }
                    case "OPPONENTS" -> stack.add(countOpponents(player(pop(stack))));
                    case "UNITS_AT" -> {
                        int y2 = number(pop(stack));
                        int x2 = number(pop(stack));
                        int y1 = number(pop(stack));
                        int x1 = number(pop(stack));
                        String type = string(pop(stack));
                        int owner = player(pop(stack));
                        stack.add(countUnitsAt(owner, type, x1, y1, x2, y2));
                    }
                    case "NEAR", "RESCUED_NEAR" -> {
                        String centre = string(pop(stack));
                        String type = string(pop(stack));
                        int wanted = number(pop(stack));
                        String operator = string(pop(stack));
                        Object owner = pop(stack);
                        stack.add(nearUnit(owner, operator, wanted, type, centre,
                                "RESCUED_NEAR".equals(token)));
                    }
                    case "ADD" -> {
                        int right = number(pop(stack));
                        int left = number(pop(stack));
                        stack.add(left + right);
                    }
                    case "AND" -> {
                        boolean right = truthy(pop(stack));
                        boolean left = truthy(pop(stack));
                        stack.add(left && right);
                    }
                    case "OR" -> {
                        boolean right = truthy(pop(stack));
                        boolean left = truthy(pop(stack));
                        stack.add(left || right);
                    }
                    case "EQ", "NE", "GE", "GT", "LE", "LT" -> {
                        Object right = pop(stack);
                        Object left = pop(stack);
                        stack.add(compareValues(left, token, right));
                    }
                    default -> throw new IllegalStateException("unknown native trigger opcode "
                            + token);
                }
            }
        }
        if (stack.size() != 1) {
            throw new IllegalStateException("native trigger left " + stack.size() + " values");
        }
        return truthy(stack.get(0));
    }

    /** Returns true while the action wants to remain armed. */
    private boolean executeAction(NativeTrigger trigger) {
        String[] action = trigger.action;
        return switch (action[0]) {
            case "VICTORY" -> {
                outcome = Outcome.VICTORY;
                yield false;
            }
            case "DEFEAT" -> {
                outcome = Outcome.DEFEAT;
                yield false;
            }
            case "DRAW" -> {
                outcome = Outcome.DRAW;
                yield false;
            }
            case "NOOP" -> {
                requireAction(action, 1);
                yield false;
            }
            case "SET_FLAG" -> {
                requireAction(action, 2);
                nativeFlags.add(quoted(action[1]));
                yield false;
            }
            case "DIPLOMACY" -> {
                if (action.length < 4 || (action.length - 1) % 3 != 0) {
                    throw new IllegalStateException("invalid diplomacy action");
                }
                for (int i = 1; i < action.length; i += 3) {
                    world.setDiplomacy(Integer.parseInt(action[i]), quoted(action[i + 1]),
                            Integer.parseInt(action[i + 2]));
                }
                yield false;
            }
            case "DELAYED_VICTORY" -> {
                requireAction(action, 3);
                int initial = Integer.parseInt(action[1]);
                if (trigger.remaining < 0) {
                    trigger.remaining = initial;
                }
                trigger.remaining--;
                if (trigger.remaining <= 0) {
                    outcome = Outcome.VICTORY;
                    yield false;
                }
                yield true;
            }
            default -> throw new IllegalStateException("unknown native trigger action "
                    + action[0]);
        };
    }

    private static void requireAction(String[] action, int length) {
        if (action.length != length) {
            throw new IllegalStateException("invalid native action " + String.join(" ", action));
        }
    }

    private static Object pop(List<Object> stack) {
        if (stack.isEmpty()) {
            throw new IllegalStateException("native trigger stack underflow");
        }
        return stack.remove(stack.size() - 1);
    }

    private int player(Object value) {
        return value == Marker.THIS ? thisPlayer : number(value);
    }

    private static int number(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalStateException("expected number, got " + value);
    }

    private static String string(Object value) {
        if (value instanceof String text) {
            return text;
        }
        throw new IllegalStateException("expected string, got " + value);
    }

    private static boolean truthy(Object value) {
        return value != Marker.NIL && !Boolean.FALSE.equals(value);
    }

    private static boolean compareValues(Object left, String operator, Object right) {
        if (left instanceof Number && right instanceof Number) {
            int a = number(left);
            int b = number(right);
            return switch (operator) {
                case "EQ" -> a == b;
                case "NE" -> a != b;
                case "GE" -> a >= b;
                case "GT" -> a > b;
                case "LE" -> a <= b;
                case "LT" -> a < b;
                default -> false;
            };
        }
        boolean equal = java.util.Objects.equals(left, right);
        return "NE".equals(operator) ? !equal : "EQ".equals(operator) && equal;
    }

    private static String decode(String encoded) {
        return new String(Base64.getUrlDecoder().decode(encoded),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String quoted(String token) {
        if (!token.startsWith("Q")) {
            throw new IllegalStateException("expected encoded string, got " + token);
        }
        return decode(token.substring(1));
    }

    // --------------------------------------------------------------- queries

    /**
     * {@code UnitTypesCount}, which excludes buildings still going up.
     *
     * @see World#unitTypesCount(int, String)
     */
    private int countUnits(int player, String typeIdent) {
        return world.unitTypesCount(player, typeIdent);
    }


    /** Native mission-DSL form of the proximity query. */
    private boolean nearUnit(Object ownerValue, String operator, int wanted,
            String type, String centreType, boolean rescuedOnly) {
        java.util.function.IntPredicate owner;
        if (ownerValue == Marker.THIS || "this".equals(ownerValue)) {
            owner = player -> player == thisPlayer;
        } else if ("any".equals(ownerValue)) {
            owner = player -> true;
        } else if (ownerValue instanceof Number number) {
            int expected = number.intValue();
            owner = player -> player == expected;
        } else {
            return false;
        }
        for (Unit centre : world.units()) {
            if (!centre.isAlive() || centre.type() == null
                    || !centreType.equals(centre.type().ident())) {
                continue;
            }
            int found = 0;
            for (Unit near : world.units()) {
                if (near == centre || !near.isAlive() || near.type() == null
                        || (rescuedOnly && !near.wasRescued())
                        || (!"any".equals(type) && !type.equals(near.type().ident()))
                        || !owner.test(near.player())) {
                    continue;
                }
                if (centre.distanceTo(near) <= 1) {
                    found++;
                }
            }
            if (compare(found, operator, wanted)) {
                return true;
            }
        }
        return false;
    }

    /** The comparison spellings {@code GetCompareFunction} accepts. */
    private static boolean compare(int left, String operator, int right) {
        return switch (operator) {
            case "==", "=" -> left == right;
            case ">=" -> left >= right;
            case ">" -> left > right;
            case "<=" -> left <= right;
            case "<" -> left < right;
            case "!=" -> left != right;
            default -> false;
        };
    }

    /**
     * Players still holding something who are at war with the one asking.
     *
     * <p>{@code CclGetNumOpponents}: anyone with units who is an enemy of the
     * asker, or whose enemy the asker is. Not "anyone active", which is what
     * this used to ask. A map's player types say who controls a slot, not who
     * is fighting whom, and the expansion uses slots the base campaigns do not
     * -- so on those maps every enemy was counted as no enemy at all and the
     * victory condition, which is "no opponents left", was true before a shot
     * was fired.
     */
    private int countOpponents(int asking) {
        int count = 0;
        for (Player player : world.players()) {
            int other = player.index();
            if (other == asking || countUnits(other, null) == 0) {
                continue;
            }
            if (world.isEnemyPlayer(asking, other) || world.isEnemyPlayer(other, asking)) {
                count++;
            }
        }
        return count;
    }

    private int countUnitsAt(int player, String typeIdent,
            int x1, int y1, int x2, int y2) {
        int count = 0;
        for (Unit unit : world.units()) {
            if (!unit.isAlive()) {
                continue;
            }
            // Player -1 or "any" means every player, which the scripts use for
            // "is anything in this area".
            if (player >= 0 && unit.player() != player) {
                continue;
            }
            if (!"any".equals(typeIdent) && !unit.type().ident().equals(typeIdent)) {
                continue;
            }
            if (unit.tileX() >= x1 && unit.tileX() <= x2
                    && unit.tileY() >= y1 && unit.tileY() <= y2) {
                count++;
            }
        }
        return count;
    }

}
