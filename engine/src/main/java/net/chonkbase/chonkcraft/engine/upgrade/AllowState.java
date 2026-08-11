package net.chonkbase.chonkcraft.engine.upgrade;

import java.util.HashMap;
import java.util.Map;

/**
 * What each player is permitted to build, train and research.
 *
 * <p>Implements the allow table, filled from
 * the {@code DefineAllow} calls at the top of every campaign mission script.
 *
 * <p>This is separate from the tech tree and does a different job. The tech
 * tree says a knight needs stables; this says whether the mission lets you
 * have knights at all. The first mission of the human campaign permits five
 * unit types and forbids everything else, which is how Warcraft II teaches the
 * game one building at a time:
 *
 * <pre>
 *   DefineAllowNormalHumanUnits("FFFFFFFFFFFFFFFF")
 *   DefineAllow("unit-farm", "AAAAAAAAAAAAAAAA")
 * </pre>
 *
 * <p>The flag string carries one character per player, so a mission can hand
 * the computer opponents an arsenal the player has not earned yet.
 *
 * <p>Anything never mentioned is permitted. The scripts forbid in bulk and
 * then allow by name, so an absent entry means the mission had no opinion,
 * not that it said no.
 */
public final class AllowState {

    /** As many player slots as a flag string can address. */
    public static final int PLAYER_MAX = 16;

    /** Flag character: available. */
    public static final char ALLOWED = 'A';
    /** Flag character: forbidden. */
    public static final char FORBIDDEN = 'F';
    /** Flag character: already researched, for upgrades. */
    public static final char RESEARCHED = 'R';

    /** Identifier to the per-player flags. */
    private final Map<String, char[]> flags = new HashMap<>();

    /**
     * Records a flag string against an identifier.
     *
     * @param ident the unit or upgrade identifier
     * @param ids   one character per player; shorter strings leave the rest
     *              of the players untouched, as upstream does
     */
    public void define(String ident, String ids) {
        if (ident == null || ids == null || ids.isEmpty()) {
            return;
        }
        char[] existing = flags.computeIfAbsent(ident, ignored -> {
            char[] fresh = new char[PLAYER_MAX];
            java.util.Arrays.fill(fresh, ALLOWED);
            return fresh;
        });
        int count = Math.min(ids.length(), PLAYER_MAX);
        for (int player = 0; player < count; player++) {
            existing[player] = ids.charAt(player);
        }
    }

    /** Whether a player may have something. */
    public boolean isAllowed(int player, String ident) {
        char[] entry = flags.get(ident);
        if (entry == null || player < 0 || player >= PLAYER_MAX) {
            return true;
        }
        return entry[player] != FORBIDDEN;
    }

    /** Whether an upgrade is already researched for a player at mission start. */
    public boolean isPreResearched(int player, String ident) {
        char[] entry = flags.get(ident);
        return entry != null && player >= 0 && player < PLAYER_MAX
                && entry[player] == RESEARCHED;
    }

    /** The flag table itself, for writing a save. */
    public Map<String, char[]> flags() {
        return flags;
    }

    /** How many identifiers carry an opinion. */
    public int size() {
        return flags.size();
    }

    /** Whether anything has been declared at all. */
    public boolean isEmpty() {
        return flags.isEmpty();
    }
}
