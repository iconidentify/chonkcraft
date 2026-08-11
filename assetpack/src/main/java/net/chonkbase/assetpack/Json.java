package net.chonkbase.assetpack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A JSON reader and writer, in one file, with no dependencies.
 *
 * <p>Not a general-purpose library. It exists because a pack manifest has to
 * be readable by a human with a text editor and by an artist's tooling in any
 * language, and because this module is not allowed to depend on anything. It
 * handles exactly what a manifest contains: objects, arrays, strings, numbers,
 * booleans and null.
 *
 * <p>Numbers come back as {@link Long} when they have no fraction or exponent
 * and as {@link Double} otherwise. That distinction matters: a frame count
 * written as {@code 45} must not read back as {@code 45.0} and then be
 * rendered as {@code "45.0"} on the next write, because the manifest is
 * compared byte for byte in the round-trip test.
 */
public final class Json {

    private Json() {
    }

    /** Thrown when the text is not JSON, with the offset it went wrong at. */
    public static final class SyntaxException extends RuntimeException {
        SyntaxException(String message, int at) {
            super(message + " at offset " + at);
        }
    }

    // ------------------------------------------------------------------ read

    /** Parses a document into maps, lists, strings, numbers, booleans and nulls. */
    public static Object parse(String text) {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.value();
        parser.skipWhitespace();
        if (parser.at < text.length()) {
            throw new SyntaxException("trailing content", parser.at);
        }
        return value;
    }

    /** Parses a document that must be an object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new SyntaxException("expected an object at the top level", 0);
        }
        return (Map<String, Object>) value;
    }

    private static final class Parser {
        private final String text;
        private int at;

        Parser(String text) {
            this.text = text;
        }

        void skipWhitespace() {
            while (at < text.length()) {
                char c = text.charAt(at);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    at++;
                } else {
                    break;
                }
            }
        }

        Object value() {
            if (at >= text.length()) {
                throw new SyntaxException("unexpected end of input", at);
            }
            char c = text.charAt(at);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        Object literal(String word, Object result) {
            if (!text.startsWith(word, at)) {
                throw new SyntaxException("expected " + word, at);
            }
            at += word.length();
            return result;
        }

        Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            at++;
            skipWhitespace();
            if (at < text.length() && text.charAt(at) == '}') {
                at++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = string();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                map.put(key, value());
                skipWhitespace();
                if (at >= text.length()) {
                    throw new SyntaxException("unterminated object", at);
                }
                char c = text.charAt(at++);
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new SyntaxException("expected , or } in object", at - 1);
                }
            }
        }

        List<Object> array() {
            List<Object> list = new ArrayList<>();
            at++;
            skipWhitespace();
            if (at < text.length() && text.charAt(at) == ']') {
                at++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(value());
                skipWhitespace();
                if (at >= text.length()) {
                    throw new SyntaxException("unterminated array", at);
                }
                char c = text.charAt(at++);
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new SyntaxException("expected , or ] in array", at - 1);
                }
            }
        }

        String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (at >= text.length()) {
                    throw new SyntaxException("unterminated string", at);
                }
                char c = text.charAt(at++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (at >= text.length()) {
                    throw new SyntaxException("unterminated escape", at);
                }
                char escape = text.charAt(at++);
                switch (escape) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (at + 4 > text.length()) {
                            throw new SyntaxException("truncated \\u escape", at);
                        }
                        out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        at += 4;
                    }
                    default -> throw new SyntaxException("unknown escape \\" + escape, at - 1);
                }
            }
        }

        Object number() {
            int start = at;
            if (at < text.length() && (text.charAt(at) == '-' || text.charAt(at) == '+')) {
                at++;
            }
            boolean fractional = false;
            while (at < text.length()) {
                char c = text.charAt(at);
                if (c >= '0' && c <= '9') {
                    at++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    fractional = true;
                    at++;
                } else {
                    break;
                }
            }
            if (at == start) {
                throw new SyntaxException("expected a value", at);
            }
            String literal = text.substring(start, at);
            try {
                return fractional ? (Object) Double.valueOf(literal) : (Object) Long.valueOf(literal);
            } catch (NumberFormatException e) {
                throw new SyntaxException("bad number " + literal, start);
            }
        }

        void expect(char c) {
            if (at >= text.length() || text.charAt(at) != c) {
                throw new SyntaxException("expected " + c, at);
            }
            at++;
        }
    }

    // ----------------------------------------------------------------- write

    /**
     * Renders a value, indented two spaces a level.
     *
     * <p>Pretty-printed rather than compact on purpose. A manifest is a
     * document an artist reads and a reviewer diffs; the few hundred kilobytes
     * of whitespace vanish in the zip's deflate.
     */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(out, value, 0);
        out.append('\n');
        return out.toString();
    }

    private static void writeValue(StringBuilder out, Object value, int depth) {
        switch (value) {
            case null -> out.append("null");
            case Map<?, ?> map -> writeObject(out, map, depth);
            case List<?> list -> writeArray(out, list, depth);
            case String string -> writeString(out, string);
            case Boolean bool -> out.append(bool.booleanValue() ? "true" : "false");
            case Double d -> out.append(finite(d));
            case Float f -> out.append(finite(f.doubleValue()));
            case Number number -> out.append(number.toString());
            default -> writeString(out, value.toString());
        }
    }

    /** JSON has no NaN or infinity, so a caller that produces one is a bug. */
    private static String finite(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("cannot write " + value + " as JSON");
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    private static void writeObject(StringBuilder out, Map<?, ?> map, int depth) {
        if (map.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append("{\n");
        int remaining = map.size();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            indent(out, depth + 1);
            writeString(out, String.valueOf(entry.getKey()));
            out.append(": ");
            writeValue(out, entry.getValue(), depth + 1);
            if (--remaining > 0) {
                out.append(',');
            }
            out.append('\n');
        }
        indent(out, depth);
        out.append('}');
    }

    private static void writeArray(StringBuilder out, List<?> list, int depth) {
        if (list.isEmpty()) {
            out.append("[]");
            return;
        }
        // A list of plain scalars stays on one line: a font's 224 glyph widths
        // as 224 lines makes the manifest unreadable, which defeats the point
        // of it being readable.
        boolean scalars = list.stream().allMatch(v -> v == null
                || v instanceof Number || v instanceof Boolean || v instanceof String);
        if (scalars) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                writeValue(out, list.get(i), depth);
            }
            out.append(']');
            return;
        }
        out.append("[\n");
        for (int i = 0; i < list.size(); i++) {
            indent(out, depth + 1);
            writeValue(out, list.get(i), depth + 1);
            if (i < list.size() - 1) {
                out.append(',');
            }
            out.append('\n');
        }
        indent(out, depth);
        out.append(']');
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7F) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static void indent(StringBuilder out, int depth) {
        out.append("  ".repeat(depth));
    }

    // ---------------------------------------------------------------- access

    /** The string at {@code key}, or {@code fallback}. */
    public static String string(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value instanceof String s ? s : fallback;
    }

    /** The string at {@code key}, or a failure naming the key. */
    public static String required(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String s)) {
            throw new PackFormatException("manifest entry is missing \"" + key + "\"");
        }
        return s;
    }

    /** The integer at {@code key}, or {@code fallback}. */
    public static int integer(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        return value instanceof Number n ? n.intValue() : fallback;
    }

    /** The long at {@code key}, or {@code fallback}. */
    public static long longValue(Map<String, Object> map, String key, long fallback) {
        Object value = map.get(key);
        return value instanceof Number n ? n.longValue() : fallback;
    }

    /** The boolean at {@code key}, or {@code fallback}. */
    public static boolean bool(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        return value instanceof Boolean b ? b.booleanValue() : fallback;
    }

    /** The object at {@code key}, or an empty map. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    /** The array at {@code key}, or an empty list. */
    @SuppressWarnings("unchecked")
    public static List<Object> array(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof List ? (List<Object>) value : List.of();
    }

    /** The array at {@code key} read as integers. */
    public static int[] integers(Map<String, Object> map, String key) {
        List<Object> list = array(map, key);
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i) instanceof Number n ? n.intValue() : 0;
        }
        return out;
    }
}
