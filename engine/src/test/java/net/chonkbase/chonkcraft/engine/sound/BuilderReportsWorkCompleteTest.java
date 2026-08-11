package net.chonkbase.chonkcraft.engine.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.nio.file.Paths;
import net.chonkbase.runtime.audio.AudioMixer;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.unit.UnitType.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The voice that reports a finished building is the builder's, not the
 * building's.
 *
 * <p>Upstream is explicit about it twice. {@code src/include/sound.h:80}
 * annotates the event itself -- {@code WorkCompleted, /// only worker, work
 * completed} -- and plays it on the
 * worker: {@code PlayUnitSound(*worker, EUnitVoice::WorkCompleted)}. This implementation
 * announced it on the finished building instead.
 *
 * <p>What a player loses by that is one line in the whole game, and it is a
 * line ChonkCraft wrote a comment to protect. Of all 143 unit types, exactly one
 * declares a {@code work-complete} of its own: the human oil tanker, which
 * says {@code basic human voices research complete} because, in
 * {@code scripts/human/units.legacy-declaration:549}'s words, "the oil tankers do not use the
 * nasal 'work's done' peasant sound for completing buildings". A tanker builds
 * oil platforms, so the exception is reachable in ordinary play -- and by
 * asking the platform rather than the tanker, this implementation always got the
 * peasant's line.
 *
 * <p>Everything else is unaffected, because no other unit declares one and
 * both readings fall back to the race's game sound. That is also why it
 * survived: 398 sound bindings were transcribed off the scripts, 397 of them
 * were reachable, and the exception is the one that was not.
 *
 * <p>The measurement starts from a worker putting up a building and looks at
 * what came out of the sound queue, not at {@code chosenPath}.
 * {@code WorkCompleteSoundTest.anOwnSoundWins} already asks {@code chosenPath}
 * for the tanker's sound directly and passed throughout -- the tanker's own
 * line always won, once something thought to ask the tanker.
 */
class BuilderReportsWorkCompleteTest {

    /** What the human oil tanker says, from {@code unit-sounds.tsv}. */
    private static final String TANKER_VOICE = "basic human voices research complete";

    @Test
    @DisplayName("an oil tanker finishing a platform reports it in its own voice")
    void theBuilderIsWhatSpeaks() {
        World world = richWorld();
        Unit tanker = world.createUnit(tanker(), 0, 3, 3);
        UnitType platform = platform();

        assertTrue(world.orderBuild(tanker, platform, 10, 10),
                "the fixture must be able to start the platform or it proves nothing");

        List<World.SoundEvent> reports = runUntilBuilt(world, tanker);
        assertEquals(1, reports.size(),
                "a finished building should report exactly once, and reported "
                        + reports.size() + " times");

        World.SoundEvent report = reports.get(0);
        assertEquals(tanker, report.unit(),
                "the platform announced its own completion; upstream plays"
                        + " WorkCompleted on the worker (action_built.cpp:196),"
                        + " which is the only unit that has a voice for it");
        assertEquals(TANKER_VOICE, report.unit().type().sounds().get("work-complete"),
                "the unit that spoke has no work-complete of its own, so the"
                        + " tanker's exception is still being flattened to the"
                        + " race's peasant line");

        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set -Dchonkcraft.pack=... or wc2.install.dir");
        SoundBank bank = new GameData(assets).sounds();
        Assumptions.assumeTrue(bank.isAvailable(), "no sound archives in this release");
        GameAudio audio = new GameAudio(bank);
        audio.startWithoutDevice();
        audio.playUnit(report.unit(), report.event(), 0);
        float[] samples = new float[4096 * AudioMixer.OUTPUT_CHANNELS];
        audio.mixer().render(samples, 4096);
        float peak = 0f;
        for (float sample : samples) {
            peak = Math.max(peak, Math.abs(sample));
        }
        assertTrue(peak > 0.001f,
                "the completed platform reached the mixer but rendered as silence");
    }

    @Test
    @DisplayName("a peasant finishing a farm still reports, and has nothing of its own to say")
    void aBuilderWithNoVoiceOfItsOwnStillReports() {
        World world = richWorld();
        Unit peasant = world.createUnit(peasant(), 0, 3, 3);

        assertTrue(world.orderBuild(peasant, farm(), 10, 10),
                "the fixture must be able to start the farm or it proves nothing");

        List<World.SoundEvent> reports = runUntilBuilt(world, peasant);
        assertEquals(1, reports.size(),
                "a finished farm should report exactly once, and reported "
                        + reports.size() + " times");
        assertEquals(peasant, reports.get(0).unit(),
                "the farm announced its own completion rather than the peasant");

        // The other 142 types say nothing of their own here, which is what
        // sends them to the race's game sound in GameAudio. Both readings agree
        // for them, and that is precisely why only the tanker could show this.
        assertNotNull(reports.get(0).unit().type(), "the report named no type at all");
        assertEquals(null, peasant.type().sounds().get("work-complete"),
                "the peasant is expected to have no work-complete of its own; if"
                        + " that changed, this fixture no longer tells the two"
                        + " readings apart");
    }

    /** Ticks until the site is finished, keeping every work-complete report. */
    private static List<World.SoundEvent> runUntilBuilt(World world, Unit worker) {
        List<World.SoundEvent> reports = new java.util.ArrayList<>();
        for (int cycle = 0; cycle < 3000 && worker.order() == Unit.Order.BUILD; cycle++) {
            world.tick();
            for (World.SoundEvent event : world.drainSoundEvents()) {
                if (!event.named() && "work-complete".equals(event.event())) {
                    reports.add(event);
                }
            }
        }
        assertTrue(worker.order() != Unit.Order.BUILD,
                "the building never finished, so nothing was measured");
        return reports;
    }

    private static World richWorld() {
        World world = new World(grass(30));
        world.player(0).set(Resource.GOLD, 5000);
        world.player(0).set(Resource.WOOD, 5000);
        world.player(0).set(Resource.OIL, 5000);
        return world;
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
        set.put(AnimationSet.State.MOVE, Animation.parse("move",
                List.of("frame 0", "move 16", "wait 1", "frame 5", "move 16", "wait 1")));
        return set;
    }

    private static UnitType worker(String ident) {
        UnitType type = new UnitType(ident);
        type.setTileSize(1, 1);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setAnimationSet(walker());
        type.costs().put(Resource.TIME, 1);
        type.costs().put(Resource.GOLD, 400);
        return type;
    }

    private static UnitType peasant() {
        return worker("unit-peasant");
    }

    /** The one unit in the game with a work-complete of its own. */
    private static UnitType tanker() {
        UnitType type = worker("unit-human-oil-tanker");
        type.sounds().put("work-complete", TANKER_VOICE);
        return type;
    }

    private static UnitType farm() {
        UnitType type = new UnitType("unit-farm");
        type.setTileSize(2, 2);
        type.setHitPoints(400);
        type.setBuilding(true);
        type.costs().put(Resource.TIME, 1);
        type.costs().put(Resource.GOLD, 500);
        type.costs().put(Resource.WOOD, 250);
        return type;
    }

    /** What a tanker builds, and it says nothing of its own. */
    private static UnitType platform() {
        UnitType type = new UnitType("unit-human-oil-platform");
        type.setTileSize(3, 3);
        type.setHitPoints(650);
        type.setBuilding(true);
        type.costs().put(Resource.TIME, 1);
        type.costs().put(Resource.GOLD, 700);
        type.costs().put(Resource.WOOD, 450);
        return type;
    }
}
