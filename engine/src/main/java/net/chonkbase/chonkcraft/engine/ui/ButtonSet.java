package net.chonkbase.chonkcraft.engine.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Every command button the game defines.
 *
 * <p>Implements the {@code UnitButtonTable}.
 *
 * <p>Buttons are kept in the order the scripts declared them, because that
 * order settles collisions: several buttons share a slot for the same unit,
 * and the last one whose availability check passes is the one shown. Upstream
 * is explicit about the direction, in {@code UpdateButtonPanelMultipleUnits}:
 * "OverWrite, So take last valid button."
 *
 * <p>The direction is not a detail. A barracks has both a minuteman and a
 * footman declared at slot one with no check on either, and taking the first
 * would train peasants for the rest of the game.
 */
public final class ButtonSet {

    private final List<UnitButton> buttons = new ArrayList<>();

    public void add(UnitButton button) {
        buttons.add(button);
    }

    /** Every button defined, in declaration order. */
    public List<UnitButton> all() {
        return List.copyOf(buttons);
    }

    public int size() {
        return buttons.size();
    }

    /**
     * The buttons a unit shows on a page.
     *
     * <p>At most one per slot: where several claim the same one, the last
     * that {@code available} accepts wins, and the earlier ones are the
     * fallbacks it shadows.
     *
     * @param unitIdent the selected unit's identifier
     * @param level     the page being shown
     * @param available decides whether a button's check passes, given its
     *                  {@code Allowed} name and its value
     * @return a nine-element array indexed by slot minus one, with nulls for
     *         empty slots
     */
    public UnitButton[] page(String unitIdent, int level, Availability available) {
        UnitButton[] slots = new UnitButton[9];
        for (UnitButton button : buttons) {
            int index = button.pos() - 1;
            if (index < 0 || index >= slots.length) {
                continue;
            }
            if (button.level() != level || !button.appliesTo(unitIdent)) {
                continue;
            }
            if (available != null && !available.test(button)) {
                continue;
            }
            // Overwrites whatever was there: later declarations win.
            slots[index] = button;
        }
        return slots;
    }

    /** Decides whether a button's availability check passes. */
    @FunctionalInterface
    public interface Availability {
        boolean test(UnitButton button);
    }
}
