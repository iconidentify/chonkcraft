package net.chonkbase.chonkcraft.data.map;

import java.util.List;

/**
 * A parsed Warcraft II {@code .PUD} map.
 *
 * @param description   the map's name, or {@code "(unnamed)"}
 * @param tileset       which terrain set the map uses
 * @param width         map width in tiles
 * @param height        map height in tiles
 * @param tiles         {@code width * height} raw tile codes, row-major
 * @param players       per-slot player kind, always 16 entries
 * @param races         per-slot race, always 16 entries
 * @param startGold     per-slot starting gold, always 16 entries
 * @param startLumber   per-slot starting lumber, always 16 entries
 * @param startOil      per-slot starting oil, always 16 entries
 * @param aiTypes       per-slot AI script selector, always 16 entries
 * @param unitData      optional per-map BNE unit-stat table
 * @param units         every placed unit, including start locations
 */
public record PudMap(
        String description,
        Tileset tileset,
        int width,
        int height,
        int[] tiles,
        PlayerType[] players,
        Race[] races,
        int[] startGold,
        int[] startLumber,
        int[] startOil,
        int[] aiTypes,
        PudUnitData unitData,
        List<PudUnit> units) {

    /** Player slots in every Warcraft II map. */
    public static final int PLAYER_MAX = 16;

    /** Unit statistics embedded in the PUD's optional {@code UDTA} section. */
    public record PudUnitData(boolean useDefaults, int[] hitPoints,
            int[] priorities) {

        /** Hit points for a PUD unit type, or zero when it has no entry. */
        public int hitPoints(int type) {
            return type >= 0 && type < hitPoints.length ? hitPoints[type] : 0;
        }

        /** Native target-selection priority for a PUD unit type. */
        public int priority(int type) {
            return type >= 0 && type < priorities.length ? priorities[type] : 0;
        }
    }

    /** How a player slot is controlled. */
    public enum PlayerType {
        /** Slots 0 and 1 in some maps: unused. */
        UNKNOWN_0,
        UNKNOWN_1,
        /** Neutral, the critter and gold-mine owner. */
        NEUTRAL,
        /** An unused slot. */
        NOBODY,
        COMPUTER,
        PERSON,
        RESCUE_PASSIVE,
        RESCUE_ACTIVE;

        static PlayerType of(int value) {
            return value >= 0 && value < values().length ? values()[value] : NOBODY;
        }
    }

    /** Which side a player slot fights for. */
    public enum Race {
        HUMAN,
        ORC,
        NEUTRAL;

        static Race of(int value) {
            return value >= 0 && value < values().length ? values()[value] : NEUTRAL;
        }
    }

    /** The four terrain sets. */
    public enum Tileset {
        FOREST,
        WINTER,
        WASTELAND,
        SWAMP;

        static Tileset of(int value) {
            return value >= 0 && value < values().length ? values()[value] : FOREST;
        }
    }

    /**
     * One placed unit.
     *
     * @param x      tile column
     * @param y      tile row
     * @param type   the PUD type byte; see {@link PudUnitTypes}
     * @param player owning slot
     * @param data   resource amount for mines and patches, otherwise unused
     */
    public record PudUnit(int x, int y, int type, int player, int data) {

        /** The engine identifier for this unit's type. */
        public String typeName() {
            return PudUnitTypes.name(type);
        }

        /** Resources held, already scaled to engine units. */
        public int resourcesHeld() {
            return data * PudUnitTypes.RESOURCE_SCALE;
        }
    }

    /** The raw tile code at a position. */
    public int tileAt(int x, int y) {
        return tiles[y * width + x];
    }

    /**
     * Wall hit points for the tile at a position, or 0 where there is no wall.
     *
     * <p>Warcraft II encodes walls in the terrain rather than as units, so the
     * engine has to recover them from tile codes. The bit patterns are lifted
     * from {@code PudData::Parse}.
     */
    public int wallValueAt(int x, int y) {
        return wallValue(tileAt(x, y));
    }

    /** Wall hit points implied by a raw tile code, or 0. */
    public static int wallValue(int tile) {
        boolean orcWall = (tile & 0xFFF0) == 0x00A0 || (tile & 0xFFF0) == 0x00C0 || (tile & 0xFF00) == 0x0900;
        boolean humanWall = (tile & 0x00F0) == 0x0090 || (tile & 0xFFF0) == 0x00B0 || (tile & 0xFF00) == 0x0800;
        return orcWall || humanWall ? 40 : 0;
    }

    /** The starting tile of a player slot, or {@code null} if it has none. */
    public int[] startLocation(int player) {
        for (PudUnit unit : units) {
            if (unit.player() == player && PudUnitTypes.isStartLocation(unit.type())) {
                return new int[] {unit.x(), unit.y()};
            }
        }
        return null;
    }

    /** How many slots are playable, that is neither nobody nor neutral. */
    public int playableSlots() {
        int count = 0;
        for (PlayerType type : players) {
            if (type == PlayerType.PERSON || type == PlayerType.COMPUTER) {
                count++;
            }
        }
        return count;
    }
}
