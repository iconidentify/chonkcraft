package net.chonkbase.chonkcraft.engine.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.trigger.TriggerSystem;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The whole game, played through, checked for the faults that reached players.
 *
 * <p>Every other test in this suite asks whether one piece of behaviour is
 * right. This one asks whether the game is still coherent after running, and
 * it exists because that is the question none of the others were asking. The
 * record is in focused tests and it is unambiguous: the transport that could not
 * unload, the woodcutter chopping at bare ground, the terrain that never
 * redrew, the archer that could not walk onto the circle of power, the crash
 * on a mouse move -- every one of them was found by a person playing, with a
 * green suite behind them, and several had been shipping for months.
 *
 * <p>The repository's four mechanical checks cannot see this class of fault by
 * construction. They look for an accessor nothing calls, a layout element
 * nothing draws, a script name nothing binds and a sound nothing plays: all
 * four are checks for <em>absence</em>. What actually broke each time was
 * present, called, and quietly wrong.
 *
 * <p>So this plays all fifty-two campaign missions for a simulated minute
 * each, orders every woodcutter on every map to chop, and asserts four
 * properties that must hold of any Warcraft II at any moment. Each is derived
 * from a fault that shipped; the Javadoc on each method in
 * {@link PlayInvariants} names which. It takes about five seconds.
 *
 * <p>Two of the four are exact and two carry a stated allowance, because two
 * of the underlying faults are open and covered by focused tests rather than fixed.
 * An allowance with a number in it catches a regression -- the woodcutter
 * figure was effectively 100% before the woodcutter lane and is 1.05% now --
 * where a deleted assertion catches nothing.
 */
class PlayInvariantsTest {

    /**
     * Run once and shared, because loading fifty-two missions four times over
     * would be four times the cost for the same answer.
     */
    private static List<PlayInvariants.Run> runs;

    private static synchronized List<PlayInvariants.Run> play() {
        GameData data = SimulationProfile.load();
        Assumptions.assumeTrue(data != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        if (runs == null) {
            runs = PlayInvariants.measureAll(data, PlayInvariants.CYCLES);
        }
        Assumptions.assumeTrue(!runs.isEmpty(), "campaign maps unavailable");
        return runs;
    }

    private static List<PlayInvariants.Breach> of(String invariant) {
        List<PlayInvariants.Breach> found = new ArrayList<>();
        for (PlayInvariants.Run run : play()) {
            for (PlayInvariants.Breach breach : run.breaches) {
                if (breach.invariant().equals(invariant)) {
                    found.add(breach);
                }
            }
        }
        return found;
    }

    /** The first few breaches, for a failure message somebody can act on. */
    private static String sample(List<PlayInvariants.Breach> breaches) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < Math.min(5, breaches.size()); i++) {
            text.append("\n  ").append(breaches.get(i));
        }
        if (breaches.size() > 5) {
            text.append("\n  ... and ").append(breaches.size() - 5).append(" more");
        }
        return text.toString();
    }

    @Test
    @DisplayName("every mission loads and runs for a minute")
    void everyMissionLoadsAndRuns() {
        List<PlayInvariants.Run> all = play();
        // The guard that stops the other three passing vacuously. A sweep that
        // loaded nothing would satisfy every assertion below, and this is
        // exactly how a campaign went fourteen missions without an ending
        // while the suite stayed green.
        assertEquals(52, all.size(), "the campaign scripts should name 52 missions");
        List<String> failed = new ArrayList<>();
        for (PlayInvariants.Run run : all) {
            if (run.error != null) {
                failed.add(run.mission + ": " + run.error);
            }
        }
        assertTrue(failed.isEmpty(), "missions threw while loading or running: " + failed);
    }

    @Test
    @DisplayName("nobody is standing on ground they could not have walked onto")
    void nobodyStandsWhereTheyCouldNotWalk() {
        int samples = 0;
        for (PlayInvariants.Run run : play()) {
            samples += run.groundSamples;
        }
        assertTrue(samples > 500_000,
                "the sweep must actually look at units; it looked at " + samples);

        List<PlayInvariants.Breach> breaches = of("standing ground");
        assertTrue(breaches.isEmpty(),
                breaches.size() + " units are standing on ground their own movement mask"
                        + " forbids. Felling a tree once wrote a flag word of nought, and a save"
                        + " once restored wood over the woodcutter standing in it." + sample(breaches));
    }

    @Test
    @DisplayName("an oil patch, a circle of power and everything else answers a click")
    void everythingOnTheMapCanBeClickedOn() {
        int samples = 0;
        for (PlayInvariants.Run run : play()) {
            samples += run.clickSamples;
        }
        assertTrue(samples > 1_000_000,
                "the sweep must actually look at units; it looked at " + samples);

        List<PlayInvariants.Breach> breaches = of("clickable");
        // Without Unit.isPointable this is 22,588 breaches: all 105 oil
        // patches across 29 maps and all 10 circles of power, every sample,
        // because both are declared HitPoints = 0 and World.unitAt asked
        // isAlive() first.
        assertTrue(breaches.isEmpty(),
                breaches.size() + " things on the map cannot be clicked. In Warcraft II you"
                        + " click an oil patch to read the oil left in it, and the circle of power"
                        + " is the objective of the Dark Portal missions." + sample(breaches));
    }

    /**
     * The one open item left from the woodcutter lane, held to its measured
     * size.
     *
     * <p>focused tests: "The re-find after a tree falls waits out {@code
     * WaitAtResource} first. Upstream re-checks the target square on every
     * cycle of the walk; this implementation re-checks it at the top of {@code
     * walkToWood}, which the wait counter skips." So a worker spends a short
     * window aimed at a square whose tree has just come down, and recovers by
     * itself.
     *
     * <p>The ceiling is what makes this worth having rather than a restatement
     * of the bug. Measured over 476 driven woodcutters on all fifty-two maps:
     * 535 breaches in 50,913 samples, 1.05%. Before the woodcutter lane the
     * same measurement would have been effectively total -- seventy-seven
     * return trips out of seventy-seven came out of the hall aimed at ground
     * that had already been cleared. Three per cent is comfortably above the
     * window and nowhere near a regression.
     */
    @Test
    @DisplayName("a woodcutter is aimed at an actual tree, apart from the moment one falls")
    void aWoodcutterIsAimedAtATree() {
        int ordered = 0;
        int samples = 0;
        for (PlayInvariants.Run run : play()) {
            ordered += run.woodcuttersOrdered;
            samples += run.harvestSamples;
        }
        // Both guards matter. The first draft of this read the wrong pair of
        // fields off the unit -- orderTarget rather than resourceTile -- and
        // sampled nought workers across all fifty-two missions while passing.
        assertTrue(ordered > 300,
                "the sweep must actually set woodcutters going; it started " + ordered);
        assertTrue(samples > 20_000,
                "the sweep must actually watch them work; it sampled " + samples);

        List<PlayInvariants.Breach> breaches = of("harvest target");
        double rate = 100.0 * breaches.size() / samples;
        assertTrue(rate < 3.0, String.format(
                "%.2f%% of the time a woodcutter is walking to a square with no tree on it"
                        + " (%d of %d samples), against 1.05%% measured and a 3%% ceiling."
                        + " Before the woodcutter lane this was effectively total.%s",
                rate, breaches.size(), samples, sample(breaches)));
    }

    @Test
    @DisplayName("every worker actively chopping faces its tree")
    void everyActiveWoodcutterFacesTheResourcePoint() {
        List<PlayInvariants.Breach> breaches = of("harvest facing");
        assertTrue(breaches.isEmpty(),
                breaches.size() + " active woodcutters are swinging away from their tree."
                        + sample(breaches));
    }

    /**
     * A mission left alone does not decide itself.
     *
     * <p>Pinned as a set rather than a count so that it says which.
     *
     * <p>{@code orc-exp/levelx01o} used to be in the set, and what put it
     * there was the enemy table being derived from the alliance one: "not
     * allied" meant "enemy", the four guards around the caged Beast Cry
     * counted the prisoner an enemy, and they cut it down from 240 hit
     * points to dead in 54 seconds -- the hero both defeat triggers watch,
     * gone before a player could act. {@code CPlayer::Init} keeps
     * {@code Enemy} apart from {@code Allied} precisely so a computer and a
     * rescue-passive slot can be neither, and with that ported the guards
     * stand over the prisoner and the mission runs.
     *
     * <p>{@code human-exp/levelx11h} entered the set with the same fix, and
     * it belongs there: its player starts with two escorts and three heroes
     * who are rescued in the opening seconds, and the orc computer's own
     * mission script sleeps 1500 cycles and sends its first war band --
     * measured, four axethrowers and grunts of player 2 reach the idle band
     * at 45 seconds and the knight-rider is dead at 54. That force used to
     * spend itself on the nineteen rescue-passive prisoners it wrongly
     * counted as enemies. A player who does not defend that opening loses
     * it in Warcraft II too; "left alone" is not something this mission
     * permits.
     *
     * <p>That mission is a close-run thing, and it has now left this set three
     * times and come back twice, each time on a change to how faithfully a
     * unit spends its cycles. It went out when a blocked chase stopped giving
     * up and came back when chasers stopped walking twice in the cycle a step
     * lands; it went out again when every stored route started costing the
     * ten-cycle pause upstream charges at its end, and came back when a dead
     * unit stopped being replaced by a new corpse and started becoming one --
     * which is a draw off the shared random stream per death, and this mission
     * has a great many deaths in its opening minute. It left a third time when
     * the attack-move march learned the chase's own cadence -- the walk owns
     * the cycle, a spent route owes the ten-cycle wait, an arrival turns
     * before it swings -- which slows the war band that used to kill the
     * knight-rider inside the window by exactly the pauses upstream's takes.
     * And it came straight back when the march also learned the chase's
     * mid-walk re-plan -- a marcher whose quarry moves re-aims from where it
     * stands instead of walking its stale course out -- which is faster at
     * running prey by about what the pauses had cost.
     *
     * <p>Worth saying plainly, because the temptation each time is to move the
     * expectation and get on: the fight this watches is decided within a few
     * seconds either way, so it reads every latency this implementation adds or removes.
     * That makes it a poor thing to argue with and a good thing to measure
     * against. Instrumented on its own, without the chop order this run gives
     * every gatherer, the knight-rider has died as early as cycle 1,500 and as
     * late as 2,700 of a 3,600-cycle window across those four changes.
     *
     * <p>Twelve of the fifty-two were ending in the first seconds once, when
     * the alliance table was never filled in at all. That is the regression
     * this guards.
     *
     * <p>How long the missions are left alone is deliberate and documented on
     * {@link PlayInvariants#CYCLES}: this assertion used to sit within two
     * seconds of its own window, and read an engine change that moved that
     * fight slightly as a victory-condition regression.
     */
    @Test
    @DisplayName("a mission left alone does not win or lose on its own")
    void noMissionDecidesItselfWithNobodyPlaying() {
        Set<String> decided = new TreeSet<>();
        for (PlayInvariants.Run run : play()) {
            if (run.outcome != TriggerSystem.Outcome.RUNNING) {
                decided.add(run.mission + " -> " + run.outcome);
            }
        }
        // levelx11h's self-defeat left for the fifth time -- and this time
        // by the mission playing out: a force sent on an attack-march
        // fights its way in rather than strolling unarmed, so the defence
        // holds where the fractional walk let it fall.
        assertEquals(Set.of(), decided,
                "the set of missions that decide themselves with nobody playing has changed."
                        + " If one was fixed, take it out of this assertion and out of focused tests;"
                        + " if one appeared, it is a regression in the victory conditions");
    }
}
