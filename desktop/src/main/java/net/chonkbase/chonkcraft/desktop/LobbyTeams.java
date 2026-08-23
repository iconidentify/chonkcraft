package net.chonkbase.chonkcraft.desktop;

import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.network.GameLobby;

/** The map-defined sides used by Battle.net Edition's Top vs Bottom template. */
final class LobbyTeams {

    /** The two starting areas named by the template. */
    enum Side {
        TOP("Top Team"),
        BOTTOM("Bottom Team");

        private final String caption;

        Side(String caption) {
            this.caption = caption;
        }

        String caption() {
            return caption;
        }
    }

    private final Side[] sides;

    private LobbyTeams(Side[] sides) {
        this.sides = sides;
    }

    /**
     * Reads each colour's fixed start from the synchronized map.
     *
     * <p>BNE defines Top vs Bottom by starting area, not by lobby row or join
     * order. Many retail maps deliberately interleave their colour slots: on
     * All You Need BNE, for example, Red and Violet start in the north while
     * Blue and Green start in the south. When a protocol-only fixture has no
     * start marker, splitting the colour roster in half remains deterministic.
     */
    static LobbyTeams from(PudMap map, int capacity) {
        int count = Math.max(0, capacity);
        Side[] sides = new Side[count];
        int fallbackHalf = Math.max(1, (count + 1) / 2);
        for (int player = 0; player < count; player++) {
            int[] start = map == null ? null : map.startLocation(player);
            if (start != null && map.height() > 0) {
                sides[player] = start[1] * 2 < map.height() ? Side.TOP : Side.BOTTOM;
            } else {
                sides[player] = player < fallbackHalf ? Side.TOP : Side.BOTTOM;
            }
        }
        return new LobbyTeams(sides);
    }

    Side sideOf(int player) {
        if (player < 0 || player >= sides.length) {
            return Side.BOTTOM;
        }
        return sides[player];
    }

    boolean together(int first, int second) {
        return sideOf(first) == sideOf(second);
    }

    /** Groups the visible rows by their real starting area while retaining colour order. */
    List<GameLobby.Slot> arrange(List<GameLobby.Slot> slots) {
        List<GameLobby.Slot> arranged = new ArrayList<>(slots.size());
        for (Side side : Side.values()) {
            for (GameLobby.Slot slot : slots) {
                if (sideOf(slot.index()) == side) {
                    arranged.add(slot);
                }
            }
        }
        return List.copyOf(arranged);
    }
}
