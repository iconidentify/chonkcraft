package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Regression coverage for retail BNE's action-30 transport grid. */
class BattleNetTransportStartupTest {

    private static Player[] players(PudMap.PlayerType owner) {
        Player[] players = new Player[Player.MAX];
        for (int index = 0; index < players.length; index++) {
            players[index] = new Player(index,
                    index == 0 ? owner : PudMap.PlayerType.NOBODY,
                    PudMap.Race.HUMAN);
        }
        return players;
    }

    private static AnimationSet mover() {
        AnimationSet set = new AnimationSet("transport");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE,
                Animation.parse("move", List.of("frame 0", "move 32", "wait 1")));
        return set;
    }

    private static UnitType transport() {
        UnitType type = new UnitType("unit-human-transport");
        type.setTileSize(2, 2);
        type.setHitPoints(150);
        type.setSpeed(10);
        type.setSeaUnit(true);
        type.setMaxOnBoard(6);
        type.canTransport_().add("LandUnit");
        type.setAnimationSet(mover());
        return type;
    }

    private static UnitType hall() {
        UnitType type = new UnitType("unit-town-hall");
        type.setTileSize(4, 4);
        type.setHitPoints(1200);
        type.setBuilding(true);
        return type;
    }

    @Test
    @DisplayName("BNE transport order rewrite steps toward the last land before open water")
    void startupTransportUsesHallToShipShoreRewrite() {
        GameMap map = new GameMap(40, 50, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                // Open water on and south of y=38; land north of it. Mirrors
                // Orc 4's shoreline geometry for the 18,40 → 17,37 rewrite.
                map.field(x, y).setFlags(y >= 38
                        ? TileFlag.WATER_ALLOWED
                        : TileFlag.LAND_ALLOWED);
            }
        }

        World world = new World(map, players(PudMap.PlayerType.COMPUTER));
        world.enableAi(0);
        // Ship and hall placed like Orc 4: transport at (18,40), town hall
        // top-left (5,11). Native orderXY rewrites to (17,37).
        Unit ship = world.createUnit(transport(), 0, 18, 40);
        world.createUnit(hall(), 0, 5, 11);
        ship.setBattleNetReadySuppressed(true);
        ship.setBattleNetAnimationTimer(1);
        world.fireBattleNetReadyForAll();

        for (int i = 0; i < 8 && ship.tileX() == 18 && ship.tileY() == 40; i++) {
            world.tick();
        }

        assertEquals(17, ship.tileX(),
                "retail Orc 4's transport first-steps west one column");
        assertEquals(39, ship.tileY(),
                "the first step is north-west onto (17,39), not double-step (16,38)");
    }

    @Test
    @DisplayName("Chebyshev-4 shore approach double-steps even when hall BR is odd")
    void humanTwelveTransportDoubleStepsTowardOddHallShore() {
        // Human 12 transport 1522 at (68,34) aims at shore rewrite (72,29)
        // for fortress (85,14). Hall BR y is 17 (odd); gating double-step on
        // hall BR forced stride 1 onto (69,33) while native double-steps NE
        // to (70,32). Ship-on-even-lattice + |delta|!=1 + Chebyshev>=4.
        GameMap map = new GameMap(100, 50, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.WATER_ALLOWED);
            }
        }
        for (int y = 14; y < 18; y++) {
            for (int x = 85; x < 89; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }

        World world = new World(map, players(PudMap.PlayerType.COMPUTER));
        world.enableAi(0);
        Unit ship = world.createUnit(transport(), 0, 68, 34);
        UnitType fortress = new UnitType("unit-fortress");
        fortress.setTileSize(4, 4);
        fortress.setHitPoints(1600);
        fortress.setBuilding(true);
        world.createUnit(fortress, 0, 85, 14);
        ship.setBattleNetReadySuppressed(true);
        ship.setBattleNetAnimationTimer(1);
        world.fireBattleNetReadyForAll();

        for (int i = 0; i < 16
                && ship.tileX() == 68 && ship.tileY() == 34; i++) {
            world.tick();
        }

        assertEquals(70, ship.tileX(),
                "Human 12 transport double-steps east toward shore 72,29");
        assertEquals(32, ship.tileY(),
                "Human 12 transport double-steps north toward shore 72,29");
    }

    @Test
    @DisplayName("BNE does not issue the AI transport order to a person")
    void personOwnedTransportKeepsItsPlacedGuardOrder() {
        GameMap map = new GameMap(32, 32, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.WATER_ALLOWED);
            }
        }

        World world = new World(map, players(PudMap.PlayerType.PERSON));
        Unit ship = world.createUnit(transport(), 0, 4, 10);
        world.createUnit(hall(), 0, 21, 9);
        ship.setBattleNetAnimationTimer(1);
        world.fireBattleNetReadyForAll();

        for (int i = 0; i < 8; i++) {
            world.tick();
        }

        assertEquals(Unit.Order.STILL, ship.order());
        assertEquals(4, ship.tileX());
        assertEquals(10, ship.tileY());
    }
}
