package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated AI oil-platform exit placement from retail BNE. */
class BattleNetAiOilPlatformExitRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("an Orc 8 tanker tests the exit anchor instead of its drawn hull")
    void anOrc8TankerUsesTheNativeOverlappingWestAnchor() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/orc/level08o";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        for (int cycle = 1; cycle < 155; cycle++) {
            mission.tick();
        }

        mission.tick();
        Unit tanker = at(mission.world(), "unit-human-oil-tanker", 84, 104);
        assertNotNull(tanker,
                "native slot 1482 surfaces on the platform's west anchor at fixture 155");
        assertEquals(Unit.Order.STILL, tanker.order());
        assertTrue(tanker.battleNetDoubleStep());
        assertEquals(0, (tanker.tileX() | tanker.tileY()) & 1);

        Unit platform = at(mission.world(), "unit-human-oil-platform", 85, 103);
        assertNotNull(platform);
        assertTrue(tanker.tileX() + tanker.type().tileWidth() > platform.tileX(),
                "the proof must retain native's visual hull/platform overlap");

        for (int cycle = 156; cycle <= 179; cycle++) {
            mission.tick();
            assertEquals(Unit.Order.STILL, tanker.order(),
                    "native's queued return must respect the ready delay on cycle " + cycle);
            assertEquals(84, tanker.tileX());
            assertEquals(104, tanker.tileY());
        }
        mission.tick();
        assertEquals(Unit.Order.RETURN_GOODS, tanker.order(),
                "native promotes the delayed return order on fixture cycle 180");
        assertEquals(84, tanker.tileX());
        assertEquals(104, tanker.tileY());

        mission.tick();
        mission.tick();
        assertEquals(84, tanker.tileX(),
                "native holds the route anchor through fixture cycle 182");
        assertEquals(104, tanker.tileY());
        mission.tick();
        assertEquals(84, tanker.tileX(),
                "native takes its first doubled north stride on fixture cycle 183");
        assertEquals(102, tanker.tileY());

        Unit southTanker = at(mission.world(), "unit-human-oil-tanker", 84, 106);
        assertNotNull(southTanker,
                "the neighboring native tanker is the non-overlap control");
        for (int cycle = 184; cycle <= 200; cycle++) {
            mission.tick();
            assertEquals(84, southTanker.tileX(),
                    "a clear south-face hull must not inherit the overlap rule on cycle "
                            + cycle);
            assertEquals(106, southTanker.tileY());
        }
    }

    @Test
    @DisplayName("an XOrc 11 tanker uses BNE's unrounded east platform face")
    void anXOrc11TankerUsesTheUnroundedEastPlatformFace() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/orc-exp/levelx11o";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (mission.world().cycle() - BNE_INITIALIZATION_TICKS < 160) {
            mission.tick();
        }

        Unit tanker = at(mission.world(), "unit-human-oil-tanker", 8, 20);
        assertNotNull(tanker,
                "native slot 1552 surfaces on the platform's east face at fixture 160");
        assertEquals(Unit.Order.STILL, tanker.order());
        assertTrue(tanker.battleNetDoubleStep(),
                "the even east-face anchor keeps the doubled naval lattice");
    }

    @Test
    @DisplayName("an XHuman 8 tanker leaves its platform on BNE's even anchor grid")
    void anXHuman8TankerLeavesItsPlatformOnTheEvenAnchorGrid() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx08h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        for (int cycle = 1; cycle < 258; cycle++) {
            mission.tick();
        }
        mission.tick();

        Unit tanker = at(mission.world(), "unit-orc-oil-tanker", 66, 58);
        assertNotNull(tanker,
                "native slot 1538 surfaces south of its platform on fixture cycle 258");
        assertEquals(Unit.Order.STILL, tanker.order(),
                "the platform exit exposes the naval ready boundary");
        assertTrue(tanker.battleNetDoubleStep(),
                "the contained tanker must retain native unit+0x1c bit 1");
        assertEquals(0, (tanker.tileX() | tanker.tileY()) & 1,
                "native retains its doubled-grid bit and rejects odd dropout anchors");

        for (int cycle = 259; cycle <= 282; cycle++) {
            mission.tick();
            assertEquals(Unit.Order.STILL, tanker.order(),
                    "the naval ready window must hold through fixture cycle " + cycle);
            assertEquals(66, tanker.tileX());
            assertEquals(58, tanker.tileY());
        }
        mission.tick();
        assertEquals(Unit.Order.RETURN_GOODS, tanker.order(),
                "native promotes queued action 24 on fixture cycle 283");
        mission.tick();
        mission.tick();
        mission.tick();
        assertEquals(64, tanker.tileX(),
                "native takes its first doubled west stride on fixture cycle 286");
        assertEquals(58, tanker.tileY());

        for (int cycle = 287; cycle < 318; cycle++) {
            mission.tick();
            assertEquals(64, tanker.tileX(),
                    "the west stride must drain through fixture cycle " + cycle);
            assertEquals(58, tanker.tileY());
        }
        mission.tick();
        assertEquals(62, tanker.tileX(),
                "native takes the refinery wall route's northwest stride on cycle 318");
        assertEquals(56, tanker.tileY(),
                "the marked refinery skirt must beat the straight blocked-goal prefix");

        for (int cycle = 319; cycle <= 381; cycle++) {
            mission.tick();
        }
        assertEquals(60, tanker.tileX(),
                "the final doubled west stride lands on the outer refinery skirt");
        assertEquals(56, tanker.tileY());
        int oilBefore = mission.world().player(tanker.player())
                .get(UnitType.Resource.OIL);

        mission.tick();
        assertTrue(tanker.isOnMap(),
                "native action 25 remains visible on refinery visit 382");
        assertTrue(tanker.battleNetResourceApproachStaged(),
                "the doubled outer skirt must arm native's three-visit depot stage");
        mission.tick();
        assertTrue(tanker.isOnMap(),
                "native action 25 remains visible on refinery visit 383");
        mission.tick();
        assertTrue(tanker.isOnMap(),
                "native action 25 remains visible on refinery visit 384");
        mission.tick();
        assertFalse(tanker.isOnMap(),
                "native banks and enters hidden action 26 on fixture cycle 385");
        assertEquals(oilBefore + 125,
                mission.world().player(tanker.player()).get(UnitType.Resource.OIL),
                "the refinery's 125-percent income must land on the entry cycle");
    }

    private static Unit at(World world, String ident, int x, int y) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.isAlive() && unit.isOnMap() && unit.tileX() == x
                    && unit.tileY() == y && unit.type() != null
                    && ident.equals(unit.type().ident())) {
                return unit;
            }
        }
        return null;
    }
}
