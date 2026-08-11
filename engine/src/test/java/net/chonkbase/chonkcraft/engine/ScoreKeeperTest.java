package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Points for kills.
 *
 * <p>{@code UI.Resources[ScoreCost]} reserves a place in the top bar for a
 * figure this implementation never computed, so the slot was parsed, positioned and left
 * blank. Every unit type in the game carries a {@code Points} value for exactly
 * this.
 */
class ScoreKeeperTest {

    private static World world() {
        GameMap map = new GameMap(32, 32, new Tileset());
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        // Three sides, not two. A two-sided fixture cannot tell "pay the
        // killer" apart from "pay everyone who was hostile", which is exactly
        // why the old behaviour survived: all 52 campaign missions are
        // two-sided.
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < Player.MAX; i++) {
            players[i] = new Player(i, i < 3 ? PudMap.PlayerType.PERSON
                    : PudMap.PlayerType.NOBODY, PudMap.Race.HUMAN);
        }
        World world = new World(map, players);
        // Nobody allied with anybody, which is what "enemy" means here.
        for (int a = 0; a < 3; a++) {
            for (int b = 0; b < 3; b++) {
                if (a != b) {
                    world.setAllied(a, b, false);
                }
            }
        }
        return world;
    }

    private static UnitType soldier(String ident, int points) {
        UnitType type = new UnitType(ident);
        type.setName(ident);
        type.setHitPoints(60);
        type.setPoints(points);
        type.setLandUnit(true);
        type.setTileSize(1, 1);
        return type;
    }

    @Test
    @DisplayName("a kill is worth the dead unit's points, once")
    void aKillBanksItsPoints() {
        World world = world();
        ScoreKeeper scores = new ScoreKeeper(world);
        UnitType footman = soldier("unit-footman", 50);

        Unit mine = world.createUnit(footman, 0, 4, 4);
        Unit theirs = world.createUnit(footman, 1, 6, 4);
        assertNotNull(mine);
        assertNotNull(theirs);

        scores.update();
        assertEquals(0, world.player(0).score(), "nothing has died yet");

        world.kill(theirs, mine);
        scores.update();
        assertEquals(50, world.player(0).score(), "the enemy footman was worth fifty");
        assertEquals(0, world.player(1).score(), "the side that lost it scores nothing");

        // Counted once, however many times the keeper looks. A corpse lingers
        // for many cycles and would otherwise be paid for on each of them.
        for (int i = 0; i < 20; i++) {
            scores.update();
        }
        assertEquals(50, world.player(0).score());
        assertEquals(1, scores.counted());
    }

    @Test
    @DisplayName("losing your own units earns nobody anything from you")
    void yourOwnDeadDoNotScoreForYou() {
        World world = world();
        ScoreKeeper scores = new ScoreKeeper(world);
        Unit mine = world.createUnit(soldier("unit-peasant", 30), 0, 4, 4);
        Unit theirs = world.createUnit(soldier("unit-grunt", 50), 1, 20, 20);
        assertNotNull(mine);
        assertNotNull(theirs);
        world.kill(mine, theirs);
        scores.update();
        assertEquals(0, world.player(0).score(), "you are not your own enemy");
        assertEquals(30, world.player(1).score(), "the side that killed it is");
    }

    @Test
    @DisplayName("a type worth nothing scores nothing")
    void unitsWorthNoPointsAreIgnored() {
        World world = world();
        ScoreKeeper scores = new ScoreKeeper(world);
        Unit critter = world.createUnit(soldier("unit-critter", 0), 1, 4, 4);
        Unit killer = world.createUnit(soldier("unit-footman", 50), 0, 20, 20);
        assertNotNull(critter);
        assertNotNull(killer);
        world.kill(critter, killer);
        scores.update();
        assertEquals(0, world.player(0).score());
    }

    /** A building, which is the one thing the razings count tests for. */
    private static UnitType hall(String ident, int points) {
        UnitType type = soldier(ident, points);
        type.setLandUnit(false);
        type.setBuilding(true);
        type.setTileSize(2, 2);
        return type;
    }

    @Test
    @DisplayName("kills and razings are tallied apart from each other")
    void unitsAndBuildingsAreCountedSeparately() {
        World world = world();
        ScoreKeeper scores = new ScoreKeeper(world);

        Unit soldier = world.createUnit(soldier("unit-grunt", 50), 1, 4, 4);
        Unit building = world.createUnit(hall("unit-farm", 30), 1, 10, 10);
        Unit killer = world.createUnit(soldier("unit-footman", 50), 0, 20, 20);
        assertNotNull(soldier);
        assertNotNull(building);
        assertNotNull(killer);

        world.kill(soldier, killer);
        world.kill(building, killer);
        scores.update();

        assertEquals(1, world.player(0).totalKills(),
                "one enemy unit died, and TotalKills counts units");
        assertEquals(1, world.player(0).totalRazings(),
                "one enemy building died, and TotalRazings counts buildings");
        assertEquals(80, world.player(0).score(), "both were worth their points");
        assertEquals(0, world.player(1).totalKills(), "the side that lost them counts nothing");
        assertEquals(0, world.player(1).totalRazings());

        // Once each, however often the keeper looks, exactly as the score is.
        for (int i = 0; i < 20; i++) {
            scores.update();
        }
        assertEquals(1, world.player(0).totalKills());
        assertEquals(1, world.player(0).totalRazings());
    }

    /**
     * Upstream raises the tally outside the line that adds the points, so a
     * kill worth nothing is still a kill. Sheep and seals carry no
     * {@code Points} at all and there are dozens of them on some maps.
     */
    /**
     * The case the whole rule exists for. This used to pay every player who
     * counted the dead unit as an enemy, which is indistinguishable from the
     * right answer in a two-sided game -- and all 52 campaign missions are
     * two-sided, which is why it survived.
     */
    @Test
    @DisplayName("only the killer is paid, not everyone who was hostile")
    void onlyTheKillerIsPaid() {
        World world = world();
        ScoreKeeper scores = new ScoreKeeper(world);
        Unit victim = world.createUnit(soldier("unit-grunt", 50), 1, 4, 4);
        Unit killer = world.createUnit(soldier("unit-footman", 50), 0, 6, 4);
        Unit bystander = world.createUnit(soldier("unit-footman", 50), 2, 20, 20);
        assertNotNull(victim);
        assertNotNull(killer);
        Assumptions.assumeTrue(bystander != null, "no third slot on this fixture");
        Assumptions.assumeTrue(world.isEnemyPlayer(2, 1),
                "the third player must also be hostile or this proves nothing");

        world.kill(victim, killer);
        scores.update();

        assertEquals(50, world.player(0).score(), "the player who struck the blow");
        assertEquals(0, world.player(2).score(),
                "a third player who was merely hostile to the dead unit was paid for a kill"
                        + " it had nothing to do with");
    }

    /** A death nobody caused pays nobody: a cancelled building, a summon expiring. */
    @Test
    @DisplayName("a death with no killer is credited to nobody")
    void anUncausedDeathPaysNobody() {
        World world = world();
        ScoreKeeper scores = new ScoreKeeper(world);
        Unit stray = world.createUnit(soldier("unit-grunt", 50), 1, 4, 4);
        assertNotNull(stray);
        world.kill(stray);
        scores.update();
        for (int player = 0; player < Player.MAX; player++) {
            if (world.player(player) != null) {
                assertEquals(0, world.player(player).score(),
                        "player " + player + " was paid for a death nobody caused");
            }
        }
    }

    @Test
    @DisplayName("a kill worth no points is still a kill")
    void worthlessKillsStillCount() {
        World world = world();
        ScoreKeeper scores = new ScoreKeeper(world);
        Unit critter = world.createUnit(soldier("unit-critter", 0), 1, 4, 4);
        Unit killer = world.createUnit(soldier("unit-footman", 50), 0, 20, 20);
        assertNotNull(critter);
        assertNotNull(killer);
        world.kill(critter, killer);
        scores.update();
        assertEquals(0, world.player(0).score(), "it was worth nothing");
        assertEquals(1, world.player(0).totalKills(), "it was still something you killed");
    }

    @Test
    @DisplayName("the score never goes backwards")
    void theScoreOnlyRises() {
        Player player = new Player(0, PudMap.PlayerType.PERSON, PudMap.Race.HUMAN);
        player.addScore(120);
        player.addScore(-40);
        assertEquals(120, player.score(), "a negative award is not a deduction");
        player.setScore(-5);
        assertTrue(player.score() >= 0);
    }
}
