package net.chonkbase.runtime.input;

import com.sun.jna.NativeLibrary;
import io.github.libsdl4j.api.Sdl;
import io.github.libsdl4j.api.SdlSubSystemConst;
import io.github.libsdl4j.api.error.SdlError;
import io.github.libsdl4j.api.gamecontroller.SdlGamecontroller;
import io.github.libsdl4j.api.hints.SdlHints;
import io.github.libsdl4j.api.hints.SdlHintsConst;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Configures and initializes SDL2 without making native availability fatal. */
final class SdlNativeRuntime {
    static final String SDL_LIBRARY_PATH_PROPERTY = "seven.sdl.library.path";
    static final String SDL_LIBRARY_PATH_ENV = "SEVEN_SDL_LIBRARY_PATH";
    static final String USER_MAPPING_PATH_PROPERTY = "seven.sdl.controller.mappings";
    static final String USER_MAPPING_PATH_ENV = "SEVEN_SDL_CONTROLLER_MAPPINGS";
    static final String PACKAGED_MAPPING_RESOURCE = "/controllers/gamecontrollerdb.txt";
    static final String PACKAGED_RUNTIME_PROPERTY = "seven.packaged";
    static final String PACKAGED_APP_DIRECTORY_PROPERTY = "seven.packaged.app.dir";
    static final String JNA_LIBRARY_PATH_PROPERTY = "jna.library.path";
    static final String JNA_BOOT_LIBRARY_PATH_PROPERTY = "jna.boot.library.path";
    static final String JNA_NO_UNPACK_PROPERTY = "jna.nounpack";
    static final String JNA_NO_SYSTEM_PROPERTY = "jna.nosys";

    private static boolean initialized;
    private static String diagnostic = "not initialized";

    private SdlNativeRuntime() {}

    static synchronized void init() {
        if (initialized) {
            return;
        }
        String osName = System.getProperty("os.name", "");
        String javaHome = System.getProperty("java.home");
        boolean packagedRuntime =
                propertyEnabled(System.getProperty(PACKAGED_RUNTIME_PROPERTY));
        String packagedAppDirectory =
                System.getProperty(PACKAGED_APP_DIRECTORY_PROPERTY);
        List<File> searchPaths = configureSdlSearchPaths(
                firstNonBlank(
                        System.getProperty(SDL_LIBRARY_PATH_PROPERTY),
                        System.getenv(SDL_LIBRARY_PATH_ENV)),
                packagedRuntime,
                packagedAppDirectory,
                javaHome,
                System.getProperty("user.dir"),
                osName);
        if (!packagedRuntime) {
            warnForIncompletePackagedLayout(osName, javaHome);
        }
        setControllerHints();
        int result = Sdl.SDL_Init(
                SdlSubSystemConst.SDL_INIT_GAMECONTROLLER
                        | SdlSubSystemConst.SDL_INIT_EVENTS);
        if (result != 0) {
            throw new IllegalStateException(
                    "Unable to initialize SDL2 controller subsystem: " + lastSdlError());
        }
        int mappings = loadControllerMappings();
        diagnostic = "SDL2 ready; mappings=" + mappings + " nativeSearch=" + searchPaths;
        initialized = true;
        System.out.println("[SevenDays][controller] " + diagnostic);
    }

    static String diagnostic() {
        return diagnostic;
    }

    static List<File> configureSdlSearchPaths(
            String override,
            String javaHome,
            String userDirectory,
            String osName) {
        return configureSdlSearchPaths(
                override,
                false,
                null,
                javaHome,
                userDirectory,
                osName);
    }

    static List<File> configureSdlSearchPaths(
            String override,
            boolean packagedRuntime,
            String packagedAppDirectory,
            String javaHome,
            String userDirectory,
            String osName) {
        List<File> candidates =
                candidateLibraryDirectories(
                        override,
                        packagedRuntime,
                        packagedAppDirectory,
                        javaHome,
                        userDirectory,
                        osName);
        if (packagedRuntime) {
            validatePackagedNativeLayout(candidates, osName);
        }
        configurePackagedJna(candidates, osName, packagedRuntime);
        for (File directory : candidates) {
            NativeLibrary.addSearchPath("SDL2", directory.getAbsolutePath());
        }
        if (!packagedRuntime) {
            prependPathProperty(JNA_LIBRARY_PATH_PROPERTY, candidates);
        }
        return candidates;
    }

    static List<File> candidateLibraryDirectories(
            String override,
            String javaHome,
            String userDirectory,
            String osName) {
        return candidateLibraryDirectories(
                override,
                false,
                null,
                javaHome,
                userDirectory,
                osName);
    }

    static List<File> candidateLibraryDirectories(
            String override,
            boolean packagedRuntime,
            String packagedAppDirectory,
            String javaHome,
            String userDirectory,
            String osName) {
        LinkedHashSet<File> directories = new LinkedHashSet<>();
        if (packagedRuntime) {
            addPackagedDirectory(directories, packagedAppDirectory);
            return List.copyOf(directories);
        }

        addOverridePaths(directories, override);

        File javaHomeDirectory = javaHome == null ? null : new File(javaHome);
        File inferredMacAppDirectory = appContentsChild(javaHomeDirectory, "app");
        addIfDirectory(directories, inferredMacAppDirectory);
        addIfDirectory(directories, appContentsChild(javaHomeDirectory, "Frameworks"));
        addIfDirectory(directories, appContentsChild(javaHomeDirectory, "MacOS"));

        // A packaged app must be self-contained. Do not let a missing or
        // unloadable bundled native silently fall through to a developer's
        // working tree, Homebrew installation, or system-wide framework.
        if (inferredMacAppDirectory != null && inferredMacAppDirectory.isDirectory()) {
            return List.copyOf(directories);
        }

        File workingDirectory =
                userDirectory == null ? null : new File(userDirectory);
        if (workingDirectory != null) {
            addIfDirectory(
                    directories,
                    new File(workingDirectory, "game/target/packaging-input"));
            addIfDirectory(
                    directories,
                    new File(workingDirectory, "target/packaging-input"));
            addIfDirectory(directories, workingDirectory);
        }

        String operatingSystem =
                osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (operatingSystem.contains("mac") || operatingSystem.contains("darwin")) {
            addIfDirectory(directories, new File("/opt/homebrew/lib"));
            addIfDirectory(directories, new File("/usr/local/lib"));
            addIfDirectory(directories, new File("/Library/Frameworks/SDL2.framework"));
        }
        return List.copyOf(directories);
    }

    static void validatePackagedNativeLayout(
            List<File> candidates,
            String osName) {
        if (candidates.size() != 1) {
            throw new IllegalStateException(
                    "Packaged controller runtime requires one absolute "
                            + "-D"
                            + PACKAGED_APP_DIRECTORY_PROPERTY
                            + " directory");
        }
        File appDirectory = candidates.getFirst();
        for (String nativeName : requiredPackagedNativeNames(osName)) {
            Path nativePath = appDirectory.toPath().resolve(nativeName);
            if (!Files.isRegularFile(nativePath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException(
                        "Packaged controller runtime is missing "
                                + nativeName
                                + " under "
                                + appDirectory);
            }
        }
    }

    static List<String> requiredPackagedNativeNames(String osName) {
        String operatingSystem =
                osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (operatingSystem.contains("mac") || operatingSystem.contains("darwin")) {
            return List.of("libSDL2-2.0.0.dylib", "libjnidispatch.jnilib");
        }
        if (operatingSystem.contains("win")) {
            return List.of("SDL2.dll", "jnidispatch.dll");
        }
        if (operatingSystem.contains("nux") || operatingSystem.contains("nix")) {
            return List.of("libSDL2.so", "libjnidispatch.so");
        }
        throw new IllegalStateException(
                "Packaged controller runtime does not support operating system '"
                        + osName
                        + "'");
    }

    static boolean propertyEnabled(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim();
        return normalized.equalsIgnoreCase("true")
                || normalized.equals("1")
                || normalized.equalsIgnoreCase("yes")
                || normalized.equalsIgnoreCase("on");
    }

    static boolean hasNonCommentMapping(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return false;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(payload),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    return true;
                }
            }
            return false;
        } catch (Exception malformed) {
            return true;
        }
    }

    static String lastSdlError() {
        String error = SdlError.SDL_GetError();
        return error == null || error.isBlank() ? "<no SDL error>" : error;
    }

    private static void setControllerHints() {
        SdlHints.SDL_SetHint(SdlHintsConst.SDL_HINT_APP_NAME, "Seven Days to Tomorrow");
        SdlHints.SDL_SetHint(
                SdlHintsConst.SDL_HINT_GAMECONTROLLER_USE_BUTTON_LABELS,
                "1");
        SdlHints.SDL_SetHint(SdlHintsConst.SDL_HINT_ENABLE_STEAM_CONTROLLERS, "1");
        SdlHints.SDL_SetHint(
                SdlHintsConst.SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS,
                "0");
        SdlHints.SDL_SetHint(SdlHintsConst.SDL_HINT_JOYSTICK_RAWINPUT, "0");
        SdlHints.SDL_SetHint(SdlHintsConst.SDL_HINT_NO_SIGNAL_HANDLERS, "1");
        SdlHints.SDL_SetHint(SdlHintsConst.SDL_HINT_JOYSTICK_THREAD, "1");
    }

    private static int loadControllerMappings() {
        int loaded = 0;
        File packaged = extractPackagedMappings();
        if (packaged != null) {
            loaded += Math.max(
                    0,
                    SdlGamecontroller.SDL_GameControllerAddMappingsFromFile(
                            packaged.getAbsolutePath()));
        }
        for (File mapping : userMappingFiles()) {
            int result = SdlGamecontroller.SDL_GameControllerAddMappingsFromFile(
                    mapping.getAbsolutePath());
            if (result < 0) {
                System.err.println(
                        "[SevenDays][controller] mapping load failed "
                                + mapping
                                + ": "
                                + lastSdlError());
            } else {
                loaded += result;
            }
        }
        return loaded;
    }

    private static File extractPackagedMappings() {
        try (InputStream input =
                SdlNativeRuntime.class.getResourceAsStream(PACKAGED_MAPPING_RESOURCE)) {
            if (input == null) {
                return null;
            }
            byte[] payload = input.readAllBytes();
            if (!hasNonCommentMapping(payload)) {
                return null;
            }
            String user = System.getProperty("user.name", "anonymous")
                    .replaceAll("[^A-Za-z0-9_.-]", "_");
            Path directory = Path.of(
                    System.getProperty("java.io.tmpdir"),
                    "seven-days-sdl-" + user);
            Files.createDirectories(directory);
            Path output = directory.resolve("gamecontrollerdb-4d6648a1.txt");
            if (!Files.isRegularFile(output)
                    || Files.size(output) != payload.length) {
                Files.write(output, payload);
            }
            return output.toFile();
        } catch (Exception failure) {
            System.err.println(
                    "[SevenDays][controller] packaged mapping staging failed: "
                            + failure.getMessage());
            return null;
        }
    }

    private static List<File> userMappingFiles() {
        String raw = firstNonBlank(
                System.getProperty(USER_MAPPING_PATH_PROPERTY),
                System.getenv(USER_MAPPING_PATH_ENV));
        if (raw == null) {
            return List.of();
        }
        List<File> files = new ArrayList<>();
        for (String part : raw.split(File.pathSeparator)) {
            if (part == null || part.isBlank()) {
                continue;
            }
            File file = new File(part.trim());
            if (file.isFile()) {
                files.add(file);
            } else {
                System.err.println(
                        "[SevenDays][controller] mapping file not found: " + file);
            }
        }
        return List.copyOf(files);
    }

    private static void configurePackagedJna(
            List<File> candidates,
            String osName,
            boolean packagedRuntime) {
        List<String> jnaNames = packagedRuntime
                ? List.of(requiredPackagedNativeNames(osName).get(1))
                : List.of(
                        "libjnidispatch.jnilib",
                        "jnidispatch.dll",
                        "libjnidispatch.so");
        for (File directory : candidates) {
            for (String jnaName : jnaNames) {
                File jnaNative = new File(directory, jnaName);
                if (!jnaNative.isFile()) {
                    continue;
                }
                if (packagedRuntime) {
                    applyPackagedJnaProperties(directory);
                } else {
                    prependPathProperty(
                            JNA_BOOT_LIBRARY_PATH_PROPERTY,
                            List.of(directory));
                    System.setProperty(JNA_NO_UNPACK_PROPERTY, "true");
                }
                return;
            }
        }
    }

    static void applyPackagedJnaProperties(File directory) {
        String exactDirectory = directory.getAbsolutePath();
        System.setProperty(JNA_BOOT_LIBRARY_PATH_PROPERTY, exactDirectory);
        System.setProperty(JNA_LIBRARY_PATH_PROPERTY, exactDirectory);
        System.setProperty(JNA_NO_UNPACK_PROPERTY, "true");
        System.setProperty(JNA_NO_SYSTEM_PROPERTY, "true");
    }

    private static void prependPathProperty(String property, List<File> directories) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (File directory : directories) {
            paths.add(directory.getAbsolutePath());
        }
        String existing = System.getProperty(property);
        if (existing != null && !existing.isBlank()) {
            for (String part : existing.split(File.pathSeparator)) {
                if (!part.isBlank()) {
                    paths.add(part);
                }
            }
        }
        if (!paths.isEmpty()) {
            System.setProperty(property, String.join(File.pathSeparator, paths));
        }
    }

    private static void warnForIncompletePackagedLayout(String osName, String javaHome) {
        String operatingSystem =
                osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if ((!operatingSystem.contains("mac") && !operatingSystem.contains("darwin"))
                || javaHome == null) {
            return;
        }
        File appDirectory = appContentsChild(new File(javaHome), "app");
        if (appDirectory == null) {
            return;
        }
        File sdl = new File(appDirectory, "libSDL2-2.0.0.dylib");
        File jna = new File(appDirectory, "libjnidispatch.jnilib");
        if (!sdl.isFile() || !jna.isFile()) {
            System.err.println(
                    "[SevenDays][controller][WARNING] packaged macOS layout is missing "
                            + "signed SDL2/JNA natives under "
                            + appDirectory
                            + "; do not ship this bundle.");
        }
    }

    private static void addOverridePaths(Set<File> directories, String override) {
        if (override == null || override.isBlank()) {
            return;
        }
        for (String part : override.split(File.pathSeparator)) {
            if (part == null || part.isBlank()) {
                continue;
            }
            File path = new File(part.trim());
            addIfDirectory(directories, path.isFile() ? path.getParentFile() : path);
        }
    }

    private static void addPackagedDirectory(
            Set<File> directories,
            String configuredDirectory) {
        if (configuredDirectory == null || configuredDirectory.isBlank()) {
            return;
        }
        Path configured = Path.of(configuredDirectory.trim());
        if (!configured.isAbsolute()) {
            return;
        }
        addIfDirectory(directories, configured.toFile());
    }

    private static File appContentsChild(File javaHome, String child) {
        if (javaHome == null) {
            return null;
        }
        Path path = javaHome.toPath().toAbsolutePath().normalize();
        for (int index = 0; index < path.getNameCount(); index++) {
            if ("Contents".equals(path.getName(index).toString())
                    && index > 0
                    && path.getName(index - 1).toString().endsWith(".app")) {
                Path contents = path.getRoot() == null
                        ? path.subpath(0, index + 1)
                        : path.getRoot().resolve(path.subpath(0, index + 1));
                return contents.resolve(child).toFile();
            }
        }
        return null;
    }

    private static void addIfDirectory(Set<File> directories, File directory) {
        if (directory == null) {
            return;
        }
        try {
            File canonical = directory.getCanonicalFile();
            if (Files.isDirectory(canonical.toPath())) {
                directories.add(canonical);
            }
        } catch (Exception ignored) {
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null || second.isBlank() ? null : second;
    }
}
