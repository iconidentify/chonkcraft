package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import net.chonkbase.chonkcraft.data.map.PudMap;
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
 * Whether an animal keeps breathing while it is waiting.
 *
 * <p>It does. A unit whose wait is counting down is skipped by the order loop,
 * but retail's animation program is not the order loop: {@code IsWaiting} plays
 * the Still loop over the wait, and that loop keeps reaching its action markers.
 * The critter's Still program is {@code 4718 frame 0}, {@code 4720 marker},
 * {@code 4721 jump 4982}, {@code 4982 marker}, {@code 4983 wait 4},
 * {@code 4985 jump 4982}, so an animal parked on it with three left on the
 * clock reaches its next marker three cycles later.
 *
 * <p>This implementation skipped the unit outright, so the ten-cycle pause a critter is
 * left on when its wander ends froze the program with it. Orc 4's animal comes
 * to rest on cycle 50 and retail dispatches it again at 52, 55, 56, 57 and 62,
 * where this implementation reached no marker until 62. In Human 4 the consequence was
 * worse than lateness: the animal that was owed the draw did not take it, so
 * its neighbour spent it instead -- the same seed in both engines, handed to a
 * different animal -- and that mission reported one critter standing where
 * retail had it moving and another moving where retail had it standing.
 */
class CritterWaitBreathTest {

    private static byte[] retailScriptBin() throws IOException {
        String packProp = System.getProperty("chonkcraft.pack");
        Path pack = packProp != null && !packProp.isBlank()
                ? Path.of(packProp)
                : Path.of(System.getProperty("user.home"),
                        ".chonkcraft/work",
                        "warcraft-ii-battle-net-edition-usa.pre-full-media-2026-07-30.chonkpack");
        assumeTrue(Files.isRegularFile(pack),
                "BNE asset pack required for the retail Still sequence");
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            var entry = zip.getEntry("assets/archives/maindat/0278.bin");
            assumeTrue(entry != null, "pack must contain maindat entry 278");
            try (var in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    private static Player[] neutralOwner() {
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i,
                    i == 0 ? PudMap.PlayerType.COMPUTER
                            : i == 1 ? PudMap.PlayerType.PERSON
                                    : PudMap.PlayerType.NOBODY,
                    PudMap.Race.HUMAN);
        }
        return players;
    }

    private static UnitType critter() {
        UnitType type = new UnitType("unit-critter");
        type.setTileSize(1, 1);
        type.setHitPoints(5);
        type.setSpeed(10);
        type.setLandUnit(true);
        AnimationSet set = new AnimationSet("critter");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 16", "wait 1",
                "frame 5", "move 16", "unbreakable end", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    @Test
    @DisplayName("a critter parked on its route-end pause still reaches its next Still marker")
    void aWaitingCritterKeepsWalkingItsStillProgram() throws Exception {
        GameMap map = new GameMap(24, 24, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map, neutralOwner());
        world.setBattleNetSequenceData(retailScriptBin());
        world.restoreRandom(1, 0);

        Unit animal = world.createUnit(critter(), 15, 12, 12);
        assertNotNull(animal, "the critter must place");

        // The state a critter is left in the cycle its wander ends: standing,
        // its Still program re-entered at the top with three on the clock, and
        // parked on the ten-cycle empty-route pause.
        animal.setOrder(Unit.Order.STILL);
        animal.clearPath();
        animal.setOffset(0, 0);
        animal.setBattleNetSequenceOffset(
                world.idle.battleNetStillSequenceStart(animal));
        animal.setBattleNetAnimationTimer(3);
        animal.setBattleNetIdlePhase(2);
        animal.setWaitCycles(10);

        int seed = world.battleNetRandomSeed();
        int drewOn = -1;
        for (int cycle = 1; cycle <= 14 && drewOn < 0; cycle++) {
            world.tick();
            if (world.battleNetRandomSeed() != seed) {
                drewOn = cycle;
            }
        }

        assertEquals(3, drewOn,
                "the animal is three cycles from the marker at 4720 when its "
                        + "wander ends, and the wait it is parked on does not "
                        + "stop it getting there; standing frozen for the whole "
                        + "pause is what let the next animal in the shared "
                        + "stream spend the number this one was owed");
    }
}
