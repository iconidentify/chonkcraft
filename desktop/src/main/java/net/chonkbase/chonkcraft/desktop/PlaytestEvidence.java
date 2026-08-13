package net.chonkbase.chonkcraft.desktop;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.imageio.ImageIO;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.MapField;
import net.chonkbase.chonkcraft.engine.missile.Missile;
import net.chonkbase.chonkcraft.engine.save.SaveGame;
import net.chonkbase.chonkcraft.engine.unit.Unit;

/** Writes the complete, resumable evidence bundle behind the playtest hotkey. */
final class PlaytestEvidence {

    private static final int UNIT_RADIUS = 10;
    private static final int TERRAIN_RADIUS = 4;
    private static final DateTimeFormatter STAMP = DateTimeFormatter
            .ofPattern("uuuuMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private PlaytestEvidence() {
    }

    record Request(World world, BufferedImage screenshot, Unit selected,
            int localPlayer, int cameraX, int cameraY, int focusX, int focusY,
            String mapPath, String campaign, int mission,
            List<Integer> armedTriggers, Path root, Instant createdAt,
            List<PlayerIntentJournal.Entry> playerIntents) {}

    record Result(Path directory, int units, int missiles, int terrainTiles) {}

    static Result write(Request request) throws IOException {
        if (request.mapPath() == null || request.mapPath().isBlank()) {
            throw new IOException("nothing to save");
        }
        Files.createDirectories(request.root());
        String base = "playtest-" + STAMP.format(request.createdAt())
                + "-c" + request.world().cycle();
        Path directory = uniqueDirectory(request.root(), base);

        Path screenshot = directory.resolve("screen.png");
        Path save = directory.resolve("state" + SaveGame.SUFFIX);
        Path evidence = directory.resolve("evidence.json");
        if (!ImageIO.write(request.screenshot(), "png", screenshot.toFile())) {
            throw new IOException("PNG writer unavailable");
        }
        SaveGame.write(request.world(), request.mapPath(), request.campaign(), request.mission(),
                request.armedTriggers(), save);

        List<Unit> units = nearbyUnits(request);
        List<Missile> missiles = nearbyMissiles(request);
        int terrainTiles = writeJson(request, units, missiles, evidence);
        return new Result(directory, units.size(), missiles.size(), terrainTiles);
    }

    private static Path uniqueDirectory(Path root, String base) throws IOException {
        for (int suffix = 0; suffix < 1_000; suffix++) {
            Path candidate = root.resolve(suffix == 0 ? base : base + "-" + suffix);
            try {
                return Files.createDirectory(candidate);
            } catch (java.nio.file.FileAlreadyExistsException collision) {
                // Two rapid captures in one millisecond retain both packets.
            }
        }
        throw new IOException("could not allocate evidence directory");
    }

    private static List<Unit> nearbyUnits(Request request) {
        List<Unit> units = new ArrayList<>();
        for (Unit unit : request.world().unitsSnapshot()) {
            if (unit == request.selected() || unit.selected()
                    || Math.max(Math.abs(unit.tileX() - request.focusX()),
                            Math.abs(unit.tileY() - request.focusY())) <= UNIT_RADIUS) {
                units.add(unit);
            }
        }
        units.sort(Comparator.comparingInt(Unit::id));
        return units;
    }

    private static List<Missile> nearbyMissiles(Request request) {
        List<Missile> missiles = new ArrayList<>();
        for (Missile missile : request.world().missiles()) {
            if (Math.max(Math.abs(missile.tileX() - request.focusX()),
                    Math.abs(missile.tileY() - request.focusY())) <= UNIT_RADIUS + 4) {
                missiles.add(missile);
            }
        }
        missiles.sort(Comparator.comparingInt(Missile::battleNetPoolSlot));
        return missiles;
    }

    private static int writeJson(Request request, List<Unit> units,
            List<Missile> missiles, Path evidence) throws IOException {
        StringBuilder out = new StringBuilder(32_768);
        World world = request.world();
        out.append("{\n");
        out.append("  \"schema\": 1,\n");
        string(out, "created_at", request.createdAt().toString(), 2, true);
        out.append("  \"cycle\": ").append(world.cycle()).append(",\n");
        string(out, "map_path", request.mapPath(), 2, true);
        string(out, "campaign", request.campaign(), 2, true);
        out.append("  \"mission\": ").append(request.mission()).append(",\n");
        out.append("  \"local_player\": ").append(request.localPlayer()).append(",\n");
        out.append("  \"sync_rng\": {\"seed\": ")
                .append(Integer.toUnsignedLong(world.randomSeed()))
                .append(", \"draws\": ").append(world.randomDraws()).append("},\n");
        out.append("  \"async_rng\": {\"seed\": ")
                .append(Integer.toUnsignedLong(world.battleNetRandomSeed()))
                .append(", \"draws\": ").append(world.battleNetRandomDraws()).append("},\n");
        out.append("  \"camera\": {\"pixel_x\": ").append(request.cameraX())
                .append(", \"pixel_y\": ").append(request.cameraY())
                .append(", \"focus_tile_x\": ").append(request.focusX())
                .append(", \"focus_tile_y\": ").append(request.focusY()).append("},\n");
        out.append("  \"selected_unit_id\": ")
                .append(request.selected() == null ? "null" : request.selected().id())
                .append(",\n");
        out.append("  \"artifacts\": {\"screenshot\": \"screen.png\", \"save\": ")
                .append(quote("state" + SaveGame.SUFFIX)).append("},\n");

        appendPlayerIntents(out, request.playerIntents());
        out.append(",\n");

        out.append("  \"units\": [\n");
        for (int i = 0; i < units.size(); i++) {
            appendUnit(out, world, units.get(i));
            out.append(i + 1 < units.size() ? ",\n" : "\n");
        }
        out.append("  ],\n");

        out.append("  \"missiles\": [\n");
        for (int i = 0; i < missiles.size(); i++) {
            appendMissile(out, missiles.get(i));
            out.append(i + 1 < missiles.size() ? ",\n" : "\n");
        }
        out.append("  ],\n");

        int terrainTiles = appendTerrain(out, request);
        out.append("\n  ]\n}");
        out.append('\n');
        Files.writeString(evidence, out, StandardCharsets.UTF_8);
        return terrainTiles;
    }

    private static void appendPlayerIntents(StringBuilder out,
            List<PlayerIntentJournal.Entry> entries) {
        out.append("  \"player_intents\": [\n");
        List<PlayerIntentJournal.Entry> safe = entries == null ? List.of() : entries;
        for (int index = 0; index < safe.size(); index++) {
            PlayerIntentJournal.Entry entry = safe.get(index);
            out.append("    {\"cycle\": ").append(entry.cycle())
                    .append(", \"event\": ").append(quote(entry.event()))
                    .append(", \"selected_unit_ids\": ").append(entry.selectedUnitIds());
            if (entry.command() != null) {
                var command = entry.command();
                out.append(", \"command\": {\"kind\": ")
                        .append(quote(command.kind().name()))
                        .append(", \"player\": ").append(command.player())
                        .append(", \"unit_id\": ").append(command.unitId())
                        .append(", \"x\": ").append(command.x())
                        .append(", \"y\": ").append(command.y())
                        .append(", \"target_id\": ").append(command.targetId())
                        .append(", \"type_index\": ").append(command.typeIndex())
                        .append(", \"queued\": ").append(command.queued()).append('}');
            }
            if (entry.accepted() != null) {
                out.append(", \"accepted\": ").append(entry.accepted());
            }
            out.append('}').append(index + 1 < safe.size() ? ",\n" : "\n");
        }
        out.append("  ]");
    }

    private static void appendUnit(StringBuilder out, World world, Unit unit) {
        Unit target = unit.target();
        MapField field = world.map().fieldOrNull(unit.tileX(), unit.tileY());
        out.append("    {\"id\": ").append(unit.id())
                .append(", \"type\": ").append(quote(unit.type() == null
                        ? null : unit.type().ident()))
                .append(", \"player\": ").append(unit.player())
                .append(", \"tile_x\": ").append(unit.tileX())
                .append(", \"tile_y\": ").append(unit.tileY())
                .append(", \"offset_x\": ").append(unit.offsetX())
                .append(", \"offset_y\": ").append(unit.offsetY())
                .append(", \"hp\": ").append(unit.hitPoints())
                .append(", \"order\": ").append(quote(name(unit.order())))
                .append(", \"saved_order\": ").append(quote(name(unit.savedOrder())))
                .append(", \"target_id\": ").append(target == null ? "null" : target.id())
                .append(", \"order_target_x\": ").append(unit.orderTargetX())
                .append(", \"order_target_y\": ").append(unit.orderTargetY())
                .append(", \"attack_goal_x\": ").append(unit.attackGoalX())
                .append(", \"attack_goal_y\": ").append(unit.attackGoalY())
                .append(", \"ai_behavior\": ").append(unit.battleNetAiBehavior())
                .append(", \"selected\": ").append(unit.selected())
                .append(", \"alive\": ").append(unit.isAlive())
                .append(", \"on_map\": ").append(unit.isOnMap());
        if (field != null) {
            out.append(", \"visual_tile\": ").append(field.tile())
                    .append(", \"tile_flags\": ")
                    .append(quote("0x" + Long.toHexString(field.flags())));
        }
        out.append('}');
    }

    private static void appendMissile(StringBuilder out, Missile missile) {
        out.append("    {\"type\": ").append(quote(missile.type().ident()))
                .append(", \"pool_slot\": ").append(missile.battleNetPoolSlot())
                .append(", \"x\": ").append(Math.round(missile.x()))
                .append(", \"y\": ").append(Math.round(missile.y()))
                .append(", \"to_x\": ").append(Math.round(missile.toX()))
                .append(", \"to_y\": ").append(Math.round(missile.toY()))
                .append(", \"frame\": ").append(missile.frame())
                .append(", \"direction\": ").append(missile.direction())
                .append(", \"remaining\": ").append(missile.battleNetRemaining())
                .append(", \"pending_impact\": ").append(missile.battleNetPendingImpact())
                .append(", \"source_id\": ")
                .append(missile.source() == null ? "null" : missile.source().id())
                .append(", \"target_id\": ")
                .append(missile.target() == null ? "null" : missile.target().id())
                .append('}');
    }

    private static int appendTerrain(StringBuilder out, Request request) {
        out.append("  \"terrain\": [\n");
        int written = 0;
        for (int y = request.focusY() - TERRAIN_RADIUS;
                y <= request.focusY() + TERRAIN_RADIUS; y++) {
            for (int x = request.focusX() - TERRAIN_RADIUS;
                    x <= request.focusX() + TERRAIN_RADIUS; x++) {
                MapField field = request.world().map().fieldOrNull(x, y);
                if (field == null) {
                    continue;
                }
                if (written++ > 0) {
                    out.append(",\n");
                }
                out.append("    {\"x\": ").append(x)
                        .append(", \"y\": ").append(y)
                        .append(", \"visual_tile\": ").append(field.tile())
                        .append(", \"flags\": ")
                        .append(quote("0x" + Long.toHexString(field.flags())))
                        .append('}');
            }
        }
        return written;
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static void string(StringBuilder out, String name, String value,
            int indent, boolean comma) {
        out.append(" ".repeat(indent)).append(quote(name)).append(": ")
                .append(quote(value)).append(comma ? ",\n" : "\n");
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }
}
