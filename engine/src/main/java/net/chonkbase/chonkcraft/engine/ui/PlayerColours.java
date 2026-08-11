package net.chonkbase.chonkcraft.engine.ui;

import java.util.List;

/** Native BNE player-colour palette ramps. */
public final class PlayerColours {
    /** A player's four replacement colours, as packed {@code 0xRRGGBB}. */
    public record Ramp(String name, int[] colours) {
        public Ramp { colours = colours.clone(); }
        @Override public int[] colours() { return colours.clone(); }
    }

    private static final PlayerColours BNE = new PlayerColours(208, 4, List.of(
            ramp("red", 164,0,0, 124,0,0, 92,4,0, 68,4,0),
            ramp("blue", 12,72,204, 4,40,160, 0,20,116, 0,4,76),
            ramp("green", 44,180,148, 20,132,92, 4,84,44, 0,40,12),
            ramp("violet", 152,72,176, 116,44,132, 80,24,88, 44,8,44),
            ramp("orange", 248,140,20, 200,96,16, 152,60,16, 108,32,12),
            ramp("black", 40,40,60, 28,28,44, 20,20,32, 12,12,20),
            ramp("white", 224,224,224, 152,152,180, 84,84,128, 36,40,76),
            ramp("yellow", 252,252,72, 228,204,40, 204,160,16, 180,116,0),
            ramp("red", 164,0,0, 124,0,0, 92,4,0, 68,4,0),
            ramp("blue", 12,72,204, 4,40,160, 0,20,116, 0,4,76),
            ramp("green", 44,180,148, 20,132,92, 4,84,44, 0,40,12),
            ramp("violet", 152,72,176, 116,44,132, 80,24,88, 44,8,44),
            ramp("orange", 248,140,20, 200,96,16, 152,60,16, 108,32,12),
            ramp("black", 40,40,60, 28,28,44, 20,20,32, 12,12,20),
            ramp("white", 224,224,224, 152,152,180, 84,84,128, 36,40,76),
            ramp("yellow", 252,252,72, 228,204,40, 204,160,16, 180,116,0)));

    private final int firstIndex;
    private final int count;
    private final List<Ramp> ramps;

    private PlayerColours(int firstIndex, int count, List<Ramp> ramps) {
        this.firstIndex = firstIndex;
        this.count = count;
        this.ramps = List.copyOf(ramps);
    }

    public static PlayerColours battleNet() { return BNE; }
    public int firstIndex() { return firstIndex; }
    public int count() { return count; }
    public boolean isDefined() { return firstIndex >= 0 && count > 0 && !ramps.isEmpty(); }
    public List<Ramp> ramps() { return ramps; }
    public Ramp rampFor(int player) { return ramps.get(Math.floorMod(player, ramps.size())); }

    private static Ramp ramp(String name, int... rgb) {
        int[] colours = new int[rgb.length / 3];
        for (int i = 0; i < colours.length; i++) {
            colours[i] = (rgb[i * 3] << 16) | (rgb[i * 3 + 1] << 8) | rgb[i * 3 + 2];
        }
        return new Ramp(name, colours);
    }
}
