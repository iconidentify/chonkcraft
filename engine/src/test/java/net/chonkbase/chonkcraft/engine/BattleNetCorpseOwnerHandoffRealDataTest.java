package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Locks the fallback corpse owner edge for mobile bodies and building rubble. */
class BattleNetCorpseOwnerHandoffRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("building rubble carries its first owner edge for one callback")
    void buildingRubbleCarriesItsFirstOwnerEdgeForOneCallback() {
        Mission mission = mission("campaigns/human-exp/levelx12h");
        World world = mission.world();
        Unit rubble = unitById(world, 230);
        assertNotNull(rubble, "XHuman 12 has no native-slot-1370 building");

        initializeAndAdvance(mission, world, 374);
        assertEquals("unit-destroyed-2x2-place", rubble.type().ident());
        assertEquals(1, rubble.player());

        mission.tick();
        assertEquals(375, fixtureCycle(world));
        assertEquals(1, rubble.player(),
                "the first fallback frame transition retains the building owner");
        assertTrue(rubble.battleNetCorpseOwnerHandoffPending());

        mission.tick();
        assertEquals(376, fixtureCycle(world));
        assertEquals(World.NEUTRAL_PLAYER, rubble.player(),
                "the following corpse callback performs the native handoff");
        assertFalse(rubble.battleNetCorpseOwnerHandoffPending());
    }

    @Test
    @DisplayName("mobile bodies hand ownership over on their frame transition")
    void mobileBodiesDoNotInheritTheRubbleDelay() {
        Mission humanTwo = mission("campaigns/human-exp/levelx02h");
        World humanTwoWorld = humanTwo.world();
        Unit humanTwoBody = unitById(humanTwoWorld, 52);
        assertNotNull(humanTwoBody, "XHuman 2 has no native-slot-1548 grunt");

        initializeAndAdvance(humanTwo, humanTwoWorld, 349);
        assertEquals("unit-human-dead-body", humanTwoBody.type().ident());
        assertEquals(1, humanTwoBody.player());
        humanTwo.tick();
        assertEquals(350, fixtureCycle(humanTwoWorld));
        assertEquals(World.NEUTRAL_PLAYER, humanTwoBody.player());
        assertFalse(humanTwoBody.battleNetCorpseOwnerHandoffPending());

        Mission humanTen = mission("campaigns/human-exp/levelx10h");
        World humanTenWorld = humanTen.world();
        Unit humanTenBody = unitById(humanTenWorld, 108);
        assertNotNull(humanTenBody, "XHuman 10 has no native-slot-1492 grunt");

        initializeAndAdvance(humanTen, humanTenWorld, 348);
        humanTen.tick();
        assertEquals(349, fixtureCycle(humanTenWorld));
        assertEquals("unit-human-dead-body", humanTenBody.type().ident());
        assertEquals(World.NEUTRAL_PLAYER, humanTenBody.player());
        assertFalse(humanTenBody.battleNetCorpseOwnerHandoffPending());
    }

    private static Mission mission(String map) {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        return mission;
    }

    private static void initializeAndAdvance(Mission mission, World world,
            int fixture) {
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < fixture) {
            mission.tick();
        }
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }

    private static Unit unitById(World world, int id) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.id() == id) {
                return unit;
            }
        }
        return null;
    }
}
