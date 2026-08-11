package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ConcurrentModificationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Moving the mouse while the game is running.
 *
 * <p>A player moved the pointer across the battlefield and the game died:
 *
 * <pre>
 * Exception in thread "AWT-EventQueue-0" java.util.ConcurrentModificationException
 *     at net.chonkbase.chonkcraft.engine.World.unitAtPixel(World.java:1614)
 *     at net.chonkbase.chonkcraft.desktop.GameScreen.unitUnder(GameScreen.java:2192)
 *     at net.chonkbase.chonkcraft.desktop.GameScreen.kindAt(GameScreen.java:172)
 *     at net.chonkbase.chonkcraft.desktop.GameScreen.updateCursor(GameScreen.java:131)
 * </pre>
 *
 * <p>The simulation runs on its own thread at thirty cycles a second and the
 * interface runs on the event thread. {@code World} publishes an immutable
 * snapshot of the unit list once a tick precisely so the two do not meet, and
 * most of the interface reads it -- but a handful of paths reached past it into
 * the live collections, and every one of those is a crash the player can cause
 * by moving the mouse, opening a menu or watching a battle.
 *
 * <p>The window is not as narrow as the birth and death rate suggests. On
 * {@code campaigns/human/level13h} with two hundred and ten units, units are
 * born 0.08 times a second and die 0.07 times a second -- but the list's
 * modification count changes thirty times a second, because {@code tick} ends
 * with {@code units.addAll(pending)} and {@code ArrayList.addAll} increments
 * that count before it checks whether the collection it was handed is empty.
 * So the iterator the event thread is holding is invalidated once per cycle on
 * a map where nothing whatever is happening. Against a real mission this took
 * four reads to reproduce.
 *
 * <p>Each test below hammers one read surface, because a failure naming
 * {@code pings()} is a great deal easier to act on than one naming "the view".
 * Every one of them counts what it walked, so a run that found nothing to read
 * cannot pass by finding nothing, and
 * {@link #theLiveRosterIsStillFailFast} is the control that proves the harness
 * races at all -- a pair of threads that never meet passes every one of these.
 *
 * <p>What the unfixed code did, on this fixture, before any of it was changed:
 *
 * <ul>
 * <li>{@code unitAtPixel} -- ConcurrentModificationException after 8 reads
 * <li>{@code units()} -- ConcurrentModificationException after 56 reads
 * <li>{@code pings()} -- ConcurrentModificationException out of
 * {@code ArrayList.removeIf} after 14,508 reads
 * <li>{@code seenBuildings().forPlayer()} -- a list with a {@code null} in it,
 * after 12,080 reads
 * <li>{@code markOrder()} -- ArrayIndexOutOfBoundsException <em>on the
 * simulation thread</em> after 379,283 clicks, which the player sees as the
 * game freezing
 * <li>{@code unitAt()} -- survived, here and against a real mission for 3.5
 * billion reads. It is on the same footing as the rest and is left alone
 * deliberately; see the focused tests.
 * </ul>
 */
class ViewThreadReadsTest {

    /** How long each race runs when it is not going to fail. */
    private static final long BUDGET_MILLIS = 3_000;

    private static GameData load() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No Warcraft II assets configured. Set CHONKCRAFT_ASSET_PACK or"
                        + " -Dwc2.install.dir=/path/to/game.");
        return new GameData(assets);
    }

    /**
     * A field with two sides on it, a building to be remembered, and enough
     * units that a sweep of the roster is not over before it starts.
     */
    private static World field(GameData data) {
        int size = 64;
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i,
                    i < 2 ? PudMap.PlayerType.PERSON : PudMap.PlayerType.NOBODY,
                    PudMap.Race.HUMAN);
        }
        World world = new World(map, players);
        world.setUnitTypes(data.unitTypes().types());
        world.setMissileTypes(data.missiles().types());

        UnitType footman = data.unitTypes().types().get("unit-footman");
        UnitType farm = data.unitTypes().types().get("unit-farm");
        for (int i = 0; i < 40; i++) {
            world.createUnit(footman, 0, 2 + (i % 20) * 3, 2 + (i / 20) * 3);
            world.createUnit(footman, 1, 2 + (i % 20) * 3, 40 + (i / 20) * 3);
        }
        // Something for player 0 to have scouted and to be remembering.
        world.createUnit(farm, 1, 30, 30);
        return world;
    }

    /** What the sim thread does while the view reads. */
    private interface Drive {
        void run(World world, long tick);
    }

    /** What the view thread does; returns how many elements it walked. */
    private interface Look {
        int run(World world, long read);
    }

    /** The outcome of one race: what it walked, and what killed it. */
    private record Race(long reads, long walked, long ticks, Throwable broken,
            String thread) {
    }

    /**
     * Ticks a world on one thread while another reads it, until something
     * throws or the budget runs out.
     *
     * <p>The simulation runs flat out rather than at thirty hertz. That is not
     * a harsher test than the game, it is the same test in less wall time: the
     * hazard is one cycle's structural change landing inside one read, and the
     * game presents that window thirty times a second for however many hours it
     * is played.
     */
    private static Race race(World world, Drive drive, Look look) {
        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong reads = new AtomicLong();
        AtomicLong walked = new AtomicLong();
        AtomicLong ticks = new AtomicLong();
        AtomicReference<Throwable> broken = new AtomicReference<>();
        AtomicReference<String> thread = new AtomicReference<>();

        Thread sim = new Thread(() -> {
            while (!stop.get()) {
                long tick = ticks.get();
                try {
                    world.tick();
                    drive.run(world, tick);
                } catch (Throwable failed) {
                    if (broken.compareAndSet(null, failed)) {
                        thread.set("simulation");
                        stop.set(true);
                    }
                    return;
                }
                ticks.incrementAndGet();
            }
        }, "race-simulation");

        Thread view = new Thread(() -> {
            while (!stop.get()) {
                long read = reads.incrementAndGet();
                try {
                    walked.addAndGet(look.run(world, read));
                } catch (Throwable failed) {
                    if (broken.compareAndSet(null, failed)) {
                        thread.set("view");
                        stop.set(true);
                    }
                    return;
                }
            }
        }, "race-view");

        sim.start();
        view.start();
        long deadline = System.currentTimeMillis() + BUDGET_MILLIS;
        while (System.currentTimeMillis() < deadline && broken.get() == null) {
            try {
                Thread.sleep(2);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        stop.set(true);
        try {
            sim.join(5_000);
            view.join(5_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return new Race(reads.get(), walked.get(), ticks.get(), broken.get(),
                thread.get());
    }

    /** The message a failure carries, with the numbers that make it actionable. */
    private static String report(String what, Race race) {
        if (race.broken() == null) {
            return "";
        }
        StackTraceElement top = race.broken().getStackTrace().length == 0
                ? null : race.broken().getStackTrace()[0];
        return what + " threw on the " + race.thread() + " thread after "
                + race.reads() + " reads and " + race.ticks() + " cycles: "
                + race.broken() + (top == null ? "" : " at " + top);
    }

    @Test
    @DisplayName("the pointer can cross a unit while the game is running")
    void unitAtPixelSurvivesTheGameRunning() {
        World world = field(load());
        Race race = race(world, (w, tick) -> { }, (w, read) -> {
            // Every read sweeps the whole roster, so the read count is the
            // measure here rather than what it happened to find under the
            // pointer.
            w.unitAtPixel((int) (read * 37 % 2048), (int) (read * 61 % 2048), null);
            return 1;
        });
        assertNull(race.broken(), report("unitAtPixel", race));
        assertTrue(race.reads() > 100,
                "the view never got to read: " + race.reads() + " reads");
    }

    /**
     * The same harness against the roster the simulation owns, which must
     * still fail.
     *
     * <p>This is the control, and without it every other test in this class
     * proves nothing: a race that never actually interleaves passes perfectly.
     * It also states the rule the rest follow from. {@link World#units()}
     * is the live list and is deliberately fail-fast -- it is what
     * {@code TriggerSystem}, {@code SyncHash}, {@code CommandApplier},
     * {@code AiPlayer} and {@code SaveGame} read, all of them on the
     * simulation's own thread, and the day some new path adds a unit in the
     * middle of the tick's own loop the exception it throws is the alarm that
     * says so. Handing the interface a list that tolerated that would silence
     * it.
     *
     * <p>So the interface reads {@link World#unitsSnapshot()} instead. The
     * last caller that did not, {@code CommandPanel.selection()}, is on the
     * snapshot now, and {@code CommandPanelViewThreadTest} in {@code desktop}
     * races the panel's own draw to hold it there.
     */
    @Test
    @DisplayName("the roster the simulation owns still refuses to be read from outside")
    void theLiveRosterIsStillFailFast() {
        World world = field(load());
        Race race = race(world, (w, tick) -> { }, (w, read) -> {
            int seen = 0;
            for (Unit unit : w.units()) {
                if (unit.isAlive()) {
                    seen++;
                }
            }
            return seen;
        });
        assertNotNull(race.broken(),
                "reading the live roster from another thread survived " + race.reads()
                + " reads and " + race.ticks() + " cycles, so this harness is not "
                + "racing anything and the tests above prove nothing");
        assertInstanceOf(ConcurrentModificationException.class, race.broken(),
                "the live roster failed with something other than the fail-fast "
                + "exception: " + race.broken());
    }

    @Test
    @DisplayName("a ping can be drawn while another player is pinging")
    void pingsSurviveBeingDrawn() {
        World world = field(load());
        Race race = race(world,
                (w, tick) -> w.addPing((int) (tick % 2), 10, 10),
                (w, read) -> {
                    int seen = 0;
                    for (var ping : w.pings()) {
                        if (ping.tileX() >= 0) {
                            seen++;
                        }
                    }
                    return seen;
                });
        assertNull(race.broken(), report("pings()", race));
        assertTrue(race.walked() > 1000,
                "the sweep walked " + race.walked() + " pings, which proves nothing");
    }

    /**
     * The ping still fades when it used to.
     *
     * <p>Expiring a ping was the renderer's job and is now the simulation's,
     * which is a change to when the arithmetic runs and must not be a change to
     * what the player sees. A ping that vanished a frame early would be nobody's
     * bug report and everybody's annoyance, and there was no test on this at
     * all: the whole of the ping's visible life was pinned by nothing.
     */
    @Test
    @DisplayName("a ping stays on screen for its two seconds and then goes")
    void aPingLastsAsLongAsItUsedTo() {
        World world = field(load());
        world.addPing(0, 10, 10);
        assertTrue(world.pings().size() == 1,
                "the ping was not there the moment it arrived");
        for (int cycle = 0; cycle < World.PING_CYCLES; cycle++) {
            world.tick();
            assertTrue(world.pings().size() == 1,
                    "the ping went out after " + cycle + " cycles, and it is meant to "
                    + "last " + World.PING_CYCLES);
        }
        world.tick();
        assertTrue(world.pings().isEmpty(),
                "the ping was still on screen " + (World.PING_CYCLES + 1)
                + " cycles after it arrived");
    }

    @Test
    @DisplayName("a remembered building can be drawn while the scout moves")
    void seenBuildingsSurviveBeingDrawn() {
        GameData data = load();
        World world = field(data);
        UnitType peasant = data.unitTypes().types().get("unit-peasant");
        // A revealer that comes and goes, which is what a death vision is: it
        // gives player 0 sight of the farm and takes it away again, so the
        // memory of the farm is forgotten and remade over and over while the
        // interface is copying the table.
        AtomicReference<Unit> revealer = new AtomicReference<>();
        Race race = race(world, (w, tick) -> {
            Unit current = revealer.getAndSet(null);
            if (current != null) {
                w.remove(current);
            } else {
                revealer.set(w.createUnit(peasant, 0, 30, 33));
            }
        }, (w, read) -> {
            int seen = 0;
            for (var memory : w.seenBuildings().forPlayer(0)) {
                if (memory.tileX() >= 0) {
                    seen++;
                }
            }
            return seen;
        });
        assertNull(race.broken(), report("seenBuildings().forPlayer()", race));
        assertTrue(race.walked() > 1000,
                "the sweep walked " + race.walked() + " memories, which proves nothing");
    }

    @Test
    @DisplayName("clicking an order down does not break the simulation")
    void theClickMarkerDoesNotBreakTheSimulation() {
        World world = field(load());
        Race race = race(world, (w, tick) -> { }, (w, read) -> {
            w.markOrder((int) (read % 60) + 1, (int) (read * 7 % 60) + 1);
            return 1;
        });
        assertNull(race.broken(), report("markOrder()", race));
        assertTrue(race.reads() > 1000,
                "the view never got to click: " + race.reads() + " clicks");
    }

    @Test
    @DisplayName("the tile under the pointer can be asked for while units walk")
    void unitAtSurvivesTheGameRunning() {
        World world = field(load());
        Race race = race(world, (w, tick) -> { }, (w, read) -> {
            var snapshot = w.unitsSnapshot();
            if (snapshot.isEmpty()) {
                return 0;
            }
            Unit target = snapshot.get((int) (read % snapshot.size()));
            return w.unitAt(target.tileX(), target.tileY()) == null ? 0 : 1;
        });
        assertNull(race.broken(), report("unitAt()", race));
        assertTrue(race.walked() > 1000,
                "the sweep found " + race.walked() + " occupied tiles, which proves nothing");
    }
}
