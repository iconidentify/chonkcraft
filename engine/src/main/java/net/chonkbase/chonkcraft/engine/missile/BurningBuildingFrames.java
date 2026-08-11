package net.chonkbase.chonkcraft.engine.missile;

import java.util.List;

/**
 * Which fire a damaged building wears at a given fraction of its health.
 *
 * <p>Implements {@code BurningBuildingFrame} and the {@code BurningBuildingFrames}
 * table, filled by
 * {@code CclDefineBurningBuilding}. The shipped declaration is the last thing
 * in {@code scripts/missiles.legacy-declaration}:
 *
 * <pre>
 *   DefineBurningBuilding(
 *     {"percent", 0, "missile", "missile-big-fire"},
 *     {"percent", 50, "missile", "missile-small-fire"},
 *     {"percent", 75 } -- no missile
 *   )
 * </pre>
 *
 * <p>Nothing in this implementation read that until now, so a building under fire in
 * Warcraft II -- which smokes, then burns, then burns harder as it is beaten
 * down -- stood there unmarked however much damage it had taken. The player
 * lost the one cue that says at a glance which of their buildings is in
 * trouble, and the only way to tell a keep at nine tenths from a keep at a
 * tenth was to click it.
 *
 * <h2>What a "percent" means</h2>
 *
 * <p>An entry's percent is a <em>floor</em>, not a ceiling, and the entry that
 * applies is the last one whose percent is at or below the building's current
 * health. Upstream writes that as an {@code upper_bound} followed by a step
 * back, which is why {@code IsBurningBuildingFramesValid} insists the list be
 * sorted. Read the shipped table with that rule:
 *
 * <ul>
 *   <li>75% and above: the third entry, which names no missile at all -- so
 *       {@link #missileAt} answers null and a lightly damaged building does
 *       not burn. This is also how a fire goes out: a building repaired back
 *       past three quarters stops matching any missile.</li>
 *   <li>50% up to 74%: the small fire.</li>
 *   <li>Below 50%: the big fire.</li>
 * </ul>
 *
 * <p>Fifty is therefore small fire and not big: the boundary belongs to the
 * entry that names it. Reading the percents as ceilings instead -- the obvious
 * misreading, and the one the declaration's ordering invites -- inverts the
 * whole table, so a nearly dead keep would show a wisp of smoke and one barely
 * scratched would be an inferno.
 */
public final class BurningBuildingFrames {

    /**
     * One row of the table.
     *
     * @param percent the health percentage at or above which this row applies,
     *                until the next row takes over
     * @param missile the fire to draw, or null for "no fire at all", which is
     *                what the shipped table's last row says and what puts a
     *                repaired building's fire out
     */
    public record Frame(int percent, MissileType missile) {}

    /** The table before any script has spoken, and after a failed parse. */
    public static final BurningBuildingFrames NONE = new BurningBuildingFrames(List.of());

    private final List<Frame> frames;

    public BurningBuildingFrames(List<Frame> frames) {
        this.frames = List.copyOf(frames);
    }

    /** The rows, in the order the script declared them. */
    public List<Frame> frames() {
        return frames;
    }

    public boolean isEmpty() {
        return frames.isEmpty();
    }

    /**
     * The fire a building at {@code percent} of its health should wear, or
     * null for none.
     *
     * <p>{@code MissileBurningBuilding}. Upstream binary-searches, which is
     * only correct on a sorted table and is why it validates the table on
     * load; this picks the highest row at or below {@code percent} outright,
     * which is the same answer on sorted input and a sensible one on input a
     * mod got wrong. Ties go to the last row declared, as stepping back from
     * an upper bound does.
     */
    public MissileType missileAt(int percent) {
        Frame best = null;
        for (Frame frame : frames) {
            if (frame.percent() <= percent
                    && (best == null || frame.percent() >= best.percent())) {
                best = frame;
            }
        }
        return best == null ? null : best.missile();
    }

    /**
     * Whether the table is one upstream would have accepted.
     *
     * <p>{@code IsBurningBuildingFramesValid}: percents inside nought to a
     * hundred and sorted ascending. Upstream throws a script error when it is
     * not; this implementation reports rather than refuses, because a table it can still
     * read the right answer out of is not worth losing the rest of the script
     * over.
     */
    public boolean isValid() {
        if (frames.isEmpty()) {
            return true;
        }
        if (frames.get(0).percent() < 0 || frames.get(frames.size() - 1).percent() > 100) {
            return false;
        }
        for (int i = 1; i < frames.size(); i++) {
            if (frames.get(i - 1).percent() > frames.get(i).percent()) {
                return false;
            }
        }
        return true;
    }

    /**
     * The table the game scripts declared.
     *
     * <p>Global, as upstream's is: one {@code BurningBuildingFrames} vector
     * serves every burning building in the game, and the alternative here
     * would be threading the table through {@code setMissileTypes} and every
     * one of its callers for a value that is the same in all of them. A
     * {@link net.chonkbase.chonkcraft.engine.World} that wants its own -- a test
     * with a made-up table -- sets one on itself and never touches this.
     */
    public static BurningBuildingFrames declared() {
        return declared;
    }

    /** Records what {@code DefineBurningBuilding} said. */
    public static void declare(BurningBuildingFrames frames) {
        declared = frames == null ? NONE : frames;
    }

    private static volatile BurningBuildingFrames declared = NONE;
}
