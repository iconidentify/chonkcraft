package net.chonkbase.chonkcraft.desktop;

import java.awt.image.BufferedImage;
import net.chonkbase.chonkcraft.data.graphic.IndexedImage;
import net.chonkbase.chonkcraft.engine.ui.FogOfWarSettings;
import net.chonkbase.chonkcraft.engine.ui.FogOfWarSettings.Levels;

/**
 * The sixteen masks that give the fog of war its shape.
 *
 * <p>Warcraft II does not cover hidden ground with opaque squares. Every
 * tileset carries sixteen 32 by 32 fog masks in the first sixteen tiles of its
 * sheet -- copies them there before it decodes a single
 * piece of terrain, and {@code SetFogOfWarGraphics} in the ChonkCraft prelude
 * points the engine at exactly that sheet. One mask per way the four corners
 * of a square can be covered, so the boundary between seen and unseen ground
 * is drawn with curves and a dithered fringe instead of a staircase.
 *
 * <p>Which mask goes where is {@link net.chonkbase.chonkcraft.engine.map.FogOfWar}'s
 * business; this class only turns them into something drawable.
 *
 * <p>The masks are a single palette index, so their colour is thrown away and
 * only their coverage is kept. Each one is baked into two images up front, one
 * at each opacity the game uses, because building them per square per frame is
 * the same mistake that made unit sprites stutter: a screen of fog is several
 * hundred squares and it is redrawn sixty times a second.
 */
public final class FogTiles {

    /** How many masks a tileset carries, and the size of each. */
    public static final int FRAMES = 16;
    private static final int SIZE = 32;

    private final BufferedImage[] explored = new BufferedImage[FRAMES];
    private final BufferedImage[] unseen = new BufferedImage[FRAMES];
    private final Levels levels;

    /**
     * Cuts the masks out of a decoded tileset sheet at the game's own
     * opacities.
     *
     * <p>All four shipped tilesets carry byte-identical fog masks, which is
     * why upstream can name the summer sheet unconditionally and still be
     * right in winter. Reading them from whichever tileset is loaded gets the
     * same sixteen frames without a second decode.
     *
     * @param levels what {@code SetFogOfWarOpacityLevels} in the prelude asked
     *               for. Handed in rather than held here as a constant: the
     *               numbers used to be copied into this file with a comment
     *               naming the script line they came from, which meant editing
     *               the script changed nothing
     */
    public static FogTiles from(IndexedImage sheet, Levels levels) {
        if (sheet.width() < FRAMES * SIZE || sheet.height() < SIZE) {
            throw new IllegalArgumentException(
                    "tileset sheet is too small to hold the fog masks: "
                            + sheet.width() + "x" + sheet.height());
        }
        return new FogTiles(sheet, levels == null ? FogOfWarSettings.DEFAULT : levels);
    }

    /**
     * The masks at the engine's built-in opacities, for a caller that has no
     * game data to ask. The shipped scripts set these same numbers.
     */
    public static FogTiles from(IndexedImage sheet) {
        return from(sheet, FogOfWarSettings.DEFAULT);
    }

    private FogTiles(IndexedImage sheet, Levels levels) {
        this.levels = levels;
        for (int frame = 0; frame < FRAMES; frame++) {
            explored[frame] = bake(sheet, frame, levels.explored());
            unseen[frame] = bake(sheet, frame, levels.unseen());
        }
    }

    /** The opacities these masks were baked at. */
    public Levels levels() {
        return levels;
    }

    /**
     * One mask as black at a given alpha.
     *
     * <p>Index zero is the transparent index in every extracted graphic, so it
     * is the absence of fog. Everything else is fog, whatever colour the
     * palette gives it: the masks are drawn in a single index and the game
     * uses them for coverage, not for colour.
     */
    private static BufferedImage bake(IndexedImage sheet, int frame, int alpha) {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        int left = frame * SIZE;
        int argb = alpha << 24;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                if (sheet.get(left + x, y) != 0) {
                    image.setRGB(x, y, argb);
                }
            }
        }
        return image;
    }

    /** The mask for the edge of ground that is remembered but unwatched. */
    public BufferedImage explored(int frame) {
        return explored[frame];
    }

    /** The mask for the edge of ground never seen. */
    public BufferedImage unseen(int frame) {
        return unseen[frame];
    }

    /**
     * How many pixels of a mask are covered, which is what the shape of the
     * fringe amounts to. Here so a test can tell a real mask from an empty
     * one without going through the renderer.
     */
    public int coverage(int frame) {
        BufferedImage image = explored[frame];
        int count = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    count++;
                }
            }
        }
        return count;
    }
}
