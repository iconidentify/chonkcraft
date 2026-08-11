package net.chonkbase.chonkcraft.matchmaking;

import java.security.SecureRandom;
import java.util.Locale;

/** A short code that can be read aloud without ambiguous characters. */
public final class RoomCode {

    private static final String ALPHABET = "123456789ABCDEFGHJKMNPQRSTVWXYZ";

    public static final int LENGTH = 6;

    private RoomCode() {
    }

    /** Draws roughly thirty bits of code space from a cryptographic source. */
    public static String generate(SecureRandom random) {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    /**
     * Accepts pasted spaces and hyphens and repairs the usual I/L reading mistakes.
     *
     * @throws IllegalArgumentException when the result is not a room code
     */
    public static String normalize(String value) {
        String cleaned = value == null ? "" : value.toUpperCase(Locale.ROOT)
                .replace("-", "")
                .replace(" ", "")
                .replace('I', '1')
                .replace('L', '1');
        if (cleaned.length() != LENGTH) {
            throw new IllegalArgumentException("A game code has six characters.");
        }
        for (int i = 0; i < cleaned.length(); i++) {
            if (ALPHABET.indexOf(cleaned.charAt(i)) < 0) {
                throw new IllegalArgumentException("That game code is not valid.");
            }
        }
        return cleaned;
    }
}
