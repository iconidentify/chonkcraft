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

class BnePhysicalAdapterTest {

    @Test
    @DisplayName("a human 1 field click writes a gamescreen physical receipt")
    void aHuman1FieldClickWritesAGamescreenPhysicalReceipt() throws Exception {
        Assumptions.assumeTrue(AssetSource.fromEnvironment() != null,
                "No Warcraft II installation configured (-Dwc2.install.dir). ");
        Path directory = Files.createTempDirectory("bne-physical-human01-");
        Path scenarioPath = directory.resolve("scenario.json");
        Path output = directory.resolve("evidence.json");
        Path cycleLog = directory.resolve("cycles.jsonl");
        Map<String, Object> scenario = Json.parseObject("""
                {
                  "schema": "chonkcraft-bne-physical-scenario-1",
                  "setup": {
                    "scenario": "Campaign\\\\Human\\\\Human01.pud",
                    "seed": 1,
                    "java_map": "campaigns/human/level01h"
                  },
                  "select": [
                    {"native_id": 1598, "player": 1, "x": 21, "y": 5}
                  ],
                  "gesture": {
                    "origin": "field",
                    "detail": "right-click",
                    "tile_x": 25,
                    "tile_y": 28,
                    "modifiers": "plain"
                  },
                  "issue_cycle": 5,
                  "cycles": 20
                }
                """);
        Files.writeString(scenarioPath, Json.write(scenario), StandardCharsets.UTF_8);

        BnePhysicalAdapter.main(new String[] {
                "--scenario", scenarioPath.toString(),
                "--output", output.toString(),
                "--cycle-log", cycleLog.toString(),
                "--build-sha256", "a".repeat(64),
        });

        Map<String, Object> evidence = Json.parseObject(
                Files.readString(output, StandardCharsets.UTF_8));
        assertEquals("java", ((Map<?, ?>) evidence.get("authority")).get("side"));
        assertEquals("bne-physical-gamescreen",
                ((Map<?, ?>) evidence.get("authority")).get("producer"));
        List<?> intents = (List<?>) evidence.get("player_intents");
        assertTrue(intents.size() >= 2, "the field click must journal a gesture and an order");
        Map<?, ?> gesture = null;
        for (Object item : intents) {
            if (((Map<?, ?>) item).get("gesture") instanceof Map<?, ?> body) {
                gesture = body;
                break;
            }
        }
        assertNotNull(gesture, "the field click must journal a physical gesture");
        assertEquals("field", gesture.get("origin"));
        assertEquals("open-ground", gesture.get("target_shape"));
        Map<?, ?> order = null;
        for (Object item : intents) {
            if (((Map<?, ?>) item).get("command") instanceof Map<?, ?> command) {
                order = command;
                break;
            }
        }
        assertNotNull(order, "the click must fan out a wire command");
        assertEquals("MOVE", order.get("kind"));
        assertEquals(25, ((Number) order.get("x")).intValue());
        assertEquals(28, ((Number) order.get("y")).intValue());
        assertTrue(order.get("wire_hex") instanceof String
                && !((String) order.get("wire_hex")).isBlank(),
                "the lockstep record stays on the physical receipt");
        List<?> feedback = (List<?>) evidence.get("player_feedback");
        assertEquals(1, feedback.size(), "one selected footman keeps the voice");
        assertEquals("voice", ((Map<?, ?>) feedback.getFirst()).get("mode"));
        List<?> outcomes = (List<?>) evidence.get("player_outcomes");
        assertEquals(1, outcomes.size());
        assertEquals(10, ((Number) ((Map<?, ?>) outcomes.getFirst())
                        .get("first_progress_cycle")).intValue(),
                "first progress is the first walk pixel, not the cycle-9 tile pop");
        assertTrue(Files.size(cycleLog) > 0, "the cycle twin log must not be empty");
        String five = null;
        String six = null;
        for (String line : Files.readString(cycleLog, StandardCharsets.UTF_8)
                .split("\n")) {
            if (line.contains("\"cycle\":5")) {
                five = line;
            }
            if (line.contains("\"cycle\":6")) {
                six = line;
            }
        }
        assertNotNull(five, "the cycle twin log must contain fixture 5");
        assertNotNull(six, "the cycle twin log must contain fixture 6");
        assertTrue(five.contains("\"order\":\"STILL\""),
                "retail is Still with next Move at cycle 5, not " + five);
        assertTrue(six.contains("\"order\":\"MOVE\""),
                "retail promotes the queued Move at cycle 6, not " + six);
    }
}
