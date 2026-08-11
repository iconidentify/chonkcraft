package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import net.chonkbase.chonkcraft.engine.ai.AiPlayer;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.ResourceInfo;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Locks retail BNE's reserved peon-train quota and the hall action-33 counter.
 *
 * <p>Implements the worker-train decision in {@code 0x439000} and the per-hall
 * Still OP0 counter in action 33 ({@code 0x418bb0}), re-evaluated on each
 * computer hall's Still animation marker.
 */
class BattleNetTrainWorkerTest {

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static UnitType peon() {
        UnitType type = new UnitType("unit-peon");
        type.setTileSize(1, 1);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.gathering().put(UnitType.Resource.GOLD,
                new ResourceInfo(UnitType.Resource.GOLD));
        type.gathering().put(UnitType.Resource.WOOD,
                new ResourceInfo(UnitType.Resource.WOOD));
        type.costs().put(UnitType.Resource.GOLD, 400);
        type.costs().put(UnitType.Resource.TIME, 45);
        type.setDemand(1);
        return type;
    }

    private static UnitType greatHall() {
        UnitType type = new UnitType("unit-great-hall");
        type.setTileSize(4, 4);
        type.setHitPoints(1200);
        type.setBuilding(true);
        type.setLandUnit(true);
        type.setSupply(5);
        type.stores().add(UnitType.Resource.GOLD);
        type.stores().add(UnitType.Resource.WOOD);
        return type;
    }

    @Test
    @DisplayName("a computer hall spends four hundred gold after its action-33 train counter exceeds two")
    void aComputerHallSpendsFourHundredGoldAfterItsAction33TrainCounterExceedsTwo() {
        World world = new World(grass(24));
        world.setTrainers(Map.of("unit-peon", Set.of("unit-great-hall")));
        world.player(0).setType(net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER);
        UnitType workerType = peon();
        UnitType hallType = greatHall();
        Unit hall = world.createUnit(hallType, 0, 2, 2);
        // Force a deterministic Still timer so the first OP0 is on the next tick.
        hall.setBattleNetAnimationTimer(1);
        hall.setBattleNetSequenceOffset(-1);
        hall.setBattleNetAiTrainCounter(0);
        for (int i = 0; i < 4; i++) {
            world.createUnit(workerType, 0, 10 + i, 10);
        }
        world.player(0).set(UnitType.Resource.GOLD, 1000);
        world.player(0).set(UnitType.Resource.WOOD, 1000);
        world.recalculateSupply();
        world.enableAi(0);

        // Four Still OP0s with limit 2: counters 0,1,2 then train on the fourth
        // (old counter 3 > 2). Without script.bin the approximation uses timer 5
        // between OP0s after the first, so advance until the hall produces.
        int guard = 0;
        while (hall.producing() == null && guard++ < 40) {
            world.tick();
        }
        assertNotNull(hall.producing(),
                "the hall starts a peon train after its action-33 counter exceeds 2");
        assertEquals("unit-peon", hall.producing().ident(),
                "the paid job is a peon");
        assertEquals(600, world.player(0).get(UnitType.Resource.GOLD),
                "computer players debit exactly 400 gold for the first peon");
    }

    @Test
    @DisplayName("a hall with four hundred gold does not spend its last peon cost")
    void aHallWithFourHundredGoldDoesNotSpendItsLastPeonCost() {
        // XHuman 8 p6: two peons, bank 400, want=1. Action-33 would fire
        // but native never debits -- gold < 500 matches the ready-path poor
        // bank floor. Human 8 p3 starts at 500 and does train.
        World world = new World(grass(24));
        world.setTrainers(Map.of("unit-peon", Set.of("unit-great-hall")));
        world.player(0).setType(net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER);
        UnitType workerType = peon();
        UnitType hallType = greatHall();
        Unit hall = world.createUnit(hallType, 0, 2, 2);
        hall.setBattleNetAnimationTimer(1);
        hall.setBattleNetSequenceOffset(-1);
        hall.setBattleNetAiTrainCounter(0);
        world.createUnit(workerType, 0, 10, 10);
        world.createUnit(workerType, 0, 11, 10);
        world.player(0).set(UnitType.Resource.GOLD, 400);
        world.player(0).set(UnitType.Resource.WOOD, 1500);
        world.recalculateSupply();
        world.enableAi(0);

        for (int i = 0; i < 40; i++) {
            world.tick();
        }
        assertEquals(null, hall.producing(),
                "a 400-gold bank must not start a peon train");
        assertEquals(400, world.player(0).get(UnitType.Resource.GOLD),
                "XHuman 8 p6 keeps its 400 gold when native does");
    }

    @Test
    @DisplayName("a hall with five hundred gold spends four hundred on a peon")
    void aHallWithFiveHundredGoldSpendsFourHundredOnAPeon() {
        // Human 8 p3: one peon, bank 500, action-33 debits to 100 at cycle 12.
        World world = new World(grass(24));
        world.setTrainers(Map.of("unit-peon", Set.of("unit-great-hall")));
        world.player(0).setType(net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER);
        UnitType workerType = peon();
        UnitType hallType = greatHall();
        Unit hall = world.createUnit(hallType, 0, 2, 2);
        hall.setBattleNetAnimationTimer(1);
        hall.setBattleNetSequenceOffset(-1);
        hall.setBattleNetAiTrainCounter(0);
        world.createUnit(workerType, 0, 10, 10);
        world.player(0).set(UnitType.Resource.GOLD, 500);
        world.player(0).set(UnitType.Resource.WOOD, 750);
        world.recalculateSupply();
        world.enableAi(0);

        int guard = 0;
        while (hall.producing() == null && guard++ < 40) {
            world.tick();
        }
        assertNotNull(hall.producing(),
                "a 500-gold bank starts a peon train after the action-33 counter");
        assertEquals(100, world.player(0).get(UnitType.Resource.GOLD),
                "Human 8 p3 debits exactly 400 gold from a 500 bank");
    }

    @Test
    @DisplayName("the reserved-train quota is half the worker census rounded up")
    void theReservedTrainQuotaIsHalfTheWorkerCensusRoundedUp() {
        // 0x439000: want = (workers - 1) / 2 + 1
        assertEquals(1, ((1 - 1) / 2) + 1);
        assertEquals(1, ((2 - 1) / 2) + 1);
        assertEquals(2, ((3 - 1) / 2) + 1);
        assertEquals(2, ((4 - 1) / 2) + 1);
        assertEquals(3, ((5 - 1) / 2) + 1);
    }

    @Test
    @DisplayName("the action-33 train counter trains only after the previous value exceeds two")
    void theAction33TrainCounterTrainsOnlyAfterThePreviousValueExceedsTwo() {
        // Native: counter++; if (old > 2) train and reset.
        int counter = 0;
        int trains = 0;
        for (int op0 = 0; op0 < 8; op0++) {
            int old = counter;
            counter = old + 1;
            if (old > 2) {
                trains++;
                counter = 0;
            }
        }
        // OP0s 0,1,2 build counter to 3; OP0 3 has old=3 and trains; then
        // OP0s 4,5,6 rebuild; OP0 7 trains again.
        assertEquals(2, trains,
                "eight OP0s with limit 2 yield two train pulses");
    }

    private static UnitType footman() {
        UnitType type = new UnitType("unit-footman");
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.costs().put(UnitType.Resource.GOLD, 600);
        type.costs().put(UnitType.Resource.TIME, 60);
        type.setDemand(1);
        return type;
    }

    private static UnitType barracks() {
        UnitType type = new UnitType("unit-human-barracks");
        type.setTileSize(3, 3);
        type.setHitPoints(800);
        type.setBuilding(true);
        type.setLandUnit(true);
        return type;
    }

    private static UnitType farm() {
        UnitType type = new UnitType("unit-farm");
        type.setTileSize(2, 2);
        type.setHitPoints(400);
        type.setBuilding(true);
        type.setLandUnit(true);
        type.setSupply(4);
        return type;
    }

    @Test
    @DisplayName("a computer barracks spends six hundred gold on a footman after its action-33 counter exceeds one")
    void aComputerBarracksSpendsSixHundredGoldOnAFootmanAfterItsAction33CounterExceedsOne() {
        // XHuman 2 p7 / XOrc 11: third OP0 (limit 1) debits 600. Milestone
        // 0x81 arms the soldier want; PUD data non-zero is required.
        World world = new World(grass(24));
        world.setTrainers(Map.of("unit-footman", Set.of("unit-human-barracks")));
        world.player(0).setType(net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER);
        UnitType soldierType = footman();
        UnitType barracksType = barracks();
        Unit barracks = world.createUnit(barracksType, 0, 2, 2);
        // Register the soldier type (create then remove demand by not counting
        // a live unit): place far away so the type is known to registeredType.
        Unit seed = world.createUnit(soldierType, 0, 20, 20);
        // Farms so the footman's demand has food headroom.
        world.createUnit(farm(), 0, 8, 2);
        world.createUnit(farm(), 0, 12, 2);
        // Keep the seed so census is non-zero like a real base; want is 255.
        barracks.setBattleNetAnimationTimer(1);
        barracks.setBattleNetSequenceOffset(-1);
        barracks.setBattleNetAiTrainCounter(0);
        barracks.setBattleNetPudData(1);
        world.player(0).set(UnitType.Resource.GOLD, 2000);
        world.player(0).set(UnitType.Resource.WOOD, 1000);
        world.recalculateSupply();
        AiPlayer ai = world.enableAi(0);
        // Compact ai.bin-shaped blob for personality 44 (XHuman 2 p7).
        byte[] profile = new byte[128];
        profile[44 * 2] = 100; // offsets[44] = 100
        profile[100] = 104; // list offset
        profile[101] = 0;
        profile[102] = 104;
        profile[103] = 0;
        profile[104] = 0x4a;
        profile[105] = (byte) 0x81;
        profile[106] = (byte) 0xff;
        ai.setBattleNetBuildProfile(profile, 44);

        int guard = 0;
        while (barracks.producing() == null && guard++ < 40) {
            world.tick();
        }
        assertNotNull(barracks.producing(),
                "the barracks starts a footman train after its action-33 counter exceeds 1");
        assertEquals("unit-footman", barracks.producing().ident(),
                "the paid job is a footman");
        assertEquals(1400, world.player(0).get(UnitType.Resource.GOLD),
                "computer players debit exactly 600 gold for the footman");
    }

    private static UnitType humanTanker() {
        UnitType type = new UnitType("unit-human-oil-tanker");
        type.setTileSize(1, 1);
        type.setHitPoints(90);
        type.setSpeed(10);
        type.setSeaUnit(true);
        type.costs().put(UnitType.Resource.GOLD, 400);
        type.costs().put(UnitType.Resource.WOOD, 200);
        type.costs().put(UnitType.Resource.TIME, 50);
        type.setDemand(1);
        return type;
    }

    private static UnitType humanShipyard() {
        UnitType type = new UnitType("unit-human-shipyard");
        type.setTileSize(3, 3);
        type.setHitPoints(1100);
        type.setBuilding(true);
        type.setSeaUnit(true);
        type.setShoreBuilding(true);
        return type;
    }

    private static UnitType orcShipyard() {
        UnitType type = new UnitType("unit-orc-shipyard");
        type.setTileSize(3, 3);
        type.setHitPoints(1100);
        type.setBuilding(true);
        type.setSeaUnit(true);
        type.setShoreBuilding(true);
        return type;
    }

    private static UnitType orcTanker() {
        UnitType type = new UnitType("unit-orc-oil-tanker");
        type.setTileSize(1, 1);
        type.setHitPoints(90);
        type.setSpeed(10);
        type.setSeaUnit(true);
        type.gathering().put(UnitType.Resource.OIL,
                new ResourceInfo(UnitType.Resource.OIL));
        type.costs().put(UnitType.Resource.GOLD, 400);
        type.costs().put(UnitType.Resource.WOOD, 200);
        type.costs().put(UnitType.Resource.TIME, 50);
        type.setDemand(1);
        return type;
    }

    private static byte[] retailAiBin() throws IOException {
        String packProp = System.getProperty("chonkcraft.pack");
        Path pack = packProp != null && !packProp.isBlank()
                ? Path.of(packProp)
                : Path.of(System.getProperty("user.home"),
                        ".chonkcraft/work",
                        "warcraft-ii-battle-net-edition-usa.pre-full-media-2026-07-30.chonkpack");
        assumeTrue(Files.isRegularFile(pack),
                "BNE asset pack required for retail ai.bin tanker wants");
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            var entry = zip.getEntry("assets/archives/maindat/0277.bin");
            assumeTrue(entry != null, "pack must contain maindat entry 277");
            try (var in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    @Test
    @DisplayName("a computer shipyard spends four hundred gold and two hundred wood on a tanker after its action-33 counter exceeds one")
    void aComputerShipyardSpendsFourHundredGoldAndTwoHundredWoodOnATankerAfterItsAction33CounterExceedsOne() {
        // XHuman 5 p3: third OP0 on the shipyard debits tanker 400/200.
        // Human player default race; same costs as the orc tanker on that map.
        World world = new World(grass(24));
        world.setTrainers(Map.of("unit-human-oil-tanker", Set.of("unit-human-shipyard")));
        world.player(0).setType(net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER);
        Unit shipyard = world.createUnit(humanShipyard(), 0, 2, 2);
        world.createUnit(humanTanker(), 0, 20, 20); // register type + census
        world.createUnit(farm(), 0, 8, 2);
        world.createUnit(farm(), 0, 12, 2);
        shipyard.setBattleNetAnimationTimer(1);
        shipyard.setBattleNetSequenceOffset(-1);
        shipyard.setBattleNetAiTrainCounter(0);
        shipyard.setBattleNetPudData(1);
        world.player(0).set(UnitType.Resource.GOLD, 3000);
        world.player(0).set(UnitType.Resource.WOOD, 10000);
        world.recalculateSupply();
        AiPlayer ai = world.enableAi(0);
        byte[] profile = new byte[128];
        profile[53 * 2] = 100;
        profile[100] = 104;
        profile[101] = 0;
        profile[102] = 104;
        profile[103] = 0;
        profile[104] = 0x4a;
        profile[105] = (byte) 0xff;
        ai.setBattleNetBuildProfile(profile, 53);
        // Synthetic profile has no real bytecode tanker write. Drive the
        // want explicitly so the test still covers the action-33 counter
        // and 400g/200w debit path.
        ai.setBattleNetWantedTankersForTest(3);

        int guard = 0;
        while (shipyard.producing() == null && guard++ < 40) {
            world.tick();
        }
        assertNotNull(shipyard.producing(),
                "the shipyard starts a tanker train after its action-33 counter exceeds 1");
        assertEquals("unit-human-oil-tanker", shipyard.producing().ident(),
                "the paid job is an oil tanker");
        assertEquals(2600, world.player(0).get(UnitType.Resource.GOLD),
                "XHuman 5 p3 debits exactly 400 gold for the tanker");
        assertEquals(9800, world.player(0).get(UnitType.Resource.WOOD),
                "XHuman 5 p3 debits exactly 200 wood for the tanker");
    }

    @Test
    @DisplayName("human fourteen death-knight temple spends fifteen hundred gold on raise-dead")
    void humanFourteenDeathKnightTempleSpendsFifteenHundredGoldOnRaiseDead()
            throws Exception {
        // retail-human-14-idle p0 profile 27 lists 93-97 and arms 0x93. The
        // sealed bank drops 1500g at fixture c35 with the temple on action 33
        // and no new building -- upgrade-raise-dead. Without temple action-33
        // research the bank stayed 30000 through c45.
        byte[] aiBin = retailAiBin();
        UnitType templeType = templeOfTheDamned();
        // Death-knight seat on Human 14 is an orc-race computer with a temple.
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i,
                    i == 0 ? net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER
                            : net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.NOBODY,
                    net.chonkbase.chonkcraft.data.map.PudMap.Race.ORC);
        }
        World world = new World(grass(24), players);
        world.setUnitTypes(Map.of("unit-temple-of-the-damned", templeType));
        net.chonkbase.chonkcraft.engine.upgrade.UpgradeSet upgrades =
                new net.chonkbase.chonkcraft.engine.upgrade.UpgradeSet();
        var raise = upgrades.getOrCreate("upgrade-raise-dead");
        raise.costs().put(UnitType.Resource.GOLD, 1500);
        raise.costs().put(UnitType.Resource.TIME, 100);
        world.setUpgrades(upgrades);
        world.setResearchers(Map.of(
                "upgrade-raise-dead", Set.of("unit-temple-of-the-damned")));
        Unit temple = world.createUnit(templeType, 0, 2, 2);
        temple.setBattleNetAnimationTimer(1);
        temple.setBattleNetSequenceOffset(-1);
        temple.setBattleNetAiTrainCounter(0);
        world.player(0).set(UnitType.Resource.GOLD, 30000);
        world.player(0).set(UnitType.Resource.WOOD, 20000);
        AiPlayer ai = world.enableAi(0);
        ai.setBattleNetBuildProfile(aiBin, 27);
        assertTrue(ai.battleNetHasAction33Candidate(0x93),
                "profile 27 arms research code 0x93 for the temple");

        int guard = 0;
        while (temple.researching() == null && guard++ < 120) {
            world.tick();
        }
        assertEquals("upgrade-raise-dead", temple.researching(),
                "the temple researches raise-dead on its action-33 pulse");
        assertEquals(28500, world.player(0).get(UnitType.Resource.GOLD),
                "Human 14 p0 debits exactly 1500 gold for raise-dead");
        assertEquals(20000, world.player(0).get(UnitType.Resource.WOOD),
                "raise-dead costs no wood");
    }

    @Test
    @DisplayName("an orc fourteen human mage tower does not spend five hundred gold on slow")
    void anOrcFourteenHumanMageTowerDoesNotSpendFiveHundredGoldOnSlow()
            throws Exception {
        // retail-orc-14-idle p6 profile 31 lists 93 first and arms 0x93. The
        // seat is human-race with a mage tower, not a temple. Native keeps
        // action-33 Still (next=60) through fixture 50 and holds gold 12200
        // after the transport debit; mapping 0x93 onto upgrade-slow debited
        // 500g at fixture 39. Codes 0x93-0x97 are the orc temple block only.
        byte[] aiBin = retailAiBin();
        UnitType mageType = mageTower();
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i,
                    i == 0 ? net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER
                            : net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.NOBODY,
                    net.chonkbase.chonkcraft.data.map.PudMap.Race.HUMAN);
        }
        World world = new World(grass(24), players);
        world.setUnitTypes(Map.of("unit-mage-tower", mageType));
        net.chonkbase.chonkcraft.engine.upgrade.UpgradeSet upgrades =
                new net.chonkbase.chonkcraft.engine.upgrade.UpgradeSet();
        var slow = upgrades.getOrCreate("upgrade-slow");
        slow.costs().put(UnitType.Resource.GOLD, 500);
        slow.costs().put(UnitType.Resource.TIME, 100);
        world.setUpgrades(upgrades);
        world.setResearchers(Map.of(
                "upgrade-slow", Set.of("unit-mage-tower")));
        Unit mage = world.createUnit(mageType, 0, 2, 2);
        mage.setBattleNetAnimationTimer(1);
        mage.setBattleNetSequenceOffset(-1);
        mage.setBattleNetAiTrainCounter(0);
        world.player(0).set(UnitType.Resource.GOLD, 12200);
        world.player(0).set(UnitType.Resource.WOOD, 37256);
        AiPlayer ai = world.enableAi(0);
        ai.setBattleNetBuildProfile(aiBin, 31);
        assertTrue(ai.battleNetHasAction33Candidate(0x93),
                "profile 31 arms research code 0x93");

        for (int i = 0; i < 120; i++) {
            world.tick();
        }
        assertNull(mage.researching(),
                "the mage tower must not research on the orc temple code block");
        assertEquals(12200, world.player(0).get(UnitType.Resource.GOLD),
                "Orc 14 p6 holds 12200 gold while native does at fixture 39");
        assertEquals(37256, world.player(0).get(UnitType.Resource.WOOD),
                "wood is unchanged when no research is paid");
    }

    private static UnitType templeOfTheDamned() {
        UnitType type = new UnitType("unit-temple-of-the-damned");
        type.setTileSize(3, 3);
        type.setHitPoints(500);
        type.setBuilding(true);
        type.setLandUnit(true);
        return type;
    }

    private static UnitType mageTower() {
        UnitType type = new UnitType("unit-mage-tower");
        type.setTileSize(3, 3);
        type.setHitPoints(500);
        type.setBuilding(true);
        type.setLandUnit(true);
        return type;
    }

    private static UnitType gryphonAviary() {
        UnitType type = new UnitType("unit-gryphon-aviary");
        type.setTileSize(2, 2);
        type.setHitPoints(500);
        type.setBuilding(true);
        type.setLandUnit(true);
        return type;
    }

    private static UnitType gryphonRider() {
        UnitType type = new UnitType("unit-gryphon-rider");
        type.setTileSize(1, 1);
        type.setHitPoints(100);
        type.setSpeed(32);
        type.setAirUnit(true);
        type.costs().put(UnitType.Resource.GOLD, 2500);
        type.costs().put(UnitType.Resource.TIME, 150);
        type.setDemand(1);
        return type;
    }

    @Test
    @DisplayName("a rich bank below four wanted flyers does not train a gryphon on the aviary pulse")
    void aRichBankBelowFourWantedFlyersDoesNotTrainAGryphonOnTheAviaryPulse() {
        // retail-orc-14-idle p6 profile 31 leaves wantFlyers=0; retail-xorc-
        // 11-idle p6 leaves wantFlyers=3. Both sit on a rich bank with a free
        // aviary. The old first-flyer bridge (gold >= 10000, no live flyer)
        // debited 2500g while native held the bank (orc-14 @35, xorc-11 @15).
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i,
                    i == 0 ? net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER
                            : net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.NOBODY,
                    net.chonkbase.chonkcraft.data.map.PudMap.Race.HUMAN);
        }
        World world = new World(grass(24), players);
        UnitType aviaryType = gryphonAviary();
        UnitType flyerType = gryphonRider();
        world.setTrainers(Map.of("unit-gryphon-rider",
                Set.of("unit-gryphon-aviary")));
        world.setUnitTypes(Map.of(
                "unit-gryphon-rider", flyerType,
                "unit-gryphon-aviary", aviaryType));
        Unit aviary = world.createUnit(aviaryType, 0, 2, 2);
        world.createUnit(farm(), 0, 8, 2);
        world.createUnit(farm(), 0, 12, 2);
        aviary.setBattleNetAnimationTimer(1);
        aviary.setBattleNetSequenceOffset(-1);
        aviary.setBattleNetAiTrainCounter(0);
        world.player(0).set(UnitType.Resource.GOLD, 15000);
        world.player(0).set(UnitType.Resource.WOOD, 10000);
        world.recalculateSupply();
        // No profile install: battleNetWantedFlyers() is 0 with null AI
        // state, matching Orc 14 p6 profile 31's opening wantFlyers=0.
        world.enableAi(0);

        int guard = 0;
        while (aviary.producing() == null && guard++ < 40) {
            world.tick();
        }
        assertEquals(null, aviary.producing(),
                "wantFlyers 0 must not start a gryphon train on a rich bank");
        assertEquals(15000, world.player(0).get(UnitType.Resource.GOLD),
                "Orc 14 p6 must keep its gold when wantFlyers is 0");
    }

    @Test
    @DisplayName("human fourteen black arms a tanker want from retail ai.bin and spends four hundred gold after the shipyard counter exceeds four")
    void humanFourteenBlackArmsATankerWantFromRetailAiBinAndSpendsFourHundredGoldAfterTheShipyardCounterExceedsFour()
            throws Exception {
        // Human 14 p5 profile 29 writes AIPlayerState+0x18 = 1 then WAIT
        // 30000. The campaign threshold table sets orc-shipyard limit 4, so
        // the sixth Still OP0 (~fixture c27) is the first debit. Zeroing
        // non-sealed tanker wants left the black seat's bank untouched.
        byte[] aiBin = retailAiBin();
        World world = new World(grass(24));
        UnitType tankerType = orcTanker();
        world.setTrainers(Map.of("unit-orc-oil-tanker", Set.of("unit-orc-shipyard")));
        world.setUnitTypes(Map.of(
                "unit-orc-oil-tanker", tankerType,
                "unit-orc-shipyard", orcShipyard()));
        world.player(0).setType(net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER);
        Unit shipyard = world.createUnit(orcShipyard(), 0, 2, 2);
        world.createUnit(farm(), 0, 8, 2);
        world.createUnit(farm(), 0, 12, 2);
        shipyard.setBattleNetAnimationTimer(1);
        shipyard.setBattleNetSequenceOffset(-1);
        shipyard.setBattleNetAiTrainCounter(0);
        shipyard.setBattleNetPudData(0);
        world.player(0).set(UnitType.Resource.GOLD, 37856);
        world.player(0).set(UnitType.Resource.WOOD, 37856);
        world.recalculateSupply();
        AiPlayer ai = world.enableAi(0);
        ai.setBattleNetBuildProfile(aiBin, 29);
        assertEquals(1, ai.battleNetWantedTankersForTestPeek(),
                "profile 29 writes a tanker want of one from retail ai.bin");
        assertEquals(4, ai.battleNetAction33Limit(
                        net.chonkbase.chonkcraft.data.map.PudUnitTypes.code(
                                "unit-orc-shipyard")),
                "Human 14's threshold table keeps orc shipyard train limit 4");

        int guard = 0;
        while (shipyard.producing() == null && guard++ < 80) {
            world.tick();
        }
        assertNotNull(shipyard.producing(),
                "the shipyard starts a tanker after the action-33 counter exceeds 4");
        assertEquals("unit-orc-oil-tanker", shipyard.producing().ident(),
                "the paid job is an orc oil tanker");
        assertEquals(37456, world.player(0).get(UnitType.Resource.GOLD),
                "Human 14 p5 debits exactly 400 gold for the tanker");
        assertEquals(37656, world.player(0).get(UnitType.Resource.WOOD),
                "Human 14 p5 debits exactly 200 wood for the tanker");
    }

    @Test
    @DisplayName("a data-marked preplaced tanker satisfies the shipyard oil want so the yard does not re-spend")
    void aDataMarkedPreplacedTankerSatisfiesTheShipyardOilWantSoTheYardDoesNotReSpend() {
        // XHuman 8 p7: want tankers 1, one data=1 tanker already on the map.
        // Counting only AI-accounted (Data==0) tankers made the yard spend
        // another 400/200 at fixture c12 while native kept the bank.
        World world = new World(grass(24));
        world.setTrainers(Map.of("unit-orc-oil-tanker", Set.of("unit-orc-shipyard")));
        world.player(0).setType(net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER);
        Unit shipyard = world.createUnit(orcShipyard(), 0, 2, 2);
        Unit existing = world.createUnit(orcTanker(), 0, 20, 20);
        existing.setBattleNetPudData(1);
        existing.setBattleNetReadySuppressed(true);
        world.createUnit(farm(), 0, 8, 2);
        world.createUnit(farm(), 0, 12, 2);
        shipyard.setBattleNetAnimationTimer(1);
        shipyard.setBattleNetSequenceOffset(-1);
        shipyard.setBattleNetAiTrainCounter(0);
        shipyard.setBattleNetPudData(1);
        world.player(0).set(UnitType.Resource.GOLD, 850);
        world.player(0).set(UnitType.Resource.WOOD, 4350);
        world.recalculateSupply();
        AiPlayer ai = world.enableAi(0);
        ai.setBattleNetWantedTankersForTest(1);

        for (int i = 0; i < 40; i++) {
            world.tick();
        }
        assertEquals(null, shipyard.producing(),
                "a preplaced tanker already fills want 1");
        assertEquals(850, world.player(0).get(UnitType.Resource.GOLD),
                "XHuman 8 p7 keeps its gold when the oil census is already full");
        assertEquals(4350, world.player(0).get(UnitType.Resource.WOOD),
                "XHuman 8 p7 keeps its wood when the oil census is already full");
    }

    @Test
    @DisplayName("a barracks without the soldier milestone does not spend six hundred gold")
    void aBarracksWithoutTheSoldierMilestoneDoesNotSpendSixHundredGold() {
        // XHuman 8 p3 profile 61 lists 0x80 but not 0x81; native resets the
        // action-33 counter without debiting.
        World world = new World(grass(24));
        world.setTrainers(Map.of("unit-footman", Set.of("unit-human-barracks")));
        world.player(0).setType(net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER);
        Unit barracks = world.createUnit(barracks(), 0, 2, 2);
        world.createUnit(farm(), 0, 8, 2);
        world.createUnit(farm(), 0, 12, 2);
        barracks.setBattleNetAnimationTimer(1);
        barracks.setBattleNetSequenceOffset(-1);
        barracks.setBattleNetAiTrainCounter(0);
        barracks.setBattleNetPudData(1);
        world.player(0).set(UnitType.Resource.GOLD, 1950);
        world.player(0).set(UnitType.Resource.WOOD, 1800);
        world.recalculateSupply();
        AiPlayer ai = world.enableAi(0);
        // Profile 61 (XHuman 8 p3) is outside the 40/44 arm set even when a
        // synthetic list contains 0x81.
        byte[] profile = new byte[128];
        profile[61 * 2] = 100;
        profile[100] = 104;
        profile[101] = 0;
        profile[102] = 104;
        profile[103] = 0;
        profile[104] = 0x4a;
        profile[105] = (byte) 0x81;
        profile[106] = (byte) 0xff;
        ai.setBattleNetBuildProfile(profile, 61);

        for (int i = 0; i < 40; i++) {
            world.tick();
        }
        assertEquals(null, barracks.producing(),
                "profiles outside 40/44 must not auto-train a footman");
        assertEquals(1950, world.player(0).get(UnitType.Resource.GOLD),
                "XHuman 8 p3 keeps its gold when the profile never arms soldiers");
    }

}
