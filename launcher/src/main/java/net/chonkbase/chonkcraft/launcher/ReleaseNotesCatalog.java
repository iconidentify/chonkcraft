package net.chonkbase.chonkcraft.launcher;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** A bounded, deterministic history of authenticated game publications. */
public final class ReleaseNotesCatalog {

    public record Entry(String version, String published, String title,
            String body, String revision) {

        public Entry {
            version = clean(version, 80);
            published = clean(published, 64);
            title = clean(title, 160);
            body = clean(body, 12_000);
            revision = clean(revision, 128);
        }
    }

    public record History(List<Entry> entries) {

        public History {
            entries = List.copyOf(entries == null ? List.of() : entries);
        }

        public static History empty() {
            return new History(List.of());
        }

        public Entry latest() {
            return entries.isEmpty() ? null : entries.getFirst();
        }
    }

    static final int MAX_ENTRIES = 100;
    static final long MAX_BYTES = 512L * 1024;
    private static final String FORMAT = "chonkcraft-release-notes-1";

    private ReleaseNotesCatalog() {
    }

    public static History parse(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) {
            throw new IOException("release-note history is larger than its safety limit");
        }
        Properties values = new Properties();
        try (var input = new ByteArrayInputStream(bytes)) {
            values.load(input);
        } catch (IllegalArgumentException e) {
            throw new IOException("release-note history is malformed", e);
        }
        if (!FORMAT.equals(values.getProperty("format", ""))) {
            throw new IOException("release-note history has an unsupported format");
        }
        int count;
        try {
            count = Integer.parseInt(values.getProperty("count", ""));
        } catch (NumberFormatException e) {
            throw new IOException("release-note history has an invalid entry count", e);
        }
        if (count < 0 || count > MAX_ENTRIES) {
            throw new IOException("release-note history has too many entries");
        }
        List<Entry> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String prefix = "entry." + index + ".";
            String version = required(values, prefix + "version");
            if (!version.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,79}")) {
                throw new IOException("release-note history contains an invalid version");
            }
            String published = required(values, prefix + "published");
            try {
                Instant.parse(published);
            } catch (DateTimeParseException e) {
                throw new IOException("release-note history contains an invalid date", e);
            }
            entries.add(new Entry(version, published,
                    decode(values, prefix + "title"),
                    decode(values, prefix + "body"),
                    values.getProperty(prefix + "revision", "")));
        }
        return new History(entries);
    }

    public static byte[] encode(History history) throws IOException {
        StringBuilder text = new StringBuilder();
        text.append("format=").append(FORMAT).append('\n');
        int count = Math.min(history.entries().size(), MAX_ENTRIES);
        text.append("count=").append(count).append('\n');
        for (int index = 0; index < count; index++) {
            Entry entry = history.entries().get(index);
            String prefix = "entry." + index + ".";
            text.append(prefix).append("version=").append(entry.version()).append('\n');
            text.append(prefix).append("published=").append(entry.published()).append('\n');
            text.append(prefix).append("title=").append(encode(entry.title())).append('\n');
            text.append(prefix).append("body=").append(encode(entry.body())).append('\n');
            text.append(prefix).append("revision=").append(safeProperty(entry.revision()))
                    .append('\n');
        }
        byte[] bytes = text.toString().getBytes(StandardCharsets.US_ASCII);
        if (bytes.length > MAX_BYTES) {
            throw new IOException("release-note history is larger than its safety limit");
        }
        return bytes;
    }

    public static History append(History previous, Entry current) {
        Map<String, Entry> unique = new LinkedHashMap<>();
        unique.put(current.version(), current);
        for (Entry entry : previous.entries()) {
            unique.putIfAbsent(entry.version(), entry);
            if (unique.size() == MAX_ENTRIES) {
                break;
            }
        }
        return new History(List.copyOf(unique.values()));
    }

    static History fromRelease(GameReleaseManager.Release release) {
        if (release.notes() == null || release.notes().isBlank()) {
            return History.empty();
        }
        String published = release.published();
        try {
            Instant.parse(published);
        } catch (DateTimeParseException e) {
            published = Instant.EPOCH.toString();
        }
        String title = "ChonkCraft " + release.version();
        String body = release.notes();
        if (body.startsWith("Automated game update from ")) {
            title = "Earlier ChonkCraft update";
            body = "This release predates detailed release notes.";
        }
        return new History(List.of(new Entry(release.version(), published,
                title, body, release.revision())));
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(Properties values, String key) throws IOException {
        try {
            return new String(Base64.getDecoder().decode(required(values, key)),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IOException("release-note history contains invalid text", e);
        }
    }

    private static String required(Properties values, String key) throws IOException {
        String value = values.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IOException("release-note history has no " + key);
        }
        return value.trim();
    }

    private static String clean(String value, int limit) {
        String cleaned = value == null ? "" : value.replace("\u0000", "").trim();
        return cleaned.length() <= limit ? cleaned : cleaned.substring(0, limit).trim();
    }

    private static String safeProperty(String value) {
        return value.replace("\\", "\\\\").replace("\n", " ").replace("\r", " ");
    }
}
