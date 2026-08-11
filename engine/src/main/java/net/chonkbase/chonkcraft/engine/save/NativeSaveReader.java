package net.chonkbase.chonkcraft.engine.save;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the deliberately small, data-only language emitted by {@link SaveGame}.
 *
 * <p>This native reader accepts top-level assignments and
 * function calls whose values are strings, numbers, booleans, nil or nested
 * tables. Unknown calls are ignored for forward compatibility, matching the
 * old loader's metatable stubs.
 */
final class NativeSaveReader {
    @FunctionalInterface
    interface Function {
        Object[] call(Object[] arguments);
    }

    private final SaveTable globals = new SaveTable();
    private final Map<String, Function> functions = new HashMap<>();

    void register(String name, Function function) {
        functions.put(name, function);
    }

    SaveTable globals() {
        return globals;
    }

    void run(String source) {
        new Parser(source).document();
    }

    private final class Parser {
        private final Lexer lexer;
        private Token token;
        private Token next;

        Parser(String source) {
            lexer = new Lexer(source);
            token = lexer.token();
            next = lexer.token();
        }

        void document() {
            while (token.kind != Kind.END) {
                if (is("function")) {
                    skipFunction();
                } else if (is("local")) {
                    advance();
                    assignmentOrCall();
                } else if (token.kind == Kind.IDENTIFIER) {
                    assignmentOrCall();
                } else {
                    advance();
                }
            }
        }

        private void assignmentOrCall() {
            String name = token.text;
            advance();
            if (accept("=")) {
                globals.rawSet(name, value());
            } else if (is("(")) {
                invoke(name);
            }
        }

        private Object value() {
            if (token.kind == Kind.STRING) {
                String value = token.text;
                advance();
                return value;
            }
            if (token.kind == Kind.NUMBER) {
                double value = Double.parseDouble(token.text);
                advance();
                return value;
            }
            if (is("true") || is("false")) {
                boolean value = is("true");
                advance();
                return value;
            }
            if (is("nil")) {
                advance();
                return null;
            }
            if (is("{")) {
                return table();
            }
            if (token.kind == Kind.IDENTIFIER) {
                String name = token.text;
                advance();
                if (is("(")) {
                    Object[] result = invoke(name);
                    return result.length == 0 ? null : result[0];
                }
                return globals.rawGet(name);
            }
            advance();
            return null;
        }

        private SaveTable table() {
            require("{");
            SaveTable result = new SaveTable();
            while (token.kind != Kind.END && !is("}")) {
                if (token.kind == Kind.IDENTIFIER && "=".equals(next.text)) {
                    String key = token.text;
                    advance();
                    require("=");
                    result.rawSet(key, value());
                } else {
                    result.add(value());
                }
                accept(",");
            }
            require("}");
            return result;
        }

        private Object[] invoke(String name) {
            require("(");
            List<Object> arguments = new ArrayList<>();
            while (token.kind != Kind.END && !is(")")) {
                arguments.add(value());
                if (!accept(",") && !is(")")) {
                    advance();
                }
            }
            require(")");
            Function function = functions.get(name);
            return function == null ? new Object[0]
                    : function.call(arguments.toArray(Object[]::new));
        }

        private void skipFunction() {
            while (token.kind != Kind.END && !is("end")) {
                advance();
            }
            accept("end");
        }

        private boolean is(String text) {
            return text.equals(token.text);
        }

        private boolean accept(String text) {
            if (!is(text)) {
                return false;
            }
            advance();
            return true;
        }

        private void require(String text) {
            if (!accept(text)) {
                throw new IllegalArgumentException("Malformed save near '" + token.text
                        + "': expected '" + text + "'");
            }
        }

        private void advance() {
            token = next;
            next = lexer.token();
        }
    }

    private enum Kind { IDENTIFIER, NUMBER, STRING, SYMBOL, END }
    private record Token(Kind kind, String text) {}

    private static final class Lexer {
        private final String source;
        private int at;

        Lexer(String source) {
            this.source = source == null ? "" : source;
        }

        Token token() {
            skipSpaceAndComments();
            if (at >= source.length()) {
                return new Token(Kind.END, "");
            }
            char c = source.charAt(at);
            if (Character.isJavaIdentifierStart(c)) {
                int start = at++;
                while (at < source.length()
                        && Character.isJavaIdentifierPart(source.charAt(at))) {
                    at++;
                }
                return new Token(Kind.IDENTIFIER, source.substring(start, at));
            }
            if (c == '"') {
                return string();
            }
            if (c == '-' || c == '+' || Character.isDigit(c)) {
                int start = at++;
                while (at < source.length()) {
                    char digit = source.charAt(at);
                    if (!(Character.isDigit(digit) || digit == '.' || digit == 'e'
                            || digit == 'E' || digit == '+' || digit == '-')) {
                        break;
                    }
                    at++;
                }
                return new Token(Kind.NUMBER, source.substring(start, at));
            }
            at++;
            return new Token(Kind.SYMBOL, String.valueOf(c));
        }

        private Token string() {
            at++;
            StringBuilder value = new StringBuilder();
            while (at < source.length()) {
                char c = source.charAt(at++);
                if (c == '"') {
                    return new Token(Kind.STRING, value.toString());
                }
                if (c == '\\' && at < source.length()) {
                    char escaped = source.charAt(at++);
                    value.append(escaped == 'n' ? '\n' : escaped);
                } else {
                    value.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string in save");
        }

        private void skipSpaceAndComments() {
            while (at < source.length()) {
                if (Character.isWhitespace(source.charAt(at))) {
                    at++;
                    continue;
                }
                if (source.charAt(at) == '-' && at + 1 < source.length()
                        && source.charAt(at + 1) == '-') {
                    at += 2;
                    while (at < source.length() && source.charAt(at) != '\n') {
                        at++;
                    }
                    continue;
                }
                return;
            }
        }
    }
}

