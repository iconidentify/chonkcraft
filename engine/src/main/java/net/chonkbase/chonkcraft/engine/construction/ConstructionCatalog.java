package net.chonkbase.chonkcraft.engine.construction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.chonkbase.chonkcraft.engine.generated.GeneratedConstructions;

/** Native building-construction animations for one retail tileset. */
public final class ConstructionCatalog {

    public enum Source {
        CONSTRUCTION,
        MAIN
    }

    public record Stage(int percent, Source source, int frame) {}

    public record Construction(String ident, String sprite, int width, int height,
            List<Stage> stages) {

        public Construction {
            stages = List.copyOf(stages);
        }

        public Stage stageAt(double fraction) {
            int percent = (int) Math.round(Math.max(0, Math.min(1, fraction)) * 100);
            Stage chosen = stages.isEmpty() ? null : stages.getFirst();
            for (Stage stage : stages) {
                if (stage.percent() <= percent) {
                    chosen = stage;
                }
            }
            return chosen;
        }
    }

    private final Map<String, Construction> constructions;

    private ConstructionCatalog(Map<String, Construction> constructions) {
        this.constructions = Map.copyOf(constructions);
    }

    public static ConstructionCatalog generated(String tilesetName) {
        String tileset = tilesetName == null
                ? "summer" : tilesetName.toLowerCase(Locale.ROOT);
        Map<String, Construction> constructions = new LinkedHashMap<>();
        for (GeneratedConstructions.Row row : GeneratedConstructions.ROWS) {
            String sprite = row.sprites().get(tileset);
            if (sprite == null) {
                throw new IllegalArgumentException("Unknown construction tileset: " + tilesetName);
            }
            List<Stage> stages = row.stages().stream()
                    .map(stage -> new Stage(stage.percent(),
                            "main".equals(stage.source()) ? Source.MAIN : Source.CONSTRUCTION,
                            stage.frame()))
                    .toList();
            constructions.put(row.ident(), new Construction(row.ident(), sprite,
                    row.width(), row.height(), stages));
        }
        return new ConstructionCatalog(constructions);
    }

    public Map<String, Construction> constructions() {
        return constructions;
    }

    public Construction get(String ident) {
        return constructions.get(ident);
    }
}
