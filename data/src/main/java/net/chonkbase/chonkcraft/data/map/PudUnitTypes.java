package net.chonkbase.chonkcraft.data.map;

/**
 * Maps a PUD unit type byte to the engine's unit identifier.
 *
 * <p>Transcribed from {@code UnitScriptNames}. Order is
 * significant: the array index is the type byte stored in the map file. The
 * empty entries are real, and correspond to type codes Blizzard's editor never
 * emitted.
 */
public final class PudUnitTypes {

    private static final String[] NAMES = {
        "unit-footman", "unit-grunt",
        "unit-peasant", "unit-peon",
        "unit-ballista", "unit-catapult",
        "unit-knight", "unit-ogre",
        "unit-archer", "unit-axethrower",
        "unit-mage", "unit-death-knight",
        "unit-paladin", "unit-ogre-mage",
        "unit-dwarves", "unit-goblin-sappers",
        "unit-attack-peasant", "unit-attack-peon",
        "unit-ranger", "unit-berserker",
        "unit-female-hero", "unit-evil-knight",
        "unit-flying-angel", "unit-fad-man",
        "unit-white-mage", "unit-beast-cry",
        "unit-human-oil-tanker", "unit-orc-oil-tanker",
        "unit-human-transport", "unit-orc-transport",
        "unit-human-destroyer", "unit-orc-destroyer",
        "unit-battleship", "unit-ogre-juggernaught",
        "", "unit-fire-breeze",
        "", "",
        "unit-human-submarine", "unit-orc-submarine",
        "unit-balloon", "unit-zeppelin",
        "unit-gryphon-rider", "unit-dragon",
        "unit-knight-rider",
        "unit-eye-of-vision",
        "unit-arthor-literios",
        "unit-quick-blade",
        "",
        "unit-double-head",
        "unit-wise-man",
        "unit-ice-bringer",
        "unit-man-of-light",
        "unit-sharp-axe",
        "",
        "unit-skeleton",
        "unit-daemon",
        "unit-critter",
        "unit-farm", "unit-pig-farm",
        "unit-human-barracks", "unit-orc-barracks",
        "unit-church", "unit-altar-of-storms",
        "unit-human-watch-tower", "unit-orc-watch-tower",
        "unit-stables", "unit-ogre-mound",
        "unit-inventor", "unit-alchemist",
        "unit-gryphon-aviary", "unit-dragon-roost",
        "unit-human-shipyard", "unit-orc-shipyard",
        "unit-town-hall", "unit-great-hall",
        "unit-elven-lumber-mill", "unit-troll-lumber-mill",
        "unit-human-foundry", "unit-orc-foundry",
        "unit-mage-tower", "unit-temple-of-the-damned",
        "unit-human-blacksmith", "unit-orc-blacksmith",
        "unit-human-refinery", "unit-orc-refinery",
        "unit-human-oil-platform", "unit-orc-oil-platform",
        "unit-keep", "unit-stronghold",
        "unit-castle", "unit-fortress",
        "unit-gold-mine",
        "unit-oil-patch",
        "unit-human-start-location", "unit-orc-start-location",
        "unit-human-guard-tower", "unit-orc-guard-tower",
        "unit-human-cannon-tower", "unit-orc-cannon-tower",
        "unit-circle-of-power",
        "unit-dark-portal",
        "unit-runestone",
        "unit-human-wall", "unit-orc-wall"
    };

    // Type codes that carry a resource amount in their Data field. From the
    // PudUnitTypes enum in pud.h.
    private static final int HUMAN_OIL_PLATFORM = 0x56;
    private static final int ORC_OIL_PLATFORM = 0x57;
    private static final int GOLD_MINE = 0x5C;
    private static final int OIL_PATCH = 0x5D;
    private static final int HUMAN_START_LOCATION = 0x5E;
    private static final int ORC_START_LOCATION = 0x5F;

    /**
     * Multiplier turning a PUD's stored resource figure into engine units.
     * The editor stores hundreds of units of 25.
     */
    public static final int RESOURCE_SCALE = 2500;

    private PudUnitTypes() {
    }

    /** The engine identifier for a type byte, or {@code ""} if unassigned. */
    public static String name(int type) {
        return type >= 0 && type < NAMES.length ? NAMES[type] : "";
    }

    /** Number of defined type codes. */
    public static int count() {
        return NAMES.length;
    }

    /** The PUD/BNE numeric code for an engine identifier, or {@code -1}. */
    public static int code(String name) {
        if (name == null || name.isEmpty()) {
            return -1;
        }
        for (int code = 0; code < NAMES.length; code++) {
            if (name.equals(NAMES[code])) {
                return code;
            }
        }
        return -1;
    }

    /** Whether a type carries a resource amount in its data field. */
    public static boolean holdsResources(int type) {
        return type == HUMAN_OIL_PLATFORM || type == ORC_OIL_PLATFORM
                || type == GOLD_MINE || type == OIL_PATCH;
    }

    /** Whether a type marks a player's starting position rather than a real unit. */
    public static boolean isStartLocation(int type) {
        return type == HUMAN_START_LOCATION || type == ORC_START_LOCATION;
    }
}
