package net.chonkbase.chonkcraft.engine.sound;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.chonkbase.chonkcraft.engine.unit.UnitType;

/**
 * Which of the sound table's names each unit answers to.
 *
 * <p>Implements the {@code Sounds} half of {@code CclDefineUnitType}, holding what the {@code Sounds = {...}}
 * blocks in {@code scripts/units.legacy-declaration}, {@code scripts/human/units.legacy-declaration} and
 * {@code scripts/orc/units.legacy-declaration} declare. That is 107 units and 398 bindings,
 * over six events and no others.
 *
 * <p>This table is backed entirely by native declarations, and it is
 * the half of the first one that was missing. {@link SoundBindings} says where
 * {@code footman-selected} keeps its recordings; nothing shipped said that a
 * footman is what answers to it, so the shipped sound table was keyed on names
 * only somebody else's checkout supplied. Both halves are here now, and
 * {@code UnitVoicesWithoutScriptsRealDataTest} holds one to the other without
 * reading any retired scripting language at all.
 *
 * <p>It does not yet remove the checkout, because the roster these attach to is
 * still {@code units.legacy-declaration}. What it removes is the risk in the order the
 * detachment has to happen in: when the roster is generated, a generator that
 * forgets the {@code Sounds} blocks would silence 107 units, and every one of
 * those bindings would still parse, still have an accessor, and be read by
 * nothing -- which is this repository's commonest fault wearing a new hat.
 */
public final class UnitSounds {

    /** Where the shipped table lives. */
    private static final String RESOURCE = "/chonkcraft/unit-sounds.tsv";

    private UnitSounds() {
    }

    /**
     * Gives every type in {@code types} the voice the shipped table gives it.
     *
     * <p>Every type is written, not only the ones the table names. A type the
     * table is silent about ends with no sounds at all, which is what the
     * scripts give the fourteen empty blocks and the twenty-two markers the
     * roster builds in a loop. Leaving those alone would look harmless and
     * would mean the game was still playing whatever the retired scripting language had put there,
     * which is the thing this is supposed to stop.
     */
    public static void install(Map<String, UnitType> types) {
        Map<String, Map<String, String>> table = byUnitType();
        for (Map.Entry<String, UnitType> entry : types.entrySet()) {
            Map<String, String> sounds = entry.getValue().sounds();
            sounds.clear();
            Map<String, String> shipped = table.get(entry.getKey());
            if (shipped != null) {
                sounds.putAll(shipped);
            }
        }
    }

    /**
     * The shipped table: unit identifier, then event to sound name.
     *
     * <p>In the order the file is written, which is the order the scripts
     * declare, so a reader comparing the two reads them the same way round.
     */
    public static Map<String, Map<String, String>> byUnitType() {
        Map<String, Map<String, String>> table = new LinkedHashMap<>();
        try (InputStream in = UnitSounds.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + RESOURCE);
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] row = line.split("\t", -1);
                if (row.length != 3) {
                    throw new IllegalStateException(
                            "unit sound row is not three fields: " + line);
                }
                table.computeIfAbsent(row[0], ignored -> new LinkedHashMap<>())
                        .put(row[1], row[2]);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Map<String, String>> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : table.entrySet()) {
            frozen.put(entry.getKey(), Collections.unmodifiableMap(entry.getValue()));
        }
        return Collections.unmodifiableMap(frozen);
    }
}
