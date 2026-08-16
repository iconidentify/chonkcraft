package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BneAiDecisionAdapterTest {

    @TempDir
    Path temp;

    @Test
    void emitsACompleteMachineReadableCycleWindow() throws Exception {
        Assumptions.assumeTrue(AssetSource.fromEnvironment() != null,
                "No asset pack/install");
        Path output = temp.resolve("java-ai.json");
        BneAiDecisionAdapter.main(new String[] {
                "--map", "campaigns/orc/level01o",
                "--cycles", "12",
                "--output", output.toString(),
        });
        String json = Files.readString(output);
        assertTrue(json.contains("\"schema\":\"chonkcraft-bne-ai-decision-ledger-1\""));
        assertTrue(json.contains("\"cycle\":1"));
        assertTrue(json.contains("\"cycle\":12"));
        assertTrue(json.contains("\"writes\":["));
        assertTrue(json.contains("\"person_player\":0"));
        assertTrue(json.contains("\"computer_players\":[1]"));
    }

    @Test
    void evidenceBindsAutoSelectedPersonAndEveryComputer() {
        var arguments = new BneAiDecisionAdapter.Arguments(
                "campaigns/human-exp/levelx01h", 7, 1800,
                temp.resolve("unused.json"));
        String json = BneAiDecisionAdapter.evidenceJson(
                List.of(), arguments, 3, List.of(0, 2, 6));
        assertTrue(json.contains("\"map\":\"campaigns/human-exp/levelx01h\""));
        assertTrue(json.contains("\"seed\":7"));
        assertTrue(json.contains("\"cycles\":1800"));
        assertTrue(json.contains("\"person_player\":3"));
        assertTrue(json.contains("\"computer_players\":[0,2,6]"));
    }
}
