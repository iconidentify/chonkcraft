package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * What the computer does with an aircraft that has finished scouting.
 *
 * <p>It sends it out again, and not once. Retail walks its aircraft on a
 * fifty-cycle beat and gives every one of them that is standing still a fresh
 * point to fly to. XOrc 8 is the compact witness: its behaviour-four draws land
 * on fixture cycles 0, 49, 99, 149 and 199, and the number spent each time is
 * exactly the number of those aircraft standing still at that moment -- six,
 * one, one, two and three.
 *
 * <p>This implementation ran that pass only at game creation. Its gryphon rider finished
 * its first leg and stood down at fixture 38 and then stood there for the rest
 * of the mission, where retail's is picked up again at 49 and patrolling at 52.
 * That one aircraft was the last thing holding the common proven frontier at
 * cycle 51.
 */
class ScoutPassTest {

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

    private static Player[] computerAndPerson() {
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

    private static UnitType scoutAircraft() {
        UnitType type = new UnitType("unit-balloon");
        type.setTileSize(1, 1);
        type.setHitPoints(150);
        type.setSpeed(17);
        type.setAirUnit(true);
        type.setSightRange(9);
        AnimationSet set = new AnimationSet("balloon");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 16", "wait 1",
                "frame 5", "move 16", "unbreakable end", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    @Test
    @DisplayName("a computer's idle scout aircraft is sent out again on the fifty-cycle beat")
    void anIdleScoutAircraftIsSentOutAgainOnTheBeat() throws Exception {
        GameMap map = new GameMap(64, 64, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map, computerAndPerson());
        world.setBattleNetSequenceData(retailScriptBin());
        world.restoreRandom(1, 0);

        Unit scout = world.createUnit(scoutAircraft(), 0, 32, 32);
        assertNotNull(scout, "the scout must place");
        scout.setOrder(Unit.Order.STILL);

        // Stand it down where it arrived, as one does at the end of a leg.
        boolean sentOut = false;
        int sentOnCycle = -1;
        for (int cycle = 1; cycle <= 60 && !sentOut; cycle++) {
            world.tick();
            if (scout.hasBattleNetPendingPatrol()
                    || scout.order() == Unit.Order.PATROL) {
                sentOut = true;
                sentOnCycle = cycle;
            }
            scout.setOrder(Unit.Order.STILL);
        }

        assertTrue(sentOut,
                "the computer left its aircraft standing for sixty cycles; "
                        + "retail picks one up on every fiftieth and gives it "
                        + "somewhere new to fly");
        assertEquals(49, sentOnCycle,
                "retail's beat lands on the forty-ninth cycle and every "
                        + "fiftieth after it, which is where XOrc 8 spends the "
                        + "draw that sends its gryphon rider out again");
    }
}
