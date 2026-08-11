package net.chonkbase.chonkcraft.desktop;

import java.awt.image.BufferedImage;
import java.io.StringWriter;
import java.util.LinkedHashSet;
import java.util.Set;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.save.LoadGame;
import net.chonkbase.chonkcraft.engine.save.SaveGame;

/** Runtime-only certification entry point used by the JAR-plus-pack gate. */
public final class HermeticCertification {

    private HermeticCertification() {
    }

    public static void main(String[] args) throws Exception {
        AssetSource assets = require(AssetSource.fromEnvironment(), "asset pack");
        check(assets.isBattleNetEdition(), "source is not Battle.net Edition");
        GameData data = new GameData(assets);

        check(data.unitTypes().types().size() > 100, "native roster incomplete");
        check(data.sounds().isAvailable() && data.sounds().failures().isEmpty(),
                "sound bindings incomplete: " + data.sounds().failures());
        check(data.upgrades().upgrades().all().size() > 40, "technology incomplete");
        check(data.spells().spells().size() >= 20, "spell catalog incomplete");
        check(data.missiles().types().size() >= 30, "missile catalog incomplete");
        check(data.userInterface("summer").buttons().all().size() > 200,
                "command interface incomplete");
        for (PudMap.Tileset tileset : PudMap.Tileset.values()) {
            check(data.loadTileset(tileset) != null, "missing tileset " + tileset);
            String constructionTileset = tileset == PudMap.Tileset.FOREST
                    ? "summer" : tileset.name().toLowerCase();
            check(!data.constructions(constructionTileset).constructions().isEmpty(),
                    "missing constructions for " + tileset);
        }

        int missions = 0;
        int triggers = 0;
        int opponents = 0;
        Mission saveSubject = null;
        Set<String> loaded = new LinkedHashSet<>();
        for (var campaign : data.campaigns()) {
            for (var step : campaign.missions()) {
                String path = step.mapArchivePath();
                Mission mission = require(data.loadMission(path), path);
                check(mission.triggers().failures().isEmpty(),
                        path + " trigger construction failed");
                check(loaded.add(path), "duplicate mission " + path);
                triggers += mission.triggers().triggerCount();
                opponents += mission.ai().size();
                missions++;
                if (saveSubject == null) {
                    saveSubject = mission;
                }
            }
        }
        check(data.campaigns().size() == 4, "expected four campaigns");
        check(missions == 52, "expected 52 missions, got " + missions);
        check(triggers == 137, "expected 137 mission triggers, got " + triggers);
        check(opponents > 0, "no retail AI opponents attached");

        // Exercise the native AI and trigger cadence from a real campaign.
        Mission running = require(data.loadMission("campaigns/human/level01h"), "human 1");
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 5; cycle++) {
            running.tick();
        }
        check(running.triggers().failures().isEmpty(), "mission trigger failed while ticking");

        StringWriter saved = new StringWriter();
        SaveGame.write(saveSubject.world(), "campaigns/human/level01h", "human", 1,
                saveSubject.triggers().armedTriggers(), saved);
        String document = saved.toString();
        check(document.startsWith("SaveFormat(\"chonkcraft-save\", 2)"),
                "native save schema missing");
        LoadGame.Header header = require(LoadGame.header(document), "save header");
        check("campaigns/human/level01h".equals(header.mapPath()), "save map changed");

        check(assets.musicTracks().size() == 20,
                "expected twenty recorded BNE tracks, got " + assets.musicTracks().size());
        for (int index : new int[] {0, 6, 12, 15, 19}) {
            short[] pcm = assets.musicSamples(index);
            check(pcm != null && pcm.length > 44_100, "music track " + index + " did not decode");
            boolean signal = false;
            for (short sample : pcm) {
                if (sample != 0) {
                    signal = true;
                    break;
                }
            }
            check(signal, "music track " + index + " decoded to silence");
        }

        // Paint the real menu without a window; this loads fonts, title media,
        // campaign presentation and the command-facing Swing boundary.
        MenuScreen menu = new MenuScreen(data, "human", 1280, 800, null);
        check(menu.isAvailable(), "native menu unavailable");
        menu.setSize(1280, 800);
        BufferedImage image = new BufferedImage(1280, 800, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            menu.printAll(graphics);
        } finally {
            graphics.dispose();
        }
        check(data.unresolvedPaths().isEmpty(),
                "unresolved runtime assets: " + data.unresolvedPaths());

        System.out.println("HERMETIC_CERTIFICATION=PASS");
        System.out.println("CAMPAIGNS=4");
        System.out.println("MISSIONS=52");
        System.out.println("TRIGGERS=137");
        System.out.println("MUSIC=20");
        System.out.println("AI_ASSIGNMENTS=" + opponents);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static <T> T require(T value, String label) {
        if (value == null) {
            throw new IllegalStateException("missing " + label);
        }
        return value;
    }
}
