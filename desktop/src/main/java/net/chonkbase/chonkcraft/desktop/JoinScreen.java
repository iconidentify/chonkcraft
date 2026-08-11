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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.network.GameDiscovery;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingClient;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.GameListing;
import net.chonkbase.chonkcraft.matchmaking.RoomCode;

/** Public game browser, code join, and deliberately separate local-network fallback. */
final class JoinScreen extends JPanel {

    private static final int DESIGN_WIDTH = 640;
    private static final int DESIGN_HEIGHT = 480;
    private static final int PAGE_X = 40;
    private static final int PAGE_WIDTH = DESIGN_WIDTH - PAGE_X * 2;
    private static final int TAB_Y = 68;
    private static final int TAB_WIDTH = 180;
    private static final int TAB_HEIGHT = 28;
    private static final int LIST_Y = 112;
    private static final int ROW_HEIGHT = 32;
    private static final int ROW_GAP = 4;
    private static final int MAX_ROWS = 6;
    private static final int FIELD_Y = 372;
    private static final int FIELD_HEIGHT = 32;
    private static final int ACTION_WIDTH = 154;
    private static final int FOOT_Y = 420;
    private static final int FOOT_HEIGHT = 30;

    enum Page {
        ONLINE,
        LOCAL
    }

    interface Listener {
        /** Join a public or code-addressed room through the central relay. */
        default void onJoinOnline(String code) {
        }

        /** Join an explicitly addressed host on the local/direct UDP transport. */
        void onJoin(String host, int port);

        void onCancel();

        /** Leave the game process so its launcher can fetch the required release. */
        default void onUpdateRequired() {
            onCancel();
        }
    }

    private final GameFont large;
    private final GameFont font;
    private final GameFont small;
    private final Listener listener;
    private final GameDiscovery discovery;
    private final MatchmakingClient matchmaking;
    private final boolean directOnly;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final List<Hit> hits = new ArrayList<>();

    private volatile List<GameListing> online = List.of();
    private List<GameDiscovery.Game> local = List.of();
    private Page page = Page.ONLINE;
    private String typed = "";
    private String notice = "";
    private boolean updateRequired;
    private String service = "Connecting to ChonkCraft...";
    private long lastRefresh;
    private BufferedImage design;
    private BufferedImage scaleCache;

    private record Hit(Rectangle where, Runnable action) {
    }

    JoinScreen(GameData data, GameDiscovery discovery, Listener listener) {
        this(data, discovery, null, false, listener);
    }

    JoinScreen(GameData data, GameDiscovery discovery, MatchmakingClient matchmaking,
            Listener listener) {
        this(data, discovery, matchmaking, false, listener);
    }

    JoinScreen(GameData data, GameDiscovery discovery, MatchmakingClient matchmaking,
            boolean directOnly, Listener listener) {
        this.large = GameFont.load(data, GameFont.Face.LARGE);
        this.font = GameFont.load(data, GameFont.Face.GAME);
        this.small = GameFont.load(data, GameFont.Face.SMALL);
        this.discovery = discovery;
        this.matchmaking = matchmaking;
        this.directOnly = directOnly;
        this.listener = listener;
        if (directOnly) {
            page = Page.LOCAL;
            service = "";
        }
        setFocusable(true);
        setBackground(Color.BLACK);
        installInput();
    }

    /** Refreshes LAN immediately and the internet directory at a calm three-second cadence. */
    void tick() {
        if (discovery != null) {
            local = discovery.games();
        }
        long now = System.currentTimeMillis();
        if (matchmaking != null && now - lastRefresh >= 3_000 && refreshing.compareAndSet(false, true)) {
            lastRefresh = now;
            Thread.startVirtualThread(() -> {
                try {
                    var result = matchmaking.games();
                    online = result.games();
                    service = online.isEmpty() ? "Online · no waiting games" : "Online · just refreshed";
                } catch (Exception unavailable) {
                    service = "Game service unavailable · local play still works";
                } finally {
                    refreshing.set(false);
                    SwingUtilities.invokeLater(this::repaint);
                }
            });
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
                type(event.getKeyCode(), event.getKeyChar());
                repaint();
            }
        });
    }

    void type(int keyCode, char typedChar) {
        switch (keyCode) {
            case KeyEvent.VK_ESCAPE -> {
                listener.onCancel();
                return;
            }
            case KeyEvent.VK_ENTER -> {
                connect();
                return;
            }
            case KeyEvent.VK_BACK_SPACE -> {
                if (!typed.isEmpty()) {
                    typed = typed.substring(0, typed.length() - 1);
                }
                return;
            }
            default -> {
            }
        }
        boolean accepted = page == Page.ONLINE
                ? Character.isLetterOrDigit(typedChar) || typedChar == '-' || typedChar == ' '
                : Character.isLetterOrDigit(typedChar) || ".:-[]%_".indexOf(typedChar) >= 0;
        int maximum = page == Page.ONLINE ? 10 : 80;
        if (typed.length() < maximum && accepted) {
            typed += typedChar;
        }
    }

    String address() {
        return typed;
    }

    private void connect() {
        if (page == Page.ONLINE) {
            try {
                String code = RoomCode.normalize(typed);
                notice = "Joining " + code + "...";
                listener.onJoinOnline(code);
            } catch (IllegalArgumentException invalid) {
                notice = invalid.getMessage();
            }
            return;
        }
        try {
            DirectAddress address = DirectAddress.parse(typed, DEFAULT_PORT);
            notice = "Contacting " + address.display() + "...";
            listener.onJoin(address.host(), address.port());
        } catch (IllegalArgumentException invalid) {
            notice = invalid.getMessage();
        }
    }

    static final int DEFAULT_PORT = 7100;

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (design == null) {
            design = new BufferedImage(DESIGN_WIDTH, DESIGN_HEIGHT, BufferedImage.TYPE_INT_RGB);
        }
        Graphics2D g2 = design.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        drawDesign(g2);
        g2.dispose();
        scaleCache = PixelScaler.draw((Graphics2D) graphics, design, getWidth(), getHeight(), false,
                scaleCache);
    }

    private void drawDesign(Graphics2D g2) {
        hits.clear();
        g2.setColor(new Color(0x101014));
        g2.fillRect(0, 0, DESIGN_WIDTH, DESIGN_HEIGHT);
        PanelArt.panel(g2, 16, 16, DESIGN_WIDTH - 32, DESIGN_HEIGHT - 32,
                StoneTexture.Tint.STONE);
        large.drawCentred(g2, directOnly ? "Join Direct Game" : "Join a Game",
                DESIGN_WIDTH / 2, 28, GameFont.Ink.YELLOW);

        if (!directOnly) {
            tab(g2, PAGE_X, "Online", Page.ONLINE);
            tab(g2, PAGE_X + TAB_WIDTH + 8, "Direct / LAN", Page.LOCAL);
        }
        small.draw(g2, page == Page.ONLINE ? service : localStatus(), PAGE_X,
                directOnly ? 82 : 100,
                service.contains("unavailable") && page == Page.ONLINE
                        ? GameFont.Ink.RED : GameFont.Ink.GREY);

        if (page == Page.ONLINE) {
            drawOnline(g2);
        } else {
            drawLocal(g2);
        }

        if (!notice.isEmpty()) {
            int noticeWidth = updateRequired ? PAGE_WIDTH : PAGE_WIDTH - ACTION_WIDTH - 14;
            int noticeY = updateRequired
                    ? FOOT_Y - small.height() - 4
                    : FOOT_Y + (FOOT_HEIGHT - small.height()) / 2;
            small.draw(g2, small.fitted(notice, noticeWidth), PAGE_X, noticeY,
                    notice.startsWith("Joining") ? GameFont.Ink.GREY : GameFont.Ink.RED);
        }
        if (updateRequired) {
            button(g2, PAGE_X + PAGE_WIDTH - ACTION_WIDTH * 2 - 10, FOOT_Y,
                    ACTION_WIDTH, FOOT_HEIGHT, "Quit to Update", listener::onUpdateRequired);
        } else if (page == Page.ONLINE) {
            button(g2, PAGE_X + PAGE_WIDTH - ACTION_WIDTH * 2 - 10, FOOT_Y,
                    ACTION_WIDTH, FOOT_HEIGHT, "Refresh", () -> lastRefresh = 0);
        }
        button(g2, PAGE_X + PAGE_WIDTH - ACTION_WIDTH, FOOT_Y, ACTION_WIDTH, FOOT_HEIGHT,
                "Back (Esc)", listener::onCancel);
    }

    private void tab(Graphics2D g2, int x, String caption, Page target) {
        PanelArt.panel(g2, x, TAB_Y, TAB_WIDTH, TAB_HEIGHT,
                page == target ? StoneTexture.Tint.SLATE : StoneTexture.Tint.STONE);
        font.drawCentred(g2, caption, x + TAB_WIDTH / 2,
                TAB_Y + (TAB_HEIGHT - font.height()) / 2,
                page == target ? GameFont.Ink.YELLOW : GameFont.Ink.GREY);
        hits.add(new Hit(new Rectangle(x, TAB_Y, TAB_WIDTH, TAB_HEIGHT), () -> switchPage(target)));
    }

    private void switchPage(Page target) {
        page = target;
        typed = "";
        notice = "";
    }

    private void drawOnline(Graphics2D g2) {
        for (int i = 0; i < MAX_ROWS; i++) {
            int y = LIST_Y + i * (ROW_HEIGHT + ROW_GAP);
            PanelArt.sunken(g2, PAGE_X, y, PAGE_WIDTH, ROW_HEIGHT, StoneTexture.Tint.STONE);
            if (i >= online.size()) {
                continue;
            }
            GameListing game = online.get(i);
            int textY = y + (ROW_HEIGHT - font.height()) / 2;
            font.draw(g2, font.fitted(game.name(), 170), PAGE_X + 10, textY,
                    GameFont.Ink.WHITE);
            font.draw(g2, font.fitted(game.map(), 160), PAGE_X + 190, textY,
                    GameFont.Ink.GREY);
            font.draw(g2, game.code(), PAGE_X + 382, textY, GameFont.Ink.YELLOW);
            String count = game.players() + "/" + game.capacity();
            font.draw(g2, count, PAGE_X + PAGE_WIDTH - font.widthOf(count) - 12, textY,
                    game.hasRoom() ? GameFont.Ink.WHITE : GameFont.Ink.RED);
            if (game.hasRoom()) {
                hits.add(new Hit(new Rectangle(PAGE_X, y, PAGE_WIDTH, ROW_HEIGHT),
                        () -> listener.onJoinOnline(game.code())));
            }
        }
        small.draw(g2, "Game code", PAGE_X, FIELD_Y - 16, GameFont.Ink.GREY);
        field(g2, "ABC123");
        button(g2, PAGE_X + PAGE_WIDTH - ACTION_WIDTH, FIELD_Y, ACTION_WIDTH, FIELD_HEIGHT,
                "Join by Code", this::connect);
    }

    private void drawLocal(Graphics2D g2) {
        for (int i = 0; i < MAX_ROWS; i++) {
            int y = LIST_Y + i * (ROW_HEIGHT + ROW_GAP);
            PanelArt.sunken(g2, PAGE_X, y, PAGE_WIDTH, ROW_HEIGHT, StoneTexture.Tint.STONE);
            if (i >= local.size()) {
                continue;
            }
            GameDiscovery.Game game = local.get(i);
            int textY = y + (ROW_HEIGHT - font.height()) / 2;
            font.draw(g2, font.fitted(game.name(), 180), PAGE_X + 10, textY,
                    GameFont.Ink.WHITE);
            font.draw(g2, font.fitted(game.map(), 230), PAGE_X + 210, textY,
                    GameFont.Ink.GREY);
            String count = game.players() + "/" + game.capacity();
            String status = game.isCompatible() ? count : "Update";
            font.draw(g2, status, PAGE_X + PAGE_WIDTH - font.widthOf(status) - 12, textY,
                    game.hasRoom() && game.isCompatible()
                            ? GameFont.Ink.YELLOW : GameFont.Ink.RED);
            if (!game.isCompatible()) {
                hits.add(new Hit(new Rectangle(PAGE_X, y, PAGE_WIDTH, ROW_HEIGHT),
                        () -> showUpdateRequired("Required: "
                                + MatchmakingProtocol.normalizeBuild(game.build())
                                + " · Your game: " + MatchmakingProtocol.gameBuild())));
            } else if (game.hasRoom()) {
                hits.add(new Hit(new Rectangle(PAGE_X, y, PAGE_WIDTH, ROW_HEIGHT),
                        () -> listener.onJoin(game.host(), game.port())));
            }
        }
        small.draw(g2, "Host address (default UDP 7100)", PAGE_X, FIELD_Y - 16,
                GameFont.Ink.GREY);
        field(g2, "203.0.113.20:7100");
        button(g2, PAGE_X + PAGE_WIDTH - ACTION_WIDTH, FIELD_Y, ACTION_WIDTH, FIELD_HEIGHT,
                "Connect", this::connect);
    }

    private void field(Graphics2D g2, String placeholder) {
        int width = PAGE_WIDTH - ACTION_WIDTH - 10;
        PanelArt.sunken(g2, PAGE_X, FIELD_Y, width, FIELD_HEIGHT, StoneTexture.Tint.SLATE);
        String value = typed.isEmpty() ? placeholder : typed + "|";
        font.draw(g2, font.fitted(value, width - 20), PAGE_X + 10,
                FIELD_Y + (FIELD_HEIGHT - font.height()) / 2,
                typed.isEmpty() ? GameFont.Ink.GREY : GameFont.Ink.WHITE);
    }

    private String localStatus() {
        return local.isEmpty() ? "Enter a public IP, hostname, or discover a LAN game."
                : local.size() + (local.size() == 1 ? " LAN game" : " LAN games")
                        + " · direct address also available";
    }

    private void button(Graphics2D g2, int x, int y, int width, int height, String caption,
            Runnable action) {
        PanelArt.panel(g2, x, y, width, height, StoneTexture.Tint.SLATE);
        font.drawCentred(g2, caption, x + width / 2, y + (height - font.height()) / 2,
                GameFont.Ink.WHITE);
        hits.add(new Hit(new Rectangle(x, y, width, height), action));
    }

    private Point toDesign(Point screen) {
        Rectangle fitted = PixelScaler.fit(DESIGN_WIDTH, DESIGN_HEIGHT, getWidth(), getHeight(), false);
        if (fitted.width <= 0 || fitted.height <= 0) {
            return screen;
        }
        return new Point((int) ((screen.x - fitted.x) * (double) DESIGN_WIDTH / fitted.width),
                (int) ((screen.y - fitted.y) * (double) DESIGN_HEIGHT / fitted.height));
    }

    boolean clickDesign(int x, int y) {
        for (Hit hit : List.copyOf(hits)) {
            if (hit.where().contains(x, y)) {
                hit.action().run();
                return true;
            }
        }
        return false;
    }

    BufferedImage render() {
        BufferedImage image = new BufferedImage(DESIGN_WIDTH, DESIGN_HEIGHT,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = image.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        drawDesign(g2);
        g2.dispose();
        return image;
    }

    void setFound(List<GameDiscovery.Game> games) {
        local = List.copyOf(games);
    }

    void setOnlineGames(List<GameListing> games) {
        online = List.copyOf(games);
        service = online.isEmpty() ? "Online · no waiting games" : "Online · just refreshed";
    }

    void showNotice(String message) {
        updateRequired = false;
        notice = message == null ? "" : message;
        repaint();
    }

    void showUpdateRequired(String message) {
        updateRequired = true;
        notice = message == null ? "A newer ChonkCraft game is required." : message;
        repaint();
    }

    void showLocalForTest() {
        switchPage(Page.LOCAL);
    }

    boolean isDirectOnlyForTest() {
        return directOnly;
    }

    boolean updateRequiredForTest() {
        return updateRequired;
    }

    List<String> faceFamiliesForTest() {
        return List.of(large.family(), font.family(), small.family());
    }

    static Rectangle fieldBounds() {
        return new Rectangle(PAGE_X, FIELD_Y, PAGE_WIDTH - ACTION_WIDTH - 10, FIELD_HEIGHT);
    }

    static Rectangle connectBounds() {
        return new Rectangle(PAGE_X + PAGE_WIDTH - ACTION_WIDTH, FIELD_Y,
                ACTION_WIDTH, FIELD_HEIGHT);
    }

    static Rectangle cancelBounds() {
        return new Rectangle(PAGE_X + PAGE_WIDTH - ACTION_WIDTH, FOOT_Y,
                ACTION_WIDTH, FOOT_HEIGHT);
    }

    static Rectangle updateBounds() {
        return new Rectangle(PAGE_X + PAGE_WIDTH - ACTION_WIDTH * 2 - 10, FOOT_Y,
                ACTION_WIDTH, FOOT_HEIGHT);
    }

    static int visibleRows() {
        return MAX_ROWS;
    }

    static Rectangle rowBounds(int index) {
        return new Rectangle(PAGE_X, LIST_Y + index * (ROW_HEIGHT + ROW_GAP),
                PAGE_WIDTH, ROW_HEIGHT);
    }
}
