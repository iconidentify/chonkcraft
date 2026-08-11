package net.chonkbase.chonkcraft.engine.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.trigger.TriggerSystem;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A mission must not be over before it has begun.
 *
 * <p>Every campaign mission was ending in the first few seconds, and the cause
 * was never in the missions. It was that an unimplemented script function
 * answered with a table, and a table in retired scripting language is true -- so a victory condition
 * calling something this implementation had never bound was satisfied on its first
 * evaluation. The engine's answer to "not implemented" and its answer to "yes"
 * were the same value.
 *
 * <p>This runs the real campaigns for their first half minute and asks only
 * that they still be running. It is a coarse test and deliberately so: the
 * fault it guards against is not subtle, and anything subtler would have to
 * encode what each mission is supposed to do.
 */
class MissionLengthTest {

    /** How long a mission must survive before anything decides it. */
    private static final int SECONDS = 30;

    private static GameData load() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No Warcraft II retail assets configured.");
        return new GameData(assets);
    }

    /**
     * Campaigns whose opening mission is known to end early, and why.
     *
     * <p>Empty, and it was not always. "A Time for Heroes" used to be here:
     * its three rescuable heroes were killed by the orcs across the map within
     * four seconds, because the implementation left every player hostile to every other
     * and a prisoner is not somebody you shoot. Establishing diplomacy from
     * the map's player types cured it, and this list told me so -- it fails if
     * a campaign named here stops ending early, which is the only way an
     * exception like this ever gets taken back out.
     */
    private static final List<String> KNOWN_EARLY = List.of();

    /**
     * The mission a campaign begins with, which is the one a player meets.
     *
     * <p>Only the first of each rather than all fifty-two: the whole set takes
     * minutes to simulate and the fault this guards is not mission-specific.
     */
    @Test
    @DisplayName("The first mission of every campaign is still running after half a minute")
    void openingMissionsSurvive() {
        GameData data = load();
        List<String> ended = new ArrayList<>();
        List<String> unexpectedlyFine = new ArrayList<>();
        for (Campaign campaign : data.campaigns()) {
            String path = campaign.missions().get(0).mapArchivePath();
            Mission mission = data.loadMission(path);
            if (mission == null) {
                ended.add(campaign.name() + " mission 1 will not load at all");
                continue;
            }
            TriggerSystem.Outcome outcome = runFor(mission, SECONDS);
            boolean known = KNOWN_EARLY.contains(campaign.name());
            if (outcome != TriggerSystem.Outcome.RUNNING && !known) {
                ended.add(campaign.name() + " mission 1 ended in " + outcome
                        + " within " + SECONDS + " seconds, decided by trigger "
                        + mission.triggers().decidedBy() + " of "
                        + mission.triggers().decidedOfHowMany());
            }
            if (outcome == TriggerSystem.Outcome.RUNNING && known) {
                unexpectedlyFine.add(campaign.name());
            }
        }
        assertTrue(ended.isEmpty(), String.join("\n", ended));
        assertTrue(unexpectedlyFine.isEmpty(),
                "these are listed as known to end early and no longer do; take them off"
                        + " the list rather than leaving it lying: " + unexpectedlyFine);
    }

    private static TriggerSystem.Outcome runFor(Mission mission, int seconds) {
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * seconds; cycle++) {
            mission.tick();
            if (mission.outcome() != TriggerSystem.Outcome.RUNNING) {
                return mission.outcome();
            }
        }
        return TriggerSystem.Outcome.RUNNING;
    }

    /**
     * The mission is played by the slot the map calls a person.
     *
     * <p>Assuming slot zero works on the two original campaigns and on neither
     * expansion one, which is what made this look like a problem with the
     * expansion rather than with the assumption.
     */
    @Test
    @DisplayName("The player is the slot the map names, not slot zero")
    void theMapSaysWhoThePlayerIs() {
        GameData data = load();
        boolean anyElsewhere = false;
        for (Campaign campaign : data.campaigns()) {
            for (int number = 1; number <= campaign.missions().size(); number++) {
                Mission mission = data.loadMission(
                        campaign.missions().get(number - 1).mapArchivePath());
                if (mission == null) {
                    continue;
                }
                int person = GameData.personIn(mission.source());
                assertEquals(net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.PERSON,
                        mission.source().players()[person],
                        campaign.name() + " mission " + number
                                + ": slot " + person + " is not a person");
                if (person != 0) {
                    anyElsewhere = true;
                }
            }
        }
        assertTrue(anyElsewhere,
                "no mission puts the player anywhere but slot zero, so this proves nothing");
    }

}
