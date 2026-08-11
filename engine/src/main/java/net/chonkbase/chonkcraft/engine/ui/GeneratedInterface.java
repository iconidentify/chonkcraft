package net.chonkbase.chonkcraft.engine.ui;

import net.chonkbase.chonkcraft.engine.generated.GeneratedButtons;

/** Builds the command-panel model from the committed declarative snapshot. */
public final class GeneratedInterface {

    private GeneratedInterface() {
    }

    public static ButtonSet buttons() {
        ButtonSet buttons = new ButtonSet();
        for (GeneratedButtons.Row row : GeneratedButtons.ROWS) {
            buttons.add(new UnitButton(row.pos(), row.level(), row.icon(), row.action(),
                    row.value(), row.allowed(), row.allowArg(), row.popup(), row.key(),
                    row.hint(), row.forUnits()));
        }
        return buttons;
    }
}
