package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.pathfinder.PathFinder;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a chaser owes on the cycle it starts moving again.
 *
 * <p>Nothing. A unit that has come to rest exactly on its square and then takes
 * another step is drawn a whole square back on that cycle and starts closing
 * the gap on the next one, which is how retail opens every new leg. This implementation
 * opened a leg that way only when the unit had never been chasing; a chase that
 * paused and resumed spent the first pace immediately.
 *
 * <p>Human 13's ogre 1482 is the witness. It pauses on 124,32 for fixture
 * cycles 31 to 33 and steps at 34, where retail leaves it drawn at 3968,1024
 * and this implementation drew it at 3965,1021. Every pixel after that matched retail
 * exactly and a cycle early, so it finished arriving at 45 instead of 46,
 * started its attack program a cycle early, and wounded the wise man at 52
 * where retail does it at 53.
 */
class ColdChaseLegPaceTest {

    private static byte[] retailScriptBin() throws IOException {
        String packProp = System.getProperty("chonkcraft.pack");
        if (packProp == null || packProp.isBlank()) {
            packProp = System.getenv("CHONKCRAFT_ASSET_PACK");
        }
        assumeTrue(packProp != null && !packProp.isBlank(),
                "BNE asset pack path required via chonkcraft.pack or CHONKCRAFT_ASSET_PACK");
        Path pack = Path.of(packProp);
        assumeTrue(Files.isRegularFile(pack),
                "BNE asset pack required for the retail Move sequence");
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            var entry = zip.getEntry("assets/archives/maindat/0278.bin");
            assumeTrue(entry != null, "pack must contain maindat entry 278");
            try (var in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static UnitType ogre() {
        UnitType type = new UnitType("unit-ogre");
        type.setTileSize(1, 1);
        type.setHitPoints(90);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setMaxAttackRange(1);
        type.setSightRange(6);
        type.setReactRangeComputer(6);
        type.setReactRangePerson(6);
        AnimationSet set = new AnimationSet("ogre");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 16", "wait 1",
                "frame 5", "move 16", "unbreakable end", "wait 1")));
        set.put(AnimationSet.State.ATTACK, Animation.parse("attack", List.of(
                "unbreakable begin", "frame 25", "wait 3", "frame 30",
                "attack", "unbreakable end", "wait 5")));
        return type;
    }

    private static UnitType prey() {
        UnitType type = new UnitType("unit-peasant");
        type.setTileSize(1, 1);
        type.setHitPoints(40);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setSightRange(4);
        AnimationSet set = new AnimationSet("peasant");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 16", "wait 1",
                "frame 5", "move 16", "unbreakable end", "wait 1")));
        return type;
    }

    @Test
    @DisplayName("a chaser that stopped and starts again is drawn a whole square back on the cycle it steps")
    void aResumedChaseLegSpendsNothingOnTheCycleItSteps() throws Exception {
        GameMap map = grass(40);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.setBattleNetSequenceData(retailScriptBin());
        world.restoreRandom(1, 0);

        Unit chaser = world.createUnit(ogre(), 0, 20, 20);
        Unit quarry = world.createUnit(prey(), 1, 26, 20);
        assertTrue(chaser != null && quarry != null, "both units must place");
        assertTrue(world.orderAttack(chaser, quarry), "the attack was refused");

        // Standing exactly on its square, mid-chase, with the retail Move body
        // already armed -- the state the ogre is in after its pause.
        world.combat.armBattleNetChaseMoveBody(chaser);
        assumeTrue(world.combat.onBattleNetChaseMoveBody(chaser),
                "the retail Move body must be armed for a chase-sequence step");
        int east = Direction.fromDelta(1, 0);
        chaser.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {east, east}));
        chaser.setPathGoal(quarry.tileX(), quarry.tileY());
        chaser.setTarget(quarry);
        chaser.setChasing(true);
        chaser.setOffset(0, 0);
        chaser.setResidual(0, 0);
        chaser.setWalkHolding(true);
        chaser.setStepDrained(true);
        // The retail Move body decides its next heading on OP0; arm that so
        // this visit is the one the step commits on.
        chaser.setBattleNetChaseStepReady(true);

        int fromX = chaser.tileX();
        world.movement.stepMove(chaser, false);

        assertEquals(fromX + 1, chaser.tileX(),
                "the chaser was supposed to take its eastward step this cycle");
        assertEquals(-Unit.TILE_PIXELS, chaser.offsetX(),
                "a chase leg that opens from a standstill owes no pace on the "
                        + "cycle it steps: the unit stands on the new square "
                        + "and is drawn a whole square back, and only starts "
                        + "closing that gap next cycle");
    }
}
