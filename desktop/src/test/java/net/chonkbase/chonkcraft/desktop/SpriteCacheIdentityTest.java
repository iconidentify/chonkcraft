package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import net.chonkbase.chonkcraft.data.graphic.IndexedImage;
import net.chonkbase.chonkcraft.data.graphic.Palette;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Two different sheets never share a cache entry.
 *
 * <p>The frame cache used to key on
 * {@code System.identityHashCode(sheet) + ":" + ...}. An identity hash is not
 * an identity: it is thirty-one bits, so two live sheets can produce the same
 * one, and when they did the second sheet would be handed the first sheet's
 * picture -- permanently, for the rest of the session, with nothing to see in
 * the frame except a unit drawn as some other unit.
 *
 * <p>It was recorded as "unlikely to be demonstrable". It is demonstrable. A
 * HotSpot identity hash behaves like a draw from a thirty-one bit space, so a
 * couple of hundred thousand objects turn up colliding pairs by the birthday
 * bound in a fraction of a second -- which is what this does. It finds a real
 * pair, gives the two sheets visibly different contents, and asks the real
 * cache for both. On the old key the second answer is the first sheet's
 * picture.
 *
 * <p>A game does not hold two hundred thousand sheets, so the failure needed a
 * long session and bad luck rather than a bug report. That is precisely why it
 * is worth closing off by construction instead of by probability.
 */
class SpriteCacheIdentityTest {

    private static final String MAP = "campaigns/human/level02h";
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;

    /**
     * How many one-pixel sheets to allocate looking for a colliding pair.
     *
     * <p>Two hundred thousand draws from a thirty-one bit space expect about
     * nine collisions, and measured on this JVM it produces nine. Ten times
     * that many would be wasteful; ten times fewer would be flaky.
     */
    private static final int SHEETS = 200_000;

    private record Pair(IndexedImage first, IndexedImage second) {}

    private static GameData data() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    /** A screen with a real palette on it, which is all the cut path needs. */
    private static GameScreen screen(GameData data) {
        PudMap pud = data.campaignMap(MAP);
        Assumptions.assumeTrue(pud != null, "no campaign map available");
        var tileset = data.loadTileset(pud.tileset());
        World world = new World(GameMap.from(pud, tileset.tileset()), Player.from(pud));
        String tilesetName = pud.tileset() == PudMap.Tileset.FOREST
                ? "summer"
                : pud.tileset().name().toLowerCase(java.util.Locale.ROOT);
        BufferedImage terrain = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        GameScreen screen = new GameScreen(world, data, terrain, tileset.palette(),
                tilesetName, 0, WIDTH, HEIGHT, null, null, null, null, null,
                java.util.List.of(), "human");
        screen.setSize(WIDTH, HEIGHT);
        return screen;
    }

    /** Two distinct sheets that a thirty-one bit identity hash cannot tell apart. */
    private static Pair colliding() {
        Map<Integer, IndexedImage> seen = new HashMap<>(SHEETS * 2);
        for (int i = 0; i < SHEETS; i++) {
            IndexedImage sheet = new IndexedImage(1, 1);
            IndexedImage previous = seen.putIfAbsent(System.identityHashCode(sheet), sheet);
            if (previous != null) {
                return new Pair(previous, sheet);
            }
        }
        return null;
    }

    @Test
    @DisplayName("two sheets with the same identity hash draw as themselves, not as each other")
    void theCacheDistinguishesSheetsAnIdentityHashCannot() {
        GameData data = data();
        GameScreen screen = screen(data);
        Palette palette = data.loadTileset(data.campaignMap(MAP).tileset()).palette();
        assertNotNull(palette);

        Pair pair = colliding();
        Assumptions.assumeTrue(pair != null,
                "no identity-hash collision in " + SHEETS + " sheets on this JVM");
        assertTrue(pair.first() != pair.second(), "a sheet did not collide with itself");

        // Two palette entries that look different, so "the wrong picture" is
        // something the assertion below can actually see.
        int a = -1;
        int b = -1;
        for (int index = 1; index < 200 && b < 0; index++) {
            if (a < 0) {
                a = index;
            } else if (palette.argb(index) != palette.argb(a)) {
                b = index;
            }
        }
        Assumptions.assumeTrue(b > 0, "this palette has no two distinguishable entries");

        pair.first().set(0, 0, a);
        pair.second().set(0, 0, b);

        BufferedImage drawnFirst = screen.spriteImage(pair.first(), 0, 0, 1, 1, -1);
        BufferedImage drawnSecond = screen.spriteImage(pair.second(), 0, 0, 1, 1, -1);

        assertNotEquals(drawnFirst.getRGB(0, 0), drawnSecond.getRGB(0, 0),
                "the second sheet was drawn as the first: the cache cannot tell apart two"
                        + " sheets whose identity hashes collide, and both are live");
    }
}
