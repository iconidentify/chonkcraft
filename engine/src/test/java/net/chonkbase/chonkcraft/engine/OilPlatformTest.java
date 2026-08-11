package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Campaign;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.BuildRestriction;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The third resource, which this implementation had no way of gathering.
 *
 * <p>An oil platform is not put down on empty sea. It is founded on an oil
 * patch and on nothing else, which the data states as
 * {@code BuildingRules = { { "ontop", { Type = "unit-oil-patch",
 * ReplaceOnDie = true, ReplaceOnBuild = true } } }}
 * ({@code scripts/human/units.legacy-declaration:1332}). Upstream honours it in
 * {@code CanBuildUnitType} before it looks at the ground at all -- "Terrain
 * Flags don't matter if building on top of a unit",
 * The game because the patch reserves its own
 * nine squares against being built on and the terrain test would refuse every
 * one of them.
 *
 * <p>This implementation modelled no {@code BuildingRules} of any kind, so
 * {@code orderBuild} reached that terrain test and said no. Measured across
 * the campaign before the fix: 105 oil patches on 29 of the 52 maps, and not
 * one of them would take a platform. Oil buys every ship above a transport, so
 * the naval half of both campaigns was being played with two resources.
 *
 * <p>These start from {@code orderBuild} and from the map, not from the rule
 * objects. A test that asked whether {@code BuildingRules} had been parsed
 * would have passed the moment the key was read and said nothing about whether
 * a tanker could build anything.
 */
class OilPlatformTest {

    /** A mission with oil patches and open water to sail a tanker in from. */
    private static final String MISSION = "campaigns/human/level03h";

    private static GameData load() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install configured. Set -Dchonkcraft.pack or wc2.install.dir.");
        return new GameData(assets);
    }

    private static Mission mission(GameData data, String path) {
        Mission mission = data.loadMission(path);
        Assumptions.assumeTrue(mission != null, "no campaign map available");
        return mission;
    }

    /** The living unit of a type standing with its corner on a square. */
    private static Unit at(World world, String ident, int tileX, int tileY) {
        for (Unit unit : world.units()) {
            if (unit.type() != null && ident.equals(unit.type().ident())
                    && unit.tileX() == tileX && unit.tileY() == tileY && !unit.removed()) {
                return unit;
            }
        }
        return null;
    }

    private static Unit firstOf(World world, String ident) {
        for (Unit unit : world.units()) {
            if (unit.type() != null && ident.equals(unit.type().ident()) && !unit.removed()) {
                return unit;
            }
        }
        return null;
    }

    @Test
    @DisplayName("an oil-platform route ignores a tanker's stale prior destination")
    void oilPlatformRouteIgnoresStalePriorDestination() {
        GameData data = load();
        Mission mission = mission(data, "campaigns/orc/level03o");
        World world = mission.world();
        for (Unit unit : new ArrayList<>(world.units())) {
            world.remove(unit);
        }
        UnitType patchType = data.unitTypes().types().get("unit-oil-patch");
        UnitType tankerType = data.unitTypes().types().get("unit-orc-oil-tanker");
        UnitType destroyerType = data.unitTypes().types().get("unit-orc-destroyer");
        UnitType shipyardType = data.unitTypes().types().get("unit-orc-shipyard");
        UnitType platform = data.unitTypes().types().get("unit-orc-oil-platform");
        Unit patch = world.createUnit(patchType, 15, 7, 51);
        Unit destroyer = world.createUnit(destroyerType, 0, 14, 50);
        Unit shipyard = world.createUnit(shipyardType, 0, 21, 48);
        Unit tanker = world.createUnit(tankerType, 0, 19, 49);
        assertNotNull(patch, "the Orc mission coast rejected its oil patch");
        assertNotNull(destroyer, "the saved-game destroyer could not be reconstructed");
        assertNotNull(shipyard, "the saved-game shipyard could not be reconstructed");
        assertNotNull(tanker, "the saved-game tanker could not be reconstructed");
        world.player(0).set(UnitType.Resource.GOLD, 5000);
        world.player(0).set(UnitType.Resource.WOOD, 5000);

        // The play report came after this tanker had previously sailed toward
        // 15,39. BUILD has its own live site and route, but orderTarget still
        // legitimately retains that older point. A patrol-only residual
        // shortcut used it to replace each multi-step platform route: at
        // 13,47 it chose west, at 11,47 it chose east, forever.
        tanker.setOrderTarget(15, 39);
        assertTrue(world.orderBuild(tanker, platform, patch.tileX(), patch.tileY()),
                "the reconstructed tanker refused the platform order");

        Unit built = null;
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 120 && built == null; cycle++) {
            world.tick();
            built = at(world, platform.ident(), patch.tileX(), patch.tileY());
        }
        assertNotNull(built,
                "the tanker alternated between 13,47 and 11,47 instead of"
                        + " preserving its BNE route to the oil patch");
    }

    /** A clear square of open water within reach of a patch to start a tanker on. */
    private static int[] waterNear(World world, UnitType ship, int tileX, int tileY) {
        long mask = Unit.movementMaskFor(ship);
        long blocking = Unit.blockingFlagsFor(ship);
        int size = Math.max(1, ship.tileWidth());
        for (int radius = 2; radius < 10; radius++) {
            for (int dx = -radius; dx <= radius + 2; dx++) {
                for (int dy = -radius; dy <= radius + 2; dy++) {
                    int x = tileX + dx;
                    int y = tileY + dy;
                    if (world.map().isFootprintFree(x, y, size, size, mask, blocking)) {
                        return new int[] {x, y};
                    }
                }
            }
        }
        return null;
    }

    @Test
    @DisplayName("a tanker founds a platform on an oil patch and pumps the oil out of it")
    void aTankerFoundsAPlatformOnAPatchAndPumpsIt() {
        GameData data = load();
        Mission mission = mission(data, MISSION);
        World world = mission.world();
        world.fog().revealAll(0);

        UnitType platformType = data.unitTypes().types().get("unit-human-oil-platform");
        UnitType tankerType = data.unitTypes().types().get("unit-human-oil-tanker");
        assertNotNull(platformType, "no oil platform in the roster");
        assertNotNull(tankerType, "no oil tanker in the roster");

        Unit patch = firstOf(world, "unit-oil-patch");
        assertNotNull(patch, "the third human mission has no oil patch on it");
        int patchX = patch.tileX();
        int patchY = patch.tileY();
        int oilInTheGround = patch.resourcesHeld();
        assertTrue(oilInTheGround > 0,
                "the fixture must start with oil in the patch or it proves nothing about "
                        + "what the platform inherits");

        // The mission posts an enemy destroyer six squares from this patch,
        // and a warship keeps the nearest thing its threat arithmetic ranks
        // -- which is the spawned tanker, chased to the patch and stood on
        // the build site. It used to wander off instead, because the swap
        // rule replaced its target with any candidate at all; when that rule
        // became upstream's ThreatCalculate comparison the contest surfaced.
        // This test is about the ontop building rule, not the naval fight,
        // so the water is cleared first.
        List<Unit> hostiles = new ArrayList<>();
        for (Unit unit : world.units()) {
            if (unit.type() != null && unit.type().canAttack() && !unit.removed()
                    && unit.player() != 0
                    && Math.abs(unit.tileX() - patchX) < 15
                    && Math.abs(unit.tileY() - patchY) < 15) {
                hostiles.add(unit);
            }
        }
        for (Unit hostile : hostiles) {
            world.kill(hostile);
        }

        int[] start = waterNear(world, tankerType, patchX, patchY);
        assertNotNull(start, "no open water beside the patch to start a tanker on");
        Unit tanker = world.createUnit(tankerType, 0, start[0], start[1]);
        assertNotNull(tanker, "the tanker could not be put on the water at "
                + start[0] + "," + start[1]);
        tanker.setHitPoints(tankerType.hitPoints());
        world.player(0).set(UnitType.Resource.GOLD, 5000);
        world.player(0).set(UnitType.Resource.WOOD, 5000);

        assertTrue(world.orderBuild(tanker, platformType, patchX, patchY),
                "a tanker was refused the order to found an oil platform on the oil patch at "
                        + patchX + "," + patchY + ": the ontop building rule is not consulted, so "
                        + "the patch's own MapFieldNoBuilding refuses the site");

        Unit platform = null;
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 120; cycle++) {
            mission.tick();
            Unit built = at(world, "unit-human-oil-platform", patchX, patchY);
            if (built != null && built.order() != Unit.Order.UNDER_CONSTRUCTION
                    && built.hitPoints() >= platformType.hitPoints()) {
                platform = built;
                break;
            }
        }
        assertNotNull(platform, "the platform was ordered and never finished at "
                + patchX + "," + patchY);

        // ReplaceOnBuild. The patch is gone and its oil
        // is in the platform: a platform that kept its own type's figure would
        // be a well of whatever HitPoints said.
        assertNull(at(world, "unit-oil-patch", patchX, patchY),
                "the oil patch is still standing under the platform: ReplaceOnBuild says the "
                        + "parent is removed when the building starts");
        assertEquals(oilInTheGround, platform.resourcesHeld(),
                "the platform did not take over what the patch was holding: ReplaceOnBuild "
                        + "copies ResourcesHeld across at action_build.cpp:304");

        int banked = world.player(0).get(UnitType.Resource.OIL);
        assertTrue(tanker.removed(),
                "BNE keeps the platform builder inside for its first oil load");
        assertEquals(platform, tanker.worksite(),
                "the completed platform must become its builder's resource container");
        assertEquals(Unit.Order.HARVEST, tanker.order(),
                "the builder must begin native resource action 23 without another command");
        assertEquals(Unit.BattleNetOilAction.TO_RESOURCE, tanker.battleNetOilAction());
        int carriedAtMost = 0;
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 300; cycle++) {
            mission.tick();
            carriedAtMost = Math.max(carriedAtMost, tanker.carried());
        }
        assertTrue(carriedAtMost > 0,
                "the tanker never loaded any oil from the platform it had just built");
        assertTrue(platform.resourcesHeld() < oilInTheGround,
                "the platform gave out oil without losing any: it still holds "
                        + platform.resourcesHeld() + " of " + oilInTheGround);
        assertTrue(world.player(0).get(UnitType.Resource.OIL) > banked,
                "no oil reached the player's bank: it went from " + banked + " to "
                        + world.player(0).get(UnitType.Resource.OIL));
    }

    /**
     * A laden tanker unloads at a shipyard, because a shipyard stores oil.
     *
     * <p>Reported from play on this very mission: "oil tankers are stuck ...
     * they seem to have picked it up but no way to return it." The player had
     * a shipyard near their platform and the tanker loaded its hundred and
     * stood down in open water for the rest of the game -- measured, nought
     * oil banked in four simulated minutes. The depot search had invented a
     * rule out of {@code refinery-harvester}, which both tankers declare
     * ({@code scripts/human/units.legacy-declaration:541}): it read the flag as "may only
     * unload at a building named refinery", where upstream's one reader is
     * the AI's building-place finder and the
     * flag means the harvester's mine goes on top of the resource.
     * {@code FindDeposit} filters by {@code CanStore} alone
     * and both shipyards declare
     * {@code CanStore = {"oil"}}.
     *
     * <p>Why the suite stayed green: the pumping test above plays player
     * nought, and this map gives player nought a refinery at 45,53 -- the
     * orcs' own. A fixture with a refinery in reach cannot tell the real rule
     * from the invented one, so this one plays the mission's person slot,
     * which owns no refinery, and requires the guard to say so.
     */
    @Test
    @DisplayName("a laden tanker unloads at its own shipyard, with no refinery anywhere")
    void aLadenTankerUnloadsAtItsOwnShipyard() {
        GameData data = load();
        Mission mission = mission(data, MISSION);
        World world = mission.world();
        int me = -1;
        for (var player : world.players()) {
            if (player.type() == net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.PERSON) {
                me = player.index();
                break;
            }
        }
        assertTrue(me >= 0, "the mission seats no person");
        world.fog().revealAll(me);
        for (Unit unit : world.units()) {
            assertTrue(unit.player() != me || unit.type() == null
                            || !unit.type().ident().contains("refinery"),
                    "the person owns a refinery on this map, so a delivery here cannot"
                            + " tell CanStore from the invented refinery-only rule");
        }

        UnitType shipyardType = data.unitTypes().types().get("unit-human-shipyard");
        UnitType platformType = data.unitTypes().types().get("unit-human-oil-platform");
        UnitType tankerType = data.unitTypes().types().get("unit-human-oil-tanker");
        Unit patch = firstOf(world, "unit-oil-patch");
        assertNotNull(patch, "the third human mission has no oil patch on it");
        for (UnitType.Resource resource : UnitType.Resource.values()) {
            world.player(me).set(resource, 10000);
        }

        // A shipyard where a player would put one: the first coastal site
        // near the patch that the placement rules accept.
        Unit shipyard = null;
        for (int r = 2; r < 40 && shipyard == null; r++) {
            for (int y = patch.tileY() - r; y <= patch.tileY() + r && shipyard == null; y++) {
                for (int x = patch.tileX() - r; x <= patch.tileX() + r && shipyard == null; x++) {
                    if (world.map().contains(x, y)
                            && world.canPlaceBuilding(shipyardType, x, y)) {
                        shipyard = world.createUnit(shipyardType, me, x, y);
                    }
                }
            }
        }
        assertNotNull(shipyard, "nowhere near the patch to found a shipyard");

        int[] start = waterNear(world, tankerType, patch.tileX(), patch.tileY());
        assertNotNull(start, "no open water beside the patch");
        Unit tanker = world.createUnit(tankerType, me, start[0], start[1]);
        assertNotNull(tanker, "the tanker could not be put on the water");
        assertTrue(world.orderBuild(tanker, platformType, patch.tileX(), patch.tileY()),
                "the tanker was refused the platform order");
        Unit platform = null;
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 120 && platform == null; cycle++) {
            mission.tick();
            Unit built = at(world, "unit-human-oil-platform", patch.tileX(), patch.tileY());
            if (built != null && built.order() != Unit.Order.UNDER_CONSTRUCTION) {
                platform = built;
            }
        }
        assertNotNull(platform, "the platform never finished");

        // BNE does not drop the builder into open water and wait for another
        // command. It keeps that same tanker inside the completed platform
        // while native oil action 23 loads its first cargo. Looking only for
        // an on-map tanker therefore turns the correct transition into "the
        // tanker disappeared" and, worse, replaces the automatic retail
        // order with a second synthetic player order.
        Unit hauler = tanker;
        assertTrue(hauler.hitPoints() > 0 && hauler.order() != Unit.Order.DYING,
                "the platform destroyed its tanker builder");
        assertTrue(hauler.removed(),
                "the platform builder should remain inside for its first oil load");
        assertEquals(platform, hauler.worksite(),
                "the completed platform did not become the builder's resource container");
        assertEquals(Unit.Order.HARVEST, hauler.order(),
                "the platform builder did not begin its automatic first oil trip");
        int banked = world.player(me).get(UnitType.Resource.OIL);
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 240; cycle++) {
            mission.tick();
            if (world.player(me).get(UnitType.Resource.OIL) > banked) {
                break;
            }
        }
        assertTrue(world.player(me).get(UnitType.Resource.OIL) > banked,
                "a tanker loaded a hundred oil and banked none of it in four minutes,"
                        + " with its own shipyard standing and CanStore = {\"oil\"} on it:"
                        + " the depot search demands a refinery that refinery-harvester"
                        + " never asked for");
    }

    @Test
    @DisplayName("a platform blown up over oil leaves the patch behind for somebody else")
    void aDestroyedPlatformPutsThePatchBack() {
        GameData data = load();
        Mission mission = mission(data, MISSION);
        World world = mission.world();
        world.fog().revealAll(0);

        UnitType platformType = data.unitTypes().types().get("unit-human-oil-platform");
        BuildRestriction.OnTop rule = platformType.onTopRule();
        assertNotNull(rule, "the platform declares no ontop rule, so this proves nothing");
        assertTrue(rule.replaceOnDie(),
                "the fixture needs ReplaceOnDie set or there is nothing to put back");

        Unit patch = firstOf(world, "unit-oil-patch");
        assertNotNull(patch, "the third human mission has no oil patch on it");
        int patchX = patch.tileX();
        int patchY = patch.tileY();
        int oil = patch.resourcesHeld();

        // Straight to a finished platform: the founding half is the test above,
        // and what is being measured here is what its death leaves behind.
        world.remove(patch);
        Unit platform = world.createUnit(platformType, 0, patchX, patchY);
        assertNotNull(platform, "the platform could not be placed at " + patchX + "," + patchY);
        platform.setHitPoints(platformType.hitPoints());
        platform.setResourcesHeld(oil);
        assertNull(at(world, "unit-oil-patch", patchX, patchY),
                "the fixture must have no patch under the platform or it proves nothing");

        world.kill(platform);
        for (int cycle = 0; cycle < 5; cycle++) {
            mission.tick();
        }

        Unit back = at(world, "unit-oil-patch", patchX, patchY);
        assertNotNull(back, "the oil patch did not come back where the platform stood: "
                + "UnitLost re-makes the parent under ReplaceOnDie at unit.cpp:1364, which is "
                + "what stops a bombed platform taking the whole oil field with it");
        assertEquals(oil, back.resourcesHeld(),
                "the patch came back empty: upstream carries ResourcesHeld across at "
                        + "unit.cpp:1372");
        assertEquals(World.NEUTRAL_PLAYER, back.player(),
                "the patch came back owned by a player: MakeUnitAndPlace hands it to "
                        + "Players[PlayerNumNeutral]");
    }

    @Test
    @DisplayName("a dry platform leaves nothing behind, because there is no oil left to leave")
    void aDryPlatformLeavesNothing() {
        GameData data = load();
        Mission mission = mission(data, MISSION);
        World world = mission.world();

        UnitType platformType = data.unitTypes().types().get("unit-human-oil-platform");
        Unit patch = firstOf(world, "unit-oil-patch");
        assertNotNull(patch, "the third human mission has no oil patch on it");
        int patchX = patch.tileX();
        int patchY = patch.tileY();
        world.remove(patch);

        Unit platform = world.createUnit(platformType, 0, patchX, patchY);
        assertNotNull(platform, "the platform could not be placed");
        platform.setHitPoints(platformType.hitPoints());
        platform.setResourcesHeld(0);

        world.kill(platform);
        for (int cycle = 0; cycle < 5; cycle++) {
            mission.tick();
        }
        assertNull(at(world, "unit-oil-patch", patchX, patchY),
                "a worked-out platform put an oil patch back: upstream's condition is "
                        + "ReplaceOnDie && type.GivesResource && unit.ResourcesHeld != 0 "
                        + "(unit.cpp:1365), so an empty one leaves nothing");
    }

    @Test
    @DisplayName("every oil patch in the campaign will take a platform unless a ship is parked on it")
    void everyPatchInTheCampaignWillTakeAPlatform() {
        GameData data = load();
        UnitType human = data.unitTypes().types().get("unit-human-oil-platform");
        UnitType orc = data.unitTypes().types().get("unit-orc-oil-platform");
        assertNotNull(human, "no human oil platform in the roster");
        assertNotNull(orc, "no orc oil platform in the roster");

        List<String> paths = new ArrayList<>();
        for (Campaign campaign : data.campaigns()) {
            for (var step : campaign.missions()) {
                paths.add(step.mapArchivePath());
            }
        }
        Assumptions.assumeTrue(!paths.isEmpty(), "no campaign scripts in this checkout");

        int patches = 0;
        int placeable = 0;
        int occupied = 0;
        for (String path : paths) {
            Mission mission;
            try {
                mission = data.loadMission(path);
            } catch (RuntimeException broken) {
                continue;
            }
            if (mission == null) {
                continue;
            }
            World world = mission.world();
            for (Unit patch : new ArrayList<>(world.units())) {
                if (patch.type() == null || !"unit-oil-patch".equals(patch.type().ident())) {
                    continue;
                }
                patches++;
                boolean shipOnIt = shipInFootprint(world, patch);
                boolean allowed = world.canPlaceBuilding(human, patch.tileX(), patch.tileY());
                assertEquals(allowed,
                        world.canPlaceBuilding(orc, patch.tileX(), patch.tileY()),
                        "the two races' platforms disagree about the patch at " + patch.tileX()
                                + "," + patch.tileY() + " on " + path);
                if (shipOnIt) {
                    occupied++;
                    assertFalse(allowed, "the patch at " + patch.tileX() + "," + patch.tileY()
                            + " on " + path + " has a ship sitting in its footprint and still "
                            + "accepted a platform: build.cpp:405 refuses a site with anything "
                            + "of the parent's own movement kind on it");
                } else {
                    placeable++;
                    assertTrue(allowed, "the oil patch at " + patch.tileX() + "," + patch.tileY()
                            + " on " + path + " will not take an oil platform: the ontop "
                            + "building rule is not being consulted, so the patch's own "
                            + "MapFieldNoBuilding refuses the site");
                }
            }
        }
        // Counted, not merely checked. A sweep that found no patches would
        // declare the whole oil economy sound while it sat behind this.
        assertTrue(patches >= 100,
                "only " + patches + " oil patches found across the campaigns, so this sweep "
                        + "proves very little");
        assertTrue(placeable > 100,
                "only " + placeable + " of " + patches + " patches will take a platform ("
                        + occupied + " have a ship parked on them)");
    }

    /** Whether anything naval but the patch itself stands inside its footprint. */
    private static boolean shipInFootprint(World world, Unit patch) {
        UnitType type = patch.type();
        for (Unit other : world.units()) {
            if (other == patch || other.type() == null || other.removed()) {
                continue;
            }
            if (other.type().moveType() != type.moveType()) {
                continue;
            }
            int width = Math.max(1, other.type().tileWidth());
            int height = Math.max(1, other.type().tileHeight());
            boolean apart = other.tileX() + width <= patch.tileX()
                    || patch.tileX() + Math.max(1, type.tileWidth()) <= other.tileX()
                    || other.tileY() + height <= patch.tileY()
                    || patch.tileY() + Math.max(1, type.tileHeight()) <= other.tileY();
            if (!apart) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("a platform cannot be founded on open sea, only on a patch")
    void openSeaWillNotTakeAPlatform() {
        GameData data = load();
        Mission mission = mission(data, MISSION);
        World world = mission.world();

        UnitType platformType = data.unitTypes().types().get("unit-human-oil-platform");
        assertNotNull(platformType, "no oil platform in the roster");

        // Open sea wide enough to take the platform's own three by three
        // footprint, and with no patch anywhere under it. The fixture has to
        // be somewhere the terrain would say yes, or the refusal below could
        // be the ground talking rather than the rule.
        int[] open = null;
        long mask = Unit.movementMaskFor(platformType);
        long blocking = Unit.blockingFlagsFor(platformType);
        for (int y = 0; y < world.map().height() && open == null; y++) {
            for (int x = 0; x < world.map().width(); x++) {
                if (!world.map().isFootprintFree(x, y, 3, 3, mask, blocking)) {
                    continue;
                }
                boolean patchUnder = false;
                for (int dy = 0; dy < 3 && !patchUnder; dy++) {
                    for (int dx = 0; dx < 3; dx++) {
                        if (at(world, "unit-oil-patch", x + dx, y + dy) != null) {
                            patchUnder = true;
                            break;
                        }
                    }
                }
                if (!patchUnder) {
                    open = new int[] {x, y};
                    break;
                }
            }
        }
        assertNotNull(open, "no clear stretch of sea on the map, so there is nothing to contrast");

        assertFalse(world.canPlaceBuilding(platformType, open[0], open[1]),
                "an oil platform was accepted at " + open[0] + "," + open[1] + " on open sea "
                        + "with no oil patch under it: a type with BuildingRules and no rule "
                        + "that passes cannot be built at all (build.cpp:475-485)");
    }

    /**
     * The clearance a town hall keeps from a gold mine.
     *
     * <p>Every site tested here is one the mine's own footprint does not
     * touch, and every one is checked twice: with the mine on the map, where
     * the rule must refuse it, and with the mine taken away, where the same
     * site must be accepted. That pairing is the whole test. A site chosen a
     * square off the mine's corner is refused by the occupancy check whatever
     * the rule says -- a four by four hall laid there covers the mine -- so
     * asking about one proves nothing at all: it passes against a port that
     * has never heard of {@code BuildingRules}, which is how this was
     * originally written and what the pairing is here to prevent.
     *
     * <p>Measured over the campaign: the rule turns 5,002 accepted sites
     * within eight squares of a mine into 2,695.
     */
    @Test
    @DisplayName("a town hall keeps its distance from the gold mine, and the mine is why")
    void aTownHallKeepsItsDistanceFromAGoldMine() {
        GameData data = load();
        UnitType hall = data.unitTypes().types().get("unit-town-hall");
        assertNotNull(hall, "no town hall in the roster");
        assertFalse(hall.buildingRules().isEmpty(),
                "the town hall declares no BuildingRules, so this proves nothing: "
                        + "scripts/human/units.legacy-declaration:1194 gives it a three-square clearance "
                        + "from unit-gold-mine");

        List<String> paths = new ArrayList<>();
        for (Campaign campaign : data.campaigns()) {
            for (var step : campaign.missions()) {
                paths.add(step.mapArchivePath());
            }
        }
        Assumptions.assumeTrue(!paths.isEmpty(), "no campaign scripts in this checkout");

        int mines = 0;
        int sitesTheMineAloneRefuses = 0;
        for (String path : paths) {
            Mission mission;
            try {
                mission = data.loadMission(path);
            } catch (RuntimeException broken) {
                continue;
            }
            if (mission == null) {
                continue;
            }
            World world = mission.world();
            for (Unit mine : new ArrayList<>(world.units())) {
                if (mine.type() == null || !"unit-gold-mine".equals(mine.type().ident())) {
                    continue;
                }
                mines++;
                sitesTheMineAloneRefuses += clearanceHolds(world, hall, mine, path);
            }
        }
        assertTrue(mines > 100,
                "only " + mines + " gold mines found across the campaigns, so this sweep "
                        + "proves very little");
        // Counted, not merely checked. Every site above could have been
        // refused by the ground rather than by the rule, and a sweep that
        // never met one the rule alone decides would pass against a port
        // that does not read BuildingRules at all.
        assertTrue(sitesTheMineAloneRefuses > 100,
                "only " + sitesTheMineAloneRefuses + " sites across " + mines + " gold mines "
                        + "were refused by the mine's clearance and by nothing else, so this "
                        + "sweep does not show the distance rule is being read");
    }

    /**
     * Checks the ring around one mine, and answers how many of its sites the
     * clearance alone decided.
     */
    private static int clearanceHolds(World world, UnitType hall, Unit mine, String path) {
        int width = Math.max(1, hall.tileWidth());
        int height = Math.max(1, hall.tileHeight());
        int mineWidth = Math.max(1, mine.type().tileWidth());
        int mineHeight = Math.max(1, mine.type().tileHeight());
        List<int[]> candidates = new ArrayList<>();
        for (int dy = -6; dy <= 6; dy++) {
            for (int dx = -6; dx <= 6; dx++) {
                int x = mine.tileX() + dx;
                int y = mine.tileY() + dy;
                // Sites whose footprint lies clear of the mine's own, so the
                // occupancy check cannot be what refuses them.
                boolean apart = x + width <= mine.tileX()
                        || mine.tileX() + mineWidth <= x
                        || y + height <= mine.tileY()
                        || mine.tileY() + mineHeight <= y;
                if (!apart) {
                    continue;
                }
                if (Unit.distanceBetween(hall, x, y, mine.type(), mine.tileX(), mine.tileY()) > 3) {
                    continue;
                }
                candidates.add(new int[] {x, y});
            }
        }

        int decided = 0;
        for (int[] site : candidates) {
            if (world.canPlaceBuilding(hall, site[0], site[1])) {
                throw new AssertionError("a town hall was accepted at " + site[0] + "," + site[1]
                        + ", within three squares of the gold mine at " + mine.tileX() + ","
                        + mine.tileY() + " on " + path + " and clear of its footprint: the "
                        + "distance rule that keeps a hall three squares off a mine is not "
                        + "being read");
            }
        }
        // Now take the mine away. Any of those sites that becomes buildable was
        // being refused by the mine's clearance and by nothing else.
        world.remove(mine);
        for (int[] site : candidates) {
            if (world.canPlaceBuilding(hall, site[0], site[1])) {
                decided++;
            }
        }
        return decided;
    }
}
