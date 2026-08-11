package net.chonkbase.chonkcraft.engine.map;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.chonkbase.chonkcraft.engine.unit.UnitType;

/**
 * What a player remembers standing on ground they can no longer see.
 *
 * <p>Scout an enemy town in Warcraft II and it stays on your map after your
 * scout dies. That is not a rendering accident: {@code CUnit::Seen} keeps a
 * snapshot of the unit's type, position and frame, {@code UnitGoesUnderFog}
 * takes it when the unit slips out of sight, and
 * {@code CUnit::IsVisibleInViewport} draws from it for any type carrying
 * {@code VisibleUnderFog} -- which in the shipped data is every building and
 * nothing else. Your scouted enemy base persists; the grunts garrisoning it do
 * not.
 *
 * <p>Upstream hangs this off the unit and keeps destroyed units alive with a
 * reference count so a razed building can still be drawn to whoever has not
 * looked since. This implementation deletes its dead, so the memory is held here
 * instead, keyed by where the thing stood.
 *
 * <p>That difference makes the bookkeeping simpler rather than harder. A
 * memory is only ever consulted for ground the player cannot see, so it is
 * enough to record what is standing on hidden ground and to throw a memory
 * away the moment its ground is watched again. A building razed while you were
 * away therefore stays on your map until you look -- which is exactly the
 * behaviour upstream's reference counting exists to produce.
 */
public final class SeenBuildings {

    /** One remembered building, as it was when the player last saw it. */
    public record Memory(UnitType type, int owner, int tileX, int tileY,
            int spriteIndex, boolean mirrored, boolean underConstruction,
            double progress) {

        /** Whether this memory covers a square. */
        public boolean covers(int x, int y) {
            return x >= tileX && y >= tileY
                    && x < tileX + Math.max(1, type.tileWidth())
                    && y < tileY + Math.max(1, type.tileHeight());
        }
    }

    /**
     * Concurrent, because this table has a writer and a reader on different
     * threads and no lock between them.
     *
     * <p>The simulation rebuilds these every cycle while the interface copies
     * them to draw the minimap and the remembered buildings on the field, twice
     * a frame. Against a plain {@code HashMap} that copy is not merely
     * unsynchronised, it is wrong in a way that reaches the renderer: the copy
     * is taken by walking the table's own array while the simulation is
     * resizing it, so the interface got back a list with a {@code null} in it
     * and drew a memory that was not there --
     *
     * <pre>
     * java.lang.NullPointerException: Cannot invoke
     *     "SeenBuildings$Memory.tileX()" because "memory" is null
     *     at ...SeenBuildings.forPlayer(SeenBuildings.java:54)
     * </pre>
     *
     * -- and, on the same fixture, an
     * {@code ArrayIndexOutOfBoundsException} out of
     * {@code HashMap.valuesToArray}. Both took about twelve thousand copies to
     * reproduce with one building going in and out of sight, which is a
     * scouting party's worth of churn and a few minutes of play.
     *
     * <p>A concurrent map's iterator is weakly consistent rather than
     * fail-fast: it may miss a building remembered while the copy was being
     * taken, or include one forgotten a moment ago, and it can do neither of
     * the two things above. Missing a memory for one frame is invisible -- the
     * next frame has it, a sixtieth of a second later, and the thing it
     * describes has been out of sight for however long the player has been
     * away from it.
     */
    private final Map<Integer, Map<Long, Memory>> byPlayer = new ConcurrentHashMap<>();

    /** What a player remembers, in no particular order. */
    public Collection<Memory> forPlayer(int player) {
        Map<Long, Memory> memories = byPlayer.get(player);
        return memories == null ? List.of() : new ArrayList<>(memories.values());
    }

    /** Records, or refreshes, one remembered building. */
    public void remember(int player, Memory memory) {
        byPlayer.computeIfAbsent(player, key -> new ConcurrentHashMap<>())
                .put(key(memory.tileX(), memory.tileY()), memory);
    }

    /**
     * Forgets everything a player remembers about ground they can now see.
     *
     * <p>Called before the memories are rebuilt, so a building torn down while
     * the player was away disappears the moment they look at where it stood,
     * and not before.
     */
    public void forgetVisible(int player, FogOfWar fog) {
        Map<Long, Memory> memories = byPlayer.get(player);
        if (memories == null || memories.isEmpty()) {
            return;
        }
        memories.values().removeIf(memory -> anyTileVisible(memory, player, fog));
    }

    private static boolean anyTileVisible(Memory memory, int player, FogOfWar fog) {
        int width = Math.max(1, memory.type().tileWidth());
        int height = Math.max(1, memory.type().tileHeight());
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (fog.isVisible(player, memory.tileX() + x, memory.tileY() + y)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Drops everything a player remembers, for a reset or a fresh load. */
    public void clear(int player) {
        byPlayer.remove(player);
    }

    /** How many buildings a player is remembering, for tests. */
    public int size(int player) {
        Map<Long, Memory> memories = byPlayer.get(player);
        return memories == null ? 0 : memories.size();
    }

    private static long key(int tileX, int tileY) {
        return ((long) tileX << 32) | (tileY & 0xFFFFFFFFL);
    }
}
