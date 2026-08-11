package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.imageio.ImageIO;
import net.chonkbase.chonkcraft.data.graphic.IndexedImage;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.MapRenderer;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A mage's mana and a barracks' progress are visible without clicking them.
 *
 * <p>{@code ui.legacy-declaration:42-52} declares six decorations at one position under every
 * unit -- Mana, Transport, Research, Training, UpgradeTo and CarryResource --
 * and this implementation drew none of them. The state was not missing: the info panel
 * showed a selected caster's mana correctly, which is exactly why nobody
 * noticed the field did not. What a player lost was the thing the decoration
 * exists for: whether it is safe to close on that mage, and whether that
 * barracks is making something, answered without selecting either.
 *
 * <p>Rendered rather than reasoned about. Each check paints the real
 * {@link GameScreen} and looks for a run of the bar's own colour on the row
 * {@code Offset = {0, -1}} of {@code OffsetPercent = {50, 100}} puts it on,
 * and each is paired with a frame of the same unit in the state that draws
 * nothing -- a caster at full mana, an idle barracks -- so that finding the
 * colour means the bar and not the grass.
 */
class MapDecorationRenderingTest {

    private static final String MAP = "campaigns/human/level02h";
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    private static final int TILE = 32;

    /** {@code GameScreen.MANA_BAR} and {@code PROGRESS_BAR}, as painted. */
    private static final int MANA = 0xFF2020FF;

    private static final int PROGRESS = 0xFF00C000;

    private static GameData data() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    private record Scene(GameScreen screen, World world, GameData data) {}

    private static Scene scene() {
        GameData data = data();
        PudMap pud = data.campaignMap(MAP);
        Assumptions.assumeTrue(pud != null, "no campaign map available");
        GameData.LoadedTileset tileset = data.loadTileset(pud.tileset());
        World world = new World(GameMap.from(pud, tileset.tileset()), Player.from(pud));
        world.setUnitTypes(data.unitTypes().types());
        world.setUpgrades(data.upgrades().upgrades());
        world.setMissileTypes(data.missiles().types());
        world.fog().revealAll(0);

        IndexedImage rendered = new MapRenderer(tileset.tileset(), tileset.sheet())
                .render(world.map().width(), world.map().height(), world.map().tileCodes());
        BufferedImage terrain = rendered.toIndexedBufferedImage(tileset.palette());
        String tilesetName = pud.tileset() == PudMap.Tileset.FOREST
                ? "summer"
                : pud.tileset().name().toLowerCase(java.util.Locale.ROOT);

        GameScreen screen = new GameScreen(world, data, terrain, tileset.palette(),
                tilesetName, 0, WIDTH, HEIGHT, null, null, null, null, null,
                java.util.List.of(), "human");
        screen.setSize(WIDTH, HEIGHT);
        screen.setLayout((net.chonkbase.chonkcraft.engine.ui.UiLayout.Layout) null);
        screen.setGameScale(1);
        return new Scene(screen, world, data);
    }

    private static Unit place(Scene scene, String ident) {
        UnitType type = scene.data().unitTypes().types().get(ident);
        assertNotNull(type, "the shipped data has " + ident);
        for (int x = 2; x < WIDTH / TILE - 6; x++) {
            for (int y = 2; y < HEIGHT / TILE - 6; y++) {
                Unit made = scene.world().createUnit(type, 0, x, y);
                if (made != null) {
                    return made;
                }
            }
        }
        return null;
    }

    private static BufferedImage paint(GameScreen screen, String name) {
        BufferedImage frame = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        var g = frame.createGraphics();
        screen.paint(g);
        g.dispose();
        try {
            Path out = Paths.get("target");
            Files.createDirectories(out);
            ImageIO.write(frame, "png", out.resolve(name).toFile());
        } catch (IOException unwritable) {
            // A frame that could not be saved is not a failing assertion.
        }
        return frame;
    }

    /**
     * The topmost row in a band holding eight of this colour in a row, or -1.
     *
     * <p>Eight running rather than one, because a single pixel of any colour is
     * something a tileset can produce and a horizontal run of eight is not.
     */
    private static int barTop(BufferedImage frame, int left, int width,
            int top, int bottom, int colour) {
        for (int y = Math.max(0, top); y < Math.min(frame.getHeight(), bottom); y++) {
            int run = 0;
            for (int x = Math.max(0, left); x < Math.min(frame.getWidth(), left + width); x++) {
                run = frame.getRGB(x, y) == colour ? run + 1 : 0;
                if (run >= 8) {
                    return y;
                }
            }
        }
        return -1;
    }

    /** Where the second bar's top row belongs: {@code Offset = {0, -1}}. */
    private static int expectedRow(Unit unit) {
        return unit.pixelY() + Math.max(1, unit.type().tileHeight()) * TILE - 1;
    }

    private static int[] band(Unit unit) {
        return new int[] {unit.pixelX(),
                Math.max(1, unit.type().tileWidth()) * TILE,
                unit.pixelY() - 48,
                unit.pixelY() + Math.max(1, unit.type().tileHeight()) * TILE + 48};
    }

    @Test
    @DisplayName("a mage half out of mana carries a bar, and a full one does not")
    void manaIsDrawnOnTheField() {
        Scene scene = scene();
        Unit mage = place(scene, "unit-mage");
        Assumptions.assumeTrue(mage != null, "nowhere on this map to stand a mage");
        UnitType type = mage.type();
        Assumptions.assumeTrue(type.mana() > 0 && mage.isCaster(),
                "the shipped mage declares no mana, so there is nothing to draw");

        // Full first. ShowWhenMax defaults false, so this
        // frame must hold none of the colour -- which is what makes the second
        // frame's run a bar rather than a shade of the ground.
        mage.setMana(type.mana());
        BufferedImage full = paint(scene.screen(), "map-decorations-mana-full.png");
        int[] box = band(mage);
        assertEquals(-1, barTop(full, box[0], box[1], box[2], box[3], MANA),
                "a caster at full mana drew a mana bar, which ShowWhenMax says it should"
                        + " not -- or the terrain contains the colour this test looks for");

        mage.setMana(type.mana() / 2);
        BufferedImage half = paint(scene.screen(), "map-decorations-mana-half.png");
        int found = barTop(half, box[0], box[1], box[2], box[3], MANA);
        assertTrue(found >= 0,
                "a mage at half mana drew no mana bar on the field: ui.legacy-declaration declares"
                        + " DefineDecorations{Index = \"Mana\"} and nothing read it");
        assertEquals(expectedRow(mage), found,
                "the mana bar is at row " + found + " rather than " + expectedRow(mage)
                        + ", which is Offset {0, -1} of OffsetPercent {50, 100}");
    }

    @Test
    @DisplayName("a barracks making a footman says so without being clicked")
    void aBuildingAtWorkShowsItsProgress() {
        Scene scene = scene();
        Unit barracks = place(scene, "unit-human-barracks");
        Assumptions.assumeTrue(barracks != null, "nowhere to put a barracks");
        for (UnitType.Resource resource : UnitType.Resource.values()) {
            scene.world().player(0).set(resource, 100000);
        }
        // Farms, or World.orderTrain refuses for want of supply before it ever
        // charges anybody and there is no job to draw.
        for (int extra = 0; extra < 4; extra++) {
            place(scene, "unit-farm");
        }
        scene.world().recalculateSupply();

        BufferedImage idle = paint(scene.screen(), "map-decorations-idle.png");
        int[] box = band(barracks);
        assertEquals(-1, barTop(idle, box[0], box[1], box[2], box[3], PROGRESS),
                "an idle barracks drew a progress bar, so the frame below proves nothing");

        UnitType footman = scene.data().unitTypes().types().get("unit-footman");
        Assumptions.assumeTrue(scene.world().orderTrain(barracks, footman),
                "the barracks would not start a footman, so there is no job to show");
        // Part way through, or the bar is nought pixels wide and invisible.
        for (int cycle = 0; cycle < 200; cycle++) {
            scene.world().tick();
        }
        Assumptions.assumeTrue(barracks.progressFraction() > 0.05,
                "the job has not got far enough to draw a bar wider than the run"
                        + " this test looks for");

        BufferedImage working = paint(scene.screen(), "map-decorations-training.png");
        int found = barTop(working, box[0], box[1], box[2], box[3], PROGRESS);
        assertTrue(found >= 0,
                "a barracks part way through a footman drew nothing on the field:"
                        + " ui.legacy-declaration declares DefineDecorations{Index = \"Training\"}");
        assertEquals(expectedRow(barracks), found,
                "the progress bar is at row " + found + " rather than "
                        + expectedRow(barracks));
    }

    /** The longest run of {@code colour} on one row, so a bar has a width. */
    private static int runOn(BufferedImage frame, int y, int left, int width, int colour) {
        if (y < 0 || y >= frame.getHeight()) {
            return 0;
        }
        int best = 0;
        int run = 0;
        for (int x = Math.max(0, left); x < Math.min(frame.getWidth(), left + width); x++) {
            run = frame.getRGB(x, y) == colour ? run + 1 : 0;
            best = Math.max(best, run);
        }
        return best;
    }

    /**
     * A gold mine says how much is in it, all the time, and the bar drains.
     *
     * <p>{@code GiveResource} is the one decoration declaring
     * {@code ShowWhenMax = true} and {@code HideNeutral = false}
     * ({@code ui.legacy-declaration:50}), so upstream shows it on every mine at every
     * moment. This implementation had no state for it until the save lane gave
     * deposits {@code resourcesHeld}, and then drew nothing with it: a mine
     * about to run dry looked exactly like a fresh one.
     */
    @Test
    @DisplayName("a gold mine wears its bar when full, and the bar drains with the gold")
    void aMineShowsWhatIsLeft() {
        Scene scene = scene();
        Unit mine = place(scene, "unit-gold-mine");
        Assumptions.assumeTrue(mine != null, "nowhere on this map to put a mine");
        Assumptions.assumeTrue(mine.resourcesHeld() > 0,
                "the placed mine holds nothing, so there is no amount to show");

        BufferedImage full = paint(scene.screen(), "map-decorations-mine-full.png");
        int row = expectedRow(mine);
        int[] box = band(mine);
        int fullRun = runOn(full, row, box[0], box[1], MANA);
        assertTrue(fullRun >= 8,
                "a full mine drew no bar: GiveResource declares ShowWhenMax = true, which"
                        + " is exactly what separates it from the mana bar beside it");

        mine.setResourcesHeld(mine.resourcesHeld() / 2);
        BufferedImage half = paint(scene.screen(), "map-decorations-mine-half.png");
        int halfRun = runOn(half, row, box[0], box[1], MANA);
        assertTrue(halfRun >= 8, "the half-drained mine lost its bar entirely");
        assertTrue(halfRun < fullRun,
                "the bar does not drain: " + halfRun + " pixels against " + fullRun
                        + " when half the gold is gone, so the figure it shows is not"
                        + " the amount left");
    }

    /**
     * An enchanted unit wears the badge of its spell, in that spell's slot.
     *
     * <p>{@code ui.legacy-declaration:53-65}: five {@code static-sprite} decorations off one
     * sheet, Bloodlust at {@code Offset = {0, 0}}, Invisible at
     * {@code {32, 0}}, all declaring {@code ShowOpponent}. The timers have
     * been live since the spell lane -- the spells cost mana and changed the
     * fight -- and the badges were never drawn, so whether a grunt was
     * bloodlusted was knowable only from the damage numbers.
     *
     * <p>Region comparison rather than colour search, because a badge is a
     * picture: the 16 by 16 cell at the slot must change when the spell lands
     * and the other slots must not, which also pins each badge to its own
     * slot.
     */
    @Test
    @DisplayName("a bloodlusted grunt wears the badge, in bloodlust's slot")
    void anEnchantedUnitWearsItsBadge() {
        Scene scene = scene();
        Unit grunt = place(scene, "unit-grunt");
        Assumptions.assumeTrue(grunt != null, "nowhere on this map to stand a grunt");

        BufferedImage plain = paint(scene.screen(), "map-decorations-no-badge.png");
        grunt.setBuff(Unit.Buff.BLOODLUST, 500);
        BufferedImage lusted = paint(scene.screen(), "map-decorations-bloodlust.png");

        assertTrue(regionDiffers(plain, lusted, grunt.pixelX() + 1, grunt.pixelY() + 1),
                "bloodlust landed and the frame did not change at Offset {0, 0}: the badge"
                        + " ui.legacy-declaration puts there is not drawn");
        assertTrue(!regionDiffers(plain, lusted, grunt.pixelX() + 33, grunt.pixelY() + 1),
                "bloodlust drew into Invisible's slot at Offset {32, 0}, so the badges"
                        + " are not where the script puts them");

        grunt.setBuff(Unit.Buff.INVISIBLE, 500);
        BufferedImage cloaked = paint(scene.screen(), "map-decorations-invisible.png");
        assertTrue(regionDiffers(lusted, cloaked, grunt.pixelX() + 33, grunt.pixelY() + 1),
                "invisibility landed and Offset {32, 0} did not change: the third slot"
                        + " is not drawn");
    }

    /** Whether any pixel of the 16 by 16 cell at {@code x, y} differs. */
    private static boolean regionDiffers(BufferedImage before, BufferedImage after,
            int x, int y) {
        for (int dy = 0; dy < 16; dy++) {
            for (int dx = 0; dx < 16; dx++) {
                int px = x + dx;
                int py = y + dy;
                if (px < 0 || py < 0 || px >= before.getWidth() || py >= before.getHeight()) {
                    continue;
                }
                if (before.getRGB(px, py) != after.getRGB(px, py)) {
                    return true;
                }
            }
        }
        return false;
    }
}
