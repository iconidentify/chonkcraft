package net.chonkbase.chonkcraft.desktop;

import java.awt.Cursor;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.Map;
import net.chonkbase.chonkcraft.data.graphic.IndexedImage;
import net.chonkbase.chonkcraft.data.graphic.Palette;
import net.chonkbase.chonkcraft.engine.GameData;

/**
 * Warcraft II's pointers, out of the archive.
 *
 * <p>The game does not have one cursor. It has a gauntlet for pointing, a
 * yellow eagle over your own units, a red one over an enemy, a green one over
 * something you can act on, and a crossed-out gauntlet where you cannot go.
 * Which one is showing is how the game answers "what would a click do here"
 * before the click, and a port with a plain arrow makes the player guess.
 *
 * <p>The orc pointers are a claw and crosshairs rather than a gauntlet and
 * eagles; which set is loaded follows the player's race, as the original does.
 */
final class GameCursors {

    /** What the pointer is over. */
    enum Kind {
        /** Ordinary ground. */
        POINT,
        /** Something of yours. */
        OWN,
        /** Something hostile. */
        ENEMY,
        /** Something a pending command can act on. */
        ACT,
        /** Somewhere the pending command cannot be carried out. */
        FORBIDDEN
    }

    private final Map<Kind, Cursor> cursors = new EnumMap<>(Kind.class);

    private GameCursors() {
    }

    /**
     * Loads the set for a race, or returns null if the art is missing.
     *
     * <p>Null rather than a fallback: a caller that cannot get the game's
     * pointers should keep the system one, which at least exists.
     */
    static GameCursors load(GameData data, String race) {
        boolean orc = "orc".equalsIgnoreCase(race);
        GameCursors set = new GameCursors();
        set.put(data, Kind.POINT,
                orc ? "orc/cursors/orcish_claw" : "human/cursors/human_gauntlet");
        set.put(data, Kind.OWN,
                orc ? "orc/cursors/yellow_crosshairs" : "human/cursors/yellow_eagle");
        set.put(data, Kind.ENEMY,
                orc ? "orc/cursors/red_crosshairs" : "human/cursors/red_eagle");
        set.put(data, Kind.ACT,
                orc ? "orc/cursors/green_crosshairs" : "human/cursors/green_eagle");
        set.put(data, Kind.FORBIDDEN,
                orc ? "orc/cursors/orcish_dont_click_here"
                        : "human/cursors/human_dont_click_here");
        return set.cursors.containsKey(Kind.POINT) ? set : null;
    }

    /**
     * Builds one pointer.
     *
     * <p>The hotspot comes out of the file. Inventing one -- top left for the
     * gauntlet, centre for the eagles -- is a guess that happens to be close
     * for some of them and wrong for the rest.
     */
    private void put(GameData data, Kind kind, String path) {
        var decoded = data.cursor(path);
        Palette palette = decoded == null ? null : data.paletteFor(path);
        if (decoded == null || palette == null) {
            return;
        }
        BufferedImage picture = decoded.image().toBufferedImage(palette);

        // The toolkit chooses a size it can manage and scales silently, so the
        // picture is padded into that size rather than stretched into it.
        //
        // Asking at all throws without a display, which is how a headless
        // render brought the whole screen down: the guard below was around the
        // wrong call.
        java.awt.Dimension best;
        try {
            best = Toolkit.getDefaultToolkit()
                    .getBestCursorSize(picture.getWidth(), picture.getHeight());
        } catch (RuntimeException e) {
            return;
        }
        if (best.width <= 0 || best.height <= 0) {
            return;
        }
        BufferedImage padded = new BufferedImage(
                Math.max(best.width, picture.getWidth()),
                Math.max(best.height, picture.getHeight()),
                BufferedImage.TYPE_INT_ARGB);
        var g2 = padded.createGraphics();
        g2.drawImage(picture, 0, 0, null);
        g2.dispose();

        Point hotspot = new Point(
                Math.max(0, Math.min(decoded.hotspotX(), padded.getWidth() - 1)),
                Math.max(0, Math.min(decoded.hotspotY(), padded.getHeight() - 1)));
        try {
            cursors.put(kind, Toolkit.getDefaultToolkit()
                    .createCustomCursor(padded, hotspot, kind.name()));
        } catch (RuntimeException e) {
            // A headless or unusual display; the system pointer will do.
        }
    }

    /** The cursor for a kind, falling back to the plain pointer. */
    Cursor cursor(Kind kind) {
        Cursor found = cursors.get(kind);
        return found != null ? found : cursors.get(Kind.POINT);
    }

    /** Whether any pointers were loaded. */
    boolean isAvailable() {
        return !cursors.isEmpty();
    }
}
