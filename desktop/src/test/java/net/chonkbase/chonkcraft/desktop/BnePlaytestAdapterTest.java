package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.chonkbase.assetpack.Json;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BnePlaytestAdapterTest {

    @Test
    @DisplayName("actors rescued on the first native frame are paired after that transfer")
    void actorsRescuedOnTheFirstNativeFrameArePairedAfterThatTransfer() throws Exception {
        Assumptions.assumeTrue(AssetSource.fromEnvironment() != null,
                "the BNE asset pack must be available");
        Path directory = Files.createTempDirectory("bne-playtest-rescue-");
        Path scenario = directory.resolve("scenario.json");
        Path output = directory.resolve("result.json");
        Map<String, Object> input = Map.of(
                "schema", "chonkcraft-bne-playtest-scenario-1",
                "scenario_sha256", "a".repeat(64),
                "seed_identity", Map.of("fixture", "xorc10-player-commands"),
                "setup", Map.of("kind", "sealed-fixture", "java_map",
                        "campaigns/orc-exp/levelx10o", "seed", 1),
                "settle_cycles", 80,
                "actors", List.of(Map.of("id", 1571, "player", 5,
                        "x", 5, "y", 5, "domain", "land",
                        "capabilities", List.of("move"))),
                "targets", List.of(),
                "commands", List.of(Map.of("kind", "move", "unit_id", 1571,
                        "x", 9, "y", 5, "queued", false, "issue_cycle", 5)));
        Files.writeString(scenario, Json.write(input));
        BnePlaytestAdapter.main(new String[] {"--scenario", scenario.toString(),
                "--output", output.toString(), "--build-sha256", "b".repeat(64)});
        Map<?, ?> result = Json.parseObject(Files.readString(output));
        Map<?, ?> order = (Map<?, ?>) ((List<?>) result.get("observations")).getFirst();
        assertEquals(Boolean.TRUE, order.get("accepted"));
        assertNotNull(order.get("first_progress_cycle"),
                "the transferred knight must respond to its new owner's command");
    }

    @Test
    @DisplayName("an authenticated orc-1 peon move is issued through command applier")
    void anAuthenticatedOrc1PeonMoveIsIssuedThroughCommandApplier() throws Exception {
        Assumptions.assumeTrue(AssetSource.fromEnvironment() != null,
                "No Warcraft II installation configured (-Dwc2.install.dir). ");
        Path directory = Files.createTempDirectory("bne-playtest-adapter-");
        Path scenarioPath = directory.resolve("scenario.json");
        Path output = directory.resolve("result.json");
        String scenarioSha = "a".repeat(64);
        String scenario = """
                {
                  "schema": "chonkcraft-bne-playtest-scenario-1",
                  "scenario_sha256": "%s",
                  "seed_identity": {"fixture": "orc01-commanded"},
                  "setup": {
                    "kind": "sealed-fixture",
                    "scenario": "Campaign\\\\Orc\\\\Orc01.pud",
                    "seed": 1
                  },
                  "pattern": "single",
                  "settle_cycles": 40,
                  "actors": [
                    {"id": 1594, "player": 0, "domain": "land",
                     "capabilities": ["move"], "x": 25, "y": 18}
                  ],
                  "targets": [],
                  "commands": [
                    {"kind": "move", "unit_id": 1594, "x": 30, "y": 18,
                     "queued": false, "issue_cycle": 5}
                  ]
                }
                """.formatted(scenarioSha);
        // The explorer hashes the canonical object. Rebuild identity the same
        // way after parsing so the adapter's fail-closed check sees a real id.
        Map<String, Object> parsed = Json.parseObject(scenario);
        parsed.put("scenario_sha256", scenarioSha);
        Files.writeString(scenarioPath, Json.write(parsed), StandardCharsets.UTF_8);

        BnePlaytestAdapter.main(new String[] {
                "--scenario", scenarioPath.toString(),
                "--output", output.toString(),
                "--build-sha256", "b".repeat(64),
        });

        Map<String, Object> result = Json.parseObject(
                Files.readString(output, StandardCharsets.UTF_8));
        assertEquals("chonkcraft-bne-playtest-result-1", result.get("schema"));
        assertEquals("java", result.get("side"));
        assertEquals("bne-playtest-java-command-applier",
                ((Map<?, ?>) result.get("producer")).get("name"));
        List<?> observations = (List<?>) result.get("observations");
        assertEquals(1, observations.size(), "the peon order must be observed");
        Map<?, ?> observation = (Map<?, ?>) observations.getFirst();
        assertEquals(Boolean.TRUE, observation.get("accepted"),
                "the starting peon must accept the move through CommandApplier");
        assertNotNull(observation.get("first_progress_cycle"),
                "the peon must physically start walking");
        assertNotNull(observation.get("terminal_reason"),
                "the order must reach a terminal classification");
        Map<?, ?> state = (Map<?, ?>) observation.get("state");
        assertTrue(state.get("tile_x") instanceof Number,
                "the peon's final tile is part of the observation");
    }

    @Test
    @DisplayName("human campaign clicks command the player shown in the game")
    void humanCampaignClicksCommandThePlayerShownInTheGame() throws Exception {
        Assumptions.assumeTrue(AssetSource.fromEnvironment() != null,
                "No Warcraft II installation configured (-Dwc2.install.dir). ");
        Path directory = Files.createTempDirectory("bne-playtest-human01-");
        Path scenarioPath = directory.resolve("scenario.json");
        Path output = directory.resolve("result.json");
        String scenarioSha = "d".repeat(64);
        String scenario = """
                {
                  "schema": "chonkcraft-bne-playtest-scenario-1",
                  "scenario_sha256": "%s",
                  "seed_identity": {"fixture": "human01-field-move"},
                  "setup": {
                    "kind": "sealed-fixture",
                    "scenario": "Campaign\\\\Human\\\\Human01.pud",
                    "seed": 1
                  },
                  "pattern": "single",
                  "settle_cycles": 40,
                  "actors": [
                    {"id": 1598, "player": 1, "domain": "land",
                     "capabilities": ["move"], "x": 21, "y": 5}
                  ],
                  "targets": [],
                  "commands": [
                    {"kind": "move", "unit_id": 1598, "x": 25, "y": 28,
                     "queued": false, "issue_cycle": 5}
                  ]
                }
                """.formatted(scenarioSha);
        Map<String, Object> parsed = Json.parseObject(scenario);
        parsed.put("scenario_sha256", scenarioSha);
        Files.writeString(scenarioPath, Json.write(parsed), StandardCharsets.UTF_8);

        BnePlaytestAdapter.main(new String[] {
                "--scenario", scenarioPath.toString(),
                "--output", output.toString(),
                "--build-sha256", "e".repeat(64),
        });

        Map<String, Object> result = Json.parseObject(
                Files.readString(output, StandardCharsets.UTF_8));
        List<?> observations = (List<?>) result.get("observations");
        assertEquals(1, observations.size(), "the footman order must be observed");
        Map<?, ?> observation = (Map<?, ?>) observations.getFirst();
        assertEquals(Boolean.TRUE, observation.get("accepted"),
                "BNE UI GiveOrder belongs to the campaign's human player 1");
        assertNotNull(observation.get("first_progress_cycle"),
                "the human footman must visibly start its accepted Move");
    }

    @Test
    @DisplayName("a worker inside its depot is alive in the cycle observations")
    void workerInsideItsDepotIsAliveInTheCycleObservations() throws Exception {
        Assumptions.assumeTrue(AssetSource.fromEnvironment() != null,
                "the BNE asset pack must be available");
        Path directory = Files.createTempDirectory("bne-playtest-contained-");
        Path input = directory.resolve("scenario.json");
        Path output = directory.resolve("result.json");
        Map<String, Object> scenario = Map.of(
                "schema", "chonkcraft-bne-playtest-scenario-1",
                "scenario_sha256", "f".repeat(64),
                "setup", Map.of("scenario", "Campaign\\Human\\Human01.pud", "seed", 1),
                "actors", List.of(Map.of("id", 1596, "player", 1, "x", 14, "y", 9)),
                "targets", List.of(),
                "settle_cycles", 120,
                "combat_observation", Map.of("unit_ids", List.of(1596)),
                "commands", List.of(Map.of("kind", "return-goods", "unit_id", 1596,
                        "issue_cycle", 5, "queued", false)));
        Files.writeString(input, Json.write(scenario));
        BnePlaytestAdapter.main(new String[] {"--scenario", input.toString(),
                "--output", output.toString(), "--build-sha256", "b".repeat(64)});
        Map<String, Object> result = Json.parseObject(Files.readString(output));
        List<?> events = (List<?>) result.get("events");
        List<?> contained = events.stream().filter(value -> value instanceof Map<?, ?> event
                && "combat-state".equals(event.get("kind"))
                && Boolean.FALSE.equals(event.get("on_map"))).toList();
        assertTrue(!contained.isEmpty(), "the return must take the worker into its depot");
        for (Object value : contained) {
            assertEquals(Boolean.TRUE, ((Map<?, ?>) value).get("alive"),
                    "a contained worker must not be counted as a combat death");
        }
    }

    @Test
    @DisplayName("a human 13 north click reports two live shots at the still visit")
    void aHuman13NorthClickReportsTwoLiveShotsAtTheStillVisit() throws Exception {
        Assumptions.assumeTrue(AssetSource.fromEnvironment() != null,
                "No Warcraft II installation configured (-Dwc2.install.dir). ");
        Path directory = Files.createTempDirectory("bne-playtest-missiles-");
        Path scenarioPath = directory.resolve("scenario.json");
        Path output = directory.resolve("result.json");
        String scenarioSha = "c".repeat(64);
        String scenario = """
                {
                  "schema": "chonkcraft-bne-playtest-scenario-1",
                  "scenario_sha256": "%s",
                  "seed_identity": {"fixture": "batch-1-24"},
                  "setup": {
                    "kind": "sealed-fixture",
                    "scenario": "Campaign\\\\Human\\\\Human13.pud",
                    "command_player": 0,
                    "seed": 1
                  },
                  "pattern": "single",
                  "settle_cycles": 80,
                  "actors": [
                    {"id": 1449, "player": 0, "domain": "land",
                     "capabilities": ["move"], "x": 98, "y": 57}
                  ],
                  "targets": [],
                  "commands": [
                    {"kind": "move", "unit_id": 1449, "x": 98, "y": 55,
                     "queued": false, "issue_cycle": 5}
                  ]
                }
                """.formatted(scenarioSha);
        Map<String, Object> parsed = Json.parseObject(scenario);
        parsed.put("scenario_sha256", scenarioSha);
        Files.writeString(scenarioPath, Json.write(parsed), StandardCharsets.UTF_8);

        BnePlaytestAdapter.main(new String[] {
                "--scenario", scenarioPath.toString(),
                "--output", output.toString(),
                "--build-sha256", "b".repeat(64),
        });

        Map<String, Object> result = Json.parseObject(
                Files.readString(output, StandardCharsets.UTF_8));
        Map<?, ?> observation = (Map<?, ?>) ((List<?>) result.get("observations"))
                .getFirst();
        Map<?, ?> state = (Map<?, ?>) observation.get("state");
        assertEquals(40, ((Number) observation.get("terminal_cycle")).intValue(),
                "the axethrower is Still on 98,55 at fixture 40");
        assertEquals(2, ((Number) state.get("missile_count")).intValue(),
                "the Still visit still has the landed rock and its impact, not later axe throws");
    }

    @Test
    @DisplayName("human 13 combat-state counts only constructor-debited shots")
    void human13CombatStateCountsOnlyConstructorDebitedShots() throws Exception {
        Assumptions.assumeTrue(AssetSource.fromEnvironment() != null,
                "No Warcraft II installation configured (-Dwc2.install.dir). ");
        Path directory = Files.createTempDirectory("bne-playtest-h13-prefix-");
        Path scenarioPath = directory.resolve("scenario.json");
        Path output = directory.resolve("result.json");
        String scenarioSha = "f".repeat(64);
        String scenario = """
                {
                  "schema": "chonkcraft-bne-playtest-scenario-1",
                  "scenario_sha256": "%s",
                  "seed_identity": {"fixture": "human13-ranged-prefix"},
                  "setup": {
                    "kind": "sealed-fixture",
                    "scenario": "Campaign\\\\Human\\\\Human13.pud",
                    "command_player": 0,
                    "seed": 1
                  },
                  "pattern": "single",
                  "settle_cycles": 10,
                  "combat_observation": {
                    "encounter": "ranged-infantry",
                    "stance": "attack",
                    "unit_ids": [1494]
                  },
                  "actors": [
                    {"id": 1494, "player": 0, "domain": "land",
                     "capabilities": ["attack"], "x": 118, "y": 29,
                     "target_ids": [1493]}
                  ],
                  "pair_units": [
                    {"id": 1479, "player": 0, "x": 118, "y": 36}
                  ],
                  "targets": [
                    {"id": 1493, "player": 1, "domain": "land",
                     "x": 120, "y": 29}
                  ],
                  "commands": [
                    {"kind": "attack", "unit_id": 1494, "target_id": 1493,
                     "queued": false, "issue_cycle": 5}
                  ]
                }
                """.formatted(scenarioSha);
        Map<String, Object> parsed = Json.parseObject(scenario);
        parsed.put("scenario_sha256", scenarioSha);
        Files.writeString(scenarioPath, Json.write(parsed), StandardCharsets.UTF_8);

        BnePlaytestAdapter.main(new String[] {
                "--scenario", scenarioPath.toString(),
                "--output", output.toString(),
                "--build-sha256", "c".repeat(64),
        });

        Map<String, Object> result = Json.parseObject(
                Files.readString(output, StandardCharsets.UTF_8));
        int cycleFour = -1;
        int cycleFive = -1;
        for (Object raw : (List<?>) result.get("events")) {
            Map<?, ?> event = (Map<?, ?>) raw;
            if (!"combat-state".equals(event.get("kind"))) {
                continue;
            }
            int cycle = ((Number) event.get("cycle")).intValue();
            int count = ((Number) event.get("missile_count")).intValue();
            if (cycle == 4) {
                cycleFour = count;
            }
            if (cycle == 5) {
                cycleFive = count;
            }
        }
        assertEquals(0, cycleFour,
                "cycle 4 still has only presentation placeholders; retail "
                        + "AUXL is empty until the constructor boundary");
        assertTrue(cycleFive >= 2,
                "cycle 5 constructs the two catapult rocks retail already "
                        + "shows in the pool");
        boolean namedCatapult = false;
        for (Object raw : (List<?>) result.get("events")) {
            Map<?, ?> event = (Map<?, ?>) raw;
            if (!"combat-projectile".equals(event.get("kind"))) {
                continue;
            }
            if (((Number) event.get("cycle")).intValue() != 5) {
                continue;
            }
            if (event.get("source_id") instanceof Number source
                    && source.intValue() == 1479) {
                namedCatapult = true;
                break;
            }
        }
        assertTrue(namedCatapult,
                "the cycle-5 rock must keep native slot 1479 after pairing");
    }

    @Test
    @DisplayName("an orc 1 grunt hall mend reports first progress at fixture 9")
    void anOrc1GruntHallMendReportsFirstProgressAtFixture9() throws Exception {
        Assumptions.assumeTrue(AssetSource.fromEnvironment() != null,
                "No Warcraft II installation configured (-Dwc2.install.dir). ");
        Path directory = Files.createTempDirectory("bne-playtest-repair-");
        Path scenarioPath = directory.resolve("scenario.json");
        Path output = directory.resolve("result.json");
        String scenarioSha = "d".repeat(64);
        String scenario = """
                {
                  "schema": "chonkcraft-bne-playtest-scenario-1",
                  "scenario_sha256": "%s",
                  "seed_identity": {"fixture": "repair-1-03"},
                  "setup": {
                    "kind": "sealed-fixture",
                    "scenario": "Campaign\\\\Orc\\\\Orc01.pud",
                    "seed": 1
                  },
                  "pattern": "single",
                  "settle_cycles": 155,
                  "actors": [
                    {"id": 1592, "player": 0, "domain": "land",
                     "capabilities": ["repair"], "x": 18, "y": 23}
                  ],
                  "targets": [
                    {"id": 1593, "player": 0, "domain": "land", "x": 22, "y": 22}
                  ],
                  "commands": [
                    {"kind": "repair", "unit_id": 1592, "target_id": 1593,
                     "queued": false, "issue_cycle": 5}
                  ]
                }
                """.formatted(scenarioSha);
        Map<String, Object> parsed = Json.parseObject(scenario);
        parsed.put("scenario_sha256", scenarioSha);
        Files.writeString(scenarioPath, Json.write(parsed), StandardCharsets.UTF_8);

        BnePlaytestAdapter.main(new String[] {
                "--scenario", scenarioPath.toString(),
                "--output", output.toString(),
                "--build-sha256", "b".repeat(64),
        });

        Map<String, Object> result = Json.parseObject(
                Files.readString(output, StandardCharsets.UTF_8));
        Map<?, ?> observation = (Map<?, ?>) ((List<?>) result.get("observations"))
                .getFirst();
        Map<?, ?> state = (Map<?, ?>) observation.get("state");
        assertEquals(9, ((Number) observation.get("first_progress_cycle")).intValue(),
                "the grunt's Still wait pops Repair at fixture 9, not earlier dest-arm");
        assertEquals(60, ((Number) observation.get("terminal_cycle")).intValue(),
                "the grunt stands Still on 21,23 at fixture 60");
        assertEquals(21, ((Number) state.get("tile_x")).intValue(),
                "the grunt stands on the west hall ring");
        assertEquals(23, ((Number) state.get("tile_y")).intValue(),
                "the grunt stands on the west hall ring");
    }
}
