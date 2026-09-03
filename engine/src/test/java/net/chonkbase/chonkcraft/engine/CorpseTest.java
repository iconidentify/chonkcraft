package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationCatalog;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.unit.UnitTypeCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What is left where a unit fell, and whether it is the same unit.
 *
 * <p>It is. {@code COrder_Die::Execute} does not make a body, it turns the unit
 * into one:
 *
 * <pre>
 * unit.Remove(nullptr);
 * unit.Type = &amp;corpseType;
 * UpdateUnitSightRange(unit);
 * unit.Place(unit.tilePos);
 * unit.Frame = 0;
 * UnitUpdateHeading(unit);
 * AnimateActionDie(unit); // with new corpse.
 * </pre>
 *
 * <p>So the body keeps the dead unit's slot, its owner and its heading, and
 * only a type that leaves nothing behind is removed and released. This implementation
 * made a new unit for the neutral player instead, which is wrong three ways at
 * once, and all three show on {@code maps/demo/demo02} at cycle 120 where a
 * peasant finishes dying at 0,25: the body is a different unit, so the parity
 * harness cannot pair it with upstream's; it belongs to nobody rather than to
 * the peasant's player; and making a unit draws a heading from the shared
 * random stream, so this implementation drew where upstream did not and every roll
 * afterwards differed. That map's first divergence moved from cycle 120 to 158.
 */
class CorpseTest {

    @Test
    @DisplayName("BNE uses its type-105 body for both land-unit race families")
    void generatedLandUnitsUseTheRetailUnifiedCorpseType() {
        UnitTypeCatalog catalog = UnitTypeCatalog.generated(
                AnimationCatalog.generated());
        for (String ident : List.of("unit-grunt", "unit-ogre", "unit-peon",
                "unit-axethrower")) {
            assertEquals("unit-human-dead-body",
                    catalog.types().get(ident).corpse(), ident);
        }
    }

    @Test
    @DisplayName("BNE uses its type-105 body for both naval race families")
    void generatedNavalUnitsUseTheRetailUnifiedCorpseType() {
        UnitTypeCatalog catalog = UnitTypeCatalog.generated(
                AnimationCatalog.generated());
        // The sealed corpus directly witnesses each of these four classes,
        // spanning both destroyer factions and both capital-ship factions.
        for (String ident : List.of("unit-human-destroyer", "unit-orc-destroyer",
                "unit-battleship", "unit-ogre-juggernaught")) {
            assertEquals("unit-human-dead-body",
                    catalog.types().get(ident).corpse(), ident);
        }
    }

    @Test
    @DisplayName("unified mobile corpses do not change other death families")
    void generatedUnifiedCorpsesLeaveHeldOutDeathFamiliesAlone() {
        UnitTypeCatalog catalog = UnitTypeCatalog.generated(
                AnimationCatalog.generated());

        assertEquals("unit-destroyed-2x2-place",
                catalog.types().get("unit-human-guard-tower").corpse());
        assertEquals("unit-destroyed-3x3-place",
                catalog.types().get("unit-human-barracks").corpse());
        assertEquals("", catalog.types().get("unit-balloon").corpse());
        assertEquals("", catalog.types().get("unit-zeppelin").corpse());
    }

    @Test
    @DisplayName("BNE infantry bodies hold four visible decay intervals")
    void generatedInfantryBodiesUseTheRetailDecayProgram() {
        AnimationCatalog catalog = AnimationCatalog.generated();
        for (String name : List.of("animations-human-dead-body", "animations-orc-dead-body")) {
            Animation death = catalog.sets().get(name).get(AnimationSet.State.DEATH);
            long longHolds = death.instructions().stream()
                    .filter(instruction -> instruction.kind() == Animation.Kind.WAIT
                            && instruction.value() == 200)
                    .count();
            assertEquals(4, longHolds,
                    name + " held its final displayed frame for a fifth 200-cycle interval");
            assertEquals(801, death.cycles(),
                    name + " no longer matches BNE's four decay intervals plus tail tick");
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

    /** The body: it lies there for a while and then goes. */
    private static UnitType deadBody() {
        UnitType type = new UnitType("unit-human-dead-body");
        type.setTileSize(1, 1);
        type.setHitPoints(1);
        type.setVanishes(true);
        type.setNumDirections(1);
        AnimationSet set = new AnimationSet("dead-body");
        set.put(AnimationSet.State.DEATH, Animation.parse("death",
                List.of("frame 0", "wait 20")));
        type.setAnimationSet(set);
        return type;
    }

    private static UnitType fadingBody(String ident, boolean revealer) {
        UnitType type = new UnitType(ident);
        type.setTileSize(1, 1);
        type.setHitPoints(1);
        type.setVanishes(true);
        type.setRevealer(revealer);
        type.setNumDirections(1);
        AnimationSet set = new AnimationSet("fading-body");
        set.put(AnimationSet.State.DEATH, Animation.parse("death",
                List.of("unbreakable begin", "frame 0", "wait 3",
                        "frame 10", "wait 3", "unbreakable end", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    private static UnitType peasant() {
        UnitType type = new UnitType("unit-peasant");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setNumDirections(8);
        type.setSightRange(4);
        type.setCorpse("unit-human-dead-body");
        AnimationSet set = new AnimationSet("peasant");
        set.put(AnimationSet.State.STILL, Animation.parse("still",
                List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.DEATH, Animation.parse("death",
                List.of("frame 5", "wait 4", "frame 10", "wait 4")));
        type.setAnimationSet(set);
        return type;
    }

    private static World world() {
        World world = new World(grass(20));
        UnitType body = deadBody();
        world.setUnitTypes(Map.of(body.ident(), body));
        return world;
    }

    @Test
    @DisplayName("death replaces a queued order's old action label immediately")
    void deathDoesNotReportTheFinishedCommandHead() {
        World world = world();
        Unit worker = world.createUnit(peasant(), 3, 5, 5);
        worker.setOrder(Unit.Order.ATTACK_MOVE);
        worker.setActionBeforeQueued(Unit.Order.MOVE);

        world.kill(worker);

        assertEquals(Unit.Order.DYING, worker.order());
        assertEquals(Unit.Order.DYING, worker.currentAction(),
                "LetUnitDie left CurrentAction on the command queue's old head");
    }

    @Test
    @DisplayName("a dead unit becomes its own body rather than being replaced by one")
    void theBodyIsTheSameUnit() {
        World world = world();
        Unit worker = world.createUnit(peasant(), 3, 5, 5);
        world.kill(worker);

        for (int cycle = 0; cycle < 40
                && !"unit-human-dead-body".equals(worker.type().ident()); cycle++) {
            world.tick();
        }

        assertEquals("unit-human-dead-body", worker.type().ident(),
                "the peasant never turned into a body at all, so nothing below is measured");
        assertSame(worker, world.unitAt(5, 5) == null ? worker : world.unitAt(5, 5),
                "something else is standing where the peasant fell");
        assertEquals(3, worker.player(),
                "the body was handed to somebody else. Upstream changes the type and leaves"
                        + " Player alone, so the corpse belongs to whoever it belonged to"
                        + " alive -- which costs that player nothing, because a vanishing"
                        + " type is skipped by the counters either way");
        assertEquals(1, world.unitsSnapshot().stream()
                        .filter(unit -> "unit-human-dead-body".equals(unit.type().ident()))
                        .count(),
                "there is more than one body for one peasant, so a second was made rather"
                        + " than the first being turned into it");
    }

    @Test
    @DisplayName("a body's first completed decay hold hands its scenery to neutral")
    void theFirstDecayFrameHandsTheBodyToNeutral() {
        UnitType body = fadingBody("unit-human-dead-body", false);
        World world = new World(grass(20));
        world.setUnitTypes(Map.of(body.ident(), body));
        UnitType living = peasant();
        living.setCorpse(body.ident());
        Unit worker = world.createUnit(living, 3, 5, 5);
        world.kill(worker);

        while (!"unit-human-dead-body".equals(worker.type().ident())) {
            world.tick();
        }
        assertEquals(3, worker.player(),
                "becoming a corpse itself must retain the living owner");

        while (worker.animation().waitCycles() > 1) {
            world.tick();
            assertEquals(3, worker.player(),
                    "the corpse became neutral before its first decay hold expired");
        }
        world.tick();
        assertEquals(3, worker.player(),
                "finishing the wait alone must not predate the next decay frame");
        world.tick();
        assertEquals(World.NEUTRAL_PLAYER, worker.player(),
                "the first decay-frame transition did not make the corpse neutral");
    }

    @Test
    @DisplayName("a revealed death marker stays outside the corpse handoff")
    void aRevealerDoesNotUseTheCorpseNeutralHandoff() {
        UnitType marker = fadingBody("unit-dead-vision-1-4", true);
        World world = new World(grass(20));
        world.setUnitTypes(Map.of(marker.ident(), marker));
        Unit unit = world.createUnit(marker, 3, 5, 5);
        unit.setOrder(Unit.Order.DYING);

        for (int cycle = 0; cycle < 5; cycle++) {
            world.tick();
        }

        assertEquals(3, unit.player(),
                "a revealer is a timed vision record, not decaying corpse scenery");
    }

    @Test
    @DisplayName("becoming a body costs nothing from the shared random stream")
    void theBodyIsNotANewDraw() {
        World world = world();
        Unit worker = world.createUnit(peasant(), 3, 5, 5);
        world.kill(worker);

        // Run to one cycle short of the change, so that whatever the death
        // animation itself spends is already spent and the reading below is
        // only about the body.
        int before = 0;
        for (int cycle = 0; cycle < 40; cycle++) {
            before = world.randomSeed();
            world.tick();
            if ("unit-human-dead-body".equals(worker.type().ident())) {
                break;
            }
        }

        assertEquals("unit-human-dead-body", worker.type().ident(),
                "the peasant never turned into a body at all");
        assertEquals(before, world.randomSeed(),
                "the cycle the body appeared spent a number from the shared stream, which is"
                        + " what making a unit costs -- CUnit::Init draws an opening heading"
                        + " for anything that faces more than one way. Upstream changes the"
                        + " type in place and draws nothing, and a draw this port makes and"
                        + " upstream does not puts every later roll out of step");
    }

    @Test
    @DisplayName("the corpse animation starts on the cycle the dead unit becomes it")
    void theCorpseAnimationStartsDuringTheTypeChange() {
        World world = world();
        Unit worker = world.createUnit(peasant(), 3, 5, 5);
        world.kill(worker);

        for (int cycle = 0; cycle < 40
                && !"unit-human-dead-body".equals(worker.type().ident()); cycle++) {
            world.tick();
        }

        assertEquals("unit-human-dead-body", worker.type().ident(),
                "the fixture never reached the corpse transition");
        assertSame(worker.type().animationSet().get(AnimationSet.State.DEATH),
                worker.animation().current(),
                "COrder_Die did not call AnimateActionDie again after installing the"
                        + " corpse type");
    }

    @Test
    @DisplayName("a type that leaves nothing behind is still taken off the map")
    void aTypeWithNoCorpseIsReleased() {
        World world = world();
        UnitType type = peasant();
        type.setCorpse(null);
        Unit worker = world.createUnit(type, 3, 5, 5);
        world.kill(worker);

        for (int cycle = 0; cycle < 40 && worker.isOnMap(); cycle++) {
            world.tick();
        }

        assertTrue(!worker.isOnMap(),
                "a unit with no corpse type has to go: upstream's Remove and Release are the"
                        + " whole of the CorpseType == nullptr arm");
    }

    @Test
    @DisplayName("a referenced dead unit retains its global-table slot")
    void aReferencedDeathWaitsForItsLastPointer() {
        World world = world();
        UnitType type = peasant();
        type.setCorpse(null);
        Unit victim = world.createUnit(type, 3, 4, 5);
        Unit holder = world.createUnit(type, 3, 8, 5);
        Unit tail = world.createUnit(type, 3, 12, 5);
        holder.setTarget(victim);
        holder.setOrder(Unit.Order.ATTACK);
        holder.setWaitCycles(100);
        world.kill(victim);

        for (int cycle = 0; cycle < 40 && !victim.destroyed(); cycle++) {
            world.tick();
        }

        assertTrue(victim.destroyed(), "the fixture never finished the death animation");
        assertEquals(List.of(victim, holder, tail), world.unitsSnapshot(),
                "CUnit::Release must not mutate the global table while an order still holds"
                        + " a CUnitPtr to the Destroyed unit");

        holder.setTarget(null);
        world.tick();
        assertEquals(List.of(tail, holder), world.unitsSnapshot(),
                "the final pointer release must swap the table's last unit into the hole");
    }

    @Test
    @DisplayName("a stale target field on a still order is not a reference")
    void aStillOrdersStaleTargetDoesNotRetainTheDead() {
        World world = world();
        UnitType type = peasant();
        type.setCorpse(null);
        Unit victim = world.createUnit(type, 3, 4, 5);
        Unit stale = world.createUnit(type, 3, 8, 5);
        Unit tail = world.createUnit(type, 3, 12, 5);
        stale.setTarget(victim);
        world.kill(victim);

        for (int cycle = 0; cycle < 40 && world.unitsSnapshot().contains(victim); cycle++) {
            world.tick();
        }

        assertEquals(List.of(tail, stale), world.unitsSnapshot(),
                "COrder_Still owns no Goal CUnitPtr; shared Java target storage must not"
                        + " retain the dead body or suppress the swap-last release");
    }

    @Test
    @DisplayName("a stationary still order's attack goal is a real reference")
    void aStationaryStillOrdersAttackGoalRetainsTheDead() {
        World world = world();
        UnitType victimType = peasant();
        victimType.setCorpse(null);
        UnitType tower = peasant();
        tower.setSpeed(0);
        Unit victim = world.createUnit(victimType, 3, 4, 5);
        Unit holder = world.createUnit(tower, 3, 8, 5);
        Unit tail = world.createUnit(victimType, 3, 12, 5);
        holder.setTarget(victim);
        world.kill(victim);

        for (int cycle = 0; cycle < 40 && !victim.destroyed(); cycle++) {
            world.tick();
        }

        assertTrue(victim.destroyed(), "the fixture never finished the death animation");
        assertEquals(List.of(victim, holder, tail), world.unitsSnapshot(),
                "COrder_Still::AutoAttackStand stores a stationary attack target in the"
                        + " Still order's own Goal CUnitPtr");

        holder.setTarget(null);
        world.tick();
        assertEquals(List.of(tail, holder), world.unitsSnapshot(),
                "clearing the in-place attack goal must perform the deferred release");
    }

    @Test
    @DisplayName("death destroys the attack order's offered-target reference")
    void anOfferedTargetDoesNotSurviveItsOwnersDeath() {
        World world = world();
        UnitType type = peasant();
        type.setCorpse(null);
        Unit victim = world.createUnit(type, 3, 4, 5);
        Unit holder = world.createUnit(type, 3, 8, 5);
        Unit tail = world.createUnit(type, 3, 12, 5);
        holder.setOrder(Unit.Order.ATTACK);
        holder.setOfferedTarget(victim);
        world.kill(holder);

        assertEquals(null, holder.offeredTarget(),
                "LetUnitDie destroys COrder_Attack, whose offeredTarget CUnitPtr must"
                        + " release in the same operation");
        world.kill(victim);

        for (int cycle = 0; cycle < 80 && world.unitsSnapshot().contains(victim); cycle++) {
            world.tick();
        }

        assertFalse(world.unitsSnapshot().contains(victim),
                "a stale offeredTarget left on the dead owner retained the second corpse"
                        + " after its own death animation had finished");
        assertEquals(List.of(tail), world.unitsSnapshot(),
                "both corpseless units should be swap-released, leaving only the tail");
    }

    @Test
    @DisplayName("finishing an attack releases its offered-target reference")
    void anOfferedTargetDoesNotSurviveTheAttackOrder() {
        World world = world();
        UnitType type = peasant();
        type.setCorpse(null);
        Unit victim = world.createUnit(type, 3, 4, 5);
        Unit attacker = world.createUnit(type, 4, 8, 5);
        Unit tail = world.createUnit(type, 3, 12, 5);
        attacker.setOrder(Unit.Order.ATTACK);
        attacker.setTarget(victim);
        attacker.setOfferedTarget(victim);

        world.kill(victim);
        world.finishAttackOrder(attacker);

        assertEquals(null, attacker.offeredTarget(),
                "EndActionAttack destroyed COrder_Attack but retained the order-owned"
                        + " offeredTarget CUnitPtr");
        for (int cycle = 0; cycle < 80 && world.unitsSnapshot().contains(victim); cycle++) {
            world.tick();
        }

        assertFalse(world.unitsSnapshot().contains(victim),
                "the dead attack target stayed in the action table after its last real"
                        + " order reference had gone");
        assertEquals(List.of(tail, attacker), world.unitsSnapshot(),
                "the final CUnitPtr release must swap the action table's tail into the hole");
    }

    @Test
    @DisplayName("an unseen wreck retains its fog-memory reference")
    void aDestroyedVisibleUnderFogUnitWaitsUntilItsGroundIsSeen() {
        World world = world();
        UnitType wreck = peasant();
        wreck.setCorpse(null);
        wreck.setVisibleUnderFog(true);
        Unit victim = world.createUnit(wreck, 0, 5, 5);
        world.kill(victim);

        for (int cycle = 0; cycle < 40 && !victim.destroyed(); cycle++) {
            world.tick();
        }

        assertTrue(victim.destroyed(), "the fixture never finished the death animation");
        assertTrue(world.unitsSnapshot().contains(victim),
                "a human player's fog memory owns a reference after the wreck disappears");

        Unit viewer = world.createUnit(peasant(), 0, 6, 5);
        world.tick();
        assertEquals(List.of(viewer), world.unitsSnapshot(),
                "seeing the wreck's ground must release the memory reference and its slot");
    }

    @Test
    @DisplayName("global unit release fills its action-table hole with the final unit")
    void releaseSwapsTheLastActiveUnitIntoTheHole() {
        World world = world();
        UnitType type = peasant();
        type.setCorpse(null);
        Unit first = world.createUnit(type, 3, 2, 5);
        Unit released = world.createUnit(type, 3, 4, 5);
        Unit third = world.createUnit(type, 3, 6, 5);
        Unit last = world.createUnit(type, 3, 8, 5);
        world.kill(released);

        for (int cycle = 0; cycle < 40 && world.unitsSnapshot().contains(released); cycle++) {
            world.tick();
        }

        assertEquals(List.of(first, last, third), world.unitsSnapshot(),
                "CUnitManager::ReleaseUnit does not shift the tail left: it writes"
                        + " units.back() into the released unit's UnitSlot and pops the back."
                        + " UnitActions walks this table, so shifting changes which idle"
                        + " animation or wander order consumes each shared random draw");
    }

    /** A marker whose whole life is written in its idle animation. */
    private static UnitType revealer() {
        UnitType type = new UnitType("unit-dead-vision-1-4");
        type.setTileSize(1, 1);
        type.setHitPoints(1);
        type.setRevealer(true);
        type.setVanishes(true);
        type.setNumDirections(1);
        type.setSightRange(4);
        AnimationSet set = new AnimationSet("dead-vision");
        set.put(AnimationSet.State.STILL, Animation.parse("still",
                List.of("frame 0", "wait 4", "die")));
        type.setAnimationSet(set);
        return type;
    }

    /** An idle unit that creates one new active-table entry on its second turn. */
    private static UnitType spawner(UnitType newborn) {
        UnitType type = new UnitType("unit-spawner");
        type.setTileSize(1, 1);
        type.setHitPoints(1);
        type.setNumDirections(1);
        AnimationSet set = new AnimationSet("spawner");
        set.put(AnimationSet.State.STILL, Animation.parse("still",
                List.of("frame 0", "wait 1",
                        "spawn-unit " + newborn.ident() + " 1 0 0 l.this", "wait 100")));
        type.setAnimationSet(set);
        return type;
    }

    @Test
    @DisplayName("a unit killed by its own animation is still in the world that cycle")
    void aDieInstructionTakesEffectOnTheCycleAfter() {
        World world = world();
        Unit marker = world.createUnit(revealer(), 0, 5, 5);

        int died = -1;
        for (int cycle = 1; cycle <= 40 && died < 0; cycle++) {
            world.tick();
            if (marker.removed()) {
                died = cycle;
            }
        }

        assertTrue(died > 0, "the marker never reached the die at the end of its animation");
        // The `die` instruction throws AnimationDie_Exception and the catch is
        // one line -- AnimationDie_OnCatch(unit) { LetUnitDie(unit); } -- so
        // the unit is taken off the map on that cycle and handed to the unit
        // manager, which is not the same as being gone. Upstream's trace of
        // maps/demo/demo02 still lists the vision marker a dead peasant leaves
        // on cycle 175, the cycle its own animation kills it.
        assertTrue(world.units().contains(marker),
                "the marker was swept out of the world on the same cycle its animation"
                        + " killed it, on cycle " + died + ". The Die order runs and"
                        + " releases it at its own action-table turn on the cycle after");

        world.tick();
        assertTrue(!world.units().contains(marker),
                "and then it has to go, or a map fills up with markers");
    }

    @Test
    @DisplayName("same-cycle births remain behind the active table during release")
    void aDieInstructionLeavesSameCycleBirthAtTheActionTableTail() {
        World world = world();
        UnitType newbornType = peasant();
        UnitType spawnerType = spawner(newbornType);
        world.setUnitTypes(Map.of(newbornType.ident(), newbornType));

        Unit spawner = world.createUnit(spawnerType, 3, 2, 5);
        Unit marker = world.createUnit(revealer(), 3, 4, 5);
        Unit tail = world.createUnit(peasant(), 3, 6, 5);

        // Put the marker on its animation's `die` immediately. It stays in
        // the action table for this cycle, then its Die order releases it at
        // its own position in the next UnitActions pass. The spawner precedes
        // it, so the newborn exists by then, but it remains outside the active
        // table until that pass closes.
        marker.type().animationSet().put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("die")));
        world.tick();
        assertTrue(world.units().contains(marker),
                "the die instruction itself must leave the released slot visible one cycle");

        world.tick();
        Unit newborn = world.unitsSnapshot().stream()
                .filter(unit -> unit.type() == newbornType)
                .findFirst().orElseThrow();
        assertEquals(List.of(spawner, tail, newborn), world.unitsSnapshot(),
                "the active tail must fill the marker's hole before the same-cycle"
                        + " newborn is appended; XOrc 11's fixture-482 peasant owns"
                        + " the next pass's first idle draw from that tail position");
    }
}
