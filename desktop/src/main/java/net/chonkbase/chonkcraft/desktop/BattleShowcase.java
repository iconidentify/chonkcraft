package net.chonkbase.chonkcraft.desktop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.MapField;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;

/** Builds a deterministic, camera-ready battle from retail game units. */
final class BattleShowcase {

    static final int DEFAULT_UNITS = 720;
    static final int MIN_UNITS = 80;
    static final int MAX_UNITS = 1_600;
    private static final int DIRECTOR_INTERVAL = 90;

    private static final int NAVAL_DIVISOR = 5;
    private static final int SPELL_WAVE_INTERVAL = 240;

    /* Every ordinary mobile combat class is represented on its real terrain. */
    private static final List<String> HUMAN_LAND_ROSTER = List.of(
            "unit-footman", "unit-footman", "unit-footman", "unit-footman",
            "unit-knight", "unit-knight", "unit-paladin",
            "unit-archer", "unit-archer", "unit-ranger",
            "unit-ballista", "unit-dwarves", "unit-mage",
            "unit-gryphon-rider", "unit-peasant", "unit-balloon");
    private static final List<String> ORC_LAND_ROSTER = List.of(
            "unit-grunt", "unit-grunt", "unit-grunt", "unit-grunt",
            "unit-ogre", "unit-ogre", "unit-ogre-mage",
            "unit-axethrower", "unit-axethrower", "unit-berserker",
            "unit-catapult", "unit-goblin-sappers", "unit-death-knight",
            "unit-skeleton", "unit-dragon", "unit-peon", "unit-zeppelin",
            "unit-eye-of-vision");
    private static final List<String> HUMAN_NAVAL_ROSTER = List.of(
            "unit-battleship", "unit-battleship", "unit-battleship",
            "unit-human-destroyer", "unit-human-destroyer", "unit-human-destroyer",
            "unit-human-submarine", "unit-human-submarine",
            "unit-human-transport", "unit-human-oil-tanker");
    private static final List<String> ORC_NAVAL_ROSTER = List.of(
            "unit-ogre-juggernaught", "unit-ogre-juggernaught",
            "unit-ogre-juggernaught", "unit-orc-destroyer", "unit-orc-destroyer",
            "unit-orc-destroyer", "unit-orc-submarine", "unit-orc-submarine",
            "unit-orc-transport", "unit-orc-oil-tanker");

    static final Set<String> HUMAN_FIELD_TYPES = fieldTypes(
            HUMAN_LAND_ROSTER, HUMAN_NAVAL_ROSTER);
    static final Set<String> ORC_FIELD_TYPES = fieldTypes(
            ORC_LAND_ROSTER, ORC_NAVAL_ROSTER);

    private BattleShowcase() {
    }

    static boolean requested() {
        String property = System.getProperty("chonkcraft.showcase");
        String value = property == null || property.isBlank()
                ? System.getenv("CHONKCRAFT_SHOWCASE") : property;
        return value != null && !value.isBlank()
                && !"0".equals(value) && !"false".equalsIgnoreCase(value);
    }

    static int requestedUnits() {
        String property = System.getProperty("chonkcraft.showcase.units");
        String value = property == null || property.isBlank()
                ? System.getenv("CHONKCRAFT_SHOWCASE_UNITS") : property;
        try {
            int requested = value == null || value.isBlank()
                    ? DEFAULT_UNITS : Integer.parseInt(value.trim());
            return Math.max(MIN_UNITS, Math.min(MAX_UNITS, requested));
        } catch (NumberFormatException ignored) {
            return DEFAULT_UNITS;
        }
    }

    /** A large retail arena whose tileset supplies authentic land and water art. */
    static String defaultMapName(AssetSource assets) {
        String[] preferred = {
            "ladder/Garden of war BNE.pud",
            "Classic/Garden of War.pud",
            "-8 Garden of War.pud"
        };
        for (String wanted : preferred) {
            for (String candidate : assets.mapNames()) {
                if (candidate.equalsIgnoreCase(wanted)) {
                    return candidate;
                }
            }
        }
        for (String candidate : assets.mapNames()) {
            String normal = candidate.toLowerCase(java.util.Locale.ROOT)
                    .replace('-', ' ').replace('_', ' ')
                    .replaceAll("\\s+", " ");
            if (normal.contains("garden of war")) {
                return candidate;
            }
        }
        return assets.mapNames().isEmpty() ? null : assets.mapNames().getFirst();
    }

    /**
     * Replaces a skirmish opening with two total-war armies and sends them
     * through the ordinary BNE command machinery. No combat shortcut or
     * scripted damage is used: land and naval pathing, acquisition,
     * projectiles, spells, deaths and rendering are the normal game systems.
     */
    static Result deploy(World world, Map<String, UnitType> types, int requested) {
        int wanted = Math.max(MIN_UNITS, Math.min(MAX_UNITS, requested));
        for (Unit unit : List.copyOf(world.unitsSnapshot())) {
            world.remove(unit);
        }
        Arena arena = sculptArena(world);

        Player human = world.player(0);
        Player orc = world.player(1);
        if (human == null || orc == null) {
            throw new IllegalStateException("battle showcase needs player slots zero and one");
        }
        human.setType(net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.PERSON);
        orc.setType(net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER);

        int humanWanted = (wanted + 1) / 2;
        int orcWanted = wanted / 2;
        int humanNavalWanted = Math.max(HUMAN_NAVAL_ROSTER.size(),
                humanWanted / NAVAL_DIVISOR);
        int orcNavalWanted = Math.max(ORC_NAVAL_ROSTER.size(),
                orcWanted / NAVAL_DIVISOR);

        List<Unit> humanLand = placeArmy(world, types, 0, HUMAN_LAND_ROSTER,
                landFormationCells(world, arena, true), humanWanted - humanNavalWanted);
        List<Unit> orcLand = placeArmy(world, types, 1, ORC_LAND_ROSTER,
                landFormationCells(world, arena, false), orcWanted - orcNavalWanted);
        List<Unit> humanNaval = placeArmy(world, types, 0, HUMAN_NAVAL_ROSTER,
                navalFormationCells(world, arena, true), humanNavalWanted);
        List<Unit> orcNaval = placeArmy(world, types, 1, ORC_NAVAL_ROSTER,
                navalFormationCells(world, arena, false), orcNavalWanted);

        List<Unit> left = joined(humanLand, humanNaval);
        List<Unit> right = joined(orcLand, orcNaval);
        List<Unit> all = joined(left, right);

        if (left.size() < MIN_UNITS / 2 || right.size() < MIN_UNITS / 2) {
            throw new IllegalStateException("map has too little open ground for the showcase");
        }

        world.fireBattleNetReadyForAll();
        revealForShowcase(world);
        orderCharge(world, humanLand, orcLand);
        orderCharge(world, orcLand, humanLand);
        orderCharge(world, humanNaval, orcNaval);
        orderCharge(world, orcNaval, humanNaval);
        int openingSpells = armMagic(world, humanLand, orcLand)
                + armMagic(world, orcLand, humanLand);
        world.recalculateSupply();

        return new Result(wanted, all.size(), left.size(), right.size(),
                humanLand.size() + orcLand.size(), humanNaval.size() + orcNaval.size(),
                openingSpells, arena.centreX(), arena.centreY(), List.copyOf(all));
    }

    /** Permanent map-wide sight and detection for this recording scene only. */
    private static void revealForShowcase(World world) {
        int width = world.map().width();
        int height = world.map().height();
        for (int player : new int[] {0, 1}) {
            world.fog().addSight(player, 0, 0, width, height, 0);
            world.fog().addDetection(player, 0, 0, width, height, 0);
        }
    }

    /**
     * Restores the original showcase composition: one huge open land arena in
     * the middle of Garden of War. The formerly empty perimeter becomes an
     * ocean, leaving the centre free for the wall of infantry while fleets
     * collide along its top and bottom edges. The source map and pack are
     * never modified.
     */
    private static Arena sculptArena(World world) {
        GameMap map = world.map();
        Map<Integer, Integer> groundFrequency = new HashMap<>();
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                MapField field = map.field(x, y);
                long flags = field.flags();
                if (field.isLandPassable()
                        && (flags & (TileFlag.FOREST | TileFlag.ROCKS
                                | TileFlag.WALL | TileFlag.COAST_ALLOWED
                                | TileFlag.UNPASSABLE)) == 0) {
                    groundFrequency.merge(field.tile(), 1, Integer::sum);
                }
            }
        }
        if (groundFrequency.isEmpty()) {
            throw new IllegalStateException("showcase map has no ordinary open ground");
        }
        int groundTile = groundFrequency.entrySet().stream()
                .max(Map.Entry.<Integer, Integer>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey()))
                .orElseThrow().getKey();
        List<Integer> waterTiles = new ArrayList<>();
        for (int code = 0; code < map.tileset().tileCount(); code++) {
            var tile = map.tileset().tile(code);
            if (tile.isDefined() && tile.baseTerrain() != 0 && tile.mixTerrain() == 0
                    && (tile.flags() & TileFlag.WATER_ALLOWED) != 0
                    && (tile.flags() & (TileFlag.COAST_ALLOWED
                            | TileFlag.UNPASSABLE)) == 0) {
                waterTiles.add(code);
            }
        }
        if (waterTiles.isEmpty()) {
            throw new IllegalStateException("showcase tileset has no open-water picture");
        }
        long cleared = GameMap.OCCUPANCY_FLAGS | TileFlag.FOREST | TileFlag.ROCKS
                | TileFlag.WALL | TileFlag.UNPASSABLE | TileFlag.OPAQUE
                | TileFlag.SUBTILES_UNPASSABLE_MASK | TileFlag.LAND_ALLOWED
                | TileFlag.COAST_ALLOWED | TileFlag.WATER_ALLOWED;
        long groundFlags = (map.tileset().flagsFor(groundTile) & ~cleared)
                | TileFlag.LAND_ALLOWED;
        long waterFlags = (map.tileset().flagsFor(waterTiles.getFirst()) & ~cleared)
                | TileFlag.WATER_ALLOWED;
        int margin = Math.max(16, Math.min(24, Math.min(map.width(), map.height()) / 4));
        int minX = margin;
        int maxX = map.width() - margin - 1;
        int minY = margin;
        int maxY = map.height() - margin - 1;
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                boolean water = x < minX || x > maxX || y < minY || y > maxY;
                MapField field = map.field(x, y);
                field.setTile(water
                        ? waterTiles.get(Math.floorMod(x * 31 + y * 17, waterTiles.size()))
                        : groundTile);
                field.setFlags(water ? waterFlags : groundFlags);
                field.setValue(0);
            }
        }
        map.recordLoadedTerrain();
        return new Arena(map.width() / 2, map.height() / 2,
                minX, maxX, minY, maxY);
    }

    /** Starts one synchronized charge using only ordinary player orders. */
    private static void orderCharge(World world, List<Unit> army, List<Unit> enemies) {
        for (Unit unit : army) {
            Unit nearest = nearestDomainEnemy(unit, enemies);
            if (unit.type().canAttack()) {
                // Spread opening targets across the opposing formation. A
                // single attack-move destination made hundreds of units
                // converge on the same square and left the rear ranks
                // technically moving but visibly idle behind the jam.
                List<Unit> nearestTargets = enemies.stream()
                        .filter(enemy -> enemy.isAlive() && enemy.isOnMap())
                        .sorted(java.util.Comparator.comparingInt(unit::distanceTo))
                        .toList();
                if (!attackFirstCompatible(world, unit, nearestTargets, 0)) {
                    if (nearest != null) {
                        world.orderAttackMove(unit, nearest.tileX(), nearest.tileY());
                    }
                }
            } else {
                // Flying machines and zeppelins cannot attack. They still
                // join the charge as scouts rather than remaining statues.
                if (nearest != null) {
                    world.orderMove(unit, nearest.tileX(), nearest.tileY());
                }
            }
        }
    }

    private static boolean attackFirstCompatible(World world, Unit attacker,
            List<Unit> enemies, int offset) {
        if (enemies.isEmpty()) {
            return false;
        }
        for (int step = 0; step < enemies.size(); step++) {
            Unit target = enemies.get((offset + step) % enemies.size());
            if (target.isAlive() && target.isOnMap()
                    && sameBattleDomain(attacker, target)
                    && world.orderAttack(attacker, target)) {
                return true;
            }
        }
        // Flyers and capital ships can legally reach across the shoreline.
        // Keep that capability as a fallback after preferring a target whose
        // position the attacker can actually navigate to.
        for (int step = 0; step < enemies.size(); step++) {
            Unit target = enemies.get((offset + step) % enemies.size());
            if (target.isAlive() && target.isOnMap()
                    && world.orderAttack(attacker, target)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameBattleDomain(Unit unit, Unit target) {
        UnitType.Movement movement = unit.type().moveType();
        if (movement == UnitType.Movement.FLY) {
            return true;
        }
        boolean naval = movement == UnitType.Movement.NAVAL;
        return naval == (target.type().moveType() == UnitType.Movement.NAVAL);
    }

    /** Arms real retail spells and launches the first synchronized volley. */
    private static int armMagic(World world, List<Unit> army, List<Unit> enemies) {
        String[] human = {"spell-blizzard", "spell-fireball"};
        String[] orc = {"spell-death-and-decay", "spell-whirlwind", "spell-death-coil"};
        for (String ident : army.getFirst().player() == 0 ? human : orc) {
            var spell = world.spells() == null ? null : world.spells().get(ident);
            if (spell != null && !spell.dependUpgrade().isEmpty()) {
                world.upgrades(army.getFirst().player()).complete(spell.dependUpgrade());
            }
        }
        int cast = 0;
        int spellIndex = 0;
        for (Unit unit : army) {
            String[] choices;
            if ("unit-mage".equals(unit.type().ident())) {
                choices = human;
            } else if ("unit-death-knight".equals(unit.type().ident())) {
                choices = orc;
            } else {
                continue;
            }
            unit.setMana(unit.type().mana());
            Unit target = nearestDomainEnemy(unit, enemies);
            if (target != null && orderShowcaseSpell(
                    world, unit, choices[spellIndex++ % choices.length], target)) {
                cast++;
            }
        }
        return cast;
    }

    private static boolean orderShowcaseSpell(
            World world, Unit caster, String spellIdent, Unit target) {
        var spell = world.spells() == null ? null : world.spells().get(spellIdent);
        if (spell == null || caster.mana() < spell.manaCost()) {
            return false;
        }
        return world.orderCast(caster, spellIdent, target.tileX(), target.tileY());
    }

    private static Unit nearestDomainEnemy(Unit unit, List<Unit> enemies) {
        return enemies.stream()
                .filter(enemy -> enemy.isAlive() && enemy.isOnMap())
                .filter(enemy -> sameBattleDomain(unit, enemy))
                .min(java.util.Comparator.comparingInt(unit::distanceTo))
                .orElse(null);
    }

    private static List<Unit> placeArmy(World world, Map<String, UnitType> types,
            int player, List<String> roster, List<int[]> cells, int wanted) {
        List<Unit> placed = new ArrayList<>();
        Set<String> missing = new LinkedHashSet<>(roster);
        int cursor = 0;
        for (int index = 0; index < wanted; index++) {
            String ident = index < roster.size()
                    ? roster.get(index) : roster.get(index % roster.size());
            UnitType type = required(types, ident);
            Unit made = null;
            while (cursor < cells.size() && made == null) {
                int[] cell = cells.get(cursor++);
                if (!fits(world, type, cell[0], cell[1])) {
                    continue;
                }
                made = world.createUnit(type, player, cell[0], cell[1]);
            }
            if (made == null) {
                break;
            }
            placed.add(made);
            missing.remove(ident);
        }
        if (wanted >= roster.size() && !missing.isEmpty()) {
            throw new IllegalStateException("showcase could not stage " + missing);
        }
        return placed;
    }

    private static boolean fits(World world, UnitType type, int x, int y) {
        return world.map().isFootprintFree(x, y,
                Math.max(1, type.tileWidth()), Math.max(1, type.tileHeight()),
                Unit.movementMaskFor(type), Unit.blockingFlagsFor(type));
    }

    private static UnitType required(Map<String, UnitType> types, String ident) {
        UnitType type = types.get(ident);
        if (type == null) {
            throw new IllegalStateException("asset pack has no " + ident);
        }
        return type;
    }

    /** Front ranks first, filling the same central collision as the original. */
    private static List<int[]> landFormationCells(
            World world, Arena arena, boolean left) {
        List<int[]> cells = new ArrayList<>();
        int depthLimit = Math.max(4, Math.min(40,
                (arena.maxX() - arena.minX() + 1) / 2 - 3));
        int halfHeight = (arena.maxY() - arena.minY()) / 2;
        for (int depth = 0; depth < depthLimit; depth++) {
            int x = arena.centreX() + (left ? -3 - depth : 3 + depth);
            for (int offset = 0; offset <= halfHeight; offset++) {
                if (offset == 0) {
                    addIfOnMap(world, cells, x, arena.centreY());
                } else {
                    addIfOnMap(world, cells, x, arena.centreY() - offset);
                    addIfOnMap(world, cells, x, arena.centreY() + offset);
                }
            }
        }
        return cells;
    }

    /** Fleets face across the north and south oceans around the land melee. */
    private static List<int[]> navalFormationCells(
            World world, Arena arena, boolean left) {
        List<int[]> cells = new ArrayList<>();
        int depthLimit = Math.max(4,
                (arena.maxX() - arena.minX() + 1) / 2 - 3);
        int waterDepth = arena.minY();
        for (int depth = 0; depth < depthLimit; depth++) {
            int x = arena.centreX() + (left ? -3 - depth : 3 + depth);
            for (int offset = 0; offset < waterDepth; offset++) {
                int northY = arena.minY() - 1 - offset;
                int southY = arena.maxY() + 1 + offset;
                if (northY >= 0) {
                    addIfOnMap(world, cells, x, northY);
                }
                if (southY < world.map().height()) {
                    addIfOnMap(world, cells, x, southY);
                }
            }
        }
        return cells;
    }

    private static void addIfOnMap(World world, List<int[]> cells, int x, int y) {
        if (world.map().contains(x, y)) {
            cells.add(new int[] {x, y});
        }
    }

    private static Set<String> fieldTypes(List<String> first, List<String> second) {
        Set<String> types = new LinkedHashSet<>(first);
        types.addAll(second);
        return Set.copyOf(types);
    }

    private static List<Unit> joined(List<Unit> first, List<Unit> second) {
        List<Unit> joined = new ArrayList<>(first.size() + second.size());
        joined.addAll(first);
        joined.addAll(second);
        return joined;
    }

    record Result(int requested, int deployed, int humanUnits, int orcUnits,
            int landUnits, int navalUnits, int openingSpells,
            int centreX, int centreY, List<Unit> units) { }

    private record Arena(int centreX, int centreY,
            int minX, int maxX, int minY, int maxY) { }

    /**
     * Keeps a showcase battle moving after the two opening attack-move orders
     * have crossed. A normal match has players (and a computer script) to
     * issue the next command. The showcase deliberately has neither, so
     * surviving pockets otherwise reach their destination and correctly go
     * still while enemies remain elsewhere on the map.
     *
     * <p>The director does not deal damage or move pieces. Every few seconds
     * it gives each disengaged survivor an ordinary attack command against
     * the nearest compatible enemy, falling back to attack-move only when no
     * unit target is legal. Everything that follows is still the normal
     * movement, acquisition, projectile, spell, damage, death and rendering
     * machinery.
     */
    static final class Director {
        private final World world;
        private final Result battle;
        private long nextReview;
        private long nextSpellWave;
        private int spellWave;
        private String finalMessage;

        Director(World world, Result battle) {
            this.world = world;
            this.battle = battle;
            this.nextReview = DIRECTOR_INTERVAL;
            this.nextSpellWave = SPELL_WAVE_INTERVAL;
        }

        Status update() {
            Army human = army(0);
            Army orc = army(1);
            if (human.alive() == 0 || orc.alive() == 0) {
                if (finalMessage == null) {
                    if (human.alive() == orc.alive()) {
                        finalMessage = "Battle complete — no survivors";
                    } else if (human.alive() > 0) {
                        finalMessage = "Battle complete — Human alliance wins with "
                                + human.alive() + " survivors";
                    } else {
                        finalMessage = "Battle complete — Orcish Horde wins with "
                                + orc.alive() + " survivors";
                    }
                }
                return new Status(true, true, finalMessage,
                        human.alive(), orc.alive(), 0);
            }
            if (world.cycle() < nextReview) {
                return new Status(false, false, null,
                        human.alive(), orc.alive(), 0);
            }
            nextReview = world.cycle() + DIRECTOR_INTERVAL;
            int redirected = castMagicWave(human.units(), orc.units())
                    + castMagicWave(orc.units(), human.units())
                    + engageDisengaged(human.units(), orc.units(), orc.x(), orc.y())
                    + engageDisengaged(orc.units(), human.units(), human.x(), human.y());
            String message = redirected > 0
                    ? "Battle raging — Human " + human.alive() + " · Orc " + orc.alive()
                    : null;
            return new Status(redirected > 0, false, message,
                    human.alive(), orc.alive(), redirected);
        }

        private int engageDisengaged(List<Unit> units, List<Unit> enemies, int x, int y) {
            int redirected = 0;
            for (Unit unit : units) {
                if (!unit.isAlive() || !unit.isOnMap()) {
                    continue;
                }
                Unit target = unit.target();
                boolean fightingLiveTarget = unit.order() == Unit.Order.ATTACK
                        && target != null && target.isAlive() && target.isOnMap();
                boolean committedOrQueued = unit.animation().unbreakable()
                        || unit.order() == Unit.Order.SPELL_CAST
                        || unit.queuedReplacementPending() || unit.hasQueuedOrders();
                if (!fightingLiveTarget && !committedOrQueued) {
                    boolean accepted;
                    Unit domainGoal = nearestDomainEnemy(unit, enemies);
                    int goalX = domainGoal == null ? x : domainGoal.tileX();
                    int goalY = domainGoal == null ? y : domainGoal.tileY();
                    if (isShowcaseCaster(unit)) {
                        // Leave artillery casters still while their mana
                        // recovers. The next spell wave will use it; marching
                        // them into melee would make the magic disappear.
                        accepted = false;
                    } else if (unit.type().canAttack()) {
                        // An exhausted or congestion-blocked ATTACK_MOVE is
                        // not engagement. Give it a concrete nearby enemy so
                        // every surviving rank keeps trying to fight.
                        List<Unit> nearest = enemies.stream()
                                .filter(enemy -> enemy.isAlive() && enemy.isOnMap())
                                .sorted(java.util.Comparator.comparingInt(unit::distanceTo))
                                .toList();
                        accepted = attackFirstCompatible(world, unit, nearest, 0);
                        if (!accepted) {
                            accepted = world.orderAttackMove(unit, goalX, goalY);
                        }
                    } else {
                        accepted = unit.order() == Unit.Order.STILL
                                && world.orderMove(unit, goalX, goalY);
                    }
                    if (accepted) {
                        redirected++;
                    }
                }
            }
            return redirected;
        }

        private int castMagicWave(List<Unit> casters, List<Unit> enemies) {
            if (world.cycle() < nextSpellWave) {
                return 0;
            }
            int cast = 0;
            for (Unit caster : casters) {
                if (!caster.isAlive() || !caster.isOnMap()
                        || !isShowcaseCaster(caster)
                        || caster.order() != Unit.Order.STILL
                        || caster.animation().unbreakable()) {
                    continue;
                }
                Unit target = nearestDomainEnemy(caster, enemies);
                if (target == null) {
                    continue;
                }
                String[] choices = caster.player() == 0
                        ? new String[] {"spell-blizzard", "spell-fireball"}
                        : new String[] {"spell-death-and-decay", "spell-whirlwind",
                            "spell-death-coil"};
                for (int step = 0; step < choices.length; step++) {
                    String spell = choices[(caster.id() + spellWave + step) % choices.length];
                    if (orderShowcaseSpell(world, caster, spell, target)) {
                        cast++;
                        break;
                    }
                }
            }
            spellWave++;
            nextSpellWave = world.cycle() + SPELL_WAVE_INTERVAL;
            return cast;
        }

        private Army army(int player) {
            List<Unit> alive = battle.units().stream()
                    .filter(unit -> unit.player() == player
                            && unit.isAlive() && unit.isOnMap())
                    .toList();
            if (alive.isEmpty()) {
                return new Army(alive, 0, battle.centreX(), battle.centreY());
            }
            int x = (int) Math.round(alive.stream()
                    .mapToInt(Unit::tileX).average().orElse(battle.centreX()));
            int y = (int) Math.round(alive.stream()
                    .mapToInt(Unit::tileY).average().orElse(battle.centreY()));
            return new Army(alive, alive.size(), x, y);
        }
    }

    record Status(boolean changed, boolean complete, String message,
            int humanAlive, int orcAlive, int redirected) { }

    private record Army(List<Unit> units, int alive, int x, int y) { }

    private static boolean isShowcaseCaster(Unit unit) {
        String ident = unit.type().ident();
        return "unit-mage".equals(ident) || "unit-death-knight".equals(ident);
    }
}
