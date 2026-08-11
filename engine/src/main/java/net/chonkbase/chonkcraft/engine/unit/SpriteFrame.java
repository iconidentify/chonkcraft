package net.chonkbase.chonkcraft.engine.unit;

/**
 * Turns an animation frame and a heading into a sheet index.
 *
 * <p>Implements {@code UnitUpdateHeading}.
 *
 * <p>Warcraft II draws eight facings from five pictures. North, north-east,
 * east, south-east and south are stored; the western three are the eastern
 * three flipped. That is why a sprite sheet is five frames wide: one sheet row
 * holds all five facings of a single animation step, and the animation's
 * {@code frame} instruction names the row by its first index, in multiples of
 * five.
 */
public final class SpriteFrame {

    /**
     * A resolved frame.
     *
     * @param index    index into the sprite sheet
     * @param mirrored whether to draw it flipped horizontally
     */
    public record Resolved(int index, boolean mirrored) {}

    private SpriteFrame() {
    }

    /**
     * Resolves a frame.
     *
     * @param baseFrame     the animation's current frame, a multiple of the
     *                      stored-facing count
     * @param heading       0 to 7, north then clockwise
     * @param numDirections how many facings the type has, normally 8
     */
    public static Resolved resolve(int baseFrame, int heading, int numDirections) {
        int directions = numDirections > 0 ? numDirections : 8;
        // Stored facings per animation step: half the directions, plus one,
        // because both north and south are their own mirror.
        int stored = directions / 2 + 1;

        // Snap the base frame to the start of its row, in case a caller passed
        // an already-resolved index back in.
        int row = (baseFrame / stored) * stored;

        int facing = Math.floorMod(heading, directions);
        if (facing <= directions / 2) {
            // North through south going clockwise: stored directly.
            return new Resolved(row + facing, false);
        }
        // The western half mirrors the eastern half.
        return new Resolved(row + (directions - facing), true);
    }

    /** Stored facings per animation step for a type. */
    public static int storedFacings(int numDirections) {
        int directions = numDirections > 0 ? numDirections : 8;
        return directions / 2 + 1;
    }
}
