package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.unit.ResourceInfo;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.unit.UnitType.Resource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which sheet a worker draws from, read rather than guessed.
 *
 * <p> swaps the type's sprite for the resource's own
 * {@code SpriteWhenLoaded} or {@code SpriteWhenEmpty} whenever a harvester has
 * a current resource. Those two come straight out of the unit data as
 * {@code file-when-loaded} and {@code file-when-empty}.
 *
 * <p>The implementation used to build the name instead, by putting {@code _with_gold} or
 * {@code _with_wood} on the end of the type's own sprite path. That is the
 * right answer for a peasant and a peon and the wrong one for both oil
 * tankers, whose data says {@code oil_tanker_full} and {@code oil_tanker_empty}
 * -- so a laden tanker drew the same picture as an empty one, and the empty
 * sheet the archive ships was never used at all.
 */
class WorkerSpriteTest {

    private static GameData gameData() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    /** What the guess used to produce, kept here so the difference is visible. */
    private static String guessed(UnitType type, Resource carrying) {
        String suffix = switch (carrying) {
            case GOLD -> "_with_gold";
            case WOOD -> "_with_wood";
            default -> null;
        };
        if (suffix == null) {
            return null;
        }
        String base = type.imageFileFor("summer");
        int dot = base.lastIndexOf('.');
        if (dot > base.lastIndexOf('/')) {
            base = base.substring(0, dot);
        }
        return base + suffix;
    }

    @Test
    @DisplayName("A tanker's laden and empty sheets come from its own data")
    void aTankerDrawsTheSheetsItsDataNames() {
        GameData data = gameData();
        UnitType tanker = data.unitTypes().types().get("unit-human-oil-tanker");
        assertNotNull(tanker, "the human oil tanker is not in the roster");

        ResourceInfo oil = tanker.gathering().get(Resource.OIL);
        assertNotNull(oil, "an oil tanker gathers oil");
        assertEquals(150, oil.waitAtResource(),
                "BNE keeps a tanker inside its platform for 150 cycles");
        assertEquals(150, oil.waitAtDepot(),
                "BNE keeps a tanker inside its depot for 150 cycles");
        assertEquals("human/units/oil_tanker_full.png", oil.fileWhenLoaded());
        assertEquals("human/units/oil_tanker_empty.png", oil.fileWhenEmpty());

        assertEquals("human/units/oil_tanker_full.png",
                tanker.imageFileFor("summer", Resource.OIL, true));
        assertEquals("human/units/oil_tanker_empty.png",
                tanker.imageFileFor("summer", Resource.OIL, false));
        assertNotEquals(tanker.imageFileFor("summer", Resource.OIL, true),
                tanker.imageFileFor("summer", Resource.OIL, false),
                "full and empty are different pictures");

        // The guess produced nothing at all for oil, so a laden tanker fell
        // back to the plain sheet.
        assertEquals(null, guessed(tanker, Resource.OIL));

        // And both sheets are real: they decode out of the archive.
        assertNotNull(data.sprite(oil.fileWhenLoaded()), "no such sprite: " + oil.fileWhenLoaded());
        assertNotNull(data.sprite(oil.fileWhenEmpty()), "no such sprite: " + oil.fileWhenEmpty());
    }

    /**
     * Every harvester in the game, both races. What the data names has to
     * exist, and where the guess happened to agree it still has to agree.
     */
    @Test
    @DisplayName("Every file-when-loaded and file-when-empty in the game decodes")
    void everyNamedWorkerSheetDecodes() {
        GameData data = gameData();
        List<String> missing = new ArrayList<>();
        int named = 0;
        for (UnitType type : data.unitTypes().types().values()) {
            for (ResourceInfo info : type.gathering().values()) {
                for (String file : List.of(info.fileWhenLoaded(), info.fileWhenEmpty())) {
                    if (file == null || file.isBlank()) {
                        continue;
                    }
                    named++;
                    if (data.sprite(file) == null) {
                        missing.add(type.ident() + " " + info.resource() + " " + file);
                    }
                }
            }
        }
        assertTrue(named >= 6,
                "expected the four workers' laden sheets and the tankers' empty ones, found " + named);
        assertTrue(missing.isEmpty(), String.join("\n", missing));
    }

    @Test
    @DisplayName("A worker with nothing to gather still draws its own sprite")
    void anEmptyHandedWorkerFallsBackToItsType() {
        GameData data = gameData();
        UnitType peasant = data.unitTypes().types().get("unit-peasant");
        assertNotNull(peasant);

        assertEquals(peasant.imageFileFor("summer"),
                peasant.imageFileFor("summer", null, false),
                "no resource in hand means the ordinary sheet");
        assertEquals("human/units/peasant_with_gold.png",
                peasant.imageFileFor("summer", Resource.GOLD, true));
        // A peasant declares no empty sheet, so it keeps its own.
        assertEquals(peasant.imageFileFor("summer"),
                peasant.imageFileFor("summer", Resource.GOLD, false));
    }
}
