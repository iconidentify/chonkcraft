package net.chonkbase.chonkcraft.engine.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Loads the real upgrade definitions from the game scripts. */
class UpgradeRealDataTest {

    private static GameData gameData() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    @Test
    @DisplayName("the scripts define both races' full upgrade tree")
    void bothRacesUpgradeTreesLoad() {
        UpgradeSet upgrades = gameData().upgrades().upgrades();

        // Warcraft II has weapon, shield and ship upgrades for each race,
        // plus spell research, which comes to about fifty.
        assertTrue(upgrades.size() >= 40, "expected the full tree, got " + upgrades.size());

        for (String ident : List.of(
                "upgrade-sword1", "upgrade-sword2", "upgrade-arrow1", "upgrade-arrow2",
                "upgrade-human-shield1", "upgrade-human-shield2",
                "upgrade-battle-axe1", "upgrade-battle-axe2",
                "upgrade-orc-shield1", "upgrade-orc-shield2")) {
            assertTrue(upgrades.get(ident) != null, "missing " + ident);
        }
    }

    @Test
    @DisplayName("upgrades carry the stat changes the data declares")
    void upgradesCarryTheirRealStatChanges() {
        UpgradeSet upgrades = gameData().upgrades().upgrades();

        // These are the shipped numbers, not invented expectations.
        assertEquals(2, upgrades.get("upgrade-sword1").change(Upgrade.Stat.PIERCING_DAMAGE));
        assertEquals(1, upgrades.get("upgrade-sword1").change(Upgrade.Stat.LEVEL));
        assertEquals(2, upgrades.get("upgrade-human-shield1").change(Upgrade.Stat.ARMOR));
        assertEquals(1, upgrades.get("upgrade-arrow1").change(Upgrade.Stat.PIERCING_DAMAGE));
    }

    @Test
    @DisplayName("upgrades name the unit types they improve")
    void upgradesNameTheirTargets() {
        GameData data = gameData();
        UpgradeSet upgrades = data.upgrades().upgrades();

        Upgrade sword = upgrades.get("upgrade-sword1");
        assertTrue(sword.appliesTo().contains("unit-footman"), "sword1 should reach footmen");
        assertTrue(sword.appliesTo().contains("unit-knight"), "and knights");
        assertTrue(!sword.appliesTo().contains("unit-archer"), "but not archers");

        // Every named target must be a real unit type, or the upgrade would
        // silently improve nothing.
        var roster = data.unitTypes().types();
        for (Upgrade upgrade : upgrades.all().values()) {
            for (String target : upgrade.appliesTo()) {
                assertTrue(roster.containsKey(target),
                        upgrade.ident() + " improves '" + target + "', which is not a unit type");
            }
        }
    }

    @Test
    @DisplayName("researching a real upgrade changes a real unit's damage")
    void researchingChangesRealUnitDamage() {
        GameData data = gameData();
        UnitType footman = data.unitTypes().types().get("unit-footman");
        Assumptions.assumeTrue(footman != null, "unit-footman not defined");

        UpgradeState state = new UpgradeState(data.upgrades().upgrades());
        int base = state.piercingDamage(footman);

        state.complete("upgrade-sword1");
        assertEquals(base + 2, state.piercingDamage(footman), "sword1 should add two");

        state.complete("upgrade-sword2");
        assertEquals(base + 4, state.piercingDamage(footman), "and sword2 another two");
    }
}
