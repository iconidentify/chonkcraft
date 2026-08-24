package net.chonkbase.chonkcraft.desktop;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JPanel;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.network.GameLobby;

/**
 * The table everybody sits at before the game starts.
 *
 * <p>Eight rows, one per slot. A slot determines colour and starting position;
 * in Teams mode a separate, explicit team number determines alliances. The
 * host can change either without silently changing the other.
 *
 * <p>The host decides and everyone else watches. Every control here is the
 * host's; a client sees the same table and can change nothing on it, which is
 * exactly what it can do over the wire, so the screen is not promising
 * anything the protocol will not honour.
 */
final class LobbyScreen extends JPanel {

    /** The size everything is laid out at, and then scaled up from. */
    private static final int DESIGN_WIDTH = 640;

    private static final int DESIGN_HEIGHT = 480;

    /** The table's own geometry, in design pixels. */
    private static final int TABLE_X = 40;

    private static final int TABLE_Y = 106;

    private static final int TABLE_WIDTH = DESIGN_WIDTH - TABLE_X * 2;

    private static final int ROW_HEIGHT = 30;

    private static final int ROW_GAP = 4;

    /** Where the per-row controls sit, measured from the table's right edge. */
    private static final int ACTION_WIDTH = 120;

    private static final int MOVE_WIDTH = 54;

    private static final int ACTION_GAP = 4;

    private static final int RACE_WIDTH = 72;

    /**
     * The buttons along the bottom.
     *
     * <p>Sixteen clear of the panel's foot, which is what it clears the panel's
     * head by. At fifty-six from the page they sat eight from the panel and the
     * screen read as having slipped downwards.
     */
    private static final int FOOT_Y = DESIGN_HEIGHT - 64;

    private static final int FOOT_HEIGHT = 32;

    /** The line of advice or complaint above the buttons. */
    private static final int HINT_Y = FOOT_Y - 30;

    /** Where the two columns of a row's text begin. */
    private static final int COLOUR_COLUMN = 30;

    private static final int TEAM_COLUMN = 108;

    private static final int WHO_COLUMN = 202;

    /** What the screen can ask of whatever is running it. */
    interface Listener {
        /** The host pressed Start, or a client heard that it had been pressed. */
        void onStart(GameLobby lobby);

        /** Somebody backed out. */
        void onCancel();

        /** A deterministic peer requires different game code; return to the updater. */
        default void onUpdateRequired() {
            onCancel();
        }
    }

    private final GameLobby lobby;
    private final Listener listener;
    private final OnlineLobby online;

    /** The heading's face, which is the menu's, so the two screens match. */
    private final GameFont large;

    private final GameFont font;
    private final GameFont small;
    private final String mapName;

    /** Where each clickable thing was last drawn, in design pixels. */
    private final List<Hit> hits = new ArrayList<>();

    /** A clickable region and what it does. */
    private record Hit(Rectangle where, Runnable action) {}

    /**
     * Which player the host has picked up, or -1.
     *
     * <p>Moving somebody is two clicks: theirs, then the slot to put them in.
     * A drag would be prettier and would also be the only drag on the screen,
     * and a gesture that exists in one place is a gesture nobody finds.
     */
    private int holding = -1;

    /** What went wrong, shown under the table. */
    private String notice = "";

    /** The lobby has handed itself onward, so another frame must not do it again. */
    private boolean starting;

    private BufferedImage design;
    private BufferedImage scaleCache;

    LobbyScreen(GameData data, GameLobby lobby, String mapName, Listener listener) {
        this(data, lobby, mapName, null, listener);
    }

    LobbyScreen(GameData data, GameLobby lobby, String mapName, OnlineLobby online,
            Listener listener) {
        this.lobby = lobby;
        this.listener = listener;
        this.online = online;
        this.mapName = mapName == null ? "" : mapName;
        this.large = data == null ? null : GameFont.load(data, GameFont.Face.LARGE);
        this.font = data == null ? null : GameFont.load(data, GameFont.Face.GAME);
        this.small = data == null ? null : GameFont.load(data, GameFont.Face.SMALL);
        setFocusable(true);
        setBackground(Color.BLACK);
        installInput();
    }

    /**
     * Carries the conversation on and redraws.
     *
     * <p>Driven from outside rather than by a timer of its own, so the screen
     * has one clock and not two.
     */
    void tick() {
        lobby.poll();
        if (online != null && lobby.isHost()) {
            online.tick(lobby);
        }
        if (lobby.state().allPlayersReady()
                && "Waiting for every player to receive the map.".equals(notice)) {
            notice = "";
        }
        if (!lobby.isHost() && lobby.wasRejectedAsFull()) {
            notice = "That game is full.";
        }
        if (lobby.isStarted() && !lobby.isHost() && !starting) {
            // Loading the map takes longer than one UI frame. This used to
            // call onStart on every frame until the game screen appeared,
            // starting several peers over the same released socket.
            starting = true;
            listener.onStart(lobby);
            return;
        }
        repaint();
    }

    private void installInput() {
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                requestFocusInWindow();
                Point at = toDesign(event.getPoint());
                for (Hit hit : List.copyOf(hits)) {
                    if (hit.where().contains(at)) {
                        hit.action().run();
                        repaint();
                        return;
                    }
                }
            }
        });
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    cancel();
                } else if (event.getKeyCode() == KeyEvent.VK_ENTER && lobby.isHost()) {
                    begin();
                }
            }
        });
    }

    private void cancel() {
        closeLobby();
        listener.onCancel();
    }

    private void quitForUpdate() {
        closeLobby();
        listener.onUpdateRequired();
    }

    private void closeLobby() {
        if (online != null && lobby.isHost()) {
            online.close();
        }
        if (!lobby.isHost()) {
            lobby.leave();
        }
        lobby.close();
    }

    private void begin() {
        if (!lobby.isHost()) {
            return;
        }
        GameLobby.State state = lobby.state();
        if (!state.allPlayersReady()) {
            notice = "Waiting for every player to receive the map.";
            return;
        }
        long playing = state.slots().stream().filter(GameLobby.Slot::isPlaying).count();
        if (playing < 2) {
            notice = "A game needs at least two players.";
            return;
        }
        if (state.gameTemplate() == GameLobby.GameTemplate.TEAMS
                && !state.hasValidMatchup()
                && !state.canInferComputerOpponents()) {
            notice = "Add a computer opponent or assign someone to another team.";
            return;
        }
        try {
            lobby.start();
            if (!lobby.isStarted()) {
                notice = "The roster changed. Check the team assignments and try again.";
                return;
            }
            if (online != null) {
                online.starting(lobby);
            }
            lobby.markOnlineRoomStarted();
        } catch (java.io.IOException failed) {
            notice = "Could not tell everyone to start: " + failed.getMessage();
            return;
        }
        starting = true;
        listener.onStart(lobby);
    }

    /** What the host's click on a slot does, given what is in it. */
    private void cycle(int index, GameLobby.Occupant occupant) {
        GameLobby.Occupant next = switch (occupant) {
            case OPEN -> GameLobby.Occupant.COMPUTER;
            case COMPUTER -> GameLobby.Occupant.CLOSED;
            default -> GameLobby.Occupant.OPEN;
        };
        lobby.setOccupant(index, next);
    }

    /** The host picking a player up, or putting them down in an open slot. */
    private void pickUpOrPlace(int index, GameLobby.Slot slot) {
        if (holding >= 0) {
            if (slot.occupant() == GameLobby.Occupant.OPEN) {
                lobby.move(holding, index);
            }
            holding = -1;
            return;
        }
        if (slot.isPlaying()) {
            holding = index;
        }
    }

    /** Cycles the creator's synchronized game type. */
    private void cycleGameTemplate() {
        GameLobby.GameTemplate current = lobby.state().gameTemplate();
        lobby.setGameTemplate(current.next());
    }

    /** Advances one player's explicit team without changing colour or start. */
    private void cycleTeam(GameLobby.Slot slot) {
        int teamCount = Math.min(8, Math.max(2, lobby.capacity()));
        lobby.setTeam(slot.index(), slot.team() % teamCount + 1);
        notice = "";
    }

    // ---- drawing ------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (design == null) {
            design = new BufferedImage(DESIGN_WIDTH, DESIGN_HEIGHT, BufferedImage.TYPE_INT_RGB);
        }
        Graphics2D g2 = design.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        drawDesign(g2);
        g2.dispose();

        Graphics2D out = (Graphics2D) g;
        scaleCache = PixelScaler.draw(out, design, getWidth(), getHeight(), false, scaleCache);
    }

    private void drawDesign(Graphics2D g2) {
        hits.clear();
        g2.setColor(new Color(0x101014));
        g2.fillRect(0, 0, DESIGN_WIDTH, DESIGN_HEIGHT);
        PanelArt.panel(g2, 16, 16, DESIGN_WIDTH - 32, DESIGN_HEIGHT - 32,
                StoneTexture.Tint.STONE);

        GameLobby.State state = lobby.state();
        // The same face and the same gold the menu sets its headings in: this
        // screen is reached from that one, and a heading two sizes smaller
        // read as a caption rather than as the top of a page.
        if (large != null) {
            large.drawCentred(g2, lobbyHeading(),
                    DESIGN_WIDTH / 2, 30, GameFont.Ink.YELLOW);
        }
        if (font != null) {
            String map = mapName.isEmpty() ? state.map() : mapName;
            font.drawCentred(g2, font.fitted(map, DESIGN_WIDTH - 120),
                    DESIGN_WIDTH / 2, 54, GameFont.Ink.WHITE);
        }
        if (online != null) {
            if (font != null) {
                font.draw(g2, "Game code  " + online.code(), TABLE_X, 79, GameFont.Ink.YELLOW);
            }
            button(g2, TABLE_X + TABLE_WIDTH - 154, 72, 154, 26,
                    "Copy Invite", this::copyInvite);
        } else if (font != null) {
            String direct = lobby.isHost()
                    ? "Direct UDP " + lobby.connectionPort()
                            + "  ·  share public-ip:" + lobby.connectionPort()
                    : "Direct connection  ·  host UDP " + lobby.connectionPort();
            font.drawCentred(g2, font.fitted(direct, TABLE_WIDTH), DESIGN_WIDTH / 2, 79,
                    GameFont.Ink.YELLOW);
        }

        drawTable(g2, state);
        drawFoot(g2, state);
    }

    private String lobbyHeading() {
        if (online != null) {
            return online.privateGame() ? "Private Game Lobby" : "Online Game Lobby";
        }
        return lobby.isHost() ? "Direct IP Lobby" : "Joining Direct Game";
    }

    private void drawTable(Graphics2D g2, GameLobby.State state) {
        List<GameLobby.Slot> slots = state.slots();
        for (int i = 0; i < slots.size(); i++) {
            GameLobby.Slot slot = slots.get(i);
            int y = TABLE_Y + i * (ROW_HEIGHT + ROW_GAP);
            boolean mine = slot.index() == state.localSlot();
            PanelArt.sunken(g2, TABLE_X, y, TABLE_WIDTH, ROW_HEIGHT,
                    mine ? StoneTexture.Tint.SLATE : StoneTexture.Tint.STONE);
            if (state.gameTemplate() == GameLobby.GameTemplate.TEAMS && slot.isPlaying()) {
                Color team = PlayerColours.of(slot.team() - 1);
                g2.setColor(new Color(team.getRed(), team.getGreen(), team.getBlue(), 34));
                g2.fillRect(TABLE_X + 2, y + 2, TABLE_WIDTH - 4, ROW_HEIGHT - 4);
            }
            if (holding == slot.index()) {
                g2.setColor(new Color(255, 220, 100, 60));
                g2.fillRect(TABLE_X, y, TABLE_WIDTH, ROW_HEIGHT);
            }

            // The colour, which is the point of the slot.
            g2.setColor(PlayerColours.of(slot.index()));
            g2.fillRect(TABLE_X + 6, y + 7, 16, 16);
            g2.setColor(new Color(0, 0, 0, 180));
            g2.drawRect(TABLE_X + 6, y + 7, 16, 16);

            int raceX = TABLE_X + TABLE_WIDTH - ACTION_WIDTH - RACE_WIDTH - 12;
            if (font != null) {
                // Centred in the row rather than eight pixels down it: eight
                // was a guess made against a bitmap face and it left every
                // line sitting low in its slot.
                int text = y + (ROW_HEIGHT - font.height()) / 2;
                font.draw(g2, PlayerColours.nameOf(slot.index()),
                        TABLE_X + COLOUR_COLUMN, text,
                        GameFont.Ink.GREY);
                String who = switch (slot.occupant()) {
                    case HUMAN -> slot.name() + (mine ? " (you)" : "");
                    case COMPUTER -> "Computer";
                    case CLOSED -> "Closed";
                    case OPEN -> "Open";
                };
                GameFont.Ink ink = slot.occupant() == GameLobby.Occupant.HUMAN
                        ? GameFont.Ink.WHITE
                        : GameFont.Ink.GREY;
                // A player's name is theirs, not ours, and it stops where the
                // buttons begin.
                font.draw(g2, font.fitted(who, raceX - (TABLE_X + WHO_COLUMN) - 12),
                        TABLE_X + WHO_COLUMN, text, ink);
            }

            int actionX = TABLE_X + TABLE_WIDTH - ACTION_WIDTH - 6;
            if (state.gameTemplate() == GameLobby.GameTemplate.TEAMS && slot.isPlaying()) {
                button(g2, TABLE_X + TEAM_COLUMN - 4, y + 4, 84, ROW_HEIGHT - 8,
                        "Team " + slot.team(), lobby.isHost() ? () -> cycleTeam(slot) : null);
            }
            if (slot.occupant() != GameLobby.Occupant.CLOSED) {
                button(g2, raceX, y + 4, RACE_WIDTH, ROW_HEIGHT - 8,
                        "orc".equals(slot.race()) ? "Orc" : "Human",
                        lobby.isHost() ? () -> lobby.setRace(slot.index(),
                                "orc".equals(slot.race()) ? "human" : "orc") : null);
            }

            if (lobby.isHost()) {
                if (slot.isPlaying()) {
                    button(g2, actionX, y + 4, MOVE_WIDTH, ROW_HEIGHT - 8, "Move",
                            () -> pickUpOrPlace(slot.index(), slot));
                    if (!mine) {
                        int secondaryX = actionX + MOVE_WIDTH + ACTION_GAP;
                        button(g2, secondaryX, y + 4,
                                ACTION_WIDTH - MOVE_WIDTH - ACTION_GAP, ROW_HEIGHT - 8,
                                slot.occupant() == GameLobby.Occupant.HUMAN
                                        ? "Remove" : "Close",
                                slot.occupant() == GameLobby.Occupant.HUMAN
                                        ? () -> lobby.kick(slot.index())
                                        : () -> cycle(slot.index(), slot.occupant()));
                    }
                } else {
                    button(g2, actionX, y + 4, ACTION_WIDTH, ROW_HEIGHT - 8,
                            captionFor(slot.occupant()),
                            () -> cycle(slot.index(), slot.occupant()));
                }
            }

            // The rest of the row picks any playing seat up and puts it down
            // again. That includes computers: a team setup should not require
            // closing one AI and recreating it on the other side.
            if (lobby.isHost()) {
                hits.add(new Hit(new Rectangle(TABLE_X, y, TABLE_WIDTH - ACTION_WIDTH
                        - RACE_WIDTH - 20, ROW_HEIGHT), () -> pickUpOrPlace(slot.index(), slot)));
            }
        }
    }

    /** What the cycling button offers next, which is what it should say. */
    private static String captionFor(GameLobby.Occupant occupant) {
        return switch (occupant) {
            case OPEN -> "Computer";
            case COMPUTER -> "Close";
            default -> "Open";
        };
    }

    private void drawFoot(Graphics2D g2, GameLobby.State state) {
        if (small != null) {
            String hint = notice;
            if (hint.isEmpty() && online != null && !online.serviceProblem().isEmpty()) {
                hint = online.serviceProblem();
            }
            if (hint.isEmpty() && !state.mapProblem().isEmpty()) {
                hint = state.mapProblem();
            } else if (hint.isEmpty() && !lobby.isHost() && state.localSlot() < 0) {
                hint = online == null ? "Contacting host on UDP " + lobby.connectionPort() + "..."
                        : "Contacting the host through ChonkCraft online...";
            } else if (hint.isEmpty() && !state.mapReady()) {
                hint = "Receiving map from host... " + state.mapPercent() + "%";
            } else if (hint.isEmpty() && !state.allPlayersReady()) {
                hint = lobby.isHost()
                        ? "Waiting for every player to receive the map."
                        : "Your map is ready. Waiting for the other players.";
            } else if (hint.isEmpty() && lobby.isHost()) {
                hint = lobby.humanCount() == 1
                        ? online == null
                                ? "Waiting for players on UDP " + lobby.connectionPort() + "."
                                : online.privateGame()
                                        ? "Only people with code " + online.code() + " can join."
                                        : "Share code " + online.code() + " or copy the invite."
                        : holding >= 0
                                ? "Now click an open slot to move them there."
                                : state.gameTemplate() == GameLobby.GameTemplate.TEAMS
                                        ? teamSummary(state)
                                        : "Click any player, then an open slot, to choose a colour.";
            } else if (hint.isEmpty()) {
                hint = state.localSlot() < 0
                        ? "Looking for the host..."
                        : "Waiting for the host to start.";
            }
            small.draw(g2, small.fitted(hint, TABLE_WIDTH), TABLE_X, HINT_Y,
                    notice.isEmpty() && !state.updateRequired()
                            ? GameFont.Ink.GREY : GameFont.Ink.RED);
        }

        if (lobby.isHost()) {
            boolean enoughPlayers = state.slots().stream().filter(GameLobby.Slot::isPlaying)
                    .count() >= 2;
            boolean opposingTeams = state.gameTemplate() != GameLobby.GameTemplate.TEAMS
                    || state.hasValidMatchup()
                    || state.canInferComputerOpponents();
            boolean canStart = state.canStart();
            button(g2, TABLE_X, FOOT_Y, 160, FOOT_HEIGHT,
                    !enoughPlayers ? "Waiting for Players"
                            : !opposingTeams ? "Assign Opponents"
                            : state.allPlayersReady() ? "Start Game" : "Syncing Map...",
                    canStart ? this::begin : null);
        }
        button(g2, TABLE_X + 180, FOOT_Y, 200, FOOT_HEIGHT,
                "Mode: " + state.gameTemplate().caption(),
                lobby.isHost() ? this::cycleGameTemplate : null);
        button(g2, TABLE_X + TABLE_WIDTH - 160, FOOT_Y, 160, FOOT_HEIGHT,
                state.updateRequired() ? "Quit to Update" : "Cancel (Esc)",
                state.updateRequired() ? this::quitForUpdate : this::cancel);
    }

    /** A compact, literal account of who will share sight and who will not. */
    private String teamSummary(GameLobby.State state) {
        Map<Integer, List<String>> teams = new LinkedHashMap<>();
        for (GameLobby.Slot slot : state.slots()) {
            if (!slot.isPlaying()) {
                continue;
            }
            String name = slot.occupant() == GameLobby.Occupant.COMPUTER
                    ? "Computer" : slot.name();
            String member = name + " (" + PlayerColours.nameOf(slot.index()) + ")";
            teams.computeIfAbsent(slot.team(), ignored -> new ArrayList<>()).add(member);
        }
        List<String> summaries = new ArrayList<>();
        for (Map.Entry<Integer, List<String>> team : teams.entrySet()) {
            summaries.add("Team " + team.getKey() + ": " + String.join(" + ", team.getValue()));
        }
        return String.join(" | ", summaries) + " | Team chat and shared sight ON";
    }

    private void copyInvite() {
        if (online == null) {
            return;
        }
        try {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                    new java.awt.datatransfer.StringSelection(online.inviteUrl()), null);
            notice = "Invite copied.";
        } catch (RuntimeException unavailable) {
            notice = "Game code: " + online.code();
        }
    }

    /** Draws a button and, when it does something, registers where it is. */
    private void button(Graphics2D g2, int x, int y, int width, int height,
            String caption, Runnable action) {
        PanelArt.panel(g2, x, y, width, height,
                action == null ? StoneTexture.Tint.STONE : StoneTexture.Tint.SLATE);
        if (font != null) {
            font.drawCentred(g2, caption, x + width / 2, y + (height - font.height()) / 2,
                    action == null ? GameFont.Ink.GREY : GameFont.Ink.WHITE);
        }
        if (action != null) {
            hits.add(new Hit(new Rectangle(x, y, width, height), action));
        }
    }

    /** A point on the window in the design's own pixels. */
    private Point toDesign(Point screen) {
        Rectangle fitted = PixelScaler.fit(DESIGN_WIDTH, DESIGN_HEIGHT, getWidth(), getHeight(), false);
        if (fitted.width <= 0 || fitted.height <= 0) {
            return screen;
        }
        return new Point(
                (int) ((screen.x - fitted.x) * (double) DESIGN_WIDTH / fitted.width),
                (int) ((screen.y - fitted.y) * (double) DESIGN_HEIGHT / fitted.height));
    }

    /** The families this screen letters with, so a test can prove they match. */
    java.util.List<String> faceFamiliesForTest() {
        return java.util.List.of(large.family(), font.family(), small.family());
    }

    /** Where a row is drawn, for the tests that click one. */
    static Rectangle rowBounds(int index) {
        return new Rectangle(TABLE_X, TABLE_Y + index * (ROW_HEIGHT + ROW_GAP),
                TABLE_WIDTH, ROW_HEIGHT);
    }

    /** Where a row's right-hand button is. */
    static Rectangle actionBounds(int index) {
        return new Rectangle(TABLE_X + TABLE_WIDTH - ACTION_WIDTH - 6,
                TABLE_Y + index * (ROW_HEIGHT + ROW_GAP) + 4,
                ACTION_WIDTH, ROW_HEIGHT - 8);
    }

    /** Where the explicit Move control is drawn for a playing seat. */
    static Rectangle moveBounds(int index) {
        Rectangle action = actionBounds(index);
        return new Rectangle(action.x, action.y, MOVE_WIDTH, action.height);
    }

    /** Where a row's race button is. */
    static Rectangle raceBounds(int index) {
        return new Rectangle(TABLE_X + TABLE_WIDTH - ACTION_WIDTH - RACE_WIDTH - 12,
                TABLE_Y + index * (ROW_HEIGHT + ROW_GAP) + 4,
                RACE_WIDTH, ROW_HEIGHT - 8);
    }

    /** Where a playing row's explicit team control is drawn. */
    static Rectangle teamBounds(int index) {
        return new Rectangle(TABLE_X + TEAM_COLUMN - 4,
                TABLE_Y + index * (ROW_HEIGHT + ROW_GAP) + 4,
                84, ROW_HEIGHT - 8);
    }

    /** Where the Start button is. */
    static Rectangle startBounds() {
        return new Rectangle(TABLE_X, FOOT_Y, 160, FOOT_HEIGHT);
    }

    /** Where the synchronized game-template control is drawn. */
    static Rectangle templateBounds() {
        return new Rectangle(TABLE_X + 180, FOOT_Y, 200, FOOT_HEIGHT);
    }

    /** Where Cancel, or the build-mismatch update action, is drawn. */
    static Rectangle cancelBounds() {
        return new Rectangle(TABLE_X + TABLE_WIDTH - 160, FOOT_Y, 160, FOOT_HEIGHT);
    }

    /** Drives one click at a point in design pixels, for tests. */
    boolean clickDesign(int x, int y) {
        for (Hit hit : List.copyOf(hits)) {
            if (hit.where().contains(x, y)) {
                hit.action().run();
                return true;
            }
        }
        return false;
    }

    /** Which colour slot occupies a visual row. */
    int slotAtRowForTest(int row) {
        return lobby.state().slots().get(row).index();
    }

    /** Lays the screen out without a window, so a test can look at it. */
    BufferedImage render() {
        BufferedImage image = new BufferedImage(DESIGN_WIDTH, DESIGN_HEIGHT,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = image.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        drawDesign(g2);
        g2.dispose();
        return image;
    }
}
