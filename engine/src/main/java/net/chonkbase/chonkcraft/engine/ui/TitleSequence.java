package net.chonkbase.chonkcraft.engine.ui;

import java.util.List;

/** Fixed application presentation shown before the Battle.net Edition menu. */
public final class TitleSequence {

    /** How one title entry is presented. */
    public enum Kind {
        /** A native black background; no asset is requested. */
        BLACK,
        IMAGE,
        VIDEO
    }

    /** One step in the native title sequence. */
    public record Screen(Kind kind, String assetPath, int timeoutSeconds,
            boolean stretch, boolean menuMusic) {

        public Screen {
            if (kind == null) {
                throw new IllegalArgumentException("title kind is required");
            }
            if (kind != Kind.BLACK && (assetPath == null || assetPath.isBlank())) {
                throw new IllegalArgumentException(kind + " title requires an asset");
            }
            if (kind == Kind.BLACK && assetPath != null) {
                throw new IllegalArgumentException("a native black title has no asset");
            }
            timeoutSeconds = Math.max(0, timeoutSeconds);
        }
    }

    private static final List<Screen> BATTLE_NET = List.of(
            new Screen(Kind.BLACK, null, 1, true, false),
            new Screen(Kind.VIDEO, "videos/logo", 0, true, false),
            new Screen(Kind.VIDEO, "videos/gameintro", 0, true, false),
            new Screen(Kind.IMAGE, "ui/title", 20, true, true));

    private TitleSequence() {
    }

    /** Blizzard media plus Java's native black movie background. */
    public static List<Screen> battleNet() {
        return BATTLE_NET;
    }
}
