package net.chonkbase.chonkcraft.engine.network;

import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.trigger.TriggerSystem;

/** BNE melee/team elimination evaluated from the synchronized world. */
public final class MultiplayerOutcome {

    private MultiplayerOutcome() { }

    /**
     * Decides the local team after a network tick.
     *
     * <p>BNE's native trigger seam asks whether hostile players have any real
     * units left. Teams extend the same question across the local player's
     * alliance: losing one's own last unit is not defeat while a teammate is
     * still standing, and defeating one enemy is not victory while another
     * hostile team survives.</p>
     */
    public static TriggerSystem.Outcome evaluate(World world, int localPlayer) {
        if (world == null || localPlayer < 0 || localPlayer >= Player.MAX
                || world.player(localPlayer) == null
                || !world.player(localPlayer).isActive()) {
            return TriggerSystem.Outcome.RUNNING;
        }
        boolean teamAlive = world.multiplayerTeamUnitsRemaining(localPlayer) > 0;
        boolean opponentsAlive = world.multiplayerOpponentsRemaining(localPlayer) > 0;
        if (teamAlive && opponentsAlive) {
            return TriggerSystem.Outcome.RUNNING;
        }
        if (teamAlive) {
            return TriggerSystem.Outcome.VICTORY;
        }
        return opponentsAlive
                ? TriggerSystem.Outcome.DEFEAT
                : TriggerSystem.Outcome.DRAW;
    }
}
