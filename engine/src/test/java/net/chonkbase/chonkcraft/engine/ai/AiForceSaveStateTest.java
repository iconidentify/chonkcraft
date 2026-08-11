package net.chonkbase.chonkcraft.engine.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A computer player reloaded mid-assault is still mid-assault.
 *
 * <p>A force is the only thing in this AI that carries an attack:
 * {@code AiForceManager} fills one, sends it, and re-aims it as its victims
 * die. Everything else a saved game keeps about a computer player -- its
 * standing requests, its researches, its sleep cycle -- describes what it
 * intends, not what it is in the middle of doing. So a save that drops the
 * forces reloads an AI that has forgotten its army was half way across the map
 * and starts gathering a new one from the units already out there.
 *
 * <p>{@code AiForce} had no way to be read out or put back, which is why the
 * save could not carry it. The control below is the point of the test: it
 * plays the same second with the forces dropped and shows the difference is
 * real rather than something the units would have done anyway.
 */
class AiForceSaveStateTest {

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static AnimationSet fighter() {
        AnimationSet set = new AnimationSet("f");
        set.put(AnimationSet.State.STILL, Animation.parse("s", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("m",
                List.of("frame 0", "move 16", "wait 1", "frame 5", "move 16", "wait 1")));
        set.put(AnimationSet.State.ATTACK, Animation.parse("a",
                List.of("frame 25", "attack", "wait 2")));
        set.put(AnimationSet.State.DEATH, Animation.parse("d", List.of("frame 50", "wait 1")));
        return set;
    }

    private static UnitType footman() {
        UnitType type = new UnitType("unit-footman");
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setDemand(1);
        // Far enough to see the hall it is sent at. A force whose target is
        // inside unexplored ground is marched at rather than attacked, and
        // this test is about the state the force is in, not about the fog.
        type.setSightRange(16);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(6);
        type.setMaxAttackRange(1);
        type.setAnimationSet(fighter());
        return type;
    }

    private static UnitType townHall() {
        UnitType type = new UnitType("unit-town-hall");
        type.setTileSize(4, 4);
        type.setHitPoints(1200);
        type.setBuilding(true);
        type.setSightRange(4);
        type.stores().add(UnitType.Resource.GOLD);
        return type;
    }

    private static Player[] computerVersusPerson() {
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            PudMap.PlayerType kind = switch (i) {
                case 0 -> PudMap.PlayerType.COMPUTER;
                case 1 -> PudMap.PlayerType.PERSON;
                default -> PudMap.PlayerType.NOBODY;
            };
            players[i] = new Player(i, kind, PudMap.Race.HUMAN);
        }
        return players;
    }

    /** A computer player whose force has been sent at the enemy's hall. */
    private static World marching(UnitType soldier) {
        World world = new World(grass(40), computerVersusPerson());
        world.establishDiplomacy();
        world.createUnit(townHall(), 1, 16, 16);
        for (int i = 0; i < 3; i++) {
            world.createUnit(soldier, 0, 4 + i, 4);
        }
        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        ai.force(1).setWanted(Map.of(soldier, 3));
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 2; cycle++) {
            world.tick();
        }
        // The script's word is what launches a filled force; the manager
        // moves only Defending and Attacking ones (AiForceManager::Update,
        // The game ). The attack rides the handed-off army.
        ai.handOffForAttack(1);
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 3; cycle++) {
            world.tick();
        }
        return world;
    }

    private static Unit byId(World world, int id) {
        for (Unit unit : world.units()) {
            if (unit.id() == id) {
                return unit;
            }
        }
        return null;
    }

    /** How many of a player's units are walking or fighting rather than standing. */
    private static long busy(World world, int player) {
        return world.units().stream()
                .filter(unit -> unit.player() == player && unit.isAlive()
                        && unit.order() != Unit.Order.STILL)
                .count();
    }

    @Test
    @DisplayName("a force written out by id and read back is still mid-assault")
    void aForceSurvivesBeingWrittenOutAndReadBack() {
        UnitType soldier = footman();
        World world = marching(soldier);
        AiPlayer ai = world.ais().get(0);
        AiForce before = ai.forces().stream()
                .filter(f -> f.state() == AiForce.State.ATTACKING)
                .findFirst().orElse(null);

        assertNotNull(before,
                "the fixture must have the force under way before the round trip, or it proves"
                        + " nothing");
        int slot = before.index();
        assertEquals(3, before.members().size(), "and holding all three soldiers");

        // What a writer takes: identifiers, not objects, because a force names
        // units the save has yet to write.
        List<Integer> savedMembers = before.memberIds();
        var savedWanted = before.wantedByIdent();
        AiForce.State savedState = before.state();
        boolean savedDefending = before.defending();

        assertEquals(Map.of("unit-footman", 3), savedWanted,
                "the shopping list has to come out keyed by identifier for a save to name it");

        // The reload: the AI comes back with its requests and its researches
        // and no forces at all.
        ai.forces().clear();

        Map<UnitType, Integer> wantedTypes = new java.util.LinkedHashMap<>();
        savedWanted.forEach((ident, count) -> wantedTypes.put(soldier, count));
        List<Unit> restoredMembers = new ArrayList<>();
        savedMembers.forEach(id -> restoredMembers.add(byId(world, id)));
        ai.force(slot).restore(wantedTypes, restoredMembers, savedState, savedDefending);

        // Every member put back to standing before the clock runs, so what is
        // measured below is an order the restored force gave and not one left
        // over from before the save. Watched across the window too: these
        // soldiers have no reaction range, so an order does not outlive the
        // cycle after it, and reading the end of the window only worked while
        // the AI happened to think on its last cycle.
        for (int id : savedMembers) {
            byId(world, id).setOrder(Unit.Order.STILL);
        }
        long everBusy = 0;
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 3; cycle++) {
            world.tick();
            everBusy = Math.max(everBusy, busy(world, 0));
        }

        AiForce after = ai.force(slot);
        assertEquals(AiForce.State.ATTACKING, after.state(),
                "the reloaded force went back to gathering: it is carrying an attack and the"
                        + " state is what says so");
        assertEquals(savedMembers, after.memberIds(),
                "and it should hold the same three units, in the same order -- the first is the"
                        + " one that picks the target and the others follow it");
        assertTrue(everBusy > 0, "and they should still be on their way");
    }

    /**
     * The control.
     *
     * <p>Without it the test above proves only that three soldiers already
     * walking keep walking for another three seconds.
     */
    @Test
    @DisplayName("without its forces the reloaded AI has forgotten the assault")
    void withoutTheForcesTheAssaultIsForgotten() {
        UnitType soldier = footman();
        World world = marching(soldier);
        AiPlayer ai = world.ais().get(0);
        assertTrue(ai.forces().stream().anyMatch(f -> f.state() == AiForce.State.ATTACKING));

        ai.forces().clear();
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 3; cycle++) {
            world.tick();
        }

        assertTrue(ai.forces().stream().noneMatch(f -> f.state() == AiForce.State.ATTACKING),
                "a force that was never restored cannot be attacking, and if it is then the"
                        + " round-trip test above is measuring nothing");
    }
}
