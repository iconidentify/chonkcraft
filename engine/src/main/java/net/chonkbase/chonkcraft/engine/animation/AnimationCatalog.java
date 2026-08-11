package net.chonkbase.chonkcraft.engine.animation;

import java.util.LinkedHashMap;
import java.util.Map;
import net.chonkbase.chonkcraft.engine.generated.GeneratedAnimations;

/** Every native animation program committed in the game JAR. */
public final class AnimationCatalog {

    private final Map<String, AnimationSet> sets;

    public AnimationCatalog(Map<String, AnimationSet> sets) {
        this.sets = new LinkedHashMap<>(sets);
    }

    public static AnimationCatalog generated() {
        return new AnimationCatalog(GeneratedAnimations.sets());
    }

    public Map<String, AnimationSet> sets() {
        return sets;
    }
}
