package net.chonkbase.chonkcraft.engine.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.trigger.TriggerSystem;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The tenth human mission and the prisoners it is about.
 *
 * <p>A campaign map is two scripts upstream. {@code <map>.sms} is generated
 * from the PUD and is a list of {@code CreateUnit} lines; {@code <map>_c.sms}
 * is hand-written and ends by {@code Load}ing it. That ordering is a seam, and
 * one mission in the game uses it: {@code level10h_c.sms} redefines
 * {@code CreateUnit} to turn slot four's peasants into
 * {@code unit-attack-peasant} on the way in, loads the map, and puts the
 * definition back.
 *
 * <p>Both of the mission's triggers count that type -- rescue four near the
 * circle of power to win, let the count fall below four to lose -- so without
 * the substitution the count is zero, the defeat condition is true the first
 * time it is evaluated, and the mission is lost before the player has moved.
 */
class AttackPeasantTest {

    private static final String MAP = "campaigns/human/level10h";

    /** The slot the campaign script names, and its triggers count. */
    private static final int PRISONER_SLOT = 4;

    private static GameData load() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No Warcraft II retail assets configured.");
        return new GameData(assets);
    }

    private static long countOf(World world, String ident, int player) {
        return world.units().stream()
                .filter(Unit::isAlive)
                .filter(unit -> unit.player() == player)
                .filter(unit -> unit.type().ident().equals(ident))
                .count();
    }

    @Test
    @DisplayName("Slot four's peasants arrive as Minutemen, as the campaign script asks")
    void theCampaignScriptConvertsThePrisoners() {
        Mission mission = load().loadMission(MAP);
        assertNotNull(mission, "the tenth human mission will not load");
        World world = mission.world();

        assertTrue(countOf(world, "unit-attack-peasant", PRISONER_SLOT) >= 4,
                "the defeat condition counts at least four of these and there are "
                        + countOf(world, "unit-attack-peasant", PRISONER_SLOT));
        assertEquals(0, countOf(world, "unit-peasant", PRISONER_SLOT),
                "the wrapper converts every one of slot four's peasants, not some");
    }

    /**
     * The substitution is confined to the map that asks for it. The wrapper is
     * installed and taken back down around a single {@code Load}, so no other
     * player on this map and no other map in the game sees it.
     */
    @Test
    @DisplayName("Nobody else's peasants are touched")
    void theSubstitutionIsConfinedToSlotFour() {
        GameData data = load();
        World tenth = data.loadMission(MAP).world();
        for (int slot = 0; slot < tenth.players().length; slot++) {
            if (slot == PRISONER_SLOT) {
                continue;
            }
            assertEquals(0, countOf(tenth, "unit-attack-peasant", slot),
                    "slot " + slot + " should have ordinary peasants");
        }

        Mission ninth = data.loadMission("campaigns/human/level09h");
        assertNotNull(ninth);
        for (int slot = 0; slot < ninth.world().players().length; slot++) {
            assertEquals(0, countOf(ninth.world(), "unit-attack-peasant", slot),
                    "no other map installs the wrapper");
        }
    }

    /**
     * The point of the whole thing: the mission has to still be running.
     */
    @Test
    @DisplayName("The mission no longer decides itself")
    void theMissionDoesNotDecideItself() {
        Mission mission = load().loadMission(MAP);
        assertNotNull(mission);
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 30; cycle++) {
            mission.tick();
            if (mission.outcome() != TriggerSystem.Outcome.RUNNING) {
                throw new AssertionError("decided " + mission.outcome() + " after "
                        + cycle + " cycles, by trigger " + mission.triggers().decidedBy()
                        + " of " + mission.triggers().decidedOfHowMany());
            }
        }
        assertEquals(TriggerSystem.Outcome.RUNNING, mission.outcome());
    }
}
