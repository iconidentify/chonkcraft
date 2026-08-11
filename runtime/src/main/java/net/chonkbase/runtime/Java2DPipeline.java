package net.chonkbase.runtime;

import java.util.Locale;

/**
 * Selects the accelerated Java2D pipeline before AWT initializes.
 *
 * <p>Adapted from ChonkBlocker's tested desktop bootstrap. Override with
 * {@code SEVEN_JAVA2D_PIPELINE=metal|opengl|xrender|d3d|software}.
 */
public final class Java2DPipeline {
    public enum Choice {
        METAL,
        OPENGL,
        XRENDER,
        D3D,
        SOFTWARE
    }

    private Java2DPipeline() {}

    public static Choice apply() {
        Choice choice = configuredChoice();
        switch (choice) {
            case METAL -> {
                System.setProperty("sun.java2d.metal", "true");
                System.setProperty("sun.java2d.opengl", "false");
            }
            case OPENGL -> {
                System.setProperty("sun.java2d.opengl", "true");
                System.setProperty("sun.java2d.metal", "false");
                System.setProperty("sun.java2d.xrender", "false");
            }
            case XRENDER -> {
                System.setProperty("sun.java2d.xrender", "true");
                System.setProperty("sun.java2d.opengl", "false");
                System.setProperty("sun.java2d.metal", "false");
            }
            case D3D -> {
                System.setProperty("sun.java2d.d3d", "true");
                System.setProperty("sun.java2d.opengl", "false");
                System.setProperty("sun.java2d.noddraw", "true");
            }
            case SOFTWARE -> {
                System.setProperty("sun.java2d.metal", "false");
                System.setProperty("sun.java2d.opengl", "false");
                System.setProperty("sun.java2d.d3d", "false");
                System.setProperty("sun.java2d.noddraw", "true");
            }
        }
        System.out.println("[SevenDays] Java2D pipeline=" + choice.name().toLowerCase(Locale.ROOT));
        return choice;
    }

    static Choice configuredChoice() {
        String override = System.getenv("SEVEN_JAVA2D_PIPELINE");
        if (override == null || override.isBlank()) {
            override = System.getProperty("seven.java2d.pipeline");
        }
        if (override != null && !override.isBlank()) {
            return switch (override.trim().toLowerCase(Locale.ROOT)) {
                case "metal" -> Choice.METAL;
                case "opengl", "gl" -> Choice.OPENGL;
                case "xrender", "xr" -> Choice.XRENDER;
                case "d3d", "direct3d" -> Choice.D3D;
                case "software", "off", "none" -> Choice.SOFTWARE;
                default -> defaultForOs();
            };
        }
        return defaultForOs();
    }

    static Choice defaultForOs() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return Choice.METAL;
        }
        if (os.contains("win")) {
            return Choice.D3D;
        }
        if (os.contains("nux") || os.contains("nix") || os.contains("bsd")) {
            return Choice.OPENGL;
        }
        return Choice.SOFTWARE;
    }
}

