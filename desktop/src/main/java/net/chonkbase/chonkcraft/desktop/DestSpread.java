package net.chonkbase.chonkcraft.desktop;

import java.util.List;
import net.chonkbase.chonkcraft.engine.unit.Unit;

/**
 * Per-unit destination offsets {@code DoRightButton} applies to an
 * open-ground group click.
 *
 * <p>{@code 0x43e330} measures the selected soldiers' tile box and their
 * mean. {@code 0x43e530} then writes each unit's dest as its own tile plus
 * {@code click - mean} on any axis whose span is at most three. A click
 * inside the box, or an axis wider than three, keeps the clicked square.
 * Retail also skips the measurement when {@code 0x416bc0(click, 0xc)} is
 * nonzero. Used to send every selected footman to the same tile, which is
 * why two Human 1 soldiers stacked on 25,28 instead of spreading onto
 * 25,27 and 25,29.
 */
final class DestSpread {

    /** {@code 0x43e330} spreads an axis only when {@code max - min <= 3}. */
    private static final int MAX_SPREAD_SPAN = 3;

    private final boolean spreadX;
    private final boolean spreadY;
    private final int addX;
    private final int addY;
    private final int mapWidth;
    private final int mapHeight;

    private DestSpread(boolean spreadX, boolean spreadY, int addX, int addY,
            int mapWidth, int mapHeight) {
        this.spreadX = spreadX;
        this.spreadY = spreadY;
        this.addX = addX;
        this.addY = addY;
        this.mapWidth = mapWidth;
        this.mapHeight = mapHeight;
    }

    static DestSpread none() {
        return new DestSpread(false, false, 0, 0, 0, 0);
    }

    /**
     * One measurement of the current selection against the clicked square.
     *
     * <p>Retail computes this once per {@code DoRightButton} before walking
     * the nine-slot packet. A click on another unit keeps the unit's own
     * tile; dest-spread is a ground-order construction.
     */
    static DestSpread of(List<Unit> selected, int clickX, int clickY,
            int mapWidth, int mapHeight) {
        if (selected == null || selected.isEmpty()) {
            return none();
        }
        int count = 0;
        int sumX = 0;
        int sumY = 0;
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (Unit unit : selected) {
            if (unit == null) {
                continue;
            }
            int tileX = unit.tileX();
            int tileY = unit.tileY();
            count++;
            sumX += tileX;
            sumY += tileY;
            if (tileX < minX) {
                minX = tileX;
            }
            if (tileX > maxX) {
                maxX = tileX;
            }
            if (tileY < minY) {
                minY = tileY;
            }
            if (tileY > maxY) {
                maxY = tileY;
            }
        }
        if (count == 0) {
            return none();
        }
        if (clickX >= minX && clickX <= maxX && clickY >= minY && clickY <= maxY) {
            return none();
        }
        boolean spreadX = (maxX - minX) <= MAX_SPREAD_SPAN;
        boolean spreadY = (maxY - minY) <= MAX_SPREAD_SPAN;
        int addX = spreadX ? clickX - (sumX / count) : 0;
        int addY = spreadY ? clickY - (sumY / count) : 0;
        return new DestSpread(spreadX, spreadY, addX, addY, mapWidth, mapHeight);
    }

    int destX(Unit unit, int clickX) {
        if (!spreadX || unit == null) {
            return clickX;
        }
        return clamp(unit.tileX() + addX, mapWidth);
    }

    int destY(Unit unit, int clickY) {
        if (!spreadY || unit == null) {
            return clickY;
        }
        return clamp(unit.tileY() + addY, mapHeight);
    }

    /**
     * Retail clamps both axes to {@code word[0x4acc2c] - 1}. Maps are square
     * there; Java uses each axis' own size so a rectangular test map cannot
     * walk off the far edge.
     */
    private static int clamp(int value, int mapSize) {
        if (mapSize <= 0) {
            return Math.max(0, value);
        }
        if (value < 0) {
            return 0;
        }
        if (value >= mapSize) {
            return mapSize - 1;
        }
        return value;
    }
}
