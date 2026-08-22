package net.chonkbase.chonkcraft.engine.save;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.ai.AiForce;
import net.chonkbase.chonkcraft.engine.ai.AiPlayer;
import net.chonkbase.chonkcraft.engine.missile.Missile;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.trigger.TriggerSystem;

/**
 * Reads a saved game back.
 *
 * <p>The counterpart to {@link SaveGame}, and it works the way upstream's
 * does at the data boundary: the save is a readable sequence of assignments
 * and engine calls. A constrained native reader accepts exactly those data
 * forms; no general-purpose script runtime is involved.
 *
 * <p>Loading happens in two passes, and it has to. The script's first act is
 * to load its map, and the caller is the one who knows how to do that: a
 * campaign mission comes out of the archive, a skirmish map off the disk. So
 * the header is read first, the caller builds a world from it, and then the
 * rest of the script runs against that world.
 */
public final class LoadGame {

    private LoadGame() {
    }

    /**
     * What a save says about itself, before any of it is applied.
     *
     * @param mapPath  the map to rebuild first
     * @param campaign the campaign it belongs to, or null for a skirmish
     * @param mission  the mission number within it, or 0
     * @param seed     the simulation generator's seed
     * @param draws    how many numbers had been drawn from it
     * @param cycle    how far the game had run
     */
    public record Header(String mapPath, String campaign, int mission, long seed, long draws,
            long cycle) {}

    /** Reads a save's text, gzipped or not. */
    public static String read(Path file) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        if (raw.length > 1 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B) {
            try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return new String(raw, StandardCharsets.UTF_8);
    }

    /**
     * Reads only the header.
     *
     * <p>Everything else in the script is stubbed out, so this can be asked of
     * a save without a world to apply it to. That is what a load menu needs:
     * which map, which mission, how far in, without paying to rebuild any of
     * it.
     */
    public static Header header(String script) {
        requireSupportedFormat(script);
        NativeSaveReader reader = new NativeSaveReader();
        Header[] found = new Header[1];
        long[] cycle = new long[1];

        reader.register("SavedGameInfo", args -> {
            if (args.length > 0 && args[0] instanceof SaveTable info) {
                found[0] = new Header(
                        string(info.rawGet("SaveFile")),
                        string(info.rawGet("Campaign")),
                        integer(info.rawGet("Mission")),
                        (long) number(info.rawGet("SyncRandSeed")),
                        (long) number(info.rawGet("SyncRandDraws")),
                        0);
            }
            return new Object[0];
        });
        // Everything the rest of the script calls, doing nothing.
        stubEverything(reader);

        run(reader, script);
        Object gameCycle = reader.globals().rawGet("GameCycle");
        cycle[0] = gameCycle == null ? 0 : (long) number(gameCycle);
        if (found[0] == null) {
            return null;
        }
        return new Header(found[0].mapPath(), found[0].campaign(), found[0].mission(),
                found[0].seed(), found[0].draws(), cycle[0]);
    }

    /**
     * Which of a mission's triggers the save says are still armed.
     *
     * <p>Asked separately from {@link #apply} because the triggers do not live
     * on the world: the caller holds the {@code TriggerSystem} and is the only
     * one that can prune it, and it has to do so <em>after</em> reloading the
     * mission script, which is what re-arms them all in the first place.
     *
     * @return the indices still armed, or null if the save says nothing about
     *     triggers -- which is what a save written before this existed does,
     *     and means "keep what the script gave you"
     */
    public static java.util.List<Integer> armedTriggers(String script) {
        TriggerSystem.SavedState state = triggerState(script);
        return state == null ? null : state.armed();
    }

    /** Reads the complete mutable trigger checkpoint from a version-four save. */
    public static TriggerSystem.SavedState triggerState(String script) {
        requireSupportedFormat(script);
        NativeSaveReader reader = new NativeSaveReader();
        stubEverything(reader);
        java.util.List<Integer>[] found = new java.util.List[1];
        java.util.List<String>[] flags = new java.util.List[1];
        java.util.List<TriggerSystem.SavedDelay>[] delays = new java.util.List[1];
        reader.register("SetArmedTriggers", args -> {
            if (args.length > 0 && args[0] instanceof SaveTable table) {
                java.util.List<Integer> armed = new java.util.ArrayList<>();
                for (Object index : table.array()) {
                    armed.add(integer(index));
                }
                found[0] = armed;
            }
            return new Object[0];
        });
        reader.register("SetTriggerFlags", args -> {
            if (args.length > 0 && args[0] instanceof SaveTable table) {
                java.util.List<String> values = new java.util.ArrayList<>();
                for (Object value : table.array()) {
                    values.add(string(value));
                }
                flags[0] = values;
            }
            return new Object[0];
        });
        reader.register("SetTriggerDelays", args -> {
            if (args.length > 0 && args[0] instanceof SaveTable table) {
                if (table.array().size() % 2 != 0) {
                    throw new IllegalArgumentException("odd saved trigger delay table");
                }
                java.util.List<TriggerSystem.SavedDelay> values =
                        new java.util.ArrayList<>();
                for (int index = 0; index < table.array().size(); index += 2) {
                    values.add(new TriggerSystem.SavedDelay(
                            integer(table.array().get(index)),
                            integer(table.array().get(index + 1))));
                }
                delays[0] = values;
            }
            return new Object[0];
        });
        run(reader, script);
        if (found[0] == null && flags[0] == null && delays[0] == null) {
            return null;
        }
        return new TriggerSystem.SavedState(
                found[0] == null ? java.util.List.of() : found[0],
                flags[0] == null ? java.util.List.of() : flags[0],
                delays[0] == null ? java.util.List.of() : delays[0]);
    }

    /**
     * Applies a save to a world already built from its map.
     *
     * <p>The world must have its terrain and nothing else: the script creates
     * every unit itself, which is the point of the stubbing it does around its
     * own {@code Load} call.
     *
     * @param types the roster, for turning identifiers back into unit types
     */
    public static void apply(World world, String script, java.util.Map<String, UnitType> types) {
        requireSupportedFormat(script);
        NativeSaveReader reader = new NativeSaveReader();
        stubEverything(reader);

        int[] savedSyncSeed = new int[1];
        long[] savedSyncDraws = new long[1];
        int[] savedBattleNetSeed = new int[1];
        long[] savedBattleNetDraws = new long[1];
        boolean[] hasRandomState = new boolean[1];
        boolean[] hasBattleNetRandomState = new boolean[1];

        reader.register("SavedGameInfo", args -> {
            if (args.length > 0 && args[0] instanceof SaveTable info) {
                savedSyncSeed[0] = (int) number(info.rawGet("SyncRandSeed"));
                savedSyncDraws[0] = (long) number(info.rawGet("SyncRandDraws"));
                hasRandomState[0] = info.rawGet("SyncRandSeed") != null;
                if (info.rawGet("BattleNetRandSeed") != null) {
                    savedBattleNetSeed[0] =
                            (int) number(info.rawGet("BattleNetRandSeed"));
                    savedBattleNetDraws[0] =
                            (long) number(info.rawGet("BattleNetRandDraws"));
                    hasBattleNetRandomState[0] = true;
                }
            }
            return new Object[0];
        });

        // The map is the caller's job, so the script's Load does nothing here.
        reader.register("Load", args -> new Object[0]);

        // The most recently created unit, which the SetX calls refer to.
        Unit[] current = new Unit[1];
        java.util.Map<Integer, Unit> unitsBySavedId = new java.util.HashMap<>();
        java.util.Set<Unit> explicitAiBehavior = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());

        reader.register("CreateUnit", args -> {
            if (args.length < 3) {
                return new Object[0];
            }
            UnitType type = types.get(string(args[0]));
            int player = integer(args[1]);
            int x = 0;
            int y = 0;
            if (args[2] instanceof SaveTable at && at.array().size() >= 2) {
                x = (int) number(at.array().get(0));
                y = (int) number(at.array().get(1));
            }
            if (type == null) {
                return new Object[0];
            }
            current[0] = world.restoreUnit(type, player, x, y);
            return new Object[] {current[0] == null ? null : (double) current[0].id()};
        });

        reader.register("SetSavedUnitId", args -> {
            if (current[0] != null && args.length > 1) {
                unitsBySavedId.put(integer(args[1]), current[0]);
            }
            return new Object[0];
        });

        reader.register("SetHitPoints", args -> {
            if (current[0] != null && args.length > 1) {
                current[0].setHitPoints(integer(args[1]));
            }
            return new Object[0];
        });
        reader.register("SetMana", args -> {
            if (current[0] != null && args.length > 1) {
                current[0].setMana(integer(args[1]));
            }
            return new Object[0];
        });
        reader.register("SetResourcesHeld", args -> {
            if (current[0] != null && args.length > 2) {
                UnitType.Resource resource = resourceOf(string(args[1]));
                if (resource != null) {
                    current[0].setCarrying(resource);
                    current[0].setHeldResource(resource);
                    current[0].setCarried(integer(args[2]));
                }
            }
            return new Object[0];
        });
        reader.register("SetConstruction", args -> {
            if (current[0] != null && args.length > 2) {
                current[0].setOrder(Unit.Order.UNDER_CONSTRUCTION);
                current[0].setProgress(integer(args[1]));
                current[0].setProgressGoal(integer(args[2]));
            }
            return new Object[0];
        });

        reader.register("SetContained", args -> {
            if (args.length < 4) {
                return new Object[0];
            }
            Unit unit = unitsBySavedId.get(integer(args[0]));
            Unit container = unitsBySavedId.get(integer(args[1]));
            if (unit == null || container == null) {
                return new Object[0];
            }
            Unit.Order order;
            try {
                order = Unit.Order.valueOf(string(args[3]));
            } catch (IllegalArgumentException e) {
                order = Unit.Order.STILL;
            }
            world.restoreContained(unit, container, "carrier".equals(string(args[2])), order);
            return new Object[0];
        });

        reader.register("SetHarvestState", args -> {
            if (args.length < 6) {
                return new Object[0];
            }
            Unit worker = unitsBySavedId.get(integer(args[0]));
            Unit resource = unitsBySavedId.get(integer(args[1]));
            if (worker != null) {
                world.restoreHarvestState(worker, resource,
                        integer(args[2]), integer(args[3]),
                        truthy(args[4]), integer(args[5]));
                // Older schema-2 saves omit these optional references and
                // safely re-run FindDeposit. New saves resume the exact weak
                // and remembered goals of the BNE resource order.
                if (args.length >= 7) {
                    worker.setResourceDepot(unitsBySavedId.get(integer(args[6])));
                }
                if (args.length >= 8) {
                    worker.setReturnDepotGoal(unitsBySavedId.get(integer(args[7])));
                }
            }
            return new Object[0];
        });

        reader.register("SetUnitState", args -> {
            if (args.length < 2 || !(args[1] instanceof SaveTable state)) {
                return new Object[0];
            }
            Unit unit = unitsBySavedId.get(integer(args[0]));
            if (unit != null) {
                if (state.rawGet("aiBehavior") != null) {
                    explicitAiBehavior.add(unit);
                }
                applyUnitState(unit, state, types, unitsBySavedId);
            }
            return new Object[0];
        });

        reader.register("RestoreMissile", args -> {
            if (args.length < 1 || !(args[0] instanceof SaveTable state)) {
                return new Object[0];
            }
            Unit source = unitsBySavedId.get(integer(state.rawGet("source")));
            Unit target = unitsBySavedId.get(integer(state.rawGet("target")));
            Missile.SavedState saved = new Missile.SavedState(
                    number(state.rawGet("x")), number(state.rawGet("y")),
                    number(state.rawGet("fromX")), number(state.rawGet("fromY")),
                    number(state.rawGet("toX")), number(state.rawGet("toY")),
                    number(state.rawGet("travelled")), number(state.rawGet("total")),
                    integer(state.rawGet("bounces")), truthy(state.rawGet("hit")),
                    integer(state.rawGet("delay")), integer(state.rawGet("damage")),
                    state.rawGet("timeToLive") == null
                            ? -1 : integer(state.rawGet("timeToLive")),
                    truthy(state.rawGet("periodicHit")),
                    truthy(state.rawGet("arrived")), integer(state.rawGet("sleep")),
                    integer(state.rawGet("cycleState")), truthy(state.rawGet("moveStarted")),
                    integer(state.rawGet("frame")), integer(state.rawGet("direction")),
                    truthy(state.rawGet("bneMotion")),
                    truthy(state.rawGet("bneConstructorDrawn")),
                    truthy(state.rawGet("bnePendingImpact")),
                    integer(state.rawGet("bneImpactWait")),
                    integer(state.rawGet("bneSpeed")), integer(state.rawGet("bneError")),
                    integer(state.rawGet("bneMajor")), integer(state.rawGet("bneMinor")),
                    integer(state.rawGet("bneFlags")), integer(state.rawGet("bneRemaining")),
                    integer(state.rawGet("bneArcProgress")),
                    integer(state.rawGet("bneArcStride")),
                    truthy(state.rawGet("bneSkipDraw")),
                    integer(state.rawGet("bnePoolSlot")));
            world.restoreMissile(string(state.rawGet("type")), source, target, saved,
                    (long) number(state.rawGet("startCycle")),
                    (long) number(state.rawGet("queuedCycle")),
                    truthy(state.rawGet("pending")));
            return new Object[0];
        });

        reader.register("SetDiplomacy", args -> {
            if (args.length >= 3) {
                world.setDiplomacy(integer(args[0]), string(args[1]), integer(args[2]));
            }
            return new Object[0];
        });
        reader.register("SetPlayerData", args -> {
            // SetPlayerData(player, "Resources", "gold", amount)
            int index = integer(args.length > 0 ? args[0] : null);
            if (index < 0 || index >= world.players().length || world.player(index) == null) {
                return new Object[0];
            }
            Player player = world.player(index);
            String what = args.length > 1 ? string(args[1]) : null;
            if (args.length >= 4 && "Resources".equals(what)) {
                UnitType.Resource resource = resourceOf(string(args[2]));
                if (resource != null) {
                    player.set(resource, integer(args[3]));
                }
            } else if (args.length >= 3 && what != null) {
                switch (what) {
                    case "Score" -> player.setScore(integer(args[2]));
                    case "TotalKills" -> player.setTotalKills(integer(args[2]));
                    case "TotalRazings" -> player.setTotalRazings(integer(args[2]));
                    default -> {
                        // A field a later version writes. Ignoring it loses
                        // that state; refusing to load loses the whole game.
                    }
                }
            }
            return new Object[0];
        });

        // The ground. SaveGame writes these lines ahead of the units, so a
        // unit is never created on a square the save says was cleared while
        // the map still calls it forest. The call is upstream's map-fields
        // table reduced to the squares that moved; see SaveGame.writeTerrain.
        // A save written before terrain was carried has none of these lines,
        // and the map keeps what its own file gave it, which is exactly what
        // this implementation did before.
        reader.register("SetSavedTile", args -> {
            if (args.length >= 5) {
                world.map().restoreSavedTile(integer(args[0]), integer(args[1]), integer(args[2]),
                        flagWord(args[3]), integer(args[4]),
                        args.length >= 6 ? integer(args[5]) : -1);
            }
            return new Object[0];
        });

        reader.register("SetSharedVision", args -> {
            if (args.length >= 2) {
                world.setSharedVision(integer(args[0]), integer(args[1]), true);
            }
            return new Object[0];
        });

        reader.register("SetExplored", args -> {
            if (args.length >= 2) {
                restoreExplored(world, integer(args[0]), string(args[1]));
            }
            return new Object[0];
        });

        reader.register("RememberBuilding", args -> {
            if (args.length >= 5) {
                UnitType type = types.get(string(args[1]));
                if (type != null) {
                    world.seenBuildings().remember(integer(args[0]),
                            new net.chonkbase.chonkcraft.engine.map.SeenBuildings.Memory(
                                    type, integer(args[2]), integer(args[3]), integer(args[4]),
                                    args.length > 5 ? integer(args[5]) : 0,
                                    args.length > 6 && truthy(args[6]),
                                    args.length > 7 && truthy(args[7]),
                                    args.length > 8 ? number(args[8]) : 1.0));
                }
            }
            return new Object[0];
        });

        reader.register("DefineAiPlayerState", args -> {
            if (args.length >= 1) {
                var ai = world.enableAi(integer(args[0]));
                if (args.length >= 2) {
                    ai.setSleepUntilCycle((long) number(args[1]));
                }
                if (args.length >= 3) {
                    ai.setUsePlan(truthy(args[2]));
                }
                if (args.length >= 5) {
                    ai.restoreScriptPosition(integer(args[3]), integer(args[4]));
                }
            }
            return new Object[0];
        });
        reader.register("RestoreBattleNetAiState", args -> {
            if (args.length < 11
                    || !(args[7] instanceof SaveTable candidates)
                    || !(args[8] instanceof SaveTable resolved)) {
                return new Object[0];
            }
            byte[] state = bytesOf(string(args[3]));
            if (state == null) {
                return new Object[0];
            }
            world.enableAi(integer(args[0])).restoreBattleNetState(
                    new AiPlayer.BattleNetSavedState(
                            integer(args[1]), integer(args[2]), state,
                            integer(args[4]), integer(args[5]), integer(args[6]),
                            integers(candidates), integers(resolved),
                            (long) number(args[9]), (long) number(args[10])));
            return new Object[0];
        });
        reader.register("RestoreAiForce", args -> {
            if (args.length < 9
                    || !(args[2] instanceof SaveTable wantedTable)
                    || !(args[3] instanceof SaveTable memberTable)) {
                return new Object[0];
            }
            java.util.Map<UnitType, Integer> wanted = new java.util.LinkedHashMap<>();
            java.util.List<Object> wantedFields = wantedTable.array();
            for (int i = 0; i + 1 < wantedFields.size(); i += 2) {
                UnitType type = types.get(string(wantedFields.get(i)));
                if (type != null) {
                    wanted.put(type, integer(wantedFields.get(i + 1)));
                }
            }
            java.util.List<Unit> members = new java.util.ArrayList<>();
            for (Object id : memberTable.array()) {
                Unit member = unitsBySavedId.get(integer(id));
                if (member != null) {
                    members.add(member);
                }
            }
            AiForce.State state;
            try {
                state = AiForce.State.valueOf(string(args[4]));
            } catch (IllegalArgumentException | NullPointerException unknown) {
                state = AiForce.State.GATHERING;
            }
            world.enableAi(integer(args[0])).restoreForce(
                    integer(args[1]), wanted, members, state, truthy(args[5]),
                    integer(args[6]), integer(args[7]), integer(args[8]));
            return new Object[0];
        });
        reader.register("AiPlayerNeeds", args -> {
            if (args.length >= 3) {
                UnitType type = types.get(string(args[1]));
                if (type != null) {
                    world.enableAi(integer(args[0])).need(type, integer(args[2]));
                }
            }
            return new Object[0];
        });
        reader.register("AiPlayerWants", args -> {
            if (args.length >= 3) {
                UnitType type = types.get(string(args[1]));
                if (type != null) {
                    world.enableAi(integer(args[0]))
                            .insertUnitTypeRequest(type, integer(args[2]));
                }
            }
            return new Object[0];
        });
        reader.register("AiPlayerResearches", args -> {
            if (args.length >= 2) {
                world.enableAi(integer(args[0])).research(string(args[1]));
            }
            return new Object[0];
        });

        reader.register("SetUpgrade", args -> {
            if (args.length >= 2) {
                var state = world.upgrades(integer(args[0]));
                if (state != null) {
                    state.complete(string(args[1]));
                }
            }
            return new Object[0];
        });

        reader.register("DefineAllow", args -> {
            var allowed = world.allowed();
            if (allowed == null) {
                allowed = new net.chonkbase.chonkcraft.engine.upgrade.AllowState();
                world.setAllowed(allowed);
            }
            for (int i = 0; i + 1 < args.length; i += 2) {
                allowed.define(string(args[i]), string(args[i + 1]));
            }
            return new Object[0];
        });

        run(reader, script);

        repairLegacyAiAssaultPatrols(world, explicitAiBehavior);

        // Schema-two saves could name runtime-only wood/rock transition
        // codes. Their flags have now all been restored, so any such picture
        // can be reconstructed against its complete neighbourhood.
        world.map().finishSavedTerrainRestore();

        // Unit construction can initialize animations and consume the BNE
        // asynchronous stream. The save names the state at the save boundary,
        // so install both generators after the reconstruction script, not
        // before it.
        if (hasRandomState[0]) {
            world.restoreRandom(savedSyncSeed[0], savedSyncDraws[0]);
        }
        if (hasBattleNetRandomState[0]) {
            world.restoreBattleNetRandom(savedBattleNetSeed[0], savedBattleNetDraws[0]);
        }

        Object gameCycle = reader.globals().rawGet("GameCycle");
        if (gameCycle != null) {
            world.setCycle((long) number(gameCycle));
        }
        world.repairRestoredOilOrders();
        world.recalculateSupply();
    }

    /**
     * Recovers the assault marker omitted by saves written before it was durable.
     *
     * <p>BNE AI force marches travel as Patrol toward the selected hostile's
     * tile. Without behavior two, the first empty compact route is interpreted
     * as the end of a map-authored patrol and the whole assault becomes Still.
     * A legacy save cannot name the missing marker, but an AI-owned Patrol whose
     * destination is an actual hostile unit retains the force march's decisive
     * external evidence. If that hostile has since vanished, a nearby rally
     * endpoint paired with a far destination retains the force's construction
     * shape. Newly written saves carry behavior zero explicitly, so a deliberate
     * map patrol is never inferred through this migration path.
     */
    private static void repairLegacyAiAssaultPatrols(World world,
            java.util.Set<Unit> explicitAiBehavior) {
        for (Unit unit : world.units()) {
            if (explicitAiBehavior.contains(unit)
                    || unit.order() != Unit.Order.PATROL
                    || unit.battleNetAiBehavior() > 1
                    || !world.ais().containsKey(unit.player())
                    || !world.map().contains(unit.orderTargetX(), unit.orderTargetY())) {
                continue;
            }
            Unit hostile = world.unitAt(unit.orderTargetX(), unit.orderTargetY());
            boolean liveHostile = hostile != null && hostile.isAlive()
                    && hostile.isOnMap()
                    && world.isEnemyPlayer(unit.player(), hostile.player());
            int goalDistance = Math.max(
                    Math.abs(unit.tileX() - unit.orderTargetX()),
                    Math.abs(unit.tileY() - unit.orderTargetY()));
            int backDistance = Math.max(
                    Math.abs(unit.tileX() - unit.patrolX()),
                    Math.abs(unit.tileY() - unit.patrolY()));
            // A legacy assault can outlive the hostile that originally
            // supplied its far endpoint. The captured Human 6 save has the
            // packed force seven tiles from its rally point and sixty from
            // that now-empty endpoint. RestoreUnit constructs modern AI
            // combatants as behavior one, so requiring zero or a still-live
            // target made this whole saved army go Still on its first tick.
            // The tight near-back/far-goal shape is the durable force-march
            // evidence; newly written behavior-zero map Patrols are excluded
            // above through explicitAiBehavior.
            boolean legacyForceMarch = unit.battleNetAiBehavior() <= 1
                    && unit.patrolX() >= 0 && unit.patrolY() >= 0
                    && backDistance <= 8 && goalDistance >= 16;
            if (!liveHostile && !legacyForceMarch) {
                continue;
            }
            unit.setBattleNetAiBehavior(2);
            unit.setBattleNetAiHome(unit.orderTargetX(), unit.orderTargetY());
        }
    }

    /**
     * Puts back everything {@code SaveGame.writeUnitState} wrote.
     *
     * <p>Every field is optional. A save written by an earlier version simply
     * has fewer keys, and a unit keeps whatever {@code CreateUnit} gave it for
     * the ones that are missing, which is the same state this implementation restored
     * before any of these were written at all.
     */
    private static void applyUnitState(Unit unit, SaveTable state,
            java.util.Map<String, UnitType> types, java.util.Map<Integer, Unit> byId) {
        Object order = state.rawGet("order");
        if (order != null) {
            try {
                unit.setOrder(Unit.Order.valueOf(string(order)));
            } catch (IllegalArgumentException unknownOrder) {
                // An order this version does not have. Standing still is the
                // safe reading: the unit is there and can be given a new one.
                unit.setOrder(Unit.Order.STILL);
            }
        }
        if (state.rawGet("heading") != null) {
            unit.setHeading(integer(state.rawGet("heading")));
        }
        if (state.rawGet("offsetX") != null) {
            unit.setOffset(integer(state.rawGet("offsetX")), integer(state.rawGet("offsetY")));
        }
        if (state.rawGet("pathGoalX") != null) {
            unit.setPathGoal(integer(state.rawGet("pathGoalX")),
                    integer(state.rawGet("pathGoalY")));
        }
        if (state.rawGet("orderTargetX") != null) {
            unit.setOrderTarget(integer(state.rawGet("orderTargetX")),
                    integer(state.rawGet("orderTargetY")));
        }
        if (state.rawGet("attackMoveX") != null) {
            unit.setAttackMove(integer(state.rawGet("attackMoveX")),
                    integer(state.rawGet("attackMoveY")));
        }
        if (state.rawGet("aiBehavior") != null) {
            unit.setBattleNetAiBehavior(integer(state.rawGet("aiBehavior")));
        }
        if (state.rawGet("aiHomeX") != null) {
            unit.setBattleNetAiHome(integer(state.rawGet("aiHomeX")),
                    integer(state.rawGet("aiHomeY")));
        }
        if (state.rawGet("carrying") != null) {
            UnitType.Resource carrying = resourceOf(string(state.rawGet("carrying")));
            if (carrying != null) {
                unit.setCarrying(carrying);
            }
        }
        if (state.rawGet("heldResource") != null) {
            UnitType.Resource held = resourceOf(string(state.rawGet("heldResource")));
            if (held != null) {
                unit.setHeldResource(held);
            }
        }
        if (state.rawGet("savedOrder") != null) {
            try {
                unit.setSavedOrder(Unit.Order.valueOf(string(state.rawGet("savedOrder"))));
            } catch (IllegalArgumentException unknownOrder) {
                unit.setSavedOrder(null);
            }
        }
        if (unit.savedOrder() == Unit.Order.ATTACK_MOVE
                && state.rawGet("savedAttackMoveX") != null) {
            unit.setSavedAttackMove(integer(state.rawGet("savedAttackMoveX")),
                    integer(state.rawGet("savedAttackMoveY")));
            if (state.rawGet("savedMoveRange") != null) {
                unit.setSavedMoveRange(integer(state.rawGet("savedMoveRange")));
            }
            if (state.rawGet("savedAttackScanSleep") != null) {
                unit.setSavedAttackScanSleep(
                        integer(state.rawGet("savedAttackScanSleep")));
            }
            if (state.rawGet("savedAttackMoveOpening") != null) {
                unit.setSavedAttackMoveOpening(
                        truthy(state.rawGet("savedAttackMoveOpening")));
            }
        }
        if (state.rawGet("rallyX") != null) {
            int rallyX = integer(state.rawGet("rallyX"));
            int rallyY = integer(state.rawGet("rallyY"));
            if (rallyX >= 0 && rallyY >= 0) {
                unit.setRallyPoint(rallyX, rallyY);
            }
        }
        if (state.rawGet("patrolX") != null) {
            unit.setPatrol(integer(state.rawGet("patrolX")), integer(state.rawGet("patrolY")));
        }
        if (state.rawGet("progress") != null) {
            unit.setProgress(integer(state.rawGet("progress")));
        }
        if (state.rawGet("progressGoal") != null) {
            unit.setProgressGoal(integer(state.rawGet("progressGoal")));
        }
        if (state.rawGet("wait") != null) {
            unit.setWaitCycles(integer(state.rawGet("wait")));
        }
        if (state.rawGet("rescuedFrom") != null) {
            unit.setRescuedFrom(integer(state.rawGet("rescuedFrom")));
        }
        if (state.rawGet("battleNetSequenceOffset") != null) {
            unit.setBattleNetSequenceOffset(
                    integer(state.rawGet("battleNetSequenceOffset")));
            unit.setBattleNetAnimationTimer(
                    integer(state.rawGet("battleNetAnimationTimer")));
        }
        if (state.rawGet("animationState") != null
                && unit.type().animationSet() != null) {
            try {
                net.chonkbase.chonkcraft.engine.animation.AnimationSet.State animationState =
                        net.chonkbase.chonkcraft.engine.animation.AnimationSet.State.valueOf(
                                string(state.rawGet("animationState")));
                unit.animation().restore(
                        unit.type().animationSet().get(animationState),
                        integer(state.rawGet("animationIndex")),
                        integer(state.rawGet("animationWait")),
                        truthy(state.rawGet("animationUnbreakable")));
            } catch (IllegalArgumentException ignored) {
                // A later version may add an animation state. The order still
                // survives and will select its normal presentation next tick.
            }
        }
        if (state.rawGet("battleNetOrderDelay") != null) {
            unit.setBattleNetOrderDelay(
                    integer(state.rawGet("battleNetOrderDelay")));
        }
        if (state.rawGet("woodReadyPathRequired") != null) {
            unit.setBattleNetWoodReadyPathRequired(
                    truthy(state.rawGet("woodReadyPathRequired")));
        }
        if (state.rawGet("rangedAttackCadenceRemaining") != null) {
            unit.setBattleNetRangedAttackCadenceRemaining(integer(
                    state.rawGet("rangedAttackCadenceRemaining")));
        }
        if (state.rawGet("battleNetRefusals") != null) {
            unit.setBattleNetRefusals(
                    integer(state.rawGet("battleNetRefusals")));
        }
        if (state.rawGet("attackRefusalRecoveryStage") != null) {
            unit.setBattleNetAttackRefusalRecoveryStage(
                    integer(state.rawGet("attackRefusalRecoveryStage")));
        }
        if (state.rawGet("actionBeforeQueued") != null) {
            try {
                Unit.Order before = Unit.Order.valueOf(
                        string(state.rawGet("actionBeforeQueued")));
                int release = state.rawGet("actionBeforeQueuedReleaseDelay") == null
                        ? 3
                        : integer(state.rawGet("actionBeforeQueuedReleaseDelay"));
                unit.rememberActionBeforeQueued(before, release);
            } catch (IllegalArgumentException ignored) {
                unit.setActionBeforeQueued(null);
            }
        }
        if (state.rawGet("playerCommandMove") != null) {
            unit.setBattleNetPlayerCommandMove(
                    truthy(state.rawGet("playerCommandMove")));
        }
        if (state.rawGet("attackGroundMove") != null) {
            unit.setBattleNetAttackGroundMove(
                    truthy(state.rawGet("attackGroundMove")));
        }
        if (state.rawGet("stopAfterLeftover") != null) {
            unit.setBattleNetStopAfterLeftover(
                    truthy(state.rawGet("stopAfterLeftover")));
        }
        if (state.rawGet("saturatedResidualFaceRetry") != null) {
            unit.setBattleNetSaturatedResidualFaceRetry(
                    truthy(state.rawGet("saturatedResidualFaceRetry")));
        }
        if (state.rawGet("directRefusalRecoveryProbe") != null) {
            unit.setBattleNetDirectRefusalRecoveryProbe(
                    truthy(state.rawGet("directRefusalRecoveryProbe")));
        }
        if (state.rawGet("retargetResidualParkRefill") != null) {
            unit.setBattleNetRetargetResidualParkRefill(
                    truthy(state.rawGet("retargetResidualParkRefill")));
            if (state.rawGet("retargetResidualParkSteps") != null) {
                unit.setBattleNetRetargetResidualParkSteps(
                        integer(state.rawGet("retargetResidualParkSteps")));
            }
        }
        if (state.rawGet("oilAction") != null) {
            try {
                unit.setBattleNetOilAction(Unit.BattleNetOilAction.valueOf(
                        string(state.rawGet("oilAction"))));
            } catch (IllegalArgumentException ignored) {
                unit.setBattleNetOilAction(Unit.BattleNetOilAction.TO_RESOURCE);
            }
        }
        if (state.rawGet("oilActionTicks") != null) {
            unit.setBattleNetOilActionTicks(integer(state.rawGet("oilActionTicks")));
        }
        if (state.rawGet("oilStartedAdjacent") != null) {
            unit.setBattleNetOilStartedAdjacent(
                    truthy(state.rawGet("oilStartedAdjacent")));
        }
        if (state.rawGet("threshold") != null) {
            unit.setThreshold(integer(state.rawGet("threshold")));
        }
        if (state.rawGet("underAttack") != null) {
            unit.setUnderAttack(integer(state.rawGet("underAttack")));
        }
        if (state.rawGet("seenByPlayers") != null) {
            unit.setSeenByPlayers(integer(state.rawGet("seenByPlayers")));
        }
        if (state.rawGet("resourcesHeld") != null) {
            unit.setResourcesHeld(integer(state.rawGet("resourcesHeld")));
        }
        if (state.rawGet("target") != null) {
            unit.setTarget(byId.get(integer(state.rawGet("target"))));
        }
        // Restore this after the target. Unit.setTarget deliberately clears a
        // marker inherited from a different quarry.
        if (state.rawGet("attackWaitRefillResidual") != null) {
            unit.setBattleNetAttackWaitRefillResidual(
                    truthy(state.rawGet("attackWaitRefillResidual")));
        }
        if (state.rawGet("movingQuarryResidual") != null) {
            unit.setBattleNetMovingQuarryResidual(
                    truthy(state.rawGet("movingQuarryResidual")));
        }
        if (state.rawGet("attackWrapDestArmPending") != null) {
            unit.setBattleNetAttackWrapDestArmPending(
                    truthy(state.rawGet("attackWrapDestArmPending")));
        }
        if (state.rawGet("spatialHitHelpHandoff") != null) {
            unit.setBattleNetSpatialHitHelpHandoff(
                    truthy(state.rawGet("spatialHitHelpHandoff")));
        }
        if (state.rawGet("landPatrolAttackRoutePending") != null) {
            unit.setBattleNetLandPatrolAttackRoutePending(
                    truthy(state.rawGet("landPatrolAttackRoutePending")));
        }
        if (state.rawGet("residualEmptyApproachIdlePending") != null) {
            unit.setBattleNetResidualEmptyApproachIdlePending(truthy(
                    state.rawGet("residualEmptyApproachIdlePending")));
        }
        if (truthy(state.rawGet("burning"))) {
            unit.setBurning(true);
        }
        if (truthy(state.rawGet("walkHolding"))) {
            unit.setWalkHolding(true);
        }
        if (state.rawGet("moveRange") != null) {
            unit.setMoveRange(integer(state.rawGet("moveRange")));
        }
        if (state.rawGet("exploreWait") != null) {
            unit.setExploreWaitingCycle(integer(state.rawGet("exploreWait")));
        }
        // Read key by key, as everything here is, so a save written before the
        // spells existed simply has none of these and the unit comes back with
        // every timer at nought -- which is what that save meant.
        for (Unit.Buff buff : Unit.Buff.values()) {
            Object cycles = state.rawGet("buff_" + buff.name());
            if (cycles != null) {
                unit.setBuff(buff, integer(cycles));
            }
        }
        if (state.rawGet("producing") != null) {
            unit.setProducing(types.get(string(state.rawGet("producing"))));
        }
        if (state.rawGet("trainingQueue") instanceof SaveTable queue) {
            for (Object ident : queue.array()) {
                UnitType type = types.get(string(ident));
                if (type != null) {
                    unit.enqueueTraining(type);
                }
            }
        }
        if (state.rawGet("researching") != null) {
            unit.setResearching(string(state.rawGet("researching")));
        }
        if (state.rawGet("upgradingTo") != null) {
            unit.setUpgradingTo(types.get(string(state.rawGet("upgradingTo"))));
        }
        if (state.rawGet("pendingTransform") != null) {
            unit.setPendingTransform(types.get(string(state.rawGet("pendingTransform"))));
        }
        if (state.rawGet("autoCast") != null) {
            unit.setAutoCast(string(state.rawGet("autoCast")));
        }
        if (state.rawGet("castingSpell") != null) {
            unit.setCastingSpell(string(state.rawGet("castingSpell")));
        }
        if (state.rawGet("pendingBuild") != null) {
            unit.setPendingBuild(types.get(string(state.rawGet("pendingBuild"))));
            unit.setBuildTile(integer(state.rawGet("buildTileX")),
                    integer(state.rawGet("buildTileY")));
            if (state.rawGet("buildGoalX") != null) {
                unit.setBuildGoal(integer(state.rawGet("buildGoalX")),
                        integer(state.rawGet("buildGoalY")));
            }
        }
        if (state.rawGet("queuedOrders") instanceof SaveTable queued) {
            for (Object entry : queued.array()) {
                if (entry instanceof SaveTable order2) {
                    Unit.QueuedOrderKind kind;
                    try {
                        kind = Unit.QueuedOrderKind.valueOf(string(order2.rawGet("kind")));
                    } catch (IllegalArgumentException | NullPointerException unknown) {
                        continue;
                    }
                    unit.enqueueOrder(new Unit.QueuedOrder(kind,
                            integer(order2.rawGet("x")), integer(order2.rawGet("y")),
                            byId.get(integer(order2.rawGet("target"))),
                            types.get(string(order2.rawGet("type"))),
                            string(order2.rawGet("value"))));
                }
            }
            unit.setQueuedReplacementPending(
                    truthy(state.rawGet("queuedReplacementPending")));
        }
    }

    /**
     * Marks a player's explored ground back in, from the run lengths
     * {@code SaveGame.runLengths} wrote.
     *
     * <p>A square is marked by lighting it and putting the light out again.
     * {@code FogOfWar} counts sight rather than flagging it, and exploration
     * is the sticky half of that count: adding one unit of sight at range zero
     * touches exactly the one square and sets its explored bit, and taking it
     * away leaves the bit set. That keeps this to the class's public surface
     * and, because the two calls balance, it does not matter whether it runs
     * before or after the units light their own surroundings.
     */
    private static void restoreExplored(World world, int player, String runs) {
        if (runs == null || player < 0 || player >= world.fog().playerCount()) {
            return;
        }
        int width = world.map().width();
        int height = world.map().height();
        int tile = 0;
        boolean explored = false;
        for (String field : runs.split(",")) {
            int run;
            try {
                run = Integer.parseInt(field.trim());
            } catch (NumberFormatException malformed) {
                return;
            }
            if (explored) {
                for (int i = 0; i < run && tile + i < width * height; i++) {
                    int index = tile + i;
                    world.fog().addSight(player, index % width, index / width, 1, 1, 0);
                    world.fog().removeSight(player, index % width, index / width, 1, 1, 0);
                }
            }
            tile += run;
            explored = !explored;
            if (tile >= width * height) {
                return;
            }
        }
    }

    /**
     * Binds every name a save can mention to a function that does nothing.
     *
     * <p>Scripts written by a later version of this implementation may say things this
     * one does not understand. Ignoring them loses that state; failing to load
     * loses the whole game. The first is the lesser harm, and it is what
     * upstream does with the same problem.
     */
    private static void stubEverything(NativeSaveReader reader) {
        // Unknown calls are ignored by NativeSaveReader for forward compatibility.
    }

    /** Accepts schema 4 plus historical schemas 1–3 for migration. */
    private static void requireSupportedFormat(String source) {
        NativeSaveReader reader = new NativeSaveReader();
        int[] version = {1};
        reader.register("SaveFormat", args -> {
            if (args.length != 2 || !"chonkcraft-save".equals(string(args[0]))) {
                throw new IllegalArgumentException("unsupported save format identity");
            }
            version[0] = integer(args[1]);
            return new Object[0];
        });
        reader.run(source);
        if (version[0] != 1 && version[0] != 2 && version[0] != 3
                && version[0] != 4) {
            throw new IllegalArgumentException("unsupported save format version " + version[0]);
        }
    }

    private static void run(NativeSaveReader reader, String script) {
        reader.run(script);
    }

    private static UnitType.Resource resourceOf(String name) {
        if (name == null) {
            return null;
        }
        for (UnitType.Resource resource : UnitType.Resource.values()) {
            if (resource.name().equalsIgnoreCase(name)) {
                return resource;
            }
        }
        return null;
    }

    private static String string(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            double parsed = number.doubleValue();
            return parsed == Math.rint(parsed)
                    ? Long.toString((long) parsed) : Double.toString(parsed);
        }
        return String.valueOf(value);
    }

    private static double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static int integer(Object value) {
        return (int) number(value);
    }

    /**
     * A terrain flag word, written as hex because the legacy numeric type was a
     * double and the per-subtile impassability mask starts above bit 48.
     *
     * <p>A number is accepted as well, so that a save hand-edited by somebody
     * diagnosing it -- which is the reason the format is a script at all --
     * still loads.
     */
    private static long flagWord(Object value) {
        if (value instanceof String text) {
            String digits = text.trim();
            boolean hex = digits.startsWith("0x") || digits.startsWith("0X");
            try {
                return Long.parseUnsignedLong(hex ? digits.substring(2) : digits, hex ? 16 : 10);
            } catch (NumberFormatException notAFlagWord) {
                return 0;
            }
        }
        return (long) number(value);
    }

    private static boolean truthy(Object value) {
        return value instanceof Boolean flag ? flag : value != null;
    }

    private static byte[] bytesOf(String hex) {
        if (hex == null || (hex.length() & 1) != 0) {
            return null;
        }
        byte[] bytes = new byte[hex.length() / 2];
        try {
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) Integer.parseInt(hex, i * 2, i * 2 + 2, 16);
            }
        } catch (NumberFormatException malformed) {
            return null;
        }
        return bytes;
    }

    private static java.util.List<Integer> integers(SaveTable table) {
        java.util.List<Integer> values = new java.util.ArrayList<>();
        for (Object value : table.array()) {
            values.add(integer(value));
        }
        return values;
    }

    /** The name a save carries, without its suffix. */
    public static String nameOf(Path file) {
        String name = file.getFileName().toString();
        return name.toLowerCase(Locale.ROOT).endsWith(SaveGame.SUFFIX)
                ? name.substring(0, name.length() - SaveGame.SUFFIX.length())
                : name;
    }
}
