package net.chonkbase.chonkcraft.data.archive;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the music tracks off a Warcraft II CD.
 *
 * <p>The DOS release plays its music two ways. A machine with a sound card
 * gets XMI through the synthesiser, which is what {@code muddat}'s eighteen
 * tracks are; a machine with the disc in the drive gets the same music as
 * red book audio, recorded rather than synthesised, and it sounds
 * considerably better. Sixteen of those tracks sit on the Tides of Darkness
 * disc after the data track.
 *
 * <p>Red book audio is not a file. It is raw sectors: 2352 bytes each holding
 * 588 stereo frames of 16-bit samples at 44,100 a second, with no header of
 * any kind. Where each track starts is not in the image either, it is in the
 * {@code .cue} beside it, written as minutes, seconds and frames of a
 * seventy-five-per-second clock.
 */
public final class CdAudio implements AutoCloseable {

    /** Bytes per sector, the same raw size the data track uses. */
    private static final int RAW_SECTOR = 2352;

    /** Sectors per second on the red book clock. */
    private static final int SECTORS_PER_SECOND = 75;

    /** What the samples are, once read. */
    public static final int SAMPLE_RATE = 44_100;
    public static final int CHANNELS = 2;

    private static final Pattern TRACK = Pattern.compile(
            "\\s*TRACK\\s+(\\d+)\\s+(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern INDEX = Pattern.compile(
            "\\s*INDEX\\s+0?1\\s+(\\d+):(\\d+):(\\d+)", Pattern.CASE_INSENSITIVE);

    /**
     * One track on the disc.
     *
     * @param number    its number, counting from one as the disc does
     * @param audio     whether it is music rather than the data track
     * @param startLba  the sector it begins at
     * @param endLba    the sector after its last, exclusive
     */
    public record Track(int number, boolean audio, int startLba, int endLba) {

        /** How long it runs, in seconds. */
        public double seconds() {
            return (endLba - startLba) / (double) SECTORS_PER_SECOND;
        }
    }

    private final RandomAccessFile image;
    private final List<Track> tracks;

    private CdAudio(RandomAccessFile image, List<Track> tracks) {
        this.image = image;
        this.tracks = tracks;
    }

    /**
     * Opens a disc image and its cue sheet, or returns null.
     *
     * <p>Null rather than an exception because a caller usually asks this of
     * whatever images it found lying about, and most discs are not this one.
     *
     * @param image the {@code .img} or {@code .bin}; its {@code .cue} is looked
     *              for beside it
     */
    public static CdAudio open(Path image) {
        Path cue = withSuffix(image, ".cue");
        if (!Files.isRegularFile(image) || !Files.isRegularFile(cue)) {
            return null;
        }
        try {
            long sectors = Files.size(image) / RAW_SECTOR;
            List<Track> tracks = parseCue(Files.readAllLines(cue, StandardCharsets.ISO_8859_1),
                    (int) sectors);
            if (tracks.isEmpty()) {
                return null;
            }
            return new CdAudio(new RandomAccessFile(image.toFile(), "r"), tracks);
        } catch (IOException e) {
            return null;
        }
    }

    private static Path withSuffix(Path path, String suffix) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot);
        return path.resolveSibling(base + suffix);
    }

    /**
     * Reads track boundaries out of a cue sheet.
     *
     * <p>A track's end is the next track's start, and the last one runs to the
     * end of the image. The cue does not say where anything ends, only where
     * it begins, which is why this has to be worked out rather than read.
     */
    static List<Track> parseCue(List<String> lines, int imageSectors) {
        List<int[]> found = new ArrayList<>();
        int number = -1;
        boolean audio = false;
        for (String line : lines) {
            Matcher track = TRACK.matcher(line);
            if (track.lookingAt()) {
                number = Integer.parseInt(track.group(1));
                audio = "AUDIO".equalsIgnoreCase(track.group(2));
                continue;
            }
            Matcher index = INDEX.matcher(line);
            if (index.lookingAt() && number > 0) {
                int minutes = Integer.parseInt(index.group(1));
                int seconds = Integer.parseInt(index.group(2));
                int frames = Integer.parseInt(index.group(3));
                int lba = (minutes * 60 + seconds) * SECTORS_PER_SECOND + frames;
                found.add(new int[] {number, audio ? 1 : 0, lba});
                number = -1;
            }
        }

        List<Track> tracks = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            int[] entry = found.get(i);
            int end = i + 1 < found.size() ? found.get(i + 1)[2] : imageSectors;
            tracks.add(new Track(entry[0], entry[1] == 1, entry[2], Math.max(entry[2], end)));
        }
        return tracks;
    }

    /** Every track on the disc, data track included. */
    public List<Track> tracks() {
        return List.copyOf(tracks);
    }

    /** Only the music. */
    public List<Track> musicTracks() {
        return tracks.stream().filter(Track::audio).toList();
    }

    /**
     * Reads a track as interleaved 16-bit stereo at 44,100.
     *
     * <p>No decoding is involved: the sectors are the samples, little-endian,
     * left channel first. That is the whole of the red book format, and the
     * only reason it needs a method is that nothing says where a track stops.
     */
    public short[] read(Track track) throws IOException {
        if (!track.audio()) {
            throw new IllegalArgumentException("track " + track.number() + " is not audio");
        }
        int sectors = track.endLba() - track.startLba();
        short[] samples = new short[sectors * (RAW_SECTOR / 2)];
        byte[] sector = new byte[RAW_SECTOR];
        int at = 0;
        for (int i = 0; i < sectors; i++) {
            long offset = (long) (track.startLba() + i) * RAW_SECTOR;
            if (offset + RAW_SECTOR > image.length()) {
                break;
            }
            image.seek(offset);
            image.readFully(sector);
            for (int b = 0; b + 1 < sector.length; b += 2) {
                samples[at++] = (short) ((sector[b] & 0xFF) | (sector[b + 1] << 8));
            }
        }
        return at == samples.length ? samples : java.util.Arrays.copyOf(samples, at);
    }

    /** The images beside an installation that carry music. */
    public static List<Path> discsUnder(Path root) {
        List<Path> found = new ArrayList<>();
        for (Path image : CdImage.imagesUnder(root)) {
            String name = image.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".img") && !name.endsWith(".bin")) {
                continue;
            }
            if (Files.isRegularFile(withSuffix(image, ".cue"))) {
                found.add(image);
            }
        }
        return found;
    }

    @Override
    public void close() throws IOException {
        image.close();
    }
}
