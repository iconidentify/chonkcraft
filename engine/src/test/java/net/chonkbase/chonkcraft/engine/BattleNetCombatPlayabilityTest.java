package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Complete player-command fights using the retail BNE roster and missiles. */
class BattleNetCombatPlayabilityTest {

    private record Fixture(GameData data, World world, CommandApplier commands) {}

    private static Fixture fixture() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");

        GameData data = new GameData(assets);
        GameMap map = new GameMap(32, 32, new Tileset());
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
        world.establishDiplomacy();
        world.setUnitTypes(data.unitTypes().types());
        world.setUpgrades(data.upgrades().upgrades());
        world.setMissileTypes(data.missiles().types());
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        CommandApplier commands = new CommandApplier(
                world, new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);
        return new Fixture(data, world, commands);
    }

    @Test
    @DisplayName("the command boundary reports BNE refusals instead of pretending they worked")
    void rejectedOrdersAreReportedAsRejected() {
        Fixture fixture = fixture();
        Unit footman = place(fixture, "unit-footman", 0, 8, 8);
        Unit friend = place(fixture, "unit-footman", 0, 10, 8);

        assertTrue(fixture.commands().apply(
                        GameCommand.repair(0, footman.id(), friend.id())),
                "GiveOrder 27 on a footman was refused instead of becoming a walk");
        assertTrue(awaitOrder(fixture.world(), footman, Unit.Order.MOVE, 16),
                "a footman told to mend never left the native Still queue as a walk");
        // Empty send-home and GiveOrder 17 are not refusals. Native
        // NewActionReturnGoods walks an empty hull to the gold depot, and
        // commanded Orc 1 grunt 1592 takes attack-ground on grass.
        assertTrue(fixture.commands().apply(
                        GameCommand.returnGoods(0, footman.id())),
                "an empty send-home was refused");
        assertTrue(fixture.commands().apply(
                        GameCommand.attackGround(0, footman.id(), 12, 12)),
                "GiveOrder 17 on a footman was refused");
        assertTrue(awaitOrder(fixture.world(), footman, Unit.Order.ATTACK_GROUND, 16),
                "the queued footman attack-ground order never left Still");
        Unit peon = place(fixture, "unit-peon", 1, 8, 12);
        assertTrue(fixture.commands().apply(
                        GameCommand.attackGround(1, peon.id(), 14, 12)),
                "GiveOrder 17 on a peon was refused");
        assertFalse(fixture.commands().apply(
                        GameCommand.unload(0, footman.id(), 12, 12)),
                "a footman falsely accepted a transport-only unload order");
        assertFalse(fixture.commands().apply(
                        GameCommand.board(0, footman.id(), friend.id())),
                "a footman falsely accepted another footman as a transport");
        assertFalse(fixture.commands().apply(
                        GameCommand.unloadOne(0, footman.id(), friend.id())),
                "a footman falsely accepted a passenger-unload order");
        assertFalse(fixture.commands().apply(
                        GameCommand.autoCast(0, footman.id(), 0, true)),
                "a footman falsely accepted a caster-only standing spell");
        assertFalse(fixture.commands().apply(
                        GameCommand.rallyPoint(0, footman.id(), 12, 12)),
                "a footman falsely accepted a producer-only rally point");
    }

    @Test
    @DisplayName("a player-issued melee attack commits swings, deals damage and kills")
    void playerMeleeFightCompletes() {
        Fixture fixture = fixture();
        Unit footman = place(fixture, "unit-footman", 0, 8, 8);
        Unit grunt = place(fixture, "unit-grunt", 1, 10, 8);
        grunt.setHitPoints(Math.min(12, grunt.hitPoints()));
        int before = grunt.hitPoints();

        assertTrue(fixture.commands().apply(
                        GameCommand.attack(0, footman.id(), grunt.id())),
                "the wire command was refused");
        // Native GiveOrder 8 from Still with remaining wait keeps Still
        // until the marker. A freshly placed footman's Still timer is
        // 1..8, so the order may still be queued on the issue visit.
        boolean sawAttack = footman.order() == Unit.Order.ATTACK;
        boolean damaged = false;
        for (int cycle = 0; cycle < 2_000 && grunt.isAlive(); cycle++) {
            fixture.world().tick();
            sawAttack |= footman.order() == Unit.Order.ATTACK;
            damaged |= grunt.hitPoints() < before;
        }
        assertTrue(sawAttack, "the queued Attack never left Still");
        assertTrue(damaged, "the retail footman never landed a committed swing");
        assertFalse(grunt.isAlive(), "the melee fight never produced a death");
        assertTrue(footman.isAlive(), "the commanded attacker died before the fixture spoke");
    }

    @Test
    @DisplayName("a player-issued ranged attack creates a travelling retail projectile")
    void playerRangedAttackTravelsAndHits() {
        Fixture fixture = fixture();
        Unit archer = place(fixture, "unit-archer", 0, 5, 5);
        Unit grunt = place(fixture, "unit-grunt", 1, 9, 5);
        int before = grunt.hitPoints();

        fixture.commands().apply(GameCommand.attack(0, archer.id(), grunt.id()));
        boolean travelled = false;
        for (int cycle = 0; cycle < 2_000 && grunt.hitPoints() == before; cycle++) {
            fixture.world().tick();
            travelled |= !fixture.world().missiles().isEmpty();
        }
        assertTrue(travelled, "the archer's retail arrow never existed in flight");
        assertTrue(grunt.hitPoints() < before, "the travelling arrow never dealt damage");
    }

    @Test
    @DisplayName("retail ballista splash hits land and spares an adjacent aircraft")
    void playerSplashRespectsTheRetailTargetDomain() {
        Fixture fixture = fixture();
        Unit ballista = place(fixture, "unit-ballista", 0, 4, 14);
        Unit grunt = place(fixture, "unit-grunt", 1, 11, 14);
        Unit flyer = place(fixture, "unit-gryphon-rider", 1, 12, 14);
        int gruntBefore = grunt.hitPoints();
        int flyerBefore = flyer.hitPoints();

        assertTrue(fixture.commands().apply(GameCommand.attackGround(
                        0, ballista.id(), grunt.tileX(), grunt.tileY())),
                "the player attack-ground command was refused");
        // GiveOrder 17 may remain queued in Still and complete its firing
        // transition between observations. The observable contract here is
        // acceptance plus the retail projectile's target-domain effect.
        for (int cycle = 0; cycle < 2_000 && grunt.hitPoints() == gruntBefore; cycle++) {
            fixture.world().tick();
        }
        assertTrue(grunt.hitPoints() < gruntBefore,
                "the retail ballista blast missed its ground target");
        assertEquals(flyerBefore, flyer.hitPoints(),
                "land-only ballista splash damaged an aircraft");
    }

    @Test
    @DisplayName("a Ballista attack can be replaced by a player move")
    void aBallistaAttackCanBeReplacedByAPlayerMove() {
        Fixture fixture = fixture();
        Unit ballista = place(fixture, "unit-ballista", 0, 4, 4);
        Unit grunt = place(fixture, "unit-grunt", 1, 11, 4);

        assertTrue(fixture.commands().apply(GameCommand.attack(
                        0, ballista.id(), grunt.id())),
                "the Ballista attack was refused");
        boolean fired = false;
        for (int cycle = 0; cycle < 400 && !fired; cycle++) {
            fixture.world().tick();
            fired = fixture.world().missiles().stream().anyMatch(missile ->
                    "missile-ballista-bolt".equals(missile.type().ident()));
        }
        assertTrue(fired, "the Ballista never entered its committed volley");

        assertTrue(fixture.commands().apply(GameCommand.move(
                        0, ballista.id(), 4, 24)),
                "the player Move was refused during the Ballista volley");
        boolean moved = false;
        for (int cycle = 0; cycle < 4_000
                && (ballista.order() != Unit.Order.STILL
                        || ballista.hasQueuedOrders()); cycle++) {
            fixture.world().tick();
            moved |= ballista.tileY() > 4;
        }

        assertTrue(moved, "the Ballista never moved after accepting the command");
        assertEquals(Unit.Order.STILL, ballista.order(),
                "the Ballista never completed the replacement Move");
        assertTrue(ballista.tileY() >= 22,
                "the Ballista stopped before the player's destination: "
                        + ballista.tileX() + "," + ballista.tileY());
        assertEquals(null, ballista.target(),
                "the replaced attack kept ownership of its old target");
        assertFalse(ballista.fighting(),
                "the Ballista completed Move while still marked as firing");
        assertFalse(ballista.chasing(),
                "the Ballista completed Move while still marked as chasing");
    }

    @Test
    @DisplayName("a commanded Gryphon damages a building and can be redirected")
    void aCommandedGryphonDamagesABuildingAndCanBeRedirected() {
        Fixture fixture = fixture();
        Unit gryphon = place(fixture, "unit-gryphon-rider", 0, 4, 8);
        Unit farm = place(fixture, "unit-farm", 1, 12, 8);
        int before = farm.hitPoints();

        assertTrue(fixture.commands().apply(GameCommand.attack(
                        0, gryphon.id(), farm.id())),
                "the Gryphon attack on the building was refused");
        LinkedHashSet<String> missilesSeen = new LinkedHashSet<>();
        for (int cycle = 0; cycle < 2_000 && farm.hitPoints() == before; cycle++) {
            fixture.world().tick();
            fixture.world().missiles().forEach(missile ->
                    missilesSeen.add(missile.type().ident()));
        }
        assertTrue(farm.hitPoints() < before,
                "the Gryphon projectile never damaged the building: order="
                        + gryphon.order() + ", at=" + gryphon.tileX() + ","
                        + gryphon.tileY() + ", target="
                        + (gryphon.target() == null ? "null"
                                : gryphon.target().type().ident())
                        + ", fighting=" + gryphon.fighting() + ", chasing="
                        + gryphon.chasing() + ", missile="
                        + gryphon.type().missile() + ", seen=" + missilesSeen);

        assertTrue(fixture.commands().apply(GameCommand.move(
                        0, gryphon.id(), 20, 24)),
                "the Gryphon refused a player redirect after attacking");
        boolean moved = false;
        for (int cycle = 0; cycle < 4_000
                && (gryphon.order() != Unit.Order.STILL
                        || gryphon.hasQueuedOrders()); cycle++) {
            fixture.world().tick();
            moved |= gryphon.tileX() > 4 || gryphon.tileY() > 8;
        }
        assertTrue(moved, "the Gryphon never moved after accepting the redirect");
        assertEquals(Unit.Order.STILL, gryphon.order(),
                "the Gryphon never completed the redirect");
        assertEquals(null, gryphon.target(),
                "the redirected Gryphon retained the building attack order");
        assertFalse(gryphon.fighting(),
                "the redirected Gryphon retained its firing state");
        assertFalse(gryphon.chasing(),
                "the redirected Gryphon retained its chase state");
    }

    @Test
    @DisplayName("a player move cannot be stolen by an attack-move reacquire")
    void aPlayerMoveReplacesAnEngagedAttackMoveAfterCommittedPixels() {
        Fixture fixture = fixture();
        fixture.data().configureWorld(fixture.world(), PudMap.Tileset.FOREST);
        Unit grunt = place(fixture, "unit-grunt", 0, 8, 8);
        Unit enemy = place(fixture, "unit-footman", 1, 12, 8);

        assertTrue(fixture.commands().apply(GameCommand.attackMove(
                        0, grunt.id(), 24, 8)),
                "the opening attack-move was refused");
        boolean engaged = false;
        for (int cycle = 0; cycle < 1_000 && !engaged; cycle++) {
            fixture.world().tick();
            engaged = grunt.target() == enemy
                    && (grunt.fighting() || grunt.chasing())
                    && (grunt.residualX() != 0 || grunt.residualY() != 0);
        }
        assertTrue(engaged,
                "the attack-move never acquired with committed pixels in flight");

        // The retained retail replay's record 3771 applies this same Move to
        // native slot 1539 while it reports Attack-Move. The pinned binary's
        // GiveOrder path (451070 -> 4513d0 -> 438410) releases the old attack
        // goal at unit+0x88 after installing the replacement. A live stride
        // may delay Move; settled animation overshoot must be discarded. In
        // neither case may the destroyed Attack-Move reacquire its quarry.
        assertTrue(fixture.commands().apply(GameCommand.move(
                        0, grunt.id(), 8, 24)),
                "the redirect was refused during the engagement");
        boolean moved = false;
        for (int cycle = 0; cycle < 1_000 && !moved; cycle++) {
            fixture.world().tick();
            moved = grunt.tileY() > 8 || grunt.offsetY() != 0;
        }

        assertTrue(moved,
                "the old attack-move reacquired its target and stole the redirect");
        assertEquals(null, grunt.target(),
                "the replaced attack-move regained combat ownership");
        assertTrue(grunt.order() == Unit.Order.MOVE
                        || grunt.order() == Unit.Order.STILL,
                "the replacement never became the current order: " + grunt.order());
    }

    private static Unit place(Fixture fixture, String ident, int player, int x, int y) {
        UnitType type = fixture.data().unitTypes().types().get(ident);
        assertNotNull(type, "retail roster has no " + ident);
        Unit unit = fixture.world().createUnit(type, player, x, y);
        assertNotNull(unit, "could not place " + ident + " at " + x + "," + y);
        return unit;
    }

    /** Retail queues a click made during the unit's remaining Still wait. */
    private static boolean awaitOrder(World world, Unit unit, Unit.Order expected, int cycles) {
        for (int cycle = 0; cycle <= cycles; cycle++) {
            if (unit.order() == expected) {
                return true;
            }
            world.tick();
        }
        return false;
    }
}
