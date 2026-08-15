package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A doubled mover keeps Move through the stride residual after an odd click.
 *
 * <p>Authenticated commanded fixture {@code batch-2/00}: Orc 5 balloon 1549
 * is clicked from 50,86 to 53,86 at fixture 5, steps onto 52,86 at 9, stays
 * on Move through 28, and is Still at 29. Wiping residual on the even
 * neighbour used to Still it at 10.
 */
class BattleNetOddDestMoveCadenceRealDataTest {

    @Test
    @DisplayName("a balloon keeps Move on the even neighbour after an odd east click")
    void aBalloonKeepsMoveOnTheEvenNeighbourAfterAnOddEastClick() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        GameMap map = new GameMap(32, 32, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map);
        data.configureWorld(world, PudMap.Tileset.FOREST);
        UnitType balloonType = data.unitTypes().types().get("unit-balloon");
        assertNotNull(balloonType, "retail roster has no balloon");
        Unit balloon = world.createUnit(balloonType, 0, 10, 10);
        CommandApplier commands = new CommandApplier(
                world, new ArrayList<>(data.unitTypes().types().values()));
        assertTrue(commands.apply(GameCommand.move(0, balloon.id(), 13, 10)),
                "the odd east click must be accepted");

        Integer parked = null;
        Unit.Order tenAfterPark = null;
        Integer stillAt = null;
        for (int i = 0; i < 80; i++) {
            world.tick();
            if (balloon.tileX() == 12 && parked == null) {
                parked = i;
            }
            if (parked != null && i == parked + 10) {
                tenAfterPark = balloon.order();
            }
            if (parked != null && balloon.order() == Unit.Order.STILL && stillAt == null) {
                stillAt = i;
            }
        }

        assertEquals(12, balloon.tileX(),
                "retail parks on the even neighbour of the odd click, not "
                        + balloon.tileX() + "," + balloon.tileY());
        assertEquals(10, balloon.tileY(),
                "retail parks on the even neighbour of the odd click, not "
                        + balloon.tileX() + "," + balloon.tileY());
        assertEquals(Unit.Order.MOVE, tenAfterPark,
                "retail is still on Move ten visits after the even-neighbour step, not "
                        + tenAfterPark);
        assertTrue(stillAt != null && stillAt >= parked + 15,
                "retail stands down after the Move body, not the visit it parked");
        assertEquals(Unit.Order.STILL, balloon.order(),
                "the balloon must still stand down after the residual drains");
    }
}
