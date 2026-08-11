package net.chonkbase.chonkcraft.desktop;

import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.network.NetworkGame;

/**
 * Where the player's orders go.
 *
 * <p>The screen builds a {@link GameCommand} and hands it here without knowing
 * whether this is a single-player game or a networked one. That is the whole
 * point: {@code CommandApplier}'s own documentation says every path into the
 * simulation goes through it "in single player as well as multiplayer", and
 * this is what makes that true. It was not, before: clicks called the world
 * directly and only the network path went through commands, so the two could
 * drift and the drift would surface as a desync, which is the worst place to
 * discover it.
 *
 * <p>The difference between the two is one of timing, not of route. Locally a
 * command takes effect now. Over a network it takes effect on an agreed future
 * cycle, on every machine at once, including the one that issued it.
 */
interface CommandSink {

    /** Carries out an order, now or when the network agrees. */
    void issue(GameCommand command);

    /**
     * Submits an order and reports whether this process could accept it now.
     *
     * <p>A network sink can only confirm delivery into lockstep; the shared
     * simulation will make the authoritative decision on its release cycle.
     */
    default boolean issueAccepted(GameCommand command) {
        issue(command);
        return true;
    }

    /** Straight into the simulation, for a game with nobody else in it. */
    static CommandSink local(CommandApplier applier) {
        return new CommandSink() {
            @Override
            public void issue(GameCommand command) {
                applier.apply(command);
            }

            @Override
            public boolean issueAccepted(GameCommand command) {
                return applier.apply(command);
            }
        };
    }

    /** Into the lockstep queue, to run on an agreed cycle. */
    static CommandSink networked(NetworkGame game) {
        return game::issue;
    }
}
