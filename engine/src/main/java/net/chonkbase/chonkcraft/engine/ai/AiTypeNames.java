package net.chonkbase.chonkcraft.engine.ai;

import java.util.List;

/**
 * What the PUD's per-slot AI byte means.
 *
 * <p>A PUD's {@code AIPL} section holds one byte per player slot, and the byte
 * is an index into this table. The names are the ones {@code DefineAi}
 * registers: {@code "hum-04"} is the personality the fourth human mission
 * writes for its orc opponent, {@code "wc2-passive"} is the garrison that sits
 * still, {@code "wc2-sea-attack"} is the one that builds a navy.
 *
 * <p>Implements {@code AiTypeNames}, which is where
 * the byte becomes a name: {@code wartool} writes one
 * {@code SetAiType(slot, name)} line per slot into the {@code .sms} it
 * generates, and LegacyEngine reads the name from there. This implementation reads the PUD
 * out of the archive directly and never runs {@code wartool}, so the generated
 * file does not exist and the translation has to happen here instead. Without
 * it the byte is parsed and discarded, and every computer player on every map
 * plays whatever single script the engine happened to pick.
 *
 * <p>The order is the table's order and must not be sorted or tidied: the
 * index is the map data.
 */
public final class AiTypeNames {

    private AiTypeNames() {
    }

    /**
     * The names, by the byte that selects them.
     *
     * <p>Entries 0 to 31 are the original release, the rest are Beyond the
     * Dark Portal. Numbering runs {@code hum-04}, {@code orc-04},
     * {@code hum-05} and so on because the two campaigns were authored
     * together, and it breaks at 23 where the last mission of each campaign
     * needs one personality per enemy colour.
     */
    private static final List<String> NAMES = List.of(
            "wc2-land-attack",
            "wc2-passive",
            "orc-03",
            "hum-04",
            "orc-04",
            "hum-05",
            "orc-05",
            "hum-06",
            "orc-06",
            "hum-07",
            "orc-07",
            "hum-08",
            "orc-08",
            "hum-09",
            "orc-09",
            "hum-10",
            "orc-10",
            "hum-11",
            "orc-11",
            "hum-12",
            "orc-12",
            "hum-13",
            "orc-13",
            "hum-14-orange",
            "orc-14-blue",
            "wc2-sea-attack",
            "wc2-air-attack",
            "hum-14-red",
            "hum-14-white",
            "hum-14-black",
            "orc-14-green",
            "orc-14-white",
            // Beyond the Dark Portal.
            "orc-exp-4",
            "orc-exp-5",
            "orc-exp-7a",
            "orc-exp-9",
            "orc-exp-10",
            "orc-exp-12",
            "orc-exp-6a",
            "orc-exp-6b",
            "orc-exp-11a",
            "orc-exp-11b",
            "hum-exp-2a",
            "hum-exp-2b",
            "hum-exp-2c",
            "hum-exp-3a",
            "hum-exp-3b",
            "hum-exp-3c",
            "hum-exp-4a",
            "hum-exp-4b",
            "hum-exp-4c",
            "hum-exp-5a",
            "hum-exp-5b",
            "hum-exp-5c",
            "hum-exp-5d",
            "hum-exp-6a",
            "hum-exp-6b",
            "hum-exp-6c",
            "hum-exp-6d",
            "hum-exp-8a",
            "hum-exp-8b",
            "hum-exp-8c",
            "hum-exp-9a",
            "hum-exp-9b",
            "hum-exp-9c",
            "hum-exp-9d",
            "hum-exp-10a",
            "hum-exp-10b",
            "hum-exp-10c",
            "hum-exp-11a",
            "hum-exp-11b",
            "hum-exp-12a",
            "orc-exp-5b",
            "hum-exp-7a",
            "hum-exp-7b",
            "hum-exp-7c",
            "orc-exp-12a",
            "orc-exp-12b",
            "orc-exp-12c",
            "orc-exp-12d",
            "orc-exp-2",
            "orc-exp-7b",
            "orc-exp-3");

    /** How many names the table holds. */
    public static int count() {
        return NAMES.size();
    }

    /**
     * The script name a PUD AI byte selects.
     *
     * <p>A byte past the end of the table answers with the first entry, which
     * is what upstream falls back to when it cannot match a name.
     */
    public static String name(int aiType) {
        return aiType >= 0 && aiType < NAMES.size() ? NAMES.get(aiType) : NAMES.get(0);
    }

    /** The whole table, in index order. */
    public static List<String> names() {
        return NAMES;
    }
}
