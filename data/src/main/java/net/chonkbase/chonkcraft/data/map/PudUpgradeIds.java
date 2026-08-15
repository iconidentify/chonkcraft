package net.chonkbase.chonkcraft.data.map;

/**
 * Retail UGRD column indexes 0x00..0x21, in on-disk order.
 *
 * <p>The same table the replay dispatcher uses for family-2 research.
 */
public final class PudUpgradeIds {

    private static final String[] NAMES = {
        "upgrade-sword1", "upgrade-sword2",
        "upgrade-battle-axe1", "upgrade-battle-axe2",
        "upgrade-arrow1", "upgrade-arrow2",
        "upgrade-throwing-axe1", "upgrade-throwing-axe2",
        "upgrade-human-shield1", "upgrade-human-shield2",
        "upgrade-orc-shield1", "upgrade-orc-shield2",
        "upgrade-human-ship-cannon1", "upgrade-human-ship-cannon2",
        "upgrade-orc-ship-cannon1", "upgrade-orc-ship-cannon2",
        "upgrade-human-ship-armor1", "upgrade-human-ship-armor2",
        "upgrade-orc-ship-armor1", "upgrade-orc-ship-armor2",
        "upgrade-catapult1", "upgrade-catapult2",
        "upgrade-ballista1", "upgrade-ballista2",
        "upgrade-ranger", "upgrade-longbow",
        "upgrade-ranger-scouting", "upgrade-ranger-marksmanship",
        "upgrade-berserker", "upgrade-light-axes",
        "upgrade-berserker-scouting", "upgrade-berserker-regeneration",
        "upgrade-paladin", "upgrade-ogre-mage"
    };

    private PudUpgradeIds() {
    }

    public static int indexOf(String ident) {
        if (ident == null) {
            return -1;
        }
        for (int i = 0; i < NAMES.length; i++) {
            if (ident.equals(NAMES[i])) {
                return i;
            }
        }
        return -1;
    }

    public static String name(int index) {
        return index >= 0 && index < NAMES.length ? NAMES[index] : "";
    }

    public static int count() {
        return NAMES.length;
    }
}
