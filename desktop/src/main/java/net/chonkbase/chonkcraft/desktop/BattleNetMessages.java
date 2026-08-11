package net.chonkbase.chonkcraft.desktop;

import net.chonkbase.chonkcraft.engine.GameData;

/**
 * Player-facing messages recovered from the Battle.net Edition string table.
 *
 * <p>The executable's notification paths address these entries by number; the
 * English text itself lives in entry 1 of {@code strdat.war}. ChonkPacks retain
 * that table, exposed through {@link GameData#names()}. Keeping the index and a
 * byte-for-byte English fallback together prevents an incomplete or older pack
 * from degrading into vague, inconsistently styled messages.
 */
final class BattleNetMessages {

    enum Key {
        NOT_ENOUGH_MANA(435, "Not enough mana to cast spell."),
        NOWHERE_TO_RETURN(436, "Nowhere to return to...cannot return."),
        CANNOT_CAST_ON_BUILDINGS(437, "Cannot cast on buildings."),
        NOT_ENOUGH_FOOD(438, "Not enough food...build more farms."),
        NOT_ENOUGH_GOLD(439, "Not enough gold...mine more gold."),
        NOT_ENOUGH_LUMBER(440, "Not enough lumber...chop more trees."),
        NOT_ENOUGH_OIL(441, "Not enough oil...drill for oil."),
        BUILD_OFF_MAP(442, "You cannot build off the map."),
        CANNOT_BUILD_THERE(443, "You cannot build there."),
        BUILD_ON_COAST(444, "You must build this building on the coast."),
        EXPLORE_FIRST(445, "You must explore there first."),
        PLATFORM_OVER_OIL(446, "You must build an oil platform over a patch of oil."),
        TOWN_HALL_NEAR_GOLD(447, "You cannot build a townhall too near a goldmine."),
        TOO_NEAR_OIL(448, "You cannot build too near a patch of oil.");

        private final int index;
        private final String fallback;

        Key(int index, String fallback) {
            this.index = index;
            this.fallback = fallback;
        }
    }

    private BattleNetMessages() { }

    /** Returns the installed BNE sentence, or its exact English fallback. */
    static String text(GameData data, Key key) {
        if (data != null && data.names() != null) {
            String installed = data.names().name(key.index);
            if (installed != null && !installed.isBlank()) {
                return installed;
            }
        }
        return key.fallback;
    }

    /**
     * Styles ChonkCraft-only notices like the retail notification sentences.
     * Recovered BNE text does not pass through here and is never rewritten.
     */
    static String sentence(String notice) {
        if (notice == null || notice.isBlank()) {
            return "";
        }
        String text = notice.strip();
        int first = text.offsetByCodePoints(0, 1);
        text = text.substring(0, first).toUpperCase(java.util.Locale.ROOT)
                + text.substring(first);
        char last = text.charAt(text.length() - 1);
        if (last != '.' && last != '!' && last != '?' && last != '…') {
            text += ".";
        }
        return text;
    }
}
