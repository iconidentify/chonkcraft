package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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

class BattleNetPlayerAttackTargetRealDataTest {
    @Test
    @DisplayName("an explicit attack retains its quarry when a nearer enemy enters reaction range")
    void explicitAttackRetainsItsQuarryWhenANearerEnemyEntersReactionRange() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null, "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level01h";
        int person = GameData.personIn(data.campaignMap(map));
        for (boolean stand : new boolean[] {false, true}) {
            Mission mission = data.loadMission(map, person, 1);
            World world = mission.world();
            Unit footman = world.units().stream().filter(u -> u.tileX() == 21
                    && u.tileY() == 5).findFirst().orElseThrow();
            Unit quarry = world.units().stream().filter(u -> u.tileX() == 20
                    && u.tileY() == 31).findFirst().orElseThrow();
            CommandApplier commands = new CommandApplier(world,
                    new ArrayList<>(data.unitTypes().types().values()));
            data.configureCommands(commands);
            mission.tick();
            mission.tick();
            // Sealed Human 1 GiveOrder captures 010 and 047: Stop/Stand
            // Ground at 5, Attack slot 1588 at 6. Native order 9 retains
            // +0x88 while walking past grunt 1592; automatic order 12 may scan.
            for (int cycle = 1; cycle <= 400; cycle++) {
                if (cycle == 5) {
                    assertTrue(commands.apply(stand
                            ? GameCommand.standGround(person, footman.id())
                            : GameCommand.stop(person, footman.id())),
                            "the initial Stop or Stand Ground must be accepted");
                }
                if (cycle == 6) {
                    assertTrue(commands.apply(GameCommand.attack(person, footman.id(), quarry.id())),
                            "the explicit Attack must be accepted");
                }
                mission.tick();
                if (cycle >= 9) {
                    assertSame(quarry, footman.target(), "the commanded quarry at native cycle " + cycle);
                }
                if (cycle == 89) {
                    assertEquals(25, footman.tileX(), "the commanded footman must pass east of the nearer grunt");
                    assertEquals(10, footman.tileY(), "the retained quarry must keep the native southward route");
                }
            }
            assertTrue(footman.isAlive(), "BNE's footman survives the commanded pursuit");
            assertEquals(39, footman.hitPoints(), "retaining the quarry must preserve native received damage");
            assertEquals(23, footman.tileX(), "the pursuit must finish at the native column");
            assertEquals(27, footman.tileY(), "the pursuit must finish at the native row");
        }
    }
}
