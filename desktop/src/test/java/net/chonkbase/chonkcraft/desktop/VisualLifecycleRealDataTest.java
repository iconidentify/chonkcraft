package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.chonkbase.chonkcraft.data.graphic.IndexedImage;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.missile.Missile;
import net.chonkbase.chonkcraft.engine.missile.MissileType;
import net.chonkbase.chonkcraft.engine.unit.SpriteFrame;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A catalog-wide presentation contract against the real BNE media.
 *
 * <p>This is deliberately wider than a campaign screenshot.  A fixture only
 * sees the units that happen to be alive during its short opening window; a
 * missing death pose, a dead tower shot or a mirrored naval missile can stay
 * invisible until a player reaches the one map that uses it.  This gate walks
 * the complete player-facing roster and the complete projectile family.
 *
 * <p>The direction oracle is BNE itself.  The authenticated 52-case state
 * corpus contains 984 projectile lifetimes.  Of the 638 births with a non-zero
 * aim, projectile byte {@code +0x0a} uses all values {@code 0..7} and never
 * value {@code 8}.  The legacy declaration's nine entries are therefore the
 * eight compass headings plus the repeated endpoint, not nine equally sized
 * sectors.  Each directional family below is launched along all eight vectors
 * and its resolved stored cell and mirror are checked.
 */
class VisualLifecycleRealDataTest {

    private static final String TILESET = "summer";
    private static final int TRANSPARENT = 255;
    private static final Set<String> BNE_INTENTIONAL_VANISH = Set.of(
            // LetUnitDie 0x004514c0: type 0x28 has flags 0x82 and takes
            // neither the bit-2 effect arm nor the explicit type-0x29 arm.
            "unit-balloon",
            // Eye of Kilrogg has no body/effect and is released by its own
            // short-lived summon lifecycle.
            "unit-eye-of-vision");
    private static final Set<AnimationSet.State> LIFECYCLE_STATES = EnumSet.of(
            AnimationSet.State.STILL,
            AnimationSet.State.MOVE,
            AnimationSet.State.ATTACK,
            AnimationSet.State.DEATH,
            AnimationSet.State.HARVEST,
            AnimationSet.State.REPAIR);

    private enum TerminalVisual {
        DEATH_FRAMES, CORPSE, EFFECT, VANISH
    }

    private static GameData data() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No BNE media. Set -Dchonkcraft.pack=/path/to/BNE.chonkpack.");
        return new GameData(assets);
    }

    private static List<UnitType> lifecycleTypes(GameData data) {
        Set<String> corpseTypes = new HashSet<>();
        for (UnitType type : data.unitTypes().types().values()) {
            if (!type.corpse().isEmpty()) {
                corpseTypes.add(type.corpse());
            }
        }
        return data.unitTypes().types().values().stream()
                .filter(UnitType::hasGraphics)
                .filter(type -> type.animationSet() != null)
                .filter(type -> type.hitPoints() > 0)
                .filter(type -> !type.revealer() && !type.vanishes())
                .filter(type -> !corpseTypes.contains(type.ident()))
                .filter(type -> !type.ident().endsWith("start-location"))
                .toList();
    }

    @Test
    @DisplayName("every gameplay sprite state names visible in-bounds pixels")
    void everyGameplaySpriteStateNamesVisibleInBoundsPixels() {
        GameData data = data();
        List<UnitType> types = lifecycleTypes(data);
        assertTrue(types.size() >= 80,
                "the lifecycle discovery collapsed to " + types.size() + " types");

        List<String> failures = new ArrayList<>();
        int states = 0;
        int frames = 0;
        for (UnitType type : types) {
            IndexedImage sheet = data.sprite(type.imageFileFor(TILESET));
            if (sheet == null) {
                failures.add(type.ident() + " has no " + TILESET + " sprite");
                continue;
            }
            for (AnimationSet.State state : type.animationSet().states()) {
                // Shared building sets contain Train/Research/Upgrade programs
                // even for special buildings that can never enter those
                // states. The lifecycle gate drives states an on-map actor can
                // actually enter; production-button tests own capability
                // reachability for the others.
                if (!LIFECYCLE_STATES.contains(state)) {
                    continue;
                }
                Animation animation = type.animationSet().get(state);
                if (animation == null) {
                    continue;
                }
                states++;
                for (Animation.Instruction instruction : animation.instructions()) {
                    if (instruction.kind() != Animation.Kind.FRAME) {
                        continue;
                    }
                    frames++;
                    for (int heading = 0; heading < type.numDirections(); heading++) {
                        FramePixels pixels = framePixels(sheet, type.imageWidth(), type.imageHeight(),
                                instruction.value(), heading, type.numDirections());
                        if (!pixels.inBounds()) {
                            failures.add(type.ident() + " " + state + " frame "
                                    + instruction.value() + " heading " + heading
                                    + " leaves " + sheet.width() + "x" + sheet.height());
                        } else if (pixels.opaque() == 0) {
                            failures.add(type.ident() + " " + state + " frame "
                                    + instruction.value() + " heading " + heading
                                    + " is fully transparent");
                        }
                    }
                }
            }
        }
        assertTrue(states >= 200, "only " + states + " animation states were exercised");
        assertTrue(frames >= 400, "only " + frames + " frame instructions were exercised");
        assertTrue(failures.isEmpty(), "invalid catalog frames: " + failures);
        System.out.printf("visual-lifecycle sprites: types=%d states=%d frame-instructions=%d%n",
                types.size(), states, frames);
    }

    @Test
    @DisplayName("every movable and attacking type creates a visible screen change")
    void everyMovableAndAttackingTypeCreatesAVisibleScreenChange() {
        GameData data = data();
        List<String> failures = new ArrayList<>();
        int movers = 0;
        int attackers = 0;

        for (UnitType type : lifecycleTypes(data)) {
            Animation move = type.animationSet().get(AnimationSet.State.MOVE);
            if (type.speed() > 0 && move != null) {
                movers++;
                boolean changesPosition = move.instructions().stream()
                        .anyMatch(instruction -> instruction.kind() == Animation.Kind.MOVE
                                && instruction.value() != 0);
                if (!changesPosition) {
                    failures.add(type.ident() + " MOVE never changes a pixel position");
                }
                validateStatePixels(data, type, AnimationSet.State.MOVE, failures);
                exerciseMove(data, type, failures);
            }

            if (!type.canAttack()) {
                continue;
            }
            attackers++;
            Animation attack = type.animationSet().get(AnimationSet.State.ATTACK);
            if (attack == null) {
                failures.add(type.ident() + " can attack but has no ATTACK animation");
                continue;
            }
            boolean strikes = attack.instructions().stream()
                    .anyMatch(instruction -> instruction.kind() == Animation.Kind.ATTACK);
            if (!strikes) {
                failures.add(type.ident() + " ATTACK never reaches an attack instruction");
            }
            Set<Long> poses = stateSignatures(data, type, AnimationSet.State.ATTACK, failures);
            boolean visibleProjectile = false;
            if (type.firesMissile()) {
                MissileType missile = data.missiles().get(type.missile());
                visibleProjectile = missile != null && missile.sprite() != null
                        && data.sprite(missile.sprite()) != null;
                if (!visibleProjectile) {
                    failures.add(type.ident() + " fires missing/invisible " + type.missile());
                }
            }
            if (poses.size() < 2 && !visibleProjectile) {
                failures.add(type.ident()
                        + " attack changes neither its pose nor any projectile pixels");
            }
            exerciseAttack(data, type, failures);
        }

        assertTrue(movers >= 45, "only " + movers + " movable types were exercised");
        assertTrue(attackers >= 45, "only " + attackers + " attackers were exercised");
        assertTrue(failures.isEmpty(), "inert visual lifecycles: " + failures);
        System.out.printf("visual-lifecycle actions: movers=%d attackers=%d%n",
                movers, attackers);
    }

    @Test
    @DisplayName("every killable type reaches a BNE-classified terminal visual")
    void everyKillableTypeReachesAClassifiedTerminalVisual() {
        GameData data = data();
        List<String> failures = new ArrayList<>();
        int killed = 0;
        int corpses = 0;
        int effects = 0;
        int vanishes = 0;

        for (UnitType type : lifecycleTypes(data)) {
            if (type.indestructible()) {
                continue;
            }
            killed++;
            EnumSet<TerminalVisual> expected = terminalVisuals(type);
            if (expected.isEmpty()) {
                failures.add(type.ident() + " has no death frames, corpse, effect, or BNE vanish");
                continue;
            }
            if (expected.contains(TerminalVisual.CORPSE)) corpses++;
            if (expected.contains(TerminalVisual.EFFECT)) effects++;
            if (expected.contains(TerminalVisual.VANISH)) vanishes++;

            World world = visualWorld(data);
            Unit unit = world.createUnit(type, 0, 12, 12);
            assertNotNull(unit, "could not create " + type.ident());
            world.kill(unit);

            boolean sawEffect = world.missiles().stream()
                    .anyMatch(missile -> missile.type().ident().equals(type.explosion()));
            boolean sawCorpse = false;
            boolean sawVisibleDeathFrame = false;
            for (int cycle = 0; cycle < 1800; cycle++) {
                if (unit.type() != type && unit.type().ident().equals(type.corpse())) {
                    sawCorpse = true;
                }
                if (unit.type() == type && unit.isDying() && !unit.removed()) {
                    IndexedImage sheet = data.sprite(type.imageFileFor(TILESET));
                    if (sheet != null) {
                        FramePixels pixels = framePixels(sheet, type.imageWidth(), type.imageHeight(),
                                unit.frame(), unit.direction() / 32, type.numDirections());
                        sawVisibleDeathFrame |= pixels.inBounds() && pixels.opaque() > 0;
                    }
                }
                sawEffect |= world.missiles().stream()
                        .anyMatch(missile -> missile.type().ident().equals(type.explosion()));
                boolean deathSeen = !expected.contains(TerminalVisual.DEATH_FRAMES)
                        || sawVisibleDeathFrame;
                boolean corpseSeen = !expected.contains(TerminalVisual.CORPSE) || sawCorpse;
                boolean effectSeen = !expected.contains(TerminalVisual.EFFECT) || sawEffect;
                boolean vanishSeen = !expected.contains(TerminalVisual.VANISH) || unit.removed();
                if (deathSeen && corpseSeen && effectSeen && vanishSeen) {
                    break;
                }
                world.tick();
            }

            if (expected.contains(TerminalVisual.DEATH_FRAMES) && !sawVisibleDeathFrame) {
                failures.add(type.ident() + " never displayed its death frames");
            }
            if (expected.contains(TerminalVisual.CORPSE) && !sawCorpse) {
                failures.add(type.ident() + " never became corpse " + type.corpse());
            }
            if (expected.contains(TerminalVisual.EFFECT) && !sawEffect) {
                failures.add(type.ident() + " never spawned effect " + type.explosion());
            }
            if (expected.contains(TerminalVisual.VANISH) && !unit.removed()) {
                failures.add(type.ident() + " did not complete its intentional vanish");
            }
        }

        assertTrue(killed >= 70, "only " + killed + " killable types were exercised");
        assertTrue(corpses >= 20, "corpse classification collapsed to " + corpses);
        assertTrue(effects >= 10, "effect classification collapsed to " + effects);
        assertTrue(vanishes >= 1, "the BNE vanish class was not exercised");
        assertTrue(failures.isEmpty(), "broken terminal lifecycles: " + failures);
        System.out.printf("visual-lifecycle deaths: killed=%d corpse=%d effect=%d vanish=%d%n",
                killed, corpses, effects, vanishes);
    }

    @Test
    @DisplayName("every directional projectile family follows BNE's eight compass poses")
    void everyDirectionalProjectileFamilyFollowsTheBneCompass() {
        GameData data = data();
        Set<String> used = new LinkedHashSet<>();
        for (UnitType type : lifecycleTypes(data)) {
            if (type.canAttack() && type.firesMissile()) {
                used.add(type.missile());
            }
        }
        // Include spell and bounce families that are player-visible but are
        // not named by a unit's basic attack field.
        for (MissileType type : data.missiles().types().values()) {
            if (type.directions() == 9 && type.sprite() != null) {
                used.add(type.ident());
            }
        }
        used.removeIf(ident -> {
            MissileType type = data.missiles().get(ident);
            return type == null || type.directions() != 9 || type.sprite() == null;
        });

        assertTrue(used.size() >= 16,
                "directional projectile discovery collapsed to " + used);
        assertTrue(used.containsAll(Set.of("missile-arrow", "missile-arrow-super",
                "missile-big-cannon", "missile-small-cannon",
                "missile-small-cannon-super", "missile-ballista-bolt")),
                "tower/siege projectile families were not all discovered: " + used);

        int[][] vectors = {
            {0, -96}, {96, -96}, {96, 0}, {96, 96},
            {0, 96}, {-96, 96}, {-96, 0}, {-96, -96}
        };
        SpriteFrame.Resolved[] expectedCells = {
            new SpriteFrame.Resolved(0, false),
            new SpriteFrame.Resolved(1, false),
            new SpriteFrame.Resolved(2, false),
            new SpriteFrame.Resolved(3, false),
            new SpriteFrame.Resolved(4, false),
            new SpriteFrame.Resolved(3, true),
            new SpriteFrame.Resolved(2, true),
            new SpriteFrame.Resolved(1, true)
        };

        List<String> failures = new ArrayList<>();
        for (String ident : used) {
            MissileType type = data.missiles().get(ident);
            if (type == null || type.directions() != 9) {
                continue;
            }
            IndexedImage sheet = data.sprite(type.sprite());
            if (sheet == null) {
                failures.add(ident + " has no sprite " + type.sprite());
                continue;
            }
            if (type.headingCount() != 8 || type.storedFacings() != 5) {
                failures.add(ident + " resolves " + type.headingCount()
                        + " headings into " + type.storedFacings() + " stored cells");
                continue;
            }
            for (int expected = 0; expected < vectors.length; expected++) {
                int[] vector = vectors[expected];
                Missile shot = new Missile(type, null, null,
                        256, 256, 256 + vector[0], 256 + vector[1]);
                if (shot.direction() != expected) {
                    failures.add(ident + " vector " + vector[0] + "," + vector[1]
                            + " chose facing " + shot.direction() + " instead of " + expected);
                }
                SpriteFrame.Resolved resolved = GameScreen.missileSpriteFrame(shot);
                SpriteFrame.Resolved wanted = expectedCells[expected];
                if (!resolved.equals(wanted)) {
                    failures.add(ident + " facing " + expected + " resolved " + resolved
                            + " instead of BNE cell " + wanted);
                }
                FramePixels pixels = framePixels(sheet, type.frameWidth(), type.frameHeight(),
                        resolved.index(), 0, 1);
                if (!pixels.inBounds() || pixels.opaque() == 0) {
                    failures.add(ident + " BNE cell " + resolved + " has no visible pixels");
                }
            }
        }
        assertTrue(failures.isEmpty(), "projectile orientation failures: " + failures);
        System.out.printf("visual-lifecycle projectiles: directional-families=%d vectors=%d%n",
                used.size(), used.size() * vectors.length);
    }

    private static EnumSet<TerminalVisual> terminalVisuals(UnitType type) {
        EnumSet<TerminalVisual> result = EnumSet.noneOf(TerminalVisual.class);
        Animation death = type.animationSet().get(AnimationSet.State.DEATH);
        if (death != null && death.instructions().stream()
                .anyMatch(instruction -> instruction.kind() == Animation.Kind.FRAME)) {
            result.add(TerminalVisual.DEATH_FRAMES);
        }
        if (!type.corpse().isEmpty()) result.add(TerminalVisual.CORPSE);
        if (!type.explosion().isEmpty()) result.add(TerminalVisual.EFFECT);
        if (BNE_INTENTIONAL_VANISH.contains(type.ident())) result.add(TerminalVisual.VANISH);
        return result;
    }

    private static World visualWorld(GameData data) {
        Tileset tileset = new Tileset();
        long open = TileFlag.LAND_ALLOWED | TileFlag.COAST_ALLOWED | TileFlag.WATER_ALLOWED;
        tileset.setTile(1, new Tileset.Tile(1, open, 1, 0));
        GameMap map = new GameMap(32, 32, tileset);
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(open);
            }
        }
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i,
                    i < 2 ? PudMap.PlayerType.PERSON : PudMap.PlayerType.NOBODY,
                    PudMap.Race.HUMAN);
        }
        World world = new World(map, players);
        world.setUnitTypes(data.unitTypes().types());
        world.setMissileTypes(data.missiles().types());
        world.setUpgrades(data.upgrades().upgrades());
        return world;
    }

    private static void exerciseMove(GameData data, UnitType type, List<String> failures) {
        World world = visualWorld(data);
        Unit unit = world.createUnit(type, 0, 8, 8);
        if (unit == null) {
            failures.add(type.ident() + " could not be created for MOVE");
            return;
        }
        int startX = unit.pixelX();
        int startY = unit.pixelY();
        if (!world.orderMove(unit, 16, 8)) {
            failures.add(type.ident() + " rejected a valid MOVE order");
            return;
        }
        boolean positionChanged = false;
        for (int cycle = 0; cycle < 600; cycle++) {
            world.tick();
            positionChanged |= unit.pixelX() != startX || unit.pixelY() != startY;
            if (positionChanged) {
                break;
            }
        }
        if (!positionChanged) {
            failures.add(type.ident() + " accepted MOVE but never changed screen position");
        }
    }

    private static void exerciseAttack(GameData data, UnitType type, List<String> failures) {
        World world = visualWorld(data);
        Unit attacker = world.createUnit(type, 0, 8, 10);
        if (attacker == null) {
            failures.add(type.ident() + " could not be created for ATTACK");
            return;
        }
        UnitType targetType = targetType(data, type);
        if (targetType == null) {
            failures.add(type.ident() + " has no compatible target class");
            return;
        }
        int gap = Math.max(1, type.minAttackRange());
        int targetX = 8 + Math.max(1, type.tileWidth()) + gap;
        Unit target = world.createUnit(targetType, 1, targetX, 10);
        if (target == null) {
            failures.add(type.ident() + " target could not be created");
            return;
        }
        int startFrame = attacker.frame();
        int startHp = target.hitPoints();
        if (!world.orderAttack(attacker, target)) {
            failures.add(type.ident() + " rejected ATTACK against " + targetType.ident());
            return;
        }

        boolean poseChanged = false;
        boolean projectileSeen = false;
        boolean damageSeen = false;
        for (int cycle = 0; cycle < 1200; cycle++) {
            world.tick();
            poseChanged |= attacker.frame() != startFrame;
            projectileSeen |= !world.missiles().isEmpty();
            damageSeen |= target.hitPoints() < startHp || target.isDying();
            if (damageSeen && (poseChanged || projectileSeen)) {
                break;
            }
        }
        if (!damageSeen) {
            failures.add(type.ident() + " ATTACK never struck " + targetType.ident());
        }
        if (!poseChanged && !projectileSeen) {
            failures.add(type.ident() + " ATTACK produced no pose or projectile pixels");
        }
    }

    private static UnitType targetType(GameData data, UnitType attacker) {
        if (attacker.canTargetLand()) {
            return data.unitTypes().types().get("unit-footman");
        }
        if (attacker.canTargetSea()) {
            return data.unitTypes().types().get("unit-human-destroyer");
        }
        if (attacker.canTargetAir()) {
            return data.unitTypes().types().get("unit-gryphon-rider");
        }
        return null;
    }

    private static void validateStatePixels(GameData data, UnitType type,
            AnimationSet.State state, List<String> failures) {
        stateSignatures(data, type, state, failures);
    }

    private static Set<Long> stateSignatures(GameData data, UnitType type,
            AnimationSet.State state, List<String> failures) {
        Set<Long> signatures = new HashSet<>();
        IndexedImage sheet = data.sprite(type.imageFileFor(TILESET));
        if (sheet == null) {
            failures.add(type.ident() + " has no " + TILESET + " sprite");
            return signatures;
        }
        Animation animation = type.animationSet().get(state);
        for (Animation.Instruction instruction : animation.instructions()) {
            if (instruction.kind() != Animation.Kind.FRAME) {
                continue;
            }
            FramePixels pixels = framePixels(sheet, type.imageWidth(), type.imageHeight(),
                    instruction.value(), 0, type.numDirections());
            if (!pixels.inBounds() || pixels.opaque() == 0) {
                failures.add(type.ident() + " " + state + " frame " + instruction.value()
                        + " is outside or transparent");
            } else {
                signatures.add(pixels.signature());
            }
        }
        return signatures;
    }

    private record FramePixels(boolean inBounds, int opaque, long signature) {}

    private static FramePixels framePixels(IndexedImage sheet, int width, int height,
            int baseFrame, int heading, int directions) {
        width = Math.max(1, width);
        height = Math.max(1, height);
        int columns = sheet.width() / width;
        int rows = sheet.height() / height;
        if (columns <= 0 || rows <= 0) {
            return new FramePixels(false, 0, 0);
        }
        SpriteFrame.Resolved resolved = SpriteFrame.resolve(baseFrame, heading, directions);
        if (resolved.index() < 0 || resolved.index() >= columns * rows) {
            return new FramePixels(false, 0, 0);
        }
        int sourceX = (resolved.index() % columns) * width;
        int sourceY = (resolved.index() / columns) * height;
        if (sourceX + width > sheet.width() || sourceY + height > sheet.height()) {
            return new FramePixels(false, 0, 0);
        }
        int opaque = 0;
        long hash = 0xcbf29ce484222325L;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int value = sheet.get(sourceX + x, sourceY + y);
                if (value != TRANSPARENT) {
                    opaque++;
                }
                // Include transparency and position: two poses with the same
                // colours in different places are a visible pixel change.
                hash ^= value + 257L * x + 65537L * y;
                hash *= 0x100000001b3L;
            }
        }
        return new FramePixels(true, opaque, hash);
    }
}
