package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import net.chonkbase.chonkcraft.data.map.PudUnitTypes;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.animation.BattleNetSequence;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Player-visible BNE attack-move certification scenarios. */
class BattleNetAttackMovePlayabilityTest {

    private static GameMap openField(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static UnitType soldier(String ident) {
        UnitType type = new UnitType(ident);
        type.setName(ident);
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(6);
        type.setMaxAttackRange(1);
        type.setSightRange(9);
        type.setReactRangePerson(6);
        type.setReactRangeComputer(6);
        type.setMissile("missile-none");
        AnimationSet set = new AnimationSet("soldier");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        // The two halves and unbreakable boundary are important. A one-line
        // move lets the order scan on a beat retail gives to the committed
        // step and previously made the system test a false positive.
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 16", "wait 1",
                "frame 5", "move 16", "unbreakable end", "wait 1")));
        set.put(AnimationSet.State.ATTACK, Animation.parse("attack", List.of(
                "unbreakable begin", "frame 5", "wait 1",
                "frame 10", "attack", "unbreakable end", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    private static World twoSideField() {
        World world = new World(openField(48));
        world.setAllied(0, 1, false);
        return world;
    }

    private static byte[] retailScriptBin() throws IOException {
        String packProp = System.getProperty("chonkcraft.pack");
        Path pack = packProp != null && !packProp.isBlank()
                ? Path.of(packProp)
                : Path.of(System.getProperty("user.home"), ".chonkcraft/work",
                        "warcraft-ii-battle-net-edition-usa.pre-full-media-2026-07-30.chonkpack");
        assumeTrue(Files.isRegularFile(pack),
                "BNE asset pack required for retail Move sequence");
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            var entry = zip.getEntry("assets/archives/maindat/0278.bin");
            assumeTrue(entry != null, "pack must contain maindat entry 278");
            try (var in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    @Test
    @DisplayName("a player attack-move interrupts, wins, and resumes")
    void playerAttackMoveCompletesTheCombatLoop() {
        World world = twoSideField();
        UnitType marcherType = soldier("unit-footman");
        UnitType picketType = soldier("unit-grunt");
        picketType.setCanAttack(false);
        picketType.setSpeed(0);
        Unit marcher = world.createUnit(marcherType, 0, 4, 20);
        Unit picket = world.createUnit(picketType, 1, 18, 20);
        picket.setHitPoints(6);

        CommandApplier commands = new CommandApplier(
                world, List.of(marcherType, picketType));
        commands.apply(GameCommand.attackMove(0, marcher.id(), 44, 20));

        boolean acquiredBeforeDestination = false;
        boolean dealtDamage = false;
        boolean resumedAfterKill = false;
        for (int cycle = 0; cycle < 3000 && marcher.order() != Unit.Order.STILL;
                cycle++) {
            int before = picket.hitPoints();
            world.tick();
            acquiredBeforeDestination |= marcher.target() == picket
                    && marcher.tileX() < 44;
            dealtDamage |= picket.hitPoints() < before;
            resumedAfterKill |= !picket.isAlive() && marcher.tileX() > 24;
        }

        assertTrue(acquiredBeforeDestination,
                "the command reached its destination before detecting the picket");
        assertTrue(dealtDamage, "the acquired enemy was never damaged");
        assertFalse(picket.isAlive(), "the combat never resolved");
        assertTrue(resumedAfterKill,
                "the marcher stopped at the fight instead of resuming its destination");
        assertTrue(marcher.tileX() > 40,
                "the resumed march never completed its player-visible advance");
    }

    @Test
    @DisplayName("a blocked attack-move recovers when the obstruction clears")
    void blockedAttackMoveDoesNotDeadlock() {
        World world = twoSideField();
        UnitType marcherType = soldier("unit-footman");
        UnitType blockerType = soldier("unit-friendly-blocker");
        blockerType.setSpeed(0);
        blockerType.setCanAttack(false);
        Unit marcher = world.createUnit(marcherType, 0, 4, 20);
        Unit blocker = world.createUnit(blockerType, 0, 5, 20);

        // Make a one-tile corridor so the first route cannot silently avoid
        // the obstruction and cease testing refused-step recovery.
        for (int y = 0; y < world.map().height(); y++) {
            if (y == 20) {
                continue;
            }
            for (int x = 0; x < world.map().width(); x++) {
                world.map().field(x, y).setFlags(TileFlag.UNPASSABLE);
            }
        }

        CommandApplier commands = new CommandApplier(
                world, List.of(marcherType, blockerType));
        commands.apply(GameCommand.attackMove(0, marcher.id(), 40, 20));
        for (int cycle = 0; cycle < 20; cycle++) {
            world.tick();
        }
        assertEquals(4, marcher.tileX(),
                "the marcher crossed an occupied one-tile corridor");

        // The referee clears a temporary obstruction, then observes only
        // ordinary engine behavior. It does not repair or reissue the order.
        world.kill(blocker);
        for (int cycle = 0; cycle < 2000 && marcher.order() != Unit.Order.STILL;
                cycle++) {
            world.tick();
        }
        // The first failed plans widen the position order's legal arrival
        // radius. That exact endpoint is a precision question; the system
        // contract here is that the original command wakes and advances once
        // the temporary obstruction no longer occupies the corridor.
        assertTrue(marcher.tileX() > 30,
                "the original attack-move stayed deadlocked after the route cleared");
        assertEquals(Unit.Order.STILL, marcher.order(),
                "the recovered command never reached a completed state");
    }

    @Test
    @DisplayName("a native empty-route refusal still reaches the attack-move scan")
    void emptyRouteRefusalDoesNotFreezeBehindThePresentationMoveTail()
            throws Exception {
        // Human expansion 3 save, runtime grunt 313: its last chase heading
        // settled at 44,119 three tiles from footman 338. The native Move OP0
        // kept returning a two-cycle empty-route refusal, but the parallel
        // presentation Move script was parked just before unbreakable-end.
        // Treating that cosmetic bit as the native gate starved every target
        // scan forever, so the enemy appeared frozen in place.
        byte[] script = retailScriptBin();
        World world = twoSideField();
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setBattleNetSequenceData(script);

        UnitType gruntType = soldier("unit-grunt");
        gruntType.setReactRangePerson(8);
        UnitType footmanType = soldier("unit-footman");
        Unit grunt = world.createUnit(gruntType, 0, 10, 10);
        Unit footman = world.createUnit(footmanType, 1, 7, 10);
        assertTrue(world.orderAttackMove(grunt, 4, 10));
        grunt.setAttackMoveOpening(false);
        grunt.setAutoTargeting(true);
        grunt.setTarget(footman);
        grunt.setChasing(true);
        grunt.setFighting(false);
        grunt.setPathGoal(footman.tileX(), footman.tileY());
        grunt.setRouteSpent(true);
        grunt.setAttackScanSleep(0);

        Animation move = gruntType.animationSet().get(AnimationSet.State.MOVE);
        grunt.animation().restore(move, move.size() - 3, 0, true);
        BattleNetSequence sequence = new BattleNetSequence(script);
        int moveStart = sequence.sequenceStart(
                PudUnitTypes.code(gruntType.ident()),
                BattleNetSequence.MOVE_ANIMATION);
        assertTrue(moveStart >= 0, "retail grunt Move sequence must resolve");
        grunt.setBattleNetSequenceOffset(moveStart);
        grunt.setBattleNetAnimationTimer(1);

        world.combat.stepAttackMove(grunt);

        assertEquals(World.ATTACK_SCAN_INTERVAL, grunt.attackScanSleep(),
                "the native PF_WAIT boundary never reached AutoSelectTarget; "
                        + "underway state path=" + grunt.pathLength()
                        + " spent=" + grunt.routeSpent()
                        + " wait=" + grunt.waitCycles()
                        + " chasing=" + grunt.chasing()
                        + " moving=" + grunt.isMoving()
                        + " seq=" + grunt.battleNetSequenceOffset());
    }

    @Test
    @DisplayName("attack-move aimed at a visible wall becomes bombardment")
    void visibleWallUsesTheCombatOrder() {
        GameMap map = openField(32);
        map.field(20, 20).setFlags(TileFlag.LAND_ALLOWED | TileFlag.WALL
                | TileFlag.HUMAN | TileFlag.UNPASSABLE);
        map.field(20, 20).setValue(GameMap.WALL_HIT_POINTS);
        World world = new World(map);
        UnitType footmanType = soldier("unit-footman");
        Unit footman = world.createUnit(footmanType, 0, 18, 20);
        CommandApplier commands = new CommandApplier(world, List.of(footmanType));

        commands.apply(GameCommand.attackMove(0, footman.id(), 20, 20));

        assertEquals(Unit.Order.ATTACK_GROUND, footman.order(),
                "the visible wall was treated as a walk destination");
    }
}
