package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The status line said "1 Critter" where Warcraft II says "1 Sheep".
 *
 * <p>The animal wandering the map is a sheep in summer, a seal in winter, a pig
 * in wasteland and a warthog in swamp. {@code scripts/units.legacy-declaration:228} names the
 * type {@code Critter} as a placeholder and {@code scripts/scripts.legacy-declaration:50}
 * redefines it for the tileset once a map has been loaded.
 *
 * <p>This implementation ran neither line of that block. It did port the statement
 * directly above them -- the {@code UnitTypeFiles} walk that picks each type's
 * sprite for the tileset, which is {@code applyUnitTypeFiles} -- and stopped
 * there, so the four lines below it were lost: the name, and the two sounds
 * that {@code CritterVoiceRealDataTest} covers.
 *
 * <p>Found by counting rather than by reading. The shipped sound table binds
 * 371 names where {@code audit-gaps.py}'s reading of the scripts finds 372, and
 * chasing that single name led to the block. Nothing else pointed at it: the
 * type loaded, had a name, and drew correctly.
 *
 * <p>The measurement is what the player is shown after loading a real campaign
 * map of each tileset, because which animal it is is a fact about the map.
 */
class CritterNameRealDataTest {

    private static GameData load() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set -Dchonkcraft.pack=... or wc2.install.dir");
        return new GameData(assets);
    }

    @Test
    @DisplayName("the wandering animal is named for the ground it wanders")
    void theCritterIsNamedForItsTileset() {
        // Campaign maps chosen for their tilesets, each verified by reading
        // the PUD's own tileset field. Note that mission 8 is the first
        // summer map: the human campaign opens in winter, and the .PUD spells
        // that tileset FOREST where every script spells it summer.
        record Ground(String map, String animal) {}
        List<Ground> grounds = List.of(
                new Ground("campaigns/human/level08h", "Sheep"),
                new Ground("campaigns/human/level01h", "Seal"),
                new Ground("campaigns/human/level05h", "Pig"),
                new Ground("campaigns/human-exp/levelx07h", "Warthog"));

        GameData data = load();
        int checked = 0;
        for (Ground ground : grounds) {
            Mission mission = data.loadMission(ground.map(), 1, 1);
            if (mission == null) {
                continue;
            }
            checked++;
            UnitType critter = data.unitTypes().types().get("unit-critter");
            assertNotNull(critter, "the game has no critter type at all");
            assertEquals(ground.animal(), critter.name(),
                    "on " + ground.map() + " the player is shown the wrong animal");
        }
        assertEquals(grounds.size(), checked,
                "only " + checked + " of the campaign maps loaded, so this"
                        + " compared almost nothing");
    }
}
