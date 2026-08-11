package net.chonkbase.chonkcraft.data.map;

/** A file that is not a well-formed Warcraft II map. */
public class PudFormatException extends RuntimeException {

    public PudFormatException(String message) {
        super(message);
    }
}
