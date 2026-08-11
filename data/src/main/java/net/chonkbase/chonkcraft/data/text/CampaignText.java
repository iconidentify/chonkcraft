package net.chonkbase.chonkcraft.data.text;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * The mission titles and objectives, out of the archive.
 *
 * <p>Implements {@code CampaignsLoadData}.
 *
 * <p>The mission scripts do not carry their own text. {@code level01h_c.sms}
 * loads {@code level01h_c2.sms} and passes the globals {@code title} and
 * {@code objectives} to {@code Briefing}, and that second file is written by
 * the extractor from this table. A port that never reads the table leaves both
 * globals unbound, and an unbound global in this script environment resolves to
 * a stub function -- which is how a mission's objectives came out as the
 * printed form of a Java lambda.
 *
 * <p>The layout is one entry of null-terminated strings. From the offset come
 * two per mission, human then orc, for as many missions as the release has;
 * within a string the individual objectives are separated by newlines. The
 * titles follow the same alternating order, and they begin at the first string
 * that starts with a roman numeral and a full stop -- which is what
 * {@code CampaignsLoadData} scans for, because there is no count to rely on.
 */
public final class CampaignText {

    /** How many missions each race has in the original release. */
    private static final int ORIGINAL_MISSIONS = 14;

    /** And in the expansion. */
    private static final int EXPANSION_MISSIONS = 26;

    /** DOS code page 437, which is what the strings are written in. */
    private static final Charset ENCODING = Charset.forName("Cp437");

    /**
     * One mission's text.
     *
     * @param title      the mission's name, such as "Hillsbrad"
     * @param objectives its objectives, one per line as the game lists them
     */
    public record Mission(String title, List<String> objectives) {
        public Mission {
            objectives = objectives == null ? List.of() : List.copyOf(objectives);
        }
    }

    private final List<Mission> human;
    private final List<Mission> orc;

    private CampaignText(List<Mission> human, List<Mission> orc) {
        this.human = List.copyOf(human);
        this.orc = List.copyOf(orc);
    }

    /** The human campaign's missions, in order from one. */
    public List<Mission> human() {
        return human;
    }

    /** The orc campaign's. */
    public List<Mission> orc() {
        return orc;
    }

    /**
     * One mission's text, or null if the table does not have it.
     *
     * @param race   {@code human} or {@code orc}
     * @param number counting from one
     */
    public Mission mission(String race, int number) {
        List<Mission> missions = "orc".equalsIgnoreCase(race) ? orc : human;
        return number >= 1 && number <= missions.size() ? missions.get(number - 1) : null;
    }

    /**
     * Reads the table.
     *
     * @param data      the whole archive entry
     * @param offset    where the objectives start, which differs by release:
     *                  140 for most original discs, 172 for the Spanish one
     *                  and 236 for the expansion
     * @param expansion whether this is the expansion, which has twice as many
     *                  missions
     */
    public static CampaignText read(byte[] data, int offset, boolean expansion) {
        if (data == null || offset < 0 || offset >= data.length) {
            return new CampaignText(List.of(), List.of());
        }
        int missions = expansion ? EXPANSION_MISSIONS : ORIGINAL_MISSIONS;
        int strings = missions * 2;

        int at = offset;
        List<List<String>> objectives = new ArrayList<>();
        for (int i = 0; i < strings && at < data.length; i++) {
            String entry = readString(data, at);
            at += entry.length() + 1;
            objectives.add(splitLines(entry));
        }

        // The titles are somewhere after the objectives and there is no count
        // saying where, so upstream walks forward until a string opens with a
        // roman numeral: "I. Hillsbrad" and its like.
        while (at < data.length && !looksLikeTitle(readString(data, at))) {
            String skipped = readString(data, at);
            at += skipped.length() + 1;
            if (skipped.isEmpty() && at >= data.length) {
                break;
            }
        }

        List<String> titles = new ArrayList<>();
        for (int i = 0; i < strings && at < data.length; i++) {
            String entry = readString(data, at);
            at += entry.length() + 1;
            titles.add(entry);
        }

        List<Mission> human = new ArrayList<>();
        List<Mission> orc = new ArrayList<>();
        for (int level = 0; level < missions; level++) {
            for (int race = 0; race < 2; race++) {
                int index = level * 2 + race;
                String title = index < titles.size() ? titles.get(index) : "";
                List<String> lines = index < objectives.size() ? objectives.get(index) : List.of();
                Mission mission = new Mission(title, lines);
                if (race == 0) {
                    human.add(mission);
                } else {
                    orc.add(mission);
                }
            }
        }
        return new CampaignText(human, orc);
    }

    /**
     * Whether a string is where the titles begin.
     *
     * <p>{@code current[0] != 'I' && current[1] != '.'} is upstream's test, and
     * every mission title in the game is numbered this way.
     */
    private static boolean looksLikeTitle(String text) {
        return text.length() > 1 && text.charAt(0) == 'I' && text.charAt(1) == '.';
    }

    /** One null-terminated string, in the game's own code page. */
    private static String readString(byte[] data, int at) {
        int end = at;
        while (end < data.length && data[end] != 0) {
            end++;
        }
        return new String(data, at, end - at, ENCODING);
    }

    /** The objectives inside one string, which are newline separated. */
    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return lines;
    }
}
