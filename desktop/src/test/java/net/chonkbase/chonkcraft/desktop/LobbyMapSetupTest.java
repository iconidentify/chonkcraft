package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.chonkbase.chonkcraft.data.map.PudReader;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.network.GameLobby;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The synchronized lobby map becoming the map the desktop actually loads.
 *
 * <p>The lobby used to agree on a filename and the desktop then looked that
 * filename up in its own asset source. A joiner without it was returned to the
 * menu after everybody had already assembled, and two files with the same name
 * were allowed to become different worlds. These start with no local map and
 * cross the desktop seam, because testing the transfer alone would still pass
 * if the game ignored the bytes after receiving them.
 */
class LobbyMapSetupTest {

    private static final int PORT = 7521;

    private static PudMap multiplayerMap() {
        PudMap.PlayerType[] slots = new PudMap.PlayerType[PudMap.PLAYER_MAX];
        java.util.Arrays.fill(slots, PudMap.PlayerType.NOBODY);
        slots[0] = PudMap.PlayerType.PERSON;
        slots[1] = PudMap.PlayerType.PERSON;
        slots[2] = PudMap.PlayerType.PERSON;
        slots[3] = PudMap.PlayerType.PERSON;
        slots[15] = PudMap.PlayerType.NEUTRAL;
        PudMap.Race[] races = new PudMap.Race[PudMap.PLAYER_MAX];
        java.util.Arrays.fill(races, PudMap.Race.NEUTRAL);
        for (int player = 0; player < 4; player++) {
            races[player] = PudMap.Race.HUMAN;
        }
        int[] nothing = new int[PudMap.PLAYER_MAX];
        return new PudMap("network seats", PudMap.Tileset.FOREST, 8, 8, new int[64],
                slots, races, nothing, nothing, nothing, nothing, null, null, List.of(
                        new PudMap.PudUnit(1, 1, 0x5E, 0, 0),
                        new PudMap.PudUnit(6, 1, 0x5E, 1, 0),
                        new PudMap.PudUnit(1, 6, 0x5E, 2, 0),
                        new PudMap.PudUnit(6, 6, 0x5E, 3, 0)));
    }

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    @Test
    @DisplayName("The game loads the verified map even when no local file exists")
    void theGameUsesTheSynchronizedMap(@TempDir Path directory) throws Exception {
        byte[] hosted = new byte[118_000];
        for (int i = 0; i < hosted.length; i++) {
            hosted[i] = (byte) (i * 23 + 11);
        }
        InetAddress local = InetAddress.getLoopbackAddress();
        try (GameLobby host = GameLobby.host(
                    "Chris", "host-only.pud", hosted, 8, PORT);
                GameLobby client = GameLobby.join(
                    "Ann", local, PORT, name -> null)) {
            long deadline = System.currentTimeMillis() + 5_000L;
            while (System.currentTimeMillis() < deadline && !client.state().mapReady()) {
                host.poll();
                client.poll();
                Thread.sleep(5);
            }
            assertTrue(client.state().mapReady(),
                    "the missing map did not finish crossing the lobby");
            Path absent = directory.resolve("host-only.pud");
            assertFalse(Files.exists(absent),
                    "the fixture accidentally has the map on disk");

            LobbySetup setup = new LobbySetup(absent, client);
            assertArrayEquals(hosted, setup.mapBytes(null),
                    "the desktop ignored the verified transfer and looked for a local file");
        }
    }

    @Test
    @DisplayName("The final lobby table creates every human and computer player")
    void theGameUsesTheFinalLobbyPlayers() throws Exception {
        byte[] hosted = new byte[32];
        InetAddress local = InetAddress.getLoopbackAddress();
        try (GameLobby host = GameLobby.host("Chris", "network.pud", hosted, 4, PORT + 1);
                GameLobby client = GameLobby.join("Ann", local, PORT + 1, name -> hosted)) {
            long deadline = System.currentTimeMillis() + 5_000L;
            while (System.currentTimeMillis() < deadline
                    && (host.humanCount() < 2 || !host.state().allPlayersReady())) {
                host.poll();
                client.poll();
                Thread.sleep(5);
            }
            assertEquals(2, host.humanCount());

            // Start immediately after the last configuration change. START
            // itself must commit this table; relying on a preceding STATE is
            // a UDP race and once produced different worlds at cycle zero.
            host.setRace(client.state().localSlot(), "orc");
            host.setOccupant(2, GameLobby.Occupant.COMPUTER);
            host.setOccupant(3, GameLobby.Occupant.CLOSED);
            host.start();
            deadline = System.currentTimeMillis() + 5_000L;
            while (System.currentTimeMillis() < deadline && !client.isStarted()) {
                client.poll();
                Thread.sleep(5);
            }
            assertTrue(client.isStarted());

            Player[] players = new LobbySetup(Path.of("network.pud"), client)
                    .players(multiplayerMap());
            int localPlayer = client.state().localSlot();
            assertEquals(PudMap.PlayerType.PERSON, players[0].type());
            assertEquals(PudMap.PlayerType.PERSON, players[localPlayer].type(),
                    "the joining human was disabled as an extra local seat");
            assertEquals(PudMap.Race.ORC, players[localPlayer].race());
            assertEquals(PudMap.PlayerType.COMPUTER, players[2].type(),
                    "the deterministic AI slot disappeared from the network world");
            assertEquals(PudMap.PlayerType.NOBODY, players[3].type());
        }
    }

    @Test
    @DisplayName("Top vs Bottom creates two mutual teams with shared vision")
    void topVsBottomAppliesAlliancesAndSharedVision() throws Exception {
        PudMap source = multiplayerMap();
        try (GameLobby host = GameLobby.host("Chris", "network.pud", 4, PORT + 2)) {
            host.setOccupant(1, GameLobby.Occupant.COMPUTER);
            host.setOccupant(2, GameLobby.Occupant.COMPUTER);
            host.setOccupant(3, GameLobby.Occupant.COMPUTER);
            host.setGameTemplate(GameLobby.GameTemplate.TOP_VS_BOTTOM);
            LobbySetup setup = new LobbySetup(Path.of("network.pud"), host);
            World world = new World(grass(8), setup.players(source));

            setup.applyGameTemplate(world, source);

            assertTrue(world.isAllied(0, 1));
            assertTrue(world.isAllied(1, 0));
            assertTrue(world.sharesVisionWith(0, 1));
            assertTrue(world.sharesVisionWith(1, 0));
            assertTrue(world.isAllied(2, 3));
            assertTrue(world.sharesVisionWith(2, 3));
            assertFalse(world.isAllied(0, 2));
            assertTrue(world.isEnemyPlayer(0, 2));
            assertFalse(world.sharesVisionWith(0, 2));
        }
    }

    @Test
    @DisplayName("All You Need starts the displayed Top Team as allies with shared sight")
    void allYouNeedTopTeamCrossesTheStartCommit() throws Exception {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null, "No BNE media configured");
        byte[] bytes = assets.map("All You Need BNE.pud");
        Assumptions.assumeTrue(bytes != null,
                "This BNE installation does not carry the supplemental All You Need map");
        PudMap source = PudReader.read(bytes);
        GameData data = new GameData(assets);
        InetAddress local = InetAddress.getLoopbackAddress();

        try (GameLobby host = GameLobby.host(
                    "Chris", "All You Need BNE.pud", bytes, 8, PORT + 3);
                GameLobby client = GameLobby.join(
                    "Connor", local, PORT + 3, ignored -> bytes)) {
            long deadline = System.currentTimeMillis() + 5_000L;
            while (System.currentTimeMillis() < deadline
                    && (host.humanCount() < 2 || !host.state().allPlayersReady())) {
                host.poll();
                client.poll();
                Thread.sleep(5);
            }
            assertEquals(2, host.humanCount(), "Connor never reached the lobby");

            int connor = host.state().slots().stream()
                    .filter(slot -> "Connor".equals(slot.name()))
                    .findFirst().orElseThrow().index();
            LobbyTeams teams = LobbyTeams.from(source, 8);
            assertFalse(teams.together(0, connor),
                    "the regression needs the default second seat in the other area");
            assertTrue(host.move(connor, 3),
                    "Violet is the open Top Team seat displayed below Red");
            assertTrue(host.setOccupant(7, GameLobby.Occupant.COMPUTER));
            assertTrue(host.setGameTemplate(GameLobby.GameTemplate.TOP_VS_BOTTOM));

            // Start immediately. START must commit the move and template even
            // if the last ordinary STATE datagram is still in flight.
            host.start();
            deadline = System.currentTimeMillis() + 5_000L;
            while (System.currentTimeMillis() < deadline && !client.isStarted()) {
                client.poll();
                Thread.sleep(5);
            }
            assertTrue(client.isStarted());
            assertEquals(3, client.state().localSlot());
            assertEquals(GameLobby.GameTemplate.TOP_VS_BOTTOM,
                    client.state().gameTemplate());

            LobbySetup hostSetup = new LobbySetup(Path.of("All You Need BNE.pud"), host);
            LobbySetup clientSetup = new LobbySetup(Path.of("All You Need BNE.pud"), client);
            World hostWorld = populatedWorld(data, source, hostSetup);
            World clientWorld = populatedWorld(data, source, clientSetup);
            int[] connorStart = source.startLocation(3);

            for (World world : List.of(hostWorld, clientWorld)) {
                assertTrue(world.isAllied(0, 3));
                assertTrue(world.isAllied(3, 0));
                assertTrue(world.sharesVisionWith(0, 3));
                assertTrue(world.sharesVisionWith(3, 0));
                assertTrue(world.isVisibleTo(0, connorStart[0], connorStart[1]),
                        "Connor's opening sight did not reach Chris's gameboard");
                assertFalse(world.isAllied(0, 7),
                        "the bottom computer was silently put on Chris's team");
                assertTrue(world.isEnemyPlayer(0, 7));
                assertEquals(PudMap.PlayerType.COMPUTER, world.player(7).type(),
                        "the bottom lobby seat did not become a computer player");
                assertEquals(1, world.enableAiForComputerPlayers(),
                        "the displayed computer did not start exactly one live AI");
                assertTrue(world.ais().containsKey(7),
                        "the bottom computer's AI was absent from the running world");
                assertEquals(1, data.attachRetailAi(world, source, java.util.Map.of()).size(),
                        "the bottom computer did not receive its retail ai.bin profile");
            }
        }
    }

    private static World populatedWorld(GameData data, PudMap source, LobbySetup setup) {
        World world = new World(
                GameMap.from(source, data.loadTileset(source.tileset()).tileset()),
                setup.players(source));
        data.configureWorld(world, source);
        data.populate(world, source);
        setup.applyGameTemplate(world, source);
        world.recalculateSupply();
        return world;
    }

    @Test
    @DisplayName("A network player slot cannot leak into the next game")
    void aNetworkPlayerSlotIsScopedToOneStartup() {
        PudMap map = multiplayerMap();

        assertEquals(3, Main.localPlayerForStart(map, 3),
                "the joining client lost the slot assigned by its lobby");
        assertEquals(0, Main.localPlayerForStart(map, -1),
                "a later local game inherited the prior network player's slot");
    }
}
