package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.graphic.IndexedImage;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.MapRenderer;
import net.chonkbase.chonkcraft.engine.unit.SpriteFrame;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How a sprite frame is chosen and cut out, checked against the shipped data.
 *
 * <p>Written while chasing a report of an orc that drew "as a green blob" on a
 * wasteland map. The two ways a renderer produces that picture are cutting a
 * frame at the wrong stride and remapping palette entries it has no business
 * touching, and neither is visible in a screenshot of a single unit -- you can
 * only tell by asking the data whether the arithmetic can go wrong at all.
 *
 * <p>So these are the invariants that make {@code GameScreen.drawUnit}'s
 * arithmetic safe, stated so a machine can check them against every shipped
 * type on every tileset rather than against the handful anybody thinks to look
 * at.
 */
class SpriteFrameGeometryTest {

    private static final String[] TILESETS = {"summer", "winter", "wasteland", "swamp"};

    private static GameData data() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    /**
     * A sheet has to divide exactly into frames.
     *
     * <p>{@code drawUnit} works out the columns as {@code sheetWidth /
     * frameWidth} and then reads {@code frameWidth} pixels from
     * {@code (index % columns) * frameWidth}. If the division leaves a
     * remainder the last column runs off the right-hand edge, and the cut-out
     * is padded with transparency instead -- a sprite with its right-hand side
     * missing, which is exactly what "drawn only partly" looks like.
     */
    @Test
    @DisplayName("Every sheet divides exactly into its declared frames")
    void sheetsDivideExactly() {
        GameData data = data();
        List<String> ragged = new ArrayList<>();
        for (var entry : data.unitTypes().types().entrySet()) {
            UnitType type = entry.getValue();
            if (!type.hasGraphics()) {
                continue;
            }
            for (String tileset : TILESETS) {
                IndexedImage sheet = data.sprite(type.imageFileFor(tileset));
                if (sheet == null) {
                    continue;
                }
                int frameWidth = Math.max(1, type.imageWidth());
                int frameHeight = Math.max(1, type.imageHeight());
                if (sheet.width() % frameWidth != 0 || sheet.height() % frameHeight != 0) {
                    ragged.add(entry.getKey() + " [" + tileset + "] "
                            + type.imageFileFor(tileset) + " is "
                            + sheet.width() + "x" + sheet.height()
                            + " for a " + frameWidth + "x" + frameHeight + " frame");
                }
            }
        }
        assertTrue(ragged.isEmpty(), "sheets that do not divide into whole frames: " + ragged);
    }

    /**
     * No animation may name a frame the sheet does not hold.
     *
     * <p>An animation's {@code frame} instruction names the first index of a
     * row, and the heading picks one of the stored facings within it, so the
     * largest index a type can ask for is its largest declared frame plus its
     * stored facings less one. {@code drawUnit} takes that modulo the frame
     * count, which stops it reading off the end of the sheet -- but the wrap
     * lands in an unrelated row, which draws a different pose, or a slice of a
     * corpse where a live unit should be.
     *
     * <p>Buildings are excluded and the reason is a bug of its own, recorded
     * in the report that came with this test: the implementation gives every type eight
     * facings, where upstream defaults a building to one, so a building's
     * index is its animation's frame plus four rather than the frame itself.
     */
    @Test
    @DisplayName("No mobile type's animation names a frame outside its sheet")
    void animationsStayInsideTheSheet() {
        GameData data = data();
        List<String> overflowing = new ArrayList<>();
        for (var entry : data.unitTypes().types().entrySet()) {
            UnitType type = entry.getValue();
            if (!type.hasGraphics() || type.building() || isStartLocation(entry.getKey())) {
                continue;
            }
            for (String tileset : TILESETS) {
                IndexedImage sheet = data.sprite(type.imageFileFor(tileset));
                if (sheet == null) {
                    continue;
                }
                int columns = Math.max(1, sheet.width() / Math.max(1, type.imageWidth()));
                int rows = Math.max(1, sheet.height() / Math.max(1, type.imageHeight()));
                int largest = largestFrame(type) + SpriteFrame.storedFacings(type.numDirections()) - 1;
                if (largest >= columns * rows) {
                    overflowing.add(entry.getKey() + " [" + tileset + "] asks for index "
                            + largest + " of " + (columns * rows));
                }
            }
        }
        assertTrue(overflowing.isEmpty(),
                "types whose animations run off the end of their sheet: " + overflowing);
    }

    /**
     * The two start-position markers, which never reach the screen.
     *
     * <p>They are a map format's way of saying where a player begins, and
     * {@code GameData.populate} drops them rather than making units of them.
     * Their sheet holds one frame and the animation they share names two, so
     * they would fail the rule above without anything being wrong.
     */
    private static boolean isStartLocation(String ident) {
        return ident.equals("unit-human-start-location") || ident.equals("unit-orc-start-location");
    }

    private static int largestFrame(UnitType type) {
        AnimationSet set = type.animationSet();
        if (set == null) {
            return 0;
        }
        int largest = 0;
        for (AnimationSet.State state : set.states()) {
            Animation animation = set.get(state);
            if (animation == null) {
                continue;
            }
            for (Animation.Instruction instruction : animation.instructions()) {
                if (instruction.kind() == Animation.Kind.FRAME) {
                    largest = Math.max(largest, instruction.value());
                }
            }
        }
        return largest;
    }

    /**
     * The sheet a worker swaps to while carrying has to be laid out like the
     * one it swaps from.
     *
     * <p>{@code drawUnit} takes the sheet from
     * {@code UnitType.imageFileFor(tileset, resource, loaded)} and the frame
     * size from the type. If a resource's own sheet is a different shape the
     * frame size no longer describes it, and every index cuts the wrong
     * rectangle -- the classic wrong-stride picture, where a sprite comes out
     * sheared into slivers.
     */
    @Test
    @DisplayName("A carrying sheet is laid out like the type's own")
    void carryingSheetsMatch() {
        GameData data = data();
        List<String> mismatched = new ArrayList<>();
        for (var entry : data.unitTypes().types().entrySet()) {
            UnitType type = entry.getValue();
            if (!type.hasGraphics() || type.gathering().isEmpty()) {
                continue;
            }
            for (String tileset : TILESETS) {
                IndexedImage own = data.sprite(type.imageFileFor(tileset));
                if (own == null) {
                    continue;
                }
                for (var resource : type.gathering().keySet()) {
                    for (boolean loaded : new boolean[] {false, true}) {
                        String path = type.imageFileFor(tileset, resource, loaded);
                        IndexedImage other = data.sprite(path);
                        if (other == null) {
                            mismatched.add(entry.getKey() + " [" + tileset + "] has no sheet "
                                    + path);
                        } else if (other.width() != own.width()
                                || other.height() != own.height()) {
                            mismatched.add(entry.getKey() + " [" + tileset + "] " + path
                                    + " is " + other.width() + "x" + other.height()
                                    + " but its own sheet is "
                                    + own.width() + "x" + own.height());
                        }
                    }
                }
            }
        }
        assertTrue(mismatched.isEmpty(), "carrying sheets that do not match: " + mismatched);
    }

    /**
     * The team colour occupies four entries and the ground uses none of them.
     *
     * <p>{@code GameScreen.spriteImage} swaps every pixel whose palette index
     * falls in the declared band for the owning player's ramp. The band is
     * {@code DefinePlayerColorIndex(208, 4)}, and the check that the swap
     * cannot reach anything it should not is twofold: the declaration itself
     * has to be those four entries, and no tile of any tileset may use them --
     * because a tileset that did would have its rocks repainted in somebody's
     * livery the moment the same palette entry appeared in a sprite.
     */
    @Test
    @DisplayName("The team colour band is four entries the terrain never uses")
    void theTeamColourBandIsNarrowAndUnusedByTheGround() {
        GameData data = data();
        var declared = data.playerColours();
        assertTrue(declared.isDefined(), "no player colours declared");
        assertEquals(208, declared.firstIndex(), "the player colour band has moved");
        for (var ramp : declared.ramps()) {
            assertEquals(4, ramp.colours().length,
                    "a player ramp of " + ramp.colours().length
                            + " entries would reach past the declared band");
        }
        int first = declared.firstIndex();
        int last = first + 4;
        for (PudMap.Tileset which : PudMap.Tileset.values()) {
            IndexedImage sheet;
            try {
                sheet = data.loadTileset(which).sheet();
            } catch (RuntimeException unavailable) {
                continue;
            }
            int inBand = 0;
            for (int y = 0; y < sheet.height(); y++) {
                for (int x = 0; x < sheet.width(); x++) {
                    int index = sheet.get(x, y);
                    if (index >= first && index < last) {
                        inBand++;
                    }
                }
            }
            assertEquals(0, inBand,
                    which + " uses " + inBand + " pixels of the team colour band");
        }
    }

    /**
     * A drawn unit takes its owner's colours in the band and the palette's
     * everywhere else.
     *
     * <p>This is the end-to-end half: the screen paints a grunt owned by the
     * green player onto a real map, and the pixels of its sprite are compared
     * against what the sheet says they should be. Every band pixel must be one
     * of that player's four colours and every other pixel must be the
     * palette's own, which fails if the band is read at the wrong offset, if a
     * ramp is longer than the band, or if the cached image was built for
     * another player.
     */
    @Test
    @DisplayName("The band takes the owner's ramp and nothing else moves")
    void aDrawnUnitIsRecolouredOnlyInTheBand() {
        GameData data = data();
        PudMap pud = data.campaignMap("campaigns/human/level12h");
        Assumptions.assumeTrue(pud != null, "no wasteland campaign map available");
        GameData.LoadedTileset tileset = data.loadTileset(pud.tileset());
        World world = new World(GameMap.from(pud, tileset.tileset()), Player.from(pud));
        world.setUnitTypes(data.unitTypes().types());
        world.setUpgrades(data.upgrades().upgrades());
        world.setMissileTypes(data.missiles().types());

        UnitType grunt = data.unitTypes().types().get("unit-grunt");
        assertNotNull(grunt);
        // Owned by the local player, so that what is being tested is the
        // colour swap and not the fog.
        Unit unit = null;
        GameMap map = world.map();
        for (int y = 8; y < map.height() - 8 && unit == null; y++) {
            for (int x = 8; x < map.width() - 8; x++) {
                if (map.field(x, y).isLandPassable()) {
                    unit = world.createUnit(grunt, 2, x, y);
                    if (unit != null) {
                        break;
                    }
                }
            }
        }
        Assumptions.assumeTrue(unit != null, "nowhere to stand a grunt");

        IndexedImage rendered = new MapRenderer(tileset.tileset(), tileset.sheet())
                .render(map.width(), map.height(), map.tileCodes());
        BufferedImage terrain = rendered.toIndexedBufferedImage(tileset.palette());
        GameScreen screen = new GameScreen(world, data, terrain, tileset.palette(),
                "wasteland", 2, 640, 480, null, null, null, null, null,
                tileset.cyclingRanges(), "human");
        screen.setSize(640, 480);
        screen.setLayout((net.chonkbase.chonkcraft.engine.ui.UiLayout.Layout) null);
        screen.setGameScale(1);
        world.tick();
        screen.centreOn(unit.tileX(), unit.tileY());

        BufferedImage frame = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = frame.createGraphics();
        screen.paint(g);
        g.dispose();

        int cameraX = Math.max(0, Math.min(unit.tileX() * 32 - 320, terrain.getWidth() - 640));
        int cameraY = Math.max(0, Math.min(unit.tileY() * 32 - 240, terrain.getHeight() - 480));
        IndexedImage sheet = data.sprite(grunt.imageFileFor("wasteland"));
        assertNotNull(sheet);
        int frameWidth = grunt.imageWidth();
        int frameHeight = grunt.imageHeight();
        int columns = sheet.width() / frameWidth;
        SpriteFrame.Resolved resolved = unit.spriteFrame();
        assertTrue(!resolved.mirrored(), "the pose this test reads assumes an unmirrored frame");
        int sourceX = (resolved.index() % columns) * frameWidth;
        int sourceY = (resolved.index() / columns) * frameHeight;
        int drawX = unit.pixelX() + (32 - frameWidth) / 2 - cameraX;
        int drawY = unit.pixelY() + (32 - frameHeight) / 2 - cameraY;

        var declared = data.playerColours();
        int first = declared.firstIndex();
        int[] ramp = declared.ramps().get(2).colours();
        int band = 0;
        int plain = 0;
        for (int y = 0; y < frameHeight; y++) {
            for (int x = 0; x < frameWidth; x++) {
                int index = sheet.get(sourceX + x, sourceY + y);
                if (index == net.chonkbase.chonkcraft.data.graphic.Palette.TRANSPARENT_INDEX) {
                    continue;
                }
                int painted = frame.getRGB(drawX + x, drawY + y) & 0xFFFFFF;
                int shade = index - first;
                if (shade >= 0 && shade < ramp.length) {
                    band++;
                    assertEquals(ramp[shade] & 0xFFFFFF, painted,
                            "band index " + index + " at " + x + "," + y
                                    + " was not painted in the owner's ramp");
                } else {
                    plain++;
                    assertEquals(tileset.palette().rgb(index), painted,
                            "index " + index + " at " + x + "," + y
                                    + " was repainted when it should not have been");
                }
            }
        }
        assertTrue(band > 0, "the frame read carries no team colour at all");
        assertTrue(plain > band, "the frame read is almost all team colour, which is not a grunt");
    }
}
