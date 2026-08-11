package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.ui.UnitButton;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Exhaustive wiring between priced command buttons and simulation catalogs. */
class ResearchCatalogWiringTest {

    private record Fixture(GameData data, World world, CommandPanel panel,
            CommandApplier commands) {}

    private static Fixture fixture() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set -Dchonkcraft.pack=... or wc2.install.dir");
        GameData data = new GameData(assets);
        World world = new World(new GameMap(8, 8, new Tileset()));
        data.configureWorld(world, PudMap.Tileset.FOREST);
        CommandPanel panel = new CommandPanel(world, data, data.userInterface("summer"),
                data.upgrades().dependencies(), 0, "summer", "human",
                data.unitTypes().types());
        CommandApplier commands = new CommandApplier(world,
                new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);
        return new Fixture(data, world, panel, commands);
    }

    @Test
    @DisplayName("every BNE research button has a price and an engine-side researcher")
    void everyResearchButtonIsComplete() {
        Fixture fixture = fixture();
        GameData data = fixture.data();
        World world = fixture.world();
        CommandPanel panel = fixture.panel();
        CommandApplier commands = fixture.commands();

        List<String> defects = new ArrayList<>();
        int researchButtons = 0;
        for (UnitButton button : data.userInterface("summer").buttons().all()) {
            if (!"research".equals(button.action())) {
                continue;
            }
            researchButtons++;
            var upgrade = data.upgrades().upgrades().get(button.value());
            if (upgrade == null) {
                defects.add(button.value() + " has no upgrade definition");
                continue;
            }
            int time = upgrade.costs().getOrDefault(UnitType.Resource.TIME, 0);
            long payable = upgrade.costs().entrySet().stream()
                    .filter(entry -> entry.getKey() != UnitType.Resource.TIME)
                    .filter(entry -> entry.getValue() > 0)
                    .count();
            if (time <= 0 || payable == 0) {
                defects.add(button.value() + " has incomplete costs " + upgrade.costs());
            }
            if (panel.costLines(button).size() != payable) {
                defects.add(button.value() + " popup has " + panel.costLines(button)
                        + " for costs " + upgrade.costs());
            }
            if (commands.indexOfUpgrade(button.value()) < 0) {
                defects.add(button.value() + " is absent from the command roster");
            }
            for (String researcherIdent : button.forUnits()) {
                UnitType researcher = data.unitTypes().types().get(researcherIdent);
                if (researcher == null) {
                    defects.add(button.value() + " names missing " + researcherIdent);
                } else if (!world.mayResearch(researcher, button.value())) {
                    defects.add(researcherIdent + " cannot research " + button.value());
                }
            }
        }

        assertEquals(48, researchButtons,
                "the exhaustive sweep did not load the full BNE research roster");
        assertEquals(List.of(), defects,
                "research advertised by the interface is incomplete in the simulation");
        assertNotNull(world.upgradeSet(), "world startup omitted the upgrade price catalog");
        assertTrue(world.spells() != null, "the same startup boundary omitted spells");
    }

    @Test
    @DisplayName("every build, train, upgrade and spell button reaches the simulation catalog")
    void everyAdvertisedProductionActionIsComplete() {
        Fixture fixture = fixture();
        List<String> defects = new ArrayList<>();
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (UnitButton button : fixture.data().userInterface("summer").buttons().all()) {
            String action = button.action();
            if (!List.of("build", "train-unit", "upgrade-to", "cast-spell")
                    .contains(action)) {
                continue;
            }
            counts.merge(action, 1, Integer::sum);
            if ("cast-spell".equals(action)) {
                var spell = fixture.world().spells().get(button.value());
                if (spell == null) {
                    defects.add(button.value() + " has no spell definition");
                } else if (fixture.commands().indexOfSpell(button.value()) < 0) {
                    defects.add(button.value() + " is absent from the command roster");
                } else if (spell.manaCost() > 0 && fixture.panel().costLines(button).isEmpty()) {
                    defects.add(button.value() + " hides its mana cost");
                }
                continue;
            }

            UnitType product = fixture.data().unitTypes().types().get(button.value());
            if (product == null) {
                defects.add(action + " names missing " + button.value());
                continue;
            }
            if (fixture.commands().indexOf(product) < 0) {
                defects.add(button.value() + " is absent from the command roster");
            }
            long payable = product.costs().entrySet().stream()
                    .filter(entry -> entry.getKey() != UnitType.Resource.TIME)
                    .filter(entry -> entry.getValue() > 0)
                    .count();
            if (fixture.panel().costLines(button).size() != payable) {
                defects.add(action + " " + button.value() + " popup has "
                        + fixture.panel().costLines(button) + " for " + product.costs());
            }
            for (String producerIdent : button.forUnits()) {
                UnitType producer = fixture.data().unitTypes().types().get(producerIdent);
                if (producer == null) {
                    defects.add(action + " " + button.value() + " names missing "
                            + producerIdent);
                } else if ("build".equals(action)
                        && !fixture.world().mayBuild(producer, product)) {
                    defects.add(producerIdent + " cannot build " + button.value());
                } else if ("train-unit".equals(action)
                        && !fixture.world().mayTrain(producer, product)) {
                    defects.add(producerIdent + " cannot train " + button.value());
                }
            }
        }

        assertEquals(java.util.Map.of(
                "build", 34, "train-unit", 33, "upgrade-to", 8, "cast-spell", 23),
                counts, "the exhaustive production sweep loaded a partial button roster");
        assertEquals(List.of(), defects,
                "the interface advertises production that its shared catalog cannot execute");
    }
}
