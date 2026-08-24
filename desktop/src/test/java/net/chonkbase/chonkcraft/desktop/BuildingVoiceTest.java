package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.chonkbase.runtime.audio.AudioMixer;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.MapRenderer;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.ui.UiLayout;
import net.chonkbase.chonkcraft.engine.sound.GameAudio;
import net.chonkbase.chonkcraft.engine.ui.UnitButton;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a building says when it is clicked, and what a building site says
 * without being clicked at all.
 *
 * <p>A player asked for the first of these by name: "I am familiar with an
 * under construction sound when you click on a building that's in progress of
 * being built, in the original game you hear hammer and nails, saws etc. but
 * in our game I'm not hearing that."
 *
 * <p>Upstream branches on what the thing is doing before it decides the noise
 * is a voice at all. {@code UIHandleButtonUp},
 * The game a unit of yours whose action is
 * {@code UnitAction::Built} gets {@code EUnitVoice::Building}, which
 * {@code ChooseUnitVoiceSound} resolves to
 * {@code GameSounds.BuildingConstruction[race]}; a building that is alight
 * gets the named game sound {@code burning}; everything else gets its own
 * selection line. This implementation had the third arm only, so an unfinished farm
 * answered with the finished farm's line and a keep with its roof on fire
 * answered with the keep's.
 *
 * <p>Both files were in the bank the whole time with no caller.
 * {@code misc/building_construction.wav} is bound to
 * {@code building-construction} for both races by
 * {@code scripts/sound.legacy-declaration:857-858}; {@code misc/burning.wav} is asked for by
 * that literal in the C++ and named by no ChonkCraft script at all, which is why a
 * check that read only the retired scripting language would have missed it. The third gap is the
 * hammering a player hears without clicking anything --
 * {@code COrder_Built::Execute},,
 * every 150 cycles, one site in three -- and this implementation made no such noise ever.
 *
 * <p>Every check here renders the mixer and compares the samples that would
 * have gone to the speakers against the same clip played on its own, because
 * "the wrong sound came out" is a fact about what was heard. Asking the screen
 * which name it passed would pass against a name that resolves to nothing.
 * Measured on human mission one before the fix: clicking a farm 200 cycles
 * into its 600-cycle construction rendered 1.86 seconds of audio matching
 * {@code human/buildings/farm.wav}, which is what the finished farm beside it
 * answered with; after, 2.32 seconds matching
 * {@code misc/building_construction.wav}.
 */
class BuildingVoiceTest {

    private static final String MAP = "campaigns/human/level01h";

    private static final int ME = 1;

    private static final int WIDTH = 1280;

    private static final int HEIGHT = 800;

    private static final int TILE = 32;

    /** Long enough to hold the opening of any of the candidate clips. */
    private static final int FRAMES = 4096;

    /**
     * How far apart two renderings have to be to be different sounds.
     *
     * <p>Measured rather than picked. The same clip through the same mixer
     * comes back within 1e-5 of itself -- the mixer's own smoothing differs by
     * a hair depending on what it played before -- and two different clips are
     * apart by about 0.1, which is four orders of magnitude away. Anything in
     * between would be a third thing and should fail.
     */
    private static final double SAME_SOUND = 1e-3;

    private record Scene(GameScreen screen, CommandPanel commands, World world,
            GameData data, UiLayout.Layout layout, GameAudio audio) {}

    private static GameData data() {
        AssetSource source = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(source != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(source);
    }

    private static Scene scene() {
        return scene(true);
    }

    private static Scene scene(boolean revealMap) {
        GameData data = data();
        PudMap pud = data.campaignMap(MAP);
        Assumptions.assumeTrue(pud != null, "no campaign map available");
        var tileset = data.loadTileset(pud.tileset());
        World world = new World(GameMap.from(pud, tileset.tileset()), Player.from(pud));
        world.setUnitTypes(data.unitTypes().types());
        world.setUpgrades(data.upgrades().upgrades());
        world.setSpells(data.spells().spells());
        world.setMissileTypes(data.missiles().types());
        data.populate(world, pud);
        world.setBuilders(data.buildRelation(pud.tileset()));
        if (revealMap) {
            world.fog().revealAll(ME);
        }

        var rendered = new MapRenderer(tileset.tileset(), tileset.sheet())
                .render(world.map().width(), world.map().height(), world.map().tileCodes());
        BufferedImage terrain = rendered.toIndexedBufferedImage(tileset.palette());
        CommandApplier applier = new CommandApplier(world,
                new ArrayList<>(data.unitTypes().types().values()));

        UiLayout.Layout layout = data.uiLayout("human", WIDTH, HEIGHT);
        assertNotNull(layout, "the layout script must be readable for any of this to mean much");
        SidePanel panel = new SidePanel(world, data, ME, "human", "summer", layout);
        CommandPanel commands = new CommandPanel(world, data, data.userInterface("summer"),
                data.upgrades().dependencies(), ME, "summer", "human",
                data.unitTypes().types(), layout);

        // No device is opened. The mixer is exact -- given the same commands it
        // renders the same samples whether a sound card is attached or not --
        // so this drives the real clips through the real buses and then reads
        // what would have gone to the speakers.
        GameAudio audio = new GameAudio(data.sounds());
        audio.startWithoutDevice();

        GameScreen screen = new GameScreen(world, data, terrain, tileset.palette(),
                "summer", ME, WIDTH, HEIGHT, audio, panel, commands, applier,
                CommandSink.local(applier), List.of(), "human");
        screen.setSize(WIDTH, HEIGHT);
        screen.setLayout(layout);
        screen.setGameScale(1);
        return new Scene(screen, commands, world, data, layout, audio);
    }

    @Test
    @DisplayName("a half-built farm answers with hammering, not with the finished farm's line")
    void aBuildingGoingUpAnswersWithTheHammering() {
        Scene scene = scene();
        Unit site = halfBuiltFarm(scene);

        silence(scene);
        click(scene, site.tileX(), site.tileY());
        assertTrue(site.selected(), "the click did not land on the building site");
        float[] heard = render(scene);

        double asHammering = difference(heard, alone(scene, site, "building-construction"));
        double asFarm = difference(heard, alone(scene, site, "selected"));
        assertTrue(asHammering < SAME_SOUND,
                "clicking a farm at " + site.hitPoints() + " of its "
                        + site.type().hitPoints() + " hit points played the finished farm's"
                        + " selection line (" + asFarm + " away from it, " + asHammering
                        + " from the hammering). GameScreen never asked whether the"
                        + " building was still going up; mouse.cpp:2179 asks before it"
                        + " picks a voice at all, and misc/building_construction.wav has"
                        + " been decoded and playable in the bank with no caller");
        assertTrue(asFarm > SAME_SOUND,
                "the two candidate sounds cannot be told apart by this measurement,"
                        + " so it proves nothing: " + asFarm + " against " + asHammering);
    }

    @Test
    @DisplayName("a finished farm still answers with its own line")
    void aFinishedBuildingStillAnswersWithItsOwnLine() {
        // The control, and the one the implementation already had right. A branch that
        // played the hammering for every building would pass the test above
        // and be worse than what was there before.
        Scene scene = scene();
        Unit hall = find(scene, "unit-town-hall");
        assertEquals(Unit.Order.STILL, hall.order(),
                "the fixture's town hall is not finished, so this is not the control");

        silence(scene);
        click(scene, hall.tileX(), hall.tileY());
        float[] heard = render(scene);

        assertTrue(difference(heard, alone(scene, hall, "selected")) < SAME_SOUND,
                "a finished town hall no longer answers with its own selection line");
        assertTrue(difference(heard, alone(scene, hall, "building-construction")) > SAME_SOUND,
                "a finished town hall answers with the hammering");
    }

    @Test
    @DisplayName("a building that is alight answers with the fire")
    void aBurningBuildingAnswersWithTheFire() {
        Scene scene = scene();
        Unit hall = find(scene, "unit-town-hall");
        Unit grunt = enemy(scene);

        // Lit the way the game lights one: hurt to two thirds, which the
        // shipped DefineBurningBuilding table calls a small fire, and then
        // struck. catchFire is the last thing applyDamage does before the
        // answer, and it is what sets the flag.
        hall.setHitPoints(hall.type().hitPoints() * 2 / 3);
        for (int blow = 0; blow < 20 && !hall.isBurning(); blow++) {
            scene.world().hit(grunt, hall);
            hall.setHitPoints(hall.type().hitPoints() * 2 / 3);
        }
        Assumptions.assumeTrue(hall.isBurning(),
                "this installation's burning-building table lit no fire, so there is"
                        + " nothing to hear");
        assertTrue(hall.isAlive(), "the town hall was destroyed rather than set alight");

        silence(scene);
        click(scene, hall.tileX(), hall.tileY());
        float[] heard = render(scene);

        assertTrue(difference(heard, named(scene, "burning")) < SAME_SOUND,
                "a town hall with its roof alight answered with its ordinary selection"
                        + " line. mouse.cpp:2182 tests Burning before it asks whose the"
                        + " building is; misc/burning.wav is 4.41 seconds of decoded audio"
                        + " that no code path in this port could reach");
        assertTrue(difference(heard, alone(scene, hall, "selected")) > SAME_SOUND,
                "the fire and the town hall's own line cannot be told apart by this"
                        + " measurement, so it proves nothing");
    }

    @Test
    @DisplayName("a destroyed town hall audibly plays the BNE building-death group")
    void aDestroyedTownHallIsHeard() {
        Scene scene = scene();
        Unit hall = find(scene, "unit-town-hall");

        silence(scene);
        scene.world().kill(hall);
        scene.screen().playAnnouncements();
        float[] heard = render(scene);

        assertTrue(difference(heard, alone(scene, hall, "dead")) < SAME_SOUND,
                "destroying a visible town hall did not reach its `dead` binding; the"
                        + " authenticated BNE roster maps that event to the three-clip"
                        + " `building destroyed` group");
        assertTrue(peak(heard) > 1e-4f,
                "the building-death route resolved to silence in the shipped BNE pack");
    }

    @Test
    @DisplayName("the owner hears its last town hall die after the hall's sight is removed")
    void aLastTownHallDoesNotTakeItsDeathSoundIntoTheFog() {
        Scene scene = scene(false);
        Unit hall = find(scene, "unit-town-hall");
        assertEquals(ME, hall.player(), "the fixture town hall must belong to the listener");

        // Leave the hall as the only local source of vision. World.kill removes
        // its sight before the desktop drains the queued event; filtering that
        // death through the post-mortem fog loses the sound of the very
        // building whose destruction darkened the map.
        for (Unit unit : scene.world().unitsSnapshot()) {
            if (unit != hall && unit.player() == ME && unit.isAlive()) {
                scene.world().kill(unit);
            }
        }
        scene.world().drainSoundEvents();
        assertTrue(scene.world().fog().isVisible(ME, hall.tileX(), hall.tileY()),
                "the living hall is not contributing its own BNE sight");

        silence(scene);
        scene.world().kill(hall);
        assertTrue(!scene.world().fog().isVisible(ME, hall.tileX(), hall.tileY()),
                "the fixture did not reproduce the post-death visibility boundary");
        scene.screen().playAnnouncements();
        float[] heard = render(scene);

        assertTrue(difference(heard, alone(scene, hall, "dead")) < SAME_SOUND,
                "the local town hall's sight vanished before its queued BNE death voice"
                        + " was played");
    }

    @Test
    @DisplayName("the miner hears an on-screen gold mine collapse across its fog transition")
    void anExhaustedGoldMineKeepsItsCollapseSound() {
        Scene scene = scene(false);
        Unit mine = findAny(scene, "unit-gold-mine");
        assertEquals(World.NEUTRAL_PLAYER, mine.player(),
                "the authenticated gold mine must be neutral, not locally owned");

        // Reproduce the intermittent case: no local unit is left to reveal the
        // mine after the last load transition, but the camera is watching it.
        for (Unit unit : scene.world().unitsSnapshot()) {
            if (unit.player() == ME && unit.isAlive()) {
                scene.world().kill(unit);
            }
        }
        scene.world().drainSoundEvents();
        scene.screen().centreOn(mine.tileX(), mine.tileY());
        assertTrue(!scene.world().fog().isVisible(ME, mine.tileX(), mine.tileY()),
                "the fixture did not reproduce the post-collapse fog boundary");

        silence(scene);
        scene.world().killDepletedResource(mine, ME);
        scene.screen().playAnnouncements();
        float[] heard = render(scene);

        assertTrue(difference(heard, alone(scene, mine, "dead")) < SAME_SOUND,
                "the last-load witness was filtered through post-collapse fog instead of"
                        + " reaching the authenticated mine `dead` binding");
        assertTrue(peak(heard) > 1e-4f,
                "the BNE gold-mine collapse resolved to silence");
    }

    @Test
    @DisplayName("a building site hammers away on its own, without being clicked")
    void aBuildingSiteMakesItsOwnNoise() {
        Scene scene = scene();
        Unit site = freshFarm(scene);
        assertEquals(Unit.Order.UNDER_CONSTRUCTION, site.order(),
                "the fixture must still be going up");

        // Upstream considers a site once every 150 cycles and takes one in
        // three. Stopped the moment the farm is finished, because a finished
        // building announces work-complete and that sound is not this one --
        // counting it would let this pass against a port that never hammers,
        // which is exactly what it did on the first run.
        long before = scene.screen().soundChoicesForTest();
        int looks = 0;
        for (int cycle = 0; cycle < 800; cycle++) {
            scene.world().tick();
            if (site.order() != Unit.Order.UNDER_CONSTRUCTION) {
                break;
            }
            if (scene.world().cycle() % 150 == 0) {
                looks++;
            }
            scene.screen().playAnnouncements();
        }
        assertTrue(looks >= 4,
                "the fixture only reached " + looks + " of the cycles upstream would have"
                        + " considered, so a silent run says nothing. A farm takes about six"
                        + " hundred cycles to go up and four of those are multiples of a"
                        + " hundred and fifty; at one site in three, two looks can fall"
                        + " silent by luck and prove nothing either way");
        assertTrue(scene.screen().soundChoicesForTest() > before,
                "a farm went up over " + looks + " five-second intervals and the site never"
                        + " made a sound. action_built.cpp:276-279 is the hammering a player"
                        + " hears without clicking anything, and this port had no such call"
                        + " site at all");
    }

    @Test
    @DisplayName("the hammering does not touch the number two machines share")
    void theLocalDrawStaysLocal() {
        // Upstream is emphatic about this and the reason is lockstep: "IMPORTANT:
        // this is local randomization, do not use the SyncRand function",
        // The condition it guards asks whether the site
        // belongs to the player sitting here, so a draw from the shared stream
        // would happen on one machine and not the other and put the two games
        // on different numbers from that cycle on -- a desync, over a hammer.
        Scene scene = scene();
        Unit site = freshFarm(scene);
        assertEquals(Unit.Order.UNDER_CONSTRUCTION, site.order(),
                "the fixture must still be going up");

        long spoken = scene.screen().soundChoicesForTest();
        for (int cycle = 0; cycle < 800; cycle++) {
            scene.world().tick();
            if (site.order() != Unit.Order.UNDER_CONSTRUCTION) {
                break;
            }
            if (scene.world().cycle() % 150 == 0) {
                int before = scene.world().randomSeed();
                scene.screen().playAnnouncements();
                assertEquals(before, scene.world().randomSeed(),
                        "the interface drew from World.syncRand on the cycle the hammering"
                                + " is decided, which is the one draw upstream forbids");
            }
        }
        assertTrue(scene.screen().soundChoicesForTest() > spoken,
                "nothing was ever played, so the check above never had anything to catch");
    }

    // -------------------------------------------------------------- fixtures

    /** A farm of the player's the cycle it appears, with a peasant inside it. */
    private static Unit halfBuiltFarm(Scene scene) {
        Unit site = freshFarm(scene);
        for (int cycle = 0; cycle < 200
                && site.order() == Unit.Order.UNDER_CONSTRUCTION; cycle++) {
            scene.world().tick();
        }
        assertEquals(Unit.Order.UNDER_CONSTRUCTION, site.order(),
                "the farm finished before the fixture could click it");
        return site;
    }

    /**
     * The same farm, watched from the cycle its foundation goes down.
     *
     * <p>The hammering is decided once every hundred and fifty cycles and
     * takes one site in three, so what matters to the two tests that listen
     * for it is how many of those cycles fall while the farm is going up.
     * Settling the fixture first spends them: a builder now walks onto its
     * site and waits there before the foundation goes down, as upstream's
     * does, and the two hundred cycles this used to burn were enough to cost
     * the window a look and let a silent run pass for a fixture.
     */
    private static Unit freshFarm(Scene scene) {
        Unit peasant = find(scene, "unit-peasant");
        scene.screen().selectForTest(peasant);
        press(scene, peasant, "button", "1");
        press(scene, peasant, "build", "unit-farm");
        int[] open = openTile(scene);
        click(scene, open[0], open[1]);

        Unit site = null;
        for (int cycle = 0; cycle < 400 && site == null; cycle++) {
            scene.world().tick();
            Unit here = scene.world().unitAt(open[0], open[1]);
            if (here != null && here.type() != null
                    && "unit-farm".equals(here.type().ident())) {
                site = here;
            }
        }
        assertNotNull(site, "the peasant never began the farm, so there is no site to click");
        return site;
    }

    /** Renders until the mixer has nothing left to say. */
    private static void silence(Scene scene) {
        for (int block = 0; block < 400; block++) {
            render(scene);
        }
        assertTrue(peak(render(scene)) < 1e-4f,
                "the mixer is still playing something from before the click");
    }

    private static float[] render(Scene scene) {
        float[] block = new float[FRAMES * AudioMixer.OUTPUT_CHANNELS];
        scene.audio().mixer().render(block, FRAMES);
        return block;
    }

    /** The same voice played on its own, through a mixer that has heard nothing. */
    private static float[] alone(Scene scene, Unit unit, String event) {
        GameAudio fresh = new GameAudio(scene.data().sounds());
        fresh.startWithoutDevice();
        fresh.playUnit(unit, event, 0);
        float[] block = new float[FRAMES * AudioMixer.OUTPUT_CHANNELS];
        fresh.mixer().render(block, FRAMES);
        return block;
    }

    /** The same, for a game sound the C++ asks for by name. */
    private static float[] named(Scene scene, String name) {
        GameAudio fresh = new GameAudio(scene.data().sounds());
        fresh.startWithoutDevice();
        fresh.playUi(name);
        float[] block = new float[FRAMES * AudioMixer.OUTPUT_CHANNELS];
        fresh.mixer().render(block, FRAMES);
        return block;
    }

    /** Mean absolute difference between two renderings. */
    private static double difference(float[] heard, float[] expected) {
        double total = 0;
        for (int i = 0; i < heard.length; i++) {
            total += Math.abs(heard[i] - expected[i]);
        }
        return total / heard.length;
    }

    private static float peak(float[] block) {
        float peak = 0f;
        for (float sample : block) {
            peak = Math.max(peak, Math.abs(sample));
        }
        return peak;
    }

    private static UnitButton button(Scene scene, Unit unit, String action, String value) {
        UnitButton found = null;
        for (UnitButton candidate : scene.data().userInterface("summer").buttons().all()) {
            if (action.equals(candidate.action())
                    && Objects.equals(value, candidate.value())
                    && candidate.appliesTo(unit.type().ident())
                    && candidate.level() == scene.commands().level()) {
                found = candidate;
            }
        }
        assertNotNull(found, "the shipped scripts declare no " + action
                + (value == null ? "" : " " + value) + " button for " + unit.type().ident()
                + " on page " + scene.commands().level());
        return found;
    }

    private static void press(Scene scene, Unit unit, String action, String value) {
        scene.screen().press(button(scene, unit, action, value), false);
    }

    private static void click(Scene scene, int tileX, int tileY) {
        int x = scene.layout().mapArea().x() + tileX * TILE + TILE / 2;
        int y = scene.layout().mapArea().y() + tileY * TILE + TILE / 2;
        MouseEvent pressed = new MouseEvent(scene.screen(), MouseEvent.MOUSE_PRESSED, 0L, 0,
                x, y, 1, false, MouseEvent.BUTTON1);
        MouseEvent released = new MouseEvent(scene.screen(), MouseEvent.MOUSE_RELEASED, 0L, 0,
                x, y, 1, false, MouseEvent.BUTTON1);
        MouseListener[] listeners = scene.screen().getMouseListeners();
        assertTrue(listeners.length > 0, "the screen has no mouse listener to click");
        for (MouseListener listener : listeners) {
            listener.mousePressed(pressed);
            listener.mouseReleased(released);
        }
    }

    private static int[] openTile(Scene scene) {
        UnitType farm = scene.data().unitTypes().types().get("unit-farm");
        for (int y = 1; y < 16; y++) {
            for (int x = 1; x < 24; x++) {
                if (scene.world().canPlaceBuilding(farm, x, y)) {
                    return new int[] {x, y};
                }
            }
        }
        Assumptions.assumeTrue(false, "no square in view on " + MAP + " will take a farm");
        return null;
    }

    private static Unit find(Scene scene, String ident) {
        for (Unit unit : scene.world().unitsSnapshot()) {
            if (unit.player() == ME && unit.isAlive() && unit.isOnMap()
                    && unit.type() != null && ident.equals(unit.type().ident())) {
                return unit;
            }
        }
        Assumptions.assumeTrue(false, MAP + " places no " + ident + " for player " + ME);
        return null;
    }

    private static Unit findAny(Scene scene, String ident) {
        for (Unit unit : scene.world().unitsSnapshot()) {
            if (unit.isAlive() && unit.isOnMap() && unit.type() != null
                    && ident.equals(unit.type().ident())) {
                return unit;
            }
        }
        Assumptions.assumeTrue(false, MAP + " places no " + ident);
        return null;
    }

    /** Somebody to strike the town hall with. */
    private static Unit enemy(Scene scene) {
        for (Unit unit : scene.world().unitsSnapshot()) {
            if (unit.player() != ME && unit.isAlive() && unit.type() != null
                    && !unit.type().building()) {
                return unit;
            }
        }
        Assumptions.assumeTrue(false, MAP + " places nobody to attack with");
        return null;
    }
}
