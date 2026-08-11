package net.chonkbase.chonkcraft.engine.missile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Damaged buildings burn.
 *
 * <p>Warcraft II marks a building's condition on the building itself: a hit
 * one smokes, a badly hurt one is properly alight, and repairing it puts the
 * fire out. This implementation simulated the damage and drew nothing, so a keep at a
 * tenth of its health looked exactly like one that had never been touched, and
 * the only way to find the building that was about to fall was to click every
 * one of them.
 *
 * <p>The whole of it is data: {@code DefineBurningBuilding} at the foot of
 * {@code scripts/missiles.legacy-declaration} says which fire goes with which health, and it
 * was an unbound script call -- recorded as a gap and swallowed. These tests
 * assert against behaviour rather than against the declaration, and the
 * boundary case is the one worth stating outright: an entry's percent is a
 * floor, so a building at exactly fifty per cent wears the <em>small</em> fire
 * and not the big one.
 */
class BurningBuildingTest {

    private static GameData load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    /** Open ground, one player, nobody hostile: only what a test does happens. */
    private static World field(GameData data) {
        int size = 48;
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i,
                    i < 2 ? PudMap.PlayerType.PERSON : PudMap.PlayerType.NOBODY,
                    PudMap.Race.HUMAN);
        }
        World world = new World(map, players);
        world.setUnitTypes(data.unitTypes().types());
        world.setMissileTypes(data.missiles().types());
        world.setUpgrades(data.upgrades().upgrades());
        return world;
    }

    /**
     * A building and something to hit it with, both owned by the same player.
     *
     * <p>Same player on purpose. The test drives every blow itself through
     * {@link World#hit}, and a hostile footman standing next to a town hall
     * would auto-attack it between ticks and take the health somewhere the test
     * did not put it.
     */
    private record Siege(World world, Unit building, Unit attacker) {}

    private static Siege siege(GameData data) {
        World world = field(data);
        UnitType hallType = data.unitTypes().types().get("unit-town-hall");
        UnitType footmanType = data.unitTypes().types().get("unit-footman");
        assertNotNull(hallType, "the shipped data has a town hall");
        assertNotNull(footmanType, "the shipped data has a footman");
        Unit hall = world.createUnit(hallType, 0, 20, 20);
        Unit footman = world.createUnit(footmanType, 0, 18, 18);
        assertNotNull(hall);
        assertNotNull(footman);
        return new Siege(world, hall, footman);
    }

    /** Sets a unit's health to a whole percentage of its maximum. */
    private static void setHealthPercent(Unit unit, int percent) {
        unit.setHitPoints(unit.type().hitPoints() * percent / 100);
    }

    /**
     * Lands one blow and lets the world publish what it did.
     *
     * <p>The tick is not optional. {@code World.missiles()} hands back a
     * snapshot taken once a cycle rather than the live list, so a fire lit by
     * a blow only becomes visible to a reader -- the renderer, or this test --
     * when the cycle it was lit in finishes. In a game every blow lands inside
     * a cycle that then finishes, so the fire is up on the same frame; a test
     * calling {@link World#hit} directly has to close the cycle itself.
     */
    private static void strike(Siege siege) {
        siege.world().hit(siege.attacker(), siege.building());
        siege.world().tick();
    }

    /** Every fire currently burning, in the order the world holds them. */
    private static List<Missile> fires(World world) {
        return world.missiles().stream()
                .filter(missile -> missile.type().missileClass() == MissileClass.FIRE)
                .toList();
    }

    /** The one fire there should be, and a readable failure when there is not. */
    private static Missile onlyFire(World world) {
        List<Missile> burning = fires(world);
        assertEquals(1, burning.size(),
                "expected exactly one fire, found " + burning.size() + ": "
                        + burning.stream().map(missile -> missile.type().ident()).toList());
        return burning.get(0);
    }

    /**
     * Ticks long enough for a fire to reach the end of its animation and
     * re-decide which fire it is.
     *
     * <p>Upstream only re-picks on the wrap, and this implementation keeps that: the big
     * fire is ten frames at a sleep of two, so twenty cycles is one full loop
     * and forty is comfortably two.
     */
    private static void tickPastAnAnimation(World world) {
        for (int i = 0; i < 40; i++) {
            world.tick();
        }
    }

    @Test
    @DisplayName("a building knocked to 70% carries one fire, and it is the small one")
    void aDamagedBuildingCatchesTheSmallFire() {
        GameData data = load();
        Siege siege = siege(data);
        World world = siege.world();

        assertTrue(fires(world).isEmpty(), "an undamaged building is not on fire");

        setHealthPercent(siege.building(), 70);
        strike(siege);

        Missile fire = onlyFire(world);
        assertEquals("missile-small-fire", fire.type().ident(),
                "between a half and three quarters is the small fire");
        assertTrue(siege.building().isBurning(), "the building knows it is alight");

        // Over the building, and raised by one tile, as HitUnit_Burning places
        // it. A town hall is four by four, so its middle is 64 pixels in from
        // its corner on both axes.
        assertEquals(20 * 32 + 64, (int) fire.x(), "the fire sits on the building's middle");
        assertEquals(20 * 32 + 64 - 32, (int) fire.y(),
                "the fire sits one tile above the building's middle");
    }

    @Test
    @DisplayName("burning harder is the same fire growing, not a second one")
    void theFireGrowsRatherThanMultiplying() {
        GameData data = load();
        Siege siege = siege(data);
        World world = siege.world();

        setHealthPercent(siege.building(), 70);
        strike(siege);
        Missile fire = onlyFire(world);
        assertEquals("missile-small-fire", fire.type().ident());

        setHealthPercent(siege.building(), 30);
        tickPastAnAnimation(world);

        Missile stillBurning = onlyFire(world);
        assertSame(fire, stillBurning,
                "the fire on a building that got worse is the same fire, not a new one");
        assertEquals("missile-big-fire", stillBurning.type().ident(),
                "below a half is the big fire");
    }

    @Test
    @DisplayName("at exactly half health the fire is the small one")
    void theBoundaryBelongsToTheEntryThatNamesIt() {
        GameData data = load();
        Siege siege = siege(data);
        World world = siege.world();

        setHealthPercent(siege.building(), 70);
        strike(siege);

        // Exactly fifty per cent, set rather than dealt, because the point of
        // this test is the boundary and a random blow cannot land on it.
        siege.building().setHitPoints(siege.building().type().hitPoints() / 2);
        assertEquals(50, 100 * siege.building().hitPoints()
                / siege.building().type().hitPoints(), "the fixture wants exactly half");
        tickPastAnAnimation(world);
        assertEquals("missile-small-fire", onlyFire(world).type().ident(),
                "fifty is the small fire's own entry, so fifty is the small fire");

        // One hit point below it is the other side of the line.
        siege.building().setHitPoints(siege.building().type().hitPoints() * 49 / 100);
        tickPastAnAnimation(world);
        assertEquals("missile-big-fire", onlyFire(world).type().ident(),
                "one per cent below the boundary is the big fire");
    }

    @Test
    @DisplayName("a building repaired to full stops burning, and can catch light again")
    void repairingPutsTheFireOut() {
        GameData data = load();
        Siege siege = siege(data);
        World world = siege.world();

        setHealthPercent(siege.building(), 70);
        strike(siege);
        assertEquals(1, fires(world).size());

        siege.building().setHitPoints(siege.building().type().hitPoints());
        tickPastAnAnimation(world);

        assertTrue(fires(world).isEmpty(), "a building at full health does not burn");
        assertFalse(siege.building().isBurning(),
                "the flag has to be cleared or the building can never catch light again");

        // And it can. This is the half the flag exists for: without clearing
        // it, a building repaired once would be marked as burning for the rest
        // of the game and never show damage again.
        setHealthPercent(siege.building(), 60);
        strike(siege);
        assertEquals(1, fires(world).size(), "a repaired building catches fire again");
    }

    @Test
    @DisplayName("a building above three quarters health shows no fire at all")
    void aScratchDoesNotBurn() {
        GameData data = load();
        Siege siege = siege(data);
        World world = siege.world();

        setHealthPercent(siege.building(), 90);
        strike(siege);

        assertTrue(fires(world).isEmpty(),
                "the shipped table names no missile at or above 75%, so nothing burns");
        assertFalse(siege.building().isBurning());
    }

    @Test
    @DisplayName("killing the building takes the fire with it")
    void deathEndsTheFire() {
        GameData data = load();
        Siege siege = siege(data);
        World world = siege.world();

        setHealthPercent(siege.building(), 40);
        strike(siege);
        assertEquals(1, fires(world).size());

        world.kill(siege.building());
        tickPastAnAnimation(world);

        assertTrue(fires(world).isEmpty(),
                "a destroyed building's fire has nothing left to burn on");
    }

    @Test
    @DisplayName("a footman taking the same beating does not catch fire")
    void onlyBuildingsBurn() {
        GameData data = load();
        World world = field(data);
        UnitType footmanType = data.unitTypes().types().get("unit-footman");
        assertNotNull(footmanType);
        Unit victim = world.createUnit(footmanType, 0, 20, 20);
        Unit attacker = world.createUnit(footmanType, 0, 18, 18);
        assertNotNull(victim);
        assertNotNull(attacker);

        setHealthPercent(victim, 30);
        world.hit(attacker, victim);

        assertTrue(fires(world).isEmpty(), "men do not burn in Warcraft II, buildings do");
        assertFalse(victim.isBurning());
    }

    @Test
    @DisplayName("a building under sustained fire never stacks a second flame")
    void oneFirePerBuilding() {
        GameData data = load();
        Siege siege = siege(data);
        World world = siege.world();

        // Twenty blows, health held down so every one of them qualifies. Drop
        // the !Burning guard and this is twenty fires drawn on one spot, each
        // stepping its own animation.
        for (int blow = 0; blow < 20; blow++) {
            setHealthPercent(siege.building(), 60);
            strike(siege);
            world.tick();
            assertEquals(1, fires(world).size(),
                    "after " + (blow + 1) + " blows there should still be one fire");
        }
    }

    @Test
    @DisplayName("the thresholds are the shipped script's, not the engine's")
    void theTableComesFromTheScript() {
        GameData data = load();
        BurningBuildingFrames frames = data.missiles().burningBuildings();

        assertFalse(frames.isEmpty(), "scripts/missiles.legacy-declaration declares the table");
        assertTrue(frames.isValid(),
                "IsBurningBuildingFramesValid: sorted, and inside nought to a hundred");

        // Exactly what the shipped file says, including the third row that
        // names no missile -- the row that is the whole mechanism for a fire
        // going out.
        assertEquals(3, frames.frames().size());
        assertEquals(0, frames.frames().get(0).percent());
        assertEquals("missile-big-fire", frames.frames().get(0).missile().ident());
        assertEquals(50, frames.frames().get(1).percent());
        assertEquals("missile-small-fire", frames.frames().get(1).missile().ident());
        assertEquals(75, frames.frames().get(2).percent());
        assertNull(frames.frames().get(2).missile(), "the last row names no missile");

        // The lookup rule, stated at the boundaries.
        assertEquals("missile-big-fire", frames.missileAt(0).ident());
        assertEquals("missile-big-fire", frames.missileAt(49).ident());
        assertEquals("missile-small-fire", frames.missileAt(50).ident());
        assertEquals("missile-small-fire", frames.missileAt(74).ident());
        assertNull(frames.missileAt(75), "three quarters and up is no fire");
        assertNull(frames.missileAt(100));

        // And it is bound: the call used to fall through to the catch-all,
        // which recorded the name and did nothing.

    }

    @Test
    @DisplayName("a different table gives different fires, so nothing is hardcoded")
    void theEngineObeysWhateverTableItIsGiven() {
        GameData data = load();
        Siege siege = siege(data);
        World world = siege.world();

        // A table nothing ships: burn from ninety per cent down, and use the
        // big fire where the shipped data uses the small one. If any of the
        // shipped numbers were written into the engine rather than read, the
        // building below would either not burn at all or burn with the wrong
        // sprite.
        MissileType big = data.missiles().types().get("missile-big-fire");
        MissileType small = data.missiles().types().get("missile-small-fire");
        assertNotNull(big);
        assertNotNull(small);
        world.setBurningBuildings(new BurningBuildingFrames(List.of(
                new BurningBuildingFrames.Frame(0, small),
                new BurningBuildingFrames.Frame(90, big),
                new BurningBuildingFrames.Frame(95, null))));

        setHealthPercent(siege.building(), 92);
        strike(siege);
        assertEquals("missile-big-fire", onlyFire(world).type().ident(),
                "at 92% this table says big fire, where the shipped one says no fire");

        setHealthPercent(siege.building(), 20);
        tickPastAnAnimation(world);
        assertEquals("missile-small-fire", onlyFire(world).type().ident(),
                "at 20% this table says small fire, where the shipped one says big");
    }
}
