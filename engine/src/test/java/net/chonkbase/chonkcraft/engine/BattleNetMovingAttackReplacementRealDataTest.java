package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Player orders must leave the committed native Move body able to finish.
 *
 * <p>Local BNE 2.02b captures use the pinned executable SHA-256
 * b0e914a9cb7dcc81a205e700a9bb0a1d0649df19d459388051ba170783d2c807.
 * The XHuman 10 ballista fixture is
 * eb45aa32f8431c974c4508d910fc1949d292be743355c74ba1afa8e2340a06c1;
 * the independent XHuman 7 submarine fixture is
 * 56639c21d4b4a1131f179c920534e65e29eea143366ef9d04b700a029545b3be.
 * Both apply four selection/right-click operations with zero rejections.
 * Their command cycles and coordinates are reproduced below. The archer
 * witness is a Java campaign liveness regression for the same native
 * movement-before-order rule, not an exact paired campaign trace.
 */
class BattleNetMovingAttackReplacementRealDataTest {

    private static GameData data() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(assets);
    }

    @Test
    @DisplayName("a second click waits for the first move's constructor before replacing it")
    void secondClickWaitsForTheFirstMovesConstructorBeforeReplacingIt() {
        GameData data = data();
        // Retail 0x13 dispatcher captures on Human 4 and XHuman 2: Move at
        // five, then Attack-Move or Move at six, retains Move through seven.
        // The replacement constructs at eight and reserves its first tile
        // at eleven. The old Java entry points replaced the cold order at
        // six and reserved at nine, before BNE's next-order promotion.
        for (boolean ship : new boolean[] {true, false}) {
            String map = ship ? "campaigns/human/level04h"
                    : "campaigns/human-exp/levelx02h";
            int x = ship ? 20 : 10;
            int y = ship ? 18 : 120;
            int person = GameData.personIn(data.campaignMap(map));
            Mission mission = data.loadMission(map, person, 1);
            Unit actor = mission.world().units().stream().filter(unit ->
                    unit.tileX() == x && unit.tileY() == y).findFirst().orElseThrow();
            CommandApplier commands = new CommandApplier(mission.world(),
                    new ArrayList<>(data.unitTypes().types().values()));
            data.configureCommands(commands);
            mission.tick();
            mission.tick();
            for (int cycle = 1; cycle <= 12; cycle++) {
                if (cycle == 5 || cycle == 6) {
                    assertTrue(commands.apply(ship && cycle == 6
                            ? GameCommand.attackMove(person, actor.id(), x, y - 4)
                            : GameCommand.move(person, actor.id(), x, y - 4)),
                            "both player commands must be accepted");
                }
                mission.tick();
                if (cycle >= 6 && cycle <= 7) {
                    assertEquals(Unit.Order.MOVE, actor.currentAction(),
                            "BNE retains the first Move until its action marker");
                }
                assertEquals(x, actor.tileX(), "the northward command must retain its column");
                assertEquals(cycle < 11 ? y : y - 2, actor.tileY(),
                        "the replacement first reserves north at native cycle eleven: " + cycle);
                assertEquals(y * 32 - (cycle == 12 ? ship ? 2 : 4 : 0),
                        actor.tileY() * 32 + actor.offsetY(),
                        "the first physical pixels follow the reserved tile at cycle twelve");
            }
        }
    }

    @Test
    @DisplayName("attack move takes ownership after replacing an ordinary move")
    void attackMoveTakesOwnershipAfterReplacingAnOrdinaryMove() {
        GameData data = data();
        String map = "campaigns/human/level04h";
        int person = GameData.personIn(data.campaignMap(map));
        Mission mission = data.loadMission(map, person, 1);
        World world = mission.world();
        int[][] endpoints = {{16, 30, 16, 26}, {12, 28, 16, 28}, {20, 18, 20, 14}};
        List<Unit> actors = new ArrayList<>();
        for (int[] point : endpoints) {
            actors.add(world.units().stream().filter(u -> u.tileX() == point[0]
                    && u.tileY() == point[1]).findFirst().orElseThrow());
        }
        CommandApplier commands = new CommandApplier(world,
                new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);
        mission.tick();
        mission.tick();
        // Fresh native Human 4 replacement capture: all three actors Move
        // at 5, then Attack-Move at 6. The footman reserves east at 10.
        for (int cycle = 1; cycle <= 26; cycle++) {
            if (cycle == 5 || cycle == 6) {
                for (int i = 0; i < actors.size(); i++) {
                    Unit actor = actors.get(i);
                    int[] point = endpoints[i];
                    assertTrue(commands.apply(cycle == 5
                            ? GameCommand.move(person, actor.id(), point[2], point[3])
                            : GameCommand.attackMove(person, actor.id(), point[2], point[3])),
                            "each actor must accept the replacement Attack-Move");
                }
            }
            if (cycle == 19 || cycle == 20) {
                for (int i = 0; i < actors.size(); i++) {
                    Unit actor = actors.get(i);
                    int[] point = endpoints[i];
                    assertTrue(commands.apply(cycle == 19
                            ? GameCommand.stop(person, actor.id())
                            : i == 0 ? GameCommand.move(person, actor.id(), point[2], point[3])
                                    : GameCommand.patrol(person, actor.id(), point[2], point[3])),
                            "each actor must accept Stop and the next positional order");
                }
            }
            mission.tick();
            if (cycle == 10) {
                Unit footman = actors.get(1);
                assertEquals(13, footman.tileX(), "the replaced Move must release the native east step");
                assertEquals(28, footman.tileY(), "the eastward march must retain its native row");
                assertEquals(Unit.Order.ATTACK_MOVE, footman.order(), "the replacement march must own the footman");
                assertEquals(2485, footman.battleNetSequenceOffset(), "the march must begin the native Move program");
            }
        }
        // Native player dispatcher capture 1ac6b33b: Stop at 19 is replaced
        // by Patrol at 20. The remaining east pixels land at 26, then Patrol
        // constructs Still 3,2,1. A borrowed Move presentation is not a new
        // command boundary while these native pixels remain owed.
        Unit footman = actors.get(1);
        assertEquals(13, footman.tileX(), "the replaced stride must land in its reserved column");
        assertEquals(416, footman.tileX() * 32 + footman.offsetX(), "Patrol must wait for the final eastward pixel");
        assertEquals(Unit.Order.PATROL, footman.order(), "Patrol must own the footman after the stride lands");
        assertEquals(2477, footman.battleNetSequenceOffset(), "the new Patrol must construct the native Still program");
        assertEquals(3, footman.battleNetAnimationTimer(), "the new Patrol must receive its complete three-call timer");
    }

    @Test
    @DisplayName("redirecting a moving scout waits for its new order after the old stride lands")
    void redirectingAMovingScoutWaitsForItsNewOrderAfterTheOldStrideLands() {
        GameData data = data();
        String map = "campaigns/orc-exp/levelx12o";
        int person = GameData.personIn(data.campaignMap(map));
        Mission mission = data.loadMission(map, person, 1);
        Unit scout = mission.world().units().stream().filter(unit ->
                unit.tileX() == 110 && unit.tileY() == 22
                        && "unit-zeppelin".equals(unit.type().ident()))
                .findFirst().orElseThrow();
        CommandApplier commands = new CommandApplier(mission.world(),
                new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);
        mission.tick();
        mission.tick();
        // Fresh retail 0x13 capture issue12-player-controls-xorc-12,
        // slot 1512: the second Move's first reservation is cycle 34.
        for (int cycle = 1; cycle <= 35; cycle++) {
            if (cycle == 5 || cycle == 6 || cycle == 20) {
                assertTrue(commands.apply(GameCommand.move(person, scout.id(), 110, 18)),
                        "the scout must accept each redirect");
            }
            if (cycle == 19) {
                assertTrue(commands.apply(GameCommand.stop(person, scout.id())),
                        "Stop must be accepted during the first stride");
            }
            mission.tick();
            if (cycle >= 31) {
                assertEquals(110, scout.tileX(), "the northbound scout must retain its column");
                assertEquals(cycle < 34 ? 20 : 18, scout.tileY(),
                        "the replacement must wait until native cycle 34 to reserve north");
                assertEquals(cycle == 35 ? 636 : 640,
                        scout.tileY() * 32 + scout.offsetY(),
                        "the replacement cannot spend pixels during its constructor");
            }
        }
    }

    @Test
    @DisplayName("a fresh attack click does not inherit the replaced command's remaining wait")
    void freshAttackClickDoesNotInheritTheReplacedCommandsRemainingWait() {
        GameData data = data();
        String map = "campaigns/human/level01h";
        int person = GameData.personIn(data.campaignMap(map));
        // Six sealed Human 1 cases replace Stop, Stand Ground or Attack at
        // cycle 6. Every new Attack constructor owns cycles 6,7,8 before
        // its first route reservation at 9, regardless of the old delay.
        for (String prior : List.of("stop", "stand-ground", "attack")) {
            for (boolean march : new boolean[] {false, true}) {
                Mission mission = data.loadMission(map, person, 1);
                World world = mission.world();
                Unit footman = world.units().stream().filter(unit -> unit.tileX() == 21
                        && unit.tileY() == 5).findFirst().orElseThrow();
                Unit south = world.units().stream().filter(unit -> unit.tileX() == 20
                        && unit.tileY() == 31).findFirst().orElseThrow();
                Unit west = world.units().stream().filter(unit -> unit.tileX() == 3
                        && unit.tileY() == 27).findFirst().orElseThrow();
                CommandApplier commands = new CommandApplier(world,
                        new ArrayList<>(data.unitTypes().types().values()));
                data.configureCommands(commands);
                mission.tick();
                mission.tick();
                for (int cycle = 1; cycle <= 9; cycle++) {
                    if (cycle == 5) {
                        GameCommand first = switch (prior) {
                            case "stop" -> GameCommand.stop(person, footman.id());
                            case "stand-ground" -> GameCommand.standGround(person, footman.id());
                            default -> GameCommand.attack(person, footman.id(), south.id());
                        };
                        assertTrue(commands.apply(first), "the initial order must be accepted");
                    }
                    if (cycle == 6) {
                        assertTrue(commands.apply(march
                                ? GameCommand.attackMove(person, footman.id(), 6, 9)
                                : GameCommand.attack(person, footman.id(),
                                        prior.equals("attack") ? west.id() : south.id())),
                                "the replacement attack must be accepted");
                    }
                    mission.tick();
                    if (cycle >= 6 && cycle <= 8) {
                        assertEquals(21, footman.tileX(), prior + " replacement must serve its constructor");
                        assertEquals(5, footman.tileY(), prior + " replacement must retain its row");
                    }
                    if (cycle == 9) {
                        assertEquals(march || prior.equals("attack") ? 20 : 22, footman.tileX(),
                                "the replacement must reserve the native first column");
                        assertEquals(march ? 5 : 6, footman.tileY(),
                                "the replacement must reserve the native first row");
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("a moving ballista finishes its step before attacking and remains controllable")
    void movingBallistaFinishesItsStepBeforeAttacking() {
        GameData data = data();
        String map = "campaigns/human-exp/levelx10h";
        int person = GameData.personIn(data.campaignMap(map));
        Mission mission = data.loadMission(map, person, 1);
        assertNotNull(mission, "the retail campaign must load");
        World world = mission.world();
        Unit ballista = world.units().stream()
                .filter(unit -> unit.player() == person
                        && unit.tileX() == 88 && unit.tileY() == 90
                        && "unit-ballista".equals(unit.type().ident()))
                .findFirst().orElseThrow();
        Unit grunt = world.units().stream()
                .filter(unit -> unit.tileX() == 78 && unit.tileY() == 93
                        && "unit-grunt".equals(unit.type().ident()))
                .findFirst().orElseThrow();
        CommandApplier commands = new CommandApplier(world,
                new ArrayList<>(data.unitTypes().types().values()));
        // The native trace begins after two untimed bootstrap visits.
        mission.tick();
        mission.tick();
        for (int cycle = 1; cycle <= 600; cycle++) {
            if (cycle == 5) {
                assertTrue(commands.apply(GameCommand.move(
                        person, ballista.id(), 82, 85)), "Move must be accepted");
            }
            if (cycle == 23) {
                assertTrue(ballista.isMoving(), "the click must interrupt a live step");
                assertFalse(ballista.animation().unbreakable(),
                        "this must exercise native movement outside the presentation lock");
                assertTrue(commands.apply(GameCommand.attack(
                        person, ballista.id(), grunt.id())), "Attack must be accepted");
            }
            if (cycle == 100) {
                assertTrue(commands.apply(GameCommand.move(
                        person, ballista.id(), 94, 98)), "the later Move must be accepted");
            }
            mission.tick();
            if (cycle >= 23 && cycle < 44) {
                assertEquals(Unit.Order.MOVE, ballista.currentAction(),
                        "BNE keeps the old Move through fixture 43; cycle " + cycle);
                assertEquals(88, ballista.tileX(), "the committed step must retain its X");
                assertEquals(89, ballista.tileY(), "the committed step must retain its Y");
            }
            if (cycle == 44) {
                assertEquals(Unit.Order.ATTACK, ballista.currentAction(),
                        "BNE promotes Attack on the final Move pixel at fixture 44");
                assertEquals(0, ballista.offsetY(), "Attack must not strand movement pixels");
            }
        }
        assertTrue(ballista.isAlive(), "the commanded ballista must survive this witness");
        assertEquals(94, ballista.tileX(), "the later Move must reach its X without Stop");
        assertEquals(98, ballista.tileY(), "the later Move must reach its Y without Stop");
        assertFalse(ballista.hasQueuedOrders(), "the accepted Move must leave the queue");
    }

    @Test
    @DisplayName("a submarine keeps its committed stride before taking the next attack")
    void submarineKeepsItsCommittedStrideBeforeTakingTheNextAttack() {
        GameData data = data();
        String map = "campaigns/human-exp/levelx07h";
        int person = GameData.personIn(data.campaignMap(map));
        Mission mission = data.loadMission(map, person, 1);
        World world = mission.world();
        Unit submarine = world.units().stream()
                .filter(unit -> unit.player() == person
                        && unit.tileX() == 84 && unit.tileY() == 116
                        && "unit-human-submarine".equals(unit.type().ident()))
                .findFirst().orElseThrow();
        Unit destroyer = world.units().stream()
                .filter(unit -> unit.tileX() == 86 && unit.tileY() == 120
                        && "unit-orc-destroyer".equals(unit.type().ident()))
                .findFirst().orElseThrow();
        CommandApplier commands = new CommandApplier(world,
                new ArrayList<>(data.unitTypes().types().values()));
        mission.tick();
        mission.tick();
        for (int cycle = 1; cycle <= 600; cycle++) {
            if (cycle == 5) {
                assertTrue(commands.apply(GameCommand.move(
                        person, submarine.id(), 84, 120)), "the submarine must accept Move");
            }
            if (cycle == 15) {
                assertTrue(submarine.isMoving(), "Attack must arrive during the naval stride");
                assertFalse(submarine.animation().unbreakable(),
                        "the independent native Move program must own this stride");
                assertTrue(commands.apply(GameCommand.attack(
                        person, submarine.id(), destroyer.id())), "Attack must be accepted");
            }
            if (cycle == 60) {
                assertTrue(commands.apply(GameCommand.move(
                        person, submarine.id(), 80, 112)), "the withdrawal must be accepted");
            }
            mission.tick();
            if (cycle >= 15 && cycle < 52) {
                assertEquals(Unit.Order.MOVE, submarine.currentAction(),
                        "BNE retains the doubled Move through fixture 51; cycle " + cycle);
            }
            if (cycle == 52) {
                assertEquals(Unit.Order.ATTACK, submarine.currentAction(),
                        "BNE promotes the naval Attack at fixture 52");
                assertEquals(0, submarine.offsetY(), "the submarine must pay its last pixel");
            }
        }
        assertEquals(80, submarine.tileX(), "the submarine must withdraw without Stop");
        assertEquals(112, submarine.tileY(), "the submarine must reach the withdrawal point");
        assertFalse(submarine.hasQueuedOrders(), "the naval command must finish");
    }

    @Test
    @DisplayName("repeated attacks during pursuit leave a ballista able to withdraw")
    void repeatedAttacksDuringPursuitLeaveABallistaAbleToWithdraw() {
        GameData data = data();
        String map = "campaigns/human-exp/levelx10h";
        int person = GameData.personIn(data.campaignMap(map));
        int witnesses = 0;
        for (String pursuit : List.of("attack", "attack-move", "patrol")) {
            for (int click : List.of(23, 31, 44)) {
                Mission mission = data.loadMission(map, person, 1);
                World world = mission.world();
                Unit ballista = world.units().stream()
                        .filter(unit -> unit.player() == person
                                && unit.tileX() == 88 && unit.tileY() == 90
                                && "unit-ballista".equals(unit.type().ident()))
                        .findFirst().orElseThrow();
                Unit grunt = world.units().stream()
                        .filter(unit -> unit.tileX() == 78 && unit.tileY() == 93
                                && "unit-grunt".equals(unit.type().ident()))
                        .findFirst().orElseThrow();
                CommandApplier commands = new CommandApplier(world,
                        new ArrayList<>(data.unitTypes().types().values()));
                String witness = pursuit + " then Attack at " + click;
                mission.tick();
                mission.tick();
                for (int cycle = 1; cycle <= 700; cycle++) {
                    if (cycle == 5) {
                        GameCommand order = switch (pursuit) {
                            case "attack" -> GameCommand.attack(
                                    person, ballista.id(), grunt.id());
                            case "attack-move" -> GameCommand.attackMove(
                                    person, ballista.id(), 82, 85);
                            default -> GameCommand.patrol(person, ballista.id(), 82, 85);
                        };
                        assertTrue(commands.apply(order), witness + " must begin pursuit");
                    }
                    if (cycle == click) {
                        assertTrue(ballista.isMoving(), witness + " must interrupt a stride");
                        assertTrue(commands.apply(GameCommand.attack(
                                person, ballista.id(), grunt.id())),
                                witness + " must accept the repeated Attack");
                    }
                    if (cycle == 110) {
                        assertTrue(commands.apply(GameCommand.move(
                                person, ballista.id(), 94, 98)),
                                witness + " must accept withdrawal");
                    }
                    mission.tick();
                    if (pursuit.equals("attack") && click == 23 && cycle == 44) {
                        // Authenticated BNE fixture c209a0cc10870f54aa4d36dfd54fb3ab
                        // 44be5ce0142ca59b09c4664293897df3 applies all three commands.
                        // Native slot 1483 pays its last south pixel and clears
                        // next_order on cycle 44, while keeping Attack current.
                        assertEquals(88, ballista.tileX(), "BNE settles at X 88");
                        assertEquals(91, ballista.tileY(), "BNE settles at Y 91");
                        assertEquals(0, ballista.offsetY(),
                                "the replacement must inherit no unpaid south pixels");
                        assertFalse(ballista.queuedReplacementPending(),
                                "BNE promotes the repeated Attack on cycle 44");
                    }
                }
                assertTrue(ballista.isAlive(), witness + " must leave a surviving ballista");
                assertEquals(94, ballista.tileX(), witness + " must reach withdrawal X");
                assertEquals(98, ballista.tileY(), witness + " must reach withdrawal Y");
                assertFalse(ballista.hasQueuedOrders(), witness + " must finish without Stop");
                witnesses++;
            }
        }
        assertEquals(9, witnesses, "all three pursuit orders and three click phases must run");
    }

    @Test
    @DisplayName("a stopped submarine can withdraw from an automatically acquired fight")
    void stoppedSubmarineCanWithdrawFromAnAutomaticallyAcquiredFight() {
        GameData data = data();
        String map = "campaigns/human-exp/levelx07h";
        int person = GameData.personIn(data.campaignMap(map));
        // Before the old stride lands, Move replaces Stop (fixture
        // 645072fc7cddfdc2d958eb0392871d4b553b1d06f38a1c8e353b45893073793e).
        // After it lands, the completed Stop cannot cancel a later withdrawal.
        for (int withdrawal : List.of(75, 110)) {
            Mission mission = data.loadMission(map, person, 1);
            World world = mission.world();
            Unit submarine = world.units().stream()
                    .filter(unit -> unit.player() == person
                            && unit.tileX() == 84 && unit.tileY() == 116
                            && "unit-human-submarine".equals(unit.type().ident()))
                    .findFirst().orElseThrow();
            CommandApplier commands = new CommandApplier(world,
                    new ArrayList<>(data.unitTypes().types().values()));
            mission.tick();
            mission.tick();
            for (int cycle = 1; cycle <= 700; cycle++) {
                if (cycle == 5) {
                    assertTrue(commands.apply(GameCommand.move(
                            person, submarine.id(), 84, 120)), "the approach must be accepted");
                }
                if (cycle == 68) {
                    assertTrue(submarine.isMoving(), "Stop must interrupt the second naval stride");
                    assertTrue(commands.apply(GameCommand.stop(
                            person, submarine.id())), "Stop must be accepted");
                }
                if (cycle == withdrawal) {
                    if (withdrawal == 110) {
                        assertNotNull(submarine.target(), "the stopped submarine must acquire an enemy");
                    }
                    assertTrue(commands.apply(GameCommand.move(
                            person, submarine.id(), 80, 112)), "the withdrawal must be accepted");
                }
                mission.tick();
            }
            // BNE fixture 4b1d55896771f58c873debdb0f1c167d502b487d6ac7bddfce8f0184ff49e34c
            // applies these three commands without rejection and stands at 80,112
            // by cycle 500. A completed Stop must not cancel a later Move.
            assertTrue(submarine.isAlive(), "the withdrawing submarine must survive");
            assertEquals(80, submarine.tileX(), "the accepted withdrawal must reach X 80");
            assertEquals(112, submarine.tileY(), "the accepted withdrawal must reach Y 112");
            assertFalse(submarine.hasQueuedOrders(), "the withdrawal must finish without another Stop");
        }
    }

    @Test
    @DisplayName("an archer keeps moving when its quarry dies after a paid attack hold")
    void archerKeepsMovingWhenItsQuarryDiesAfterAPaidAttackHold() {
        GameData data = data();
        String map = "campaigns/human-exp/levelx09h";
        int person = GameData.personIn(data.campaignMap(map));
        Mission mission = data.loadMission(map, person, 1);
        World world = mission.world();
        CommandApplier commands = new CommandApplier(world,
                new ArrayList<>(data.unitTypes().types().values()));
        List<Unit> army = world.units().stream()
                .filter(unit -> unit.player() == person && unit.type().canAttack()
                        && unit.type().speed() > 0 && unit.isOnMap()).toList();
        assertEquals(18, army.size(), "the retail army must exercise all eighteen fighters");
        Unit archer = army.stream()
                .filter(unit -> unit.tileX() == 10 && unit.tileY() == 125
                        && "unit-archer".equals(unit.type().ident()))
                .findFirst().orElseThrow();
        // Repeated group orders make an acquired skeleton leave the archer's
        // paid firing hold and die during its next chase. No internal order,
        // animation, target, or movement field is injected by this witness.
        for (int cycle = 0; cycle < 650; cycle++) {
            if (cycle % 61 == 0) {
                for (Unit actor : army) {
                    if (!actor.isAlive()) {
                        continue;
                    }
                    Unit enemy = world.units().stream()
                            .filter(unit -> unit.isAlive() && unit.isOnMap()
                                    && unit.type().canAttack()
                                    && world.isEnemyPlayer(person, unit.player())
                                    && world.targets.canTarget(actor, unit))
                            .min(Comparator.comparingInt(unit ->
                                    Math.abs(unit.tileX() - actor.tileX())
                                            + Math.abs(unit.tileY() - actor.tileY())))
                            .orElse(null);
                    if (enemy != null) {
                        assertTrue(commands.apply(GameCommand.attack(
                                person, actor.id(), enemy.id())), "the group Attack must be accepted");
                    }
                }
            }
            if (cycle > 0 && cycle % 43 == 0) {
                for (Unit actor : army) {
                    if (actor.isAlive()) {
                        assertTrue(commands.apply(GameCommand.move(person, actor.id(),
                                Math.max(0, actor.tileX() - 6),
                                Math.max(0, actor.tileY() - 6))), "the group redirect must be accepted");
                    }
                }
            }
            mission.tick();
        }
        for (Unit actor : army) {
            if (actor.isAlive()) {
                assertTrue(commands.apply(GameCommand.move(person, actor.id(),
                        Math.min(world.map().width() - 4, actor.tileX() + 6),
                        Math.min(world.map().height() - 4, actor.tileY() + 6))),
                        "the final group Move must be accepted");
            }
        }
        for (int cycle = 0; cycle < 1_200; cycle++) {
            mission.tick();
        }
        assertTrue(archer.isAlive(), "the archer must survive to exercise later orders");
        assertFalse(archer.queuedReplacementPending(),
                "a dying quarry must not strand the archer's accepted Move for forty seconds");
        assertFalse(archer.hasQueuedOrders(), "the archer must consume the replacement without Stop");
    }
}
