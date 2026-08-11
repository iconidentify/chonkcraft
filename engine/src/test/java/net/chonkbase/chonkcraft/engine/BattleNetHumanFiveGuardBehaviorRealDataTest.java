package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Human 5's BNE-authored base guards stay posted while its field squad fights.
 *
 * <p>This is the systemic answer to the play report that trolls fired while
 * nearby grunts appeared idle. The authenticated retail Human 5 trace on
 * The authenticated retail capture keeps axethrowers at (107,37)/(120,41) and grunts at
 * (109,35)/(115,27) under STILL for all 1,800 fixtures. A separate mixed
 * squad around (49,90) enters combat. Ranged stand-ground can visibly shoot;
 * a melee guard with the same authored posture does not walk out of formation.
 * Waking every melee unit would therefore be a regression from BNE, not an AI
 * improvement.
 */
class BattleNetHumanFiveGuardBehaviorRealDataTest {

    private record Guard(String ident, int x, int y) {}

    private static GameData data() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        return new GameData(assets);
    }

    @Test
    @DisplayName("Human 5 keeps its four BNE base guards posted while the field squad engages")
    void humanFivePreservesPostedGuardsAndActivatesItsFieldSquad() {
        GameData data = data();
        Mission mission = data.loadMission("campaigns/human/level05h");
        assertNotNull(mission, "Human 5 is present in the BNE pack");
        World world = mission.world();

        List<Guard> posts = List.of(
                new Guard("unit-grunt", 115, 27),
                new Guard("unit-grunt", 109, 35),
                new Guard("unit-axethrower", 107, 37),
                new Guard("unit-axethrower", 120, 41));
        List<Unit> posted = posts.stream().map(post -> unitAt(world, 5, post)).toList();

        List<Unit> fieldSquad = world.unitsSnapshot().stream()
                .filter(unit -> unit.player() == 5 && unit.type() != null
                        && ("unit-grunt".equals(unit.type().ident())
                                || "unit-axethrower".equals(unit.type().ident()))
                        && unit.tileX() >= 46 && unit.tileX() <= 50
                        && unit.tileY() >= 88 && unit.tileY() <= 92)
                .toList();
        assertEquals(5, fieldSquad.size(), "the authenticated mixed squad changed shape");
        var starts = fieldSquad.stream()
                .collect(java.util.stream.Collectors.toMap(
                        Unit::id, unit -> new int[] {unit.tileX(), unit.tileY()}));

        for (int cycle = 0; cycle < 1_800; cycle++) {
            mission.tick();
        }

        for (int i = 0; i < posted.size(); i++) {
            Unit unit = posted.get(i);
            Guard post = posts.get(i);
            assertTrue(unit.isAlive() && unit.isOnMap(), post + " left the map");
            assertEquals(post.x(), unit.tileX(), post + " abandoned its BNE post on X");
            assertEquals(post.y(), unit.tileY(), post + " abandoned its BNE post on Y");
            assertEquals(Unit.Order.STILL, unit.order(), post + " was globally awakened");
        }

        long active = fieldSquad.stream().filter(unit -> {
            int[] start = starts.get(unit.id());
            return unit.order() != Unit.Order.STILL
                    || unit.tileX() != start[0] || unit.tileY() != start[1]
                    || !unit.isAlive();
        }).count();
        assertEquals(5, active,
                "the mobile grunt/axethrower squad did not engage as BNE does");
        assertTrue(fieldSquad.stream().anyMatch(unit -> "unit-grunt".equals(unit.type().ident())
                        && unit.order() == Unit.Order.ATTACK),
                "no grunt joined the field engagement");
        assertTrue(fieldSquad.stream().anyMatch(unit -> "unit-axethrower".equals(unit.type().ident())
                        && unit.order() == Unit.Order.ATTACK),
                "no axethrower joined the field engagement");
    }

    private static Unit unitAt(World world, int player, Guard guard) {
        Unit unit = world.unitsSnapshot().stream()
                .filter(candidate -> candidate.player() == player && candidate.type() != null
                        && guard.ident().equals(candidate.type().ident())
                        && candidate.tileX() == guard.x() && candidate.tileY() == guard.y())
                .findFirst().orElse(null);
        assertNotNull(unit, "missing BNE guard " + guard);
        return unit;
    }
}
