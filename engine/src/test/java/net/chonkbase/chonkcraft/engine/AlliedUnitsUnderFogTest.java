package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Whether an ally's units are hidden by the fog the way everybody else's are.
 *
 * <p>They were not. {@code World.isVisibleTo(int, Unit)} opened with
 * {@code if (isAllied(player, unit.player())) return true}, and
 * {@code CPlayer::Init} makes a person
 * allied with every rescue-passive and rescue-active slot on the map. Nothing
 * on a campaign map calls {@code ShareVisionWith} -- only the team game types do -- so upstream's {@code CUnit::IsVisible}
 * gives the ally's units no help at all, and this implementation gave them a free pass.
 *
 * <p>What the player saw, on the fifth human mission: the red humans they
 * could not control mined and walked and turned to face the trees under the
 * half-transparent veil over ground the player had scouted and left, while the
 * orc computer's army stayed properly hidden. Seventeen of the fifty-two
 * missions carry a rescuable slot, and across them 178 units were drawn
 * through fog, 99 of them mobile.
 *
 * <p>Both checks below ask {@code isVisibleTo}, which is the question
 * {@code GameScreen.isUnitVisible} asks for every unit it is about to draw.
 */
class AlliedUnitsUnderFogTest {

    private static GameData load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    /** Whether any square of a unit's footprint is lit for a player. */
    private static boolean lit(World world, int player, Unit unit) {
        for (int y = 0; y < Math.max(1, unit.type().tileHeight()); y++) {
            for (int x = 0; x < Math.max(1, unit.type().tileWidth()); x++) {
                if (world.fog().isVisible(player, unit.tileX() + x, unit.tileY() + y)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The mission the report came from, played for twenty seconds and then
     * asked what the screen would draw.
     *
     * <p>The whole map is marked explored first, which is the state the player
     * is in once they have flown a balloon over the neighbourhood: the terrain
     * is remembered, the veil is back over it, and nothing but their own units
     * lights anything. Every unit standing on that ground has to be hidden --
     * the ally's included.
     */
    @Test
    @DisplayName("the rescuable humans' peasants hide in the fog like everybody else's")
    void aRescuableAllysUnitsAreHiddenByTheFog() {
        GameData data = load();
        PudMap source = data.campaignMap("campaigns/human/level05h");
        Assumptions.assumeTrue(source != null, "the fifth human mission is not available");
        int me = GameData.personIn(source);
        Mission mission = data.loadMission("campaigns/human/level05h");
        assertNotNull(mission, "the fifth human mission did not load");
        World world = mission.world();

        // The fixture proves nothing unless slot zero really is a rescuable
        // ally holding units. This is the map's own declaration, not the
        // script's: level05h changes nobody's type.
        assertEquals(PudMap.PlayerType.RESCUE_ACTIVE, world.player(0).type(),
                "slot zero should be the rescue-active red humans");
        assertTrue(world.isAllied(me, 0),
                "CPlayer::Init allies a person with a rescue-active slot");
        assertFalse(world.sharesVisionWith(me, 0),
                "a campaign ally shares no vision: only the team game types call ShareVisionWith");

        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 20; cycle++) {
            mission.tick();
        }
        world.fog().revealAll(me);

        List<String> drawnThroughFog = new ArrayList<>();
        int allyUnitsInTheDark = 0;
        int allyUnitsWatched = 0;
        for (Unit unit : world.units()) {
            if (!unit.isAlive() || !unit.isOnMap() || unit.type() == null
                    || unit.player() == me) {
                continue;
            }
            if (lit(world, me, unit)) {
                if (unit.player() == 0) {
                    allyUnitsWatched++;
                }
                continue;
            }
            if (unit.player() == 0) {
                allyUnitsInTheDark++;
            }
            if (world.isVisibleTo(me, unit)) {
                drawnThroughFog.add(unit.type().ident() + " of player " + unit.player()
                        + " at " + unit.tileX() + "," + unit.tileY());
            }
        }

        // Count before checking. A sweep that found no unwatched ally would
        // declare the fog perfect having looked at nothing.
        assertTrue(allyUnitsInTheDark >= 3,
                "the fixture needs the ally's town out of sight or it proves nothing, found "
                        + allyUnitsInTheDark);
        assertTrue(allyUnitsWatched > 0,
                "the fixture needs some of the ally in sight too, or it cannot tell "
                        + "'hidden by fog' from 'never drawn'");
        assertTrue(drawnThroughFog.isEmpty(),
                "units were drawn on ground nobody is watching: " + drawnThroughFog);
    }

    /**
     * The same rule on a fixture built in code, so the two ways an ally can
     * help are told apart.
     *
     * <p>This is the control the mission above cannot be: alliance and shared
     * vision are set separately here, and only the second one lifts the fog.
     * A change that simply deleted the ally clause and a change that deleted
     * everything would both pass the mission check; only this one says which
     * was made.
     */
    @Test
    @DisplayName("an alliance shows you nothing, and shared vision shows you everything")
    void sharedVisionLiftsTheFogAndAnAllianceDoesNot() {
        GameData data = load();
        UnitType peasant = data.unitTypes().types().get("unit-peasant");
        UnitType footman = data.unitTypes().types().get("unit-footman");
        assertNotNull(peasant, "unit-peasant is missing from the shipped data");

        GameMap map = new GameMap(48, 48, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i, switch (i) {
                case 0 -> PudMap.PlayerType.PERSON;
                case 1 -> PudMap.PlayerType.RESCUE_PASSIVE;
                default -> PudMap.PlayerType.NOBODY;
            }, PudMap.Race.HUMAN);
        }
        World world = new World(map, players);
        world.setUnitTypes(data.unitTypes().types());
        world.setMissileTypes(data.missiles().types());
        world.establishDiplomacy();

        Unit prisoner = world.createUnit(peasant, 1, 40, 40);
        assertNotNull(prisoner, "the prisoner was not placed");
        assertTrue(world.isAllied(0, 1),
                "a person and a rescue-passive slot are allied, or this proves nothing");
        assertFalse(world.fog().isVisible(0, 40, 40),
                "the fixture must start with the prisoner's ground dark");

        assertFalse(world.isVisibleTo(0, prisoner),
                "an ally's peasant was visible with nobody watching it: alliance is not "
                        + "shared vision, and CPlayer::Init never calls ShareVisionWith");

        // Their own eyes see them, which is the half that must keep working.
        assertTrue(world.isVisibleTo(1, prisoner), "an owner cannot see its own peasant");

        // Walk somebody over. Ordinary sight is what reveals an ally. Three
        // squares off rather than beside it: a footman standing next to a
        // prisoner frees them, and a peasant that has changed hands lights
        // its own square and would answer the question for the wrong reason.
        Unit scout = world.createUnit(footman, 0, 37, 40);
        assertNotNull(scout, "the scout was not placed");
        assertTrue(world.isVisibleTo(0, prisoner),
                "a peasant three squares from your footman should be visible");
        assertEquals(1, prisoner.player(),
                "the prisoner must not be rescued, or it proves nothing about fog");

        // And the other route: the ally hands over what it can see.
        world.kill(scout);
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 3; cycle++) {
            world.tick();
        }
        assertFalse(world.isVisibleTo(0, prisoner),
                "the prisoner stayed visible after the only witness died");
        world.setSharedVision(0, 1, true);
        assertTrue(world.isVisibleTo(0, prisoner),
                "shared vision is the thing that does reveal an ally, and did not");
    }
}
