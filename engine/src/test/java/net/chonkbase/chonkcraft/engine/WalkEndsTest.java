package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * When a walk is over, which is later than when the unit arrives.
 *
 * <p>Later by however much animation is left.  {@code DoActionMove} reaches
 * {@code NextPathElement} -- and so anything that could take another step or
 * end the order -- only when
 * {@code unit.Moving != 1 && (&Move != unit.Anim.CurrAnim || (unit.Anim.Wait
 * == 0 && unit.Anim.Anim == 0))}. The
 * second half of that is the part worth having: a unit squarely on its new
 * tile but still inside its move animation does nothing at all until the
 * animation ends. The two lengths are not the same. ChonkCraft's critter walks 32
 * pixels over sixteen {@code move 2} instructions and its animation is 48
 * cycles long, the last move landing on cycle 45, so upstream's critter is on
 * its tile from 45 and still walking until 50.
 *
 * <p>Found on {@code maps/skirmish/(3)critter-attack}, where 38 animals stand
 * at cycle 46 and upstream's are all still walking.
 */
class WalkEndsTest {

    /**
     * A walk that covers its whole tile at once and then keeps walking.
     *
     * <p>Deliberately lopsided so the two lengths cannot be confused: 32
     * pixels on the first cycle, and eleven more cycles of animation after the
     * unit is already standing on its new square.
     */
    private static final List<String> LONG_TAIL_MOVE = List.of(
            "unbreakable begin", "frame 0", "move 32", "wait 1",
            "frame 5", "wait 5",
            "frame 10", "wait 5",
            "frame 0", "unbreakable end", "wait 1");

    /** How long that animation runs, in cycles: 1 + 5 + 5 + 1. */
    private static final int MOVE_CYCLES = 12;

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static UnitType walker() {
        UnitType type = new UnitType("unit-peasant");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setNumDirections(8);
        AnimationSet set = new AnimationSet("walker");
        set.put(AnimationSet.State.STILL, Animation.parse("still", List.of(
                "frame 0", "wait 4", "random-goto 99 no-rotate",
                "random-rotate 1", "label no-rotate", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", LONG_TAIL_MOVE));
        type.setAnimationSet(set);
        return type;
    }

    @Test
    @DisplayName("a walk is not over when the tile is reached, but when the animation is")
    void theAnimationOutlastsTheArrival() {
        World world = new World(grass(20));
        Unit walker = world.createUnit(walker(), 0, 5, 5);
        assertTrue(world.orderMove(walker, 5, 6), "the order was refused");

        world.tick();
        assertEquals(6, walker.tileY(),
                "the walker did not cross its square on the first cycle, so this fixture is"
                        + " not the lopsided one it says it is");

        // On its new tile and still walking, for every cycle the animation has
        // left. Asking anything of the route here is what took a walk to be
        // over as soon as the pixels ran out.
        for (int cycle = 2; cycle < MOVE_CYCLES; cycle++) {
            world.tick();
            assertEquals(Unit.Order.MOVE, walker.order(),
                    "the walk was called over on cycle " + cycle + ", with " +
                            (MOVE_CYCLES - cycle) + " cycles of move animation still to run."
                            + " Upstream reaches nothing that could end it until the"
                            + " animation has let go of the unit");
        }
    }

    @Test
    @DisplayName("a critter one-tile residual settles Still without an empty-route wait")
    void aCritterOneTileResidualSettlesStillWithoutAnEmptyRouteWait() {
        // Human 4 critter 1578: arrive fixture 2, script.bin residual 48 cycles,
        // Still@50. PF_WAIT 10 after residual left MOVE until ~60 (six multi@50
        // still-vs-move cases). Requires real script.bin Move program.
        String pack = System.getProperty("chonkcraft.pack",
                System.getenv("CHONKCRAFT_ASSET_PACK"));
        org.junit.jupiter.api.Assumptions.assumeTrue(
                pack != null && !pack.isBlank()
                        && java.nio.file.Files.isRegularFile(
                                java.nio.file.Path.of(pack)),
                "skip without -Dchonkcraft.pack / CHONKCRAFT_ASSET_PACK");
        GameMap map = grass(32);
        World world = new World(map);
        try {
            java.util.zip.ZipFile z = new java.util.zip.ZipFile(pack);
            byte[] bin = z.getInputStream(
                    z.getEntry("assets/archives/maindat/0278.bin"))
                    .readAllBytes();
            z.close();
            world.setBattleNetSequenceData(bin);
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "script.bin not loadable from pack: " + e);
        }
        UnitType type = new UnitType("unit-critter");
        type.setTileSize(1, 1);
        type.setHitPoints(5);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setCanAttack(false);
        AnimationSet set = new AnimationSet("critter");
        set.put(AnimationSet.State.STILL, Animation.parse("still",
                List.of("frame 0", "wait 4")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin",
                "frame 0", "move 2", "wait 1",
                "frame 0", "move 2", "wait 1",
                "frame 0", "move 2", "wait 1",
                "frame 0", "move 2", "wait 1",
                "frame 0", "move 2", "wait 1",
                "frame 0", "move 2", "wait 1",
                "frame 0", "move 2", "wait 1",
                "frame 0", "move 2", "wait 1",
                "frame 0", "move 2", "wait 1",
                "frame 0", "move 2", "wait 1",
                "frame 0", "move 2", "wait 1",
                "frame 0", "move 2", "wait 1",
                "frame 0", "move 2", "wait 1",
                "frame 0", "move 2", "wait 1",
                "frame 0", "move 2", "wait 1",
                "frame 0", "move 2", "wait 1",
                "unbreakable end", "wait 1")));
        type.setAnimationSet(set);
        Unit critter = world.createUnit(type, 15, 5, 5);
        assertTrue(world.orderMove(critter, 6, 5), "move accepted");
        int stillCycle = -1;
        for (int i = 0; i < 70; i++) {
            world.tick();
            if (critter.order() == Unit.Order.STILL) {
                stillCycle = i + 1;
                break;
            }
        }
        assertTrue(stillCycle > 0, "critter must eventually Still");
        assertEquals(6, critter.tileX(), "critter completed its one-tile step");
        // script.bin residual is 48 cycles after the delayed step (order delay
        // 2 + 48 ≈ 50-52). PF_WAIT 10 after residual pushed Still to ~60+.
        // ChonkCraft-only residual without script.bin pace Stills too early (~26).
        assertTrue(stillCycle >= 48 && stillCycle <= 55,
                "critter Still at cycle " + stillCycle
                        + " is outside the native residual band [48,55]; "
                        + "script.bin pace or residual-settle Still is missing");
    }

}
