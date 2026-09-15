package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    /**
     * Human 14's objective has neutral owner 15. Native command captures
     * ad0b3da9bf55 (Attack) and e833405bd914 (right click), BNE 2.02b, put
     * mage 1425 into Attack and Move respectively. The explicit constructor
     * at 0x436850 has no diplomacy gate; damage at 0x4184d0 does not protect
     * this campaign's portal. The port rejected every ordinary weapon before
     * it could begin approaching, and also discarded queued neutral attacks.
     */
    @Test
    @DisplayName("human 14's neutral portal accepts immediate and queued ordinary attacks")
    void neutralPortalAcceptsImmediateAndQueuedOrdinaryAttacks() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null, "Warcraft II assets are required");
        GameData data = new GameData(assets);
        for (String ident : new String[] {"unit-gryphon-rider", "unit-mage", "unit-footman"}) {
            for (boolean queued : new boolean[] {false, true}) {
                Mission mission = data.loadMission("campaigns/human/level14h");
                World world = mission.world();
                int person = GameData.personIn(mission.source());
                Unit portal = world.units().stream()
                        .filter(u -> "unit-dark-portal".equals(u.type().ident()))
                        .findFirst().orElseThrow();
                Unit attacker = world.createUnit(data.unitTypes().types().get(ident),
                        person, portal.tileX(), portal.tileY() + 4);
                world.fog().revealAll(person);
                CommandApplier commands = new CommandApplier(world,
                        new ArrayList<>(data.unitTypes().types().values()));
                data.configureCommands(commands);
                assertEquals(World.NEUTRAL_PLAYER, portal.player(), "the campaign objective is neutral");
                assertFalse(world.orderAttack(attacker, portal),
                        "automatic acquisition must continue to refuse neutral targets");
                assertFalse(world.canCommandAttack(attacker, attacker),
                        "an explicit order must still refuse self attack");
                if (queued) {
                    assertTrue(commands.apply(GameCommand.move(person, attacker.id(),
                            portal.tileX() + 1, portal.tileY() + 4)), "the preceding move must start");
                }
                assertTrue(commands.apply(GameCommand.attack(person, attacker.id(), portal.id())
                        .withQueued(queued)), ident + " must accept the portal attack");
                int health = portal.hitPoints();
                for (int cycle = 0; cycle < 800 && portal.hitPoints() == health; cycle++) {
                    mission.tick();
                }
                assertTrue(portal.hitPoints() < health,
                        ident + " must damage the portal after " + (queued ? "queued" : "immediate")
                                + " Attack; ended " + attacker.order() + " at "
                                + attacker.tileX() + "," + attacker.tileY());
                Unit bystander = world.createUnit(data.unitTypes().types().get("unit-footman"),
                        World.NEUTRAL_PLAYER, portal.tileX() - 3, portal.tileY() + 4);
                assertNotNull(bystander, "the non-commanded neutral bystander must exist");
                assertFalse(world.targets.validAttackTarget(attacker, bystander),
                        "explicit authority belongs only to the commanded quarry");
            }
        }
    }

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
