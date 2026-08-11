package net.chonkbase.chonkcraft.desktop;

import java.awt.Color;

/**
 * The eight colours a player can be.
 *
 * <p>Warcraft II's own order, which matters because it is how players refer to
 * each other: red is always the first slot and blue the second, and a game
 * where that is not true reads wrongly to anyone who has played the original.
 *
 * <p>Slot fifteen is the neutral owner -- gold mines, critters, corpses -- and
 * is deliberately a colour no player has.
 */
final class PlayerColours {

    /** Red, blue, green, violet, orange, black, white, yellow. */
    private static final Color[] COLOURS = {
        new Color(0xC80000),
        new Color(0x2038C8),
        new Color(0x00A048),
        new Color(0x7828A0),
        new Color(0xE07800),
        new Color(0x282828),
        new Color(0xE0E0E0),
        new Color(0xE0C818),
    };

    /** What nobody's units are drawn in. */
    private static final Color NEUTRAL = new Color(0x909090);

    private PlayerColours() {
    }

    /** The colour of a slot. */
    static Color of(int player) {
        if (player < 0 || player >= COLOURS.length) {
            return NEUTRAL;
        }
        return COLOURS[player];
    }

    /** What a slot is called, for the lobby and for messages. */
    static String nameOf(int player) {
        return switch (player) {
            case 0 -> "Red";
            case 1 -> "Blue";
            case 2 -> "Green";
            case 3 -> "Violet";
            case 4 -> "Orange";
            case 5 -> "Black";
            case 6 -> "White";
            case 7 -> "Yellow";
            default -> "Neutral";
        };
    }

    /** How many slots a game can hold. */
    static int count() {
        return COLOURS.length;
    }
}
