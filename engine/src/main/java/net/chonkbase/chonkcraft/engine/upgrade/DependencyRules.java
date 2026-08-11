package net.chonkbase.chonkcraft.engine.upgrade;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * What a player must already have before something becomes available.
 *
 * <p>Implements the dependency table,
 * filled from the {@code DefineDependency} calls in the two race upgrade
 * scripts.
 *
 * <p>This is the tech tree, and it lives entirely in data:
 *
 * <pre>
 *   DefineDependency("unit-ogre-mage",
 *     {"upgrade-ogre-mage", "unit-ogre-mound", "unit-orc-blacksmith"})
 *   DefineDependency("upgrade-ranger",
 *     {"unit-keep", "upgrade-ranger"}, "or", {"unit-castle", "upgrade-ranger"})
 * </pre>
 *
 * <p>Each braced list is a conjunction, and {@code "or"} separates
 * alternatives, so the second reads "a keep or a castle, either way with the
 * ranger upgrade". Sixty-eight of these describe the whole of Warcraft II's
 * progression, which is why nothing about swords needing a blacksmith appears
 * anywhere in this implementation's Java.
 */
public final class DependencyRules {

    /** Alternative requirement sets for one target; any satisfied set will do. */
    private final Map<String, List<List<String>>> rules = new LinkedHashMap<>();

    /** Records that {@code target} needs any one of {@code alternatives}. */
    public void define(String target, List<List<String>> alternatives) {
        List<List<String>> copy = new ArrayList<>();
        for (List<String> set : alternatives) {
            copy.add(List.copyOf(set));
        }
        rules.put(target, copy);
    }

    public int size() {
        return rules.size();
    }

    /** The alternatives recorded for a target, empty when it has none. */
    public List<List<String>> alternativesFor(String target) {
        return rules.getOrDefault(target, List.of());
    }

    /**
     * Whether a player may build, train or research something.
     *
     * <p>Anything with no rule is allowed: most of the roster has no
     * prerequisites, and the table only names what does.
     *
     * @param target    the unit or upgrade identifier being checked
     * @param satisfied answers whether the player has one requirement, which
     *                  means owning a unit type or having researched an upgrade
     *                  depending on the identifier's prefix
     */
    public boolean isSatisfied(String target, Predicate<String> satisfied) {
        List<List<String>> alternatives = rules.get(target);
        if (alternatives == null || alternatives.isEmpty()) {
            return true;
        }
        for (List<String> set : alternatives) {
            boolean all = true;
            for (String requirement : set) {
                if (!satisfied.test(requirement)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return true;
            }
        }
        return false;
    }
}
