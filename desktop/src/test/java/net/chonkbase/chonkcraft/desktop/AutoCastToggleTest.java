package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.ui.UnitButton;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Turning a spell into a standing order, from the interface.
 *
 * <p>The world has had {@code setAutoCast} and a caster that acts on it for
 * some time, and there was no way to reach either from the game: no key, no
 * click, no mark on the panel. A setting that cannot be turned on is a setting
 * that does not exist.
 *
 * <p>These drive the same path a control-click drives, and check the three
 * things the upstream branch is careful about: the order travels as a command
 * rather than being applied on the spot, a spell that declares no autocast
 * clause is refused, and a mixed group is brought into agreement rather than
 * inverted unit by unit.
 */
class AutoCastToggleTest {

    private static final String MAP = "campaigns/human/level02h";

    private record Rig(GameScreen screen, World world, CommandPanel panel,
            java.util.List<GameCommand> sent, GameData data) {}

    private static GameData data() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    /**
     * A screen with two mages on it and a tap on the command line.
     *
     * <p>The commands are both recorded and applied, so a test can say what
     * was sent and then look at what it did.
     */
    private static Rig rig() {
        GameData data = data();
        PudMap pud = data.campaignMap(MAP);
        Assumptions.assumeTrue(pud != null, "no campaign map available");
        var tileset = data.loadTileset(pud.tileset());
        World world = new World(GameMap.from(pud, tileset.tileset()), Player.from(pud));
        world.setUpgrades(data.upgrades().upgrades());
        world.setSpells(data.spells().spells());
        world.setMissileTypes(data.missiles().types());
        world.fog().revealAll(0);
        for (String ident : data.upgrades().upgrades().all().keySet()) {
            world.upgrades(0).complete(ident);
        }
        String tilesetName = pud.tileset() == PudMap.Tileset.FOREST
                ? "summer"
                : pud.tileset().name().toLowerCase(java.util.Locale.ROOT);

        var layout = data.uiLayout("human", 640, 480);
        CommandPanel panel = new CommandPanel(world, data, data.userInterface(tilesetName),
                data.upgrades().dependencies(), 0, tilesetName, "human",
                data.unitTypes().types(), layout);
        CommandApplier applier = new CommandApplier(world,
                new java.util.ArrayList<>(data.unitTypes().types().values()));
        applier.setUpgrades(data.upgrades().upgrades().all().keySet());
        applier.setSpells(data.spells().spells().all().keySet());
        java.util.List<GameCommand> sent = new java.util.ArrayList<>();
        CommandSink sink = command -> {
            sent.add(command);
            applier.apply(command);
        };
        GameScreen screen = new GameScreen(world, data,
                new java.awt.image.BufferedImage(64, 64,
                        java.awt.image.BufferedImage.TYPE_INT_RGB),
                tileset.palette(), tilesetName, 0, 640, 480, null, null, panel, applier,
                sink, java.util.List.of(), "human");
        screen.setLayout(layout);
        return new Rig(screen, world, panel, sent, data);
    }

    /** The panel's own button for a spell, found by drawing the panel. */
    private static UnitButton spellButton(Rig rig, Unit caster, boolean autoCastable) {
        java.awt.image.BufferedImage frame = new java.awt.image.BufferedImage(640, 480,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        var g2 = frame.createGraphics();
        rig.panel().draw(g2, caster);
        g2.dispose();
        for (int slot = 0; slot < 9; slot++) {
            var bounds = rig.panel().boundsOf(slot);
            if (bounds == null) {
                continue;
            }
            UnitButton button = rig.panel().buttonAt(bounds.x + 1, bounds.y + 1);
            if (button == null || !"cast-spell".equals(button.action())) {
                continue;
            }
            var spell = rig.data().spells().spells().get(button.value());
            if (spell != null && spell.autoCastable() == autoCastable) {
                return button;
            }
        }
        return null;
    }

    private static Unit mage(Rig rig, int x) {
        Unit made = rig.world().createUnit(rig.data().unitTypes().types().get("unit-mage"),
                0, x, 16);
        assertNotNull(made, "somewhere to stand a mage at " + x);
        made.setMana(made.type().mana());
        return made;
    }

    @Test
    @DisplayName("a modified press sets the spell, as a command")
    void theToggleTravelsAsACommand() {
        Rig rig = rig();
        Unit mage = mage(rig, 6);
        rig.screen().selectForTest(mage);
        UnitButton spell = spellButton(rig, mage, true);
        Assumptions.assumeTrue(spell != null, "the mage offers no spell that may autocast");

        assertNull(mage.autoCast(), "a caster does not spend its pool unasked");
        rig.screen().press(spell, true);

        assertEquals(1, rig.sent().size(), "one command, not a direct call into the world");
        assertEquals(GameCommand.Kind.AUTOCAST, rig.sent().get(0).kind());
        assertEquals(spell.value(), mage.autoCast());

        // And again turns it off, which is what makes it a toggle.
        rig.screen().press(spell, true);
        assertEquals(2, rig.sent().size());
        assertNull(mage.autoCast());
    }

    @Test
    @DisplayName("an unmodified press still arms the spell rather than setting it")
    void aPlainPressIsUnchanged() {
        Rig rig = rig();
        Unit mage = mage(rig, 6);
        rig.screen().selectForTest(mage);
        UnitButton spell = spellButton(rig, mage, true);
        Assumptions.assumeTrue(spell != null, "the mage offers no spell that may autocast");

        rig.screen().press(spell, false);
        assertNull(mage.autoCast(),
                "pressing a spell without the modifier casts it once, it does not set it");
    }

    @Test
    @DisplayName("a spell that declares no autocast is refused")
    void aSpellWithoutAnAutoCastClauseIsRefused() {
        Rig rig = rig();
        Unit mage = mage(rig, 6);
        rig.screen().selectForTest(mage);
        UnitButton plain = spellButton(rig, mage, false);
        Assumptions.assumeTrue(plain != null,
                "every spell the mage offers declares an autocast clause");

        rig.screen().press(plain, true);
        assertEquals(0, rig.sent().size(), "nothing is sent for a spell that cannot autocast");
        assertNull(mage.autoCast());
    }

    @Test
    @DisplayName("one press brings a mixed group into agreement")
    void aMixedGroupIsTurnedOnRatherThanInverted() {
        Rig rig = rig();
        Unit first = mage(rig, 6);
        Unit second = mage(rig, 8);
        UnitButton spell = spellButton(rig, first, true);
        Assumptions.assumeTrue(spell != null, "the mage offers no spell that may autocast");

        // One already set, one not: upstream turns the second on rather than
        // turning the first off.
        assertTrue(rig.world().setAutoCast(first, spell.value()));
        for (Unit unit : rig.world().unitsSnapshot()) {
            unit.setSelected(unit == first || unit == second);
        }
        rig.screen().selectForTest(first);
        second.setSelected(true);

        rig.screen().press(spell, true);
        assertEquals(spell.value(), first.autoCast());
        assertEquals(spell.value(), second.autoCast());
        assertEquals(1, rig.sent().size(),
                "only the unit that disagreed is told anything");

        // Now that both agree, a second press turns both off.
        rig.screen().press(spell, true);
        assertNull(first.autoCast());
        assertNull(second.autoCast());
        assertEquals(3, rig.sent().size());
    }

    @Test
    @DisplayName("the panel marks what is set and only what is set")
    void theBorderFollowsTheSetting() {
        Rig rig = rig();
        Unit mage = mage(rig, 6);
        rig.screen().selectForTest(mage);
        UnitButton spell = spellButton(rig, mage, true);
        Assumptions.assumeTrue(spell != null, "the mage offers no spell that may autocast");

        assertFalse(rig.panel().isAutoCasting(spell));
        rig.screen().press(spell, true);
        assertTrue(rig.panel().isAutoCasting(spell));

        // A second caster that is not set takes the mark off the pair: the
        // border says what a press would undo.
        Unit other = mage(rig, 8);
        other.setSelected(true);
        assertFalse(rig.panel().isAutoCasting(spell));
    }
}
