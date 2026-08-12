package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.unit.UnitType.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a builder does about somebody standing on its ground.
 *
 * <p>It waits, and for a long time. {@code COrder_Build} counts the refusals in
 * its own {@code State}: the order reaches {@code State_NearOfLocation}, 11,
 * when the worker arrives, and every refusal from {@code CheckCanBuild} does
 * {@code this->State++} with {@code unit.Wait = 10} -- the comment on the line
 * is "To keep the load low, retry each 10 cycles"
 * Only when {@code State} reaches
 * {@code State_StartBuilding_Failed}, 20, does the order end. Nine tries,
 * ninety cycles, for the ground to clear.
 *
 * <p>This implementation abandoned the job on the first refusal. On
 * {@code maps/skirmish/(3)critter-attack} that is cycle 53: an orc peasant at
 * 65,78 with a town hall to put down at 62,78 finds a critter wandering through
 * the footprint, and upstream's is still building at cycle 62 where this implementation's
 * had gone back to standing. It is the whole of that map's divergence there,
 * and putting the wait in took it from cycle 53 to 63.
 *
 * <p>That patience belongs to the DOS order and to the player's own builders.
 * Retail's computer player does not share it: it pays when it installs the job,
 * and when its worker arrives on occupied ground it gives the job back and
 * takes the money with it on that same cycle. Both are tested here, because
 * fitting either rule to both builders breaks the other.
 */
class BlockedBuildSiteTest {

    private static byte[] retailScriptBin() throws IOException {
        String packProp = System.getProperty("chonkcraft.pack");
        Path pack = packProp != null && !packProp.isBlank()
                ? Path.of(packProp)
                : Path.of(System.getProperty("user.home"),
                        ".chonkcraft/packs/warcraft-ii-battle-net-edition-usa.chonkpack");
        assumeTrue(Files.isRegularFile(pack),
                "BNE asset pack required for the worker Still sequence");
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            var entry = zip.getEntry("assets/archives/maindat/0278.bin");
            assumeTrue(entry != null, "pack must contain maindat entry 278");
            try (var in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static AnimationSet walker() {
        AnimationSet set = new AnimationSet("walker");
        set.put(AnimationSet.State.STILL, Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 16", "wait 1",
                "frame 5", "move 16", "unbreakable end", "wait 1")));
        return set;
    }

    private static UnitType peasant() {
        UnitType type = new UnitType("unit-peasant");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setNumDirections(8);
        type.setAnimationSet(walker());
        return type;
    }

    /** Standing on the ground and not going anywhere. */
    private static UnitType boulder() {
        UnitType type = new UnitType("unit-boulder");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(100);
        type.setLandUnit(true);
        type.setNumDirections(1);
        return type;
    }

    private static UnitType farm() {
        UnitType type = new UnitType("unit-farm");
        type.setTileSize(2, 2);
        type.setHitPoints(400);
        type.setBuilding(true);
        type.costs().put(Resource.TIME, 1);
        type.costs().put(Resource.GOLD, 100);
        return type;
    }

    /** Cycles the order survives per refusal, times the nine refusals. */
    private static final int PATIENCE = 90;

    @Test
    @DisplayName("a builder waits for its ground to clear instead of giving the job up")
    void aBlockedSiteIsWaitedOutRatherThanAbandoned() {
        World world = new World(grass(20));
        world.setBuilders(java.util.Map.of("unit-farm", java.util.Set.of("unit-peasant")));
        world.player(0).set(Resource.GOLD, 5000);
        Unit worker = world.createUnit(peasant(), 0, 5, 5);
        assertTrue(world.orderBuild(worker, farm(), 8, 8), "the build order was refused");
        // Onto the ground after the order is given and before the worker gets
        // there, which is the case this is about: the site was clear when the
        // job was handed out and somebody has since walked onto it. On the far
        // corner of the farm's two-by-two rather than the square the worker
        // walks to, so that it can arrive and find the footprint fouled rather
        // than never arrive at all.
        world.createUnit(boulder(), 0, 9, 9);

        int lastBuilding = -1;
        int ended = -1;
        for (int cycle = 1; cycle <= 400; cycle++) {
            world.tick();
            if (worker.order() == Unit.Order.BUILD) {
                lastBuilding = cycle;
            } else if (lastBuilding > 0 && ended < 0) {
                ended = cycle;
            }
        }

        assertTrue(lastBuilding >= PATIENCE,
                "the worker gave the job up on cycle " + lastBuilding + ". A refused site is"
                        + " worth ten cycles and another look, nine times over, because the"
                        + " thing in the way is usually walking past");
        assertTrue(ended > 0 && ended < 400,
                "the worker never gave the job up at all, and it has to: the order ends when"
                        + " State reaches State_StartBuilding_Failed");
    }

    @Test
    @DisplayName("retail's own computer player gives a blocked job straight back and takes its money with it")
    void aRetailComputerBuildHandsBackAndRefundsWhenItArrivesOnOccupiedGround() {
        // The patience above is the DOS order's, and it was fitted to a
        // skirmish map. Retail's computer player pays when it installs the job
        // and does not wait for the ground: XHuman 2's peon walks to its pig
        // farm at 65,57, finds another peon standing at 66,57 inside the
        // footprint, and on fixture cycle 52 player five's bank goes back up
        // from 300 gold and 300 wood to 800 and 550 while the peon reads Still.
        World world = new World(grass(20));
        world.setBuilders(java.util.Map.of("unit-farm", java.util.Set.of("unit-peasant")));
        world.player(0).set(Resource.GOLD, 5000);
        Unit worker = world.createUnit(peasant(), 0, 5, 5);
        UnitType farm = farm();
        int before = world.player(0).get(Resource.GOLD);
        assertTrue(world.orderBattleNetAiBuild(worker, farm, 8, 8),
                "the computer player's build order was refused outright");
        assertTrue(world.player(0).get(Resource.GOLD) < before,
                "a retail computer build pays when it installs the job, so the "
                        + "bank must already be down before the worker walks");
        // Somebody else is standing inside the two-by-two footprint, and stays.
        Unit squatter = world.createUnit(peasant(), 0, 9, 8);
        assertTrue(squatter != null, "the blocking worker must place");

        int stoodDown = -1;
        for (int cycle = 1; cycle <= 400 && stoodDown < 0; cycle++) {
            world.tick();
            if (worker.order() == Unit.Order.STILL
                    && world.player(0).get(Resource.GOLD) == before) {
                stoodDown = cycle;
            }
        }

        assertTrue(stoodDown > 0,
                "the worker never gave the blocked job back with the money: it "
                        + "is standing " + worker.order() + " with the bank at "
                        + world.player(0).get(Resource.GOLD) + " of " + before);
        // Giving up eventually is what the DOS order does, after nine refusals
        // ten cycles apart. Retail's computer player does it on the cycle it
        // arrives, so the money is back well inside that patience.
        assertTrue(stoodDown < PATIENCE,
                "the job came back on cycle " + stoodDown + ", which is the DOS "
                        + "order waiting the ground out rather than retail's "
                        + "computer player standing down when it gets there");
        assertTrue(world.unitAt(8, 8) == null
                        || world.unitAt(8, 8).type() == null
                        || !world.unitAt(8, 8).type().building(),
                "no foundation may go down on ground somebody is standing on");
    }

    @Test
    @DisplayName("an AI build hand-back enters the worker's native Still program")
    void anAiBuildHandBackStartsTheNativeStillProgram() throws Exception {
        World world = new World(grass(20));
        world.setBattleNetSequenceData(retailScriptBin());
        world.setBuilders(java.util.Map.of(
                "unit-farm", java.util.Set.of("unit-peasant")));
        world.player(0).set(Resource.GOLD, 5000);
        Unit worker = world.createUnit(peasant(), 0, 5, 5);
        int before = world.player(0).get(Resource.GOLD);
        assertTrue(world.orderBattleNetAiBuild(worker, farm(), 8, 8),
                "the computer player's build order was refused outright");
        assertTrue(world.createUnit(peasant(), 0, 9, 8) != null,
                "the footprint blocker must place");

        for (int cycle = 1; cycle <= 200; cycle++) {
            world.tick();
            if (worker.order() == Unit.Order.STILL
                    && world.player(0).get(Resource.GOLD) == before) {
                assertEquals(world.idle.battleNetStillSequenceStart(worker),
                        worker.battleNetSequenceOffset(),
                        "the hand-back cycle enters the peasant Still cursor");
                assertEquals(3, worker.battleNetAnimationTimer(),
                        "retail's hand-back begins its three-cycle stand-down now");
                return;
            }
        }
        assertTrue(false, "the blocked AI build never reached hand-back");
    }

    @Test
    @DisplayName("with the ground clear it builds at once, so the wait is not simply a stall")
    void anUnblockedSiteIsBuiltStraightAway() {
        World world = new World(grass(20));
        world.setBuilders(java.util.Map.of("unit-farm", java.util.Set.of("unit-peasant")));
        world.player(0).set(Resource.GOLD, 5000);
        Unit worker = world.createUnit(peasant(), 0, 5, 5);
        UnitType farm = farm();
        assertTrue(world.orderBuild(worker, farm, 8, 8), "the build order was refused");

        Unit site = null;
        int started = -1;
        for (int cycle = 1; cycle <= 400 && site == null; cycle++) {
            world.tick();
            Unit at = world.unitAt(8, 8);
            if (at != null && at.type() != null && at.type().building()) {
                site = at;
                started = cycle;
            }
        }

        assertEquals("unit-farm", site == null ? null : site.type().ident(),
                "no farm was ever founded on clear ground, so the fixture never reaches the"
                        + " code the test above is about");
        assertTrue(started < PATIENCE,
                "the farm was founded on cycle " + started + ", which is on the far side of"
                        + " the patience the other test measures -- the worker is waiting"
                        + " out refusals it should never have had");
    }
}
