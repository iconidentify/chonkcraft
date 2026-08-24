package net.chonkbase.chonkcraft.engine.network;

import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.MapField;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;

/**
 * A checksum over the whole simulation, for catching desynchronisation.
 *
 * <p>Lockstep depends on every machine computing the same result from the same
 * commands. When that fails the game does not crash, it quietly diverges:
 * one player watches a unit die that is alive on another's screen. The only
 * defence is for each machine to hash its own state periodically and compare,
 * which is what {@code NetworkSyncHashs} does upstream.
 *
 * <p>What goes into the hash is a judgement. It must cover everything the
 * simulation decides, so a divergence is caught, and nothing that is merely
 * presentational, or two machines with different window sizes would appear to
 * disagree. So: terrain state, unit positions, health, orders and ownership,
 * and the players' banks. Not animation frames, not the camera, not sound.
 */
public final class SyncHash {

    private SyncHash() {
    }

    /**
     * Hashes a world.
     *
     * <p>FNV-1a, which is cheap, has no table, and is well spread for short
     * inputs. This is a consistency check between cooperating machines, not a
     * defence against tampering, so a cryptographic hash would be wasted work
     * thirty times a second.
     */
    public static long of(World world) {
        long hash = 0xcbf29ce484222325L;

        hash = mix(hash, world.cycle());
        hash = mix(hash, world.randomSeed());
        hash = mix(hash, world.randomDraws());

        hash = mix(hash, world.map().width());
        hash = mix(hash, world.map().height());
        for (int y = 0; y < world.map().height(); y++) {
            for (int x = 0; x < world.map().width(); x++) {
                MapField field = world.map().field(x, y);
                hash = mix(hash, field.tile());
                hash = mix(hash, field.flags());
                hash = mix(hash, field.value());
            }
        }

        // Units in list order, which is creation order and therefore the same
        // on every machine.
        for (Unit unit : world.units()) {
            hash = mix(hash, unit.id());
            hash = mix(hash, unit.player());
            hash = mix(hash, unit.tileX());
            hash = mix(hash, unit.tileY());
            hash = mix(hash, unit.offsetX());
            hash = mix(hash, unit.offsetY());
            hash = mix(hash, unit.hitPoints());
            hash = mix(hash, unit.order().ordinal());
            hash = mix(hash, unit.currentAction().ordinal());
            hash = mix(hash, unit.battleNetOrderDelay());
            hash = mix(hash, unit.actionBeforeQueuedReleaseDelay());
            hash = mix(hash, unit.battleNetPlayerCommandMove() ? 1 : 0);
            hash = mix(hash, unit.battleNetAttackGroundMove() ? 1 : 0);
            hash = mix(hash, unit.battleNetStopAfterLeftover() ? 1 : 0);
            hash = mix(hash, unit.savedOrder() == null ? -1 : unit.savedOrder().ordinal());
            hash = mix(hash, unit.attackMoveX());
            hash = mix(hash, unit.attackMoveY());
            hash = mix(hash, unit.savedAttackMoveX());
            hash = mix(hash, unit.savedAttackMoveY());
            hash = mix(hash, unit.savedMoveRange());
            hash = mix(hash, unit.savedAttackScanSleep());
            hash = mix(hash, unit.savedAttackMoveOpening() ? 1 : 0);
            hash = mix(hash, unit.seenByPlayers());
            hash = mix(hash, unit.heading());
            hash = mix(hash, unit.pathLength());
            hash = mix(hash, unit.randomMoveSleep());
            hash = mix(hash, unit.carried());
            hash = mix(hash, unit.progress());
            hash = mix(hash, unit.target() == null ? 0 : unit.target().id());
            hash = mix(hash, unit.pendingBuild() == null
                    ? 0 : unit.pendingBuild().ident().hashCode());
            hash = mix(hash, unit.producing() == null
                    ? 0 : unit.producing().ident().hashCode());
            hash = mix(hash, unit.pendingTransform() == null
                    ? 0 : unit.pendingTransform().ident().hashCode());
            hash = mix(hash, unit.trainingQueue().size());
            for (UnitType queued : unit.trainingQueue()) {
                hash = mix(hash, queued.ident().hashCode());
            }
            hash = mix(hash, unit.queuedOrders().size());
            for (Unit.QueuedOrder queued : unit.queuedOrders()) {
                hash = mix(hash, queued.kind().ordinal());
                hash = mix(hash, queued.x());
                hash = mix(hash, queued.y());
                hash = mix(hash, queued.target() == null ? 0 : queued.target().id());
                hash = mix(hash, queued.type() == null ? 0 : queued.type().ident().hashCode());
                hash = mix(hash, queued.value() == null ? 0 : queued.value().hashCode());
            }
        }

        for (Player player : world.players()) {
            hash = mix(hash, world.departedControlMask(player.index()));
            // Diplomacy and shared sight are simulation state too. A peer
            // that missed the final explicit-team setup used to report the
            // same hash until the different target/fog decision happened to
            // alter a unit. Cover the tables at cycle zero so the network
            // names the setup divergence immediately.
            for (int other = 0; other < Player.MAX; other++) {
                hash = mix(hash, world.isEnemyPlayer(player.index(), other) ? 1 : 0);
                hash = mix(hash, world.isAllied(player.index(), other) ? 1 : 0);
                hash = mix(hash, world.sharesVisionWith(player.index(), other) ? 1 : 0);
            }
            if (!player.isActive()) {
                continue;
            }
            hash = mix(hash, player.index());
            for (UnitType.Resource resource : UnitType.Resource.values()) {
                hash = mix(hash, player.get(resource));
            }
            hash = mix(hash, player.supply());
            hash = mix(hash, player.demand());
        }
        return hash;
    }

    private static long mix(long hash, long value) {
        long result = hash;
        for (int shift = 0; shift < 64; shift += 8) {
            result ^= (value >>> shift) & 0xFF;
            result *= 0x100000001b3L;
        }
        return result;
    }
}
