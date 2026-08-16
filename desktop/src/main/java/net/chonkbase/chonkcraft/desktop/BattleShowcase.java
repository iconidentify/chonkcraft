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

    static final int DEFAULT_UNITS = 480;
    static final int MIN_UNITS = 40;
    static final int MAX_UNITS = 800;
    private static final int DIRECTOR_INTERVAL = 90;

    /*
     * Every mobile land or air class a player can field in a normal match is
     * represented. Repetition supplies the ranks: infantry forms the mass,
     * cavalry and ogres the shock line, ranged troops the second line, and
     * siege, demolition, spellcasters and flyers remain plainly visible.
     * Ships need water and belong in a naval showcase rather than sailing
     * across grass for the sake of claiming a larger list.
     */
    private static final List<String> HUMAN_ROSTER = List.of(
            "unit-footman", "unit-footman", "unit-footman", "unit-footman",
            "unit-knight", "unit-knight", "unit-paladin",
            "unit-archer", "unit-archer", "unit-ranger",
            "unit-ballista", "unit-dwarves", "unit-mage",
            "unit-gryphon-rider", "unit-peasant", "unit-balloon");
    private static final List<String> ORC_ROSTER = List.of(
            "unit-grunt", "unit-grunt", "unit-grunt", "unit-grunt",
            "unit-ogre", "unit-ogre", "unit-ogre-mage",
            "unit-axethrower", "unit-axethrower", "unit-berserker",
            "unit-catapult", "unit-goblin-sappers", "unit-death-knight",
            "unit-skeleton", "unit-dragon", "unit-peon", "unit-zeppelin",
            "unit-eye-of-vision");

    static final Set<String> HUMAN_FIELD_TYPES = Set.copyOf(HUMAN_ROSTER);
    static final Set<String> ORC_FIELD_TYPES = Set.copyOf(ORC_ROSTER);

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

    /** The largest retail BNE Garden of War shipped by the selected pack. */
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
     * Replaces a skirmish opening with two mixed armies and sends them through
     * the ordinary BNE attack-move machinery. No combat shortcut or scripted
     * damage is used: movement, acquisition, projectiles, spells, deaths and
     * rendering are the same systems used by a normal game.
     */
    static Result deploy(World world, Map<String, UnitType> types, int requested) {
        int wanted = Math.max(MIN_UNITS, Math.min(MAX_UNITS, requested));
        for (Unit unit : List.copyOf(world.unitsSnapshot())) {
            world.remove(unit);
        }
        flattenArena(world);

        Player human = world.player(0);
        Player orc = world.player(1);
        if (human == null || orc == null) {
            throw new IllegalStateException("battle showcase needs player slots zero and one");
        }
        human.setType(net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.PERSON);
        orc.setType(net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER);

        int[] centre = {world.map().width() / 2, world.map().height() / 2};
        List<int[]> leftCells = formationCells(world, centre[0], centre[1], true);
        List<int[]> rightCells = formationCells(world, centre[0], centre[1], false);
        List<Unit> left = placeArmy(world, types, 0, HUMAN_ROSTER,
                leftCells, (wanted + 1) / 2);
        List<Unit> right = placeArmy(world, types, 1, ORC_ROSTER,
                rightCells, wanted / 2);

        if (left.size() < MIN_UNITS / 2 || right.size() < MIN_UNITS / 2) {
            throw new IllegalStateException("map has too little open ground for the showcase");
        }

        world.fireBattleNetReadyForAll();
        int leftGoal = Math.min(world.map().width() - 2, centre[0] + 10);
        int rightGoal = Math.max(1, centre[0] - 10);
        orderCharge(world, left, right, leftGoal, centre[1]);
        orderCharge(world, right, left, rightGoal, centre[1]);
        world.recalculateSupply();

        List<Unit> all = new ArrayList<>(left.size() + right.size());
        all.addAll(left);
        all.addAll(right);
        return new Result(wanted, all.size(), left.size(), right.size(),
                centre[0], centre[1], List.copyOf(all));
    }

    /**
     * Turns the loaded retail map into a purpose-built open parade ground.
     * The most common ordinary ground picture is retained, so the arena uses
     * the selected pack's real tileset art, while every wall, tree, rock,
     * coast, resource value and stale occupancy bit is removed. The source
     * map and pack are never modified.
     */
    private static void flattenArena(World world) {
        GameMap map = world.map();
        Map<Integer, Integer> frequency = new HashMap<>();
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                MapField field = map.field(x, y);
                long flags = field.flags();
                if (field.isLandPassable()
                        && (flags & (TileFlag.FOREST | TileFlag.ROCKS
                                | TileFlag.WALL | TileFlag.COAST_ALLOWED
                                | TileFlag.UNPASSABLE)) == 0) {
                    frequency.merge(field.tile(), 1, Integer::sum);
                }
            }
        }
        if (frequency.isEmpty()) {
            throw new IllegalStateException("showcase map has no ordinary open-ground tile");
        }
        int groundTile = frequency.entrySet().stream()
                .max(Map.Entry.<Integer, Integer>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey()))
                .orElseThrow().getKey();
        long groundFlags = map.tileset().flagsFor(groundTile)
                & ~(GameMap.OCCUPANCY_FLAGS | TileFlag.FOREST | TileFlag.ROCKS
                        | TileFlag.WALL | TileFlag.UNPASSABLE | TileFlag.OPAQUE
                        | TileFlag.SUBTILES_UNPASSABLE_MASK);
        groundFlags |= TileFlag.LAND_ALLOWED;
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                MapField field = map.field(x, y);
                field.setTile(groundTile);
                field.setFlags(groundFlags);
                field.setValue(0);
            }
        }
        map.recordLoadedTerrain();
    }

    /** Starts one synchronized charge using only ordinary player orders. */
    private static void orderCharge(World world, List<Unit> army, List<Unit> enemies,
            int x, int y) {
        for (int index = 0; index < army.size(); index++) {
            Unit unit = army.get(index);
            if (unit.type().canAttack()) {
                // Spread opening targets across the opposing formation. A
                // single attack-move destination made hundreds of units
                // converge on the same square and left the rear ranks
                // technically moving but visibly idle behind the jam.
                if (!attackFirstCompatible(world, unit, enemies, index)) {
                    world.orderAttackMove(unit, x, y);
                }
            } else {
                // Flying machines and zeppelins cannot attack. They still
                // join the charge as scouts rather than remaining statues.
                world.orderMove(unit, x, y);
            }
        }
    }

    private static boolean attackFirstCompatible(World world, Unit attacker,
            List<Unit> enemies, int offset) {
        for (int step = 0; step < enemies.size(); step++) {
            Unit target = enemies.get((offset + step) % enemies.size());
            if (target.isAlive() && target.isOnMap()
                    && world.orderAttack(attacker, target)) {
                return true;
            }
        }
        return false;
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

    /** Front ranks first, alternating above and below the camera centre. */
    private static List<int[]> formationCells(World world, int centreX, int centreY,
            boolean left) {
        List<int[]> cells = new ArrayList<>();
        int depthLimit = Math.max(4, Math.min(26, world.map().width() / 2 - 4));
        int halfHeight = Math.max(4, Math.min(24, world.map().height() / 2 - 2));
        for (int depth = 0; depth < depthLimit; depth++) {
            int x = centreX + (left ? -3 - depth : 3 + depth);
            for (int offset = 0; offset <= halfHeight; offset++) {
                if (offset == 0) {
                    addIfOnMap(world, cells, x, centreY);
                } else {
                    addIfOnMap(world, cells, x, centreY - offset);
                    addIfOnMap(world, cells, x, centreY + offset);
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

    record Result(int requested, int deployed, int humanUnits, int orcUnits,
            int centreX, int centreY, List<Unit> units) { }

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
        private String finalMessage;

        Director(World world, Result battle) {
            this.world = world;
            this.battle = battle;
            this.nextReview = DIRECTOR_INTERVAL;
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
            int redirected = engageDisengaged(human.units(), orc.units(), orc.x(), orc.y())
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
                        || unit.queuedReplacementPending() || unit.hasQueuedOrders();
                if (!fightingLiveTarget && !committedOrQueued) {
                    boolean accepted;
                    if (unit.type().canAttack()) {
                        // An exhausted or congestion-blocked ATTACK_MOVE is
                        // not engagement. Give it a concrete nearby enemy so
                        // every surviving rank keeps trying to fight.
                        List<Unit> nearest = enemies.stream()
                                .filter(enemy -> enemy.isAlive() && enemy.isOnMap())
                                .sorted(java.util.Comparator.comparingInt(unit::distanceTo))
                                .toList();
                        accepted = attackFirstCompatible(world, unit, nearest, 0);
                        if (!accepted) {
                            accepted = world.orderAttackMove(unit, x, y);
                        }
                    } else {
                        accepted = unit.order() == Unit.Order.STILL
                                && world.orderMove(unit, x, y);
                    }
                    if (accepted) {
                        redirected++;
                    }
                }
            }
            return redirected;
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
}
