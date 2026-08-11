package net.chonkbase.chonkcraft.engine.map;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.chonkbase.chonkcraft.data.map.PudMap;

/** Complete native tile-code semantics and palette cycles for all four terrains. */
public final class TilesetCatalog {
    public record Definition(Tileset tileset, List<int[]> cyclingRanges) {}
    private record Row(String kind, String[] fields) {}

    private static final Map<PudMap.Tileset, List<Row>> ROWS = read();
    private TilesetCatalog() {}

    public static Definition create(PudMap.Tileset which) {
        List<Row> rows = ROWS.get(which);
        if (rows == null) throw new IllegalArgumentException("no native tileset " + which);
        Tileset tileset = new Tileset();
        List<int[]> cycles = new ArrayList<>();
        for (Row row : rows) {
            String[] f = row.fields();
            switch (row.kind()) {
                case "M" -> {
                    tileset.setName(f[0]);
                    tileset.setImageFile(f[1]);
                    tileset.setTileSize(Integer.parseInt(f[2]), Integer.parseInt(f[3]));
                }
                case "N" -> {
                    int actual = tileset.terrainIndex(f[0]);
                    if (actual != Integer.parseInt(f[1])) {
                        throw new IllegalStateException("terrain order drift for " + which + "/" + f[0]);
                    }
                }
                case "T" -> tileset.setTile(Integer.parseInt(f[0]), new Tileset.Tile(
                        Integer.parseInt(f[1]), Long.parseLong(f[2]),
                        Integer.parseInt(f[3]), Integer.parseInt(f[4])));
                case "S" -> tileset.setSpecial(f[0], Integer.parseInt(f[1]));
                case "C" -> cycles.add(new int[] {Integer.parseInt(f[0]), Integer.parseInt(f[1])});
                default -> throw new IllegalStateException("bad tileset row " + row.kind());
            }
        }
        return new Definition(tileset, List.copyOf(cycles));
    }

    private static Map<PudMap.Tileset, List<Row>> read() {
        Map<PudMap.Tileset, List<Row>> result = new EnumMap<>(PudMap.Tileset.class);
        try (var stream = TilesetCatalog.class.getResourceAsStream("/chonkcraft/tilesets.tsv")) {
            if (stream == null) throw new IllegalStateException("missing /chonkcraft/tilesets.tsv");
            try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank() || line.startsWith("#")) continue;
                    String[] parts = line.split("\\t", -1);
                    if (parts.length < 3) throw new IllegalStateException("bad tileset row: " + line);
                    PudMap.Tileset which = PudMap.Tileset.valueOf(parts[1]);
                    result.computeIfAbsent(which, ignored -> new ArrayList<>())
                            .add(new Row(parts[0], java.util.Arrays.copyOfRange(parts, 2, parts.length)));
                }
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return result;
    }
}
