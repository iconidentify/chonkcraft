package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Human 13 knight 1490 dest-arms south-east around the ogre on 124,32.
 */
class Human13KnightDestArmRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("human 13's still knight dest-arms south-east around the ogre at fixture 29")
    void human13sStillKnightDestArmsSouthEastAroundTheOgreAtFixture29() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No Warcraft II installation configured (-Dwc2.install.dir). ");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human/level13h", 0, 1);
        Assumptions.assumeTrue(mission != null, "Human 13 is not in the pack");
        World world = mission.world();
        List<UnitType> roster = new ArrayList<>(data.unitTypes().types().values());
        CommandApplier applier = new CommandApplier(world, roster);
        data.configureCommands(applier);

        Unit knight = unitAt(world, "unit-knight", 124, 30);
        Unit thrower = unitAt(world, "unit-axethrower", 118, 29);
        Unit quarry = unitAt(world, "unit-knight", 120, 29);
        assertNotNull(knight, "Human 13 has no knight on 124,30");
        assertNotNull(thrower, "Human 13 has no commanded axethrower on 118,29");
        assertNotNull(quarry, "Human 13 has no commanded knight on 120,29");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        for (int fixture = 1; fixture <= 29; fixture++) {
            if (fixture == 5) {
                assertTrue(applier.apply(GameCommand.attack(
                        thrower.player(), thrower.id(), quarry.id())),
                        "the commanded axe must be accepted");
            }
            mission.tick();
        }

        assertEquals(Unit.Order.ATTACK, knight.order(),
                "retail opens Attack on fixture 26 after the axe hit");
        assertEquals(125, knight.tileX(),
                "retail dest-arms south-east around the ogre on 124,32, not "
                        + knight.tileX() + "," + knight.tileY());
        assertEquals(31, knight.tileY(),
                "retail dest-arms south-east onto 125,31");
        assertEquals(124 * Unit.TILE_PIXELS,
                knight.tileX() * Unit.TILE_PIXELS + knight.offsetX(),
                "dest-arm keeps the pixels on 124,30");
        assertEquals(30 * Unit.TILE_PIXELS,
                knight.tileY() * Unit.TILE_PIXELS + knight.offsetY(),
                "dest-arm keeps the pixels on 124,30");
    }

    private static Unit unitAt(World world, String ident, int x, int y) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.isAlive() && unit.isOnMap() && unit.type() != null
                    && ident.equals(unit.type().ident())
                    && unit.tileX() == x && unit.tileY() == y) {
                return unit;
            }
        }
        return null;
    }
}
