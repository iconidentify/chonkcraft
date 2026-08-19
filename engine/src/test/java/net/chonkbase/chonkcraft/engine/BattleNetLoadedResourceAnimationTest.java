package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Laden workers used the right alternate sprite sheet but froze on frame zero.
 *
 * <p>The return-goods parity lane already follows {@code script.bin} for its
 * pixel cadence, so the same program is the authority for the frame that is
 * drawn. Land workers visit all five phases on their loaded sheets. Tankers
 * are the inverted control: their retail Move program deliberately selects
 * only frame zero, while the full/empty sheet swap remains visible.
 */
class BattleNetLoadedResourceAnimationTest {

    @Test
    @DisplayName("laden peasants and peons draw every retail walking phase")
    void ladenLandWorkersDrawEveryRetailWalkingPhase() {
        Scene scene = scene();
        for (String ident : List.of("unit-peasant", "unit-peon")) {
            UnitType type = scene.data().unitTypes().types().get(ident);
            assertNotNull(type, ident);
            for (UnitType.Resource resource
                    : List.of(UnitType.Resource.GOLD, UnitType.Resource.WOOD)) {
                Set<Integer> frames = walkOneTile(scene.world(), type, resource);
                assertEquals(Set.of(0, 5, 10, 15, 20), frames,
                        ident + " carrying " + resource
                                + " must use script.bin's five walking rows");
            }
        }
    }

    @Test
    @DisplayName("laden tankers use their full sheet without invented walk frames")
    void ladenTankersUseTheRetailStaticMoveFrame() {
        Scene scene = scene();
        for (String ident
                : List.of("unit-human-oil-tanker", "unit-orc-oil-tanker")) {
            UnitType type = scene.data().unitTypes().types().get(ident);
            assertNotNull(type, ident);
            assertNotEquals(type.imageFileFor("summer", UnitType.Resource.OIL, false),
                    type.imageFileFor("summer", UnitType.Resource.OIL, true),
                    ident + " must visibly carry oil");
            assertEquals(Set.of(0),
                    walkOneTile(scene.world(), type, UnitType.Resource.OIL),
                    ident + " has only frame zero in BNE's Move program");
        }
    }

    private static Set<Integer> walkOneTile(World world, UnitType type,
            UnitType.Resource resource) {
        Unit unit = new Unit(9000, type, 0, 20, 20);
        unit.setCarrying(resource);
        unit.setHeldResource(resource);
        unit.setCarried(100);
        unit.setReturningToDepot(true);
        unit.setOrder(Unit.Order.RETURN_GOODS);
        unit.setOffset(-32, 0);
        unit.setWalkHolding(true);

        world.movement.armBattleNetMovePace(unit);
        assertTrue(unit.battleNetMovePaceOffset() >= 0,
                type.ident() + " has no BNE Move program");

        int start = unit.pixelX();
        Set<Integer> frames = new LinkedHashSet<>();
        frames.add(unit.frame());
        for (int tick = 0; tick < 160 && unit.offsetX() < 0; tick++) {
            world.movement.walkPixels(unit, 1, 0);
            frames.add(unit.frame());
        }
        assertEquals(start + 32, unit.pixelX(),
                type.ident() + " did not finish the native one-tile pace");
        return frames;
    }

    private static Scene scene() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No BNE pack. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/orc/level01o", 0);
        Assumptions.assumeTrue(mission != null, "Orc 1 is not in the pack");
        return new Scene(data, mission.world());
    }

    private record Scene(GameData data, World world) {}
}
