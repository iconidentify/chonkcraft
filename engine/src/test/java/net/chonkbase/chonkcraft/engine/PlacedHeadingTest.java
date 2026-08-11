package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
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
 * A unit the map places keeps the angle it drew, whole.
 *
 * <p>{@code CUnit::Init} gives every unit that faces more than one way an
 * opening heading, and it is a whole byte: {@code Direction = (SyncRand() >> 8)
 * & 0xFF}. {@code Direction} is an angle in
 * 256ths and stays one -- every step sets it to {@code DirectionToHeading} of
 * the step exactly and the eight-way facing
 * the sprite sheet wants is worked out from it when it is drawn.
 *
 * <p>This implementation made the draw, because the displacement heading a unit that
 * will not fit uses comes out of the same sequence and lands elsewhere without
 * it, and then threw the number away. Every unit a map placed therefore stood
 * facing south, whatever it had drawn.
 *
 * <p>It shows up wherever an animation asks how far a unit has to turn.
 * {@code maps/demo/demo03} opens with a ballista at 0,2 that steps east on
 * cycle 2: upstream turns from the 34 it drew to the 64 a step due east is,
 * which is a rotation of thirty, and walks; this implementation turned from 128 -- due
 * south -- which is sixty-four, and the ballista's own Move animation opens
 * with {@code if-var R <= -60 turn}, thirty cycles of standing still. It
 * needed sixty-two cycles to cross one square where upstream needed
 * thirty-two, and that map's first divergence moved from cycle 65 to 67.
 */
class PlacedHeadingTest {

    /**
     * The first angle the load-time generator hands out.
     *
     * <p>Its seed is {@code 0x87654321}, drawing is {@code seed >>> 16}, and
     * the heading is the top byte of that: {@code (0x8765 >> 8) & 0xFF}. The
     * number matters less than the shape of it -- 135 is not a multiple of
     * thirty-two, so a unit holding it is holding something no facing could
     * have given it.
     */
    private static final int FIRST_DRAWN_ANGLE = 0x87;

    /** Which of eight facings 135 bands to: (135 + 16) / 32, which is south. */
    private static final int BANDED_FACING = 4;

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
        set.put(AnimationSet.State.STILL, Animation.parse("still",
                List.of("frame 0", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    private static Player[] players() {
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i, PudMap.PlayerType.NOBODY,
                    PudMap.Race.NEUTRAL);
        }
        players[0] = new Player(0, PudMap.PlayerType.PERSON, PudMap.Race.HUMAN);
        return players;
    }

    @Test
    @DisplayName("a map-placed unit faces the angle it drew, not the facing nearest it")
    void thePlacedUnitKeepsTheWholeAngle() {
        World world = new World(grass(24));

        Unit first = world.createUnitForMap(walker(), 0, 10, 10);
        assertNotNull(first, "the unit would not fit on open grass");

        assertEquals(FIRST_DRAWN_ANGLE, first.direction(),
                "the unit faces " + first.direction() + ". The load-time generator's first"
                        + " number gives " + FIRST_DRAWN_ANGLE + ", and CUnit::Init keeps the"
                        + " byte it drew -- a unit left facing 128 is one whose draw was made"
                        + " and thrown away");
        assertNotEquals(0, first.direction() % 32,
                "the angle came out a whole facing, so nothing is being kept that a facing"
                        + " could not have held");
        assertEquals(BANDED_FACING, first.heading(),
                "the facing to draw is banded from the angle, not stored in its place");
    }

    @Test
    @DisplayName("and the next one drawn is the next number, so the sequence still runs")
    void theSequenceIsUnchanged() {
        World world = new World(grass(24));

        Unit first = world.createUnitForMap(walker(), 0, 10, 10);
        Unit second = world.createUnitForMap(walker(), 0, 14, 10);
        assertNotNull(second, "the second unit would not fit on open grass");

        assertNotEquals(first.direction(), second.direction(),
                "both units drew the same angle, so the second draw is not happening and the"
                + " displacement headings that come out of the same sequence will land"
                + " somewhere upstream's do not");
    }

    @Test
    @DisplayName("an oracle seed pins load-time headings without consuming cycle one's seed")
    void anOracleSeedPinsBothStreamsAtTheirBoundary() {
        World world = new World(grass(24), players(), 1);

        Unit first = world.createUnitForMap(walker(), 0, 10, 10);

        assertNotNull(first);
        assertEquals(0, first.direction(),
                "seed one has zero in the high word used for the first load heading");
        assertEquals(1, world.randomSeed(),
                "load-time construction consumed the live synchronized seed");
    }
}
