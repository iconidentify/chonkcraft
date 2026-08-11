package net.chonkbase.chonkcraft.extract;

import java.nio.file.Path;
import java.nio.file.Paths;
import net.chonkbase.chonkcraft.data.source.InstallSource;

/**
 * The extractor, from the command line.
 *
 * <p>Reads a Warcraft II installation and writes one asset pack.
 *
 * <pre>
 *   chonkcraft-extractor [options]
 * --install DIR   the game directory holding DATA/, or WC2_INSTALL_DIR
 * --out FILE      where to write the pack, default chonkcraft.chonkpack
 *                     beside the installation
 * --no-verify     skip reading the finished pack back and comparing it
 * --quiet         report only the totals
 * </pre>
 *
 * <p>Verification is on by default and roughly doubles the build. That is the
 * right default: a pack is the only copy of the art a player will have, and
 * "we were careful" is not the same claim as "every asset was checked".
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        Path install = null;
        Path out = null;
        boolean verify = true;
        boolean quiet = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--install" -> install = Paths.get(require(args, ++i, "--install"));
                case "--out" -> out = Paths.get(require(args, ++i, "--out"));
                case "--no-verify" -> verify = false;
                case "--quiet" -> quiet = true;
                case "--help", "-h" -> {
                    usage(System.out);
                    return;
                }
                default -> {
                    System.err.println("unknown option " + args[i]);
                    usage(System.err);
                    System.exit(2);
                }
            }
        }

        if (install == null) {
            String configured = System.getProperty("wc2.install.dir");
            if (configured == null || configured.isBlank()) {
                configured = System.getenv("WC2_INSTALL_DIR");
            }
            if (configured == null || configured.isBlank()) {
                System.err.println("""
                        No Warcraft II installation given.

                        Pass --install /path/to/Warcraft, or set WC2_INSTALL_DIR or
                        -Dwc2.install.dir to the game directory holding DATA/MAINDAT.WAR.
                        """);
                System.exit(2);
                return;
            }
            install = Paths.get(configured);
        }

        InstallSource source = InstallSource.tryAt(install);
        if (source == null) {
            System.err.println("no Warcraft II data found under " + install);
            System.exit(2);
            return;
        }
        if (out == null) {
            out = install.resolve("chonkcraft.chonkpack");
        }

        long started = System.nanoTime();
        if (!quiet) {
            System.out.println("Reading " + source.describe());
            System.out.println("Writing " + out.toAbsolutePath());
            if (verify) {
                System.out.println("Verification is on; this reads everything back afterwards.");
            }
            System.out.println();
        }

        PackBuilder.Report report;
        try (source) {
            report = new PackBuilder(source, verify).build(out);
        }

        System.out.print(PackBuilder.describe(report));
        System.out.printf("%n  built in %.1f s%n", (System.nanoTime() - started) / 1e9);

        boolean failed = report.notes().stream().anyMatch(note -> note.startsWith("VERIFICATION FAILED"));
        if (failed) {
            System.exit(1);
        }
    }

    private static String require(String[] args, int index, String option) {
        if (index >= args.length) {
            System.err.println(option + " needs a value");
            System.exit(2);
        }
        return args[index];
    }

    private static void usage(java.io.PrintStream out) {
        out.println("""
                Usage: chonkcraft-extractor [options]

                  --install DIR   the Warcraft II directory holding DATA/
                                  (default: WC2_INSTALL_DIR or -Dwc2.install.dir)
                  --out FILE      where to write the pack
                                  (default: chonkcraft.chonkpack beside the installation)
                  --no-verify     do not read the finished pack back and compare it
                  --quiet         report only the totals
                """);
    }
}
