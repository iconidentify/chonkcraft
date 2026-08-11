package net.chonkbase.chonkcraft.engine.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ButtonSetTest {

    private static UnitButton button(int pos, int level, String action, String value,
            String... forUnits) {
        return new UnitButton(pos, level, "icon-" + action, action, value, null, List.of(),
                null, "x", "HINT", List.of(forUnits));
    }

    @Test
    void laterDeclarationWinsTheSlot() {
        // The direction upstream takes, and the one Warcraft II depends on: a
        // barracks declares the minuteman and then the footman at slot one,
        // both unconditionally, and the footman is what it trains.
        ButtonSet set = new ButtonSet();
        UnitButton minuteman = button(1, 0, "train-unit", "unit-attack-peasant", "unit-barracks");
        UnitButton footman = button(1, 0, "train-unit", "unit-footman", "unit-barracks");
        set.add(minuteman);
        set.add(footman);

        assertSame(footman, set.page("unit-barracks", 0, null)[0]);
    }

    @Test
    void aVetoedButtonFallsBackToTheOneItShadows() {
        // Two armour icons share slot two, the better one gated on the upgrade.
        // Without the upgrade the panel must show the earlier one rather than
        // an empty slot.
        ButtonSet set = new ButtonSet();
        UnitButton shield1 = button(2, 0, "research", "upgrade-shield1", "unit-blacksmith");
        UnitButton shield2 = button(2, 0, "research", "upgrade-shield2", "unit-blacksmith");
        set.add(shield1);
        set.add(shield2);

        UnitButton[] page = set.page("unit-blacksmith", 0,
                candidate -> !"upgrade-shield2".equals(candidate.value()));
        assertSame(shield1, page[0 + 1]);
    }

    @Test
    void buttonsAreSeparatedByPage() {
        ButtonSet set = new ButtonSet();
        set.add(button(1, 0, "move", null, "unit-peasant"));
        UnitButton farm = button(1, 1, "build", "unit-farm", "unit-peasant");
        set.add(farm);

        assertEquals("move", set.page("unit-peasant", 0, null)[0].action());
        assertSame(farm, set.page("unit-peasant", 1, null)[0]);
    }

    @Test
    void aWildcardMaskReachesEveryUnit() {
        // How the cancel buttons in scripts/buttons.legacy-declaration apply to everything.
        ButtonSet set = new ButtonSet();
        set.add(button(9, 0, "cancel", null, "*"));

        assertEquals("cancel", set.page("unit-anything-at-all", 0, null)[8].action());
    }

    @Test
    void slotsOutsideTheGridAreIgnored() {
        ButtonSet set = new ButtonSet();
        set.add(button(0, 0, "move", null, "unit-peasant"));
        set.add(button(10, 0, "stop", null, "unit-peasant"));

        for (UnitButton slot : set.page("unit-peasant", 0, null)) {
            assertNull(slot);
        }
    }

    @Test
    void theHotkeyMarkerIsStrippedFromHints() {
        UnitButton move = new UnitButton(1, 0, "icon-move", "move", null, null, List.of(),
                null, "m", "~!MOVE", List.of("unit-peasant"));
        assertEquals("MOVE", move.plainHint());
    }

    @Test
    void aPageSwitchReportsItsTarget() {
        UnitButton advanced = button(8, 0, "button", "2", "unit-peasant");
        assertEquals(2, advanced.switchesToLevel());
        assertEquals(-1, button(1, 0, "move", null, "unit-peasant").switchesToLevel());
        // A value that is not a number must not throw on the way past.
        assertEquals(-1, button(1, 0, "button", "not-a-number", "unit-peasant").switchesToLevel());
    }

    @Test
    void everyDeclarationIsKept() {
        ButtonSet set = new ButtonSet();
        set.add(button(1, 0, "move", null, "unit-peasant"));
        set.add(button(1, 0, "stop", null, "unit-peasant"));
        // Both survive even though only one can be shown: the shadowed one is
        // the fallback when a check vetoes the other.
        assertEquals(2, set.size());
        assertTrue(set.all().stream().anyMatch(b -> "move".equals(b.action())));
    }
}
