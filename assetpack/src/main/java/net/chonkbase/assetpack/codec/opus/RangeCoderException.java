package net.chonkbase.assetpack.codec.opus;

/**
 * Thrown when a range-coded stream cannot be read, or written, as asked.
 *
 * <p>A port of the {@code error} field of {@code ec_ctx} in
 * {@code celt/entcode.h}, RFC 6716 sections 4.1 and 5.1, turned from a flag
 * nobody checks into an exception that says what went wrong.
 *
 * <p>It is deliberately narrow. The range decoder is fed attacker-controlled
 * bytes and must not throw for merely being fed nonsense: RFC 6716 section
 * 4.1.2.1 requires it to keep reading zeros once it runs off the end of a
 * frame, and section 4.1.4 allows the raw-bit reader to do the same, so a
 * corrupt frame decodes to garbage audio and the layer above conceals it.
 * What this reports instead is the two things a decoder can be sure about: a
 * caller asking for something the format cannot express (a 40-bit raw field, an
 * unterminated probability table, a buffer slice outside its array), and an
 * encoder that has been handed less buffer than the symbols it was given need.
 * Both of those are bugs in the calling code rather than in the bytes, and a
 * silent one of either produces audio that sounds plausible and is wrong.
 */
public final class RangeCoderException extends RuntimeException {

    RangeCoderException(String message) {
        super(message);
    }
}
