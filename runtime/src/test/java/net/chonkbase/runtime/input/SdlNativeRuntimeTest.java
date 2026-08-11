package net.chonkbase.runtime.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

class SdlNativeRuntimeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void packagedMappingDatabaseIsTheNeutralizedPinnedAsset() throws Exception {
        try (InputStream input = SdlNativeRuntime.class.getResourceAsStream(
                SdlNativeRuntime.PACKAGED_MAPPING_RESOURCE)) {
            assertNotNull(input);
            byte[] payload = input.readAllBytes();
            assertEquals(
                    "8bb357447a7b4cb901b2c5fc6020d8153d99bfc5559955d1461b46dc663445b0",
                    HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(payload)));
            assertTrue(SdlNativeRuntime.hasNonCommentMapping(payload));
        }
    }

    @Test
    void mappingProbeRejectsBlankAndCommentOnlyPayloads() {
        assertFalse(SdlNativeRuntime.hasNonCommentMapping(new byte[0]));
        assertFalse(SdlNativeRuntime.hasNonCommentMapping(
                "# comment\n\n  # another".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertTrue(SdlNativeRuntime.hasNonCommentMapping(
                "# header\n0300,pad,a:b0".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void packagedRuntimeNeverFallsThroughToDeveloperOrMachineGlobalPaths() throws Exception {
        Path contents = temporaryDirectory.resolve("Seven Days.app/Contents");
        Path app = Files.createDirectories(contents.resolve("app"));
        Path frameworks = Files.createDirectories(contents.resolve("Frameworks"));
        Path macOs = Files.createDirectories(contents.resolve("MacOS"));
        Path javaHome = Files.createDirectories(contents.resolve("runtime/Contents/Home"));
        Path working = Files.createDirectories(temporaryDirectory.resolve("developer-worktree"));
        Files.createDirectories(working.resolve("game/target/packaging-input"));

        List<File> candidates = SdlNativeRuntime.candidateLibraryDirectories(
                null,
                true,
                app.toString(),
                javaHome.toString(),
                working.toString(),
                "Mac OS X");

        assertEquals(List.of(app.toFile().getCanonicalFile()), candidates);
        assertFalse(candidates.contains(working.toFile().getCanonicalFile()));
        assertFalse(candidates.contains(frameworks.toFile().getCanonicalFile()));
        assertFalse(candidates.contains(macOs.toFile().getCanonicalFile()));
    }

    @Test
    void packagedWindowsRuntimeUsesOnlyExplicitAbsoluteAppDirectory() throws Exception {
        Path app = Files.createDirectories(temporaryDirectory.resolve("image/app"));
        Path override = Files.createDirectories(temporaryDirectory.resolve("tester-native"));
        Path javaHome = Files.createDirectories(temporaryDirectory.resolve("image/runtime"));
        Path working = Files.createDirectories(temporaryDirectory.resolve("developer-worktree"));
        Files.createDirectories(working.resolve("game/target/packaging-input"));

        List<File> candidates = SdlNativeRuntime.candidateLibraryDirectories(
                override.toString(),
                true,
                app.toString(),
                javaHome.toString(),
                working.toString(),
                "Windows 11");

        assertEquals(List.of(app.toFile().getCanonicalFile()), candidates);
        assertFalse(candidates.contains(override.toFile().getCanonicalFile()));
        assertFalse(candidates.contains(working.toFile().getCanonicalFile()));
    }

    @Test
    void packagedWindowsRuntimeRequiresBothExternalX64NativeNames() throws Exception {
        Path app = Files.createDirectories(temporaryDirectory.resolve("windows-image/app"));
        List<File> candidates = List.of(app.toFile().getCanonicalFile());

        IllegalStateException missingSdl = assertThrows(
                IllegalStateException.class,
                () -> SdlNativeRuntime.validatePackagedNativeLayout(
                        candidates,
                        "Windows 11"));
        assertTrue(missingSdl.getMessage().contains("SDL2.dll"));

        Files.write(app.resolve("SDL2.dll"), new byte[] {1});
        IllegalStateException missingJna = assertThrows(
                IllegalStateException.class,
                () -> SdlNativeRuntime.validatePackagedNativeLayout(
                        candidates,
                        "Windows 11"));
        assertTrue(missingJna.getMessage().contains("jnidispatch.dll"));

        Files.write(app.resolve("jnidispatch.dll"), new byte[] {2});
        SdlNativeRuntime.validatePackagedNativeLayout(candidates, "Windows 11");
        assertEquals(
                List.of("SDL2.dll", "jnidispatch.dll"),
                SdlNativeRuntime.requiredPackagedNativeNames("Windows 11"));
    }

    @Test
    void packagedRuntimeRejectsRelativeOrMissingConfiguredDirectory() {
        List<File> candidates = SdlNativeRuntime.candidateLibraryDirectories(
                "/tmp/ignored-override",
                true,
                "relative/app",
                "/runtime",
                "/work",
                "Windows 11");

        assertTrue(candidates.isEmpty());
        assertThrows(
                IllegalStateException.class,
                () -> SdlNativeRuntime.validatePackagedNativeLayout(
                        candidates,
                        "Windows 11"));
    }

    @Test
    void packagedRuntimeRejectsNativeSymlinkEscapingAppDirectory() throws Exception {
        Path app = Files.createDirectories(temporaryDirectory.resolve("symlink-image/app"));
        Path external = Files.write(
                temporaryDirectory.resolve("external-SDL2.dll"),
                new byte[] {1});
        try {
            Files.createSymbolicLink(app.resolve("SDL2.dll"), external);
        } catch (IOException | UnsupportedOperationException unavailable) {
            Assumptions.assumeTrue(
                    false,
                    "Host cannot create a symlink for the packaged-native test: "
                            + unavailable);
        }
        Files.write(app.resolve("jnidispatch.dll"), new byte[] {2});

        IllegalStateException rejected = assertThrows(
                IllegalStateException.class,
                () -> SdlNativeRuntime.validatePackagedNativeLayout(
                        List.of(app.toFile().getCanonicalFile()),
                        "Windows 11"));
        assertTrue(rejected.getMessage().contains("SDL2.dll"));
    }

    @Test
    void packagedPropertyRequiresAnExplicitAffirmativeValue() {
        assertFalse(SdlNativeRuntime.propertyEnabled(null));
        assertFalse(SdlNativeRuntime.propertyEnabled(""));
        assertFalse(SdlNativeRuntime.propertyEnabled("false"));
        assertFalse(SdlNativeRuntime.propertyEnabled("0"));
        assertTrue(SdlNativeRuntime.propertyEnabled("true"));
        assertTrue(SdlNativeRuntime.propertyEnabled("1"));
        assertTrue(SdlNativeRuntime.propertyEnabled("on"));
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void packagedJnaPropertiesReplaceExternalSearchAndDisableFallback() throws Exception {
        Path app = Files.createDirectories(temporaryDirectory.resolve("sealed-image/app"));
        String oldBoot = System.getProperty(SdlNativeRuntime.JNA_BOOT_LIBRARY_PATH_PROPERTY);
        String oldLibrary = System.getProperty(SdlNativeRuntime.JNA_LIBRARY_PATH_PROPERTY);
        String oldUnpack = System.getProperty(SdlNativeRuntime.JNA_NO_UNPACK_PROPERTY);
        String oldSystem = System.getProperty(SdlNativeRuntime.JNA_NO_SYSTEM_PROPERTY);
        try {
            System.setProperty(
                    SdlNativeRuntime.JNA_BOOT_LIBRARY_PATH_PROPERTY,
                    "/attacker/boot");
            System.setProperty(
                    SdlNativeRuntime.JNA_LIBRARY_PATH_PROPERTY,
                    "/attacker/library");
            System.setProperty(SdlNativeRuntime.JNA_NO_UNPACK_PROPERTY, "false");
            System.setProperty(SdlNativeRuntime.JNA_NO_SYSTEM_PROPERTY, "false");

            SdlNativeRuntime.applyPackagedJnaProperties(
                    app.toFile().getCanonicalFile());

            String expected = app.toFile().getCanonicalPath();
            assertEquals(
                    expected,
                    System.getProperty(
                            SdlNativeRuntime.JNA_BOOT_LIBRARY_PATH_PROPERTY));
            assertEquals(
                    expected,
                    System.getProperty(
                            SdlNativeRuntime.JNA_LIBRARY_PATH_PROPERTY));
            assertEquals(
                    "true",
                    System.getProperty(SdlNativeRuntime.JNA_NO_UNPACK_PROPERTY));
            assertEquals(
                    "true",
                    System.getProperty(SdlNativeRuntime.JNA_NO_SYSTEM_PROPERTY));
        } finally {
            restoreProperty(SdlNativeRuntime.JNA_BOOT_LIBRARY_PATH_PROPERTY, oldBoot);
            restoreProperty(SdlNativeRuntime.JNA_LIBRARY_PATH_PROPERTY, oldLibrary);
            restoreProperty(SdlNativeRuntime.JNA_NO_UNPACK_PROPERTY, oldUnpack);
            restoreProperty(SdlNativeRuntime.JNA_NO_SYSTEM_PROPERTY, oldSystem);
        }
    }

    @Test
    void unpackagedRuntimeRetainsExplicitDeveloperProbeOrder() throws Exception {
        Path javaHome = Files.createDirectories(temporaryDirectory.resolve("jdk/Home"));
        Path working = Files.createDirectories(temporaryDirectory.resolve("worktree"));
        Path gameInput = Files.createDirectories(working.resolve("game/target/packaging-input"));
        Path rootInput = Files.createDirectories(working.resolve("target/packaging-input"));

        List<File> candidates = SdlNativeRuntime.candidateLibraryDirectories(
                null,
                javaHome.toString(),
                working.toString(),
                "Test OS");

        assertEquals(
                List.of(
                        gameInput.toFile().getCanonicalFile(),
                        rootInput.toFile().getCanonicalFile(),
                        working.toFile().getCanonicalFile()),
                candidates);
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
