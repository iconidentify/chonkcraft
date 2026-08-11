package net.chonkbase.chonkcraft.engine.ui;

import java.util.Map;
import net.chonkbase.chonkcraft.engine.generated.GeneratedIcons;

/**
 * The native command-icon registry.
 *
 * <p>The identifiers and frame numbers are the declarative snapshot in
 * {@link GeneratedIcons}; resolving one no longer executes {@code icons.legacy-declaration}.
 * Every retail tileset uses the same 46 by 38 frame grid and changes only the
 * image sheet behind it.
 */
public final class IconCatalog {

    /** The size of one cell in every retail icon sheet. */
    public static final int ICON_WIDTH = 46;
    public static final int ICON_HEIGHT = 38;

    private final Map<String, Integer> frames;

    public IconCatalog(Map<String, Integer> frames) {
        this.frames = Map.copyOf(frames);
    }

    /** The generated BNE/ChonkCraft command-icon declarations. */
    public static IconCatalog generated() {
        return new IconCatalog(GeneratedIcons.FRAMES);
    }

    public Map<String, Integer> frames() {
        return frames;
    }

    /** The frame for a name, or {@code -1} if it was never defined. */
    public int frame(String name) {
        return frames.getOrDefault(name, -1);
    }
}
