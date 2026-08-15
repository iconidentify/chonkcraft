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
        PudUpgradeData upgradeData,
        List<PudUnit> units) {

    /** Player slots in every Warcraft II map. */
    public static final int PLAYER_MAX = 16;

    /** Unit statistics embedded in the PUD's optional {@code UDTA} section. */
    public record PudUnitData(boolean useDefaults, int[] hitPoints,
            int[] priorities, int[] times, int[] goldTens, int[] lumberTens,
            int[] armor, int[] basicDamage, int[] piercingDamage,
            int[] attackRange, int[] sight) {

        public static final int UNIT_COUNT = 110;
        public static final int HIT_POINTS_OFFSET = 1678;
        public static final int TIME_OFFSET = 2008;
        public static final int GOLD_TENS_OFFSET = 2118;
        public static final int LUMBER_TENS_OFFSET = 2228;
        public static final int ATTACK_RANGE_OFFSET = 3328;
        public static final int SIGHT_OFFSET = 3548;
        public static final int ARMOR_OFFSET = 3658;
        public static final int PRIORITY_OFFSET = 3878;
        public static final int BASIC_DAMAGE_OFFSET = 3988;
        public static final int PIERCING_DAMAGE_OFFSET = 4098;

        /** Hit points for a PUD unit type, or zero when it has no entry. */
        public int hitPoints(int type) {
            return at(hitPoints, type);
        }

        /** Native target-selection priority for a PUD unit type. */
        public int priority(int type) {
            return at(priorities, type);
        }

        /** Build or train time in retail time units. */
        public int time(int type) {
            return at(times, type);
        }

        /** Gold cost in whole coins. The on-disk table stores tens. */
        public int gold(int type) {
            return at(goldTens, type) * 10;
        }

        /** Lumber cost in whole logs. The on-disk table stores tens. */
        public int lumber(int type) {
            return at(lumberTens, type) * 10;
        }

        public int armor(int type) {
            return at(armor, type);
        }

        public int basicDamage(int type) {
            return at(basicDamage, type);
        }

        public int piercingDamage(int type) {
            return at(piercingDamage, type);
        }

        public int attackRange(int type) {
            return at(attackRange, type);
        }

        public int sight(int type) {
            return at(sight, type);
        }

        private static int at(int[] table, int type) {
            return table != null && type >= 0 && type < table.length ? table[type] : 0;
        }
    }

    /**
     * Per-map upgrade costs and times from the 782-byte BNE {@code UGRD}
     * section.
     *
     * <p>Every authenticated retail map carries this exact size. The first
     * word is {@code useDefaults}: when it is set the arrays are the stock
     * table and must not overwrite the catalog. When it is clear, Great Wall
     * and Rescue store different gold and time values that the simulation
     * has to honour without mutating the shared upgrade list.
     */
    public record PudUpgradeData(boolean useDefaults, int[] time, int[] gold,
            int[] lumber, int[] oil) {

        public static final int UPGRADE_COUNT = 52;
        public static final int SECTION_BYTES = 782;

        public int time(int index) {
            return inRange(index) ? time[index] : 0;
        }

        public int gold(int index) {
            return inRange(index) ? gold[index] : 0;
        }

        public int lumber(int index) {
            return inRange(index) ? lumber[index] : 0;
        }

        public int oil(int index) {
            return inRange(index) ? oil[index] : 0;
        }

        private boolean inRange(int index) {
            return index >= 0 && index < time.length;
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
