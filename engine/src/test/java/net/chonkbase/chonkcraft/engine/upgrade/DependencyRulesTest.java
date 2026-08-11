package net.chonkbase.chonkcraft.engine.upgrade;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DependencyRulesTest {

    @Test
    void somethingWithNoRuleIsAlwaysAllowed() {
        // Most of the roster has no prerequisites, and the table only names
        // what does. Treating silence as a refusal would empty every panel.
        assertTrue(new DependencyRules().isSatisfied("unit-footman", ident -> false));
    }

    @Test
    void everyRequirementInASetMustBeMet() {
        DependencyRules rules = new DependencyRules();
        rules.define("unit-ballista",
                List.of(List.of("unit-human-blacksmith", "unit-elven-lumber-mill")));

        Set<String> owned = Set.of("unit-human-blacksmith");
        assertFalse(rules.isSatisfied("unit-ballista", owned::contains));

        Set<String> both = Set.of("unit-human-blacksmith", "unit-elven-lumber-mill");
        assertTrue(rules.isSatisfied("unit-ballista", both::contains));
    }

    @Test
    void anyOneAlternativeWillDo() {
        // How the scripts say "a keep or a castle, either way with the
        // ranger upgrade".
        DependencyRules rules = new DependencyRules();
        rules.define("upgrade-ranger", List.of(
                List.of("unit-keep", "upgrade-ranger-trained"),
                List.of("unit-castle", "upgrade-ranger-trained")));

        assertTrue(rules.isSatisfied("upgrade-ranger",
                Set.of("unit-castle", "upgrade-ranger-trained")::contains));
        assertTrue(rules.isSatisfied("upgrade-ranger",
                Set.of("unit-keep", "upgrade-ranger-trained")::contains));
        // The castle alone is not enough; the upgrade is in both alternatives.
        assertFalse(rules.isSatisfied("upgrade-ranger", Set.of("unit-castle")::contains));
    }

    @Test
    void requirementSetsAreNotSharedWithTheCaller() {
        List<String> mutable = new java.util.ArrayList<>(List.of("unit-keep"));
        DependencyRules rules = new DependencyRules();
        rules.define("unit-knight", List.of(mutable));
        mutable.clear();

        assertFalse(rules.isSatisfied("unit-knight", ident -> false));
    }
}
