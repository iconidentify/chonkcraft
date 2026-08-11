package net.chonkbase.assetpack.codec.opus;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * RFC 6716 itself, read at test time, as a second opinion on every transcribed
 * table.
 *
 * <p>Point at a copy of the RFC with {@code -Dopus.rfc=/path/to/rfc6716.txt}; it is
 * also found automatically beside the conformance vectors. Tests that use it skip
 * when it is absent, exactly as the vector tests do.
 *
 * <p>Two things are read out of that one file. The first is the prose tables, which
 * are parsed straight out of the ASCII art. The second is the reference
 * implementation: RFC 6716 Appendix A carries a complete gzipped tar of it, base64
 * encoded one line at a time behind a {@code ###} marker, and section A.1 gives the
 * archive's SHA-1 so that an implementer can prove they extracted the real thing.
 * That archive is the normative part of the specification -- section 1 says so --
 * which makes it the authority for the tables the prose only names, the allocation
 * ceilings above all.
 *
 * <p>The point of going to this trouble is that a wrong number in a CELT table is
 * invisible. It does not throw, it does not desynchronise the range coder, and no
 * amount of decoding a conformance vector at the wrong bit rate will reveal it. The
 * only thing that finds it is comparing the number against where it came from.
 */
final class RfcSource {

    /** SHA-1 of the tarball, as printed in RFC 6716 section A.1. */
    private static final String ARCHIVE_SHA1 = "86a927223e73d2476646a1b933fcd3fffb6ecc8c";

    private static final Object LOCK = new Object();
    private static String text;
    private static Map<String, String> sources;

    private RfcSource() {
    }

    /** The RFC text file, or null if no copy was configured or found. */
    static Path path() {
        String configured = System.getProperty("opus.rfc", System.getenv("OPUS_RFC"));
        if (configured != null && !configured.isBlank()) {
            Path candidate = Paths.get(configured);
            return Files.isRegularFile(candidate) ? candidate : null;
        }
        Path vectors = OpusTestVectors.directory();
        if (vectors != null) {
            Path sibling = vectors.getParent() == null
                    ? null : vectors.getParent().resolve("rfc6716.txt");
            if (sibling != null && Files.isRegularFile(sibling)) {
                return sibling;
            }
        }
        return null;
    }

    /** Why a test skipped, in the form the runner shows. */
    static String skipReason() {
        return "RFC 6716 text not found: set -Dopus.rfc=/path/to/rfc6716.txt or put it beside"
                + " the conformance vectors. Download from https://www.rfc-editor.org/rfc/rfc6716.txt";
    }

    /** The whole RFC as text. */
    static String text() {
        synchronized (LOCK) {
            if (text == null) {
                Path path = path();
                if (path == null) {
                    throw new IllegalStateException(skipReason());
                }
                try {
                    text = Files.readString(path, StandardCharsets.ISO_8859_1);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return text;
        }
    }

    /**
     * One file of the reference implementation, extracted from Appendix A.
     *
     * @param name path inside the archive, such as {@code celt/modes.c}
     */
    static String referenceSource(String name) {
        synchronized (LOCK) {
            if (sources == null) {
                sources = extractAppendixA();
            }
        }
        String content = sources.get("opus-rfc6716/" + name);
        if (content == null) {
            throw new IllegalStateException(name + " is not in the RFC 6716 Appendix A archive");
        }
        return content;
    }

    /**
     * A flat integer array from a C source file, however many dimensions it is
     * declared with.
     *
     * @param file path inside the archive, such as {@code celt/modes.c}
     * @param name the array's identifier
     */
    static int[] referenceArray(String file, String name) {
        String source = referenceSource(file);
        Matcher declaration = Pattern.compile(
                "\\b" + Pattern.quote(name) + "\\s*(?:\\[[^\\]]*\\])*\\s*=\\s*\\{(.*?)\\};",
                Pattern.DOTALL).matcher(source);
        if (!declaration.find()) {
            throw new IllegalStateException(name + " not found in " + file);
        }
        String body = declaration.group(1).replaceAll("(?s)/\\*.*?\\*/", "");
        return numbers(body);
    }

    /**
     * A flat float array from a C source file. Only literals suffixed {@code f}
     * count, which is what keeps {@code res.f} and array sizes out of the result.
     */
    static float[] referenceFloats(String file, String expressionStart) {
        String source = referenceSource(file);
        int from = source.indexOf(expressionStart);
        if (from < 0) {
            throw new IllegalStateException(expressionStart + " not found in " + file);
        }
        String body = source.substring(from, source.indexOf(';', from));
        Matcher literal = Pattern.compile("(-?\\d+\\.\\d+)f").matcher(body);
        List<Float> found = new ArrayList<>();
        while (literal.find()) {
            found.add(Float.parseFloat(literal.group(1)));
        }
        float[] result = new float[found.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = found.get(i);
        }
        return result;
    }

    /**
     * RFC 6716 Table 55, the bins-per-band columns, indexed by LM then band.
     */
    static int[][] table55Bins() {
        int[][] rows = table55Rows();
        int[][] bins = new int[4][rows.length];
        for (int lm = 0; lm < 4; lm++) {
            for (int band = 0; band < rows.length; band++) {
                bins[lm][band] = rows[band][1 + lm];
            }
        }
        return bins;
    }

    /** RFC 6716 Table 55, the band edges in Hz, one more than there are bands. */
    static int[] table55EdgeHz() {
        int[][] rows = table55Rows();
        int[] edges = new int[rows.length + 1];
        for (int band = 0; band < rows.length; band++) {
            edges[band] = rows[band][5];
        }
        edges[rows.length] = rows[rows.length - 1][6];
        return edges;
    }

    /**
     * RFC 6716 Table 57, transposed to {@code [quality][band]} from the way the RFC
     * prints it, which is band down the page and quality across.
     */
    static int[][] table57() {
        String block = between("Rows indicate the MDCT bands", "Table 57: CELT Static Allocation");
        List<int[]> rows = new ArrayList<>();
        for (String line : block.split("\n")) {
            int[] cells = fullNumericRow(line, 11);
            if (cells != null) {
                rows.add(cells);
            }
        }
        if (rows.size() != 22) {
            throw new IllegalStateException("Table 57 parsed as " + rows.size() + " rows");
        }
        for (int q = 0; q < 11; q++) {
            if (rows.get(0)[q] != q) {
                throw new IllegalStateException("Table 57 header row is not 0 through 10");
            }
        }
        int[][] byQuality = new int[11][21];
        for (int q = 0; q < 11; q++) {
            for (int band = 0; band < 21; band++) {
                byQuality[q][band] = rows.get(band + 1)[q];
            }
        }
        return byQuality;
    }

    /** RFC 6716 Table 58, the allocation trim PDF out of 128. */
    static int[] table58TrimPdf() {
        return pdf(128);
    }

    /** RFC 6716 Table 56, the spread PDF out of 32. */
    static int[] table56SpreadPdf() {
        return pdf(32);
    }

    /**
     * RFC 6716 Table 59, the finite {@code f_r} rotation factors for spread values 1
     * through 3. Spread 0 is printed as "infinite" and has no number to read.
     */
    static int[] table59SpreadFactors() {
        String block = between("| Spread value | f_r", "Table 59: Spreading Values");
        Matcher row = Pattern.compile("\\|\\s*(\\d)\\s*\\|\\s*(\\d+)\\s*\\|").matcher(block);
        List<Integer> factors = new ArrayList<>();
        while (row.find()) {
            factors.add(Integer.parseInt(row.group(2)));
        }
        int[] result = new int[factors.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = factors.get(i);
        }
        return result;
    }

    /**
     * RFC 6716 Tables 60 through 63, as {@code [table][LM][tf_change]}.
     *
     * <p>Table order is the RFC's: steady tf_select 0, steady tf_select 1, transient
     * tf_select 0, transient tf_select 1.
     */
    static int[][][] tables60To63() {
        String[] captions = {
            "Table 60: TF Adjustments", "Table 61: TF Adjustments",
            "Table 62: TF Adjustments", "Table 63: TF Adjustments"
        };
        int[][][] tables = new int[4][4][2];
        for (int t = 0; t < captions.length; t++) {
            String head = text().substring(0, indexOf(captions[t]));
            Matcher row = Pattern.compile(
                    "\\|\\s*[\\d.]+\\s*\\|\\s*(-?\\d+) \\|\\s*(-?\\d+) \\|").matcher(head);
            List<int[]> pairs = new ArrayList<>();
            while (row.find()) {
                pairs.add(new int[] {
                    Integer.parseInt(row.group(1)), Integer.parseInt(row.group(2))
                });
            }
            if (pairs.size() < 4) {
                throw new IllegalStateException(captions[t] + " parsed as " + pairs.size() + " rows");
            }
            for (int lm = 0; lm < 4; lm++) {
                tables[t][lm] = pairs.get(pairs.size() - 4 + lm);
            }
        }
        return tables;
    }

    private static int[][] table55Rows() {
        String block = between("| Frame  | 2.5 ms |", "Table 55: MDCT Bins per Channel");
        Matcher row = Pattern.compile(
                "\\|\\s*(\\d+)\\s*\\|\\s*(\\d+)\\s\\|\\s*(\\d+)\\s\\|\\s*(\\d+)\\s\\|"
                + "\\s*(\\d+)\\s\\|\\s*(\\d+) Hz\\s*\\|\\s*(\\d+) Hz\\s*\\|").matcher(block);
        List<int[]> rows = new ArrayList<>();
        while (row.find()) {
            int[] cells = new int[7];
            for (int i = 0; i < 7; i++) {
                cells[i] = Integer.parseInt(row.group(i + 1));
            }
            if (cells[0] != rows.size()) {
                throw new IllegalStateException("Table 55 rows are out of order at " + cells[0]);
            }
            rows.add(cells);
        }
        if (rows.size() != 21) {
            throw new IllegalStateException("Table 55 parsed as " + rows.size() + " rows");
        }
        return rows.toArray(new int[0][]);
    }

    /**
     * The first PDF in the RFC written over the given total.
     *
     * <p>The trailing lookahead is load-bearing. Without it, a search for a
     * distribution over 32 finds the silence flag's {@code {32767, 1}/32768} first
     * and reads the wrong numbers with no complaint at all.
     */
    private static int[] pdf(int total) {
        Matcher m = Pattern.compile("\\{([\\d, ]+)\\}/" + total + "(?!\\d)").matcher(text());
        if (!m.find()) {
            throw new IllegalStateException("no PDF over " + total + " found in the RFC");
        }
        int[] values = numbers(m.group(1));
        int sum = 0;
        for (int value : values) {
            sum += value;
        }
        if (sum != total) {
            throw new IllegalStateException(
                    "parsed a PDF over " + total + " whose entries sum to " + sum);
        }
        return values;
    }

    private static int[] fullNumericRow(String line, int width) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("|") || !trimmed.endsWith("|")) {
            return null;
        }
        String[] cells = trimmed.substring(1, trimmed.length() - 1).split("\\|", -1);
        if (cells.length != width) {
            return null;
        }
        int[] values = new int[width];
        for (int i = 0; i < width; i++) {
            String cell = cells[i].trim();
            if (!cell.matches("\\d+")) {
                return null;
            }
            values[i] = Integer.parseInt(cell);
        }
        return values;
    }

    private static String between(String start, String end) {
        int from = indexOf(start);
        int to = indexOf(end);
        if (to <= from) {
            throw new IllegalStateException("'" + end + "' does not follow '" + start + "'");
        }
        return text().substring(from, to);
    }

    private static int indexOf(String needle) {
        int at = text().indexOf(needle);
        if (at < 0) {
            throw new IllegalStateException("'" + needle + "' is not in this file; is it RFC 6716?");
        }
        return at;
    }

    private static int[] numbers(String body) {
        Matcher m = Pattern.compile("-?\\d+").matcher(body);
        List<Integer> found = new ArrayList<>();
        while (m.find()) {
            found.add(Integer.parseInt(m.group()));
        }
        int[] result = new int[found.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = found.get(i);
        }
        return result;
    }

    /**
     * Rebuild the reference implementation from the base64 in Appendix A.
     *
     * <p>The SHA-1 check is not decoration: it is the RFC's own way of telling an
     * implementer whether the bytes they reassembled are the normative ones, and
     * without it a truncated or reflowed copy of the RFC would produce a plausible
     * looking tree full of wrong numbers.
     */
    private static Map<String, String> extractAppendixA() {
        StringBuilder base64 = new StringBuilder();
        for (String line : text().split("\n")) {
            if (line.startsWith("   ###")) {
                base64.append(line.substring(6).trim());
            }
        }
        if (base64.isEmpty()) {
            throw new IllegalStateException("no Appendix A source lines in this file");
        }
        byte[] archive = Base64.getDecoder().decode(base64.toString());
        String actual = sha1(archive);
        if (!ARCHIVE_SHA1.equals(actual)) {
            throw new IllegalStateException(
                    "Appendix A extracted to SHA-1 " + actual + ", but RFC 6716 section A.1 says "
                    + ARCHIVE_SHA1 + "; this is not an intact copy of the RFC");
        }
        byte[] tar = gunzip(archive);
        return untar(tar);
    }

    private static String sha1(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(data);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("no SHA-1 on this JVM", e);
        }
    }

    private static byte[] gunzip(byte[] data) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 22);
            byte[] buffer = new byte[1 << 16];
            for (int read = in.read(buffer); read > 0; read = in.read(buffer)) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The smallest tar reader that can read this one archive: regular files only,
     * short names only, no extensions.
     */
    private static Map<String, String> untar(byte[] tar) {
        Map<String, String> files = new HashMap<>();
        int at = 0;
        while (at + 512 <= tar.length) {
            String name = cString(tar, at, 100);
            if (name.isEmpty()) {
                break;
            }
            long size = octal(tar, at + 124, 12);
            char type = (char) (tar[at + 156] & 0xff);
            int dataAt = at + 512;
            if (dataAt + size > tar.length) {
                throw new IllegalStateException(
                        "tar entry " + name + " claims " + size + " bytes but the archive ends");
            }
            if (type == '0' || type == 0) {
                files.put(name, new String(tar, dataAt, (int) size, StandardCharsets.ISO_8859_1));
            }
            at = dataAt + (int) ((size + 511) / 512) * 512;
        }
        return files;
    }

    private static String cString(byte[] data, int offset, int length) {
        int end = offset;
        while (end < offset + length && data[end] != 0) {
            end++;
        }
        return new String(data, offset, end - offset, StandardCharsets.ISO_8859_1);
    }

    private static long octal(byte[] data, int offset, int length) {
        long value = 0;
        for (int i = offset; i < offset + length; i++) {
            int c = data[i] & 0xff;
            if (c < '0' || c > '7') {
                break;
            }
            value = value * 8 + (c - '0');
        }
        return value;
    }
}
