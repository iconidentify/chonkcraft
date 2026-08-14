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
}
