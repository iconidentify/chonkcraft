package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Retail BNE seats one local person and disables further person slots.
 *
 * <p>campaigns/human-exp/levelx12h declares two person slots. Filling the
 * second as a computer (LegacyEngine's solo rule) made it the enemy of every
 * computer on the map; its quick-blade opened the mission by walking at a
 * cannon tower nobody else was at war with. BNE leaves the extra seat empty.
 */
class SecondPersonSlotTest {

    private static PudMap pud() {
        PudMap.PlayerType[] slots = new PudMap.PlayerType[PudMap.PLAYER_MAX];
        java.util.Arrays.fill(slots, PudMap.PlayerType.NOBODY);
        slots[0] = PudMap.PlayerType.PERSON;
        slots[1] = PudMap.PlayerType.COMPUTER;
        slots[2] = PudMap.PlayerType.PERSON;
        slots[15] = PudMap.PlayerType.NEUTRAL;
        PudMap.Race[] races = new PudMap.Race[PudMap.PLAYER_MAX];
        java.util.Arrays.fill(races, PudMap.Race.NEUTRAL);
        races[0] = PudMap.Race.HUMAN;
        races[1] = PudMap.Race.ORC;
        races[2] = PudMap.Race.HUMAN;
        int[] nothing = new int[PudMap.PLAYER_MAX];
        return new PudMap("two seats", PudMap.Tileset.FOREST, 8, 8, new int[64],
                slots, races, nothing, nothing, nothing, nothing, null, null, List.of());
    }

    @Test
    @DisplayName("retail BNE disables an additional local person seat")
    void additionalLocalPersonSeatIsDisabled() {
        Player[] players = Player.from(pud());
        assertEquals(PudMap.PlayerType.PERSON, players[0].type(),
                "the first person keeps the seat");
        assertEquals(PudMap.PlayerType.NOBODY, players[2].type(),
                "retail BNE does not fill a second local-person slot with an AI");
    }

    @Test
    @DisplayName("retail BNE retains the neutral resource words from the PUD")
    void neutralBankRetainsPudResources() {
        PudMap base = pud();
        int[] gold = new int[PudMap.PLAYER_MAX];
        int[] wood = new int[PudMap.PLAYER_MAX];
        int[] oil = new int[PudMap.PLAYER_MAX];
        gold[15] = 1000;
        wood[15] = 1000;
        oil[15] = 1000;
        PudMap withBank = new PudMap(base.description(), base.tileset(),
                base.width(), base.height(), base.tiles(), base.players(), base.races(),
                gold, wood, oil, new int[PudMap.PLAYER_MAX], null, null, base.units());

        Player[] players = Player.from(withBank);
        assertEquals(1000, players[15].get(UnitType.Resource.GOLD),
                "the neutral slot keeps its PUD gold bank");
        assertEquals(1000, players[15].get(UnitType.Resource.WOOD));
        assertEquals(1000, players[15].get(UnitType.Resource.OIL));
    }

    @Test
    @DisplayName("solo skirmish fills additional person seats with computers")
    void soloSkirmishPromotesExtraPersonsToComputers() {
        Player[] players = Player.forSoloGame(pud());
        assertEquals(PudMap.PlayerType.PERSON, players[0].type());
        assertEquals(PudMap.PlayerType.COMPUTER, players[2].type(),
                "a solo skirmish needs opponents in the other person seats");
    }

    @Test
    @DisplayName("network games keep every human and computer the lobby seated")
    void networkGameUsesSettledLobbyTable() {
        PudMap map = pud();
        PudMap.PlayerType[] slots = map.players().clone();
        PudMap.Race[] races = map.races().clone();
        slots[2] = PudMap.PlayerType.PERSON;
        races[2] = PudMap.Race.ORC;

        Player[] players = Player.forNetworkGame(map, slots, races);

        assertEquals(PudMap.PlayerType.PERSON, players[0].type());
        assertEquals(PudMap.PlayerType.COMPUTER, players[1].type());
        assertEquals(PudMap.PlayerType.PERSON, players[2].type(),
                "the joining player's slot must not be disabled as an extra local seat");
        assertEquals(PudMap.Race.ORC, players[2].race(),
                "the host's lobby race choice must define the network world");
        assertEquals(2100, players[0].get(UnitType.Resource.GOLD),
                "retail map-default network games clamp low PUD gold");
        assertEquals(1100, players[0].get(UnitType.Resource.WOOD));
        assertEquals(1000, players[0].get(UnitType.Resource.OIL));
    }

    @Test
    @DisplayName("network resource floors preserve richer map-defined banks")
    void networkResourceFloorDoesNotReplaceHigherMapValues() {
        PudMap base = pud();
        int[] gold = new int[PudMap.PLAYER_MAX];
        int[] wood = new int[PudMap.PLAYER_MAX];
        int[] oil = new int[PudMap.PLAYER_MAX];
        gold[0] = 7500;
        wood[0] = 4200;
        oil[0] = 1800;
        PudMap rich = new PudMap(base.description(), base.tileset(), base.width(),
                base.height(), base.tiles(), base.players(), base.races(), gold,
                wood, oil, new int[PudMap.PLAYER_MAX], null, null, base.units());

        Player[] players = Player.forNetworkGame(rich, rich.players(), rich.races());

        assertEquals(7500, players[0].get(UnitType.Resource.GOLD));
        assertEquals(4200, players[0].get(UnitType.Resource.WOOD));
        assertEquals(1800, players[0].get(UnitType.Resource.OIL));
    }
}
