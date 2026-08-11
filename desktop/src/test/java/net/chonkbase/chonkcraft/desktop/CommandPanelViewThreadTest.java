package net.chonkbase.chonkcraft.desktop;

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
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The command grid being drawn while the game is running.
 *
 * <p>{@code CommandPanel.selection()} was the one interface read left on
 * {@code World.units()}, the live roster, after the sweep that moved every
 * other off-thread read onto the published snapshot. It runs on the event
 * thread once a frame, and the live list's modification count moves thirty
 * times a second even on a quiet map, because {@code tick} ends with
 * {@code units.addAll(pending)} and {@code ArrayList.addAll} bumps the count
 * before it checks whether it was handed anything. Measured when the sweep
 * mapped the read surfaces: a ConcurrentModificationException within 56
 * reads, which a player sees as the window dying in the middle of a battle.
 * It was covered by focused tests rather than fixed then because another lane held
 * this file.
 *
 * <p>These drive {@link CommandPanel#draw}, the frame path, rather than
 * {@code selection()} itself, because the frame is how the event thread gets
 * there; a test that reached into the private method would keep passing if a
 * new caller of the live roster appeared on the drawing path. The control
 * runs the old read -- the live roster, walked the way {@code selection()}
 * walked it -- and must still break, because a pair of threads that never
 * actually interleave passes the first test while proving nothing.
 */
class CommandPanelViewThreadTest {

    /** How long the race runs when it is not going to fail. */
    private static final long BUDGET_MILLIS = 3_000;

    private static GameData data() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    /**
     * A field busy enough that walking the roster is not over before the
     * simulation can move it: eighty footmen, half of them selected, which is
     * a large band but a legal one.
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
        for (int i = 0; i < 40; i++) {
            Unit ours = world.createUnit(footman, 0, 2 + (i % 20) * 3, 2 + (i / 20) * 3);
            if (ours != null) {
                ours.setSelected(true);
            }
            world.createUnit(footman, 1, 2 + (i % 20) * 3, 40 + (i / 20) * 3);
        }
        return world;
    }

    private static CommandPanel panel(GameData data, World world) {
        return new CommandPanel(world, data, data.userInterface("summer"),
                data.upgrades().dependencies(), 0, "summer", "human",
                data.unitTypes().types());
    }

    /** What the view thread does; returns how many elements it walked. */
    private interface Look {
        int run(long read);
    }

    /** The outcome of one race: how far it got, and what killed it. */
    private record Race(long reads, long ticks, Throwable broken, String thread) {
    }

    /**
     * Ticks a world flat out on one thread while another looks, until
     * something throws or the budget runs out. Flat out is not harsher than
     * the game, it is the same hazard in less wall time: the window is one
     * cycle's {@code addAll} landing inside one read, offered thirty times a
     * second for as long as a mission is played.
     */
    private static Race race(World world, Look look) {
        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong reads = new AtomicLong();
        AtomicLong ticks = new AtomicLong();
        AtomicReference<Throwable> broken = new AtomicReference<>();
        AtomicReference<String> thread = new AtomicReference<>();

        Thread sim = new Thread(() -> {
            while (!stop.get()) {
                try {
                    world.tick();
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
                    look.run(read);
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
        return new Race(reads.get(), ticks.get(), broken.get(), thread.get());
    }

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
    @DisplayName("the command grid can be drawn while the game is running")
    void theGridCanBeDrawnWhileTheGameRuns() {
        GameData data = data();
        World world = field(data);
        CommandPanel panel = panel(data, world);
        Unit selected = null;
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.selected()) {
                selected = unit;
                break;
            }
        }
        assertNotNull(selected, "the fixture selected nobody, so the panel draws "
                + "nothing and this race reads nothing");
        java.awt.image.BufferedImage frame = new java.awt.image.BufferedImage(
                640, 480, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var g2 = frame.createGraphics();
        Unit chosen = selected;
        Race race = race(world, read -> {
            panel.draw(g2, chosen);
            return 1;
        });
        g2.dispose();
        assertNull(race.broken(), report("CommandPanel.draw", race));
        assertTrue(race.reads() > 100,
                "the panel never got to draw: " + race.reads() + " frames");
    }

    /**
     * The read {@code selection()} used to make, and it must still break.
     *
     * <p>This is the old behaviour run against the same harness, and it is
     * what proves the test above measures anything: if this survives its
     * budget the two threads never interleaved, and a green
     * {@link #theGridCanBeDrawnWhileTheGameRuns} says nothing at all.
     */
    @Test
    @DisplayName("the roster the simulation owns still refuses to be read from the panel's thread")
    void theOldReadStillBreaks() {
        GameData data = data();
        World world = field(data);
        Race race = race(world, read -> {
            int seen = 0;
            for (Unit unit : world.units()) {
                if (unit.selected() && unit.isAlive() && unit.player() == 0) {
                    seen++;
                }
            }
            return seen;
        });
        assertNotNull(race.broken(),
                "walking the live roster from the panel's thread survived "
                + race.reads() + " reads and " + race.ticks()
                + " cycles, so this harness is not racing anything and the "
                + "test above proves nothing");
        assertInstanceOf(ConcurrentModificationException.class, race.broken(),
                "the live roster failed with something other than the "
                + "fail-fast exception: " + race.broken());
    }
}
