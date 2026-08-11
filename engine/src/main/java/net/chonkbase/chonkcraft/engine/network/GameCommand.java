package net.chonkbase.chonkcraft.engine.network;

/**
 * A player's order, in the form that travels over the wire.
 *
 * <p>Only orders are sent, never game state. Every machine runs the same
 * simulation over the same orders and arrives at the same result, which is
 * what makes a Warcraft II network game a few dozen bytes a second rather
 * than a stream of unit positions.
 *
 * <p>Units are named by id rather than by reference for the same reason: the
 * id is stable across machines because both created the same units in the
 * same order from the same map.
 *
 * @param kind      what to do
 * @param player    who is ordering it
 * @param unitId    the unit being ordered, or 0
 * @param x         a tile column, meaning depends on the kind
 * @param y         a tile row
 * @param targetId  the unit being targeted, or 0
 * @param typeIndex an index into the roster for build and train orders
 * @param queued    whether to append this behind the unit's current order
 */
public record GameCommand(Kind kind, int player, int unitId, int x, int y, int targetId,
        int typeIndex, boolean queued) {

    /** Why a player stopped participating in a running match. */
    public enum DepartureReason {
        /** A slot that never had a network peer. Not shown to players. */
        BOOTSTRAP,
        /** The player deliberately left the running match. */
        LEFT,
        /** The host adjudicated a 45-second network timeout. */
        TIMEOUT
    }

    /** What a command asks for. */
    public enum Kind {
        /** Nothing happened this net cycle. Still sent, to prove liveness. */
        NONE,
        MOVE,
        ATTACK,
        STOP,
        HARVEST,
        BUILD,
        TRAIN,
        RESEARCH,
        CAST,
        PATROL,
        REPAIR,
        EXPLORE,
        RETURN_GOODS,
        STAND_GROUND,
        ATTACK_GROUND,
        UNLOAD,
        /** Put one named passenger ashore. */
        UNLOAD_ONE,
        /** Walk to a transport and get aboard it. */
        BOARD,
        /** Point at a spot on the map, for everyone to see. */
        PING,
        /** A player leaving. */
        QUIT,
        /**
         * Turn a caster's standing spell on or off.
         *
         * <p>Kept in its original position because the ordinal is the wire
         * tag: new kinds go after it rather than renumbering existing ones.
         */
        AUTOCAST,
        /** Keep up with a friendly unit rather than walking to where it was. */
        FOLLOW,
        /**
         * Turn a building into the next one up: a Town Hall into a Keep.
         *
         * <p>These seven are the command-panel actions that used to call the
         * world where they stood. They are at the end of the enum because the
         * ordinal is the wire tag and the existing kinds could not move.
         */
        UPGRADE_TO,
        /** Stop what a building is making and give part of the cost back. */
        CANCEL_TRAIN,
        /** Stop an upgrade being researched. */
        CANCEL_RESEARCH,
        /** Stop a building becoming a better building. */
        CANCEL_UPGRADE_TO,
        /** Abandon a half-built site. */
        CANCEL_BUILD,
        /** Point a producing building's output at a square. */
        RALLY_POINT,
        /** Get rid of a unit where it stands, which is how a critter goes off. */
        DISMISS,
        /**
         * Advance on a place, fighting whatever comes into reach.
         *
         * <p>Upstream sends it as an ordinary attack with no target --
         * {@code SendCommandAttack(unit, pos, nullptr, flush)},
         * but this implementation's ATTACK names
         * a unit and nothing else, so the tile form is its own kind.
         */
        ATTACK_MOVE
    }

    /** The empty command sent when a player did nothing. */
    public static GameCommand none(int player) {
        return new GameCommand(Kind.NONE, player, 0, 0, 0, 0, 0, false);
    }

    public static GameCommand move(int player, int unitId, int x, int y) {
        return new GameCommand(Kind.MOVE, player, unitId, x, y, 0, 0, false);
    }

    public static GameCommand attack(int player, int unitId, int targetId) {
        return new GameCommand(Kind.ATTACK, player, unitId, 0, 0, targetId, 0, false);
    }

    public static GameCommand stop(int player, int unitId) {
        return new GameCommand(Kind.STOP, player, unitId, 0, 0, 0, 0, false);
    }

    public static GameCommand harvest(int player, int unitId, int x, int y) {
        return new GameCommand(Kind.HARVEST, player, unitId, x, y, 0, 0, false);
    }

    public static GameCommand build(int player, int unitId, int typeIndex, int x, int y) {
        return new GameCommand(Kind.BUILD, player, unitId, x, y, 0, typeIndex, false);
    }

    /**
     * @param upgradeIndex an index into the upgrade list, for the same reason
     *                     build and train carry a roster index: an identifier
     *                     is a string and a command is a fixed-width record
     */
    public static GameCommand research(int player, int buildingId, int upgradeIndex) {
        return new GameCommand(Kind.RESEARCH, player, buildingId, 0, 0, 0, upgradeIndex, false);
    }

    /** @param spellIndex an index into the spell list, likewise */
    public static GameCommand cast(int player, int unitId, int targetId, int spellIndex) {
        return new GameCommand(Kind.CAST, player, unitId, 0, 0, targetId, spellIndex, false);
    }

    /**
     * Sets or clears a caster's standing spell.
     *
     * <p>A command rather than a local flick of a switch because it changes
     * what a unit does on its own: a mage told to cast on sight spends mana
     * and fires missiles, and a machine that had not been told would disagree
     * with one that had within a second.
     *
     * @param on whether to turn it on; off ignores the spell index
     */
    public static GameCommand autoCast(int player, int unitId, int spellIndex, boolean on) {
        return new GameCommand(Kind.AUTOCAST, player, unitId, on ? 1 : 0, 0, 0, spellIndex,
                false);
    }

    public static GameCommand patrol(int player, int unitId, int x, int y) {
        return new GameCommand(Kind.PATROL, player, unitId, x, y, 0, 0, false);
    }

    public static GameCommand repair(int player, int unitId, int targetId) {
        return new GameCommand(Kind.REPAIR, player, unitId, 0, 0, targetId, 0, false);
    }

    public static GameCommand explore(int player, int unitId) {
        return new GameCommand(Kind.EXPLORE, player, unitId, 0, 0, 0, 0, false);
    }

    public static GameCommand returnGoods(int player, int unitId) {
        return new GameCommand(Kind.RETURN_GOODS, player, unitId, 0, 0, 0, 0, false);
    }

    public static GameCommand standGround(int player, int unitId) {
        return new GameCommand(Kind.STAND_GROUND, player, unitId, 0, 0, 0, 0, false);
    }

    public static GameCommand attackGround(int player, int unitId, int x, int y) {
        return new GameCommand(Kind.ATTACK_GROUND, player, unitId, x, y, 0, 0, false);
    }

    /**
     * Puts a transport's cargo ashore near a place.
     *
     * <p>The position is where the player pointed, which is the whole point of
     * carrying one: {@code COrder_Unload} searches outwards from it for a
     * stretch of coast and sails there. Without a position this was an
     * instant attempt at whatever square the boat was floating on, and a boat
     * is almost never floating on one that works.
     */
    public static GameCommand unload(int player, int transportId, int x, int y) {
        return new GameCommand(Kind.UNLOAD, player, transportId, x, y, 0, 0, false);
    }

    /**
     * Points at a place on the map.
     *
     * <p>Carried as a command rather than done locally so it reaches the other
     * players by the same road as everything else. Pointing at something is
     * only useful if the people you are pointing at can see it.
     */
    public static GameCommand ping(int player, int x, int y) {
        return new GameCommand(Kind.PING, player, 0, x, y, 0, 0, false);
    }

    /** Lands one passenger, leaving the rest aboard. */
    /**
     * Sends a unit to board a transport.
     *
     * <p>A command like any other, so it goes through the same lockstep delay
     * as everything else and both machines put the same soldier on the same
     * boat on the same cycle.
     */
    public static GameCommand board(int player, int unitId, int transportId) {
        return new GameCommand(Kind.BOARD, player, unitId, 0, 0, transportId, 0, false);
    }

    public static GameCommand follow(int player, int unitId, int targetId) {
        return new GameCommand(Kind.FOLLOW, player, unitId, 0, 0, targetId, 0, false);
    }

    public static GameCommand unloadOne(int player, int transportId, int passengerId) {
        return new GameCommand(Kind.UNLOAD_ONE, player, transportId, 0, 0, passengerId, 0,
                false);
    }

    /** Lands one passenger near a place, sailing there if need be. */
    public static GameCommand unloadOneAt(int player, int transportId, int passengerId,
            int x, int y) {
        return new GameCommand(Kind.UNLOAD_ONE, player, transportId, x, y, passengerId, 0,
                false);
    }

    public static GameCommand train(int player, int buildingId, int typeIndex) {
        return new GameCommand(Kind.TRAIN, player, buildingId, 0, 0, 0, typeIndex, false);
    }

    /**
     * Starts a building becoming a better building.
     *
     * <p>{@code SendCommandUpgradeTo}.
     * The seven factories below it exist for the same reason this one does:
     * every one of them changes what a player owns or what it has spent, and
     * a change like that made on one machine and not the other is a desync on
     * the next cycle.
     */
    public static GameCommand upgradeTo(int player, int buildingId, int typeIndex) {
        return new GameCommand(Kind.UPGRADE_TO, player, buildingId, 0, 0, 0, typeIndex, false);
    }

    /** {@code SendCommandCancelTraining}. */
    public static GameCommand cancelTraining(int player, int buildingId) {
        return new GameCommand(Kind.CANCEL_TRAIN, player, buildingId, 0, 0, 0, 0, false);
    }

    /** {@code SendCommandCancelResearch}. */
    public static GameCommand cancelResearch(int player, int buildingId) {
        return new GameCommand(Kind.CANCEL_RESEARCH, player, buildingId, 0, 0, 0, 0, false);
    }

    /** {@code SendCommandCancelUpgradeTo}. */
    public static GameCommand cancelUpgradeTo(int player, int buildingId) {
        return new GameCommand(Kind.CANCEL_UPGRADE_TO, player, buildingId, 0, 0, 0, 0, false);
    }

    /**
     * Abandons a half-built site.
     *
     * <p>Upstream has no separate message for this: {@code DoClicked_CancelBuild}
     * The game sends {@code SendCommandDismiss} at the
     * site, because a site that stops being built is a unit that stops
     * existing. This implementation has {@code World.cancelConstruction}, which also
     * refunds, so it is carried as its own kind rather than folded into
     * {@link #dismiss}.
     */
    public static GameCommand cancelConstruction(int player, int siteId) {
        return new GameCommand(Kind.CANCEL_BUILD, player, siteId, 0, 0, 0, 0, false);
    }

    /**
     * Points a producing building's output at a square.
     *
     * <p>A port feature with no upstream analogue -- LegacyEngine has no player
     * rally points, only {@code AiForce}'s. It travels as a command all the
     * same, because where a trained unit walks is simulation state: a machine
     * that had not been told would put the new footman somewhere else.
     */
    public static GameCommand rallyPoint(int player, int buildingId, int x, int y) {
        return new GameCommand(Kind.RALLY_POINT, player, buildingId, x, y, 0, 0, false);
    }

    /**
     * Gets rid of a unit where it stands.
     *
     * <p>{@code SendCommandDismiss},, which
     * {@code HandleSuicideClick} sends when a critter has been clicked
     * {@code ClicksToExplode} times running.
     */
    public static GameCommand dismiss(int player, int unitId) {
        return new GameCommand(Kind.DISMISS, player, unitId, 0, 0, 0, 0, false);
    }

    /**
     * Advances on a square, fighting on the way.
     *
     * <p>{@code World.orderAttackMove} handles the wall case itself, so a
     * command aimed at a visible wall becomes a bombardment rather than a
     * walk into it.
     */
    public static GameCommand attackMove(int player, int unitId, int x, int y) {
        return new GameCommand(Kind.ATTACK_MOVE, player, unitId, x, y, 0, 0, false);
    }

    public static GameCommand quit(int player) {
        return quit(player, 0, DepartureReason.BOOTSTRAP);
    }

    /**
     * Removes a player and records who may continue commanding that slot.
     *
     * <p>The ownership, colour and resource bank stay with {@code player}.
     * {@code controlMask} grants command authority only; bit N names player N.
     * This makes a team handoff deterministic without converting units or
     * merging economies. The reason travels in {@code y}; the mask in
     * {@code x}, both fields otherwise unused by QUIT.
     */
    public static GameCommand quit(int player, int controlMask, DepartureReason reason) {
        return new GameCommand(Kind.QUIT, player, 0, controlMask,
                reason == null ? DepartureReason.LEFT.ordinal() : reason.ordinal(),
                0, 0, false);
    }

    public int departureControlMask() {
        return kind == Kind.QUIT ? x & 0xFFFF : 0;
    }

    public DepartureReason departureReason() {
        if (kind != Kind.QUIT || y < 0 || y >= DepartureReason.values().length) {
            return DepartureReason.LEFT;
        }
        return DepartureReason.values()[y];
    }

    /** Returns the same command marked to append behind the current order. */
    public GameCommand withQueued(boolean queued) {
        return new GameCommand(kind, player, unitId, x, y, targetId, typeIndex, queued);
    }

    /** Fixed wire size, in bytes. */
    public static final int WIRE_BYTES = 1 + 1 + 4 + 2 + 2 + 4 + 2 + 1;

    /** Writes this command in its wire form. */
    public void writeTo(java.nio.ByteBuffer out) {
        out.put((byte) kind.ordinal());
        out.put((byte) player);
        out.putInt(unitId);
        out.putShort((short) x);
        out.putShort((short) y);
        out.putInt(targetId);
        out.putShort((short) typeIndex);
        out.put((byte) (queued ? 1 : 0));
    }

    /** Reads one back. */
    public static GameCommand readFrom(java.nio.ByteBuffer in) {
        Kind[] kinds = Kind.values();
        int kindOrdinal = in.get() & 0xFF;
        if (kindOrdinal >= kinds.length) {
            throw new IllegalArgumentException("unknown command kind " + kindOrdinal);
        }
        return new GameCommand(
                kinds[kindOrdinal],
                in.get() & 0xFF,
                in.getInt(),
                in.getShort(),
                in.getShort(),
                in.getInt(),
                in.getShort(),
                in.get() != 0);
    }
}
