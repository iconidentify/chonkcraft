package net.chonkbase.chonkcraft.engine.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Locks the second action-33 research milestone to authenticated XHuman 11 state. */
class BattleNetSequentialResearchRealDataTest {

    private static final int INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("XHuman 11 arms throwing-axe research after battle-axe research")
    void xhuman11ArmsThrowingAxeAfterBattleAxe() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx11h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 11 is not in the pack");
        World world = mission.world();
        Unit mill = world.unitsSnapshot().stream()
                .filter(unit -> unit.player() == 2
                        && unit.type() != null
                        && "unit-troll-lumber-mill".equals(unit.type().ident()))
                .findFirst().orElse(null);
        assertNotNull(mill, "XHuman 11 must contain native mill 1540 / Java 60");

        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 500) {
            mission.tick();
        }

        AiPlayer ai = world.ais().get(2);
        assertNotNull(ai, "XHuman 11 player 2 must retain its BNE AI profile");
        AiPlayer.BattleNetSavedState state = ai.savedBattleNetState();
        assertNotNull(state, "XHuman 11 player 2 must retain its ai.bin state");
        assertTrue(!state.action33Candidates().contains(0x80),
                "the second milestone must wait for the next ready-worker scan");
        assertEquals(1650, world.player(2).get(UnitType.Resource.GOLD));
        assertEquals(1200, world.player(2).get(UnitType.Resource.WOOD));
        assertEquals(null, mill.researching());

        mission.tick();

        assertEquals(501, fixtureCycle(world));
        state = ai.savedBattleNetState();
        assertTrue(state.action33Candidates().contains(0x80),
                "peon 1538's mine-exit ready scan must pass resolved 0x86"
                        + " and expose the next unresolved profile milestone");

        while (fixtureCycle(world) < 508) {
            mission.tick();
        }
        assertEquals(1650, world.player(2).get(UnitType.Resource.GOLD),
                "the mill's fixture-504 counter pulse is not yet the third pulse");
        assertEquals(1200, world.player(2).get(UnitType.Resource.WOOD));
        assertEquals(null, mill.researching());

        mission.tick();

        assertEquals(509, fixtureCycle(world));
        assertEquals("upgrade-throwing-axe1", mill.researching(),
                "native mill slot 1540 queues research order 37 on fixture 509");
        assertEquals(1350, world.player(2).get(UnitType.Resource.GOLD));
        assertEquals(900, world.player(2).get(UnitType.Resource.WOOD));
    }

    @Test
    @DisplayName("XHuman 10 spends the same second milestone on its own producer cadence")
    void xhuman10UsesItsOwnLumberMillCadence() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx10h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();
        Unit mill = world.unitsSnapshot().stream()
                .filter(unit -> unit.player() == 2
                        && unit.type() != null
                        && "unit-troll-lumber-mill".equals(unit.type().ident()))
                .findFirst().orElse(null);
        assertNotNull(mill, "XHuman 10 must retain its player-2 lumber mill");

        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 511) {
            mission.tick();
        }
        assertEquals(4750, world.player(2).get(UnitType.Resource.GOLD));
        assertEquals(4450, world.player(2).get(UnitType.Resource.WOOD));
        assertEquals(null, mill.researching());

        mission.tick();

        assertEquals(512, fixtureCycle(world));
        assertEquals("upgrade-throwing-axe1", mill.researching());
        assertEquals(4450, world.player(2).get(UnitType.Resource.GOLD));
        assertEquals(4150, world.player(2).get(UnitType.Resource.WOOD));
    }

    @Test
    @DisplayName("XHuman 9's unaffordable pending tower blocks the next milestone")
    void xhuman9UnaffordablePendingTowerKeepsThrowingAxeBlocked() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx09h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 9 is not in the pack");
        World world = mission.world();
        Unit mill = world.unitsSnapshot().stream()
                .filter(unit -> unit.player() == 6
                        && unit.type() != null
                        && "unit-troll-lumber-mill".equals(unit.type().ident()))
                .findFirst().orElse(null);
        assertNotNull(mill, "XHuman 9 must retain native mill 1479 / Java 121");

        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 507) {
            mission.tick();
        }

        AiPlayer ai = world.ais().get(6);
        assertNotNull(ai, "XHuman 9 player 6 must retain its BNE AI profile");
        AiPlayer.BattleNetSavedState state = ai.savedBattleNetState();
        assertNotNull(state, "XHuman 9 player 6 must retain its ai.bin state");
        assertTrue(!state.action33Candidates().contains(0x80),
                "the unaffordable 0x40 watch-tower slot must stop the ready scan"
                        + " before the second high milestone");

        while (fixtureCycle(world) < 514) {
            mission.tick();
        }
        assertEquals(450, world.player(6).get(UnitType.Resource.GOLD));
        assertEquals(1500, world.player(6).get(UnitType.Resource.WOOD));
        assertEquals(null, mill.researching(),
                "native keeps the lumber mill idle instead of spending 300/300");
    }

    private static int fixtureCycle(World world) {
        return Math.max(0, (int) world.cycle() - INITIALIZATION_TICKS);
    }
}
