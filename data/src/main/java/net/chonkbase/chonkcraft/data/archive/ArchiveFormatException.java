package net.chonkbase.chonkcraft.data.archive;

/** A Warcraft II archive that is missing, truncated, or not the expected version. */
public class ArchiveFormatException extends RuntimeException {

    public ArchiveFormatException(String message) {
        super(message);
    }

    public ArchiveFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
