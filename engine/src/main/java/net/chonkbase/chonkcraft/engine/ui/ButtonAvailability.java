package net.chonkbase.chonkcraft.engine.ui;

import java.util.List;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.upgrade.DependencyRules;

/**
 * Decides which command buttons a unit may currently use.
 *
 * <p>Implements {@code IsButtonAllowed} together
 * with the checks it dispatches to.
 *
 * <p>Two layers, and both are needed. The named check from the button's
 * {@code Allowed} field runs first and can only veto; then the action's own
 * rule decides. A blacksmith's sword upgrade passes {@code check-single-research}
 * only while nothing else is being researched there, and passes the research
 * rule only if the tech tree allows it and it has not already been bought.
 *
 * <p>Getting this wrong is not a cosmetic matter. With every check passing, a
 * barracks offers knights before the stables exist and a blacksmith sells the
 * same upgrade twice.
 */
public final class ButtonAvailability implements ButtonSet.Availability {

    private final World world;
    private final Unit unit;
    private final DependencyRules dependencies;
    private final boolean networked;

    /**
     * @param world        the world the unit lives in
     * @param unit         the selected unit the panel is showing
     * @param dependencies the tech tree, or null to allow everything the rules
     *                     would otherwise gate
     * @param networked    whether this is a network game, which is all
     *                     {@code check-network} asks
     */
    public ButtonAvailability(World world, Unit unit, DependencyRules dependencies,
            boolean networked) {
        this.world = world;
        this.unit = unit;
        this.dependencies = dependencies;
        this.networked = networked;
    }

    @Override
    public boolean test(UnitButton button) {
        return passesNamedCheck(button) && passesActionCheck(button);
    }

    /**
     * The button's own {@code Allowed} check.
     *
     * <p>Only ever a veto: upstream discards a false result and then goes on to
     * the action check regardless of a true one.
     */
    private boolean passesNamedCheck(UnitButton button) {
        String check = button.allowed();
        if (check == null || check.isEmpty()) {
            return true;
        }
        List<String> args = button.allowArg();
        return switch (check) {
            case "check-true" -> true;
            // The named upgrade must already be researched.
            case "check-upgrade" -> args.stream().allMatch(this::hasUpgrade);
            // Any one of the named unit types will do.
            case "check-units-or" -> args.stream().anyMatch(this::ownsUnitType);
            // Upstream's ButtonCheckNoResearch asks about research and
            // upgrading, not training. What hides a train button while a
            // barracks is busy is the engine rule below, not this one.
            case "check-no-research", "check-single-research" -> !isBusyResearching();
            case "check-upgrade-to" -> isIdle();
            case "check-network" -> networked;
            // Only ever shown in a debug build, which this is not.
            case "check-debug" -> false;
            // An unmodelled check does not hide a button. A missing command is
            // easier to notice and fix than a silently absent one.
            default -> true;
        };
    }

    /** The rule belonging to the action itself. */
    private boolean passesActionCheck(UnitButton button) {
        String action = button.action();
        if (action == null) {
            return false;
        }
        return switch (action) {
            case "stop", "stand-ground", "button", "move", "cancel" -> true;
            case "repair" -> unit.type().repairRange() > 0;
            case "patrol", "explore" -> unit.type().speed() > 0;
            case "attack" -> unit.type().canAttack();
            // Not the same question as whether it can attack: only a siege
            // engine can be told to hit a patch of ground with nothing on it.
            case "attack-ground" -> unit.type().groundAttack();
            // Sending a full worker to harvest again would only make it walk.
            case "harvest" -> unit.carrying() == null || unit.carried() < capacity();
            case "return-goods" -> unit.carrying() != null && unit.carried() > 0;
            // ChonkCraft calls SetTrainingQueue(true), so a busy building keeps
            // its train buttons and appends each paid job behind the current.
            case "train-unit" -> unit.trainingJobCount()
                    < net.chonkbase.chonkcraft.engine.unit.Unit.MAX_TRAINING_JOBS
                    && (unit.producing() == null || world.trainingQueueEnabled())
                    && canProduce(button.value());
            case "upgrade-to", "build", "research" -> canProduce(button.value());
            case "unload" -> unit.type().canTransport() && !unit.cargo().isEmpty();
            case "cast-spell" -> spellIsAvailable(button.value());
            // The three cancels are the reason a building under construction
            // shows one button and a barracks part way through a footman shows
            // ten. Answering true unconditionally puts a cancel icon in the
            // corner of every panel in the game.
            // IsButtonAllowed's CancelUpgrade arm is
            // "CurrentAction() == UpgradeTo || CurrentAction() == Research".
            // This asked about research and training instead, so the one
            // building that actually needs the button -- a town hall part way
            // into a keep -- was the one building that could not offer it,
            // while a barracks making a footman offered a cancel-upgrade it had
            // no upgrade to cancel.
            case "cancel-upgrade" -> unit.upgradingTo() != null || unit.researching() != null;
            case "cancel-train-unit" -> unit.producing() != null;
            case "cancel-build" -> unit.order() == Unit.Order.UNDER_CONSTRUCTION;
            default -> true;
        };
    }

    /**
     * Whether a spell has been researched.
     *
     * <p>{@code SpellIsAvailable}: a spell with no dependency is always there,
     * and one with a dependency waits on that upgrade. The mage's buttons carry
     * the same check in their {@code Allowed} field, but the engine needs its
     * own answer -- autocast and the computer players never go through a
     * button.
     */
    private boolean spellIsAvailable(String ident) {
        if (ident == null || world.spells() == null) {
            return true;
        }
        var spell = world.spells().get(ident);
        String dependency = spell == null ? null : spell.dependUpgrade();
        return dependency == null || dependency.isEmpty() || hasUpgrade(dependency);
    }

    /**
     * Whether the tech tree permits producing something, and whether it is
     * worth producing at all.
     *
     * <p>An upgrade already researched drops off the panel, which is what stops
     * a blacksmith offering the same sword twice.
     */
    private boolean canProduce(String ident) {
        if (ident == null || ident.isEmpty()) {
            return false;
        }
        if (ident.startsWith("upgrade-") && hasUpgrade(ident)) {
            return false;
        }
        // What the mission permits, which is a different question from what
        // the tech tree unlocks: the first human mission allows five unit
        // types and forbids the rest however many buildings you put up.
        var allowed = world.allowed();
        if (allowed != null && !allowed.isAllowed(unit.player(), ident)) {
            return false;
        }
        if (dependencies == null) {
            return true;
        }
        return dependencies.isSatisfied(ident, this::hasRequirement);
    }

    /**
     * Whether one requirement from the tech tree is met.
     *
     * <p>The prefix says which question to ask: {@code upgrade-} means
     * researched, anything else means a completed building or unit of that
     * type standing somewhere.
     */
    private boolean hasRequirement(String ident) {
        return ident.startsWith("upgrade-") ? hasUpgrade(ident) : ownsUnitType(ident);
    }

    private boolean hasUpgrade(String ident) {
        var upgrades = world.upgrades(unit.player());
        return upgrades != null && upgrades.has(ident);
    }

    private boolean ownsUnitType(String ident) {
        // The same count the triggers and the AI use, so a half-built barracks
        // cannot enable a footman here while failing to win the mission that
        // asked for a barracks over there.
        return world.unitTypesCount(unit.player(), ident) > 0;
    }

    /** Whether this building is already researching or upgrading. */
    private boolean isBusyResearching() {
        return unit.researching() != null || unit.producing() != null;
    }

    private boolean isIdle() {
        return unit.researching() == null && unit.producing() == null;
    }

    /** How much of its current resource the unit can hold. */
    private int capacity() {
        var info = unit.carrying() == null ? null : unit.type().gathering().get(unit.carrying());
        return info == null ? Integer.MAX_VALUE : info.capacity();
    }
}
