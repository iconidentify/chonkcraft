package net.chonkbase.chonkcraft.desktop;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.chonkbase.chonkcraft.data.graphic.IndexedImage;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.FogOfWar;
import net.chonkbase.chonkcraft.engine.map.MapRenderer;
import net.chonkbase.chonkcraft.engine.map.SeenBuildings;
import net.chonkbase.chonkcraft.engine.missile.Missile;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;

/**
 * Does what the simulation says is there actually reach a pixel?
 *
 * <p>Every sweep this repository has run so far probed engine state, and the
 * one that looked at the interface painted panels and menus. Nothing had ever
 * looked at the battlefield in the middle of a fight. That left a whole
 * category with no coverage -- <em>things the simulation gets right that never
 * make it to the screen</em> -- and the corpse chain is the proof: it was
 * audited, found correct, and listed under "Checked and found correct" in
 * focused tests, while no corpse in the history of this implementation had
 * ever been drawn. The simulation put the body down, gave it the neutral
 * player, the right tile, the right sheet and the right frame, and the
 * renderer skipped it because the predicate it filtered the draw list with
 * begins {@code !unit.isAlive()}. A player watched a troll die and vanish.
 *
 * <p>So this plays a real campaign mission headlessly, stages a real fight on
 * it, and every few cycles asks of every thing the world says is present, on
 * the map and inside the local player's fog: <em>how many pixels of the
 * painted frame does it account for?</em> Zero is a finding.
 *
 * <p>The measurement is the inverted-control idiom
 * {@code FogRenderingTest.theMeasurementCatchesTheOldBehaviour} uses. The frame
 * is painted, then painted again with that one thing held back through
 * {@link GameScreen#withhold}, and the two are compared. A thing that reaches
 * the screen makes the two frames differ where it stands; a thing that does not
 * makes them identical, and no amount of correct simulation state changes that.
 * That is what stops the sweep passing vacuously, which every other way of
 * asking this question does: a frame with a corpse on it and a frame without
 * one are both just pixels.
 *
 * <p>Two soundness rules the sweep enforces on itself, both learned by getting
 * them wrong first.
 *
 * <ul>
 * <li>A candidate whose rectangle could be under opaque fog is not measured at
 * all. The fog is drawn last and over everything, so a thing correctly drawn
 * under a black mask also accounts for zero pixels, and reporting that would be
 * a lie. {@link Cover} decides, and the skipped candidates are counted so a run
 * that measured almost nothing cannot look like a clean one.
 * <li>A candidate another sprite is drawn on top of is reported with its
 * overlap count. Two corpses on one square, or a footman standing over the body
 * of the orc he killed, can hide each other, and that is not the renderer's
 * fault.
 * </ul>
 *
 * <p>Run {@link #main} for the full table. {@code RenderingTruthTest} pins it.
 */
final class RenderingTruth {

    private RenderingTruth() {
    }

    static final int WIDTH = 640;
    static final int HEIGHT = 480;
    static final int TILE = 32;

    /**
     * The kinds of thing worth asking about.
     *
     * <p>Chosen as the things a player watches a battle by, rather than as the
     * things the renderer has methods for -- the point is to catch a category
     * nobody wrote a method for at all.
     */
    enum Category {
        /** A unit alive and standing: the control, and much the commonest. */
        LIVING,
        /** A unit running its death animation. */
        DYING,
        /** The body a soldier leaves behind. */
        CORPSE,
        /** The rubble a building leaves behind. */
        RUBBLE,
        /** A building going up, drawn as scaffolding rather than as itself. */
        CONSTRUCTION,
        /** An arrow, an axe, a boulder or a bolt in the air. */
        MISSILE,
        /** The fire on a damaged building, which is a missile too. */
        BURNING,
        /** A spell in the air or on the ground. */
        SPELL,
        /** A health, mana or progress bar under a unit. */
        DECORATION,
        /** A building the player has scouted and can no longer see. */
        REMEMBERED,
    }

    /** How much fog art can land on a rectangle. */
    private enum Cover {
        /** Nothing at all: every square it touches and their neighbours are lit. */
        CLEAR,
        /** The half-alpha veil of remembered ground, which things show through. */
        VEILED,
        /** Solid black is possible, so nothing here can be measured. */
        HIDDEN,
    }

    /**
     * One thing the world says is on the screen.
     *
     * @param where the rectangle it should have landed in, worked out from the
     *              world's own numbers rather than asked of the renderer
     */
    record Candidate(Category category, String what, Rectangle where,
            Unit unit, Unit decorations, Missile missile, SeenBuildings.Memory memory) {

        int tileX() {
            return unit != null ? unit.tileX()
                    : memory != null ? memory.tileX() : where.x / TILE;
        }

        int tileY() {
            return unit != null ? unit.tileY()
                    : memory != null ? memory.tileY() : where.y / TILE;
        }

        GameScreen.Withheld held() {
            if (memory != null) {
                return GameScreen.Withheld.ofMemory(memory);
            }
            if (missile != null) {
                return GameScreen.Withheld.ofMissile(missile);
            }
            if (decorations != null) {
                return GameScreen.Withheld.ofDecorations(decorations);
            }
            return GameScreen.Withheld.ofUnit(unit);
        }
    }

    /**
     * A thing the world had and the frame did not.
     *
     * @param overlaps how many other drawn sprites cover its rectangle, which
     *                 is the one innocent explanation for a zero
     */
    record Finding(Surface surface, Category category, String what, long cycle,
            int tileX, int tileY, int pixelsInside, int pixelsAnywhere, int overlaps) {

        @Override
        public String toString() {
            return surface + " " + category + " " + what + " at " + tileX + "," + tileY
                    + " on cycle " + cycle
                    + ": " + pixelsInside + " pixels where it stands, "
                    + pixelsAnywhere + " anywhere on the picture"
                    + (overlaps > 0 ? " (" + overlaps + " drawn over it)" : "");
        }
    }

    /** What a run saw. */
    record Report(String mission,
            Map<Category, Integer> measured,
            Map<Category, Integer> skippedUnderFog,
            List<Finding> invisible,
            List<Finding> misplaced,
            int paints) {

        /** Findings nothing else can be blamed for. */
        List<Finding> unexplained() {
            return invisible.stream().filter(finding -> finding.overlaps() == 0).toList();
        }

        int measured(Category category) {
            return measured.getOrDefault(category, 0);
        }

        void print() {
            System.out.println("== " + mission + " (" + paints + " frames painted)");
            for (Category category : Category.values()) {
                int seen = measured(category);
                int skipped = skippedUnderFog.getOrDefault(category, 0);
                if (seen == 0 && skipped == 0) {
                    continue;
                }
                long missing = invisible.stream()
                        .filter(finding -> finding.category() == category).count();
                System.out.printf("   %-13s measured %4d   invisible %3d   skipped under fog %4d%n",
                        category, seen, missing, skipped);
            }
            for (Finding finding : invisible) {
                System.out.println("   INVISIBLE  " + finding);
            }
            for (Finding finding : misplaced) {
                System.out.println("   MISPLACED  " + finding);
            }
        }
    }

    /**
     * A mission loaded, wired to both surfaces, ready to be painted.
     *
     * <p>Two surfaces and not one, because a thing can be drawn on the field
     * and missing from the minimap, and the minimap is the surface a player
     * uses to know where anything is. That was not a hypothetical: the
     * minimap tested one square of a unit's footprint against one player's own
     * fog and drew no remembered building at all.
     */
    record Scene(GameData data, Mission mission, World world, GameScreen screen,
            SidePanel panel, int localPlayer, String label) {}

    /** Which picture a thing failed to reach. */
    enum Surface { FIELD, MINIMAP }

    // ------------------------------------------------------------------
    // Building a scene
    // ------------------------------------------------------------------

    static GameData data() {
        AssetSource assets = AssetSource.fromEnvironment();
        if (assets == null) {
            return null;
        }
        return new GameData(assets);
    }

    /**
     * A real campaign mission, painted by a real {@link GameScreen}.
     *
     * <p>A real mission and not a bare map, because the things this is looking
     * for are the ones a hand-built scene does not think to include. The screen
     * is given the tileset's own fog masks, so the fog on the frame is the fog
     * the player sees rather than a stand-in.
     */
    static Scene load(GameData data, String missionPath) {
        Mission mission = data.loadMission(missionPath);
        if (mission == null) {
            return null;
        }
        // GameData.loadMission does not hand the world its spell table and
        // nothing else in the game does either, so a mission's world knows no
        // spells and World.orderCast refuses every one of them. Set here so
        // the sweep can stage a cast at all; the game's own path is fixed in
        // Main and LobbySetup.
        mission.world().setSpells(data.spells().spells());
        PudMap source = mission.source();
        GameData.LoadedTileset tileset = data.loadTileset(source.tileset());
        World world = mission.world();

        IndexedImage rendered = new MapRenderer(tileset.tileset(), tileset.sheet())
                .render(world.map().width(), world.map().height(), world.map().tileCodes());
        BufferedImage terrain = rendered.toIndexedBufferedImage(tileset.palette());
        String tilesetName = source.tileset() == PudMap.Tileset.FOREST
                ? "summer"
                : source.tileset().name().toLowerCase(Locale.ROOT);

        int localPlayer = 0;
        for (int i = 0; i < source.players().length; i++) {
            if (source.players()[i] == PudMap.PlayerType.PERSON) {
                localPlayer = i;
                break;
            }
        }
        String race = source.races()[localPlayer] == PudMap.Race.ORC ? "orc" : "human";

        GameScreen screen = new GameScreen(world, data, terrain, tileset.palette(),
                tilesetName, localPlayer, WIDTH, HEIGHT, null, null, null, null, null,
                List.of(), race);
        screen.setSize(WIDTH, HEIGHT);
        screen.setLayout((net.chonkbase.chonkcraft.engine.ui.UiLayout.Layout) null);
        screen.setGameScale(1);
        screen.setFogTiles(FogTiles.from(tileset.sheet()));

        SidePanel panel = new SidePanel(world, data, localPlayer, race, tilesetName,
                data.uiLayout(race, WIDTH, HEIGHT));
        return new Scene(data, mission, world, screen, panel, localPlayer, missionPath);
    }

    /**
     * Stages a battle on the mission's own ground.
     *
     * <p>The categories this exists to cover do not turn up reliably in five
     * simulated minutes of a computer player minding its own business, and a
     * sweep that never saw a corpse would report a clean run. So a fight is
     * started: two squads facing each other, a building of the enemy's to be
     * beaten down through burning to rubble, a caster to throw a spell, and a
     * worker laying a foundation. Everything is placed by the world's own
     * {@code createUnit} and ordered through the world's own order calls, on
     * real campaign terrain, next to the player's own start.
     *
     * @return what was staged, or null if the map had no room
     */
    record Staged(int tileX, int tileY, int enemy, Unit wizard, String spell, Unit scout,
            List<Unit> defenders, Unit building) {}

    static Staged stageABattle(Scene scene) {
        World world = scene.world();
        GameData data = scene.data();
        Map<String, UnitType> roster = data.unitTypes().types();
        int local = scene.localPlayer();
        int enemy = -1;
        for (int player = 0; player < 8; player++) {
            if (player != local && world.player(player) != null
                    && world.isEnemyPlayer(local, player)) {
                enemy = player;
                break;
            }
        }
        if (enemy < 0) {
            return null;
        }

        // Beside the player's own army rather than at the map's declared start
        // location: on {@code campaigns/human/level05h} the two are sixty
        // squares apart and the start location is open water.
        int originX = world.map().width() / 2;
        int originY = world.map().height() / 2;
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.player() == local && unit.type() != null && !unit.type().building()) {
                originX = unit.tileX();
                originY = unit.tileY();
                break;
            }
        }

        // Open squares near it, rather than a clear rectangle. There is not
        // one clear ten-by-eight rectangle anywhere on {@code level02h}: a
        // Warcraft II map is trees, cliffs and water with lanes between them,
        // and the first shape of this looked for a parade ground and found
        // none on any of the four missions.
        List<int[]> ground = openGroundNear(world, originX, originY);
        if (ground.size() < 14) {
            return null;
        }
        // North for them, south for us, so the two squads have to close.
        ground.sort(java.util.Comparator.<int[]>comparingInt(tile -> tile[1])
                .thenComparingInt(tile -> tile[0]));

        UnitType ours = pick(roster, "unit-footman", "unit-grunt");
        UnitType theirs = pick(roster, "unit-grunt", "unit-footman");
        UnitType caster = pick(roster, "unit-mage", "unit-death-knight");
        UnitType worker = pick(roster, "unit-peasant", "unit-peon");
        UnitType foundation = pick(roster, "unit-farm", "unit-pig-farm");
        UnitType theirBuilding = pick(roster, "unit-orc-barracks", "unit-human-barracks",
                "unit-pig-farm", "unit-farm");

        List<Unit> defenders = new ArrayList<>();
        for (int i = 0; i < 4 && i < ground.size(); i++) {
            Unit one = world.createUnit(theirs, enemy, ground.get(i)[0], ground.get(i)[1]);
            if (one != null) {
                defenders.add(one);
            }
        }
        List<Unit> attackers = new ArrayList<>();
        for (int i = 0; i < 4 && i < ground.size(); i++) {
            int[] tile = ground.get(ground.size() - 1 - i);
            Unit one = world.createUnit(ours, local, tile[0], tile[1]);
            if (one != null) {
                attackers.add(one);
            }
        }
        if (attackers.isEmpty() || defenders.isEmpty()) {
            return null;
        }

        // A building of theirs to beat down: it burns on the way and leaves
        // rubble at the end, which is two more categories. Started at a
        // quarter of its health, because DefineBurningBuilding's first fire
        // lights below 75 per cent and four footmen take a long time over a
        // barracks at full strength.
        Unit building = null;
        if (theirBuilding != null) {
            for (int[] tile : ground) {
                if (world.canPlaceBuilding(theirBuilding, tile[0], tile[1])) {
                    building = world.createUnit(theirBuilding, enemy, tile[0], tile[1]);
                    if (building != null) {
                        building.setHitPoints(Math.max(1, theirBuilding.hitPoints() / 4));
                        break;
                    }
                }
            }
        }

        Unit wizard = null;
        Unit builder = null;
        for (int i = 4; i < ground.size() - 4; i++) {
            int[] tile = ground.get(i);
            if (wizard == null && caster != null) {
                wizard = world.createUnit(caster, local, tile[0], tile[1]);
                if (wizard != null) {
                    wizard.setMana(caster.mana());
                    continue;
                }
            }
            if (builder == null && worker != null) {
                builder = world.createUnit(worker, local, tile[0], tile[1]);
                if (builder != null) {
                    break;
                }
            }
        }

        for (Unit attacker : attackers) {
            world.orderAttack(attacker, defenders.get(0));
        }
        for (Unit defender : defenders) {
            world.orderAttack(defender, attackers.get(0));
        }
        if (building != null) {
            world.orderAttack(attackers.get(attackers.size() - 1), building);
        }
        if (builder != null && foundation != null) {
            for (int[] tile : ground) {
                if (world.orderBuild(builder, foundation, tile[0], tile[1])) {
                    break;
                }
            }
        }
        String spell = null;
        if (wizard != null && defenders.get(0) != null) {
            // A spell in the air is its own category, and a caster will not
            // throw one unless the upgrade that unlocks it has been had.
            for (String candidate : new String[] {"spell-fireball", "spell-blizzard",
                "spell-death-coil", "spell-healing"}) {
                var declared = world.spells().get(candidate);
                if (declared == null) {
                    continue;
                }
                if (!declared.dependUpgrade().isEmpty()) {
                    world.upgrades(local).complete(declared.dependUpgrade());
                }
                if (world.orderCast(wizard, candidate, defenders.get(0))) {
                    spell = candidate;
                    break;
                }
            }
        }
        int[] middle = ground.get(ground.size() / 2);
        return new Staged(middle[0], middle[1], enemy, wizard, spell,
                stageAScout(scene, enemy), defenders, building);
    }

    /**
     * A scout of ours put down beside a building of theirs.
     *
     * <p>For the remembered buildings, which are the one category that cannot
     * be staged by putting something on the ground: a memory only exists for
     * ground a player has explored and cannot currently see, so it needs a
     * unit that looks and then stops looking. The sweep takes this one away
     * again a few dozen cycles in, and the building it was watching becomes
     * the only thing the player still has a picture of.
     */
    private static Unit stageAScout(Scene scene, int enemy) {
        World world = scene.world();
        UnitType eye = pick(scene.data().unitTypes().types(), "unit-footman", "unit-grunt");
        if (eye == null) {
            return null;
        }
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.player() != enemy || unit.type() == null
                    || !unit.type().building() || !unit.type().visibleUnderFog()
                    || world.isVisibleTo(scene.localPlayer(), unit.tileX(), unit.tileY())) {
                continue;
            }
            for (int radius = 2; radius <= 4; radius++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        Unit scout = world.createUnit(eye, scene.localPlayer(),
                                unit.tileX() + dx, unit.tileY() + dy);
                        if (scout != null) {
                            return scout;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static UnitType pick(Map<String, UnitType> roster, String... idents) {
        for (String ident : idents) {
            UnitType type = roster.get(ident);
            if (type != null) {
                return type;
            }
        }
        return null;
    }

    /**
     * Squares near a point that a unit can be put down on.
     *
     * <p>Widened until there are enough of them, because how open the ground
     * is varies enormously between a Warcraft II map's clearings and its
     * forest lanes.
     */
    private static List<int[]> openGroundNear(World world, int originX, int originY) {
        List<int[]> found = new ArrayList<>();
        for (int radius = 5; radius <= 20 && found.size() < 14; radius += 5) {
            found.clear();
            for (int y = originY - radius; y <= originY + radius; y++) {
                for (int x = originX - radius; x <= originX + radius; x++) {
                    if (x < 1 || y < 1 || x >= world.map().width() - 1
                            || y >= world.map().height() - 1) {
                        continue;
                    }
                    var field = world.map().field(x, y);
                    if (field.isLandPassable() && !field.isForest() && !field.isWall()
                            && !field.isOccupied()) {
                        found.add(new int[] {x, y});
                    }
                }
            }
        }
        return found;
    }

    // ------------------------------------------------------------------
    // The sweep
    // ------------------------------------------------------------------

    /**
     * Plays a mission and measures what reaches the screen.
     *
     * @param cycles      how long to play, in simulation cycles
     * @param sampleEvery how often to stop and look, in cycles. Sampled rather
     *                    than exhaustive: a frame costs two paints per
     *                    candidate, and the answer does not change between one
     *                    cycle and the next
     */
    static Report sweep(Scene scene, Staged staged, int cycles, int sampleEvery) {
        Map<Category, Integer> measured = new LinkedHashMap<>();
        Map<Category, Integer> skipped = new LinkedHashMap<>();
        List<Finding> invisible = new ArrayList<>();
        List<Finding> misplaced = new ArrayList<>();
        int paints = 0;

        for (int cycle = 0; cycle < cycles; cycle++) {
            scene.mission().tick();
            if (cycle % sampleEvery != 0) {
                continue;
            }
            if (cycle == 40 && staged != null && staged.scout() != null) {
                // The scout stops looking, and the building it was watching
                // becomes a memory.
                scene.world().remove(staged.scout());
            }
            if (cycle == 40 && scene.world().seenBuildings()
                    .size(scene.localPlayer()) == 0) {
                // This is a rendering sweep, not the fog-lifecycle proof.
                // Stage the view model directly so a mission whose armies
                // happen to keep every building lit cannot make the entire
                // remembered-building surface disappear from the sample.
                stageRememberedBuilding(scene, staged == null
                        ? (scene.localPlayer() + 1) % 8 : staged.enemy());
            }
            // The death stages arrive on the clock, not on the melee's pace.
            // They used to arrive by accident: "not allied" meant "enemy", a
            // NOBODY slot with five units on level05h counted as one, and the
            // fight was staged against a side with no AI and no
            // reinforcements, so it collapsed quickly and the corpse and the
            // rubble fell inside the window. With CPlayer::Init's own masks
            // the staged war is against the real computer, its army keeps
            // arriving, and the first corpse moved to cycle 208 against a
            // 200-cycle window -- measured -- with the rubble not inside 300.
            // What this sweep measures is drawing, not combat pacing, so the
            // two stages it exists to watch are guaranteed through the
            // world's own kill path, the same path a sword uses. The
            // defender falls at the first sample because a death animation
            // and the body's arrival take some hundred and fifty cycles; the
            // building falls at 120 so its fire is on screen first.
            if (cycle == 8 && staged != null) {
                for (Unit defender : staged.defenders()) {
                    if (defender.isAlive()) {
                        scene.world().kill(defender);
                        break;
                    }
                }
            }
            if (cycle == 120 && staged != null
                    && staged.building() != null && staged.building().isAlive()) {
                scene.world().kill(staged.building());
            }
            if (staged != null && staged.spell() != null && staged.wizard() != null
                    && staged.wizard().isAlive()) {
                // Cast again. A fireball is in the air for two cycles, so one
                // cast at the start of a three-hundred cycle run is a coin
                // toss against the sampling and the sweep reported no spell
                // effect at all.
                for (Unit other : scene.world().unitsSnapshot()) {
                    if (other.isAlive() && other.player() == staged.enemy()
                            && other.type() != null && !other.type().building()
                            && scene.world().orderCast(
                                    staged.wizard(), staged.spell(), other)) {
                        break;
                    }
                }
            }
            for (Candidate candidate : candidates(scene)) {
                // Centred on the thing, so it is on the frame at all. A
                // 640 by 480 window over a 128-square map holds a twentieth
                // of it, and a sweep that only looked where the camera
                // happened to be would report almost nothing.
                Rectangle where = centreOn(scene, candidate);
                // A memory lives on ground that is by definition not lit, so
                // the border test the others use would refuse every one of
                // them. Its own squares are explored or it would not exist,
                // and an explored square takes the half-alpha veil and no
                // black at all, so the picture underneath still shows.
                Cover cover = candidate.category() == Category.REMEMBERED
                        ? coverOf(scene, where, 0) : coverOf(scene, where, 1);
                if (cover == Cover.HIDDEN || isBlankInTheSheet(scene, candidate)) {
                    skipped.merge(candidate.category(), 1, Integer::sum);
                    continue;
                }
                measured.merge(candidate.category(), 1, Integer::sum);
                paints += 2;

                int[] full = pixels(scene.screen());
                scene.screen().withhold(candidate.held());
                int[] without = pixels(scene.screen());
                scene.screen().withhold(GameScreen.Withheld.NOTHING);

                int inside = 0;
                int anywhere = 0;
                for (int y = 0; y < HEIGHT; y++) {
                    for (int x = 0; x < WIDTH; x++) {
                        if (full[y * WIDTH + x] == without[y * WIDTH + x]) {
                            continue;
                        }
                        anywhere++;
                        if (where.contains(x, y)) {
                            inside++;
                        }
                    }
                }
                if (anywhere == 0) {
                    invisible.add(new Finding(Surface.FIELD, candidate.category(),
                            candidate.what(), scene.world().cycle(),
                            where.x / TILE, where.y / TILE,
                            inside, anywhere, overlaps(scene, candidate, where)));
                } else if (inside == 0) {
                    misplaced.add(new Finding(Surface.FIELD, candidate.category(),
                            candidate.what(), scene.world().cycle(),
                            where.x / TILE, where.y / TILE,
                            inside, anywhere, overlaps(scene, candidate, where)));
                }

                if (!onTheMinimap(candidate) || !scene.panel().isAvailable()) {
                    continue;
                }
                paints += 2;
                Rectangle dot = minimapRectangle(scene, candidate);
                int[] withDot = panelPixels(scene, GameScreen.Withheld.NOTHING);
                int[] withoutDot = panelPixels(scene, candidate.held());
                int changed = 0;
                for (int y = dot.y; y < dot.y + dot.height; y++) {
                    for (int x = dot.x; x < dot.x + dot.width; x++) {
                        if (x < 0 || y < 0 || x >= WIDTH || y >= HEIGHT) {
                            continue;
                        }
                        if (withDot[y * WIDTH + x] != withoutDot[y * WIDTH + x]) {
                            changed++;
                        }
                    }
                }
                if (changed == 0) {
                    invisible.add(new Finding(Surface.MINIMAP, candidate.category(),
                            candidate.what(), scene.world().cycle(),
                            candidate.tileX(), candidate.tileY(), 0, 0,
                            minimapOverlaps(scene, candidate, dot)));
                }
            }
        }
        return new Report(scene.label(), measured, skipped, invisible, misplaced, paints);
    }

    /**
     * Puts one real retail building into the exact remembered-view model the
     * field and minimap renderers consume.
     *
     * <p>{@code SeenBuildingsTest} independently proves the scout-to-memory
     * lifecycle. This renderer fixture deliberately starts at that boundary:
     * it explores then darkens a free footprint and records a retail building
     * there, so presentation is certified even when the mission's live army
     * keeps every naturally scouted building visible throughout the sample.
     */
    static SeenBuildings.Memory stageRememberedBuilding(Scene scene, int owner) {
        World world = scene.world();
        int local = scene.localPlayer();
        for (UnitType type : scene.data().unitTypes().types().values()) {
            if (!type.building() || !type.visibleUnderFog()
                    || type.imageWidth() < 1 || type.imageHeight() < 1) {
                continue;
            }
            int width = Math.max(1, type.tileWidth());
            int height = Math.max(1, type.tileHeight());
            for (int y = 1; y + height < world.map().height(); y++) {
                for (int x = 1; x + width < world.map().width(); x++) {
                    boolean usable = true;
                    for (int dy = 0; dy < height && usable; dy++) {
                        for (int dx = 0; dx < width; dx++) {
                            if (world.isVisibleTo(local, x + dx, y + dy)
                                    || world.map().field(x + dx, y + dy).isOccupied()) {
                                usable = false;
                                break;
                            }
                        }
                    }
                    if (!usable) {
                        continue;
                    }
                    int horizontalOverhang = Math.max(0,
                            type.imageWidth() - width * TILE);
                    int verticalOverhang = Math.max(0,
                            type.imageHeight() - height * TILE);
                    int sight = Math.max(horizontalOverhang, verticalOverhang)
                            / (2 * TILE) + 2;
                    world.fog().addSight(local, x, y, width, height, sight);
                    world.fog().removeSight(local, x, y, width, height, sight);
                    SeenBuildings.Memory memory = new SeenBuildings.Memory(
                            type, owner, x, y, 0, false, false, 1.0);
                    world.seenBuildings().remember(local, memory);
                    return memory;
                }
            }
        }
        return null;
    }

    /**
     * Whether upstream would put this thing on the minimap.
     *
     * <p>{@code CUnit::IsVisibleOnMinimap} draws what is {@code IsAliveOnMap},
     * so no corpse, no rubble and nothing dying, and there are no missiles or
     * decorations on a minimap at all. What is left is the living, the half
     * built, and the buildings a player remembers.
     */
    private static boolean onTheMinimap(Candidate candidate) {
        return switch (candidate.category()) {
            case LIVING, CONSTRUCTION -> true;
            case REMEMBERED -> candidate.memory().type().visibleUnderFog();
            default -> false;
        };
    }

    /** Where a thing's dot lands on the panel, in panel pixels. */
    private static Rectangle minimapRectangle(Scene scene, Candidate candidate) {
        Rectangle area = SidePanel.minimapArea();
        int mapWidth = scene.world().map().width();
        int mapHeight = scene.world().map().height();
        UnitType type = candidate.unit() != null
                ? candidate.unit().type() : candidate.memory().type();
        int x = area.x + candidate.tileX() * area.width / mapWidth;
        int y = area.y + candidate.tileY() * area.height / mapHeight;
        int across = Math.max(1, Math.max(1, type.tileWidth()) * area.width / mapWidth);
        int down = Math.max(1, Math.max(1, type.tileHeight()) * area.height / mapHeight);
        return new Rectangle(x, y, across, down);
    }

    /**
     * How many other dots land on the same pixels.
     *
     * <p>A minimap square is a whole tile or more, so two units standing beside
     * each other are one dot and hiding each other is normal.
     */
    private static int minimapOverlaps(Scene scene, Candidate candidate, Rectangle dot) {
        int over = 0;
        for (Unit other : scene.world().unitsSnapshot()) {
            if (other == candidate.unit() || other.type() == null || !other.isAlive()
                    || !other.isOnMap()
                    || !scene.world().isVisibleTo(scene.localPlayer(), other)) {
                continue;
            }
            Candidate stand = new Candidate(Category.LIVING, "", new Rectangle(),
                    other, null, null, null);
            if (minimapRectangle(scene, stand).intersects(dot)) {
                over++;
            }
        }
        return over;
    }

    /** The panel painted on its own, so the minimap can be compared. */
    private static int[] panelPixels(Scene scene, GameScreen.Withheld withheld) {
        scene.panel().withhold(withheld);
        BufferedImage frame = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        var g = frame.createGraphics();
        scene.panel().draw(g, WIDTH, HEIGHT, null,
                scene.screen().cameraX(), scene.screen().cameraY(), WIDTH, HEIGHT);
        g.dispose();
        scene.panel().withhold(GameScreen.Withheld.NOTHING);
        return ((DataBufferInt) frame.getRaster().getDataBuffer()).getData();
    }

    /**
     * Whether the frame the data picks is empty in the sheet it came from.
     *
     * <p>Not a rendering fault, and worth the check because it looks exactly
     * like one. {@code missiles/explosion.png} is 320 by 256 and
     * {@code missiles.legacy-declaration} declares twenty frames, but only sixteen of the
     * twenty hold anything: the last row is padding wartool wrote to square
     * the sheet off. So the last four cycles of every explosion in the game
     * draw nothing, upstream included, and a sweep that called that invisible
     * would be reporting the art.
     */
    private static boolean isBlankInTheSheet(Scene scene, Candidate candidate) {
        IndexedImage sheet;
        int frameWidth;
        int frameHeight;
        int index;
        if (candidate.missile() != null) {
            var type = candidate.missile().type();
            sheet = scene.data().sprite(type.sprite());
            if (sheet == null) {
                return false;
            }
            frameWidth = Math.max(1, type.frameWidth());
            frameHeight = Math.max(1, type.frameHeight());
            int columns = Math.max(1, sheet.width() / frameWidth);
            int rows = Math.max(1, sheet.height() / frameHeight);
            index = Math.floorMod(GameScreen.missileSpriteFrame(candidate.missile()).index(),
                    columns * rows);
        } else if (candidate.unit() != null && candidate.decorations() == null) {
            UnitType type = candidate.unit().type();
            sheet = scene.data().sprite(type.imageFileFor(scene.screen().tilesetName()));
            if (sheet == null) {
                return false;
            }
            frameWidth = Math.max(1, type.imageWidth());
            frameHeight = Math.max(1, type.imageHeight());
            int columns = Math.max(1, sheet.width() / frameWidth);
            int rows = Math.max(1, sheet.height() / frameHeight);
            index = Math.floorMod(candidate.unit().spriteFrame().index(), columns * rows);
        } else {
            return false;
        }
        int columns = Math.max(1, sheet.width() / frameWidth);
        int left = (index % columns) * frameWidth;
        int top = (index / columns) * frameHeight;
        for (int y = top; y < top + frameHeight && y < sheet.height(); y++) {
            for (int x = left; x < left + frameWidth && x < sheet.width(); x++) {
                if (sheet.get(x, y)
                        != net.chonkbase.chonkcraft.data.graphic.Palette.TRANSPARENT_INDEX) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Moves the camera onto a candidate and returns its rectangle on screen. */
    private static Rectangle centreOn(Scene scene, Candidate candidate) {
        Rectangle world = candidate.where();
        scene.screen().centreOn((world.x + world.width / 2) / TILE,
                (world.y + world.height / 2) / TILE);
        return new Rectangle(world.x - scene.screen().cameraX(),
                world.y - scene.screen().cameraY(), world.width, world.height);
    }

    /**
     * How many other sprites are drawn over this one.
     *
     * <p>The one honest reason a thing that is drawn can account for no
     * pixels: something else was drawn on top of it. A footman standing on the
     * body of the orc he just killed hides it completely, and that is the
     * renderer doing what upstream's draw levels tell it to.
     *
     * <p>Counted by containment and not by intersection, and the difference
     * decides whether this sweep is worth running. With intersection, every
     * corpse in a four-on-four melee has a living soldier clipping a corner of
     * its frame, so every corpse is excused and the sweep passes with the
     * corpse renderer torn out -- which was measured, by tearing it out. A
     * sprite that overlaps a corner hides a corner; only one that covers the
     * whole rectangle can account for a blank.
     */
    private static int overlaps(Scene scene, Candidate candidate, Rectangle where) {
        int over = 0;
        for (Unit other : scene.world().unitsSnapshot()) {
            if (other == candidate.unit() || !other.isOnMap() || other.type() == null) {
                continue;
            }
            Rectangle theirs = spriteRectangle(other);
            theirs.translate(-scene.screen().cameraX(), -scene.screen().cameraY());
            if (theirs.contains(where)) {
                over++;
            }
        }
        // And other projectiles. A catapult's boulder and the explosion it
        // sets off stand on the same square at the same draw level, so each
        // covers the other and neither can be measured on its own.
        for (Missile other : scene.world().missiles()) {
            if (other == candidate.missile() || other.type() == null
                    || other.type().sprite() == null) {
                continue;
            }
            int width = Math.max(1, other.type().frameWidth());
            int height = Math.max(1, other.type().frameHeight());
            Rectangle theirs = new Rectangle(
                    (int) Math.round(other.x()) - width / 2 - scene.screen().cameraX(),
                    (int) Math.round(other.y()) - height / 2 - scene.screen().cameraY(),
                    width, height);
            if (theirs.intersects(where)) {
                over++;
            }
        }
        return over;
    }

    /** The painted frame, as raw pixels, so a whole-frame compare is cheap. */
    private static int[] pixels(GameScreen screen) {
        BufferedImage frame = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        var g = frame.createGraphics();
        screen.paint(g);
        g.dispose();
        return ((DataBufferInt) frame.getRaster().getDataBuffer()).getData();
    }

    /** The whole frame, for a human to look at. */
    static BufferedImage paint(GameScreen screen) {
        BufferedImage frame = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        var g = frame.createGraphics();
        screen.paint(g);
        g.dispose();
        return frame;
    }

    // ------------------------------------------------------------------
    // What the world says is there
    // ------------------------------------------------------------------

    /** Every thing on this cycle the world says the local player can see. */
    static List<Candidate> candidates(Scene scene) {
        World world = scene.world();
        int local = scene.localPlayer();
        Set<String> corpseIdents = corpseIdents(scene.data());
        List<Candidate> found = new ArrayList<>();

        for (Unit unit : world.unitsSnapshot()) {
            UnitType type = unit.type();
            if (type == null || !unit.isOnMap() || type.revealer()) {
                continue;
            }
            if (!showsToLocalPlayer(world, local, unit)) {
                continue;
            }
            Category category;
            if (corpseIdents.contains(type.ident())) {
                category = type.ident().startsWith("unit-destroyed")
                        ? Category.RUBBLE : Category.CORPSE;
            } else if (unit.isDying()) {
                category = Category.DYING;
            } else if (unit.order() == Unit.Order.UNDER_CONSTRUCTION) {
                category = Category.CONSTRUCTION;
            } else {
                category = Category.LIVING;
            }
            String what = type.ident() + " of player " + unit.player();
            found.add(new Candidate(category, what, spriteRectangle(unit),
                    unit, null, null, null));

            if (category == Category.LIVING && carriesADecoration(unit, local)) {
                found.add(new Candidate(Category.DECORATION, what,
                        decorationRectangle(unit), unit, unit, null, null));
            }
        }

        for (Missile missile : world.missiles()) {
            var type = missile.type();
            if (type == null || type.sprite() == null || type.isNone()) {
                continue;
            }
            if (world.fog().visibility(local, missile.tileX(), missile.tileY())
                    != FogOfWar.Visibility.VISIBLE) {
                continue;
            }
            Category category = BURNING_MISSILES.contains(type.ident())
                    ? Category.BURNING
                    : SPELL_MISSILES.contains(type.ident()) ? Category.SPELL : Category.MISSILE;
            int width = Math.max(1, type.frameWidth());
            int height = Math.max(1, type.frameHeight());
            Rectangle where = new Rectangle(
                    (int) Math.round(missile.x()) - width / 2,
                    (int) Math.round(missile.y()) - height / 2, width, height);
            found.add(new Candidate(category, type.ident(), where, null, null, missile, null));
        }

        for (SeenBuildings.Memory memory : world.seenBuildings().forPlayer(local)) {
            UnitType type = memory.type();
            int frameWidth = Math.max(1, type.imageWidth());
            int frameHeight = Math.max(1, type.imageHeight());
            int footprintWidth = Math.max(1, type.tileWidth()) * TILE;
            int footprintHeight = Math.max(1, type.tileHeight()) * TILE;
            Rectangle where = new Rectangle(
                    memory.tileX() * TILE + (footprintWidth - frameWidth) / 2,
                    memory.tileY() * TILE + (footprintHeight - frameHeight) / 2,
                    frameWidth, frameHeight);
            found.add(new Candidate(Category.REMEMBERED,
                    type.ident() + " remembered at " + memory.tileX() + "," + memory.tileY(),
                    where, null, null, null, memory));
        }
        return found;
    }

    /**
     * Whether the world says the local player should be looking at this unit.
     *
     * <p>Deliberately not {@code GameScreen.isUnitVisible}: that is the rule
     * under test, and asking it would make the sweep agree with itself. This
     * asks the world instead -- is the thing on the map, and is a square it
     * stands on lit -- which is what upstream's
     * {@code CUnit::IsVisibleInViewport} asks.
     */
    private static boolean showsToLocalPlayer(World world, int local, Unit unit) {
        UnitType type = unit.type();
        if (type.permanentCloak() && unit.player() != local) {
            // A submarine is meant not to be drawn, and there is no public way
            // to ask whether a detector is watching it. Left out rather than
            // reported wrongly either way.
            return false;
        }
        int width = Math.max(1, type.tileWidth());
        int height = Math.max(1, type.tileHeight());
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (world.isVisibleTo(local, unit.tileX() + x, unit.tileY() + y)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether a bar of some sort belongs under this unit. */
    private static boolean carriesADecoration(Unit unit, int local) {
        UnitType type = unit.type();
        if (unit.hitPoints() < type.hitPoints()) {
            return true;
        }
        if (unit.player() != local) {
            return false;
        }
        if (unit.producing() != null || unit.researching() != null
                || unit.upgradingTo() != null) {
            return true;
        }
        if (type.mana() > 0 && unit.isCaster() && unit.mana() < type.mana()) {
            return true;
        }
        return type.maxOnBoard() > 0 && !unit.cargo().isEmpty();
    }

    /** Where {@code drawUnit} puts the sprite, in world pixels. */
    static Rectangle spriteRectangle(Unit unit) {
        UnitType type = unit.type();
        int frameWidth = Math.max(1, type.imageWidth());
        int frameHeight = Math.max(1, type.imageHeight());
        int footprintWidth = Math.max(1, type.tileWidth()) * TILE;
        int footprintHeight = Math.max(1, type.tileHeight()) * TILE;
        return new Rectangle(
                unit.pixelX() + (footprintWidth - frameWidth) / 2,
                unit.pixelY() + (footprintHeight - frameHeight) / 2,
                frameWidth, frameHeight);
    }

    /**
     * The strip the bars live in: the bottom of the footprint, which is what
     * {@code OffsetPercent = {50, 100}} names, with room for both rows.
     */
    private static Rectangle decorationRectangle(Unit unit) {
        UnitType type = unit.type();
        int footprintWidth = Math.max(1, type.tileWidth()) * TILE;
        int footprintHeight = Math.max(1, type.tileHeight()) * TILE;
        return new Rectangle(unit.pixelX() - 2,
                unit.pixelY() + footprintHeight - 8,
                footprintWidth + 4, 16);
    }

    /** Every identifier some other type names as the thing it leaves behind. */
    static Set<String> corpseIdents(GameData data) {
        Set<String> idents = new java.util.LinkedHashSet<>();
        for (UnitType type : data.unitTypes().types().values()) {
            String corpse = type.corpse();
            if (corpse != null && !corpse.isEmpty()) {
                idents.add(corpse);
            }
        }
        return idents;
    }

    /**
     * The spells that put something in the air or on the ground.
     *
     * <p>Named rather than derived: a spell missile is an ordinary missile as
     * far as the data goes, and the only thing that separates the two is which
     * of them {@code scripts/spells.legacy-declaration} asks for.
     */
    /** {@code DefineBurningBuilding}'s two fires, {@code missiles.legacy-declaration:203}. */
    private static final Set<String> BURNING_MISSILES =
            Set.of("missile-big-fire", "missile-small-fire");

    private static final Set<String> SPELL_MISSILES = Set.of(
            "missile-fireball", "missile-blizzard", "missile-death-and-decay",
            "missile-heal-effect", "missile-exorcism", "missile-touch-of-death",
            "missile-rune", "missile-whirlwind", "missile-flame-shield",
            "missile-death-coil", "missile-normal-spell", "missile-holy-vision",
            "missile-eye-of-vision");

    /** How much fog art can land on a rectangle of the frame. */
    private static Cover coverOf(Scene scene, Rectangle where, int border) {
        FogOfWar fog = scene.world().fog();
        int local = scene.localPlayer();
        int fromX = (where.x + scene.screen().cameraX()) / TILE - border;
        int fromY = (where.y + scene.screen().cameraY()) / TILE - border;
        int toX = (where.x + scene.screen().cameraX() + where.width) / TILE + border;
        int toY = (where.y + scene.screen().cameraY() + where.height) / TILE + border;
        Cover worst = Cover.CLEAR;
        for (int y = fromY; y <= toY; y++) {
            for (int x = fromX; x <= toX; x++) {
                var visibility = fog.visibility(local, x, y);
                if (visibility == FogOfWar.Visibility.UNEXPLORED) {
                    return Cover.HIDDEN;
                }
                if (visibility == FogOfWar.Visibility.EXPLORED) {
                    worst = Cover.VEILED;
                }
            }
        }
        return worst;
    }

    // ------------------------------------------------------------------
    // The table
    // ------------------------------------------------------------------

    /**
     * Four missions, one staged fight each.
     *
     * <p>Sampled rather than exhaustive, for the reason
     * {@code AiCompetenceTest} gives about its own subset: a failure naming one
     * of four missions is easier to act on than a total over fifty-two, and
     * this measurement costs two full frames per candidate.
     */
    static final List<String> MISSIONS = List.of(
            "campaigns/human/level05h",
            "campaigns/human/level02h",
            "campaigns/orc/level03o",
            "campaigns/orc/level08o");

    public static void main(String[] args) {
        GameData data = data();
        if (data == null) {
            System.out.println("No Warcraft II installation configured. "
                    + "Set -Dwc2.install.dir=/path/to/game.");
            return;
        }
        Map<Category, Integer> totals = new TreeMap<>();
        List<Finding> everything = new ArrayList<>();
        for (String path : MISSIONS) {
            Scene scene = load(data, path);
            if (scene == null) {
                System.out.println("== " + path + ": will not load");
                continue;
            }
            Staged staged = stageABattle(scene);
            if (staged == null) {
                System.out.println("== " + path + ": nowhere to stage a fight");
                continue;
            }
            Report report = sweep(scene, staged, 300, 6);
            report.print();
            report.measured().forEach((category, count) -> totals.merge(category, count,
                    Integer::sum));
            everything.addAll(report.unexplained());
            System.out.println();
        }
        System.out.println("Measured in total: " + totals);
        System.out.println("Unexplained blanks: " + everything.size());
        for (Finding finding : everything) {
            System.out.println("   " + finding);
        }
    }
}
