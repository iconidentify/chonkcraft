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
import net.chonkbase.chonkcraft.data.source.AssetSource;
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
 * BNE keeps changing game state out of the map's unit-footprint decorations.
 *
 * <p>An old compatibility UI declaration led the desktop renderer to invent
 * coloured field gauges for mana, building work, transport occupancy, worker
 * loads and deposits. BNE presents that information in the selected-unit
 * panel instead. These rendered checks keep those artificial gauges off the
 * map while retaining BNE's actual spell-status badges.
 */
class MapDecorationRenderingTest {

    private static final String MAP = "campaigns/human/level02h";
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    private static final int TILE = 32;

    /** Colours of the removed artificial field gauges. */
    private static final int MANA = 0xFF2020FF;

    private static final int PROGRESS = 0xFF00C000;

    private static final int CARRY = 0xFFC0C000;

    private static GameData data() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No Warcraft II assets configured. Set CHONKCRAFT_ASSET_PACK or"
                        + " -Dwc2.install.dir=/path/to/game.");
        return new GameData(assets);
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

    private static int[] band(Unit unit) {
        return new int[] {unit.pixelX(),
                Math.max(1, unit.type().tileWidth()) * TILE,
                unit.pixelY() - 48,
                unit.pixelY() + Math.max(1, unit.type().tileHeight()) * TILE + 48};
    }

    @Test
    @DisplayName("mana stays in the selected-unit panel instead of under a mage")
    void manaDoesNotDrawAFieldGauge() {
        Scene scene = scene();
        Unit mage = place(scene, "unit-mage");
        Assumptions.assumeTrue(mage != null, "nowhere on this map to stand a mage");
        UnitType type = mage.type();
        Assumptions.assumeTrue(type.mana() > 0 && mage.isCaster(),
                "the shipped mage declares no mana, so there is nothing to draw");

        mage.setMana(type.mana() / 2);
        BufferedImage frame = paint(scene.screen(), "map-decorations-no-mana-gauge.png");
        int[] box = band(mage);
        assertEquals(-1, barTop(frame, box[0], box[1], box[2], box[3], MANA),
                "a half-mana mage wore a custom blue field gauge");
    }

    @Test
    @DisplayName("building work stays in the selected-unit panel")
    void buildingWorkDoesNotDrawAFieldGauge() {
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

        UnitType footman = scene.data().unitTypes().types().get("unit-footman");
        Assumptions.assumeTrue(scene.world().orderTrain(barracks, footman),
                "the barracks would not start a footman, so there is no job to show");
        for (int cycle = 0; cycle < 200; cycle++) {
            scene.world().tick();
        }
        BufferedImage frame = paint(scene.screen(), "map-decorations-no-training-gauge.png");
        int[] box = band(barracks);
        assertEquals(-1, barTop(frame, box[0], box[1], box[2], box[3], PROGRESS),
                "a working barracks wore a custom green field gauge");
    }

    @Test
    @DisplayName("research, upgrading and transport occupancy stay off the map")
    void otherCompatibilityGaugesStayOffTheMap() {
        Scene scene = scene();
        Unit building = place(scene, "unit-human-barracks");
        Assumptions.assumeTrue(building != null, "nowhere to put a building");
        building.setProgressGoal(100);
        building.setProgress(50);

        building.setResearching("upgrade-sword1");
        assertNoGauge(scene, building, PROGRESS, "research");
        building.setResearching(null);

        UnitType keep = scene.data().unitTypes().types().get("unit-keep");
        Assumptions.assumeTrue(keep != null, "the shipped data has no keep");
        building.setUpgradingTo(keep);
        assertNoGauge(scene, building, PROGRESS, "upgrade");
        building.setUpgradingTo(null);

        Unit transport = place(scene, "unit-human-transport");
        Unit passenger = place(scene, "unit-footman");
        Assumptions.assumeTrue(transport != null && passenger != null,
                "nowhere on this map to create a loaded transport");
        transport.cargo().add(passenger);
        assertNoGauge(scene, transport, PROGRESS, "transport-occupancy");
    }

    private static void assertNoGauge(Scene scene, Unit unit, int colour, String state) {
        BufferedImage frame = paint(scene.screen(), "map-decorations-no-" + state + "-gauge.png");
        int[] box = band(unit);
        assertEquals(-1, barTop(frame, box[0], box[1], box[2], box[3], colour),
                state + " drew an artificial field gauge");
    }

    /**
     * Resource state belongs in the selected-unit panel, not on the map.
     */
    @Test
    @DisplayName("deposits and carried resources do not wear field gauges")
    void resourcesDoNotDrawFieldGauges() {
        Scene scene = scene();
        Unit mine = place(scene, "unit-gold-mine");
        Assumptions.assumeTrue(mine != null, "nowhere on this map to put a mine");
        Assumptions.assumeTrue(mine.resourcesHeld() > 0,
                "the placed mine holds nothing, so there is no amount to show");

        mine.setResourcesHeld(mine.resourcesHeld() / 2);
        BufferedImage mineFrame = paint(scene.screen(), "map-decorations-no-mine-gauge.png");
        int[] box = band(mine);
        assertEquals(-1, barTop(mineFrame, box[0], box[1], box[2], box[3], MANA),
                "a gold mine wore a custom always-visible remaining-resource gauge");

        Unit peasant = place(scene, "unit-peasant");
        Assumptions.assumeTrue(peasant != null, "nowhere on this map to stand a peasant");
        var gather = peasant.type().gathering().get(UnitType.Resource.WOOD);
        Assumptions.assumeTrue(gather != null && gather.capacity() > 0,
                "the shipped peasant cannot carry wood");
        peasant.setCarrying(UnitType.Resource.WOOD);
        peasant.setCarried(Math.max(1, gather.capacity() / 2));
        BufferedImage workerFrame = paint(scene.screen(), "map-decorations-no-worker-gauge.png");
        box = band(peasant);
        assertEquals(-1, barTop(workerFrame, box[0], box[1], box[2], box[3], CARRY),
                "a wood-carrying peasant wore a custom harvest-progress-looking gauge");
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
