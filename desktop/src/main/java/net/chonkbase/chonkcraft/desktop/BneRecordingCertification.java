package net.chonkbase.chonkcraft.desktop;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.chonkbase.assetpack.Json;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.map.PudReader;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol;

/** Replays one sealed multiplayer flight record through the current engine. */
public final class BneRecordingCertification {

    private static final Pattern SAVED_AI = Pattern.compile(
            "(?m)^DefineAiPlayerState\\((\\d+),");

    private BneRecordingCertification() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException(
                    "usage: BneRecordingCertification RECORDING_DIRECTORY");
        }
        Path directory = Path.of(args[0]);
        try (AssetSource assets = requireSource(AssetSource.fromEnvironment())) {
            Certification certification = certify(directory, assets);
            System.out.print(Json.write(certification.report()));
            if (!certification.replay().exact()) {
                throw new IllegalStateException("recording replay diverged at network cycle "
                        + certification.replay().firstMismatchNetCycle() + ": "
                        + certification.replay().failure());
            }
            if (!Boolean.TRUE.equals(certification.report().get("complete"))) {
                throw new IllegalStateException(
                        "recording replay is exact but its evidence identities are incomplete");
            }
        }
    }

    record Certification(MultiplayerRecording.Validated recording,
            MultiplayerRecording.Replay replay, Map<String, Object> report) {}

    /** Reconstructs and referees one recording against an authenticated data source. */
    static Certification certify(Path directory, AssetSource assets) throws Exception {
        MultiplayerRecording.Validated recording = MultiplayerRecording.validate(directory);
        byte[] mapBytes = Files.readAllBytes(recording.map().path());
        PudMap source = PudReader.read(mapBytes);
        PlayerTable table = playerTable(recording, source);
        GameData data = new GameData(assets);
        World world = new World(
                GameMap.from(source, data.loadTileset(source.tileset()).tileset()),
                Player.forNetworkGame(source, table.types(), table.races()));
        data.configureWorld(world, source);

        // The cycle-zero save contains the live AI program counter, but the
        // bytecode itself remains authenticated game data. Install each map
        // profile before applying the save so RestoreBattleNetAiState can
        // reject a mismatched profile rather than running detached bytes.
        world.enableAiForComputerPlayers();
        data.attachRetailAi(world, source, Map.of());
        String save = net.chonkbase.chonkcraft.engine.save.LoadGame.read(
                recording.initialSave().path());
        net.chonkbase.chonkcraft.engine.save.LoadGame.apply(
                world, save, data.unitTypes().types());
        world.setPlayerSiegeBuildingTargetLockEnabled(true);
        world.recalculateSupply();

        List<UnitType> roster = new ArrayList<>(data.unitTypes().types().values());
        CommandApplier applier = new CommandApplier(world, roster);
        data.configureCommands(applier);
        MultiplayerRecording.Replay replay = MultiplayerRecording.replay(
                recording, world, applier);
        return new Certification(recording, replay,
                report(recording, replay, table.authority()));
    }

    private record PlayerTable(PudMap.PlayerType[] types, PudMap.Race[] races,
            String authority) {}

    private static PlayerTable playerTable(MultiplayerRecording.Validated recording,
            PudMap source) throws Exception {
        PudMap.PlayerType[] types = new PudMap.PlayerType[PudMap.PLAYER_MAX];
        PudMap.Race[] races = new PudMap.Race[PudMap.PLAYER_MAX];
        if (recording.rosterAuthenticated()) {
            for (MultiplayerRecording.PlayerSpec player : recording.players()) {
                types[player.index()] = player.type();
                races[player.index()] = player.race();
            }
            return new PlayerTable(types, races, "sealed-manifest");
        }

        // Schema 1 predates the player table. It can still be a useful held-out
        // diagnostic, but it cannot become certified evidence: controller and
        // race choices are reconstructed from the map, cycle-zero bootstrap
        // quits, and saved AI state rather than authenticated fields.
        for (int index = 0; index < PudMap.PLAYER_MAX; index++) {
            races[index] = source.races()[index];
            types[index] = source.players()[index] == PudMap.PlayerType.NEUTRAL
                    ? PudMap.PlayerType.NEUTRAL : PudMap.PlayerType.NOBODY;
        }
        boolean[] bootstrapQuit = new boolean[PudMap.PLAYER_MAX];
        try (BufferedReader in = Files.newBufferedReader(recording.commandStream().path(),
                StandardCharsets.UTF_8)) {
            String first = in.readLine();
            if (first != null) {
                MultiplayerRecording.Cycle cycle = MultiplayerRecording.decode(first, 0,
                        recording.initialWorldCycle(), recording.cyclesPerUpdate());
                for (GameCommand command : cycle.commands()) {
                    if (command.kind() == GameCommand.Kind.QUIT
                            && command.departureReason()
                                    == GameCommand.DepartureReason.BOOTSTRAP) {
                        bootstrapQuit[command.player()] = true;
                    }
                }
            }
        }
        for (int index = 0; index < PudMap.PLAYER_MAX; index++) {
            if (!bootstrapQuit[index]
                    && source.players()[index] != PudMap.PlayerType.NEUTRAL
                    && source.players()[index] != PudMap.PlayerType.NOBODY) {
                types[index] = PudMap.PlayerType.PERSON;
            }
        }
        String save = net.chonkbase.chonkcraft.engine.save.LoadGame.read(
                recording.initialSave().path());
        Matcher ai = SAVED_AI.matcher(save);
        while (ai.find()) {
            int index = Integer.parseInt(ai.group(1));
            if (index >= 0 && index < PudMap.PLAYER_MAX) {
                types[index] = PudMap.PlayerType.COMPUTER;
            }
        }
        return new PlayerTable(types, races, "legacy-inferred-noncertifying");
    }

    private static Map<String, Object> report(MultiplayerRecording.Validated recording,
            MultiplayerRecording.Replay replay, String playerAuthority) throws Exception {
        RefereeIdentity referee = refereeIdentity();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", "chonkcraft-multiplayer-recording-certification-1");
        report.put("complete", recording.currentSchemaComplete()
                && recording.runtime().sourceBound() && replay.exact()
                && referee.sourceBound());
        report.put("simulation_exact", replay.exact());
        report.put("recording_schema", recording.schema());
        report.put("sync_hash_schema", recording.syncHashSchema());
        report.put("referee_sync_hash_schema",
                net.chonkbase.chonkcraft.engine.network.SyncHash.SCHEMA);
        report.put("player_table_authority", playerAuthority);
        report.put("artifact_sealed", recording.artifactSealed());
        report.put("recorded_runtime_source_bound", recording.runtime().sourceBound());
        report.put("build", recording.build());
        report.put("source_revision", recording.runtime().sourceRevision());
        report.put("game_jar_sha256", recording.runtime().gameJarSha256());
        report.put("manifest_sha256", recording.manifestSha256());
        report.put("map", recording.mapName());
        report.put("map_sha256", recording.mapSha256());
        report.put("initial_save_sha256", recording.initialSave().sha256());
        report.put("command_stream_sha256", recording.commandStream().sha256());
        report.put("recorded_net_cycles", recording.recordedNetCycles());
        report.put("recorded_world_cycles",
                recording.finalWorldCycle() - recording.initialWorldCycle());
        report.put("recorded_seconds",
                (recording.finalWorldCycle() - recording.initialWorldCycle())
                        / (double) recording.cyclesPerSecond());
        report.put("recorded_commands", recording.recordedCommands());
        Map<String, Long> families = new java.util.TreeMap<>();
        for (var entry : recording.commandFamilies().entrySet()) {
            families.put(entry.getKey().name(), entry.getValue());
        }
        report.put("command_families", families);
        report.put("replayed_net_cycles", replay.replayedNetCycles());
        report.put("replayed_commands", replay.replayedCommands());
        report.put("final_world_cycle", replay.finalWorldCycle());
        report.put("final_sync_hash", MultiplayerRecording.syncHex(replay.finalSyncHash()));
        report.put("first_mismatch_net_cycle", replay.firstMismatchNetCycle());
        report.put("expected_sync_hash", replay.expectedSyncHash() == null ? null
                : MultiplayerRecording.syncHex(replay.expectedSyncHash()));
        report.put("actual_sync_hash", replay.actualSyncHash() == null ? null
                : MultiplayerRecording.syncHex(replay.actualSyncHash()));
        report.put("failure", replay.failure());
        report.put("referee_code_sha256", referee.gameJarSha256());
        report.put("referee_source_revision", referee.sourceRevision());
        report.put("referee_source_authority", referee.authority());
        report.put("referee_source_bound", referee.sourceBound());
        return report;
    }

    private record RefereeIdentity(String gameJarSha256, String sourceRevision,
            String authority) {
        boolean sourceBound() {
            return gameJarSha256 != null && sourceRevision != null;
        }
    }

    private static RefereeIdentity refereeIdentity() {
        try {
            var source = BneRecordingCertification.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return new RefereeIdentity(null, null, "unavailable");
            }
            Path path = Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
                return new RefereeIdentity(null, null, "development-classes");
            }
            String hash = MultiplayerRecording.sha256(path);
            PassiveMultiplayerRecorder.RuntimeIdentity installed =
                    PassiveMultiplayerRecorder.RuntimeIdentity.from(
                            path, MatchmakingProtocol.gameBuild());
            if (installed.sourceRevision() != null) {
                return new RefereeIdentity(hash, installed.sourceRevision(),
                        "verified-installed-release");
            }
            String revision = System.getProperty(
                    "chonkcraft.source.revision", "").trim();
            boolean clean = Boolean.getBoolean("chonkcraft.source.clean");
            if (clean && revision.matches("[0-9a-f]{40}")) {
                return new RefereeIdentity(hash, revision, "clean-checkout");
            }
            return new RefereeIdentity(hash, null, "jar-only");
        } catch (Exception unavailable) {
            return new RefereeIdentity(null, null, "unavailable");
        }
    }

    private static AssetSource requireSource(AssetSource source) {
        if (source == null) {
            throw new IllegalStateException(
                    "set -Dchonkcraft.pack or CHONKCRAFT_ASSET_PACK to the authenticated pack");
        }
        return source;
    }
}
