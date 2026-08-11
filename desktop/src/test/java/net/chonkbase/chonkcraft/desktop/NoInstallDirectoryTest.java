package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fails the build if the desktop layer learns that a Warcraft II installation
 * is a directory.
 *
 * <p>The twin of {@code engine/NoInstallDirectoryTest}, and it has to be a
 * twin rather than a shared helper because a module's sources are only
 * reachable from a test inside that module. The rule is the same: the game
 * reads an {@code AssetSource} and knows nothing else about where its data
 * comes from, because a source can be an asset pack -- a single file, built
 * years after the 1995 discs went in a drawer -- and a pack has no install
 * directory to look in, no {@code DATA} subdirectory and no CD lying beside
 * it.
 *
 * <p>The desktop layer is where this is easiest to get wrong, because it is
 * the layer that talks to the player about their installation. It prints the
 * help that tells them to set {@code -Dwc2.install.dir}, it lists the maps it
 * found, and it once walked the install directory three separate times to do
 * it. It does none of that now; it asks the source. Telling a player where to
 * put a path is not the same as holding one.
 *
 * <p>Modelled on {@code extractor/IsolationTest}, which enforces the same wall
 * from the other side: the extractor may not see the game. Between them the
 * pack is a contract rather than a private cache.
 *
 * <p><b>Read this before "fixing" a failure.</b> What is forbidden is a
 * <em>reference to one of four types</em>. What is emphatically not forbidden
 * is the text {@code wc2.install.dir} or {@code WC2_INSTALL_DIR}. Those two
 * strings appear all over this module -- in the skip message of every test
 * that needs real 1995 data, and in the help {@code Main} prints for a player
 * who has not configured anything -- and they must stay exactly as they are.
 * They are how a person is told to configure a machine and how
 * {@code scripts/ci/check-test-skips.py} classifies a skip; deleting them to
 * make something here go green would break the continuous integration script,
 * the setup documentation and the launcher's own help, and would fix nothing,
 * because a configuration string is not the game holding a directory.
 * {@link #theConfigurationStringsAreNotWhatIsForbidden()} exists to make that
 * concrete: it takes every file in this module that names those strings and
 * shows they are all clean.
 *
 * <p>Comments are stripped before the check, so prose may name a forbidden
 * type -- this Javadoc does. String literals are <em>not</em> stripped, so a
 * class named through reflection is still caught.
 *
 * <p>Checked by reading the source rather than the classpath, because this
 * module legitimately depends on {@code data} and always will: {@code PudMap},
 * {@code IndexedImage} and {@code Palette} all live there. A dependency check
 * would see nothing wrong. It is these four types specifically, and only a
 * reader of the source can tell.
 */
class NoInstallDirectoryTest {

    /**
     * The four types the engine may not name, and what each one would drag in.
     *
     * <p>{@code Warcraft2Install} is the install-directory search itself, and
     * it is package-private in {@code data.source} now precisely so this
     * cannot happen; the check stays because package-private is a compiler
     * rule and this is a design rule, and the two have different lifetimes.
     * {@code WarArchive} is the {@code .war} file reader -- holding one means
     * holding a file, where {@code EntryArchive} means holding entries.
     * {@code CdAudio} and {@code CdImage} read raw sectors off a disc image
     * found next to an installation; music reaches the player off the source,
     * which works when the discs are long gone.
     */
    private static final List<String> FORBIDDEN =
            List.of("Warcraft2Install", "WarArchive", "CdAudio", "CdImage");

    /** The two configuration names that are not type references and must survive. */
    private static final List<String> CONFIGURATION =
            List.of("wc2.install.dir", "WC2_INSTALL_DIR");

    @Test
    @DisplayName("no source in this module names an installation, an archive file or a disc")
    void theDesktopCannotNameAnInstallation() {
        List<Path> sources = sources();
        List<String> offences = new ArrayList<>();
        for (Path path : sources) {
            for (String forbidden : offencesIn(path)) {
                offences.add(path + " references " + forbidden);
            }
        }
        assertTrue(offences.isEmpty(),
                "the desktop layer must not know a Warcraft II installation is a directory. "
                        + "See this class's Javadoc before changing anything: a skip message "
                        + "naming wc2.install.dir is not one of these. Offences: " + offences);

        // A walk that found nothing would agree with a module that is clean.
        // The screens, the launcher and their tests come to seventy-odd files;
        // a handful means the path is wrong and this test is measuring an
        // empty directory.
        assertTrue(sources.size() > 50,
                "only " + sources.size() + " sources were scanned, which is not this module");
    }

    /**
     * The distinction this whole test rests on, made measurable.
     *
     * <p>Every file below tells a developer to point {@code wc2.install.dir} or
     * {@code WC2_INSTALL_DIR} at a game directory, and every one of them is
     * clean. Configuration is a string a human reads; knowing an installation
     * is a directory is a type a compiler binds. If someone ever deletes those
     * strings to make the test above pass, this one goes red and says so.
     */
    @Test
    @DisplayName("naming wc2.install.dir is configuration advice, not a reference")
    void theConfigurationStringsAreNotWhatIsForbidden() {
        List<Path> mentioning = new ArrayList<>();
        for (Path path : sources()) {
            String text = read(path);
            if (CONFIGURATION.stream().anyMatch(text::contains)) {
                mentioning.add(path);
            }
        }
        // Measured: every test in this module that reads real 1995 data says
        // this in its skip message, and both Main and NetworkPeer print it to
        // a player who has configured nothing. A floor rather than the exact
        // count, so that adding a test does not fail this.
        assertTrue(mentioning.size() >= 30,
                "only " + mentioning.size() + " sources still tell a developer how to configure "
                        + "an installation; scripts/ci/check-test-skips.py greps for that "
                        + "wording and the launcher's help depends on it");

        List<String> offences = new ArrayList<>();
        for (Path path : mentioning) {
            for (String forbidden : offencesIn(path)) {
                offences.add(path + " references " + forbidden);
            }
        }
        assertTrue(offences.isEmpty(),
                "a file that names the configuration also references a forbidden type, so the "
                        + "two are not as separable as this test claims: " + offences);
    }

    /** Which forbidden types a source file actually references. */
    private static List<String> offencesIn(Path path) {
        String code = withoutComments(read(path));
        List<String> found = new ArrayList<>();
        for (String forbidden : FORBIDDEN) {
            if (referencesIdentifier(code, forbidden)) {
                found.add(forbidden);
            }
        }
        return found;
    }

    /** Every {@code .java} file in this module, main and test, except this one. */
    private static List<Path> sources() {
        Path root = Path.of("src");
        if (!Files.isDirectory(root)) {
            fail("cannot find this module's sources at " + root.toAbsolutePath()
                    + "; this test has to run from the module directory");
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> path.toString().endsWith(".java"))
                    // This file has to name the types it forbids, in code, to
                    // forbid them. It is the one source allowed to.
                    .filter(path -> !path.endsWith("NoInstallDirectoryTest.java"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Whether {@code name} appears as a whole Java identifier.
     *
     * <p>Bounded on both sides so that a longer name containing this one is
     * not a hit: {@code CdImage} must not be found inside a hypothetical
     * {@code CdImageReader}.
     *
     * <p>A dot is deliberately <em>not</em> an identifier character here. It
     * has to be a boundary, or the two forms that matter most would both slip
     * through -- a fully qualified {@code data.archive.WarArchive} in an import,
     * and a nested {@code Warcraft2Install.Archive} -- because in each of those
     * the interesting name is preceded or followed by a dot. Treating a dot as
     * part of the identifier made this check silently find nothing, which is
     * the worst thing an enforcement test can do.
     */
    private static boolean referencesIdentifier(String code, String name) {
        int at = code.indexOf(name);
        while (at >= 0) {
            boolean beforeOk = at == 0 || !isIdentifierPart(code.charAt(at - 1));
            int after = at + name.length();
            boolean afterOk = after >= code.length() || !isIdentifierPart(code.charAt(after));
            if (beforeOk && afterOk) {
                return true;
            }
            at = code.indexOf(name, at + 1);
        }
        return false;
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isJavaIdentifierPart(c);
    }

    /**
     * The source with its comments removed and its string literals left in.
     *
     * <p>Comments go because a comment naming a type is prose, and the rule
     * this class enforces has to be explainable in the files it governs.
     * Literals stay because {@code Class.forName} of a forbidden type is
     * exactly the sort of way round a rule that a check like this is for.
     *
     * <p>Written out by hand rather than with a regular expression: a
     * {@code //} inside a string literal and a {@code "} inside a comment both
     * break the naive version, and Java text blocks -- which the launcher's
     * help text is one of -- break it again.
     */
    private static String withoutComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int at = 0;
        int end = source.length();
        while (at < end) {
            char c = source.charAt(at);
            if (c == '/' && source.startsWith("//", at)) {
                while (at < end && source.charAt(at) != '\n') {
                    at++;
                }
            } else if (c == '/' && source.startsWith("/*", at)) {
                int close = source.indexOf("*/", at + 2);
                at = close < 0 ? end : close + 2;
            } else if (source.startsWith("\"\"\"", at)) {
                int close = source.indexOf("\"\"\"", at + 3);
                int stop = close < 0 ? end : close + 3;
                out.append(source, at, stop);
                at = stop;
            } else if (c == '"' || c == '\'') {
                out.append(c);
                at++;
                while (at < end && source.charAt(at) != c) {
                    if (source.charAt(at) == '\\' && at + 1 < end) {
                        out.append(source.charAt(at)).append(source.charAt(at + 1));
                        at += 2;
                        continue;
                    }
                    out.append(source.charAt(at));
                    at++;
                }
                if (at < end) {
                    out.append(source.charAt(at));
                    at++;
                }
            } else {
                out.append(c);
                at++;
            }
        }
        return out.toString();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }
}
