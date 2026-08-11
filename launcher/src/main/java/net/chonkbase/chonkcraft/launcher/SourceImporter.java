package net.chonkbase.chonkcraft.launcher;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.chonkbase.chonkcraft.data.archive.CdImage;
import net.chonkbase.chonkcraft.data.archive.HfsImage;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.extract.PackBuilder;

/**
 * Turns the things people actually have into one verified graphics pack.
 *
 * <p>An installed directory is the easy case. The same game is more often
 * found twenty or thirty years later as a ZIP containing a directory, a ZIP
 * containing BIN/CUE media, a CloneCD IMG, an ISO, or a Toast image wrapped in
 * StuffIt. Every lane ends at the same point: a temporary directory
 * {@link InstallSource} recognizes, then the existing pack builder and its
 * read-back verifier do the conversion.
 */
public final class SourceImporter {

    /** A short message suitable for the progress panel. */
    @FunctionalInterface
    public interface Progress {
        void update(ProgressUpdate update);
    }

    /** What the exact counter under a progress stage measures. */
    public enum Unit {
        NONE,
        BYTES,
        ASSETS,
        CHECKS,
        TRACKS
    }

    /** One monotonic, measurable update for the whole import pipeline. */
    public record ProgressUpdate(String message, int percent, long completed,
            long total, Unit unit) {

        public ProgressUpdate {
            percent = Math.max(0, Math.min(100, percent));
            completed = Math.max(0, completed);
            total = Math.max(0, total);
            unit = unit == null ? Unit.NONE : unit;
        }

        public boolean measured() {
            return total > 0 && unit != Unit.NONE;
        }
    }

    /** What an import produced. */
    public record Result(PackLibrary.PackInfo pack, PackBuilder.Report report,
            List<String> steps) {}

    /** What source discovery proved before the expensive pack build. */
    public record Inspection(Path installation, String description, boolean expansion,
            boolean battleNet, int maps, int musicTracks) {}

    private static final long MAX_ZIP_BYTES = 20L * 1024 * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 100_000;
    private static final int SEARCH_DEPTH = 14;
    private static final Pattern CUE_FILE = Pattern.compile(
            "\\s*FILE\\s+(?:\"([^\"]+)\"|(\\S+))\\s+\\S+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CUE_TRACK = Pattern.compile(
            "\\s*TRACK\\s+\\d+\\s+(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CUE_INDEX = Pattern.compile(
            "\\s*INDEX\\s+\\d+\\s+(\\d+):(\\d+):(\\d+)",
            Pattern.CASE_INSENSITIVE);

    private final LauncherHome home;
    private final PackLibrary library;

    public SourceImporter(LauncherHome home, PackLibrary library) {
        this.home = home;
        this.library = library;
    }

    /**
     * Imports {@code input}, verifies every converted asset, and only then
     * makes the finished pack visible in the library.
     */
    public Result importSource(Path input, Progress progress) throws IOException {
        List<String> steps = new ArrayList<>();
        Reporter reporter = new Reporter(steps, progress);
        reporter.phase("Preparing the source", 1);
        Path source = requireSource(input);
        if (isSuffix(source, ".chonkpack")) {
            reporter.phase("Adding the existing graphics pack", 98);
            Path added = library.add(source);
            reporter.phase("Graphics pack added", 100);
            return new Result(PackLibrary.read(added), null, List.copyOf(steps));
        }

        home.create();
        Path work = Files.createTempDirectory(home.work(), "import-");
        try {
            Prepared prepared = prepare(source, work, reporter);
            reporter.phase("Reading " + prepared.description(), 42);
            Path destination = library.destinationFor(source);
            Path unfinished = work.resolve("pack.chonkpack.new");
            PackBuilder.Report report;
            try (InstallSource install = InstallSource.at(prepared.installation())) {
                requirePlayableSource(install);
                Map<String, Object> sourceDetails = provenance(source, reporter);
                report = new PackBuilder(install, true, sourceDetails,
                        (phase, completed, total) -> {
                            switch (phase) {
                                case ANALYZING -> reporter.items(
                                        "Analyzing the CD soundtrack", completed, total,
                                        47, 53, Unit.TRACKS);
                                case BUILDING -> reporter.items(
                                        "Forging the graphics pack", completed, total,
                                        53, 84, Unit.ASSETS);
                                case VERIFYING -> reporter.items(
                                        "Proving every packed asset", completed, total,
                                        84, 98, Unit.CHECKS);
                            }
                        })
                        .build(unfinished);
            }
            boolean failed = report.notes().stream()
                    .anyMatch(note -> note.startsWith("VERIFICATION FAILED"));
            if (failed) {
                throw new IOException("the graphics pack did not match the source after conversion");
            }
            reporter.phase("Verification passed; installing the graphics pack", 99);
            move(unfinished, destination);
            PackLibrary.PackInfo info = PackLibrary.read(destination);
            if (info == null) {
                throw new IOException("the finished graphics pack could not be reopened");
            }
            reporter.phase("Ready to play with " + info.name(), 100);
            return new Result(info, report, List.copyOf(steps));
        } finally {
            deleteTree(work);
        }
    }

    /** Discovers and opens a source without building a pack from it. */
    public Inspection inspect(Path input, Progress progress) throws IOException {
        home.create();
        Path work = Files.createTempDirectory(home.work(), "inspect-");
        try {
            Reporter reporter = new Reporter(new ArrayList<>(), progress);
            reporter.phase("Preparing the source", 1);
            Prepared prepared = prepare(requireSource(input), work, reporter);
            try (InstallSource install = InstallSource.at(prepared.installation())) {
                requirePlayableSource(install);
                Inspection inspection = new Inspection(prepared.installation(), install.describe(),
                        install.isExpansionRelease(), install.isBattleNetEdition(),
                        install.mapNames().size(), install.musicTracks().size());
                reporter.phase("Source ready", 100);
                return inspection;
            }
        } finally {
            deleteTree(work);
        }
    }

    private Prepared prepare(Path source, Path work, Reporter progress) throws IOException {
        if (Files.isDirectory(source)) {
            Path installation = findInstallation(source);
            if (installation != null) {
                progress.say("Found Warcraft II in " + installation);
                return new Prepared(installation, "the selected Warcraft II directory");
            }
            return prepareMediaUnder(source, work, progress);
        }

        String name = lowerName(source);
        if (name.endsWith(".zip")) {
            Path expanded = work.resolve("zip");
            progress.say("Opening " + source.getFileName());
            expandZip(source, expanded, progress);
            Path installation = findInstallation(expanded);
            if (installation != null) {
                progress.say("Found the game directory inside the ZIP");
                return new Prepared(installation, "the Warcraft II files in " + source.getFileName());
            }
            return prepareMediaUnder(expanded, work, progress);
        }
        if (name.endsWith(".sit")) {
            Path expanded = work.resolve("stuffit");
            progress.say("Opening the StuffIt archive");
            extractStuffIt(source, expanded);
            Path installation = findInstallation(expanded);
            if (installation != null) {
                return new Prepared(installation, "the Warcraft II files in " + source.getFileName());
            }
            return prepareMediaUnder(expanded, work, progress);
        }
        if (name.endsWith(".cue")) {
            Path image = imageNamedByCue(source);
            if (image == null) {
                throw new IOException("the cue sheet does not point at an image beside it");
            }
            return prepareImage(image, work, progress);
        }
        if (name.endsWith(".ccd")) {
            Path image = siblingIgnoringCase(source,
                    stripSuffix(source.getFileName().toString()) + ".img");
            if (image == null) {
                throw new IOException("the CloneCD control file has no IMG beside it");
            }
            return prepareImage(image, work, progress);
        }
        if (isMedia(source)) {
            return prepareImage(source, work, progress);
        }
        if (name.endsWith(".dmg") || name.endsWith(".7z") || name.endsWith(".rar")
                || name.endsWith(".tar") || name.endsWith(".gz")) {
            Path expanded = work.resolve("archive");
            progress.say("Opening " + source.getFileName());
            extractWithSevenZip(source, expanded);
            Path installation = findInstallation(expanded);
            if (installation != null) {
                return new Prepared(installation, "the Warcraft II files in " + source.getFileName());
            }
            return prepareMediaUnder(expanded, work, progress);
        }
        throw new IOException("unsupported source type: " + source.getFileName()
                + ". Choose a directory, ZIP, SIT, Toast,"
                + " ISO, BIN/CUE, IMG/CCD,"
                + " DMG, 7z, RAR, TAR, or an existing .chonkpack.");
    }

    private static void requirePlayableSource(InstallSource install) throws IOException {
        if (!install.hasRetailAiProgram()) {
            throw new IOException("this media is missing the original ai.bin computer-player "
                    + "program; choose an original Warcraft II retail or Battle.net release");
        }
    }

    private Prepared prepareMediaUnder(Path root, Path work, Reporter progress)
            throws IOException {
        List<Path> nestedArchives = new ArrayList<>();
        List<Path> images = new ArrayList<>();
        try (var walk = Files.walk(root, SEARCH_DEPTH, FileVisitOption.FOLLOW_LINKS)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                if (isMedia(path)) {
                    images.add(path);
                } else if (isSuffix(path, ".sit")) {
                    nestedArchives.add(path);
                }
            }
        }
        nestedArchives.sort(Comparator.comparing(Path::toString));
        for (int i = 0; i < nestedArchives.size(); i++) {
            Path expanded = work.resolve("nested-stuffit-" + i);
            progress.say("Opening " + nestedArchives.get(i).getFileName());
            extractStuffIt(nestedArchives.get(i), expanded);
            Path installation = findInstallation(expanded);
            if (installation != null) {
                return new Prepared(installation, "the Warcraft II files in "
                        + nestedArchives.get(i).getFileName());
            }
            try (var walk = Files.walk(expanded, SEARCH_DEPTH)) {
                walk.filter(Files::isRegularFile).filter(SourceImporter::isMedia)
                        .forEach(images::add);
            }
        }
        images.sort(Comparator.comparing(Path::toString));
        IOException last = null;
        Set<Path> attempted = new HashSet<>();
        for (Path image : images) {
            Path real = image.toAbsolutePath().normalize();
            if (!attempted.add(real)) {
                continue;
            }
            try {
                return prepareImage(image, work, progress);
            } catch (IOException e) {
                last = e;
            }
        }
        if (last != null) {
            throw last;
        }
        throw new IOException("no Warcraft II installation or readable disc image was found under "
                + root);
    }

    private Prepared prepareImage(Path image, Path work, Reporter progress)
            throws IOException {
        Path destination = Files.createTempDirectory(work, "disc-");
        progress.phase("Reading disc image " + image.getFileName(), 24);
        try (CdImage disc = CdImage.open(image)) {
            if (disc != null) {
                long total = disc.entries().values().stream()
                        .mapToLong(CdImage.Entry::length).sum();
                disc.extractAll(destination, completed -> progress.bytes(
                        "Extracting " + disc.volumeName(), completed, total, 24, 40));
            } else {
                Path cooked = work.resolve("cooked-" + destination.getFileName() + ".iso");
                int sectorLimit = dataTrackSectors(image);
                long availableSectors = Files.size(image) / 2352;
                long cookedSectors = Math.min(availableSectors,
                        sectorLimit > 0 ? sectorLimit : availableSectors);
                if (CdImage.cookDataTrack(image, cooked, sectorLimit,
                        completed -> progress.bytes("Converting the raw data track",
                                completed, cookedSectors * 2352, 24, 33))) {
                    try (CdImage cookedDisc = CdImage.open(cooked)) {
                        if (cookedDisc != null) {
                            long total = cookedDisc.entries().values().stream()
                                    .mapToLong(CdImage.Entry::length).sum();
                            cookedDisc.extractAll(destination,
                                    completed -> progress.bytes(
                                            "Extracting " + cookedDisc.volumeName(),
                                            completed, total, 33, 40));
                        } else {
                            progress.phase("Opening the Macintosh filesystem", 33);
                            extractCookedImage(cooked, destination, progress);
                        }
                    }
                } else {
                    progress.say("Trying the system archive tools for this image");
                    extractWithSevenZip(image, destination);
                }
            }
        }
        Path installation = findInstallation(destination);
        if (installation == null) {
            throw new IOException(image.getFileName()
                    + " opened, but its data track has no Warcraft II archives");
        }
        preserveDisc(image, installation);
        return new Prepared(installation, "the " + image.getFileName() + " disc image");
    }

    /**
     * Keeps mixed-mode media beside the extracted files until the pack is
     * finished. The data track supplies the archives; the original BIN/IMG and
     * CUE supply the red-book soundtrack, which does not exist as files inside
     * the data track.
     */
    private static void preserveDisc(Path image, Path installation) throws IOException {
        Path linkedImage = installation.resolve(image.getFileName().toString());
        linkOrCopy(image, linkedImage);
        Path cue = siblingIgnoringCase(image,
                stripSuffix(image.getFileName().toString()) + ".cue");
        if (cue != null) {
            linkOrCopy(cue, installation.resolve(cue.getFileName().toString()));
        }
    }

    private static void linkOrCopy(Path source, Path destination) throws IOException {
        if (source.toAbsolutePath().normalize().equals(destination.toAbsolutePath().normalize())
                || Files.exists(destination)) {
            return;
        }
        try {
            Files.createLink(destination, source);
        } catch (UnsupportedOperationException | IOException e) {
            Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    /**
     * Facts about the exact thing the player chose.
     *
     * <p>The extracted directory is temporary and used to be the only source
     * named by a pack. Two discs or community installers could consequently
     * produce entries with different bytes and still look identical in the
     * launcher. The original name, size and checksum survive that temporary
     * directory and make the distinction visible years later.
     */
    private static Map<String, Object> provenance(Path source, Reporter progress)
            throws IOException {
        Map<String, Object> details = new LinkedHashMap<>();
        Path name = source.getFileName();
        details.put("sourceOriginalName", name == null ? source.toString() : name.toString());
        details.put("sourceContainer", sourceContainer(source));
        if (Files.isRegularFile(source)) {
            details.put("sourceOriginalBytes", Files.size(source));
            details.put("sourceOriginalSha256", sha256(source, progress));
        } else {
            progress.phase("Source identity recorded", 47);
        }
        return details;
    }

    private static String sourceContainer(Path source) {
        if (Files.isDirectory(source)) {
            return "Game folder or mounted disc";
        }
        String name = lowerName(source);
        if (name.endsWith(".cue") || name.endsWith(".bin")) {
            return "BIN/CUE disc image";
        }
        if (name.endsWith(".ccd") || name.endsWith(".img")) {
            return "IMG/CCD disc image";
        }
        if (name.endsWith(".iso") || name.endsWith(".toast") || name.endsWith(".dmg")) {
            return "Disc image";
        }
        if (name.endsWith(".sit")) {
            return "StuffIt archive";
        }
        if (name.endsWith(".zip") || name.endsWith(".7z") || name.endsWith(".rar")
                || name.endsWith(".tar") || name.endsWith(".gz")) {
            return "Downloaded archive";
        }
        return "Warcraft II media";
    }

    private static String sha256(Path source, Reporter progress) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("this Java runtime has no SHA-256", e);
        }
        byte[] buffer = new byte[1024 * 1024];
        long total = Files.size(source);
        long completed = 0;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(source))) {
            for (int count; (count = in.read(buffer)) >= 0; ) {
                if (count > 0) {
                    digest.update(buffer, 0, count);
                    completed += count;
                    progress.bytes("Fingerprinting the original source",
                            completed, total, 42, 47);
                }
            }
        }
        StringBuilder out = new StringBuilder(64);
        for (byte value : digest.digest()) {
            out.append(Character.forDigit((value >>> 4) & 15, 16));
            out.append(Character.forDigit(value & 15, 16));
        }
        return out.toString();
    }

    private static Path findInstallation(Path root) throws IOException {
        InstallSource atRoot = containsInstallMarker(root) ? InstallSource.tryAt(root) : null;
        if (atRoot != null) {
            atRoot.close();
            return root;
        }
        try (var walk = Files.walk(root, SEARCH_DEPTH)) {
            List<Path> directories = walk.filter(Files::isDirectory)
                    .sorted(Comparator.comparingInt(Path::getNameCount)
                            .thenComparing(Path::toString))
                    .toList();
            for (Path directory : directories) {
                if (!containsInstallMarker(directory)) {
                    continue;
                }
                InstallSource found = InstallSource.tryAt(directory);
                if (found != null) {
                    found.close();
                    return directory;
                }
            }
        }
        return null;
    }

    /**
     * Distinguishes a real extracted directory from a parent that merely has a
     * disc image somewhere beneath it. {@code InstallSource} can pull one
     * archive out of such an image on demand, which is useful to the game but
     * too weak for import discovery: accepting that parent used to lose every
     * loose map and the whole disc soundtrack.
     */
    private static boolean containsInstallMarker(Path root) {
        for (Path directory : List.of(
                root, root.resolve("DATA"), root.resolve("data"),
                root.resolve("support/tomes"), root.resolve("Support/TOMES"),
                root.resolve("SUPPORT/TOMES"))) {
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (var listing = Files.list(directory)) {
                boolean found = listing.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .anyMatch(name -> name.equalsIgnoreCase("maindat.war")
                                || name.equalsIgnoreCase("War Data")
                                || name.equalsIgnoreCase("tome.1")
                                || name.equalsIgnoreCase("War2Dat.mpq"));
                if (found) {
                    return true;
                }
            } catch (IOException e) {
                // One unreadable candidate does not hide another layout.
            }
        }
        return false;
    }

    private static void expandZip(Path source, Path destination, Reporter progress)
            throws IOException {
        Files.createDirectories(destination);
        Path root = destination.toAbsolutePath().normalize();
        long written = 0;
        int entries = 0;
        byte[] buffer = new byte[64 * 1024];
        try (ZipFile zip = new ZipFile(source.toFile())) {
            long total = zip.stream()
                    .filter(entry -> !entry.isDirectory())
                    .mapToLong(entry -> Math.max(0, entry.getSize()))
                    .sum();
            var listing = zip.entries();
            while (listing.hasMoreElements()) {
                ZipEntry entry = listing.nextElement();
                if (++entries > MAX_ZIP_ENTRIES) {
                    throw new IOException("the ZIP contains more than " + MAX_ZIP_ENTRIES
                            + " entries");
                }
                Path target = root.resolve(entry.getName()).normalize();
                if (!target.startsWith(root)) {
                    throw new IOException("the ZIP contains an unsafe path: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (InputStream in = new BufferedInputStream(zip.getInputStream(entry));
                        OutputStream out = new BufferedOutputStream(
                                Files.newOutputStream(target))) {
                    for (int count; (count = in.read(buffer)) >= 0; ) {
                        if (count == 0) {
                            continue;
                        }
                        written += count;
                        if (written > MAX_ZIP_BYTES) {
                            throw new IOException("the ZIP expands beyond the 20 GB safety limit");
                        }
                        out.write(buffer, 0, count);
                        progress.bytes("Unpacking " + source.getFileName(),
                                written, total, 2, 22);
                    }
                }
            }
        }
    }

    private static void extractStuffIt(Path source, Path destination) throws IOException {
        Files.createDirectories(destination);
        IOException failure = runAny(List.of(
                List.of("unar", "-quiet", "-force-overwrite",
                        "-output-directory", destination.toString(), source.toString()),
                List.of("7zz", "x", "-y", "-o" + destination, source.toString()),
                List.of("7z", "x", "-y", "-o" + destination, source.toString())));
        if (failure != null) {
            throw new IOException("StuffIt extraction needs unar (The Unarchiver), and no"
                    + " compatible extractor could open " + source.getFileName(), failure);
        }
    }

    private static void extractWithSevenZip(Path source, Path destination) throws IOException {
        Files.createDirectories(destination);
        IOException failure = runAny(List.of(
                List.of("7zz", "x", "-y", "-o" + destination, source.toString()),
                List.of("7z", "x", "-y", "-o" + destination, source.toString()),
                List.of("unar", "-quiet", "-force-overwrite",
                        "-output-directory", destination.toString(), source.toString())));
        if (failure != null) {
            throw new IOException("no compatible archive tool could open "
                    + source.getFileName(), failure);
        }
    }

    private static void extractCookedImage(Path source, Path destination,
            Reporter progress) throws IOException {
        try (HfsImage hfs = HfsImage.open(source)) {
            if (hfs != null) {
                hfs.extractAll(destination, completed -> progress.bytes(
                        "Extracting " + hfs.volumeName(), completed,
                        hfs.dataBytes(), 33, 40));
                return;
            }
        }
        try {
            extractWithSevenZip(source, destination);
            return;
        } catch (IOException sevenZip) {
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
                throw sevenZip;
            }
            Path mount = destination.resolveSibling(destination.getFileName() + "-mount");
            Files.createDirectories(mount);
            IOException attached = runAny(List.of(List.of(
                    "hdiutil", "attach", "-readonly", "-nobrowse",
                    "-mountpoint", mount.toString(), source.toString())));
            if (attached != null) {
                throw new IOException("the HFS data track could not be mounted", attached);
            }
            try {
                copyTree(mount, destination);
            } finally {
                runAny(List.of(List.of("hdiutil", "detach", mount.toString())));
                deleteTree(mount);
            }
        }
    }

    /**
     * Tries commands in order. A command that is absent and a command that
     * refuses the format both move to the next compatible extractor.
     */
    private static IOException runAny(List<List<String>> commands) {
        IOException last = null;
        for (List<String> command : commands) {
            try {
                List<String> resolved = new ArrayList<>(command);
                resolved.set(0, resolveTool(command.getFirst()));
                Process process = new ProcessBuilder(resolved)
                        .redirectErrorStream(true)
                        .start();
                byte[] output = process.getInputStream().readAllBytes();
                int code = process.waitFor();
                if (code == 0) {
                    return null;
                }
                last = new IOException(resolved.getFirst() + " exited " + code + ": "
                        + new String(output, java.nio.charset.StandardCharsets.UTF_8).trim());
            } catch (IOException e) {
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new IOException("archive extraction was interrupted", e);
            }
        }
        return last == null ? new IOException("no archive command was available") : last;
    }

    /**
     * Finds tools even when a native app was opened by Finder and inherited
     * only the system PATH. Homebrew's two prefixes and the normal local Unix
     * prefix cover installed unar/7-Zip; the Windows candidates cover the
     * standard 7-Zip installer.
     */
    private static String resolveTool(String name) {
        List<Path> candidates = new ArrayList<>();
        for (String directory : List.of(
                "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin")) {
            candidates.add(Path.of(directory, name));
        }
        for (String variable : List.of("ProgramFiles", "ProgramFiles(x86)")) {
            String directory = System.getenv(variable);
            if (directory != null && !directory.isBlank()) {
                candidates.add(Path.of(directory, "7-Zip",
                        name.endsWith(".exe") ? name : name + ".exe"));
            }
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return name;
    }

    private static Path imageNamedByCue(Path cue) throws IOException {
        for (String line : Files.readAllLines(cue, java.nio.charset.StandardCharsets.ISO_8859_1)) {
            Matcher match = CUE_FILE.matcher(line);
            if (!match.matches()) {
                continue;
            }
            String name = match.group(1) == null ? match.group(2) : match.group(1);
            Path found = siblingIgnoringCase(cue, Path.of(name).getFileName().toString());
            if (found != null) {
                return found;
            }
        }
        for (String suffix : List.of(".bin", ".img", ".iso")) {
            Path found = siblingIgnoringCase(cue,
                    stripSuffix(cue.getFileName().toString()) + suffix);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** The first audio track's first index, which is where the data track stops. */
    private static int dataTrackSectors(Path image) throws IOException {
        Path cue = siblingIgnoringCase(image,
                stripSuffix(image.getFileName().toString()) + ".cue");
        if (cue == null) {
            return 0;
        }
        boolean audio = false;
        for (String line : Files.readAllLines(cue,
                java.nio.charset.StandardCharsets.ISO_8859_1)) {
            Matcher track = CUE_TRACK.matcher(line);
            if (track.matches()) {
                audio = "AUDIO".equalsIgnoreCase(track.group(1));
                continue;
            }
            Matcher index = CUE_INDEX.matcher(line);
            if (audio && index.matches()) {
                int minutes = Integer.parseInt(index.group(1));
                int seconds = Integer.parseInt(index.group(2));
                int frames = Integer.parseInt(index.group(3));
                return (minutes * 60 + seconds) * 75 + frames;
            }
        }
        return 0;
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        try (var walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                Path target = destination.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else if (Files.isRegularFile(path)) {
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static Path siblingIgnoringCase(Path path, String name) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return null;
        }
        Path exact = parent.resolve(name);
        if (Files.isRegularFile(exact)) {
            return exact;
        }
        try (var listing = Files.list(parent)) {
            return listing.filter(Files::isRegularFile)
                    .filter(candidate -> candidate.getFileName().toString().equalsIgnoreCase(name))
                    .findFirst().orElse(null);
        }
    }

    private static Path requireSource(Path input) throws IOException {
        if (input == null) {
            throw new IOException("no source was selected");
        }
        Path source = input.toAbsolutePath().normalize();
        if (!Files.exists(source)) {
            throw new IOException("the selected source no longer exists: " + source);
        }
        return source;
    }

    private static boolean isMedia(Path file) {
        String name = lowerName(file);
        return name.endsWith(".bin") || name.endsWith(".img")
                || name.endsWith(".iso") || name.endsWith(".toast");
    }

    private static boolean isSuffix(Path file, String suffix) {
        return lowerName(file).endsWith(suffix);
    }

    private static String lowerName(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT);
    }

    private static String stripSuffix(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static void move(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, destination);
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            // A stale import directory is safe and can be removed next launch.
        }
    }

    /**
     * Turns exact per-pass counters into one monotonic 0-100 journey.
     *
     * <p>The percentages are pipeline completion, not a pretend time estimate.
     * The accompanying counter remains the ground truth: bytes while files are
     * copied, tracks while audio is scanned, assets while the pack is written,
     * and checks while it is read back. Updates are coalesced to percentage
     * changes so a 64 KiB copy loop cannot flood Swing's event queue.
     */
    private static final class Reporter {

        private final List<String> steps;
        private final Progress progress;
        private String lastStep = "";
        private String lastMessage = "";
        private int lastPercent = -1;

        private Reporter(List<String> steps, Progress progress) {
            this.steps = steps;
            this.progress = progress;
        }

        private void say(String message) {
            publish(message, Math.max(0, lastPercent), 0, 0, Unit.NONE);
        }

        private void phase(String message, int percent) {
            publish(message, percent, 0, 0, Unit.NONE);
        }

        private void bytes(String message, long completed, long total,
                int fromPercent, int toPercent) {
            measured(message, completed, total, fromPercent, toPercent, Unit.BYTES);
        }

        private void items(String message, long completed, long total,
                int fromPercent, int toPercent, Unit unit) {
            measured(message, completed, total, fromPercent, toPercent, unit);
        }

        private void measured(String message, long completed, long total,
                int fromPercent, int toPercent, Unit unit) {
            long boundedTotal = Math.max(1, total);
            long boundedCompleted = Math.max(0, Math.min(completed, boundedTotal));
            int span = Math.max(0, toPercent - fromPercent);
            int percent = fromPercent
                    + (int) Math.min(span,
                            boundedCompleted * span / boundedTotal);
            publish(message, percent, boundedCompleted, boundedTotal, unit);
        }

        private void publish(String message, int percent, long completed,
                long total, Unit unit) {
            int monotonic = Math.max(lastPercent, Math.max(0, Math.min(100, percent)));
            boolean changedMessage = !message.equals(lastMessage);
            boolean finishedCounter = total > 0 && completed >= total;
            if (!changedMessage && monotonic == lastPercent && !finishedCounter) {
                return;
            }
            if (!message.equals(lastStep)) {
                steps.add(message);
                lastStep = message;
            }
            lastMessage = message;
            lastPercent = monotonic;
            if (progress != null) {
                progress.update(new ProgressUpdate(
                        message, monotonic, completed, total, unit));
            }
        }
    }

    private record Prepared(Path installation, String description) {}
}
