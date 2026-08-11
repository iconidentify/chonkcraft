package net.chonkbase.chonkcraft.engine.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.ResourceInfo;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.unit.UnitType.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The census weighs a mine by who already works it.
 *
 * <p>{@code AiAssignHarvesterFromUnit} asks {@code UnitFindResource} with
 * {@code check_usage} on, and the cost it minimises is a triple compared in
 * order -- waiting workers, distance to the depot, assigned workers
 * The game where
 * assigned counts every worker of every player bound to the mine for its
 * whole round trip. On campaigns/human/level08h the drafted siege peasant
 * stands ten squares from a mine five rival peons already work, and upstream
 * walks it across the map to the empty one at 21,28: cost 0/0/0 beats 0/0/5.
 * This implementation's census used to keep no counts at all and took the near mine.
 */
class CensusMinePickTest {

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static AnimationSet worker() {
        AnimationSet set = new AnimationSet("w");
        set.put(AnimationSet.State.STILL, Animation.parse("s", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("m",
                List.of("frame 0", "move 16", "wait 1", "frame 5", "move 16", "wait 1")));
        set.put(AnimationSet.State.DEATH, Animation.parse("d", List.of("frame 50", "wait 1")));
        return set;
    }

    private static UnitType peasant() {
        UnitType type = new UnitType("unit-peasant");
        type.setTileSize(1, 1);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setSightRange(4);
        type.setAnimationSet(worker());
        ResourceInfo gold = new ResourceInfo(Resource.GOLD);
        gold.setCapacity(100);
        gold.setWaitAtResource(2);
        gold.setWaitAtDepot(2);
        type.gathering().put(Resource.GOLD, gold);
        return type;
    }

    private static UnitType goldMine() {
        UnitType type = new UnitType("unit-gold-mine");
        type.setTileSize(3, 3);
        type.setHitPoints(100_000);
        type.setBuilding(true);
        type.setGivesResource(Resource.GOLD);
        type.setCanHarvest(true);
        return type;
    }

    private static Player[] players() {
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            PudMap.PlayerType kind = switch (i) {
                case 0 -> PudMap.PlayerType.COMPUTER;
                case 1 -> PudMap.PlayerType.PERSON;
                case World.NEUTRAL_PLAYER -> PudMap.PlayerType.NEUTRAL;
                default -> PudMap.PlayerType.NOBODY;
            };
            players[i] = new Player(i, kind, PudMap.Race.HUMAN);
        }
        return players;
    }

    @Test
    @DisplayName("a worker with no depot walks past a crowded mine to an empty one")
    void aCrowdedMineIsPassedOverForAnEmptyOne() {
        World world = new World(grass(48), players());
        world.establishDiplomacy();
        UnitType mineType = goldMine();
        Unit near = world.createUnit(mineType, World.NEUTRAL_PLAYER, 22, 20);
        near.setResourcesHeld(50_000);
        Unit far = world.createUnit(mineType, World.NEUTRAL_PLAYER, 40, 20);
        far.setResourcesHeld(50_000);

        // Five rival workers bound to the near mine: walking, waiting or
        // inside, every one of them counts against it for every player.
        UnitType rival = peasant();
        for (int i = 0; i < 5; i++) {
            Unit miner = world.createUnit(rival, 1, 26 + i, 24);
            assertTrue(world.orderHarvest(miner, near.tileX(), near.tileY()),
                    "the fixture's rival miners must take the harvest order");
        }

        // The one under test: no depot anywhere, so the depot-distance term
        // is nought for every mine -- level08h's drafted peasant exactly --
        // and the assigned count is all that separates the two.
        Unit drafted = world.createUnit(peasant(), 0, 20, 20);

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);

        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND; cycle++) {
            world.tick();
        }

        assertNotNull(drafted.resourceUnit(),
                "the census never put the worker on a mine at all, so this measures"
                        + " nothing");
        assertEquals(far, drafted.resourceUnit(),
                "the census must send its worker past the mine five rivals already"
                        + " work to the empty one, as UnitFindResource's check_usage"
                        + " cost does: 0/0/0 beats 0/0/5, which is level08h's peasant"
                        + " crossing the map at cycle 132");
    }
}
