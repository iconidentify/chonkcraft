package net.chonkbase.chonkcraft.engine.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.generated.GeneratedButtons;
import net.chonkbase.chonkcraft.engine.generated.GeneratedDependencies;
import net.chonkbase.chonkcraft.engine.generated.GeneratedIcons;
import net.chonkbase.chonkcraft.engine.generated.GeneratedMissiles;
import net.chonkbase.chonkcraft.engine.generated.GeneratedUnitTypes;
import net.chonkbase.chonkcraft.engine.generated.GeneratedUpgrades;
import net.chonkbase.chonkcraft.engine.ui.UnitButton;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.upgrade.Upgrade;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The generated definitions against the interpreted ones.
 *
 * <p>The point of generating Java from the scripts is to stop needing the
 * scripts at runtime. The risk is a second source of truth that quietly stops
 * agreeing with the first, so these compare them: whatever the interpreter
 * reads from the shipped retired scripting language is what the generated code has to say.
 *
 * <p>The generated classes stand on their own, so the parts of this that do
 * not need an installation run anywhere. The comparisons skip without one.
 */
class GeneratedDefinitionsTest {

    private static GameData load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    @Test
    @DisplayName("the generated definitions stand without the scripts")
    void theyStandAlone() {
        // No installation, no interpreter, no ChonkCraft checkout: the data is in
        // the class file. That is the whole point of generating it.
        assertTrue(GeneratedUnitTypes.ROWS.size() > 100,
                "only " + GeneratedUnitTypes.ROWS.size() + " unit types");
        assertTrue(GeneratedButtons.ROWS.size() > 200);
        assertTrue(GeneratedIcons.FRAMES.size() > 190);
        assertTrue(GeneratedUpgrades.ROWS.size() > 40);
        assertTrue(GeneratedDependencies.RULES.size() > 50);

        GeneratedUnitTypes.Row footman = GeneratedUnitTypes.ROWS.stream()
                .filter(row -> "unit-footman".equals(row.ident()))
                .findFirst().orElse(null);
        assertNotNull(footman, "no footman in the generated roster");
        assertEquals("Footman", footman.name());
        assertEquals(60, footman.hitPoints());
        assertEquals(600, footman.costs().get("gold"));
    }

    /**
     * The whole row, not a chosen handful of its fields.
     *
     * <p>This used to name eight of the record's twenty components and the
     * costs. {@code mana} was not among them, and the file sat stale on that
     * field for as long as anyone had been generating it: 13 casters and heroes
     * read 0 in the snapshot against 255 from a fresh parse. A snapshot exists
     * to catch drift, and a field nothing compares can drift for ever.
     *
     * <p>Building the expected row and comparing the two records is what makes
     * that impossible to repeat. A field added to {@code Row} is compared the
     * day it is added, because there is no list of field names here to forget
     * to extend -- which was the actual defect, rather than the missing
     * {@code mana} line.
     */
    @Test
    @DisplayName("the generated roster matches what the interpreter reads, field for field")
    void theRosterAgrees() {
        Map<String, UnitType> interpreted = load().unitTypes().types();
        assertEquals(interpreted.size(), GeneratedUnitTypes.ROWS.size(),
                "the roster changed size");
        assertTrue(GeneratedUnitTypes.ROWS.size() > 100,
                "only " + GeneratedUnitTypes.ROWS.size() + " rows; the sweep proves nothing");

        for (GeneratedUnitTypes.Row row : GeneratedUnitTypes.ROWS) {
            UnitType type = interpreted.get(row.ident());
            assertNotNull(type, row.ident() + " is generated but not interpreted");
            assertEquals(rowOf(type), row, row.ident() + " has drifted from the scripts");
        }
    }

    /** What {@code DefinitionWriter} should have written for a type. */
    private static GeneratedUnitTypes.Row rowOf(UnitType type) {
        Map<String, Integer> costs = new java.util.LinkedHashMap<>();
        for (var cost : type.costs().entrySet()) {
            costs.put(cost.getKey().name().toLowerCase(Locale.ROOT), cost.getValue());
        }
        return new GeneratedUnitTypes.Row(type.ident(), type.name(), type.hitPoints(),
                type.armor(), type.basicDamage(), type.piercingDamage(),
                type.minAttackRange(), type.maxAttackRange(), type.sightRange(),
                type.speed(), type.mana(), type.manaStart(),
                type.tileWidth(), type.tileHeight(),
                type.repairRange(), type.repairHp(), type.building(), type.canAttack(),
                type.landUnit(), type.seaUnit(), type.airUnit(), costs);
    }

    @Test
    @DisplayName("the native button registry preserves every generated field, in order")
    void theButtonsAgree() {
        List<UnitButton> nativeButtons =
                net.chonkbase.chonkcraft.engine.ui.GeneratedInterface.buttons().all();
        assertEquals(214, nativeButtons.size(), "the sealed command registry changed size");
        assertEquals(GeneratedButtons.ROWS.size(), nativeButtons.size());

        // Order matters as much as content: the panel resolves a slot
        // collision by taking the last valid button, so a reordering would
        // change what a barracks trains without changing what it may train.
        for (int i = 0; i < nativeButtons.size(); i++) {
            UnitButton actual = nativeButtons.get(i);
            GeneratedButtons.Row row = GeneratedButtons.ROWS.get(i);
            UnitButton expected = new UnitButton(row.pos(), row.level(), row.icon(), row.action(),
                    row.value(), row.allowed(), row.allowArg(), row.popup(), row.key(),
                    row.hint(), row.forUnits());
            assertEquals(expected, actual, "button " + i + " lost generated metadata");
        }
    }

    @Test
    @DisplayName("the native icon registry preserves every generated frame")
    void theIconsAgree() {
        assertEquals(198, GeneratedIcons.FRAMES.size(), "the sealed icon registry changed size");
        assertEquals(GeneratedIcons.FRAMES,
                net.chonkbase.chonkcraft.engine.ui.IconCatalog.generated().frames());
    }

    @Test
    @DisplayName("the native tech tree preserves every generated rule")
    void theDependenciesAgree() {
        var nativeRules = net.chonkbase.chonkcraft.engine.upgrade.UpgradeCatalog
                .generated().dependencies();
        assertEquals(nativeRules.size(), GeneratedDependencies.RULES.size(),
                "the tech tree changed size");
        for (var entry : GeneratedDependencies.RULES.entrySet()) {
            assertEquals(entry.getValue(), nativeRules.alternativesFor(entry.getKey()),
                    entry.getKey() + " requirements");
        }
    }

    @Test
    @DisplayName("the native upgrades preserve every generated field")
    void theUpgradesAgree() {
        Map<String, Upgrade> nativeUpgrades = net.chonkbase.chonkcraft.engine.upgrade.UpgradeCatalog
                .generated().upgrades().all();
        assertEquals(52, nativeUpgrades.size(), "the sealed upgrade catalog changed size");
        assertEquals(nativeUpgrades.size(), GeneratedUpgrades.ROWS.size());
        for (GeneratedUpgrades.Row row : GeneratedUpgrades.ROWS) {
            Upgrade upgrade = nativeUpgrades.get(row.ident());
            assertNotNull(upgrade, row.ident() + " is generated but not native");
            assertEquals(row.costs(), upgrade.costs().entrySet().stream().collect(
                    java.util.stream.Collectors.toMap(
                            entry -> entry.getKey().name().toLowerCase(Locale.ROOT),
                            Map.Entry::getValue)), row.ident() + " costs");
            assertEquals(row.changes(), upgrade.changes().entrySet().stream().collect(
                    java.util.stream.Collectors.toMap(
                            entry -> entry.getKey().name().toLowerCase(Locale.ROOT),
                            Map.Entry::getValue)), row.ident() + " changes");
            assertEquals(upgrade.appliesTo(), row.appliesTo(), row.ident() + " applies to");
            assertEquals(upgrade.convertTo(), row.convertTo(), row.ident() + " conversion");
        }
    }

    @Test
    @DisplayName("the native projectile catalog preserves every generated field")
    void theMissilesAgree() {
        var nativeCatalog = net.chonkbase.chonkcraft.engine.missile.MissileCatalog.generated();
        assertEquals(35, nativeCatalog.types().size());
        assertEquals(nativeCatalog.types().size(), GeneratedMissiles.TYPES.size());
        for (var generated : GeneratedMissiles.TYPES) {
            assertEquals(generated, nativeCatalog.types().get(generated.ident()),
                    generated.ident() + " lost generated metadata");
        }
        var nativeFire = nativeCatalog.burningBuildings().frames().stream()
                .map(frame -> new GeneratedMissiles.BurnRow(frame.percent(),
                        frame.missile() == null ? null : frame.missile().ident()))
                .toList();
        assertEquals(GeneratedMissiles.BURNING, nativeFire);
    }
}
