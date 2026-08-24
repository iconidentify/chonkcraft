package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Locks cached melee approach construction and its first draw to native BNE. */
class Human13MeleeSyncConstructionRealDataTest {

    private static final int INITIALIZATION_TICKS = 2;

    @Test
    void knight1500PaysItsFirstMeleeDrawOnOp0NotConstructionTimerOne() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level13h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 13 is not in the pack");
        World world = mission.world();
        Unit knight = world.unitsSnapshot().stream()
                .filter(unit -> unit.player() == 1
                        && unit.type() != null
                        && "unit-knight".equals(unit.type().ident())
                        && unit.tileX() == 120 && unit.tileY() == 26)
                .findFirst().orElse(null);
        assertNotNull(knight, "Human 13 must contain native knight 1500 / Java 100");

        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 39) {
            mission.tick();
        }

        assertEquals(0xf94bdf32, world.randomSeed(),
                "fixture 39 is still the last Attack-construction tick");
        assertEquals(1, knight.pathLength(),
                "native keeps the final northeast chase heading cached through construction");
        assertEquals(1, knight.battleNetAnimationTimer(),
                "the cached approach waits at Attack-start construction timer one");

        mission.tick();
        assertEquals(0x95fb7483, world.randomSeed(),
                "fixture 40 OP0 pays knight 1500's first table-0x27 draw");

        while (fixtureCycle(world) < 43) {
            mission.tick();
        }
        assertEquals(0xd9e2b600, world.randomSeed(),
                "knight 1493's equivalent cached approach also pays on OP0");

        mission.tick();
        assertEquals(0xbf54bc7e, world.randomSeed(),
                "fixture 44 includes grunt 1485's OP0 draw before the wood draw");
    }

    @Test
    void grunt1507RetainsItsKnightRouteThroughAttackAndMoveConstruction() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level13h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 13 is not in the pack");
        World world = mission.world();
        Unit grunt = unitById(world, 93);
        assertNotNull(grunt,
                "Human 13 must contain native grunt 1507 / Java 93");

        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        for (int fixture = 1; fixture <= 166; fixture++) {
            mission.tick();
            if (fixture >= 162 && fixture <= 164) {
                assertEquals(2539, grunt.battleNetSequenceOffset(),
                        "the settled south residual owns fresh Attack construction");
                assertEquals(165 - fixture,
                        grunt.battleNetAnimationTimer(),
                        "native exposes Attack construction as 3,2,1");
                assertEquals(4, grunt.pathLength(),
                        "Attack construction retains S,SE,S,SE behind the spent S");
            } else if (fixture == 165 || fixture == 166) {
                assertEquals(2482, grunt.battleNetSequenceOffset(),
                        "timer one hands the retained buffer back to Move");
                assertEquals(180 - fixture,
                        grunt.battleNetAnimationTimer(),
                        "the retained route owns a full Move 15..1 band");
                assertEquals(120, grunt.tileX(),
                        "Move construction must not spend a cached heading early");
                assertEquals(25, grunt.tileY(),
                        "Move construction must remain on the residual landing tile");
                assertEquals(4, grunt.pathLength(),
                        "the retained knight route remains parked during Move construction");
            }
        }
    }

    private static Unit unitById(World world, int id) {
        return world.unitsSnapshot().stream()
                .filter(unit -> unit.id() == id)
                .findFirst().orElse(null);
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - INITIALIZATION_TICKS;
    }
}
