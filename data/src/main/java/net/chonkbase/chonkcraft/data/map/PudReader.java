package net.chonkbase.chonkcraft.data.map;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.map.PudMap.PlayerType;
import net.chonkbase.chonkcraft.data.map.PudMap.PudUnitData;
import net.chonkbase.chonkcraft.data.map.PudMap.PudUpgradeData;
import net.chonkbase.chonkcraft.data.map.PudMap.PudUnit;
import net.chonkbase.chonkcraft.data.map.PudMap.Race;
import net.chonkbase.chonkcraft.data.map.PudMap.Tileset;

/**
 * Reads Warcraft II {@code .PUD} map files.
 *
 * <p>Implements {@code PudData::Parse}.
 *
 * <p>A PUD is a flat sequence of sections, each a four-character tag followed
 * by a little-endian 32-bit length and that many bytes of payload. The file
 * must open with a {@code TYPE} section containing the string
 * {@code "WAR2 MAP"}.
 */
public final class PudReader {

    private static final int SECTION_HEADER_BYTES = 8;
    private static final String MAGIC = "WAR2 MAP";
    private static final int UDTA_HIT_POINTS_OFFSET = PudUnitData.HIT_POINTS_OFFSET;
    private static final int UDTA_PRIORITY_OFFSET = PudUnitData.PRIORITY_OFFSET;
    private static final int UDTA_UNIT_COUNT = PudUnitData.UNIT_COUNT;

    private PudReader() {
    }

    /**
     * Parses a PUD.
     *
     * @throws PudFormatException if the file is not a PUD or carries a section
     *                            the original parser would have rejected
     */
    public static PudMap read(byte[] data) {
        Builder builder = new Builder();
        int cursor = 0;

        Section type = readSection(data, cursor);
        if (!type.tag().equals("TYPE")) {
            throw new PudFormatException("not a PUD: first section is '" + type.tag() + "', expected 'TYPE'");
        }
        String magic = readCString(data, cursor + SECTION_HEADER_BYTES);
        if (!MAGIC.equals(magic)) {
            throw new PudFormatException("not a PUD: TYPE section reads '" + magic + "', expected '" + MAGIC + "'");
        }
        cursor += SECTION_HEADER_BYTES + type.length();

        while (cursor < data.length) {
            Section section = readSection(data, cursor);
            int body = cursor + SECTION_HEADER_BYTES;
            if (body + section.length() > data.length) {
                throw new PudFormatException(
                        "section '" + section.tag() + "' runs past the end of the file");
            }
            apply(builder, section, data, body);
            cursor = body + section.length();
        }
        return builder.build();
    }

    private static void apply(Builder builder, Section section, byte[] data, int body) {
        switch (section.tag()) {
            // Version. ChonkCraft reads nothing from it.
            case "VER " -> { }

            case "DESC" -> {
                String description = readCString(data, body);
                builder.description = description.isEmpty() ? "(unnamed)" : description;
            }
            case "OWNR" -> {
                for (int i = 0; i < PudMap.PLAYER_MAX; i++) {
                    builder.players[i] = PlayerType.of(data[body + i] & 0xFF);
                }
            }
            // ERAX is the expansion's spelling of ERA; both carry one byte.
            case "ERA ", "ERAX" -> builder.tileset = Tileset.of(data[body] & 0xFF);

            case "DIM " -> {
                builder.width = readLe16(data, body);
                builder.height = readLe16(data, body + 2);
            }
            case "SIDE" -> {
                for (int i = 0; i < PudMap.PLAYER_MAX; i++) {
                    builder.races[i] = Race.of(data[body + i] & 0xFF);
                }
            }
            case "SGLD" -> readPlayerWords(data, body, builder.startGold);
            case "SLBR" -> readPlayerWords(data, body, builder.startLumber);
            case "SOIL" -> readPlayerWords(data, body, builder.startOil);
            case "AIPL" -> {
                for (int i = 0; i < PudMap.PLAYER_MAX; i++) {
                    builder.aiTypes[i] = data[body + i] & 0xFF;
                }
            }
            case "MTXM" -> {
                if (builder.width == 0 || builder.height == 0) {
                    throw new PudFormatException("MTXM appears before DIM, so the map size is unknown");
                }
                int count = builder.width * builder.height;
                if (body + count * 2 > data.length) {
                    throw new PudFormatException("MTXM is too short for a "
                            + builder.width + "x" + builder.height + " map");
                }
                builder.tiles = new int[count];
                for (int i = 0; i < count; i++) {
                    builder.tiles[i] = readLe16(data, body + i * 2);
                }
            }
            case "UNIT" -> {
                int count = section.length() / 8;
                builder.units = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    int at = body + i * 8;
                    builder.units.add(new PudUnit(
                            readLe16(data, at),
                            readLe16(data, at + 2),
                            data[at + 4] & 0xFF,
                            data[at + 5] & 0xFF,
                            readLe16(data, at + 6)));
                }
            }

            case "UDTA" -> {
                // The DOS and BNE layouts differ after the common 110 unit
                // records, but the hit-point table begins at byte 1678 in
                // both. The section contains the full table even when its
                // leading word says to use the game's defaults.
                int required = UDTA_PRIORITY_OFFSET + UDTA_UNIT_COUNT;
                if (section.length() >= required) {
                    int[] hitPoints = new int[UDTA_UNIT_COUNT];
                    int[] priorities = new int[UDTA_UNIT_COUNT];
                    int[] times = new int[UDTA_UNIT_COUNT];
                    int[] goldTens = new int[UDTA_UNIT_COUNT];
                    int[] lumberTens = new int[UDTA_UNIT_COUNT];
                    int[] oilTens = new int[UDTA_UNIT_COUNT];
                    int[] armor = new int[UDTA_UNIT_COUNT];
                    int[] basicDamage = new int[UDTA_UNIT_COUNT];
                    int[] piercingDamage = new int[UDTA_UNIT_COUNT];
                    int[] attackRange = new int[UDTA_UNIT_COUNT];
                    int[] sight = new int[UDTA_UNIT_COUNT];
                    for (int i = 0; i < hitPoints.length; i++) {
                        hitPoints[i] = readLe16(data,
                                body + UDTA_HIT_POINTS_OFFSET + i * 2);
                        priorities[i] = data[
                                body + UDTA_PRIORITY_OFFSET + i] & 0xff;
                        times[i] = udtaByte(data, body, section.length(),
                                PudUnitData.TIME_OFFSET, i);
                        goldTens[i] = udtaByte(data, body, section.length(),
                                PudUnitData.GOLD_TENS_OFFSET, i);
                        lumberTens[i] = udtaByte(data, body, section.length(),
                                PudUnitData.LUMBER_TENS_OFFSET, i);
                        oilTens[i] = udtaByte(data, body, section.length(),
                                PudUnitData.OIL_TENS_OFFSET, i);
                        armor[i] = udtaByte(data, body, section.length(),
                                PudUnitData.ARMOR_OFFSET, i);
                        basicDamage[i] = udtaByte(data, body, section.length(),
                                PudUnitData.BASIC_DAMAGE_OFFSET, i);
                        piercingDamage[i] = udtaByte(data, body, section.length(),
                                PudUnitData.PIERCING_DAMAGE_OFFSET, i);
                        attackRange[i] = udtaByte(data, body, section.length(),
                                PudUnitData.ATTACK_RANGE_OFFSET, i);
                        sight[i] = udtaByte(data, body, section.length(),
                                PudUnitData.SIGHT_OFFSET, i);
                    }
                    builder.unitData = new PudUnitData(
                            readLe16(data, body) != 0, hitPoints, priorities,
                            times, goldTens, lumberTens, oilTens, armor,
                            basicDamage, piercingDamage, attackRange, sight);
                }
            }

            case "UGRD" -> {
                // Every authenticated BNE map stores exactly 782 bytes. Any
                // other length is not a layout we have read, so the profile
                // stays absent rather than applying a guessed table.
                if (section.length() == PudUpgradeData.SECTION_BYTES) {
                    builder.upgradeData = readUpgradeData(data, body);
                }
            }

            // Sections the original parser accepts and ignores.
            case "ALOW", "SQM ", "OILM", "REGM", "SIGN" -> { }

            default -> throw new PudFormatException(
                    "unknown section '" + section.tag() + "' of " + section.length() + " bytes");
        }
    }

    private static PudUpgradeData readUpgradeData(byte[] data, int body) {
        int count = PudUpgradeData.UPGRADE_COUNT;
        int[] time = new int[count];
        int[] gold = new int[count];
        int[] lumber = new int[count];
        int[] oil = new int[count];
        boolean useDefaults = readLe16(data, body) != 0;
        // Authenticated Icewall matches the catalog only when time is one
        // byte per upgrade at offset 2 and gold/lumber/oil are words at
        // 54/158/262. Reading time as words produced 64,200 for sword1.
        for (int i = 0; i < count; i++) {
            time[i] = data[body + PudUpgradeData.TIME_OFFSET + i] & 0xff;
            gold[i] = readLe16(data, body + PudUpgradeData.GOLD_OFFSET + i * 2);
            lumber[i] = readLe16(data, body + PudUpgradeData.LUMBER_OFFSET + i * 2);
            oil[i] = readLe16(data, body + PudUpgradeData.OIL_OFFSET + i * 2);
        }
        return new PudUpgradeData(useDefaults, time, gold, lumber, oil);
    }

    private static void readPlayerWords(byte[] data, int body, int[] target) {
        for (int i = 0; i < PudMap.PLAYER_MAX; i++) {
            target[i] = readLe16(data, body + i * 2);
        }
    }

    private record Section(String tag, int length) {}

    private static Section readSection(byte[] data, int offset) {
        if (offset + SECTION_HEADER_BYTES > data.length) {
            throw new PudFormatException("truncated section header at offset " + offset);
        }
        String tag = new String(data, offset, 4, StandardCharsets.US_ASCII);
        int length = readLe32(data, offset + 4);
        if (length < 0) {
            throw new PudFormatException("section '" + tag + "' declares a negative length");
        }
        return new Section(tag, length);
    }

    private static String readCString(byte[] data, int offset) {
        int end = offset;
        while (end < data.length && data[end] != 0) {
            end++;
        }
        return new String(data, offset, end - offset, StandardCharsets.ISO_8859_1);
    }

    private static int udtaByte(byte[] data, int body, int length, int table, int index) {
        int at = table + index;
        if (at < 0 || at >= length) {
            return 0;
        }
        return data[body + at] & 0xff;
    }

    private static int readLe16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int readLe32(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    /** Accumulates sections as they are read; a PUD may present them in any order. */
    private static final class Builder {
        String description = "(unnamed)";
        Tileset tileset = Tileset.FOREST;
        int width;
        int height;
        int[] tiles;
        final PlayerType[] players = new PlayerType[PudMap.PLAYER_MAX];
        final Race[] races = new Race[PudMap.PLAYER_MAX];
        final int[] startGold = new int[PudMap.PLAYER_MAX];
        final int[] startLumber = new int[PudMap.PLAYER_MAX];
        final int[] startOil = new int[PudMap.PLAYER_MAX];
        final int[] aiTypes = new int[PudMap.PLAYER_MAX];
        PudUnitData unitData;
        PudUpgradeData upgradeData;
        List<PudUnit> units = List.of();

        Builder() {
            java.util.Arrays.fill(players, PlayerType.NOBODY);
            java.util.Arrays.fill(races, Race.NEUTRAL);
        }

        PudMap build() {
            if (width == 0 || height == 0) {
                throw new PudFormatException("map has no DIM section, so its size is unknown");
            }
            if (tiles == null) {
                throw new PudFormatException("map has no MTXM section, so it has no terrain");
            }
            return new PudMap(description, tileset, width, height, tiles,
                    players, races, startGold, startLumber, startOil, aiTypes,
                    unitData, upgradeData, List.copyOf(units));
        }
    }
}
