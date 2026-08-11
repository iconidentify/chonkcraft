package net.chonkbase.chonkcraft.engine.missile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.chonkbase.chonkcraft.engine.generated.GeneratedMissiles;

/** The native projectile and damaged-building presentation catalog. */
public final class MissileCatalog {

    private final Map<String, MissileType> types;
    private final BurningBuildingFrames burningBuildings;

    private MissileCatalog(Map<String, MissileType> types,
            BurningBuildingFrames burningBuildings) {
        this.types = Map.copyOf(types);
        this.burningBuildings = burningBuildings;
    }

    public static MissileCatalog generated() {
        Map<String, MissileType> types = new LinkedHashMap<>();
        for (MissileType type : GeneratedMissiles.TYPES) {
            types.put(type.ident(), type);
        }
        List<BurningBuildingFrames.Frame> frames = GeneratedMissiles.BURNING.stream()
                .map(row -> new BurningBuildingFrames.Frame(
                        row.percent(), row.missile() == null ? null : types.get(row.missile())))
                .toList();
        BurningBuildingFrames burning = new BurningBuildingFrames(frames);
        BurningBuildingFrames.declare(burning);
        return new MissileCatalog(types, burning);
    }

    public Map<String, MissileType> types() {
        return types;
    }

    public MissileType get(String ident) {
        return types.get(ident);
    }

    public BurningBuildingFrames burningBuildings() {
        return burningBuildings;
    }
}
