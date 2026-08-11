package net.chonkbase.assetpack;

/** Thrown when a pack is not one this version of the format can read. */
public class PackFormatException extends RuntimeException {

    public PackFormatException(String message) {
        super(message);
    }

    public PackFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
