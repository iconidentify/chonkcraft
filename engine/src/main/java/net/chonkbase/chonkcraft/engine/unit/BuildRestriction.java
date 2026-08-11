package net.chonkbase.chonkcraft.engine.unit;

/**
 * One condition a building site has to meet before it may be founded.
 *
 * <p>Implements {@code CBuildRestriction} and its subclasses, checked by
 * {@code CanBuildHere}. The rules come from the {@code BuildingRules} key of
 * {@code DefineUnitType}.
 *
 * <p>Upstream declares six kinds -- {@code ontop}, {@code distance},
 * {@code addon}, {@code has-unit}, {@code surrounded-by} and
 * {@code retired-interpreter-callback}. Only two of them are used by anything ChonkCraft ships:
 * counted over every script in the checkout, {@code distance} appears 65 times
 * and {@code ontop} 4, and the other four appear not at all. So only those two
 * are modelled, and a kind the implementation cannot check becomes
 * {@link Unsupported}, which fails -- a rule that is not understood must
 * refuse the site rather than wave it through, or a type would become easier
 * to build here than upstream because the implementation did not know the rule.
 *
 * <p>The two that matter, in the game a player sees. {@code ontop} is the
 * entire oil economy: an oil platform is founded on an oil patch and nowhere
 * else, and the patch comes back when the platform is destroyed.
 * {@code distance} is why a town hall cannot be dropped on the gold mine and a
 * shipyard cannot be squeezed onto an oil patch.
 */
public sealed interface BuildRestriction
        permits BuildRestriction.OnTop, BuildRestriction.Distance,
                BuildRestriction.Unsupported {

    /**
     * Build only where a unit of a given type already stands.
     *
     * <p>Implements {@code CBuildRestrictionOnTop}. The square being built on
     * must hold a living, finished unit of {@code parentIdent} whose top-left corner is
     * that square, and nothing else of the same movement kind may be standing
     * inside its footprint.
     *
     * @param parentIdent    what has to be underneath, for example
     *                       {@code unit-oil-patch}
     * @param replaceOnBuild whether starting the building removes the parent
     *                       and takes over what it held
     *
     * @param replaceOnDie   whether destroying the building puts the parent
     *                       back, carrying whatever is left
     *
     */
    record OnTop(String parentIdent, boolean replaceOnBuild, boolean replaceOnDie)
            implements BuildRestriction {
    }

    /**
     * Build only at a stated distance from every unit of a given type.
     *
     * <p>Implements {@code CBuildRestrictionDistance}. The shipped data uses
     * {@code DistanceType = ">"} for two rules a player runs into
     * constantly: a town hall, keep or castle may not be founded within three
     * squares of a gold mine ({@code scripts/human/units.legacy-declaration:1194}), and a
     * shipyard or refinery may not be founded within three of an oil patch or
     * an oil platform ({@code :1087}, {@code :1295}).
     *
     * @param restrictIdent what to measure to
     * @param distance      the figure the comparison is made against
     * @param comparison    the script's {@code DistanceType}, as written
     * @param checkBuilder  whether the builder itself counts as a unit in the
     *                      way; false in everything ChonkCraft ships
     * @param diagonal      whether a unit off both axes counts; upstream
     *                      defaults it to true and no shipped rule sets it
     */
    record Distance(String restrictIdent, int distance, Comparison comparison,
            boolean checkBuilder, boolean diagonal) implements BuildRestriction {
    }

    /**
     * A rule kind this implementation does not model, which nothing can satisfy.
     *
     * <p>Kept as a rule rather than dropped on the floor. An and-list is
     * satisfied only when every restriction in it passes, so a dropped
     * {@code addon} would leave a list that passes on the strength of the
     * rules beside it and a building could be founded where upstream refuses
     * it. Nothing ChonkCraft ships reaches this, and it is here so that the day
     * something does, the symptom is a building that cannot be placed rather
     * than one that can be placed anywhere.
     *
     * @param kind the script's rule name, for the record
     */
    record Unsupported(String kind) implements BuildRestriction {
    }

    /**
     * The comparison a distance rule is written with.
     *
     * <p>{@code EComparison}, resolved by {@code toEComparison}. Every one of the 65 shipped
     * distance rules is {@code GREATER_THAN}; the rest are here because the
     * parser has to answer for a string it is given rather than assume.
     */
    enum Comparison {
        EQUAL, NOT_EQUAL, LESS_THAN, LESS_THAN_EQUAL, GREATER_THAN, GREATER_THAN_EQUAL;

        /** Resolves a script's {@code DistanceType}, or {@code null}. */
        public static Comparison byName(String name) {
            return switch (name) {
                case "==", "=" -> EQUAL;
                case "!=" -> NOT_EQUAL;
                case "<" -> LESS_THAN;
                case "<=" -> LESS_THAN_EQUAL;
                case ">" -> GREATER_THAN;
                case ">=" -> GREATER_THAN_EQUAL;
                default -> null;
            };
        }
    }
}
