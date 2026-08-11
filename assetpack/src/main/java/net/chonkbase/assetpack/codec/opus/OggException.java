package net.chonkbase.assetpack.codec.opus;

/**
 * Thrown when a run of bytes is not an Ogg Opus stream a reader may trust.
 *
 * <p>The container half of RFC 7845 and RFC 3533, in the same spirit as
 * {@link OpusPacket.MalformedPacketException} one layer down: everything the
 * format says must hold before a byte of audio is looked at is checked, and a
 * failure says which page and which byte offset.
 *
 * <p>The reason it exists at all is what happens without it. A page header is
 * 27 bytes plus a lacing table whose length that header declares, and the
 * payload length is the sum of the lacing table. Truncate a download halfway
 * through and every one of those numbers still parses; the sum simply points
 * past the end of the array. An {@link ArrayIndexOutOfBoundsException} from
 * three frames deep in a demuxer tells whoever gets the bug report nothing at
 * all, and it arrives with a stack trace that blames the audio thread rather
 * than the truncated file. Every read in {@link OggReader} is bounds-checked
 * first and reports the offset it wanted, so a corrupt pack names itself.
 */
public final class OggException extends RuntimeException {

    OggException(String message) {
        super(message);
    }
}
