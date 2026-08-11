package net.chonkbase.chonkcraft.engine.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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
 * A computer player goes after a rescuable town it is at war with.
 *
 * <p>Reported from play: on human mission five the orcs never assault the red
 * humans, who are a RESCUE_ACTIVE slot the player does not control.
 *
 * <p>The force targeting asked for {@code isEnemyPlayer} <em>and</em> that the
 * owner be {@link Player#isActive}, which is {@code PERSON || COMPUTER}. That
 * second test quietly excluded every rescuable slot. Upstream's
 * {@code CPlayer::Init} makes rescue-active an explicit enemy of the computer
 * players and {@code AiForceEnemyFinder} applies no such filter.
 *
 * <p>Measured on the shipped campaigns before it was removed: the orc computer
 * on mission five saw 33 enemies and was left with 18, dropping all 15 of the
 * rescuable humans' units; on mission eight four computers each saw 26 and
 * were left with 8.
 *
 * <p>It read as a passive personality rather than a bug because reactive
 * combat was unaffected -- {@code World.autoAttack} gates on
 * {@code isEnemyPlayer} alone, so the AI fought those units whenever they
 * wandered into it and simply never went looking for them.
 */
class AiRescueSlotEnemyTest {

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

    private static UnitType grunt() {
        UnitType type = new UnitType("unit-grunt");
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setDemand(1);
        type.setSightRange(4);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(6);
        type.setMaxAttackRange(1);
        type.setAnimationSet(fighter());
        return type;
    }

    private static UnitType hall() {
        UnitType type = new UnitType("unit-town-hall");
        type.setTileSize(4, 4);
        type.setHitPoints(1200);
        type.setBuilding(true);
        type.setSupply(20);
        type.setSightRange(4);
        return type;
    }

    /**
     * Slot 0 rescuable, slot 1 the computer. Upstream's own arrangement on the
     * missions where this was reported.
     */
    private static World world(PudMap.PlayerType rescueKind) {
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            PudMap.PlayerType kind = switch (i) {
                case 0 -> rescueKind;
                case 1 -> PudMap.PlayerType.COMPUTER;
                default -> PudMap.PlayerType.NOBODY;
            };
            players[i] = new Player(i, kind, PudMap.Race.HUMAN);
        }
        World world = new World(grass(60), players);
        world.establishDiplomacy();
        return world;
    }

    @Test
    @DisplayName("A computer's force is sent at a rescuable player it is at war with")
    void aForceGoesAfterARescuableTown() {
        World world = world(PudMap.PlayerType.RESCUE_ACTIVE);

        // The fixture only means something if the two really are at war. That
        // is CPlayer::Init's doing, not this test's.
        assertTrue(world.isEnemyPlayer(1, 0),
                "a computer and a rescue-active slot must be enemies, or this proves nothing");

        Unit town = world.createUnit(hall(), 0, 45, 45);
        assertNotNull(town);

        AiPlayer ai = world.enableAi(1);
        ai.setUsePlan(false);
        UnitType soldier = grunt();
        AiForce force = ai.force(1);
        force.setWanted(java.util.Map.of(soldier, 2));
        assertNotNull(world.createUnit(soldier, 1, 6, 6));
        assertNotNull(world.createUnit(soldier, 1, 7, 6));

        // Let the force fill, then give the script's word: the manager moves
        // only Defending and Attacking forces (AiForceManager::Update,
        // The game ), and the attack rides the handed-off army.
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 2; cycle++) {
            world.tick();
        }
        ai.handOffForAttack(1);
        boolean setOff = false;
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 3 && !setOff; cycle++) {
            world.tick();
            setOff = ai.forces().stream().anyMatch(f -> f.state() == AiForce.State.ATTACKING);
        }
        assertTrue(setOff, "the force never set off");

        // Sent at the rescuable town, rather than specifically swinging: the
        // fixture's units have sight, but a force aimed at something it cannot
        // yet see marches at the place, which is upstream's AiForce::Attack.
        boolean engaged = false;
        int startX = -1;
        for (Unit member : world.units()) {
            if (member.player() == 1) {
                startX = member.tileX();
                break;
            }
        }
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 60 && !engaged; cycle++) {
            world.tick();
            if (town.hitPoints() < hall().hitPoints()) {
                engaged = true;
                break;
            }
            for (Unit member : world.units()) {
                if (member.player() != 1) {
                    continue;
                }
                if ((member.target() != null && member.target().player() == 0)
                        || member.tileX() > startX + 6) {
                    engaged = true;
                    break;
                }
            }
        }

        assertTrue(engaged,
                "no orc ever aimed at the rescuable town it is at war with. The force"
                        + " targeting demanded the owner be PERSON or COMPUTER on top of"
                        + " isEnemyPlayer, which excludes every rescuable slot -- so the AI"
                        + " fought them when they walked into it and never went looking.");
    }

    /**
     * The control. A neutral slot is not an enemy, and removing the isActive filter
     * must not have made one -- {@code isEnemyPlayer} is what decides this, and
     * it already excludes NEUTRAL.
     */
    @Test
    @DisplayName("A neutral player is still not a target")
    void neutralsAreLeftAlone() {
        World world = world(PudMap.PlayerType.NEUTRAL);
        assertFalse(world.isEnemyPlayer(1, 0),
                "a neutral slot must not be an enemy, or the fix has widened too far");

        Unit neutral = world.createUnit(hall(), 0, 45, 45);
        assertNotNull(neutral);
        for (int i = 0; i < 4; i++) {
            world.createUnit(grunt(), 1, 5 + i, 5);
        }

        AiPlayer ai = world.enableAi(1);
        ai.setUsePlan(false);
        AiForce force = ai.force(1);
        force.setWanted(java.util.Map.of(grunt(), 4));
        force.setState(AiForce.State.ATTACKING);

        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 60; cycle++) {
            world.tick();
        }
        for (Unit member : world.units()) {
            if (member.player() == 1 && member.target() != null) {
                assertTrue(member.target().player() != 0,
                        "an orc aimed at a neutral building");
            }
        }
        assertTrue(neutral.hitPoints() == hall().hitPoints(),
                "a neutral building was attacked");
    }
}
