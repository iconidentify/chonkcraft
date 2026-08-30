package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Human 13's eastern ogre resumes a blocked replacement chase once.
 *
 * <p>Native slot 1519 / Java unit 81 drains its old wise-man route on
 * fixture 148, selects knight 1493, and refuses the occupied south heading.
 * Retail pays Attack 643/3,2,1 once, exposes Move 586 for two visits, then
 * commits the open southeast detour on fixture 154. Re-arming the generic
 * blocked-chase constructor after that paid handoff inserted a second 3,2,1
 * pause and left a live attacker visibly frozen through fixture 155. That
 * queued-Attack promotion owns no active-order Still callback: charging one
 * shifted the shared asynchronous stream and made critter 1404 miss its
 * fixture-156 north-east wander.</p>
 */
class Human13Ogre1519RefusalHandoffRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("human 13's queued-Attack refusal handoff resumes without an idle draw")
    void easternOgrePaysOneBlockedRetargetConstructorAndResumesOn154() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No Warcraft II installation configured (-Dwc2.install.dir). ");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human/level13h", 0, 1);
        Assumptions.assumeTrue(mission != null, "Human 13 is not in the pack");
        World world = mission.world();

        Unit ogre = unitAt(world, "unit-ogre", 123, 19);
        Unit critter = unitAt(world, "unit-critter", 41, 113);
        assertNotNull(ogre, "Human 13 has no eastern ogre on 123,19");
        assertNotNull(critter, "Human 13 has no native-slot-1404 critter");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Unit knight = null;
        for (int fixture = 1; fixture <= 156; fixture++) {
            mission.tick();
            if (fixture == 148) {
                knight = ogre.target();
                assertNotNull(knight, "fixture 148 must select the southern knight");
                assertEquals("unit-knight", knight.type().ident());
                assertEquals(121, knight.tileX());
                assertEquals(30, knight.tileY());
                assertEquals(122, ogre.tileX());
                assertEquals(28, ogre.tileY());
            } else if (fixture == 149) {
                assertEquals(643, ogre.battleNetSequenceOffset());
                assertEquals(3, ogre.battleNetAnimationTimer());
            } else if (fixture == 150) {
                assertEquals(643, ogre.battleNetSequenceOffset());
                assertEquals(2, ogre.battleNetAnimationTimer());
            } else if (fixture == 151) {
                assertEquals(643, ogre.battleNetSequenceOffset());
                assertEquals(1, ogre.battleNetAnimationTimer());
            } else if (fixture == 152 || fixture == 153) {
                assertEquals(586, ogre.battleNetSequenceOffset(),
                        "the paid constructor must hand ownership back to Move");
                assertEquals(122, ogre.tileX(),
                        "the empty cursor remains parked before its redraw");
                assertEquals(28, ogre.tileY(),
                        "the empty cursor remains parked before its redraw");
            } else if (fixture == 156) {
                assertEquals(Unit.Order.MOVE, critter.order(),
                        "the queued-Attack handoff must not steal the "
                                + "critter's native wander draw");
                assertEquals(43, critter.orderTargetX());
                assertEquals(112, critter.orderTargetY());
            }
        }

        assertSame(knight, ogre.target(),
                "the resumed detour must still chase the selected knight");
        assertEquals(123, ogre.tileX(),
                "native commits the southeast detour on fixture 154");
        assertEquals(29, ogre.tileY(),
                "native commits the southeast detour on fixture 154");
        assertEquals(1, ogre.pathLength(),
                "the south-west tail remains after the southeast step");
    }

    @Test
    @DisplayName("human 13's expired melee OP0 retargets without visiting Still")
    void easternOgreRetargetsDirectlyFromItsExpiredBodyHoldOn192() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No Warcraft II installation configured (-Dwc2.install.dir). ");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human/level13h", 0, 1);
        Assumptions.assumeTrue(mission != null, "Human 13 is not in the pack");
        World world = mission.world();

        Unit ogre = unitAt(world, "unit-ogre", 123, 19);
        assertNotNull(ogre, "Human 13 has no eastern ogre on 123,19");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        for (int fixture = 1; fixture <= 195; fixture++) {
            mission.tick();
            if (fixture == 191) {
                assertEquals(Unit.Order.ATTACK, ogre.order());
                assertEquals(124, ogre.target().tileX(),
                        "the dying east knight owns the last body-hold visit");
                assertEquals(30, ogre.target().tileY());
                assertEquals(643, ogre.battleNetSequenceOffset());
                assertEquals(1, ogre.battleNetAnimationTimer());
            } else if (fixture == 192) {
                assertEquals(Unit.Order.ATTACK, ogre.order(),
                        "native keeps action 12 across the OP0 replacement");
                assertNotNull(ogre.target(),
                        "the OP0 scan must install the western knight");
                assertEquals(121, ogre.target().tileX());
                assertEquals(30, ogre.target().tileY());
                assertEquals(643, ogre.battleNetSequenceOffset());
                assertEquals(3, ogre.battleNetAnimationTimer(),
                        "the replacement opens fresh construction on 192");
            } else if (fixture == 193) {
                assertEquals(2, ogre.battleNetAnimationTimer());
            } else if (fixture == 194) {
                assertEquals(1, ogre.battleNetAnimationTimer());
            }
        }

        assertEquals(Unit.Order.ATTACK, ogre.order());
        assertEquals(122, ogre.tileX(),
                "native dest-arms southwest on fixture 195");
        assertEquals(30, ogre.tileY(),
                "native dest-arms southwest on fixture 195");
        assertEquals(121, ogre.target().tileX());
        assertEquals(30, ogre.target().tileY());
    }

    @Test
    @DisplayName("human 13's clean near-target route pays the complete refusal band")
    void southernOgreRetainsItsDirectTargetSkirtRayThroughRefusal() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No Warcraft II installation configured (-Dwc2.install.dir). ");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human/level13h", 0, 1);
        Assumptions.assumeTrue(mission != null, "Human 13 is not in the pack");
        World world = mission.world();

        Unit ogre = unitAt(world, "unit-ogre", 126, 34);
        assertNotNull(ogre, "Human 13 has no southern ogre on 126,34");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Unit knight = null;
        for (int fixture = 1; fixture <= 201; fixture++) {
            mission.tick();
            if (fixture == 197) {
                knight = ogre.target();
                assertNotNull(knight,
                        "the expired east hold must select the western knight");
                assertEquals("unit-knight", knight.type().ident());
                assertEquals(121, knight.tileX());
                assertEquals(30, knight.tileY());
                assertEquals(643, ogre.battleNetSequenceOffset());
                assertEquals(3, ogre.battleNetAnimationTimer());
            } else if (fixture == 200) {
                assertSame(knight, ogre.target());
                assertEquals(123, ogre.tileX());
                assertEquals(31, ogre.tileY());
                assertEquals(2, ogre.pathLength(),
                        "the direct north-west,west skirt ray stays cached");
                assertEquals(Direction.fromDelta(-1, -1),
                        ogre.peekHeading());
                assertEquals(Direction.fromDelta(-1, 0),
                        ogre.peekHeadingAtDepth(1));
                assertEquals(1, ogre.battleNetCollisionCounter());
                assertEquals(586, ogre.battleNetSequenceOffset());
                assertEquals(15, ogre.battleNetAnimationTimer(),
                        "the occupied first byte enters the full Move band");
                assertEquals(14, ogre.battleNetOrderDelay());
            } else if (fixture == 201) {
                assertEquals(123, ogre.tileX());
                assertEquals(31, ogre.tileY());
                assertEquals(2, ogre.pathLength(),
                        "the quiet band must not cold-replan the native ray");
                assertEquals(14, ogre.battleNetAnimationTimer());
            }
        }
    }

    @Test
    @DisplayName("human 13 plain-Move wakes retain the native line until a stored byte refuses")
    void plainMoveWakesStoreTheWholeTerrainLineBeforeRefusingMobileBodies() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No Warcraft II installation configured (-Dwc2.install.dir). ");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human/level13h", 0, 1);
        Assumptions.assumeTrue(mission != null, "Human 13 is not in the pack");
        World world = mission.world();

        Unit eastern = unitAt(world, "unit-ogre", 123, 19);
        Unit southern = unitAt(world, "unit-ogre", 120, 22);
        Unit heldOut = unitAt(world, "unit-ogre", 116, 25);
        assertNotNull(eastern, "Human 13 has no native-slot-1519 ogre");
        assertNotNull(southern, "Human 13 has no native-slot-1510 ogre");
        assertNotNull(heldOut, "Human 13 has no native-slot-1501 ogre");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        for (int fixture = 1; fixture <= 278; fixture++) {
            mission.tick();
            if (fixture == 252) {
                assertEquals(123, heldOut.tileX());
                assertEquals(29, heldOut.tileY());
                assertEquals(2, heldOut.pathLength(),
                        "the occupied first line square keeps the native detour");
                assertEquals(Direction.fromDelta(-1, -1),
                        heldOut.peekHeading());
                assertEquals(Direction.fromDelta(-1, 0),
                        heldOut.peekHeadingAtDepth(1));
            } else if (fixture == 253) {
                assertEquals(122, eastern.tileX());
                assertEquals(29, eastern.tileY());
                assertEquals(10, eastern.pathLength(),
                        "eleven north bytes leave ten after the first step");
                assertEquals(Direction.fromDelta(0, -1),
                        eastern.peekHeading());
            } else if (fixture == 255) {
                assertEquals(123, southern.tileX());
                assertEquals(30, southern.tileY());
                assertEquals(8, southern.pathLength(),
                        "the nine-byte line leaves eight after north-west");
                assertEquals(Direction.fromDelta(0, -1),
                        southern.peekHeading());
            } else if (fixture == 265) {
                assertEquals(122, eastern.tileX(),
                        "the occupied second north byte refuses for one visit");
                assertEquals(29, eastern.tileY());
            } else if (fixture == 266) {
                assertEquals(121, eastern.tileX());
                assertEquals(28, eastern.tileY(),
                        "the next visit replans and commits north-west");
                assertEquals(1, eastern.pathLength(),
                        "the native replacement leaves only north-east");
                assertEquals(Direction.fromDelta(1, -1),
                        eastern.peekHeading());
            } else if (fixture == 267) {
                assertEquals(123, southern.tileX(),
                        "the southern witness also refuses its second byte");
                assertEquals(30, southern.tileY());
            } else if (fixture == 268) {
                assertEquals(122, southern.tileX());
                assertEquals(29, southern.tileY(),
                        "the southern witness replans north-west on 268");
                assertEquals(3, southern.pathLength(),
                        "the replacement retains north-east,north-west,west");
                assertEquals(Direction.fromDelta(1, -1),
                        southern.peekHeading());
                assertEquals(Direction.fromDelta(-1, -1),
                        southern.peekHeadingAtDepth(1));
                assertEquals(Direction.fromDelta(-1, 0),
                        southern.peekHeadingAtDepth(2));
            } else if (fixture == 278) {
                assertEquals(122, eastern.tileX(),
                        "the cached north-east replacement commits on 278");
                assertEquals(27, eastern.tileY(),
                        "the replacement must not wait behind the settled ally");
            }
        }
    }

    private static Unit unitAt(World world, String ident, int x, int y) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.isAlive() && unit.isOnMap() && unit.type() != null
                    && ident.equals(unit.type().ident())
                    && unit.tileX() == x && unit.tileY() == y) {
                return unit;
            }
        }
        return null;
    }
}
