package net.chonkbase.chonkcraft.engine.network;

import java.util.List;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;

/**
 * Turns released commands into orders on the world.
 *
 * <p>Every path into the simulation goes through here, in single player as
 * well as multiplayer. That is deliberate: if the local player's clicks took a
 * shortcut, the two paths would drift and a desync would only show up on a
 * network game, which is the worst place to find it.
 */
public final class CommandApplier {

    private final World world;
    private final List<UnitType> roster;

    /**
     * @param roster the unit types in a fixed order, so a type can travel over
     *               the wire as an index. Both machines build it the same way
     *               from the same scripts.
     */
    public CommandApplier(World world, List<UnitType> roster) {
        this.world = world;
        this.roster = List.copyOf(roster);
    }

    /** The wire index of a type, or {@code -1}. */
    public int indexOf(UnitType type) {
        return roster.indexOf(type);
    }

    /** The type at a wire index, or {@code null}. */
    public UnitType typeAt(int index) {
        return index >= 0 && index < roster.size() ? roster.get(index) : null;
    }

    /**
     * The upgrades and spells in a fixed order.
     *
     * <p>Sorted rather than left in declaration order. The roster can be taken
     * as the scripts give it because both machines read the same files in the
     * same sequence; these come from tables whose iteration order is a
     * property of the map they are held in, so the order is imposed here
     * instead of trusted.
     */
    private List<String> upgradeNames = List.of();
    private List<String> spellNames = List.of();

    public void setUpgrades(java.util.Collection<String> idents) {
        this.upgradeNames = idents.stream().sorted().toList();
    }

    public void setSpells(java.util.Collection<String> idents) {
        this.spellNames = idents.stream().sorted().toList();
    }

    public int indexOfUpgrade(String ident) {
        return upgradeNames.indexOf(ident);
    }

    public int indexOfSpell(String ident) {
        return spellNames.indexOf(ident);
    }

    public String upgradeAt(int index) {
        return index >= 0 && index < upgradeNames.size() ? upgradeNames.get(index) : null;
    }

    public String spellAt(int index) {
        return index >= 0 && index < spellNames.size() ? spellNames.get(index) : null;
    }

    /** Applies a batch, in the order given. */
    public void applyAll(List<GameCommand> commands) {
        for (GameCommand command : commands) {
            apply(command);
        }
    }

    /**
     * Applies one command.
     *
     * <p>Every command is checked against the world before it is obeyed. A
     * command naming a dead unit, or a unit belonging to somebody else, is
     * dropped rather than trusted: in a network game it arrived from another
     * machine, and by the time it does the unit it names may be gone.
     *
     * @return whether the command was accepted by the authoritative world
     */
    public boolean apply(GameCommand command) {
        if (command.kind() == GameCommand.Kind.NONE) {
            return false;
        }
        if (command.kind() == GameCommand.Kind.QUIT) {
            world.setDepartedControlMask(command.player(), command.departureControlMask());
            return true;
        }
        // A ping names no unit: it is a player pointing at the ground, and it
        // has to be handled before the unit checks reject it for having none.
        if (command.kind() == GameCommand.Kind.PING) {
            world.addPing(command.player(), command.x(), command.y());
            return true;
        }
        Unit unit = findUnit(command.unitId());
        if (unit == null || !unit.isAlive()) {
            return false;
        }
        // Dismissing something that is not yours is allowed for exactly one
        // kind of unit, and upstream says so in as many words:
        // IsAValidCommand_Dismiss waves
        // through any Dismiss naming a type with ClicksToExplode and sends
        // everything else to the ordinary ownership check. That carve-out is
        // the sheep: a critter belongs to the neutral player, so a player who
        // clicks one ten times is dismissing somebody else's unit.
        boolean explodable = command.kind() == GameCommand.Kind.DISMISS
                && clicksToExplode(unit) > 0;
        if (!explodable && !world.canControl(command.player(), unit.player())) {
            return false;
        }

        if (command.queued() && shouldWait(unit, command.kind())) {
            Unit.QueuedOrder queued = queuedOrder(command);
            if (queued != null) {
                unit.enqueueOrder(queued);
            }
            return queued != null;
        }
        if (!command.queued() && isQueueable(command.kind())) {
            unit.clearQueuedOrders();
            unit.setSavedOrder(null);
        }

        boolean accepted = true;
        switch (command.kind()) {
            case MOVE -> accepted = world.orderMove(unit, command.x(), command.y());
            case STOP -> world.orderStop(unit);
            case HARVEST -> accepted = world.orderHarvest(unit, command.x(), command.y());
            case ATTACK -> {
                Unit target = findUnit(command.targetId());
                if (target != null && target.isAlive()) {
                    accepted = world.orderAttack(unit, target);
                } else {
                    accepted = false;
                }
            }
            case BUILD -> {
                UnitType what = typeAt(command.typeIndex());
                if (what != null) {
                    accepted = world.orderBuild(unit, what, command.x(), command.y());
                } else {
                    accepted = false;
                }
            }
            case TRAIN -> {
                UnitType what = typeAt(command.typeIndex());
                if (what != null) {
                    accepted = world.orderTrain(unit, what);
                } else {
                    accepted = false;
                }
            }
            case RESEARCH -> {
                String ident = upgradeAt(command.typeIndex());
                if (ident != null) {
                    accepted = world.orderResearch(unit, ident);
                } else {
                    accepted = false;
                }
            }
            case CAST -> {
                String ident = spellAt(command.typeIndex());
                net.chonkbase.chonkcraft.engine.spell.Spell spell = ident == null
                        || world.spells() == null ? null : world.spells().get(ident);
                if (spell == null) {
                    accepted = false;
                } else if (spell.target()
                        == net.chonkbase.chonkcraft.engine.spell.Spell.Target.POSITION) {
                    accepted = world.orderCast(unit, ident, command.x(), command.y());
                } else {
                    Unit target = command.targetId() == 0 ? unit : findUnit(command.targetId());
                    accepted = target != null && world.orderCast(unit, ident, target);
                }
            }
            case PATROL -> accepted = world.orderPatrol(unit, command.x(), command.y());
            case REPAIR -> {
                Unit target = findUnit(command.targetId());
                if (target != null) {
                    accepted = world.orderRepair(unit, target);
                } else {
                    accepted = false;
                }
            }
            case EXPLORE -> accepted = world.orderExplore(unit);
            case RETURN_GOODS -> accepted = world.orderReturnGoods(unit);
            case STAND_GROUND -> world.orderStandGround(unit);
            case ATTACK_GROUND -> accepted = world.orderAttackGround(
                    unit, command.x(), command.y());
            case ATTACK_MOVE -> accepted = world.orderAttackMove(
                    unit, command.x(), command.y());
            // The position is where the player pointed; zero means "here",
            // which is what the coast shortcut on the button sends.
            case UNLOAD -> accepted = world.orderUnload(unit,
                    command.x() == 0 && command.y() == 0 ? unit.tileX() : command.x(),
                    command.x() == 0 && command.y() == 0 ? unit.tileY() : command.y(),
                    null);
            case BOARD -> {
                Unit transport = findUnit(command.targetId());
                if (transport != null) {
                    accepted = world.orderBoard(unit, transport);
                } else {
                    accepted = false;
                }
            }
            case FOLLOW -> {
                Unit target = findUnit(command.targetId());
                if (target != null) {
                    accepted = world.orderFollow(unit, target);
                } else {
                    accepted = false;
                }
            }
            case UNLOAD_ONE -> {
                Unit passenger = findUnit(command.targetId());
                if (passenger != null) {
                    // Clicking a cargo icon lands that one where the boat is
                    // if it can, and otherwise sails to somewhere it can --
                    // the same order as the button, aimed at one passenger.
                    accepted = world.unloadOne(unit, passenger);
                    if (!accepted) {
                        accepted = world.orderUnload(unit,
                                command.x() == 0 && command.y() == 0 ? unit.tileX() : command.x(),
                                command.x() == 0 && command.y() == 0 ? unit.tileY() : command.y(),
                                passenger);
                    }
                } else {
                    accepted = false;
                }
            }
            case AUTOCAST -> {
                // x carries the switch, not a coordinate: turning it off names
                // no spell, so the index is only read when it is going on.
                String ident = command.x() == 0 ? null : spellAt(command.typeIndex());
                if (command.x() == 0 || ident != null) {
                    accepted = world.setAutoCast(unit, ident);
                } else {
                    accepted = false;
                }
            }
            // The command panel's own eight. Every one of these spends or
            // refunds a player's gold, and all eight used to be called
            // straight off the screen: a network game came apart on the first
            // "Train Peasant", because the four hundred gold left one
            // treasury and not the other and nothing after that agreed.
            case UPGRADE_TO -> {
                UnitType what = typeAt(command.typeIndex());
                if (what != null) {
                    accepted = world.orderUpgradeTo(unit, what);
                } else {
                    accepted = false;
                }
            }
            case CANCEL_TRAIN -> accepted = world.cancelTraining(unit);
            case CANCEL_RESEARCH -> accepted = world.cancelResearch(unit);
            case CANCEL_UPGRADE_TO -> accepted = world.cancelUpgradeTo(unit);
            case CANCEL_BUILD -> accepted = world.cancelConstruction(unit);
            case RALLY_POINT -> accepted = world.setRallyPoint(
                    unit, command.x(), command.y());
            case DISMISS -> {
                // Two calls for what upstream says in one. LetUnitDie(unit,
                // true) throws the type's own missile and then kills the unit;
                // World.hit puts missile-critter-explosion in the air and
                // cannot hurt anything with it, and World.kill with no killer
                // credits nobody.
                world.hit(unit, unit);
                world.kill(unit);
            }
            case NONE, QUIT -> accepted = false;
        }
        return accepted;
    }

    /**
     * How many clicks in a row this unit's type takes before it blows up.
     *
     * <p>{@code unit-critter} declares ten and nothing else in the game
     * declares it at all.
     */
    private static int clicksToExplode(Unit unit) {
        return unit.type() == null ? 0 : unit.type().clicksToExplode();
    }

    /** Whether a shifted command belongs behind something already in flight. */
    private static boolean shouldWait(Unit unit, GameCommand.Kind kind) {
        return isQueueable(kind)
                && (unit.order() != Unit.Order.STILL || unit.hasQueuedOrders()
                        || unit.pendingBuild() != null);
    }

    private static boolean isQueueable(GameCommand.Kind kind) {
        return switch (kind) {
            case MOVE, ATTACK, HARVEST, BUILD, CAST, PATROL, REPAIR, EXPLORE,
                    RETURN_GOODS, STAND_GROUND, ATTACK_GROUND, ATTACK_MOVE,
                    BOARD, FOLLOW -> true;
            default -> false;
        };
    }

    /** Resolves a wire command into the simulation objects it will need later. */
    private Unit.QueuedOrder queuedOrder(GameCommand command) {
        Unit target = command.targetId() == 0 ? null : findUnit(command.targetId());
        UnitType type = command.kind() == GameCommand.Kind.BUILD
                ? typeAt(command.typeIndex()) : null;
        String value = command.kind() == GameCommand.Kind.CAST
                ? spellAt(command.typeIndex()) : null;
        Unit.QueuedOrderKind kind = switch (command.kind()) {
            case MOVE -> Unit.QueuedOrderKind.MOVE;
            case ATTACK -> Unit.QueuedOrderKind.ATTACK;
            case HARVEST -> Unit.QueuedOrderKind.HARVEST;
            case BUILD -> Unit.QueuedOrderKind.BUILD;
            case CAST -> Unit.QueuedOrderKind.CAST;
            case PATROL -> Unit.QueuedOrderKind.PATROL;
            case REPAIR -> Unit.QueuedOrderKind.REPAIR;
            case EXPLORE -> Unit.QueuedOrderKind.EXPLORE;
            case RETURN_GOODS -> Unit.QueuedOrderKind.RETURN_GOODS;
            case STAND_GROUND -> Unit.QueuedOrderKind.STAND_GROUND;
            case ATTACK_GROUND -> Unit.QueuedOrderKind.ATTACK_GROUND;
            case ATTACK_MOVE -> Unit.QueuedOrderKind.ATTACK_MOVE;
            case BOARD -> Unit.QueuedOrderKind.BOARD;
            case FOLLOW -> Unit.QueuedOrderKind.FOLLOW;
            default -> null;
        };
        if (kind == null
                || (kind == Unit.QueuedOrderKind.BUILD && type == null)
                || (kind == Unit.QueuedOrderKind.CAST && value == null)
                || ((kind == Unit.QueuedOrderKind.ATTACK
                        || kind == Unit.QueuedOrderKind.REPAIR
                        || kind == Unit.QueuedOrderKind.BOARD
                        || kind == Unit.QueuedOrderKind.FOLLOW) && target == null)) {
            return null;
        }
        return new Unit.QueuedOrder(kind, command.x(), command.y(), target, type, value);
    }

    private Unit findUnit(int id) {
        for (Unit unit : world.units()) {
            if (unit.id() == id) {
                return unit;
            }
        }
        return null;
    }
}
