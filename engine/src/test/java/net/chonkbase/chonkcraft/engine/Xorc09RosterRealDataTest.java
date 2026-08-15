package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * XOrc 9's opening roster is the sealed native types, not the historical
 * CreateUnit race rewrite.
 *
 * <p>The authenticated cycle-one capture fields fifty-two skeletons on the
 * human-race computer slot and six paladins on the player slot. Java used
 * to convert those skeletons into militia, which is why the comparator
 * reported fifty-two unmatched identities before any unit had moved.
 */
class Xorc09RosterRealDataTest {

    private static GameData load() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No Warcraft II retail assets configured.");
        return new GameData(assets);
    }

    @Test
    @DisplayName("xorc 9 fields the pud skeletons and the campaign paladins")
    void xorc9FieldsThePudSkeletonsAndTheCampaignPaladins() {
        Mission mission = load().loadMission("campaigns/orc-exp/levelx09o");
        Assumptions.assumeTrue(mission != null, "the mission did not load");
        World world = mission.world();

        int skeletons = 0;
        int militia = 0;
        int paladins = 0;
        int knightsOnPlayer = 0;
        for (Unit unit : world.unitsSnapshot()) {
            if (!unit.isAlive() || unit.type() == null) {
                continue;
            }
            String ident = unit.type().ident();
            if ("unit-skeleton".equals(ident)) {
                skeletons++;
            } else if ("unit-attack-peasant".equals(ident)) {
                militia++;
            } else if ("unit-paladin".equals(ident) && unit.player() == 1) {
                paladins++;
            } else if ("unit-knight".equals(ident) && unit.player() == 1) {
                knightsOnPlayer++;
            }
        }

        assertEquals(52, skeletons,
                "XOrc 9 used to rewrite the fifty-two stored skeletons into"
                        + " militia, so cycle one could not pair them with the"
                        + " sealed native slots");
        assertEquals(0, militia,
                "XOrc 9 must not field militia the PUD never stored");
        assertEquals(6, paladins,
                "player-one's campaign knights must still become paladins");
        assertEquals(0, knightsOnPlayer,
                "a leftover player knight means the campaign roster transform"
                        + " was lost with the race-rewrite removal");
        int live = 0;
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.isAlive()) {
                live++;
            }
        }
        assertTrue(live >= 141,
                "XOrc 9's sealed cycle-one roster is 141 live units");
    }
}
