package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Retail-data coverage for an attack-march that reaches a melee victim. */
class BattleNetAttackMoveSequenceRealDataTest {

    @Test
    void attackMoveRunsTheNativeAttackProgramAfterReachingItsTarget() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set -Dchonkcraft.pack=... or wc2.install.dir");

        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human/level06h");
        assertNotNull(mission, "the retail Human 6 mission is missing");
        World world = mission.world();
        for (Unit unit : new java.util.ArrayList<>(world.units())) {
            world.remove(unit);
        }

        UnitType gruntType = data.unitTypes().types().get("unit-grunt");
        UnitType ballistaType = data.unitTypes().types().get("unit-ballista");
        assertNotNull(gruntType);
        assertNotNull(ballistaType);
        Unit grunt = world.createUnit(gruntType, 5, 40, 61);
        Unit ballista = world.createUnit(ballistaType, 1, 41, 61);
        assertNotNull(grunt);
        assertNotNull(ballista);

        assertTrue(world.orderAttackMove(grunt, ballista.tileX(), ballista.tileY()));
        int initialHealth = ballista.hitPoints();
        for (int cycle = 0; cycle < 180 && ballista.hitPoints() == initialHealth; cycle++) {
            world.tick();
        }

        assertTrue(ballista.hitPoints() < initialHealth,
                "the adjacent attack-march visibly swung but never delivered BNE opcode-10 damage");
    }
}
