package net.chonkbase.chonkcraft.engine.unit;

import java.util.LinkedHashMap;
import java.util.Map;
import net.chonkbase.chonkcraft.engine.animation.AnimationCatalog;
import net.chonkbase.chonkcraft.engine.generated.GeneratedUnitRoster;

/** The complete native Warcraft II unit roster committed in the game JAR. */
public final class UnitTypeCatalog {

    private final Map<String, UnitType> types;

    public UnitTypeCatalog(Map<String, UnitType> types) {
        this.types = new LinkedHashMap<>(types);
    }

    public static UnitTypeCatalog generated(AnimationCatalog animations) {
        UnitTypeCatalog catalog = new UnitTypeCatalog(GeneratedUnitRoster.types());
        catalog.applyBattleNetDeathEffects();
        catalog.resolveAnimations(animations.sets());
        return catalog;
    }

    /**
     * Restores the Zeppelin death effect from retail BNE.
     *
     * <p>The legacy declarations give both scout aircraft an empty corpse and
     * a one-tick death program, but omit the visual effect made by the retail
     * executable.  BNE 2.02b's {@code LetUnitDie} at {@code 0x004514c0}
     * explicitly compares the type byte with {@code 0x29} (Goblin Zeppelin)
     * before entering {@code 0x0040ff60}.  That routine allocates a visual
     * effect record centred on the dead aircraft.  Gnomish Flying Machine is
     * type {@code 0x28}; its flag byte is {@code 0x82}, so it takes neither
     * the flag-bit nor the explicit-type effect arm and intentionally vanishes.
     *
     * <p>This belongs to the aircraft family rather than {@code World.kill}:
     * ordinary flying creatures use their own death frames and must not gain
     * a mechanical explosion merely because they are airborne.
     */
    private void applyBattleNetDeathEffects() {
        UnitType zeppelin = types.get("unit-zeppelin");
        if (zeppelin != null) {
            zeppelin.setExplosion("missile-explosion");
        }
    }

    public Map<String, UnitType> types() {
        return types;
    }

    public void resolveAnimations(Map<String, net.chonkbase.chonkcraft.engine.animation.AnimationSet> sets) {
        for (UnitType type : types.values()) {
            if (!type.animations().isEmpty()) {
                type.setAnimationSet(sets.get(type.animations()));
            }
        }
    }
}
