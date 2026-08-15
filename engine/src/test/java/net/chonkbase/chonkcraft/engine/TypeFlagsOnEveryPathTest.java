package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Flags the shipped data sets that one code path honoured and the next did not.
 *
 * <p>All four here have the same shape and it is the shape this project keeps
 * finding: a field parsed correctly out of the real scripts, given an accessor,
 * read by the one method whose author happened to think of it, and ignored by
 * the sibling method that asks the same question a moment later.
 * {@code canAttack} was tested by four order paths and not by attack-ground.
 * {@code indestructible} was read when a blow landed and not when a target was
 * chosen. {@code vanishes} was read for occupancy and targeting and not for the
 * roster count that decides most of the campaign. {@code nonSolid} was read for
 * occupancy and not for placement. And the {@code Type} key that decides
 * whether a thing moves on land, in the air or at sea was parsed and read by
 * nothing at all.
 *
 * <p>Three of the four have no consequence a player could see on the shipped
 * data today, and saying so is the point of writing it down. They are pinned
 * because the masking is luck rather than design: every one of the 29 shipped
 * indestructible types happens to declare {@code HitPoints = 0} as well, so
 * {@code isAlive()} was already refusing them, and the vanishing types are all
 * owned by the neutral slot and spend their whole lives holding the dying
 * order. Take away any one of those accidents -- a map that hands a marker to a
 * player, a mod that gives an indestructible thing hit points -- and the flag
 * has to be read on both paths or the game is wrong.
 *
 * <p>Each test drives the entry point rather than the accessor. Asking whether
 * {@code type.vanishes()} was parsed would have passed against every one of
 * these from the first day.
 */
class TypeFlagsOnEveryPathTest {

    private static GameData load() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No Warcraft II retail assets configured.");
        return new GameData(assets);
    }

    /** Dry land, two people, and the whole shipped roster loaded. */
    private static World plain(GameData data) {
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
        world.setUpgrades(data.upgrades().upgrades());
        world.setMissileTypes(data.missiles().types());
        world.setSpells(data.spells().spells());
        return world;
    }

    @Test
    @DisplayName("a hall cannot be told to open fire on a piece of ground")
    void aBuildingDoesNotTakeAnAttackGroundOrder() {
        GameData data = load();
        World world = plain(data);

        int checked = 0;
        int accepted = 0;
        StringBuilder got = new StringBuilder();
        for (UnitType type : data.unitTypes().types().values()) {
            if (!type.building() || type.hitPoints() <= 0) {
                continue;
            }
            Unit unit = world.createUnit(type, 0, 5, 5);
            if (unit == null) {
                continue;
            }
            unit.setHitPoints(type.hitPoints());
            checked++;
            if (world.orderAttackGround(unit, 12, 12)) {
                accepted++;
                got.append(' ').append(type.ident());
            }
            world.remove(unit);
        }
        assertTrue(checked > 5, "only " + checked + " buildings were tried, so "
                + "this sweep proves very little");
        assertEquals(0, accepted,
                accepted + " buildings were put into ATTACK_GROUND:" + got);
    }

    @Test
    @DisplayName("a peon takes the attack-ground order the injector installed")
    void aPeonTakesTheAttackGroundOrderTheInjectorInstalled() {
        GameData data = load();
        World world = plain(data);
        UnitType peon = data.unitTypes().types().get("unit-peon");
        assertNotNull(peon, "no peon in the roster");

        Unit unit = world.createUnit(peon, 0, 5, 5);
        assertNotNull(unit, "the peon could not be placed");
        unit.setHitPoints(peon.hitPoints());
        // Commanded fixture attack-ground-1/02: native GiveOrder 17 on
        // Orc 1 peon 1594 at 30,18 is accepted. The button stays hidden.
        assertTrue(world.orderAttackGround(unit, 12, 12),
                "the peon was refused attack-ground that native GiveOrder 17 installed");
        assertSame(Unit.Order.ATTACK_GROUND, unit.order(),
                "the peon accepted the order and is not in it");
    }

    @Test
    @DisplayName("something that can attack is still allowed to bombard a square")
    void aCatapultStillTakesAnAttackGroundOrder() {
        GameData data = load();
        World world = plain(data);
        UnitType catapult = data.unitTypes().types().get("unit-catapult");
        assertNotNull(catapult, "no catapult in the roster");
        assertTrue(catapult.canAttack(), "the fixture needs a type that can attack");

        Unit unit = world.createUnit(catapult, 0, 5, 5);
        assertNotNull(unit, "the catapult could not be placed");
        unit.setHitPoints(catapult.hitPoints());
        assertTrue(world.orderAttackGround(unit, 12, 12),
                "a catapult was refused an attack-ground order, so the CanAttack test above "
                        + "is refusing everything and proves nothing");
        assertSame(Unit.Order.ATTACK_GROUND, unit.order(),
                "the catapult accepted the order and is not in it");
    }

    /**
     * A wall of a target, in two versions that differ only in the flag.
     *
     * <p>Hand-built, and it has to be. Every one of the 29 shipped types that
     * declares {@code Indestructible} declares {@code HitPoints = 0} beside it,
     * and {@link Unit#setHitPoints} clamps to the type's maximum, so no unit of
     * a shipped indestructible type can be alive in this implementation at all. That is
     * exactly the accident that hid the missing check, and a fixture that
     * inherited it could not tell the two rules apart.
     */
    private static UnitType obstacle(String ident, boolean indestructible) {
        UnitType type = new UnitType(ident);
        type.setTileSize(2, 2);
        type.setHitPoints(400);
        type.setBuilding(true);
        type.setLandUnit(true);
        type.setUnitTypeClass("land");
        type.setPriority(20);
        type.setIndestructible(indestructible);
        return type;
    }

    @Test
    @DisplayName("an archer picks a fight with something it can kill, not with something it cannot")
    void nothingAimsAtAnIndestructibleTarget() {
        GameData data = load();
        UnitType archerType = data.unitTypes().types().get("unit-archer");
        assertNotNull(archerType, "no archer in the roster");
        assertTrue(archerType.canTargetLand(), "the archer must be able to shoot at buildings");

        // The accident that hid this, stated as a number rather than a
        // recollection. Every shipped indestructible type is masked twice
        // over: either it has no hit points, so this implementation's isAlive() already
        // refuses it, or it is one of the dead-vision revealers, which the
        // target search skips for a different reason entirely. Neither is the
        // flag being read, and either could stop being true.
        int indestructibleTypes = 0;
        int masked = 0;
        for (UnitType type : data.unitTypes().types().values()) {
            if (!type.indestructible()) {
                continue;
            }
            indestructibleTypes++;
            if (type.hitPoints() == 0 || type.revealer()) {
                masked++;
            }
        }
        assertTrue(indestructibleTypes > 20,
                "only " + indestructibleTypes + " indestructible types found, so this proves "
                        + "very little");
        assertEquals(indestructibleTypes, masked,
                (indestructibleTypes - masked) + " shipped indestructible types are neither "
                        + "hit-pointless nor revealers, so the hand-built fixture below is no "
                        + "longer the only way to reach this and the real one should be used");

        World world = plain(data);
        world.fog().revealAll(0);
        Unit archer = world.createUnit(archerType, 0, 10, 10);
        archer.setHitPoints(archerType.hitPoints());
        Unit warded = world.createUnit(obstacle("fixture-warded-obelisk", true), 1, 12, 10);
        warded.setHitPoints(400);
        assertTrue(warded.isAlive(), "the fixture's target must be alive or it proves nothing");
        assertTrue(world.isEnemyPlayer(0, 1), "the two slots must be at war");

        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 4; cycle++) {
            world.tick();
        }
        assertNull(archer.target(),
                "an archer aimed at something nothing can hurt: ComputeCost scores an "
                        + "indestructible target at INT_MAX (unit_find.cpp:718) and "
                        + "BestRangeTargetFinder locks it out of the splash search (:840), so "
                        + "it is never the winner -- an army that picks one walks to it and "
                        + "swings at it for the rest of the game for no damage");

        // The control, to prove the measurement distinguishes the two rather
        // than passing on anything: the same fixture without the flag. This is
        // the old behaviour and it must fail the property the new one passes.
        World control = plain(data);
        control.fog().revealAll(0);
        Unit shooter = control.createUnit(archerType, 0, 10, 10);
        shooter.setHitPoints(archerType.hitPoints());
        Unit plain = control.createUnit(obstacle("fixture-plain-obelisk", false), 1, 12, 10);
        plain.setHitPoints(400);
        Unit picked = null;
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 4 && picked == null; cycle++) {
            control.tick();
            picked = shooter.target();
        }
        assertSame(plain, picked,
                "the archer did not aim at the identical target without the flag either, so "
                        + "the test above passes for the wrong reason");
    }

    @Test
    @DisplayName("a corpse on the field is not a unit the player still owns")
    void aVanishingTypeIsNotCountedInThePlayersRoster() {
        GameData data = load();
        World world = plain(data);
        UnitType footman = data.unitTypes().types().get("unit-footman");
        UnitType body = data.unitTypes().types().get("unit-human-dead-body");
        assertNotNull(footman, "no footman in the roster");
        assertNotNull(body, "no human dead body in the roster");
        assertTrue(body.vanishes(),
                "unit-human-dead-body does not declare Vanishes, so this proves nothing");
        assertFalse(body.revealer(),
                "the fixture must use a vanishing type that is not also a revealer, or the "
                        + "revealer test already covers it and this proves nothing");

        Unit alive = world.createUnit(footman, 0, 10, 10);
        alive.setHitPoints(footman.hitPoints());
        Unit corpse = world.createUnit(body, 0, 20, 20);
        corpse.setHitPoints(Math.max(1, body.hitPoints()));
        assertTrue(corpse.isAlive(),
                "the fixture's corpse must be alive by this port's reckoning or the count "
                        + "already skips it and this proves nothing");

        assertEquals(1, world.unitTypesCount(0, null),
                "the player's total unit count includes a corpse: CUnit::AssignToPlayer "
                        + "skips a vanishing type when it adds to UnitTypesCount and TotalUnits "
                        + "(unit.cpp:684), and this count is what GetPlayerData(player, "
                        + "\"TotalNumUnits\") answers -- the defeat condition of most of the "
                        + "campaign");
        assertEquals(0, world.unitTypesCount(0, body.ident()),
                "asking for the corpse type by name still counted it");
        assertEquals(1, world.unitTypesCount(0, footman.ident()),
                "the footman stopped being counted, so the count now proves nothing");
    }

    @Test
    @DisplayName("a death marker fits wherever anything can die, including at sea")
    void aNonSolidTypeIsStoppedByNothing() {
        GameData data = load();
        long everywhere = TileFlag.LAND_ALLOWED | TileFlag.WATER_ALLOWED | TileFlag.COAST_ALLOWED;

        int checked = 0;
        for (UnitType type : data.unitTypes().types().values()) {
            if (!type.nonSolid() || type.building()) {
                continue;
            }
            checked++;
            assertEquals(everywhere, Unit.movementMaskFor(type),
                    type.ident() + " will only be placed on some kinds of ground: "
                            + "UpdateUnitStats gives a non-building NonSolid type "
                            + "MovementMask = 0 (unittype.cpp:592) and UnitTypeCanBeAt skips "
                            + "the mask test for one outright (map/map.cpp:262), so nothing "
                            + "stops it -- and these are spawned on the square a unit has just "
                            + "died on, which for a sunk destroyer is open water");
        }
        assertTrue(checked > 20, "only " + checked + " non-solid types were found, so this "
                + "sweep proves very little: the shipped family is the twenty-two "
                + "unit-dead-vision-* markers");

        // The control: a solid land type must not accept water, or the
        // measurement above is passing on anything at all.
        UnitType footman = data.unitTypes().types().get("unit-footman");
        assertNotNull(footman, "no footman in the roster");
        assertEquals(TileFlag.LAND_ALLOWED, Unit.movementMaskFor(footman),
                "a footman accepts something other than land, so the sweep above cannot "
                        + "tell the two rules apart");
    }

    @Test
    @DisplayName("an oil platform is a building that stands in the sea, not on the beach")
    void theTypeKeyDecidesWhatGroundABuildingStandsOn() {
        GameData data = load();
        int naval = 0;
        for (UnitType type : data.unitTypes().types().values()) {
            // Shore buildings and non-solid markers are the two carve-outs
            // upstream applies around the movement kind: the first has its mask
            // rebuilt after the switch and the second never
            // reaches it (:572).
            if (!"naval".equals(type.unitTypeClass()) || type.shoreBuilding()
                    || (type.nonSolid() && !type.building())) {
                continue;
            }
            naval++;
            assertFalse((Unit.movementMaskFor(type) & TileFlag.LAND_ALLOWED) != 0,
                    type.ident() + " declares Type = \"naval\" and will be placed on dry land: "
                            + "UpdateUnitStats switches on MoveType alone to build the movement "
                            + "mask (unittype.cpp:597), and the separate LandUnit, SeaUnit and "
                            + "AirUnit keys are boolean flags for targeting and transport "
                            + "(script_unittype.cpp:379-381)");
        }
        assertTrue(naval > 10, "only " + naval + " naval types were found, so this proves "
                + "very little");

        // The four that made this worth doing: buildings and wreckage that
        // declare Type = "naval" and no SeaUnit at all, so the flags alone put
        // every one of them on land.
        for (String ident : new String[] {"unit-oil-patch", "unit-human-oil-platform",
                "unit-orc-oil-platform", "unit-destroyed-3x3-place-water"}) {
            UnitType type = data.unitTypes().types().get(ident);
            assertNotNull(type, "no " + ident + " in the roster");
            assertFalse(type.seaUnit(),
                    ident + " declares SeaUnit, so it is not one of the types this is about");
            assertSame(UnitType.Movement.NAVAL, type.moveType(),
                    ident + " is not treated as naval, though its Type key says it is");
        }
    }
}
