package net.chonkbase.chonkcraft.engine.generated;

/**
 * Building-construction presentation rows for every retail tileset.
 *
 * <p>This declarative snapshot was transcribed from the GPL-2.0-or-later
 * ChonkCraft construction resources and is retained with that provenance. Runtime
 * code consumes these rows directly and does not evaluate the source retired scripting language.
 */
public final class GeneratedConstructions {

    private GeneratedConstructions() {
    }

    public record StageRow(int percent, String source, int frame) {}

    public record Row(String ident, java.util.Map<String, String> sprites,
            int width, int height, java.util.List<StageRow> stages) {}

    private static final java.util.List<StageRow> STANDARD = java.util.List.of(
            new StageRow(0, "construction", 0),
            new StageRow(25, "construction", 1),
            new StageRow(50, "main", 1));

    private static final java.util.List<StageRow> LAND2 = java.util.List.of(
            new StageRow(0, "construction", 0),
            new StageRow(25, "construction", 1));

    public static final java.util.List<Row> ROWS = java.util.List.of(
            new Row("construction-none", land(), 64, 64, STANDARD),
            new Row("construction-land", land(), 64, 64, STANDARD),
            new Row("construction-land2", land(), 64, 64, LAND2),
            new Row("construction-wall", java.util.Map.of(
                    "summer", "tilesets/summer/neutral/buildings/wall_construction_site",
                    "winter", "tilesets/winter/neutral/buildings/wall_construction_site",
                    "wasteland", "tilesets/wasteland/neutral/buildings/wall_construction_site",
                    "swamp", "tilesets/summer/neutral/buildings/wall_construction_site"),
                    32, 32, STANDARD),
            new Row("construction-human-shipyard", java.util.Map.of(
                    "summer", "human/buildings/shipyard_construction_site",
                    "winter", "tilesets/winter/human/buildings/shipyard_construction_site",
                    "wasteland", "human/buildings/shipyard_construction_site",
                    "swamp", "tilesets/swamp/human/buildings/shipyard_construction_site"),
                    96, 96, STANDARD),
            new Row("construction-human-oil-well", java.util.Map.of(
                    "summer", "tilesets/summer/human/buildings/oil_well_construction_site",
                    "winter", "tilesets/winter/human/buildings/oil_well_construction_site",
                    "wasteland", "tilesets/wasteland/human/buildings/oil_well_construction_site",
                    "swamp", "tilesets/swamp/human/buildings/oil_platform_construction_site"),
                    96, 96, STANDARD),
            new Row("construction-human-refinery", java.util.Map.of(
                    "summer", "human/buildings/refinery_construction_site",
                    "winter", "tilesets/winter/human/buildings/refinery_construction_site",
                    "wasteland", "human/buildings/refinery_construction_site",
                    "swamp", "tilesets/swamp/human/buildings/refinery_construction_site"),
                    96, 96, STANDARD),
            new Row("construction-human-foundry", java.util.Map.of(
                    "summer", "human/buildings/foundry_construction_site",
                    "winter", "tilesets/winter/human/buildings/foundry_construction_site",
                    "wasteland", "human/buildings/foundry_construction_site",
                    "swamp", "tilesets/swamp/human/buildings/foundry_construction_site"),
                    96, 96, STANDARD),
            new Row("construction-orc-shipyard", java.util.Map.of(
                    "summer", "orc/buildings/shipyard_construction_site",
                    "winter", "tilesets/winter/orc/buildings/shipyard_construction_site",
                    "wasteland", "orc/buildings/shipyard_construction_site",
                    "swamp", "tilesets/swamp/orc/buildings/shipyard_construction_site"),
                    96, 96, STANDARD),
            new Row("construction-orc-oil-well", java.util.Map.of(
                    "summer", "tilesets/summer/orc/buildings/oil_well_construction_site",
                    "winter", "tilesets/winter/orc/buildings/oil_well_construction_site",
                    "wasteland", "tilesets/wasteland/orc/buildings/oil_well_construction_site",
                    "swamp", "tilesets/swamp/orc/buildings/oil_platform_construction_site"),
                    96, 96, STANDARD),
            new Row("construction-orc-refinery", java.util.Map.of(
                    "summer", "orc/buildings/refinery_construction_site",
                    "winter", "tilesets/winter/orc/buildings/refinery_construction_site",
                    "wasteland", "orc/buildings/refinery_construction_site",
                    "swamp", "tilesets/swamp/orc/buildings/refinery_construction_site"),
                    96, 96, STANDARD),
            new Row("construction-orc-foundry", java.util.Map.of(
                    "summer", "orc/buildings/foundry_construction_site",
                    "winter", "tilesets/winter/orc/buildings/foundry_construction_site",
                    "wasteland", "orc/buildings/foundry_construction_site",
                    "swamp", "tilesets/swamp/orc/buildings/foundry_construction_site"),
                    96, 96, STANDARD));

    private static java.util.Map<String, String> land() {
        return java.util.Map.of(
                "summer", "neutral/buildings/land_construction_site",
                "winter", "tilesets/winter/neutral/buildings/land_construction_site",
                "wasteland", "neutral/buildings/land_construction_site",
                "swamp", "neutral/buildings/land_construction_site");
    }
}
