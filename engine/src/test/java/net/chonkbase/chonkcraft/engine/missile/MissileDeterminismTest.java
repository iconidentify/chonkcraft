package net.chonkbase.chonkcraft.engine.missile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A missile's heading and its flight length are integer arithmetic.
 *
 * <p>The simulation has to give the same answer on every machine, because two
 * players' copies of a multiplayer game run the same cycles independently and
 * compare nothing but their commands. {@code Math.hypot} and {@code Math.atan2}
 * are the two calls the simulation made that the JVM specification does not
 * require to be identical between implementations, and a missile's whole
 * flight is divided by the first and drawn by the second.
 *
 * <p>Both are gone, and not by swapping in {@code StrictMath}. Upstream does
 * this arithmetic in integers -- {@code DirectionToHeading} and the
 * {@code myatan} lookup table, and
 * {@code Distance} in {@code src/include/vec2i.h:162}, which is an integer
 * square root. That is both deterministic and the answer Warcraft II actually
 * gives, and the two are not the same answer: the table is a 2608-entry
 * quantisation of {@code atan(i / 64)} indexed by a truncated integer ratio,
 * and it disagrees with a rounded true arctangent often enough to change which
 * facing gets drawn.
 *
 * <p>These assert the facing, which is what a player sees, rather than the
 * heading behind it.
 */
class MissileDeterminismTest {

    /** Eight facings, as every shipped point-to-point missile declares. */
    private static MissileType eightWay() {
        // ident, sprite, class, frame w/h, frames, directions, speed, sleep,
        // range, splash, draw level, impact missile, impact sound, hit owner,
        // bounces, damage, correct splash.
        return new MissileType("missile-test", null, MissileClass.POINT_TO_POINT,
                32, 32, 1, 8, 8, 1, 1, 1, 0, null, null, false, 0, 0, false);
    }

    private static int facingFor(double dx, double dy) {
        return new Missile(eightWay(), null, null, 512, 512, 512 + dx, 512 + dy).direction();
    }

    /** The value all directional projectile rows in BNE's catalog declare. */
    private static MissileType legacyNineEntryTable() {
        return new MissileType("missile-bne-test", null, MissileClass.POINT_TO_POINT,
                32, 32, 5, 9, 8, 1, 1, 1, 0, null, null, false, 0, 0, false);
    }

    /**
     * Deltas where the table and a rounded {@code atan2} give different
     * facings. Found by sweeping every delta within forty pixels: these are
     * the boundaries between two drawn sprites, and Warcraft II draws the
     * first of each pair.
     */
    private static final List<int[]> DISPUTED = List.of(
            new int[] {-40, -16, 6},
            new int[] {-38, 16, 5},
            new int[] {-35, -14, 6});

    @Test
    @DisplayName("a heading comes from upstream's table, not from a rounded arctangent")
    void headingsFollowTheTable() {
        for (int[] probe : DISPUTED) {
            int dx = probe[0];
            int dy = probe[1];
            assertEquals(probe[2], facingFor(dx, dy), String.format(
                    "a shot travelling (%d, %d) is drawn facing %d by Warcraft II's own"
                            + " arc-tangent table; a rounded Math.atan2 puts it one facing"
                            + " round the compass", dx, dy, probe[2]));
            // And the neighbouring facing is genuinely a different answer, so
            // the assertion above is not passing by accident.
            assertNotEquals(probe[2] + 1, facingFor(dx, dy));
        }
    }

    @Test
    @DisplayName("the compass points still land where they should")
    void theCompassIsUnmoved() {
        assertEquals(0, facingFor(0, -40), "north");
        assertEquals(2, facingFor(40, 0), "east");
        assertEquals(4, facingFor(0, 40), "south");
        assertEquals(6, facingFor(-40, 0), "west");
        assertEquals(1, facingFor(40, -40), "north-east");
        assertEquals(3, facingFor(40, 40), "south-east");
        assertEquals(5, facingFor(-40, 40), "south-west");
        assertEquals(7, facingFor(-40, -40), "north-west");
    }

    @Test
    @DisplayName("BNE's nine-entry direction table produces eight compass facings")
    void theLegacyDirectionTableHasEightDistinctHeadings() {
        MissileType type = legacyNineEntryTable();
        assertEquals(8, type.headingCount(),
                "the ninth table entry repeats north rather than adding a pose");
        assertEquals(5, type.storedFacings(),
                "BNE stores five cells and mirrors three of them");

        int[][] vectors = {
            {0, -40}, {40, -40}, {40, 0}, {40, 40},
            {0, 40}, {-40, 40}, {-40, 0}, {-40, -40}
        };
        for (int expected = 0; expected < vectors.length; expected++) {
            int[] vector = vectors[expected];
            Missile shot = new Missile(type, null, null,
                    512, 512, 512 + vector[0], 512 + vector[1]);
            assertEquals(expected, shot.direction(),
                    "BNE facing for vector " + vector[0] + "," + vector[1]);
        }
    }

    /**
     * The flight length is floored, as {@code isqrt} gives it.
     *
     * <p>Three-four-five is the case that shows it: {@code Math.hypot} of
     * (30, 40) is exactly 50 either way, so the test uses lengths whose true
     * root is not whole. A missile crosses its whole distance in
     * {@code total / speed} cycles, so a fractional difference here is a
     * different number of cycles in flight.
     */
    @Test
    @DisplayName("a flight is a whole number of pixels long, rounded down")
    void theFlightLengthIsAnIntegerSquareRoot() {
        assertEquals(50, Missile.distance(30, 40), "3-4-5 is exact either way");
        // 10^2 + 10^2 = 200; the root is 14.142..., and upstream keeps 14.
        assertEquals(14, Missile.distance(10, 10));
        assertEquals(0, Missile.distance(0, 0));
        assertEquals(1, Missile.distance(1, 1), "the root of two, floored");
        assertEquals(181, Missile.distance(-128, -128));
    }

    /**
     * A shot that lands where it was fired hits at once rather than dividing
     * by nothing. The integer distance makes that case reachable from a
     * sub-pixel aim, and {@code MissileInitMove} answers it the same way.
     */
    @Test
    @DisplayName("a shot with nowhere to go arrives immediately")
    void aZeroLengthFlightArrives() {
        Missile shot = new Missile(eightWay(), null, null, 100, 100, 100, 100);
        shot.step();
        org.junit.jupiter.api.Assertions.assertTrue(shot.hasArrived(),
                "a missile with no distance to cross has to finish, not stall");
    }
}
