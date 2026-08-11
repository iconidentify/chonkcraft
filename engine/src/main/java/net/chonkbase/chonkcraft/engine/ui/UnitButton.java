package net.chonkbase.chonkcraft.engine.ui;

import java.util.List;

/**
 * One slot in the command panel.
 *
 * <p>Implements {@code ButtonAction}, filled from
 * the {@code DefineButton} calls in {@code scripts/human/buttons.legacy-declaration} and its
 * orc counterpart.
 *
 * <p>The panel is not a fixed layout with special cases for each unit. It is a
 * query: every button names the units it belongs to, and drawing the panel
 * means asking which buttons claim the selected unit. That is why a footman and
 * a town hall show completely different grids without either being described
 * anywhere as a whole.
 *
 * @param pos      the slot, one to nine, reading left to right and top to
 *                 bottom over the three-by-three grid
 * @param level    which page the button lives on. Zero is what a unit shows
 *                 when first selected; the build buttons switch to one and two,
 *                 which is how a peasant offers more buildings than fit in nine
 *                 slots
 * @param icon     the icon identifier, resolved against {@link IconSet}
 * @param action   what pressing it does, such as {@code "train-unit"}
 * @param value    the action's argument, usually a unit or upgrade identifier
 * @param allowed  the name of the check that decides whether it is available,
 *                 or null when it always is
 * @param allowArg the check's arguments, such as the upgrade that must be
 *                 researched first
 * @param popup    the popup panel this button shows on hover, or null
 * @param key      the hotkey, as written in the script
 * @param hint     the text shown on hover, with {@code ~!} marking the hotkey
 * @param forUnits the unit identifiers this button belongs to
 */
public record UnitButton(int pos, int level, String icon, String action, String value,
        String allowed, List<String> allowArg, String popup, String key, String hint,
        List<String> forUnits) {

    public UnitButton {
        forUnits = List.copyOf(forUnits);
        allowArg = List.copyOf(allowArg);
    }

    /**
     * Whether this button belongs to a unit type.
     *
     * <p>{@code "*"} means every unit, which is how the cancel buttons in
     * {@code scripts/buttons.legacy-declaration} reach everything at once.
     */
    public boolean appliesTo(String unitIdent) {
        return forUnits.contains("*") || forUnits.contains(unitIdent);
    }

    /**
     * The hint with the hotkey marker removed.
     *
     * <p>The scripts write {@code "TRAIN ~!PEASANT"}, where {@code ~!} marks
     * the letter the key underlines. Nothing here draws underlines yet, so the
     * marker would otherwise show up as literal punctuation.
     */
    public String plainHint() {
        return hint == null ? "" : hint.replace("~!", "").replace("~<", "").replace("~>", "");
    }

    /**
     * The page this button switches to, or {@code -1} if it does not.
     *
     * <p>{@code Action = "button"} is the script's way of saying "open another
     * page", with the target page as its value.
     */
    public int switchesToLevel() {
        if (!"button".equals(action) || value == null) {
            return -1;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
