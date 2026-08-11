package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * What the computer does with a warship that has stopped.
 *
 * <p>It sends it out again on the same fifty-cycle beat it walks its aircraft
 * on, and it does not do the same for a transport. XOrc 11 is the witness: five
 * of that mission's ships carry the AI behaviour whose routine queues a patrol,
 * and they are all destroyers and battleships. The one destroyer of that player
 * which is not given it is the one carrying the marker this implementation reads as a
 * suppressed ready. The queue is a next order, promoted at the ship's following
 * action marker rather than at once, and it draws nothing -- destroyer 1519
 * keeps the point it already had, 22,36, from fixture 44 through 60 and across
 * the patrol it is put on at 53.
 *
 * <p>This implementation ran the pass over aircraft only, so 1519 stood still at 53 where
 * retail has it patrolling. Running it over every idle ship was worse than
 * running it over none: Orc 14's two transports went out on fixture 49 where
 * the oracle leaves them standing, and that mission lost seven proven cycles.
 */
class NavalPatrolPassTest {

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

    private static UnitType ship(String ident, boolean armed) {
        UnitType type = new UnitType(ident);
        type.setTileSize(1, 1);
        type.setHitPoints(100);
        type.setSpeed(10);
        type.setSeaUnit(true);
        type.setSightRange(8);
        if (armed) {
            type.setCanAttack(true);
            type.setCanTargetLand(true);
            type.setCanTargetSea(true);
            type.setMaxAttackRange(4);
        } else {
            type.setMaxOnBoard(6);
        }
        AnimationSet set = new AnimationSet(ident);
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 16", "wait 1",
                "frame 5", "move 16", "unbreakable end", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    @Test
    @DisplayName("a computer's idle warship is sent back out on the beat and its transport is not")
    void anIdleWarshipIsSentOutAgainAndATransportIsLeftAlone() throws Exception {
        GameMap map = new GameMap(64, 64, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.WATER_ALLOWED);
            }
        }
        World world = new World(map, computerAndPerson());
        world.setBattleNetSequenceData(retailScriptBin());
        world.restoreRandom(1, 0);

        Unit warship = world.createUnit(ship("unit-human-destroyer", true), 0, 32, 32);
        Unit hull = world.createUnit(ship("unit-human-transport", false), 0, 36, 32);
        assertNotNull(warship, "the warship must place on open water");
        assertNotNull(hull, "the transport must place on open water");
        warship.setOrder(Unit.Order.STILL);
        hull.setOrder(Unit.Order.STILL);
        warship.setOrderTarget(32, 28);
        hull.setOrderTarget(36, 28);

        boolean sentOut = false;
        int sentOnCycle = -1;
        boolean hullSentOut = false;
        for (int cycle = 1; cycle <= 60 && !sentOut; cycle++) {
            world.tick();
            if (warship.hasBattleNetPendingPatrol()
                    || warship.order() == Unit.Order.PATROL) {
                sentOut = true;
                sentOnCycle = cycle;
            }
            if (hull.hasBattleNetPendingPatrol()
                    || hull.order() == Unit.Order.PATROL) {
                hullSentOut = true;
            }
            warship.setOrder(Unit.Order.STILL);
            hull.setOrder(Unit.Order.STILL);
        }

        assertTrue(sentOut,
                "the computer left its warship standing for sixty cycles; "
                        + "retail walks its ships on the same fifty-cycle beat "
                        + "as its aircraft and gives an idle one somewhere to "
                        + "go");
        assertEquals(51, sentOnCycle,
                "the ships are walked two ticks after the aircraft, which is "
                        + "what puts the queue between two of the ship's action "
                        + "markers instead of immediately before one");
        assertFalse(hullSentOut,
                "retail leaves an idle transport standing; sending Orc 14's "
                        + "two out on the beat cost that mission seven proven "
                        + "cycles");
    }
}
