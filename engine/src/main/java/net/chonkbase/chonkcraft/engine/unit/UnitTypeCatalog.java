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
        // Retail BNE points both land-unit race families at native corpse type
        // 105. Type 106 remains present in the table, but is not the CorpseType
        // of the orc infantry/cavalry entries represented by the legacy orc
        // body alias. This is visible without relying on art or names: across
        // the sealed 52-case campaign corpus every one of 77 witnessed corpse
        // transitions becomes type 105, including all 31 witnesses from orc
        // types 1, 7 and 9; none becomes 106.
        for (UnitType type : types.values()) {
            if ("unit-orc-dead-body".equals(type.corpse())) {
                type.setCorpse("unit-human-dead-body");
            }
        }
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
