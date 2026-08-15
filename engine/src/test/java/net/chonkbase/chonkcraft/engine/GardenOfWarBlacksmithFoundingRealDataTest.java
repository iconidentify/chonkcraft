package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.map.PudReader;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Garden of War replay record 2151 founds an orc blacksmith at 90,6.
 *
 * <p>The authenticated packet is {@code 09 53 5a00 0600}. Native has that
 * blacksmith by record 3477 (unit 1554 researching orc-shield1). Java used
 * to accept the walk and then never found, because 90,6 sits on the
 * converted great hall at 89,5.
 */
class GardenOfWarBlacksmithFoundingRealDataTest {

    private static GameData load() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No Warcraft II retail assets configured.");
        return new GameData(assets);
    }

    @Test
    @DisplayName("a garden of war blacksmith packet founds at the clicked site")
    void aGardenOfWarBlacksmithPacketFoundsAtTheClickedSite() {
        GameData data = load();
        byte[] bytes = data.source().map("Classic/Garden of War.pud");
        if (bytes == null) {
            bytes = data.source().map("classic/garden-of-war.pud");
        }
        Assumptions.assumeTrue(bytes != null, "Garden of War is not in this pack");
        PudMap source = PudReader.read(bytes);

        Player[] players = Player.from(source);
        // Replay lobby seats player 6 as orc.
        players[6] = new Player(6, PudMap.PlayerType.PERSON, PudMap.Race.ORC);

        World world = new World(
                GameMap.from(source, data.loadTileset(source.tileset()).tileset()),
                players);
        data.configureWorld(world, source);
        data.populate(world, source);
        world.recalculateSupply();
        world.player(6).set(UnitType.Resource.GOLD, 10_000);
        world.player(6).set(UnitType.Resource.WOOD, 5_000);
        world.player(6).set(UnitType.Resource.OIL, 5_000);

        Unit hall = null;
        Unit peon = null;
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.player() != 6 || !unit.isAlive()) {
                continue;
            }
            String ident = unit.type().ident();
            if (ident.contains("hall")) {
                hall = unit;
            }
            if (unit.canMove() && !unit.type().building()) {
                peon = unit;
            }
        }
        assertNotNull(hall, "player 6 has no hall after populate");
        assertNotNull(peon, "player 6 has no worker after populate");

        java.util.List<UnitType> roster = new java.util.ArrayList<>(
                data.unitTypes().types().values());
        CommandApplier applier = new CommandApplier(world, roster);
        data.configureCommands(applier);
        int blacksmith = applier.indexOf(data.unitTypes().types().get("unit-orc-blacksmith"));
        assertTrue(blacksmith >= 0, "orc blacksmith is not in the roster");

        assertTrue(applier.apply(GameCommand.build(6, peon.id(), blacksmith, 90, 6)),
                "the authenticated 2151 packet was refused");

        boolean founded = false;
        for (int i = 0; i < 800; i++) {
            world.tick();
            for (Unit unit : world.unitsSnapshot()) {
                if (unit.player() == 6 && unit.type() != null
                        && "unit-orc-blacksmith".equals(unit.type().ident())) {
                    founded = true;
                    assertEquals(90, unit.tileX(),
                            "the blacksmith was founded at " + unit.tileX() + ","
                                    + unit.tileY() + " instead of the packet site");
                    assertEquals(6, unit.tileY(),
                            "the blacksmith was founded at " + unit.tileX() + ","
                                    + unit.tileY() + " instead of the packet site");
                    break;
                }
            }
            if (founded) {
                break;
            }
            if (peon.order() == Unit.Order.STILL && peon.pendingBuild() == null) {
                break;
            }
        }
        assertTrue(founded,
                "peon " + peon.id() + " at " + peon.tileX() + "," + peon.tileY()
                        + " order=" + peon.order()
                        + " hall=" + hall.type().ident() + "@"
                        + hall.tileX() + "," + hall.tileY()
                        + " " + hall.type().tileWidth() + "x"
                        + hall.type().tileHeight()
                        + " never founded the blacksmith at 90,6");
    }
}
