package net.chonkbase.chonkcraft.engine.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.data.map.PudMap;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which clip of a group actually gets played.
 *
 * <p>Every place that played a sound used to hand in a bound it had written
 * out by hand: {@code syncRand(2)} for a death, {@code syncRand(3)} for an
 * animation sound, {@code syncRand(4)} for a voice. None of those numbers came
 * from the data. {@code building destroyed} has three clips and was drawn from
 * two, so a third of the sound of a keep coming down did not exist as far as the
 * game was concerned; {@code tree-chopping} has four and was drawn from three.
 *
 * <p>What is checked here is reachability -- every clip the script names can be
 * played -- and the draw discipline that makes it safe to ask the simulation's
 * own generator for the number.
 */
class SoundChoiceTest {

    private static GameData gameData() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set -Dchonkcraft.pack=... or wc2.install.dir");
        return new GameData(assets);
    }

    /** A world with one player, enough to own a unit that can make a noise. */
    private static World world() {
        GameMap map = new GameMap(16, 16, new Tileset());
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < Player.MAX; i++) {
            players[i] = new Player(i, i < 2 ? PudMap.PlayerType.PERSON
                    : PudMap.PlayerType.NOBODY, PudMap.Race.HUMAN);
        }
        return new World(map, players);
    }

    private static Unit unitWith(World world, int atX, String event, String sound) {
        UnitType type = new UnitType("unit-test");
        type.setName("test");
        type.setHitPoints(60);
        type.setLandUnit(true);
        type.setTileSize(1, 1);
        if (sound != null) {
            type.sounds().put(event, sound);
        }
        Unit unit = world.createUnit(type, 0, atX, 4);
        assertNotNull(unit);
        return unit;
    }

    /**
     * The defect itself, on the real data: the building death sound has three
     * clips, and a caller drawing from two can never reach the third.
     */
    @Test
    @DisplayName("every clip of the building death sound is reachable")
    void allThreeBuildingDeathClipsAreReachable() {
        SoundBank bank = gameData().sounds();
        assertEquals(3, bank.pathsFor("building destroyed").size(),
                "sound.legacy-declaration names three files for a building coming down");
        assertEquals(3, bank.groupSize("building destroyed"),
                "the group's own size is what a caller has to draw against");

        Set<String> reachedWithTheOldBound = new LinkedHashSet<>();
        for (int pick = 0; pick < 64; pick++) {
            reachedWithTheOldBound.add(bank.pathForName("building destroyed", pick % 2));
        }
        assertEquals(2, reachedWithTheOldBound.size(),
                "the hardcoded bound of two should reach two clips, and this test is"
                        + " measuring the wrong thing if it does not");

        Set<String> reached = new LinkedHashSet<>();
        for (int pick = 0; pick < 64; pick++) {
            reached.add(bank.pathForName("building destroyed",
                    pick % bank.groupSize("building destroyed")));
        }
        assertEquals(3, reached.size(),
                "only " + reached + " can ever be played, so one of the three explosions"
                        + " the game ships is dead data");
    }

    /**
     * The same property stated over the whole sound script rather than one
     * name, because the bound was invented in several places and the data
     * disagrees with it in several more.
     */
    @Test
    @DisplayName("no group has a clip that cannot be reached")
    void everyGroupIsFullyReachable() {
        SoundBank bank = gameData().sounds();
        List<String> multiClip = new ArrayList<>();
        List<String> unreachable = new ArrayList<>();
        for (String name : List.of("building destroyed", "sword attack", "tree-chopping",
                "basic human voices acknowledge", "basic orc voices acknowledge",
                "basic human voices dead", "explosion")) {
            int size = bank.pathsFor(name).size();
            if (size > 1) {
                multiClip.add(name);
            }
            Set<String> reached = new LinkedHashSet<>();
            for (int pick = 0; pick < 4 * Math.max(1, size); pick++) {
                reached.add(bank.pathForName(name, pick % bank.groupSize(name)));
            }
            if (size > 1 && reached.size() != size) {
                unreachable.add(name + " reaches " + reached.size() + " of " + size);
            }
        }
        assertTrue(multiClip.size() >= 4, "expected several multi-clip groups, found "
                + multiClip);
        assertTrue(unreachable.isEmpty(), "clips that can never play: " + unreachable);
    }

    /**
     * The draw discipline. The callers ask the simulation's own synchronised
     * generator for the number, so a call that draws on one machine and not on
     * another leaves the two games on different numbers from then on -- a
     * desync caused by a sound effect. Every path through a play call must
     * therefore ask exactly once: a sound the unit has not got, a name the bank
     * has never heard of, and a machine with no audio device included.
     */
    @Test
    @DisplayName("a sound draws exactly one number whatever comes of it")
    void everyPlayDrawsExactlyOnce() {
        SoundBank bank = new SoundBank(java.util.Map.of(), null);
        bank.define("building destroyed", List.of("a.wav", "b.wav", "c.wav"));
        GameAudio audio = new GameAudio(bank);
        // Deliberately not started: this is the machine with no sound device,
        // and it has to stay in step with one that has.
        assertTrue(!audio.isAvailable());

        World world = world();
        Unit noisy = unitWith(world, 2, "dead", "building destroyed");
        Unit silent = unitWith(world, 4, "dead", null);

        AtomicInteger draws = new AtomicInteger();
        audio.playUnit(noisy, "dead", size -> {
            draws.incrementAndGet();
            return 0;
        });
        assertEquals(1, draws.get(), "a sound that exists draws once");

        audio.playUnit(silent, "attack", size -> {
            draws.incrementAndGet();
            return 0;
        });
        assertEquals(2, draws.get(), "a unit with no such sound still has to draw");

        audio.playNamedAt("no such sound at all", size -> {
            draws.incrementAndGet();
            return 0;
        }, 0f);
        assertEquals(3, draws.get(), "an unknown name still has to draw");
    }

    /** The bound handed to the caller is the group's, not a number from the air. */
    @Test
    @DisplayName("the pick is offered the whole group")
    void thePickIsOfferedTheWholeGroup() {
        SoundBank bank = new SoundBank(java.util.Map.of(), null);
        bank.define("building destroyed", List.of("a.wav", "b.wav", "c.wav"));
        bank.define("tree-chopping", List.of("1.wav", "2.wav", "3.wav", "4.wav"));
        GameAudio audio = new GameAudio(bank);
        World world = world();
        Unit unit = unitWith(world, 2, "dead", "building destroyed");

        AtomicInteger offered = new AtomicInteger();
        audio.playUnit(unit, "dead", size -> {
            offered.set(size);
            return 0;
        });
        assertEquals(3, offered.get(),
                "the caller was told to pick from " + offered.get() + " when the data says 3");

        audio.playNamedAt("tree-chopping", size -> {
            offered.set(size);
            return 0;
        }, 0f);
        assertEquals(4, offered.get(),
                "tree-chopping has four clips and was drawn from " + offered.get());
    }

    /**
     * A selection sound is a pair, not one long list.
     *
     * <p>{@code MakeSoundGroup("footman-selected", "basic human voices
     * selected", "basic human voices annoyed")} names thirteen files between
     * them, and running them together would have a footman grumble at you seven
     * times in thirteen the first time you clicked him. Upstream keeps the two
     * halves apart: the acknowledgement is picked at random, the annoyed lines
     * are only reached by pestering the same unit and are then walked in order.
     */
    @Test
    @DisplayName("a unit answers before it complains, and complains if pestered")
    void selectionUsesBothHalvesOfThePairInOrder() {
        SoundBank bank = new SoundBank(java.util.Map.of(), null);
        bank.define("voices selected", List.of("s1.wav", "s2.wav"));
        bank.define("voices annoyed", List.of("a1.wav", "a2.wav"));
        bank.defineSelection("footman-selected", "voices selected", "voices annoyed");

        assertEquals(2, bank.groupSize("footman-selected"),
                "a single pick chooses among the acknowledgements only");
        assertEquals(4, bank.pathsFor("footman-selected").size(),
                "the flattened list is still there for anything that wants every file");

        GameAudio audio = new GameAudio(bank);
        World world = world();
        Unit unit = unitWith(world, 2, "selected", "footman-selected");

        List<String> played = new ArrayList<>();
        for (int click = 0; click < 6; click++) {
            played.add(audio.chosenPath(unit, "selected", size -> 0));
        }
        assertEquals(List.of("s1.wav", "s1.wav", "s1.wav", "a1.wav", "a2.wav", "s1.wav"),
                played,
                "three acknowledgements, then the annoyed lines in order, then back to"
                        + " the start -- and instead: " + played);

        // Clicking someone else starts the count again, so a unit is not born
        // annoyed because of what the last one heard.
        Unit other = unitWith(world, 6, "selected", "footman-selected");
        assertEquals("s1.wav", audio.chosenPath(other, "selected", size -> 0));
    }
}
