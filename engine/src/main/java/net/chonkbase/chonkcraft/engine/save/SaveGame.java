package net.chonkbase.chonkcraft.engine.save;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.ai.AiForce;
import net.chonkbase.chonkcraft.engine.ai.AiPlayer;
import net.chonkbase.chonkcraft.engine.map.FogOfWar;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.SeenBuildings;
import net.chonkbase.chonkcraft.engine.missile.Missile;
import net.chonkbase.chonkcraft.engine.missile.MissileClass;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.trigger.TriggerSystem;

/**
 * Writes a game in ChonkCraft's readable, versioned native save schema.
 *
 * <p>Implements {@code SaveGame}, and the
 * format keeps the useful upstream property that a save is not a memory dump,
 * but a data-only sequence of typed reconstruction records. It is parsed only
 * by {@link NativeSaveReader}; it is not executable source code.
 *
 * <p>That is worth keeping for the reason upstream keeps it. A save is
 * readable, so a broken one can be diagnosed by looking at it; it survives the
 * engine's internals being rearranged, because it only names things the
 * scripts already name; and loading needs no code that saving did not already
 * have to be right about.
 *
 * <p>The caller reconstructs the named map before applying these records, then
 * the save restores mutable terrain and all live simulation state.
 */
public final class SaveGame {

    private SaveGame() {
    }

    /** The suffix a save carries; gzipped, as upstream's are. */
    public static final String SUFFIX = ".sav.gz";

    /**
     * Writes a game to a file.
     *
     * @param world    the game to write
     * @param mapPath  how to find the map again: an archive path for a
     *                 campaign mission, or a file path for a skirmish map
     * @param campaign the campaign this belongs to, or null
     * @param mission  the mission number within it, or 0
     */
    public static void write(World world, String mapPath, String campaign, int mission,
            Path file) throws IOException {
        write(world, mapPath, campaign, mission, null, file);
    }

    /**
     * The same, naming the triggers the mission has not used yet.
     *
     * @param armedTriggers what {@code TriggerSystem.armedTriggers} returned,
     *                      or null for a game with no triggers. Loading a
     *                      campaign save reruns the mission script, which arms
     *                      every trigger again -- including the ones that had
     *                      already fired -- so the save has to say which of
     *                      them were still standing.
     */
    public static void write(World world, String mapPath, String campaign, int mission,
            java.util.List<Integer> armedTriggers, Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (OutputStream raw = Files.newOutputStream(file);
                OutputStream gzip = new GZIPOutputStream(raw);
                Writer out = new OutputStreamWriter(gzip, StandardCharsets.UTF_8)) {
            write(world, mapPath, campaign, mission, armedTriggers, out);
        }
    }

    /** Writes the native document itself for tests and stream-oriented callers. */
    public static void write(World world, String mapPath, String campaign, int mission,
            Writer out) throws IOException {
        write(world, mapPath, campaign, mission, null, out);
    }

    /** The same, naming the triggers the mission has not used yet. */
    public static void write(World world, String mapPath, String campaign, int mission,
            java.util.List<Integer> armedTriggers, Writer out) throws IOException {
        writeDocument(world, mapPath, campaign, mission,
                armedTriggers == null ? null
                        : new TriggerSystem.SavedState(armedTriggers, java.util.List.of(),
                                java.util.List.of()),
                out, 3);
    }

    /** Writes a version-four save carrying every mutable trigger field. */
    public static void writeWithTriggers(World world, String mapPath, String campaign,
            int mission, TriggerSystem.SavedState triggerState, Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (OutputStream raw = Files.newOutputStream(file);
                OutputStream gzip = new GZIPOutputStream(raw);
                Writer out = new OutputStreamWriter(gzip, StandardCharsets.UTF_8)) {
            writeWithTriggers(world, mapPath, campaign, mission, triggerState, out);
        }
    }

    /** Stream form of {@link #writeWithTriggers(World, String, String, int,
     * TriggerSystem.SavedState, Path)}. */
    public static void writeWithTriggers(World world, String mapPath, String campaign,
            int mission, TriggerSystem.SavedState triggerState, Writer out) throws IOException {
        writeDocument(world, mapPath, campaign, mission, triggerState, out, 4);
    }

    private static void writeDocument(World world, String mapPath, String campaign,
            int mission, TriggerSystem.SavedState triggerState, Writer out,
            int version) throws IOException {
        out.write("SaveFormat(\"chonkcraft-save\", " + version + ")\n\n");

        out.write("SavedGameInfo({\n");
        out.write("  SaveFile = " + quote(mapPath) + ",\n");
        out.write("  Campaign = " + quote(campaign) + ",\n");
        out.write("  Mission = " + mission + ",\n");
        out.write("  SyncRandSeed = " + world.randomSeed() + ",\n");
        // The count as well as the seed. A game a hundred cycles in has drawn
        // a hundred times, and a save carrying only the seed would rewind the
        // sequence: the restored game would go on to draw different numbers
        // from the one it claims to continue.
        out.write("  SyncRandDraws = " + world.randomDraws() + ",\n");
        out.write("  BattleNetRandSeed = " + world.battleNetRandomSeed() + ",\n");
        out.write("  BattleNetRandDraws = " + world.battleNetRandomDraws() + ",\n");
        out.write("})\n\n");

        out.write("GameCycle = " + world.cycle() + "\n\n");

        if (triggerState != null) {
            StringBuilder armed = new StringBuilder("SetArmedTriggers({");
            for (int index : triggerState.armed()) {
                armed.append(index).append(", ");
            }
            out.write(armed.append("})\n\n").toString());
            StringBuilder flags = new StringBuilder("SetTriggerFlags({");
            for (String flag : triggerState.flags()) {
                flags.append(quote(flag)).append(", ");
            }
            out.write(flags.append("})\n\n").toString());
            StringBuilder delays = new StringBuilder("SetTriggerDelays({");
            for (TriggerSystem.SavedDelay delay : triggerState.delays()) {
                delays.append(delay.trigger()).append(", ")
                        .append(delay.remaining()).append(", ");
            }
            out.write(delays.append("})\n\n").toString());
        }

        writeTerrain(world, out);
        writePlayers(world, out);
        writeUpgrades(world, out);
        writeAllowed(world, out);
        writeVision(world, out);
        writeUnits(world, out);
        writeMissiles(world, out);
        writeAi(world, out);
    }

    /**
     * Writes active projectile state after the units its references name.
     *
     * <p>Building fires are derived from the saved unit's burning flag and are
     * recreated by the normal simulation; serializing them as well would draw
     * two fires after load. Every other active missile is explicit simulation
     * state, including spell clouds and click markers as well as damaging
     * shots, and is carried whole so its next action stays deterministic.
     */
    private static void writeMissiles(World world, Writer out) throws IOException {
        for (Missile missile : world.missiles()) {
            if (missile.type() == null
                    || missile.type().missileClass() == MissileClass.FIRE) {
                continue;
            }
            Missile.SavedState state = missile.savedState();
            int source = missile.source() == null ? -1 : missile.source().id();
            int target = missile.target() == null ? -1 : missile.target().id();
            long startCycle = world.savedProjectileStartCycle(missile);
            long queuedCycle = world.savedProjectileQueuedCycle(missile);
            boolean pending = world.savedProjectilePending(missile);
            out.write("RestoreMissile({ type = " + quote(missile.type().ident())
                    + ", source = " + source + ", target = " + target
                    + ", startCycle = " + startCycle + ", queuedCycle = " + queuedCycle
                    + ", pending = " + pending
                    + ", x = " + state.x() + ", y = " + state.y()
                    + ", fromX = " + state.fromX() + ", fromY = " + state.fromY()
                    + ", toX = " + state.toX() + ", toY = " + state.toY()
                    + ", travelled = " + state.travelled() + ", total = " + state.total()
                    + ", bounces = " + state.bounces() + ", hit = " + state.hit()
                    + ", delay = " + state.delay() + ", damage = " + state.damage()
                    + ", timeToLive = " + state.timeToLive()
                    + ", periodicHit = " + state.periodicHit()
                    + ", arrived = " + state.arrived() + ", sleep = " + state.sleep()
                    + ", cycleState = " + state.cycleState()
                    + ", moveStarted = " + state.moveStarted()
                    + ", frame = " + state.frame() + ", direction = " + state.direction()
                    + ", bneMotion = " + state.battleNetMotion()
                    + ", bneConstructorDrawn = " + state.battleNetConstructorDrawn()
                    + ", bnePendingImpact = " + state.battleNetPendingImpact()
                    + ", bneImpactWait = " + state.battleNetImpactWait()
                    + ", bneSpeed = " + state.battleNetSpeed()
                    + ", bneError = " + state.battleNetError()
                    + ", bneMajor = " + state.battleNetMajor()
                    + ", bneMinor = " + state.battleNetMinor()
                    + ", bneFlags = " + state.battleNetFlags()
                    + ", bneRemaining = " + state.battleNetRemaining()
                    + ", bneArcProgress = " + state.battleNetArcProgress()
                    + ", bneArcStride = " + state.battleNetArcStride()
                    + ", bneSkipDraw = " + state.battleNetSkipNextMotionDraw()
                    + ", bnePoolSlot = " + state.battleNetPoolSlot() + " })\n");
        }
        out.write("\n");
    }

    /**
     * The ground as the game left it, square by square.
     *
     * <p>None of this was written and none of it was read back, so a save
     * reloaded with every felled tree standing again. A player who cleared a
     * wood, saved and resumed found the timber back on the map -- and worse,
     * the squares impassable again, with the worker that had cut its way in
     * standing inside the forest that had grown over it. A breached wall came
     * back whole, closing the hole an army had spent five minutes making. The
     * minimap and the field both drew it, because both read the live map;
     * nothing was wrong except that the save had never carried the ground.
     *
     * <p>Upstream writes every field of the map, as {@code CMap::Save} loops over {@code CMapField::Save},
     * which prints the tile, the seen tile,
     * the value, the move cost and the flags by name. It has to: its own
     * {@code SaveGame} stubs {@code SetTile} out while the map reloads
     * so the save is the only description
     * of the terrain there is.
     *
     * <p>This implementation reloads the map and keeps what it says, so only the
     * difference is written -- see {@link GameMap#terrainChangedSinceLoad}. On
     * the seventh human mission, a game that had felled twenty squares and
     * breached a wall wrote 44 lines where upstream's shape would have written
     * 9,216, and a 128 by 128 map would cost 16,384. The worst case measured is
     * the eleventh human mission stripped of wood entirely -- 3,351 squares
     * felled, 3,799 changed once the edges of the wood are mended -- at 3,799
     * lines and 143KB of text before the save is gzipped.
     *
     * <p>Four values per square rather than upstream's five: the runtime tile
     * code, its stable graphic, terrain flags and the resource/wall value. The seen tile is
     * not among them because this implementation has none at all: it keeps the explored
     * bits and the buildings each player remembers, and no per-square memory of
     * terrain, so ground that changes under the fog changes for everybody at
     * once. That is a deviation older than this method and it is recorded in
     * focused tests rather than hidden here. The move cost is not among them because
     * ChonkCraft's terrain costs are uniform and this implementation has no field to put one
     * in.
     *
     * <p>The flags go out as a hex string rather than a number. The legacy format had one
     * numeric type and it is a double, which carries 53 bits exactly, and the
     * per-subtile impassability mask lives above bit 48 -- so a flag word
     * written in decimal could come back a different flag word.
     */
    private static void writeTerrain(World world, Writer out) throws IOException {
        GameMap map = world.map();
        java.util.List<GameMap.TerrainChange> changed = map.terrainChangedSinceLoad();
        if (changed.isEmpty()) {
            return;
        }
        out.write("-- The ground the game changed: felled wood, blown rock, breached wall.\n");
        for (GameMap.TerrainChange change : changed) {
            out.write("SetSavedTile(" + change.x() + ", " + change.y() + ", " + change.tile()
                    + ", " + quote("0x" + Long.toHexString(change.flags())) + ", "
                    + change.value() + ", " + change.graphic() + ")\n");
        }
        out.write("\n");
    }

    private static void writePlayers(World world, Writer out) throws IOException {
        Player[] players = world.players();
        for (int index = 0; index < players.length; index++) {
            Player player = players[index];
            if (player == null) {
                continue;
            }
            for (UnitType.Resource resource : UnitType.Resource.values()) {
                out.write("SetPlayerData(" + index + ", \"Resources\", "
                        + quote(resource.name().toLowerCase(Locale.ROOT)) + ", "
                        + player.get(resource) + ")\n");
            }
            // The three tallies the results screen is made of. They used to be
            // left out, so a player who saved an hour into a mission and
            // resumed it got an end-of-mission screen saying they had killed
            // nothing and razed nothing, with a score of zero.
            out.write("SetPlayerData(" + index + ", \"Score\", " + player.score() + ")\n");
            out.write("SetPlayerData(" + index + ", \"TotalKills\", "
                    + player.totalKills() + ")\n");
            out.write("SetPlayerData(" + index + ", \"TotalRazings\", "
                    + player.totalRazings() + ")\n");
        }
        writeDiplomacy(world, out);
        out.write("\n");
    }

    /**
     * Every directed standing, including ones a trigger wrote over the
     * type-derived table. Missing this used to make a Human 8 resume forget
     * that slot four had been told to hate the town it was already besieging.
     */
    private static void writeDiplomacy(World world, Writer out) throws IOException {
        Player[] players = world.players();
        for (int player = 0; player < players.length; player++) {
            if (players[player] == null) {
                continue;
            }
            for (int other = 0; other < players.length; other++) {
                if (player == other || players[other] == null) {
                    continue;
                }
                out.write("SetDiplomacy(" + player + ", "
                        + quote(world.diplomacyStance(player, other))
                        + ", " + other + ")\n");
            }
        }
    }

    private static void writeUpgrades(World world, Writer out) throws IOException {
        for (int index = 0; index < world.players().length; index++) {
            var state = world.upgrades(index);
            if (state == null) {
                continue;
            }
            for (String ident : state.researched()) {
                out.write("SetUpgrade(" + index + ", " + quote(ident) + ")\n");
            }
        }
        out.write("\n");
    }

    /**
     * What the mission permits.
     *
     * <p>Written out even though the mission script would set it again on
     * load, because a save that reloads its script would also re-arm triggers
     * that have already fired. Carrying the table is the smaller of the two
     * awkwardnesses.
     */
    private static void writeAllowed(World world, Writer out) throws IOException {
        var allowed = world.allowed();
        if (allowed == null || allowed.isEmpty()) {
            return;
        }
        for (var entry : allowed.flags().entrySet()) {
            out.write("DefineAllow(" + quote(entry.getKey()) + ", "
                    + quote(new String(entry.getValue())) + ")\n");
        }
        out.write("\n");
    }

    /**
     * What each player has seen, and whose eyes they see through.
     *
     * <p>None of this was written, and the loss is one a player notices
     * immediately: an hour of scouting on a large map went black again, every
     * remembered enemy building with it, and an alliance's shared vision was
     * silently revoked. The tiles that appeared to survive were only the ones
     * the reloaded units re-lit from their own sight radii.
     *
     * <p>Upstream writes the explored bit per player on every map field
     * and its
     * {@code SeenTile} alongside. A bit per tile per player is a great many
     * records for a 128 by 128 map, so this runs them together instead:
     * the string is alternating run lengths, starting with a run of
     * unexplored ground, read in map order. A map nobody has scouted is one
     * number and a fully explored one is two.
     */
    private static void writeVision(World world, Writer out) throws IOException {
        int players = world.players().length;
        for (int player = 0; player < players; player++) {
            for (int other = 0; other < players; other++) {
                if (player != other && world.sharesVisionWith(player, other)) {
                    out.write("SetSharedVision(" + player + ", " + other + ")\n");
                }
            }
        }

        FogOfWar fog = world.fog();
        for (int player = 0; player < fog.playerCount(); player++) {
            if (fog.exploredCount(player) == 0) {
                continue;
            }
            out.write("SetExplored(" + player + ", "
                    + quote(runLengths(fog, world.map(), player)) + ")\n");
        }

        for (int player = 0; player < players; player++) {
            // Sorted rather than left as the memory map gives them. The
            // collection is backed by a HashMap, so its order is a property of
            // the map and not of the game, and a save has to be the same file
            // whichever machine wrote it.
            java.util.List<SeenBuildings.Memory> remembered =
                    new java.util.ArrayList<>(world.seenBuildings().forPlayer(player));
            remembered.sort(java.util.Comparator
                    .comparingInt(SeenBuildings.Memory::tileY)
                    .thenComparingInt(SeenBuildings.Memory::tileX));
            for (SeenBuildings.Memory memory : remembered) {
                if (memory.type() == null) {
                    continue;
                }
                out.write("RememberBuilding(" + player + ", "
                        + quote(memory.type().ident()) + ", " + memory.owner() + ", "
                        + memory.tileX() + ", " + memory.tileY() + ", "
                        + memory.spriteIndex() + ", " + memory.mirrored() + ", "
                        + memory.underConstruction() + ", " + memory.progress() + ")\n");
            }
        }
        out.write("\n");
    }

    /** The explored bitmap as alternating run lengths, unexplored first. */
    private static String runLengths(FogOfWar fog, GameMap map, int player) {
        StringBuilder runs = new StringBuilder();
        boolean explored = false;
        int run = 0;
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                boolean seen = fog.isExplored(player, x, y);
                if (seen == explored) {
                    run++;
                    continue;
                }
                runs.append(run).append(',');
                explored = seen;
                run = 1;
            }
        }
        return runs.append(run).toString();
    }

    /**
     * What each computer opponent had in mind.
     *
     * <p>Only the standing requests, which is what {@link LoadGame} can put
     * back through {@code AiPlayer}'s public surface. The forces already
     * assembled and the script's position are not carried, so a computer that
     * was mid-assault when the game was saved re-plans from where its script
     * starts. That is a smaller loss than it sounds -- the units are still
     * there and still owned -- but it is a real one, and it is recorded in
     * focused tests rather than hidden here.
     */
    private static void writeAi(World world, Writer out) throws IOException {
        for (var entry : world.ais().entrySet()) {
            AiPlayer ai = entry.getValue();
            out.write("DefineAiPlayerState(" + entry.getKey() + ", "
                    + ai.sleepUntilCycle() + ", " + ai.usePlan() + ", "
                    + ai.scriptIndex() + ", " + ai.scriptLoopIndex() + ")\n");
            AiPlayer.BattleNetSavedState retail = ai.savedBattleNetState();
            if (retail != null) {
                out.write("RestoreBattleNetAiState(" + entry.getKey() + ", "
                        + retail.profileId() + ", " + retail.pc() + ", "
                        + quote(hex(retail.state())) + ", "
                        + retail.buildPriorityLimit() + ", "
                        + retail.wantedBasicSoldiers() + ", "
                        + retail.wantedTankers() + ", "
                        + intTable(retail.action33Candidates()) + ", "
                        + intTable(retail.action33ResolvedHigh()) + ", "
                        + retail.watchUpgradeCycle() + ", "
                        + retail.flyerTrainCycle() + ")\n");
            }
            java.util.List<String> wanted = new java.util.ArrayList<>();
            for (var request : ai.requests().entrySet()) {
                wanted.add(quote(request.getKey().ident()) + ", " + request.getValue());
            }
            java.util.Collections.sort(wanted);
            for (String request : wanted) {
                out.write("AiPlayerNeeds(" + entry.getKey() + ", " + request + ")\n");
            }
            // The standing wants, in their own order: AiSet finds the first
            // entry for a type, so the list's order is state too.
            for (AiPlayer.StandingRequest standing : ai.unitTypeRequests()) {
                out.write("AiPlayerWants(" + entry.getKey() + ", "
                        + quote(standing.type().ident()) + ", " + standing.count() + ")\n");
            }
            java.util.List<String> research =
                    new java.util.ArrayList<>(ai.researchRequests());
            java.util.Collections.sort(research);
            for (String ident : research) {
                out.write("AiPlayerResearches(" + entry.getKey() + ", " + quote(ident) + ")\n");
            }
            for (AiForce force : ai.forces()) {
                StringBuilder forceWanted = new StringBuilder("{");
                for (var want : force.wantedByIdent().entrySet()) {
                    forceWanted.append(quote(want.getKey())).append(", ")
                            .append(want.getValue()).append(", ");
                }
                forceWanted.append("}");
                StringBuilder members = new StringBuilder("{");
                for (int id : force.memberIds()) {
                    members.append(id).append(", ");
                }
                members.append("}");
                out.write("RestoreAiForce(" + entry.getKey() + ", " + force.index()
                        + ", " + forceWanted + ", " + members + ", "
                        + quote(force.state().name()) + ", " + force.defending()
                        + ", " + force.goalX() + ", " + force.goalY()
                        + ", " + force.waitOnRallyPoint() + ")\n");
            }
        }
        out.write("\n");
    }

    private static void writeUnits(World world, Writer out) throws IOException {
        java.util.List<Unit> saved = new java.util.ArrayList<>();
        for (Unit unit : world.units()) {
            if (unit.type() == null || unit.isDying()) {
                // A corpse is a few cycles of animation and no state worth
                // keeping: it will not be there when the save is opened.
                continue;
            }
            saved.add(unit);
            out.write("unit = CreateUnit(" + quote(unit.type().ident()) + ", " + unit.player()
                    + ", {" + unit.tileX() + ", " + unit.tileY() + "})\n");
            out.write("SetSavedUnitId(unit, " + unit.id() + ")\n");
            out.write("SetHitPoints(unit, " + unit.hitPoints() + ")\n");
            if (unit.isCaster()) {
                out.write("SetMana(unit, " + unit.mana() + ")\n");
            }
            UnitType.Resource held = unit.heldResource() != null
                    ? unit.heldResource() : unit.carrying();
            if (held != null && unit.carried() > 0) {
                out.write("SetResourcesHeld(unit, "
                        + quote(held.name().toLowerCase(Locale.ROOT)) + ", "
                        + unit.carried() + ")\n");
            }
            if (unit.order() == Unit.Order.UNDER_CONSTRUCTION) {
                out.write("SetConstruction(unit, " + unit.progress() + ", "
                        + unit.progressGoal() + ")\n");
            }
        }

        // References come after every CreateUnit, so a passenger may name a
        // transport that happened to be written later in the list.
        for (Unit unit : saved) {
            writeUnitState(unit, out);
        }

        for (Unit unit : saved) {
            Unit container = unit.carrier() != null ? unit.carrier() : unit.worksite();
            if (unit.removed() && container != null) {
                String kind = unit.carrier() != null ? "carrier" : "worksite";
                out.write("SetContained(" + unit.id() + ", " + container.id() + ", "
                        + quote(kind) + ", " + quote(unit.order().name()) + ")\n");
            }
            // COrder_Resource owns this state on every leg, not only while a
            // worker is hidden inside a mine or depot. Restricting it to
            // contained workers made a visible, laden tanker save as raw oil
            // action 24 but reload with returningToDepot=false.
            if (unit.order() == Unit.Order.HARVEST) {
                int resourceId = unit.resourceUnit() == null ? -1 : unit.resourceUnit().id();
                int depotId = unit.resourceDepot() == null ? -1 : unit.resourceDepot().id();
                int returnGoalId = unit.returnDepotGoal() == null
                        ? -1 : unit.returnDepotGoal().id();
                out.write("SetHarvestState(" + unit.id() + ", " + resourceId + ", "
                        + unit.resourceTileX() + ", " + unit.resourceTileY() + ", "
                        + unit.returningToDepot() + ", " + unit.waitCycles() + ", "
                        + depotId + ", " + returnGoalId + ")\n");
            }
        }
        out.write("\n");
    }

    /**
     * Everything a unit is doing, as one table.
     *
     * <p>Five things per unit used to be written -- type, owner, tile, hit
     * points, mana -- and the rest of the forty-odd fields were dropped. What
     * a player saw was the whole army stopping where it stood: workers forgot
     * they were fetching wood, soldiers forgot their targets, every unit faced
     * south, every rally point was gone, and a building half way through
     * training a unit lost the job <em>and</em> the gold, wood and oil already
     * spent on it, with no warning of any kind.
     *
     * <p>Implements what {@code CUnit::Save} writes onward. Written in a second pass and
     * addressed by saved id rather than by "the unit just created", because
     * anything naming another unit -- an attack target, a queued order's
     * target -- may name one written later in the file.
     *
     * <p>One table rather than a dozen calls so that a save written by a later
     * version can add a field without this one having to learn a new function
     * name to ignore.
     */
    private static void writeUnitState(Unit unit, Writer out) throws IOException {
        StringBuilder state = new StringBuilder();
        state.append("SetUnitState(").append(unit.id()).append(", {");
        state.append(" order = ").append(quote(unit.order().name())).append(",");
        state.append(" heading = ").append(unit.heading()).append(",");
        state.append(" offsetX = ").append(unit.offsetX()).append(",");
        state.append(" offsetY = ").append(unit.offsetY()).append(",");
        state.append(" pathGoalX = ").append(unit.pathGoalX()).append(",");
        state.append(" pathGoalY = ").append(unit.pathGoalY()).append(",");
        state.append(" orderTargetX = ").append(unit.orderTargetX()).append(",");
        state.append(" orderTargetY = ").append(unit.orderTargetY()).append(",");
        state.append(" attackMoveX = ").append(unit.attackMoveX()).append(",");
        state.append(" attackMoveY = ").append(unit.attackMoveY()).append(",");
        // Zero is state too. Legacy saves omitted it, leaving the loader no
        // way to distinguish a deliberate map Patrol from an old assault
        // whose behavior-two marker had not yet become durable.
        state.append(" aiBehavior = ")
                .append(unit.battleNetAiBehavior()).append(",");
        if (unit.battleNetMapPlaced()) {
            state.append(" mapPlaced = true,");
        }
        if (unit.hasBattleNetAiHome()) {
            state.append(" aiHomeX = ").append(unit.battleNetAiHomeX()).append(",");
            state.append(" aiHomeY = ").append(unit.battleNetAiHomeY()).append(",");
        }
        if (unit.carrying() != null) {
            state.append(" carrying = ")
                    .append(quote(unit.carrying().name())).append(",");
        }
        if (unit.heldResource() != null) {
            state.append(" heldResource = ")
                    .append(quote(unit.heldResource().name())).append(",");
        }
        if (unit.savedOrder() != null) {
            state.append(" savedOrder = ").append(quote(unit.savedOrder().name())).append(",");
            if (unit.savedOrder() == Unit.Order.ATTACK_MOVE) {
                state.append(" savedAttackMoveX = ").append(unit.savedAttackMoveX()).append(",");
                state.append(" savedAttackMoveY = ").append(unit.savedAttackMoveY()).append(",");
                if (unit.savedMoveRange() != 0) {
                    state.append(" savedMoveRange = ").append(unit.savedMoveRange()).append(",");
                }
                if (unit.savedAttackScanSleep() != 0) {
                    state.append(" savedAttackScanSleep = ")
                            .append(unit.savedAttackScanSleep()).append(",");
                }
                if (!unit.savedAttackMoveOpening()) {
                    state.append(" savedAttackMoveOpening = false,");
                }
            }
        }
        state.append(" rallyX = ").append(unit.rallyX()).append(",");
        state.append(" rallyY = ").append(unit.rallyY()).append(",");
        state.append(" patrolX = ").append(unit.patrolX()).append(",");
        state.append(" patrolY = ").append(unit.patrolY()).append(",");
        state.append(" progress = ").append(unit.progress()).append(",");
        state.append(" progressGoal = ").append(unit.progressGoal()).append(",");
        state.append(" wait = ").append(unit.waitCycles()).append(",");
        // Research, upgrades, construction and several movement orders keep
        // their work marker for one cycle after committing the result. The
        // live unit's Finished latch makes the following cycle retire that
        // marker instead of applying the result twice. Schema-2 readers also
        // accept older training saves that exposed the same boundary.
        if (unit.orderFinished()) {
            state.append(" orderFinished = true,");
        }
        // Human 2's delayed victory is RESCUED_NEAR. Reloading an elf as
        // just another person-owned archer used to restart the 120-cycle
        // wait, because the trigger no longer saw a rescued unit.
        if (unit.wasRescued()) {
            state.append(" rescuedFrom = ").append(unit.rescuedFrom()).append(",");
        }
        if (unit.battleNetSequenceOffset() >= 0) {
            state.append(" battleNetSequenceOffset = ")
                    .append(unit.battleNetSequenceOffset()).append(",");
            state.append(" battleNetAnimationTimer = ")
                    .append(unit.battleNetAnimationTimer()).append(",");
        }
        if (unit.animation().current() != null
                && unit.type().animationSet() != null) {
            for (net.chonkbase.chonkcraft.engine.animation.AnimationSet.State animationState
                    : unit.type().animationSet().states()) {
                if (unit.type().animationSet().get(animationState)
                        == unit.animation().current()) {
                    state.append(" animationState = ")
                            .append(quote(animationState.name())).append(",");
                    state.append(" animationIndex = ")
                            .append(unit.animation().index()).append(",");
                    state.append(" animationWait = ")
                            .append(unit.animation().waitCycles()).append(",");
                    state.append(" animationUnbreakable = ")
                            .append(unit.animation().unbreakable()).append(",");
                    break;
                }
            }
        }
        if (unit.battleNetOrderDelay() != 0) {
            state.append(" battleNetOrderDelay = ")
                    .append(unit.battleNetOrderDelay()).append(",");
        }
        if (unit.battleNetWoodReadyPathRequired()) {
            state.append(" woodReadyPathRequired = true,");
        }
        if (unit.battleNetWoodTerminalRefusalHeading() >= 0) {
            state.append(" woodTerminalRefusalHeading = ")
                    .append(unit.battleNetWoodTerminalRefusalHeading())
                    .append(",");
        }
        if (unit.battleNetWoodCornerRefusalHeading() >= 0) {
            state.append(" woodCornerRefusalHeading = ")
                    .append(unit.battleNetWoodCornerRefusalHeading())
                    .append(",");
            state.append(" woodCornerRefusalVisits = ")
                    .append(unit.battleNetWoodCornerRefusalVisits())
                    .append(",");
        }
        if (unit.battleNetRangedAttackCadenceRemaining() != 0) {
            state.append(" rangedAttackCadenceRemaining = ")
                    .append(unit.battleNetRangedAttackCadenceRemaining())
                    .append(",");
        }
        if (unit.battleNetRefusals() != 0) {
            state.append(" battleNetRefusals = ")
                    .append(unit.battleNetRefusals()).append(",");
        }
        if (unit.battleNetAttackRefusalRecoveryStage() != 0) {
            state.append(" attackRefusalRecoveryStage = ")
                    .append(unit.battleNetAttackRefusalRecoveryStage())
                    .append(",");
        }
        if (unit.battleNetPaidRefusalRecoveryApproach()) {
            state.append(" paidRefusalRecoveryApproach = true,");
        }
        if (unit.reportsActionBeforeQueued()) {
            state.append(" actionBeforeQueued = ")
                    .append(quote(unit.currentAction().name())).append(",");
            state.append(" actionBeforeQueuedReleaseDelay = ")
                    .append(unit.actionBeforeQueuedReleaseDelay()).append(",");
        }
        if (unit.battleNetPlayerCommandMove()) {
            state.append(" playerCommandMove = true,");
        }
        if (unit.battleNetAttackGroundMove()) {
            state.append(" attackGroundMove = true,");
        }
        if (unit.battleNetStopAfterLeftover()) {
            state.append(" stopAfterLeftover = true,");
        }
        if (unit.battleNetAttackWaitRefillResidual()) {
            state.append(" attackWaitRefillResidual = true,");
        }
        if (unit.battleNetMovingQuarryResidual()) {
            state.append(" movingQuarryResidual = true,");
        }
        if (unit.battleNetNavalPaidParkedRoute()) {
            state.append(" navalPaidParkedRoute = true,");
        }
        if (unit.battleNetSaturatedResidualFaceRetry()) {
            state.append(" saturatedResidualFaceRetry = true,");
        }
        if (unit.battleNetSaturatedCardinalRetryLoop()) {
            state.append(" saturatedCardinalRetryLoop = true,");
        }
        if (unit.battleNetDirectRefusalRecoveryProbe()) {
            state.append(" directRefusalRecoveryProbe = true,");
        }
        if (unit.battleNetStageSixCardinalProbePark()) {
            state.append(" stageSixCardinalProbePark = true,");
        }
        if (unit.battleNetSaturatedNearRecoveryFullRoute()) {
            state.append(" saturatedNearRecoveryFullRoute = true,");
        }
        if (unit.battleNetDirectRefusalReplacementBand()) {
            state.append(" directRefusalReplacementBand = true,");
        }
        if (unit.battleNetDirectRecoveryGeneration() > 0) {
            state.append(" directRecoveryGeneration = ")
                    .append(unit.battleNetDirectRecoveryGeneration())
                    .append(",");
        }
        if (unit.battleNetRetargetResidualParkRefill()) {
            state.append(" retargetResidualParkRefill = true,");
            state.append(" retargetResidualParkSteps = ")
                    .append(unit.battleNetRetargetResidualParkSteps())
                    .append(",");
        }
        if (unit.battleNetLongPaidWrapTimerOneSeen()) {
            state.append(" longPaidWrapTimerOneSeen = true,");
        }
        int paidWrapTailLength =
                unit.battleNetLongPaidWrapParkedTailLength();
        if (unit.hasBattleNetLongPaidWrapParkedRoute()) {
            state.append(" longPaidWrapParkedTailLength = ")
                    .append(paidWrapTailLength).append(",");
            for (int depth = 0; depth < paidWrapTailLength; depth++) {
                state.append(" longPaidWrapParkedTail")
                        .append(depth).append(" = ")
                        .append(unit.battleNetLongPaidWrapParkedTailHeading(
                                depth))
                        .append(",");
            }
        }
        if (unit.battleNetSaturatedWoodCornerLadder()) {
            state.append(" saturatedWoodCornerLadder = true,");
        }
        if (unit.battleNetParkedRefusalHeading() >= 0) {
            state.append(" parkedRefusalHeading = ")
                    .append(unit.battleNetParkedRefusalHeading())
                    .append(",");
        }
        if (unit.battleNetSaturatedWallFacePairHeading() >= 0) {
            state.append(" saturatedWallFacePairHeading = ")
                    .append(unit.battleNetSaturatedWallFacePairHeading())
                    .append(",");
        }
        if (unit.battleNetSaturatedWallFacePairParked()) {
            state.append(" saturatedWallFacePairParked = true,");
        }
        if (unit.battleNetSaturatedRetargetRouteBand()) {
            state.append(" saturatedRetargetRouteBand = true,");
        }
        if (unit.battleNetBuildingFootprintParkCollision()) {
            state.append(" buildingFootprintParkCollision = true,");
        }
        if (unit.battleNetAttackWrapDestArmPending()) {
            state.append(" attackWrapDestArmPending = true,");
        }
        if (unit.battleNetSpatialHitHelpHandoff()) {
            state.append(" spatialHitHelpHandoff = true,");
        }
        if (unit.battleNetRangedCloseHitHelpWallFace()) {
            state.append(" rangedCloseHitHelpWallFace = true,");
        }
        if (unit.battleNetLandPatrolAttackRoutePending()) {
            state.append(" landPatrolAttackRoutePending = true,");
        }
        if (unit.battleNetResidualEmptyApproachIdlePending()) {
            state.append(" residualEmptyApproachIdlePending = true,");
        }
        if (unit.type().gathering().containsKey(UnitType.Resource.OIL)) {
            state.append(" oilAction = ")
                    .append(quote(unit.battleNetOilAction().name())).append(",");
            state.append(" oilActionTicks = ")
                    .append(unit.battleNetOilActionTicks()).append(",");
            state.append(" oilStartedAdjacent = ")
                    .append(unit.battleNetOilStartedAdjacent()).append(",");
        }
        state.append(" threshold = ").append(unit.threshold()).append(",");
        state.append(" underAttack = ").append(unit.underAttack()).append(",");
        state.append(" seenByPlayers = ").append(unit.seenByPlayers()).append(",");
        // A deposit's contents. Without this a gold mine mined down to five
        // thousand came back holding the type's full 25,500, and an oil patch
        // -- whose amount only ever comes from the map -- came back empty.
        state.append(" resourcesHeld = ").append(unit.resourcesHeld()).append(",");
        if (unit.target() != null) {
            state.append(" target = ").append(unit.target().id()).append(",");
        }
        if (unit.isBurning()) {
            state.append(" burning = true,");
        }
        if (unit.walkHolding()) {
            state.append(" walkHolding = true,");
        }
        // Order state the walk and the explorer carry between cycles, written
        // only when it is not at rest, like the spells below. Upstream saves
        // both on the order itself: "range" on every walking order and
        // "waiting-cycle" on the explorer's.
        if (unit.moveRange() != 0) {
            state.append(" moveRange = ").append(unit.moveRange()).append(",");
        }
        if (unit.exploreWaitingCycle() != 0) {
            state.append(" exploreWait = ").append(unit.exploreWaitingCycle()).append(",");
        }
        // The five timed spells. Written only when something is on, so a save
        // of a world where nobody has cast anything is the same size it was.
        // Not doing this is the fault this file was largely rewritten for: a
        // save wrote five things per unit and dropped the other forty, and a
        // new field that nothing persists is that fault happening again. A
        // player who saves mid-fight with their army bloodlusted and their
        // knight under Unholy Armour should not reload to find both spells
        // spent and gone.
        for (Unit.Buff buff : Unit.Buff.values()) {
            if (unit.buff(buff) > 0) {
                state.append(" buff_").append(buff.name()).append(" = ")
                        .append(unit.buff(buff)).append(",");
            }
        }
        if (unit.producing() != null) {
            state.append(" producing = ").append(quote(unit.producing().ident())).append(",");
        }
        if (!unit.trainingQueue().isEmpty()) {
            state.append(" trainingQueue = {");
            for (UnitType queued : unit.trainingQueue()) {
                state.append(quote(queued.ident())).append(", ");
            }
            state.append("},");
        }
        if (unit.researching() != null) {
            state.append(" researching = ").append(quote(unit.researching())).append(",");
        }
        if (unit.upgradingTo() != null) {
            state.append(" upgradingTo = ").append(quote(unit.upgradingTo().ident())).append(",");
        }
        if (unit.pendingTransform() != null) {
            state.append(" pendingTransform = ")
                    .append(quote(unit.pendingTransform().ident())).append(",");
        }
        if (unit.autoCast() != null) {
            state.append(" autoCast = ").append(quote(unit.autoCast())).append(",");
        }
        if (unit.castingSpell() != null) {
            state.append(" castingSpell = ").append(quote(unit.castingSpell())).append(",");
        }
        if (unit.pendingBuild() != null) {
            state.append(" pendingBuild = ").append(quote(unit.pendingBuild().ident())).append(",");
            state.append(" buildTileX = ").append(unit.buildTileX()).append(",");
            state.append(" buildTileY = ").append(unit.buildTileY()).append(",");
            state.append(" buildGoalX = ").append(unit.buildGoalX()).append(",");
            state.append(" buildGoalY = ").append(unit.buildGoalY()).append(",");
        }
        if (!unit.queuedOrders().isEmpty()) {
            if (unit.queuedReplacementPending()) {
                state.append(" queuedReplacementPending = true,");
            }
            state.append(" queuedOrders = {");
            for (Unit.QueuedOrder queued : unit.queuedOrders()) {
                state.append("{ kind = ").append(quote(queued.kind().name()))
                        .append(", x = ").append(queued.x())
                        .append(", y = ").append(queued.y())
                        .append(", target = ")
                        .append(queued.target() == null ? 0 : queued.target().id())
                        .append(", type = ")
                        .append(quote(queued.type() == null ? null : queued.type().ident()))
                        .append(", value = ").append(quote(queued.value()))
                        .append(" }, ");
            }
            state.append("},");
        }
        state.append(" })\n");
        out.write(state.toString());
    }

    /** A native document string literal, or {@code nil} for a missing value. */
    private static String quote(String value) {
        if (value == null) {
            return "nil";
        }
        StringBuilder quoted = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                default -> quoted.append(c);
            }
        }
        return quoted.append('"').toString();
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static String intTable(java.util.List<Integer> values) {
        StringBuilder result = new StringBuilder("{");
        for (int value : values) {
            result.append(value).append(", ");
        }
        return result.append('}').toString();
    }
}
