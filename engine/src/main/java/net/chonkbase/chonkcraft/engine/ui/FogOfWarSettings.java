package net.chonkbase.chonkcraft.engine.ui;

/** Native retail-shaped fog opacity policy for the field and minimap. */
public final class FogOfWarSettings {
    public record Levels(int explored, int revealed, int unseen) {
        public boolean isValid() {
            return explored > 0 && explored <= 255
                    && revealed > explored && revealed <= 255
                    && unseen >= revealed && unseen <= 255;
        }
    }

    public static final Levels DEFAULT = new Levels(0x7F, 0xBE, 0xFE);
    public static final Levels MINIMAP_DEFAULT = new Levels(0x55, 0xAA, 0xFF);
    private static final FogOfWarSettings BNE = new FogOfWarSettings();

    private FogOfWarSettings() {}
    public static FogOfWarSettings battleNet() { return BNE; }
    public Levels levels() { return DEFAULT; }
    public Levels minimapLevels() { return MINIMAP_DEFAULT; }
}
