package net.chonkbase.chonkcraft.extract;

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
import org.junit.jupiter.api.Test;

/**
 * Fails the build if the extractor ever learns about the game.
 *
 * <p>The separation is the whole design. The extractor turns a 1995
 * installation into a pack; the game reads a pack; the only thing they share
 * is the format. If the extractor can see the engine it will eventually start
 * producing what today's engine happens to want rather than what the format
 * says, and the pack stops being a contract and becomes a private cache.
 *
 * <p>Checked by reading the source rather than the classpath, because a
 * transitive dependency is exactly the way this would happen by accident: an
 * import compiles, nobody notices, and six months later the extractor cannot
 * be built without the game.
 */
class IsolationTest {

    /** Packages the extractor is not allowed to know exist. */
    private static final List<String> FORBIDDEN = List.of(
            "net.chonkbase.chonkcraft.engine",
            "net.chonkbase.chonkcraft.desktop",
            "net.chonkbase.runtime");

    @Test
    void theExtractorCannotSeeTheGame() {
        List<String> offences = new ArrayList<>();
        Path sources = Path.of("src");
        if (!Files.isDirectory(sources)) {
            fail("cannot find the extractor's own sources at " + sources.toAbsolutePath()
                    + "; this test has to run from the module directory");
        }
        try (Stream<Path> walk = Files.walk(sources)) {
            walk.filter(path -> path.toString().endsWith(".java"))
                    // This file has to name the packages it forbids, so it is
                    // the one source in the module that is allowed to.
                    .filter(path -> !path.endsWith("IsolationTest.java"))
                    .forEach(path -> {
                        String text = read(path);
                        for (String forbidden : FORBIDDEN) {
                            if (text.contains(forbidden)) {
                                offences.add(path + " mentions " + forbidden);
                            }
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertTrue(offences.isEmpty(),
                "the extractor must not depend on the game: " + offences);
    }

    @Test
    void thePomDeclaresOnlyTheFormatAndTheFileReaders() {
        String pom = read(Path.of("pom.xml"));
        for (String forbidden : List.of("chonkcraft-engine", "chonkcraft-desktop", "chonk-runtime")) {
            assertTrue(!pom.contains("<artifactId>" + forbidden + "</artifactId>"),
                    "extractor/pom.xml declares a dependency on " + forbidden);
        }
        assertTrue(pom.contains("chonk-assetpack"), "the extractor needs the pack format");
        assertTrue(pom.contains("chonkcraft-data"), "the extractor needs the 1995 file readers");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }
}
