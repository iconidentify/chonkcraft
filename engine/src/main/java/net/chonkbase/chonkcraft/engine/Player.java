package net.chonkbase.chonkcraft.engine;

import java.util.EnumMap;
import java.util.Map;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.unit.UnitType;

/**
 * One player's side of the game: what they own and what they have banked.
 *
 * <p>Implements the part of {@code CPlayer} the economy needs.
 */
public final class Player {

    /** Slots in every Warcraft II game. */
    public static final int MAX = PudMap.PLAYER_MAX;

    private final int index;
    private PudMap.PlayerType type;
    private final PudMap.Race race;
    private final Map<UnitType.Resource, Integer> resources = new EnumMap<>(UnitType.Resource.class);

    /** Supply provided by farms and halls. */
    private int supply;

    /** Supply consumed by units. */
    private int demand;

    public Player(int index, PudMap.PlayerType type, PudMap.Race race) {
        this.index = index;
        this.type = type;
        this.race = race;
    }

    /**
     * Builds the sixteen players a map declares, with their starting stores.
     *
     * <p>Stores only for the slots that are playing. This implementation reads the
     * PUD itself, where every one of the sixteen slots carries a starting
     * figure whether or not anybody is using it; upstream never sees the
     * PUD at all -- it runs the {@code .sms} wartool generates from it, and
     * wartool writes {@code SetPlayerData(i, "Resources"...)} only for the
     * slots that play, so its neutral slot starts on nothing. The
     * differential harness caught the difference as the neutral player
     * holding a thousand of everything against upstream's nought, on every
     * cycle of every trace. Bounded either way -- the neutral slot owns the
     * gold mines and the critters and never spends a coin, and a mine's
     * contents live in {@code Unit.resourcesHeld} rather than in this bank
     * -- but a trace that differs every cycle is a trace nobody reads.
     */
    /**
     * The same, for a game with one person at the keyboard.
     *
     * <p>{@code CPlayer::Init},
     * whose own comment is the specification: "Take first slot for person on
     * this computer, fill other with computer players." Outside a networked
     * game the first {@code PERSON} slot is the one being played and every
     * later one becomes a {@code COMPUTER}.
     *
     * <p>Without it a map built for two people has no opponent in it. Every
     * skirmish map is built for two or more, so all twenty-eight of the ones
     * this data ships opened here as an empty field: the other side's town
     * sat where the map put it, gathered nothing, built nothing and never
     * came. The differential harness put a number on it -- upstream starts
     * {@code maps/skirmish/(2)2-players} reporting {@code AI: human:All with
     * wc2-air-attack} and this implementation enabled nought computer players, because
     * the PUD says both slots are people and it was believed.
     *
     * <p>Separate from {@link #from} rather than replacing it, because the
     * rule is about how a game is started and not about what a map says: a
     * networked game keeps every person slot -- upstream guards this with
     * {@code !NetPlayers} -- and so does a test that means to build a
     * two-person world.
     */
    public static Player[] forSoloGame(PudMap map) {
        // Skirmish solo start: seat one person and fill other person slots
        // with computers. Campaign/mission load uses {@link #from}, which
        // follows retail BNE (extra person seats become nobody).
        return from(map, true, false);
    }

    /**
     * Builds the player table under retail BNE rules: keep the neutral bank
     * from the PUD, and disable any additional local person seats.
     */
    public static Player[] from(PudMap map) {
        return from(map, true, true);
    }

    /**
     * Builds the player table a network lobby settled.
     *
     * <p>A multiplayer PUD describes which slots may be used; the lobby says
     * which of them actually are. Reusing {@link #from(PudMap)} here disables
     * every person after the first as though this were a campaign load. The
     * joining player then owns no units, has no sight, and sees a black map.
     */
    public static Player[] forNetworkGame(PudMap map, PudMap.PlayerType[] types,
            PudMap.Race[] races) {
        if (types == null || races == null || types.length < MAX || races.length < MAX) {
            throw new IllegalArgumentException("network player table must contain all slots");
        }
        Player[] players = new Player[MAX];
        for (int i = 0; i < MAX; i++) {
            players[i] = new Player(i, types[i], races[i]);
            if (types[i] != PudMap.PlayerType.NOBODY) {
                giveStartingResources(players[i], map, i);
                if (players[i].isActive()) {
                    applyBattleNetNetworkMinimums(players[i]);
                }
            }
        }
        return players;
    }

    /**
     * @deprecated the engine is BNE-only; equivalent to {@link #from(PudMap)}.
     */
    @Deprecated
    public static Player[] fromBattleNetEdition(PudMap map) {
        return from(map);
    }

    private static Player[] from(PudMap map, boolean includeNeutralResources,
            boolean disableAdditionalPersonSlots) {
        Player[] players = new Player[MAX];
        boolean seatTaken = false;
        for (int i = 0; i < MAX; i++) {
            PudMap.PlayerType type = map.players()[i];
            // "Take first slot for person on this computer, fill other with
            // computer players" -- CPlayer::Init (:
            // 645-650). A single-player game seats one person; every later
            // person slot is a computer. On campaigns/human-exp/levelx12h
            // the map declares two, and keeping the second as a person made
            // it the enemy of every computer where upstream's demoted slot
            // is their ally -- its quick-blade opened the mission by
            // attacking a cannon tower nobody else was at war with.
            if (type == PudMap.PlayerType.PERSON) {
                if (seatTaken) {
                    type = disableAdditionalPersonSlots
                            ? PudMap.PlayerType.NOBODY
                            : PudMap.PlayerType.COMPUTER;
                } else {
                    seatTaken = true;
                }
            }
            players[i] = new Player(i, type, map.races()[i]);
            if (type == PudMap.PlayerType.NOBODY
                    || (!includeNeutralResources
                            && type == PudMap.PlayerType.NEUTRAL)) {
                continue;
            }
            giveStartingResources(players[i], map, i);
        }
        return players;
    }

    private static void giveStartingResources(Player player, PudMap map, int index) {
        player.set(UnitType.Resource.GOLD, map.startGold()[index]);
        player.set(UnitType.Resource.WOOD, map.startLumber()[index]);
        player.set(UnitType.Resource.OIL, map.startOil()[index]);
    }

    /**
     * Applies retail BNE's multiplayer floor to a map-defined starting bank.
     *
     * <p>The map-default arm of native {@code 0x004338d0} first copies the
     * PUD banks and then, only for a network game, raises each active slot to
     * at least 2,100 gold, 1,100 lumber and 1,000 oil. This is deliberately a
     * floor rather than the Low preset: maps that start above it keep their
     * authored values.</p>
     */
    private static void applyBattleNetNetworkMinimums(Player player) {
        player.set(UnitType.Resource.GOLD,
                Math.max(2100, player.get(UnitType.Resource.GOLD)));
        player.set(UnitType.Resource.WOOD,
                Math.max(1100, player.get(UnitType.Resource.WOOD)));
        player.set(UnitType.Resource.OIL,
                Math.max(1000, player.get(UnitType.Resource.OIL)));
    }

    public int index() {
        return index;
    }

    public PudMap.PlayerType type() {
        return type;
    }

    /**
     * Changes what kind of player this slot is.
     *
     * <p>Only one thing does this: {@code DefinePlayerTypes} in a mission
     * script, which is how a mission overrules the types in its own map. The
     * eighth human mission is the only one in the game that uses it, and it
     * uses it to turn slot four from a rescuable ally into an enemy so that
     * the seven peasants standing there can charge you.
     */
    public void setType(PudMap.PlayerType type) {
        this.type = type;
    }

    public PudMap.Race race() {
        return race;
    }

    /** Whether this slot takes part in the game at all. */
    public boolean isActive() {
        return type == PudMap.PlayerType.PERSON || type == PudMap.PlayerType.COMPUTER;
    }

    /** How much of a resource is banked. */
    public int get(UnitType.Resource resource) {
        return resources.getOrDefault(resource, 0);
    }

    public void set(UnitType.Resource resource, int amount) {
        resources.put(resource, Math.max(0, amount));
    }

    /** Adds to the bank. */
    public void add(UnitType.Resource resource, int amount) {
        set(resource, get(resource) + amount);
    }

    /**
     * What a banked load of a resource pays, per hundred carried.
     *
     * <p>{@code CPlayer::Incomes}, default one hundred
     * ({@code DefaultIncomes}), raised to the best standing building's
     * {@code ImproveIncomes} and recomputed when one falls. A tanker's
     * hundred oil is a hundred and twenty-five in the ledger while a
     * refinery stands.
     */
    private final Map<UnitType.Resource, Integer> incomes =
            new EnumMap<>(UnitType.Resource.class);

    public int income(UnitType.Resource resource) {
        return incomes.getOrDefault(resource, 100);
    }

    public void setIncome(UnitType.Resource resource, int income) {
        incomes.put(resource, Math.max(100, income));
    }

    /** Whether every cost in a table can be paid. */
    public boolean canAfford(Map<UnitType.Resource, Integer> costs) {
        for (Map.Entry<UnitType.Resource, Integer> cost : costs.entrySet()) {
            // Time is a build duration, not a resource, and is never deducted.
            if (cost.getKey() == UnitType.Resource.TIME) {
                continue;
            }
            if (get(cost.getKey()) < cost.getValue()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Deducts a cost table.
     *
     * @return whether it could be paid; nothing is deducted if not
     */
    public boolean pay(Map<UnitType.Resource, Integer> costs) {
        if (!canAfford(costs)) {
            return false;
        }
        for (Map.Entry<UnitType.Resource, Integer> cost : costs.entrySet()) {
            if (cost.getKey() != UnitType.Resource.TIME) {
                add(cost.getKey(), -cost.getValue());
            }
        }
        return true;
    }

    /**
     * Points, as {@code CPlayer::Score} keeps them.
     *
     * <p>"Total number of points. You can get points for killing units,
     * destroying buildings" -- each unit type carries a {@code Points} value
     * and the killer's owner banks it. The top bar has a slot for this figure
     * in every one of the shipped layouts.
     */
    private int score;

    public int score() {
        return score;
    }

    public void setScore(int score) {
        this.score = Math.max(0, score);
    }

    /** Banks points, as a kill does. */
    public void addScore(int points) {
        setScore(score + Math.max(0, points));
    }

    /**
     * Units and buildings destroyed, as {@code CPlayer::TotalKills} and
     * {@code CPlayer::TotalRazings} keep them.
     *
     * <p>{@code HitUnit_IncreaseScoreForKill} raises one or the other beside
     * the score on every kill, choosing by whether what died was a building.
     * They are kept apart because Warcraft II reports them apart: the end of
     * mission screen has a row for units destroyed and a row for buildings
     * razed. Nothing in this implementation shows them yet, but a total that only starts
     * being counted when a screen to show it appears is a total that reads
     * zero for the mission the player just played.
     */
    private int totalKills;
    private int totalRazings;

    public int totalKills() {
        return totalKills;
    }

    public void setTotalKills(int kills) {
        this.totalKills = Math.max(0, kills);
    }

    public int totalRazings() {
        return totalRazings;
    }

    public void setTotalRazings(int razings) {
        this.totalRazings = Math.max(0, razings);
    }

    /**
     * Counts one destroyed unit.
     *
     * @param building whether what died was a building, which is the only
     *                 thing upstream tests: it takes the razings branch and
     *                 everything else takes the kills branch
     */
    public void addKill(boolean building) {
        if (building) {
            totalRazings++;
        } else {
            totalKills++;
        }
    }

    public int supply() {
        return supply;
    }

    public void setSupply(int supply) {
        this.supply = supply;
    }

    public int demand() {
        return demand;
    }

    public void setDemand(int demand) {
        this.demand = demand;
    }

    /**
     * Whether there is room for another unit.
     *
     * <p>Warcraft II lets you exceed your supply by building more units than
     * farms support; it just stops training new ones. The check is therefore
     * on training, not on existence.
     */
    public boolean hasSupplyRoom(int extraDemand) {
        return demand + extraDemand <= supply;
    }

    @Override
    public String toString() {
        return "player " + index + " (" + type + ", " + race + ")";
    }
}
