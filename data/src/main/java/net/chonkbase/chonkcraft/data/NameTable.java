package net.chonkbase.chonkcraft.data;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The game's string table, used to build asset paths.
 *
 * <p>{@code wartool.h} embeds this table as a byte blob called {@code Names}.
 * It does not need to: the bytes are exactly entry 1 of {@code strdat.war},
 * so this reads them from the user's own installation instead.
 *
 * <p>Layout: a 16-bit count, then that many 16-bit offsets from the start of
 * the entry, each pointing at a NUL-terminated string. Index 0 holds the
 * count itself, so real strings start at 1.
 *
 * <p>Asset paths in the conversion table refer to these by number, as in
 * {@code "human/units/%16"}. {@link #expand} performs that substitution the
 * way {@code ParseString} does.
 */
public final class NameTable {

    private final String[] names;

    private NameTable(String[] names) {
        this.names = names;
    }

    /** Reads the table from entry 1 of {@code strdat.war}. */
    public static NameTable from(byte[] entry) {
        if (entry.length < 2) {
            throw new IllegalArgumentException("string table is too short");
        }
        int count = readLe16(entry, 0);
        String[] names = new String[count];
        for (int i = 1; i < count; i++) {
            int offset = readLe16(entry, i * 2);
            if (offset <= 0 || offset >= entry.length) {
                names[i] = "";
                continue;
            }
            int end = offset;
            while (end < entry.length && entry[end] != 0) {
                end++;
            }
            names[i] = new String(entry, offset, end - offset, StandardCharsets.ISO_8859_1);
        }
        return new NameTable(names);
    }

    /** Number of slots, including the unused zeroth. */
    public int size() {
        return names.length;
    }

    /** The string at {@code index}, or {@code ""} if there is none. */
    public String name(int index) {
        return index > 0 && index < names.length && names[index] != null ? names[index] : "";
    }

    /**
     * Substitutes {@code %N} references into a path template.
     *
     * <p>Implements {@code ParseString}. Each reference is replaced by the
     * named string lowercased, with spaces and hyphens becoming underscores,
     * so {@code "human/units/%16"} becomes
     * {@code "human/units/dwarven_demolition_squad"}.
     *
     * <p>A leading minus, as in {@code %-3}, takes only the part after the
     * first space, which is how the table names a unit's second word.
     */
    public String expand(String template) {
        StringBuilder out = new StringBuilder(template.length());
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c != '%') {
                out.append(c);
                i++;
                continue;
            }
            i++;
            boolean afterFirstSpace = false;
            if (i < template.length() && template.charAt(i) == '-') {
                afterFirstSpace = true;
                i++;
            }
            int start = i;
            while (i < template.length() && Character.isDigit(template.charAt(i))) {
                i++;
            }
            if (start == i) {
                // A stray percent with no number; keep it as written.
                out.append('%');
                continue;
            }
            String value = name(Integer.parseInt(template.substring(start, i)));
            if (afterFirstSpace) {
                int space = value.indexOf(' ');
                value = space >= 0 ? value.substring(space + 1) : value;
            }
            for (int k = 0; k < value.length(); k++) {
                char ch = value.charAt(k);
                out.append(ch == '-' || ch == ' ' ? '_' : Character.toLowerCase(ch));
            }
        }
        return out.toString();
    }

    /** Whether a template contains any {@code %N} reference. */
    public static boolean hasReference(String template) {
        return template.indexOf('%') >= 0;
    }

    private static int readLe16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    @Override
    public String toString() {
        return "NameTable(" + names.length + " entries)";
    }

    /** Lower-cases without locale surprises; exposed for callers building paths. */
    public static String normalise(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
