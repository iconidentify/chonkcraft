package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.network.LockstepScheduler;
import net.chonkbase.chonkcraft.engine.network.NetworkGame;
import net.chonkbase.chonkcraft.engine.network.NetworkSession;
import net.chonkbase.chonkcraft.engine.network.SyncHash;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A player-control watchdog which observes the game without changing it.
 *
 * <p>The precision suite is deliberately allowed to retain known internal
 * differences from retail. This referee asks a narrower player question:
 * after the command seam accepted an order, did the unit visibly progress or
 * reach an honest terminal state within the journal's 600-cycle window? It
 * never retries, redirects or unsticks a unit, so a failure remains evidence
 * for a systemic fix rather than becoming a second movement implementation.
 */
class ControlLivenessPlayabilityTest {

    private record Fixture(GameData data, World world, CommandApplier commands,
            List<Unit> human, List<Unit> orc) {}

    @Test
    @DisplayName("one, three and nine-unit controls stay live through redirects and combat")
    void oneThreeAndNineUnitControlsStayLiveThroughRedirectsAndCombat() {
        Fixture fixture = fixture(data());
        PlayerIntentJournal journal = new PlayerIntentJournal();
        AtomicReference<List<Integer>> selected = new AtomicReference<>(List.of());
        CommandSink commands = journal.wrap(CommandSink.local(fixture.commands()),
                fixture.world()::cycle, selected::get, fixture.world());

        issueMoves(journal, commands, selected, fixture.world().cycle(),
                fixture.human().subList(0, 1), 28, 8, "one-unit-move");
        tick(fixture.world(), journal, 32);
        issueMoves(journal, commands, selected, fixture.world().cycle(),
                fixture.human().subList(0, 3), 28, 24, "three-unit-redirect");
        tick(fixture.world(), journal, 32);
        issueStops(journal, commands, selected, fixture.world().cycle(),
                fixture.human().subList(0, 1));
        tick(fixture.world(), journal, 24);
        issueMoves(journal, commands, selected, fixture.world().cycle(),
                fixture.human().subList(0, 9), 40, 28, "nine-unit-move");
        tick(fixture.world(), journal, 96);
        issueAttacks(journal, commands, selected, fixture.world().cycle(),
                fixture.human(), fixture.orc().get(4));
        tick(fixture.world(), journal, PlayerIntentJournal.OUTCOME_WINDOW + 300);

        assertTransactionSizes(journal, Set.of(1, 3, 9));
        assertControlLiveness(journal, 23);
    }

    @Test
    @DisplayName("two real UDP peers retain control liveness and one world hash")
    void twoRealUdpPeersRetainControlLivenessAndOneWorldHash() throws Exception {
        GameData data = data();
        Fixture hostFixture = fixture(data);
        Fixture guestFixture = fixture(data);
        PlayerIntentJournal hostJournal = new PlayerIntentJournal();
        PlayerIntentJournal guestJournal = new PlayerIntentJournal();
        AtomicReference<List<Integer>> hostSelection = new AtomicReference<>(List.of());
        AtomicReference<List<Integer>> guestSelection = new AtomicReference<>(List.of());

        try (NetworkSession hostSession = new NetworkSession(0, 0);
                NetworkSession guestSession = new NetworkSession(1, 0)) {
            InetAddress loopback = InetAddress.getLoopbackAddress();
            hostSession.addPeer(1, loopback, guestSession.localPort());
            guestSession.addPeer(0, loopback, hostSession.localPort());
            NetworkGame host = new NetworkGame(hostFixture.world(), hostSession,
                    new LockstepScheduler(2, 5, 2), hostFixture.commands(), 0);
            NetworkGame guest = new NetworkGame(guestFixture.world(), guestSession,
                    new LockstepScheduler(2, 5, 2), guestFixture.commands(), 1);
            host.start();
            guest.start();
            CommandSink hostCommands = hostJournal.wrap(CommandSink.networked(host),
                    hostFixture.world()::cycle, hostSelection::get, hostFixture.world());
            CommandSink guestCommands = guestJournal.wrap(CommandSink.networked(guest),
                    guestFixture.world()::cycle, guestSelection::get, guestFixture.world());

            boolean opened = false;
            boolean redirected = false;
            boolean stopped = false;
            boolean resumed = false;
            boolean attacked = false;
            long deadline = System.currentTimeMillis() + 30_000;
            while ((hostFixture.world().cycle() < 1_200
                    || guestFixture.world().cycle() < 1_200)
                    && System.currentTimeMillis() < deadline) {
                if (sameCycle(hostFixture.world(), guestFixture.world())) {
                    long cycle = hostFixture.world().cycle();
                    if (!opened) {
                        issueMoves(hostJournal, hostCommands, hostSelection, cycle,
                                hostFixture.human(), 36, 8, "network-nine-move");
                        issueMoves(guestJournal, guestCommands, guestSelection, cycle,
                                guestFixture.orc(), 35, 8, "network-nine-move");
                        opened = true;
                    } else if (!redirected && cycle >= 80) {
                        issueMoves(hostJournal, hostCommands, hostSelection, cycle,
                                hostFixture.human().subList(0, 3), 30, 30,
                                "network-three-redirect");
                        issueMoves(guestJournal, guestCommands, guestSelection, cycle,
                                guestFixture.orc().subList(0, 3), 41, 30,
                                "network-three-redirect");
                        redirected = true;
                    } else if (!stopped && cycle >= 140) {
                        issueStops(hostJournal, hostCommands, hostSelection, cycle,
                                hostFixture.human().subList(0, 1));
                        issueStops(guestJournal, guestCommands, guestSelection, cycle,
                                guestFixture.orc().subList(0, 1));
                        stopped = true;
                    } else if (!resumed && cycle >= 175) {
                        issueMoves(hostJournal, hostCommands, hostSelection, cycle,
                                hostFixture.human().subList(0, 1), 34, 36,
                                "network-one-resume");
                        issueMoves(guestJournal, guestCommands, guestSelection, cycle,
                                guestFixture.orc().subList(0, 1), 37, 36,
                                "network-one-resume");
                        resumed = true;
                    } else if (!attacked && cycle >= 240) {
                        issueAttacks(hostJournal, hostCommands, hostSelection, cycle,
                                hostFixture.human(), hostFixture.orc().get(4));
                        issueAttacks(guestJournal, guestCommands, guestSelection, cycle,
                                guestFixture.orc(), guestFixture.human().get(4));
                        attacked = true;
                    }
                }

                NetworkGame.Step hostStep = host.update();
                if (hostStep == NetworkGame.Step.ADVANCED) {
                    hostJournal.observe(hostFixture.world().cycle(), hostFixture.world());
                }
                NetworkGame.Step guestStep = guest.update();
                if (guestStep == NetworkGame.Step.ADVANCED) {
                    guestJournal.observe(guestFixture.world().cycle(), guestFixture.world());
                }
                assertFalse(hostStep == NetworkGame.Step.DESYNC
                                || guestStep == NetworkGame.Step.DESYNC,
                        "the control stream desynchronized the multiplayer game");
                if (hostStep == NetworkGame.Step.WAITING
                        && guestStep == NetworkGame.Step.WAITING) {
                    Thread.sleep(1);
                }
            }

            assertEquals(1_200, hostFixture.world().cycle(),
                    "the host did not finish the control-liveness match");
            assertEquals(hostFixture.world().cycle(), guestFixture.world().cycle(),
                    "the peers did not finish the same simulation prefix");
            assertEquals(SyncHash.of(hostFixture.world()), SyncHash.of(guestFixture.world()),
                    "the control stream produced different multiplayer worlds");
            assertTrue(opened && redirected && stopped && resumed && attacked,
                    "the complete control script did not run");
            assertTransactionSizes(hostJournal, Set.of(1, 3, 9));
            assertTransactionSizes(guestJournal, Set.of(1, 3, 9));
            assertControlLiveness(hostJournal, 23);
            assertControlLiveness(guestJournal, 23);
        }
    }

    private static GameData data() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        return new GameData(assets);
    }

    private static Fixture fixture(GameData data) {
        GameMap map = new GameMap(72, 48, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        Player[] players = new Player[Player.MAX];
        for (int player = 0; player < players.length; player++) {
            players[player] = new Player(player,
                    player < 2 ? PudMap.PlayerType.PERSON : PudMap.PlayerType.NOBODY,
                    player == 1 ? PudMap.Race.ORC : PudMap.Race.HUMAN);
        }
        World world = new World(map, players);
        world.setUnitTypes(data.unitTypes().types());
        world.setUpgrades(data.upgrades().upgrades());
        world.setMissileTypes(data.missiles().types());
        world.setAllied(0, 1, false);
        world.setAllied(1, 0, false);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        UnitType footman = data.unitTypes().types().get("unit-footman");
        UnitType grunt = data.unitTypes().types().get("unit-grunt");
        assertNotNull(footman, "the authenticated roster has no footman");
        assertNotNull(grunt, "the authenticated roster has no grunt");
        List<Unit> human = new ArrayList<>();
        List<Unit> orc = new ArrayList<>();
        for (int index = 0; index < 9; index++) {
            int row = index / 3;
            int column = index % 3;
            human.add(world.createUnit(footman, 0, 5 + column * 2, 8 + row * 3));
            orc.add(world.createUnit(grunt, 1, 66 - column * 2, 8 + row * 3));
        }
        assertFalse(human.contains(null) || orc.contains(null),
                "the control referee could not deploy both squads");
        List<UnitType> roster = new ArrayList<>(data.unitTypes().types().values());
        CommandApplier commands = new CommandApplier(world, roster);
        data.configureCommands(commands);
        return new Fixture(data, world, commands, List.copyOf(human), List.copyOf(orc));
    }

    private static void issueMoves(PlayerIntentJournal journal, CommandSink commands,
            AtomicReference<List<Integer>> selected, long cycle, List<Unit> units,
            int baseX, int baseY, String detail) {
        issue(journal, commands, selected, cycle, units, detail,
                (unit, ordinal) -> GameCommand.move(unit.player(), unit.id(),
                        baseX + ordinal % 3, baseY + ordinal / 3));
    }

    private static void issueStops(PlayerIntentJournal journal, CommandSink commands,
            AtomicReference<List<Integer>> selected, long cycle, List<Unit> units) {
        issue(journal, commands, selected, cycle, units, "stop-mid-stride",
                (unit, ordinal) -> GameCommand.stop(unit.player(), unit.id()));
    }

    private static void issueAttacks(PlayerIntentJournal journal, CommandSink commands,
            AtomicReference<List<Integer>> selected, long cycle, List<Unit> units,
            Unit target) {
        issue(journal, commands, selected, cycle, units, "congested-live-target",
                (unit, ordinal) -> GameCommand.attack(
                        unit.player(), unit.id(), target.id()));
    }

    private static void issue(PlayerIntentJournal journal, CommandSink commands,
            AtomicReference<List<Integer>> selected, long cycle, List<Unit> units,
            String detail, CommandFactory factory) {
        List<Integer> ids = units.stream().map(Unit::id).toList();
        selected.set(ids);
        long transaction = journal.beginGesture(cycle, "control-liveness-referee", detail,
                0, 0, -1, -1, "plain", null, "simulated-field", ids);
        try {
            for (int ordinal = 0; ordinal < units.size(); ordinal++) {
                assertTrue(commands.issueAccepted(factory.create(units.get(ordinal), ordinal)),
                        "the command seam rejected " + detail + " unit " + ordinal);
            }
            journal.recordAcceptedFanout(cycle, false);
        } finally {
            journal.endGesture(transaction);
        }
    }

    private static void tick(World world, PlayerIntentJournal journal, long cycles) {
        for (long cycle = 0; cycle < cycles; cycle++) {
            world.tick();
            journal.observe(world.cycle(), world);
        }
    }

    private static boolean sameCycle(World first, World second) {
        return first.cycle() == second.cycle();
    }

    private static void assertTransactionSizes(PlayerIntentJournal journal,
            Set<Integer> expected) {
        Set<Integer> observed = new HashSet<>();
        for (PlayerIntentJournal.Entry entry : journal.snapshot()) {
            if ("gesture".equals(entry.event())
                    && entry.gesture() != null
                    && "control-liveness-referee".equals(entry.gesture().origin())) {
                observed.add(entry.selectedUnitIds().size());
            }
        }
        assertTrue(observed.containsAll(expected),
                "the referee did not exercise all selection sizes: " + observed);
    }

    private static void assertControlLiveness(PlayerIntentJournal journal,
            int minimumOutcomes) {
        List<PlayerIntentJournal.Outcome> outcomes = journal.outcomeSnapshot();
        assertTrue(outcomes.size() >= minimumOutcomes,
                "too few controls reached the outcome referee: " + outcomes.size());
        List<PlayerIntentJournal.Outcome> silent = outcomes.stream()
                .filter(outcome -> !Boolean.FALSE.equals(outcome.accepted()))
                .filter(outcome -> "acknowledged-no-progress".equals(
                        outcome.terminalReason()))
                .toList();
        assertTrue(silent.isEmpty(), "accepted controls silently made no progress: " + silent);
        List<PlayerIntentJournal.Outcome> unresolved = outcomes.stream()
                .filter(outcome -> outcome.terminalReason() == null)
                .toList();
        assertTrue(unresolved.isEmpty(), "controls escaped the outcome window: " + unresolved);
        long physical = outcomes.stream()
                .filter(outcome -> outcome.firstProgressCycle() != null)
                .count();
        assertTrue(physical >= 10,
                "too few commands produced observable physical progress: " + physical);
    }

    @FunctionalInterface
    private interface CommandFactory {
        GameCommand create(Unit unit, int ordinal);
    }
}
