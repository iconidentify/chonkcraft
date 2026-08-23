package net.chonkbase.chonkcraft.engine.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.World;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The computer players build things, spend money and fight.
 *
 * <p>The opponent is driven by the authenticated retail {@code ai.bin}
 * profile selected by the map. Merely decoding its bytecode says nothing
 * about whether the managers beneath it can actually play, so this referee
 * measures their visible work over complete mission simulations.
 *
 * <p>Measured over the whole campaign by {@link AiProbe} -- fifty-two
 * missions, five simulated minutes each -- sixty-six of the seventy-nine
 * personalities build and spend, thirty-two attack, and eight do nothing
 * whatever. Those figures were 62, 33 and 12 before the resource manager
 * learned to walk past a request it cannot start, to ask for a farm when it
 * runs out of food, and to send a force at somewhere rather than at a unit it
 * cannot see. Counted per slot rather than per personality the same run went
 * from 72 of 114 building to 78, and from 47,141 cycles of attacking to
 * 103,101.
 *
 * <p>The eight that remain are meant to sit still or cannot move: two are
 * garrisons whose whole script is a sleep loop, and the rest own no land
 * worker at all and are over their food supply, so they can neither build a
 * farm nor train the peasant that would build one. That deadlock is upstream's
 * too.
 *
 * <p>This pins eight of the fifty-two. The full sweep is ten seconds and cost
 * is not the reason for the subset -- a failure naming one of eight missions
 * is a great deal easier to act on than one naming a total over fifty-two, and
 * these eight between them cover a working economy in both races, the biggest
 * builder in the game, the one slot whose attacks reliably land, four
 * personalities on a single map, force assembly, and two passive controls.
 *
 * <p>The numbers below are floors well under what is measured, not the
 * measurements themselves. The point is to catch a personality that stops
 * working, not to freeze the balance of a mission.
 */
class AiCompetenceTest {

    /** Five simulated minutes: long enough for the late retail force launches. */
    private static final int CYCLES = World.CYCLES_PER_SECOND * 300;

    /**
     * The eight missions, and why each is here.
     *
     * <p>{@code level12h} a clean human economy; {@code level13o} the base
     * campaign's biggest orc builder; {@code levelx06h} both extremes on one
     * map, the game's heaviest builder and a slot that is stuck; {@code
     * levelx10h} the one personality whose attack orders reliably survive;
     * {@code levelx12o} four personalities for one map load; {@code level08h}
     * force assembly, three slots of one personality and a passive baseline;
     * {@code level04o} the only naval builder in the base campaign that works;
     * {@code level01h} a passive negative control.
     */
    private static final List<String> MISSIONS = List.of(
            "campaigns/human/level12h",
            "campaigns/orc/level13o",
            "campaigns/human-exp/levelx06h",
            "campaigns/human-exp/levelx10h",
            "campaigns/orc-exp/levelx12o",
            "campaigns/human/level08h",
            "campaigns/orc/level04o",
            "campaigns/human/level01h");

    private static List<AiProbe.Slot> play() {
        GameData data = SimulationProfile.load();
        Assumptions.assumeTrue(data != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        List<AiProbe.Slot> slots = new ArrayList<>();
        for (String mission : MISSIONS) {
            slots.addAll(AiProbe.measure(data, mission, CYCLES));
        }
        Assumptions.assumeTrue(!slots.isEmpty(), "campaign maps unavailable");
        slots.forEach(slot -> System.out.printf("%-34s %d %-16s created=%-3d gold=%-6d"
                        + " harvest=%-6d credited=%-6d bneForce=%-6d attackCycles=%d%n",
                slot.mission, slot.index, slot.script, slot.created, slot.goldSpent(),
                slot.harvestCycles, slot.credited[0] + slot.credited[1] + slot.credited[2],
                slot.battleNetForceCycles, slot.attackCycles));
        return slots;
    }

    @Test
    @DisplayName("the computer players build, spend and fight rather than running clean and idle")
    void thePersonalitiesActuallyPlay() {
        List<AiProbe.Slot> slots = play();
        assertEquals(20, slots.size(),
                "these eight missions should field twenty thinking slots between them;"
                        + " a change here means the per-slot selection changed");

        // The aggregate. Seventeen of the twenty build and spend today, up from
        // fifteen; twelve is a floor that a couple of missions drifting cannot
        // cross but a broken resource or build manager immediately would.
        long playing = slots.stream()
                .filter(slot -> slot.built() && slot.goldSpent() > 0)
                .count();
        assertTrue(playing >= 12, "only " + playing + " of " + slots.size()
                + " computer players built anything and paid for it. The AI has gone quiet:"
                + " look at AiPlayer.resourceManager, which abandons the whole queue when its"
                + " first entry cannot be built, and AiPlayer.checkUnits, which is the only"
                + " thing that ever puts a worker on a resource.");

        assertTrue(slots.stream().allMatch(slot -> slot.script.startsWith("retail-ai.bin:")),
                "a sampled opponent was not driven by a retail ai.bin profile");

        // Somebody has to actually swing. Two slots own live attack orders in
        // this native-cadence sample; the other scripted forces either keep
        // mustering or correctly refuse a flood with no reachable enemy.
        // BattleNetAiPatrolLivenessTest separately follows all four members
        // of a binary-observed Orc 11 assault through movement and combat.
        long fighting = slots.stream().filter(AiProbe.Slot::attacked).count();
        assertTrue(fighting >= 2, "only " + fighting + " of " + slots.size()
                + " computer players ever put a unit into an attack order");

        // Keep the economy promise on one autonomous slot rather than letting
        // one computer harvest while another spends. Battle liveness has its
        // own binary-observed referee: BattleNetAiPatrolLivenessTest follows
        // the native Orc 11 force through movement and combat. The old
        // same-slot force clause depended on a Java-only X10 launch at cycle
        // 6201; a sealed 6,500-cycle BNE capture proves that launch does not
        // happen in retail and therefore cannot be an authenticity gate.
        List<AiProbe.Slot> completeEconomies = slots.stream().filter(slot ->
                slot.harvestCycles > 0
                && slot.credited[0] + slot.credited[1] + slot.credited[2] > 0
                && slot.built() && slot.spentAnything()).toList();
        assertFalse(completeEconomies.isEmpty(),
                "no one computer slot gathered, banked, built, and spent; "
                        + "the aggregate can pass by distributing a broken economy across players");

        // Human 1's inert control must stay inert. This catches accidentally
        // turning the old generic built-in plan back on for every slot.
        AiProbe.Slot passive = slots.stream()
                .filter(slot -> slot.mission.equals("campaigns/human/level01h")
                        && slot.index == 0)
                .findFirst().orElseThrow();
        assertEquals(0, passive.created, passive + " built something");
        assertEquals(0, passive.goldSpent(), passive + " spent money");

    }

}
