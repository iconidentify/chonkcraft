package net.chonkbase.chonkcraft.launcher;

import java.awt.FileDialog;
import java.awt.Desktop;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/** Entry point for the durable launcher and its headless qualification lanes. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        ApplicationIdentity.initialize();
        try {
            Arguments parsed = Arguments.parse(args);
            if (parsed.home() != null) {
                System.setProperty("chonkcraft.home", parsed.home().toString());
            }
            LauncherHome home = LauncherHome.configured();
            home.create();
            PackLibrary packs = new PackLibrary(home.packs());
            SourceImporter importer = new SourceImporter(home, packs);
            GameReleaseManager releases = new GameReleaseManager(home);

            switch (parsed.command()) {
                case "inspect" -> inspect(importer, parsed.value());
                case "import" -> importSource(home, importer, parsed.value());
                case "render" -> render(parsed.value(), true);
                case "render-first-run" -> render(parsed.value(), false);
                case "render-pack-manager" -> renderPackManager(parsed.value());
                case "render-pack-progress" -> renderPackProgress(parsed.value());
                case "render-release-notes" -> renderReleaseNotes(parsed.value());
                case "update" -> update(home, releases);
                case "launch" -> {
                    installBootstrap(releases);
                    launchConfigured(home, packs, releases);
                }
                case "ui" -> open(home, packs, importer, releases);
                default -> throw new IllegalArgumentException(
                        "unknown launcher command " + parsed.command());
            }
        } catch (Exception e) {
            System.err.println("Launcher error: " + message(e));
            if (!java.awt.GraphicsEnvironment.isHeadless()) {
                LauncherDialogs.error(null, message(e));
            }
            System.exit(1);
        }
    }

    private static void inspect(SourceImporter importer, Path source) throws IOException {
        SourceImporter.Inspection found = importer.inspect(source,
                update -> System.out.println("  " + update.message()));
        System.out.println("Source ready");
        System.out.println("  install: " + found.installation());
        System.out.println("  release: " + (found.battleNet() ? "Battle.net Edition"
                : found.expansion() ? "Tides of Darkness + Beyond the Dark Portal"
                        : "Tides of Darkness"));
        System.out.println("  maps: " + found.maps());
        System.out.println("  music tracks: " + found.musicTracks());
    }

    private static void importSource(LauncherHome home, SourceImporter importer, Path source)
            throws IOException {
        SourceImporter.Result result = importer.importSource(source,
                update -> System.out.println("  " + update.message()
                        + progressSuffix(update)));
        LauncherState state = LauncherState.load(home.stateFile());
        state.selectPack(result.pack().file());
        state.save();
        if (result.report() != null) {
            System.out.println();
            System.out.print(net.chonkbase.chonkcraft.extract.PackBuilder.describe(result.report()));
        }
        System.out.println("Pack: " + result.pack().file());
    }

    private static void update(LauncherHome home, GameReleaseManager releases)
            throws IOException {
        GameReleaseManager.Release release = releases.latest();
        releases.releaseNotes(release);
        if (!releases.isUpdate(release)) {
            System.out.println("Game " + release.version() + " is already installed");
        }
        GameReleaseManager.Installed installed = releases.install(release,
                message -> System.out.println("  " + message));
        LauncherState state = LauncherState.load(home.stateFile());
        state.stageVersion(installed.version());
        state.save();
        System.out.println("Game: " + installed.version());
    }

    private static void launchConfigured(LauncherHome home, PackLibrary packs,
            GameReleaseManager releases) throws IOException, InterruptedException {
        LauncherState state = LauncherState.load(home.stateFile());
        PackLibrary.PackInfo pack = PackLibrary.read(state.selectedPack());
        GameReleaseManager.Installed version = releases.find(state.selectedVersion());
        boolean save = false;
        if (pack == null) {
            List<PackLibrary.PackInfo> installedPacks = packs.scan();
            if (!installedPacks.isEmpty()) {
                pack = installedPacks.getFirst();
                state.selectPack(pack.file());
                save = true;
            }
        }
        if (version == null) {
            List<GameReleaseManager.Installed> installedVersions = releases.installed();
            if (!installedVersions.isEmpty()) {
                version = installedVersions.getFirst();
                state.selectVersion(version.version());
                save = true;
            }
        }
        if (pack == null) {
            throw new IOException("no graphics pack is installed");
        }
        if (version == null) {
            throw new IOException("no verified game is installed");
        }
        if (save) {
            state.save();
        }
        Process game = releases.launch(version, pack.file());
        boolean earlyExit = game.waitFor(15, TimeUnit.SECONDS);
        int status;
        if (earlyExit) {
            status = game.exitValue();
            if (status == 0) {
                state.confirmVersion(version.version());
                releases.retainNewest(3, List.of(state.selectedVersion()));
            } else {
                state.rollbackPending(version.version());
            }
            state.save();
        } else {
            state.confirmVersion(version.version());
            state.save();
            releases.retainNewest(3, List.of(state.selectedVersion()));
            status = game.waitFor();
        }
        if (status != 0) {
            throw new IOException("the game exited with status " + status);
        }
        checkForUpdateAfterExit(releases, state);
    }

    /** A non-UI launch gets the same post-game current-channel check as the windowed launcher. */
    static void checkForUpdateAfterExit(GameReleaseManager releases, LauncherState state) {
        if (!Boolean.parseBoolean(System.getProperty("chonkcraft.update.check", "true"))) {
            return;
        }
        try {
            GameReleaseManager.Release release = releases.latest();
            releases.releaseNotes(release);
            if (!releases.isUpdate(release)) {
                return;
            }
            GameReleaseManager.Installed installed = releases.install(release, null);
            state.stageVersion(installed.version());
            state.save();
            System.out.println("Game update ready: " + installed.version());
        } catch (IOException unavailable) {
            System.err.println("Post-game update check unavailable: " + message(unavailable));
        }
    }

    private static void open(LauncherHome home, PackLibrary packs,
            SourceImporter importer, GameReleaseManager releases) {
        installBootstrap(releases);
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame(ApplicationIdentity.NAME);
            ApplicationIdentity.install(frame);
            Application application = new Application(frame, home, packs, importer, releases);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setContentPane(application.view);
            frame.pack();
            frame.setMinimumSize(application.view.getMinimumSize());
            frame.setResizable(false);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

    private static void installBootstrap(GameReleaseManager releases) {
        String game = System.getProperty("chonkcraft.bootstrap.game", "");
        String version = System.getProperty("chonkcraft.bootstrap.version", "");
        if (game.isBlank()) {
            Path application = applicationDirectory();
            Path bundledGame = application.resolve("bootstrap-game.jar");
            if (Files.isRegularFile(bundledGame)) {
                game = bundledGame.toString();
                Path versionFile = application.resolve("bootstrap-version.txt");
                if (version.isBlank() && Files.isRegularFile(versionFile)) {
                    try {
                        version = Files.readString(versionFile).trim();
                    } catch (IOException e) {
                        version = "";
                    }
                }
            }
        }
        if (game.isBlank()) {
            return;
        }
        if (version.isBlank()) {
            version = "development";
        }
        try {
            releases.installBootstrap(version, Path.of(game), null);
        } catch (IOException e) {
            System.err.println("Bundled game was not installed: " + message(e));
        }
    }

    private static Path applicationDirectory() {
        try {
            return Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath().getParent();
        } catch (Exception e) {
            return Path.of("").toAbsolutePath();
        }
    }

    /** Renders the real launcher panel without a display, for visual regression checks. */
    private static void render(Path destination, boolean withPack) throws IOException {
        LauncherView view = new LauncherView(new NoActions());
        if (withPack) {
            view.setPacks(List.of(new PackLibrary.PackInfo(
                    Path.of("/preview/original.chonkpack"),
                    "wc2-bne",
                    "Warcraft II Battle.net Edition",
                    "preview", true, true, 1412)), null);
        } else {
            view.setPacks(List.of(), null);
        }
        view.setCurrentGame(new GameReleaseManager.Installed(
                "0.1.1-beta1", Path.of("/preview/game.jar")));
        view.setReleaseNotes(previewReleaseNotes(), true);
        view.setStatus(withPack ? "All files are ready"
                : "Choose how to prepare your original Warcraft II media");
        view.setSize(620, 660);
        view.doLayout();
        layoutTree(view);
        BufferedImage image = new BufferedImage(620, 660, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        view.printAll(graphics);
        graphics.dispose();
        Path parent = destination.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ImageIO.write(image, "png", destination.toFile());
        System.out.println(destination.toAbsolutePath());
    }

    /** Renders the real pack manager during a measured Japanese-disc import. */
    private static void renderPackManager(Path destination) throws IOException {
        PackManagerView view = new PackManagerView(new NoPackActions());
        PackLibrary.PackInfo battleNet = new PackLibrary.PackInfo(
                Path.of("/preview/battle-net.chonkpack"), "wc2-bne",
                "Warcraft II Battle.net Edition", "disc", true, true, 1412,
                "Battle.net Edition 2.02b", "BIN/CUE", "Warcraft II BNE.cue",
                "5d9f87c145827f93", 691_503_104L, "2026-08-09T10:00:00Z",
                338_690_048L, 80, 18);
        PackLibrary.PackInfo expansion = new PackLibrary.PackInfo(
                Path.of("/preview/macintosh.chonkpack"), "wc2-expansion",
                "Warcraft II: Tides of Darkness and Beyond the Dark Portal",
                "disc", true, false, 1355,
                "Tides of Darkness + Beyond the Dark Portal", "Toast",
                "Warcraft II.toast", "8b2e6921db113244", 640_000_000L,
                "2026-08-08T12:00:00Z", 310_000_000L, 52, 14);
        view.setPacks(List.of(battleNet, expansion), expansion.file());
        renderPackView(view, destination);
    }

    /** Renders the real pack manager during a measured Japanese-disc import. */
    private static void renderPackProgress(Path destination) throws IOException {
        PackManagerView view = new PackManagerView(new NoPackActions());
        view.setPacks(List.of(), null);
        view.setBusy(true, "Preparing Warcraft2_J_bin-cue.zip");
        view.setProgress(new SourceImporter.ProgressUpdate(
                "Unpacking Warcraft2_J_bin-cue.zip", 14,
                377_487_360L, 620_152_617L, SourceImporter.Unit.BYTES));
        renderPackView(view, destination);
    }

    private static void renderPackView(PackManagerView view, Path destination)
            throws IOException {
        int width = Integer.getInteger("chonkcraft.render.width", 960);
        int height = Integer.getInteger("chonkcraft.render.height", 720);
        view.setSize(width, height);
        view.doLayout();
        layoutTree(view);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        view.printAll(graphics);
        graphics.dispose();
        Path parent = destination.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ImageIO.write(image, "png", destination.toFile());
        System.out.println(destination.toAbsolutePath());
    }

    /** Renders the real release-history surface for visual qualification. */
    private static void renderReleaseNotes(Path destination) throws IOException {
        ReleaseNotesView view = new ReleaseNotesView(() -> { });
        view.setHistory(previewReleaseNotes());
        view.setSize(760, 620);
        view.doLayout();
        layoutTree(view);
        BufferedImage image = new BufferedImage(760, 620, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        view.printAll(graphics);
        graphics.dispose();
        Path parent = destination.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ImageIO.write(image, "png", destination.toFile());
        System.out.println(destination.toAbsolutePath());
    }

    private static ReleaseNotesCatalog.History previewReleaseNotes() {
        return new ReleaseNotesCatalog.History(List.of(
                new ReleaseNotesCatalog.Entry("2026.0810.15",
                        "2026-08-10T19:20:00Z", "Oil harvesting feels natural",
                        "- Tankers now approach, board, unload, and return with retail timing.\n"
                                + "- Oil platforms correctly coordinate nearby tankers.\n"
                                + "- Multiplayer order handling is more resilient.", "preview-a"),
                new ReleaseNotesCatalog.Entry("2026.0809.14",
                        "2026-08-09T21:45:00Z", "Reliable multiplayer starts",
                        "- Direct-IP and online lobbies enforce matching game versions.\n"
                                + "- Joining players now reconstruct the host map consistently.",
                        "preview-b"),
                new ReleaseNotesCatalog.Entry("0.1.1-beta7",
                        "2026-08-08T17:30:00Z", "A simpler launcher",
                        "- Automatic updates keep everyone on the current game code.\n"
                                + "- Graphics-pack management has a clearer cross-platform flow.",
                        "preview-c")));
    }

    private static void layoutTree(java.awt.Container container) {
        container.doLayout();
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof java.awt.Container nested) {
                layoutTree(nested);
            }
        }
    }

    private static String message(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null
                && (current.getMessage() == null || current.getMessage().isBlank())) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    private static String progressSuffix(SourceImporter.ProgressUpdate update) {
        if (!update.measured()) {
            return " [" + update.percent() + "%]";
        }
        String amount = update.unit() == SourceImporter.Unit.BYTES
                ? String.format(java.util.Locale.ENGLISH, "%.1f/%.1f MB",
                        update.completed() / 1_048_576.0,
                        update.total() / 1_048_576.0)
                : String.format(java.util.Locale.ENGLISH, "%,d/%,d",
                        update.completed(), update.total());
        return " [" + update.percent() + "%; " + amount + "]";
    }

    private static final class Application
            implements LauncherView.Actions, PackManagerView.Actions, ReleaseNotesView.Actions {

        private final JFrame frame;
        private final LauncherHome home;
        private final PackLibrary packs;
        private final SourceImporter importer;
        private final GameReleaseManager releases;
        private final LauncherState state;
        private final LauncherView view;
        private final PackManagerView packManager;
        private final JDialog packWindow;
        private final ReleaseNotesView releaseNotesView;
        private final JDialog releaseNotesWindow;
        private ReleaseNotesCatalog.History releaseNotesHistory;
        private final AtomicBoolean updateChecking = new AtomicBoolean();

        private Application(JFrame frame, LauncherHome home, PackLibrary packs,
                SourceImporter importer, GameReleaseManager releases) {
            this.frame = frame;
            this.home = home;
            this.packs = packs;
            this.importer = importer;
            this.releases = releases;
            state = LauncherState.load(home.stateFile());
            view = new LauncherView(this);
            packManager = new PackManagerView(this);
            packWindow = new JDialog(frame, "Graphics Packs — ChonkCraft", true);
            packWindow.setIconImages(ApplicationIdentity.icons());
            packWindow.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
            packWindow.setContentPane(packManager);
            packWindow.pack();
            // Pack names are content, not window geometry. Lock the initial
            // frame so choosing a longer edition cannot make it jump wider.
            packWindow.setMinimumSize(packWindow.getSize());
            packWindow.setResizable(false);
            releaseNotesView = new ReleaseNotesView(this);
            releaseNotesWindow = new JDialog(frame, "Release Notes — ChonkCraft", true);
            releaseNotesWindow.setIconImages(ApplicationIdentity.icons());
            releaseNotesWindow.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
            releaseNotesWindow.setContentPane(releaseNotesView);
            releaseNotesWindow.pack();
            releaseNotesWindow.setResizable(false);
            releaseNotesHistory = releases.cachedReleaseNotes();
            applyReleaseNotes(releaseNotesHistory);
            refresh();
            autoUpdate();
        }

        /**
         * Updates game code in the background. Network failure is intentionally
         * non-fatal: the authenticated bundled or previously installed game
         * remains ready to play and the player's ChonkPack is never touched.
         */
        private void autoUpdate() {
            if (!Boolean.parseBoolean(System.getProperty(
                    "chonkcraft.update.check", "true"))) {
                return;
            }
            if (!updateChecking.compareAndSet(false, true)) {
                return;
            }
            view.setBusy(true, "Checking for the latest game");
            view.setGameStatus("Checking the authenticated current channel…");
            Thread.startVirtualThread(() -> {
                try {
                    GameReleaseManager.Release release = releases.latest();
                    ReleaseNotesCatalog.History history;
                    try {
                        history = releases.releaseNotes(release);
                    } catch (IOException unavailable) {
                        history = releases.cachedReleaseNotes();
                    }
                    if (!releases.isUpdate(release)) {
                        ReleaseNotesCatalog.History available = history;
                        SwingUtilities.invokeLater(() -> {
                            applyReleaseNotes(available);
                            view.setBusy(false, "Ready");
                            view.setGameStatus("Up to date · checked automatically");
                        });
                        return;
                    }
                    GameReleaseManager.Installed installed = releases.install(release,
                            message -> SwingUtilities.invokeLater(
                                    () -> {
                                        view.setBusy(true, message);
                                        view.setGameStatus(message + "…");
                                    }));
                    ReleaseNotesCatalog.History available = history;
                    SwingUtilities.invokeLater(() -> {
                        state.stageVersion(installed.version());
                        try {
                            state.save();
                        } catch (IOException e) {
                            error(e);
                            return;
                        }
                        refresh();
                        applyReleaseNotes(available);
                        view.setBusy(false, "Ready");
                        view.setGameStatus("Up to date · installed automatically");
                    });
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> {
                        view.setBusy(false, "Ready");
                        view.setGameStatus("Update check unavailable");
                    });
                } finally {
                    updateChecking.set(false);
                }
            });
        }

        private void refresh() {
            try {
                List<PackLibrary.PackInfo> installedPacks = packs.scan();
                view.setPacks(installedPacks, state.selectedPack());
                GameReleaseManager.Installed current = releases.find(state.selectedVersion());
                if (current == null) {
                    List<GameReleaseManager.Installed> installed = releases.installed();
                    current = installed.isEmpty() ? null : installed.getFirst();
                    if (current != null) {
                        state.selectVersion(current.version());
                        state.save();
                    }
                }
                view.setCurrentGame(current);
                if (view.selectedPack() != null
                        && !view.selectedPack().file().equals(state.selectedPack())) {
                    selectPack(view.selectedPack());
                }
                packManager.setPacks(installedPacks, state.selectedPack());
            } catch (IOException e) {
                error(e);
            }
        }

        @Override
        public void managePacks() {
            refresh();
            packWindow.setLocationRelativeTo(frame);
            packWindow.setVisible(true);
        }

        @Override
        public void releaseNotes() {
            if (releaseNotesHistory.entries().isEmpty()) {
                return;
            }
            releaseNotesView.setHistory(releaseNotesHistory);
            ReleaseNotesCatalog.Entry latest = releaseNotesHistory.latest();
            if (latest != null) {
                state.markReleaseNotesSeen(latest.version());
                saveChoices();
                view.setReleaseNotes(releaseNotesHistory, false);
            }
            releaseNotesView.showLatest();
            releaseNotesWindow.setLocationRelativeTo(frame);
            releaseNotesWindow.setVisible(true);
        }

        @Override
        public void closeReleaseNotes() {
            releaseNotesWindow.setVisible(false);
        }

        private void applyReleaseNotes(ReleaseNotesCatalog.History history) {
            releaseNotesHistory = history == null
                    ? ReleaseNotesCatalog.History.empty() : history;
            releaseNotesView.setHistory(releaseNotesHistory);
            ReleaseNotesCatalog.Entry latest = releaseNotesHistory.latest();
            view.setReleaseNotes(releaseNotesHistory,
                    latest != null && !state.hasSeenReleaseNotes(latest.version()));
        }

        @Override
        public void chooseFile() {
            Path selected = nativeSource(false);
            if (selected != null) {
                importInBackground(selected);
            }
        }

        @Override
        public void chooseFolder() {
            Path selected = nativeSource(true);
            if (selected != null) {
                importInBackground(selected);
            }
        }

        @Override
        public void importSource(Path source) {
            importInBackground(source);
        }

        private void importInBackground(Path source) {
            run("Preparing " + source.getFileName(),
                    () -> importer.importSource(source,
                            update -> SwingUtilities.invokeLater(() -> {
                                view.setBusy(true, update.message());
                                packManager.setProgress(update);
                            })),
                    result -> {
                        state.selectPack(result.pack().file());
                        try {
                            state.save();
                        } catch (IOException e) {
                            error(e);
                        }
                        refresh();
                        view.setStatus("Graphics pack ready");
                        packManager.setStatus("Ready to use " + result.pack().name());
                    });
        }

        @Override
        public void usePack(PackLibrary.PackInfo pack) {
            if (pack == null) {
                return;
            }
            selectPack(pack);
            refresh();
            packWindow.setVisible(false);
            view.setStatus("Using " + pack.name());
        }

        @Override
        public void exportPack(PackLibrary.PackInfo pack) {
            if (pack == null) {
                return;
            }
            FileDialog chooser = new FileDialog(packWindow,
                    "Export " + pack.name(), FileDialog.SAVE);
            chooser.setFile(pack.file().getFileName().toString());
            chooser.setVisible(true);
            Path destination = selected(chooser);
            if (destination == null) {
                return;
            }
            if (!destination.getFileName().toString().toLowerCase(
                    java.util.Locale.ROOT).endsWith(".chonkpack")) {
                destination = destination.resolveSibling(
                        destination.getFileName() + ".chonkpack");
            }
            boolean replace = Files.exists(destination);
            if (replace && !LauncherDialogs.confirmReplace(packWindow,
                    destination.getFileName().toString())) {
                return;
            }
            Path target = destination;
            run("Exporting " + pack.name(),
                    () -> {
                        packs.export(pack, target, replace);
                        return target;
                    },
                    exported -> packManager.setStatus(
                            "Exported " + exported.getFileName()));
        }

        @Override
        public void deletePack(PackLibrary.PackInfo pack) {
            if (pack == null) {
                return;
            }
            if (!LauncherDialogs.confirmRemoval(packWindow, pack.name())) {
                return;
            }
            run("Removing " + pack.name(),
                    () -> {
                        packs.delete(pack);
                        if (pack.file().equals(state.selectedPack())) {
                            state.selectPack(null);
                            state.save();
                        }
                        return pack.name();
                    },
                    removed -> {
                        refresh();
                        packManager.setStatus("Removed " + removed);
                    });
        }

        @Override
        public void openPackFolder() {
            try {
                home.create();
                openDirectory(home.packs());
            } catch (IOException | UnsupportedOperationException | SecurityException e) {
                error(e);
            }
        }

        private static void openDirectory(Path directory) throws IOException {
            IOException desktopFailure = null;
            try {
                if (Desktop.isDesktopSupported()
                        && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(directory.toFile());
                    return;
                }
            } catch (IOException e) {
                desktopFailure = e;
            }

            String os = System.getProperty("os.name", "")
                    .toLowerCase(java.util.Locale.ROOT);
            List<String> command = os.contains("mac")
                    ? List.of("/usr/bin/open", directory.toString())
                    : os.contains("win")
                            ? List.of("explorer.exe", directory.toString())
                            : List.of("xdg-open", directory.toString());
            try {
                new ProcessBuilder(command).start();
            } catch (IOException fallbackFailure) {
                if (desktopFailure != null) {
                    fallbackFailure.addSuppressed(desktopFailure);
                }
                throw new IOException("the system file browser is unavailable",
                        fallbackFailure);
            }
        }

        @Override
        public void closePackManager() {
            packWindow.setVisible(false);
        }

        @Override
        public void play() {
            PackLibrary.PackInfo pack = view.selectedPack();
            GameReleaseManager.Installed version = view.currentGame();
            try {
                Process game = releases.launch(version, pack == null ? null : pack.file());
                frame.setVisible(false);
                qualify(game, version.version());
            } catch (IOException e) {
                error(e);
            }
        }

        private void selectPack(PackLibrary.PackInfo pack) {
            if (pack == null) {
                return;
            }
            state.selectPack(pack.file());
            saveChoices();
        }

        /** Rolls back only a newly installed build that cannot stay alive. */
        private void qualify(Process game, String version) {
            AtomicBoolean decided = new AtomicBoolean();
            Thread.startVirtualThread(() -> {
                try {
                    Thread.sleep(15_000);
                    if (game.isAlive() && decided.compareAndSet(false, true)) {
                        SwingUtilities.invokeLater(() -> {
                            state.confirmVersion(version);
                            saveChoices();
                            pruneVersions();
                        });
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            game.onExit().thenAccept(finished -> SwingUtilities.invokeLater(() -> {
                if (decided.compareAndSet(false, true)) {
                    if (finished.exitValue() == 0) {
                        state.confirmVersion(version);
                    } else {
                        state.rollbackPending(version);
                    }
                    saveChoices();
                    if (finished.exitValue() == 0) {
                        pruneVersions();
                    }
                    refresh();
                }
                frame.setVisible(true);
                frame.toFront();
                if (finished.exitValue() == 0) {
                    view.setStatus("Game closed · checking for updates");
                    autoUpdate();
                } else {
                    view.setStatus("Game failed to start; restored the previous version");
                }
            }));
        }

        private void pruneVersions() {
            try {
                releases.retainNewest(3, List.of(state.selectedVersion()));
            } catch (IOException e) {
                // Cleanup is opportunistic; it must never make a healthy game unavailable.
            }
        }

        private void saveChoices() {
            try {
                state.save();
            } catch (IOException e) {
                view.setStatus("Choices could not be saved: " + message(e));
            }
        }

        private <T> void run(String started, IoCallable<T> operation,
                java.util.function.Consumer<T> finished) {
            view.setBusy(true, started);
            packManager.setBusy(true, started);
            Thread.startVirtualThread(() -> {
                try {
                    T result = operation.call();
                    SwingUtilities.invokeLater(() -> {
                        view.setBusy(false, "Ready");
                        packManager.setBusy(false, "Ready");
                        finished.accept(result);
                    });
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> {
                        view.setBusy(false, "Ready");
                        packManager.setBusy(false, "Ready");
                        error(e);
                    });
                }
            });
        }

        private void error(Throwable error) {
            view.setStatus(message(error));
            packManager.setStatus(message(error));
            LauncherDialogs.error(packWindow.isVisible() ? packWindow : frame,
                    message(error));
        }

        private Path nativeSource(boolean directory) {
            String key = "apple.awt.fileDialogForDirectories";
            String previous = System.getProperty(key);
            boolean mac = System.getProperty("os.name", "")
                    .toLowerCase(java.util.Locale.ROOT).contains("mac");
            try {
                if (mac) {
                    System.setProperty(key, Boolean.toString(directory));
                }
                FileDialog chooser = new FileDialog(packWindow,
                        directory ? "Choose a Warcraft II folder"
                                : "Choose original game media or a ChonkPack",
                        FileDialog.LOAD);
                chooser.setMultipleMode(false);
                if (!directory) {
                    chooser.setFilenameFilter((folder, name) -> supportedSource(name));
                }
                chooser.setVisible(true);
                return selected(chooser);
            } finally {
                if (mac) {
                    if (previous == null) {
                        System.clearProperty(key);
                    } else {
                        System.setProperty(key, previous);
                    }
                }
            }
        }

        private static boolean supportedSource(String name) {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            return List.of(".zip", ".sit", ".toast", ".iso", ".bin", ".cue",
                    ".img", ".ccd", ".dmg", ".7z", ".rar", ".tar", ".gz",
                    ".exe", ".chonkpack").stream().anyMatch(lower::endsWith);
        }

        private static Path selected(FileDialog chooser) {
            java.io.File[] files = chooser.getFiles();
            if (files.length > 0) {
                return files[0].toPath();
            }
            String name = chooser.getFile();
            String directory = chooser.getDirectory();
            return name == null || directory == null
                    ? null : Path.of(directory, name);
        }
    }

    @FunctionalInterface
    private interface IoCallable<T> {
        T call() throws Exception;
    }

    private record Arguments(String command, Path value, Path home) {

        private static Arguments parse(String[] args) {
            String command = "ui";
            Path value = null;
            Path home = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--home" -> home = Path.of(require(args, ++i, "--home"));
                    case "--inspect-source" -> {
                        command = "inspect";
                        value = Path.of(require(args, ++i, "--inspect-source"));
                    }
                    case "--import" -> {
                        command = "import";
                        value = Path.of(require(args, ++i, "--import"));
                    }
                    case "--render" -> {
                        command = "render";
                        value = Path.of(require(args, ++i, "--render"));
                    }
                    case "--render-first-run" -> {
                        command = "render-first-run";
                        value = Path.of(require(args, ++i, "--render-first-run"));
                    }
                    case "--render-pack-manager" -> {
                        command = "render-pack-manager";
                        value = Path.of(require(args, ++i, "--render-pack-manager"));
                    }
                    case "--render-pack-progress" -> {
                        command = "render-pack-progress";
                        value = Path.of(require(args, ++i, "--render-pack-progress"));
                    }
                    case "--render-release-notes" -> {
                        command = "render-release-notes";
                        value = Path.of(require(args, ++i, "--render-release-notes"));
                    }
                    case "--update" -> command = "update";
                    case "--launch" -> command = "launch";
                    case "--help", "-h" -> {
                        System.out.println("""
                                ChonkCraft launcher

                                  --inspect-source PATH  prove a directory or archive is usable
                                  --import PATH          build and register a verified graphics pack
                                  --render FILE          render the launcher for visual QA
                                  --render-first-run FILE render the first-run state for visual QA
                                  --render-pack-manager FILE render the graphics-pack manager
                                  --render-pack-progress FILE render an active pack import
                                  --render-release-notes FILE render authenticated update history
                                  --update               download and select the newest game
                                  --launch               start the selected installed game
                                  --home DIR             use a separate launcher data directory
                                """);
                        System.exit(0);
                    }
                    default -> throw new IllegalArgumentException("unknown option " + args[i]);
                }
            }
            if (!"ui".equals(command) && !"launch".equals(command)
                    && !"update".equals(command) && value == null) {
                throw new IllegalArgumentException(command + " needs a path");
            }
            return new Arguments(command, value, home);
        }

        private static String require(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " needs a value");
            }
            return args[index];
        }
    }

    private static final class NoActions implements LauncherView.Actions {

        @Override public void managePacks() {}
        @Override public void releaseNotes() {}
        @Override public void play() {}
    }

    private static final class NoPackActions implements PackManagerView.Actions {

        @Override public void chooseFile() {}
        @Override public void chooseFolder() {}
        @Override public void importSource(Path source) {}
        @Override public void usePack(PackLibrary.PackInfo pack) {}
        @Override public void exportPack(PackLibrary.PackInfo pack) {}
        @Override public void deletePack(PackLibrary.PackInfo pack) {}
        @Override public void openPackFolder() {}
        @Override public void closePackManager() {}
    }
}
