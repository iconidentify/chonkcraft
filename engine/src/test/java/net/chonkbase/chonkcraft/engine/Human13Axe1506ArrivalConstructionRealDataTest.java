package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.missile.Missile;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Locks Human 13's exhausted one-step ranged arrival to authenticated BNE. */
class Human13Axe1506ArrivalConstructionRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("human 13 axe 1506 cold-constructs after its exhausted one-step arrival")
    void human13Axe1506ColdConstructsBeforeTheCycle180MeleeRolls() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No Warcraft II installation configured (-Dwc2.install.dir). ");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human/level13h", 0, 1);
        Assumptions.assumeTrue(mission != null, "Human 13 is not in the pack");
        World world = mission.world();

        Unit thrower = unitById(world, 94);
        Unit knight = unitById(world, 107);
        Unit grunt = unitById(world, 115);
        Unit ogre = unitById(world, 89);
        assertNotNull(thrower, "Human 13 has no native-slot-1506 axethrower");
        assertNotNull(knight, "Human 13 has no native-slot-1493 knight");
        assertNotNull(grunt, "Human 13 has no native-slot-1485 grunt");
        assertNotNull(ogre, "Human 13 has no native-slot-1511 ogre");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        int constructed = -1;
        int constructedDamage = -1;
        for (int fixture = 1; fixture <= 181; fixture++) {
            mission.tick();
            if (fixture >= 168 && fixture <= 170) {
                assertEquals(887, thrower.battleNetSequenceOffset(),
                        "the one-step, four-tail route opens cold Attack");
                assertEquals(171 - fixture,
                        thrower.battleNetAnimationTimer(),
                        "native exposes Attack construction 3,2,1");
            } else if (fixture == 179) {
                assertEquals(0xf50d7cb5, world.battleNetRandomSeed(),
                        "the early projectile must not steal fixture 180's "
                                + "idle and melee draws");
            } else if (fixture == 180) {
                assertEquals(62, knight.hitPoints(),
                        "native grunt 1485 rolls six damage on knight 1493");
                assertEquals(84, ogre.hitPoints(),
                        "native knight 1493 rolls six damage on ogre 1511");
            }
            for (Missile missile : world.missiles()) {
                if (missile.source() == thrower
                        && world.battleNetProjectileConstructed(missile)) {
                    int missileFixture = (int) world
                            .savedProjectileStartCycle(missile)
                            - BNE_INITIALIZATION_TICKS;
                    if (missileFixture > constructed) {
                        constructed = missileFixture;
                        constructedDamage = missile.damage();
                    }
                }
            }
        }

        assertEquals(181, constructed,
                "native constructs axe 1506's third projectile on fixture 181");
        assertEquals(5, constructedDamage,
                "fixture 181's native projectile damage roll is five");
    }

    private static Unit unitById(World world, int id) {
        return world.unitsSnapshot().stream()
                .filter(unit -> unit.id() == id)
                .findFirst().orElse(null);
    }
}
