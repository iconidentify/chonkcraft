package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.MapField;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A cycle-by-cycle referee for player-commanded ships on a retail shoreline.
 *
 * <p>A two-tile ship sprite legitimately overlaps the painted beach while its
 * BNE movement anchor remains in water. Looking at pixels therefore cannot
 * decide whether the ship drove on land. This referee asks the simulation's
 * authenticated map field on every cycle and prints the raw visual tile and
 * flag word if a hull ever crosses its legal domain.
 *
 * <p>The exception is explicit, not inferred: transports accept WATER or
 * COAST, while destroyers and oil tankers accept WATER only. Those are the
 * movement masks built by retail {@code UpdateUnitStats}.
 */
class BattleNetNavalLegalityRealDataTest {

    private record Passage(String ident, int targetX, int targetY) {}

    private static GameData data() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        return new GameData(assets);
    }

    @Test
    @DisplayName("Human 5 player ships remain on BNE-legal anchors every movement cycle")
    void humanFivePlayerShipsRemainOnLegalNavalAnchors() {
        GameData data = data();
        List<Passage> passages = List.of(
                new Passage("unit-human-transport", 10, 110),
                new Passage("unit-human-destroyer", 18, 114),
                new Passage("unit-human-oil-tanker", 24, 118));

        for (Passage passage : passages) {
            Mission mission = data.loadMission("campaigns/human/level05h");
            assertNotNull(mission, "Human 5 is present in the BNE pack");
            World world = mission.world();
            int person = GameData.personIn(data.campaignMap("campaigns/human/level05h"));
            Unit ship = world.unitsSnapshot().stream()
                    .filter(unit -> unit.type() != null
                            && passage.ident().equals(unit.type().ident())
                            && unit.player() == person)
                    .findFirst().orElse(null);
            assertNotNull(ship, "Human 5 has no " + passage.ident());
            int startX = ship.tileX();
            int startY = ship.tileY();

            CommandApplier commands = new CommandApplier(
                    world, new ArrayList<>(data.unitTypes().types().values()));
            commands.apply(GameCommand.move(
                    person, ship.id(), passage.targetX(), passage.targetY()));
            assertTrue(ship.order() == Unit.Order.MOVE
                            || ship.hasQueuedOrders()
                            || ship.queuedReplacementPending(),
                    passage.ident() + " refused the player move");

            int movingCycles = 0;
            for (int cycle = 0; cycle < 3_000 && (ship.order() != Unit.Order.STILL
                    || ship.hasQueuedOrders()
                    || ship.queuedReplacementPending()); cycle++) {
                world.tick();
                assertLegalAnchor(world, ship, cycle);
                if (ship.tileX() != startX || ship.tileY() != startY) {
                    movingCycles++;
                }
            }

            assertTrue(movingCycles > 0, passage.ident() + " never left its authored anchor");
            assertEquals(Unit.Order.STILL, ship.order(),
                    passage.ident() + " did not complete its player passage");
            assertEquals(passage.targetX(), ship.tileX(),
                    passage.ident() + " stopped at the wrong X anchor");
            assertEquals(passage.targetY(), ship.tileY(),
                    passage.ident() + " stopped at the wrong Y anchor");
        }
    }

    private static void assertLegalAnchor(World world, Unit ship, int cycle) {
        MapField field = world.map().field(ship.tileX(), ship.tileY());
        long terrain = field.flags()
                & (TileFlag.LAND_ALLOWED | TileFlag.COAST_ALLOWED | TileFlag.WATER_ALLOWED);
        String evidence = ship.type().ident() + " cycle " + cycle + " anchor "
                + ship.tileX() + "," + ship.tileY() + " visual tile " + field.tile()
                + " flags 0x" + Long.toHexString(field.flags());

        if (ship.type().canTransport()) {
            assertTrue((terrain & (TileFlag.WATER_ALLOWED | TileFlag.COAST_ALLOWED)) != 0,
                    "transport left BNE water/coast: " + evidence);
        } else {
            assertTrue((terrain & TileFlag.WATER_ALLOWED) != 0,
                    "non-transport ship left BNE water: " + evidence);
            assertEquals(0, terrain & (TileFlag.LAND_ALLOWED | TileFlag.COAST_ALLOWED),
                    "warship/tanker occupied land or coast: " + evidence);
        }
        assertEquals(0, field.flags() & TileFlag.UNPASSABLE,
                "ship occupied an impassable anchor: " + evidence);
    }
}
