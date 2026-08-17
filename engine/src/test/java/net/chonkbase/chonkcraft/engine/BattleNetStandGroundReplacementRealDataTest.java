package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Retail GiveOrder 13 replaces a march after its committed pixels land. */
class BattleNetStandGroundReplacementRealDataTest {

    private static final String MAP = "campaigns/orc/level01o";

    @Test
    @DisplayName("stand-ground lands the current peon step instead of finishing its route")
    void standGroundLandsOnlyTheCurrentPeonStep() {
        Result result = run(25, 18, 22, 18);
        assertEquals(24, result.x(), "retail holds on the current step's landing tile");
        assertEquals(18, result.y(), "the horizontal step changed rows");
        assertEquals(24, result.holdCycle(), "retail pops order 15 at fixture cycle 24");
    }

    @Test
    @DisplayName("stand-ground lands the current grunt step instead of finishing its route")
    void standGroundLandsOnlyTheCurrentGruntStep() {
        Result result = run(18, 23, 22, 23);
        assertEquals(19, result.x(), "retail holds on the current step's landing tile");
        assertEquals(22, result.y(), "the diagonal step lands one row north");
        assertEquals(28, result.holdCycle(), "retail pops order 15 at fixture cycle 28");
    }

    private static Result run(int startX, int startY, int destinationX,
            int destinationY) {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission(MAP,
                GameData.personIn(data.campaignMap(MAP)), 1);
        Assumptions.assumeTrue(mission != null, "Orc 1 is not in the pack");
        World world = mission.world();
        CommandApplier commands = new CommandApplier(
                world, new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);
        mission.tick();
        mission.tick();
        Unit actor = atTile(world, startX, startY);
        assertNotNull(actor, "Orc 1 has no actor on " + startX + "," + startY);
        int holdCycle = -1;
        for (int cycle = 1; cycle <= 80; cycle++) {
            if (cycle == 5) {
                assertTrue(commands.apply(GameCommand.move(actor.player(), actor.id(),
                                destinationX, destinationY)),
                        "the opening move must be accepted");
            }
            if (cycle == 20) {
                assertTrue(commands.apply(GameCommand.standGround(
                                actor.player(), actor.id())),
                        "the replacement hold must be accepted");
            }
            mission.tick();
            if (holdCycle < 0 && actor.order() == Unit.Order.STAND_GROUND) {
                holdCycle = cycle;
            }
        }
        assertEquals(Unit.Order.STAND_GROUND, actor.order(),
                "the replacement must remain a hold");
        assertEquals(0, actor.offsetX(), "the hold retained a horizontal residual");
        assertEquals(0, actor.offsetY(), "the hold retained a vertical residual");
        return new Result(actor.tileX(), actor.tileY(), holdCycle);
    }

    private static Unit atTile(World world, int x, int y) {
        return world.unitsSnapshot().stream()
                .filter(Unit::isAlive)
                .filter(Unit::isOnMap)
                .filter(unit -> unit.tileX() == x && unit.tileY() == y)
                .filter(unit -> unit.type() != null && !unit.type().building())
                .findFirst().orElse(null);
    }

    private record Result(int x, int y, int holdCycle) { }
}
