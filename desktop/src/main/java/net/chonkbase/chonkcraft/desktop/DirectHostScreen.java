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
import javax.swing.JPanel;
import net.chonkbase.chonkcraft.engine.GameData;

/** Configures a server-free direct UDP lobby and explains what the router needs. */
final class DirectHostScreen extends JPanel {

    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    private static final Rectangle PORT_FIELD = new Rectangle(238, 142, 164, 34);
    private static final Rectangle HOST_BUTTON = new Rectangle(72, 382, 290, 36);
    private static final Rectangle BACK_BUTTON = new Rectangle(382, 382, 186, 36);

    interface Listener {
        void onHost(int port);

        void onCancel();
    }

    private record Hit(Rectangle where, Runnable action) {
    }

    private final GameFont large;
    private final GameFont font;
    private final GameFont small;
    private final String map;
    private final Listener listener;
    private final List<Hit> hits = new ArrayList<>();
    private String port = Integer.toString(JoinScreen.DEFAULT_PORT);
    private String notice = "";
    private boolean editing;
    private boolean replaceOnType = true;
    private int endpointPort = -1;
    private List<String> endpoints = List.of();
    private BufferedImage design;
    private BufferedImage scaleCache;

    DirectHostScreen(GameData data, String map, Listener listener) {
        large = GameFont.load(data, GameFont.Face.LARGE);
        font = GameFont.load(data, GameFont.Face.GAME);
        small = GameFont.load(data, GameFont.Face.SMALL);
        this.map = map == null ? "" : map;
        this.listener = listener;
        setFocusable(true);
        setBackground(Color.BLACK);
        installInput();
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

    void type(int keyCode, char typed) {
        if (keyCode == KeyEvent.VK_ESCAPE) {
            listener.onCancel();
            return;
        }
        if (keyCode == KeyEvent.VK_ENTER) {
            begin();
            return;
        }
        if (keyCode == KeyEvent.VK_BACK_SPACE) {
            editing = true;
            if (replaceOnType) {
                port = "";
            } else if (!port.isEmpty()) {
                port = port.substring(0, port.length() - 1);
            }
            replaceOnType = false;
            return;
        }
        if (Character.isDigit(typed)) {
            if (replaceOnType) {
                port = "";
            }
            editing = true;
            replaceOnType = false;
            if (port.length() < 5) {
                port += typed;
            }
        }
    }

    private void begin() {
        try {
            int chosen = DirectAddress.parsePort(port);
            notice = "Opening UDP " + chosen + "...";
            listener.onHost(chosen);
        } catch (IllegalArgumentException invalid) {
            notice = invalid.getMessage();
        }
    }

    void showError(String message) {
        notice = message == null ? "" : message;
        repaint();
    }

    int selectedPort() {
        return DirectAddress.parsePort(port);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (design == null) {
            design = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        }
        Graphics2D g2 = design.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        draw(g2);
        g2.dispose();
        scaleCache = PixelScaler.draw((Graphics2D) graphics, design, getWidth(), getHeight(), false,
                scaleCache);
    }

    private void draw(Graphics2D g2) {
        hits.clear();
        g2.setColor(new Color(0x101014));
        g2.fillRect(0, 0, WIDTH, HEIGHT);
        PanelArt.panel(g2, 16, 16, WIDTH - 32, HEIGHT - 32, StoneTexture.Tint.STONE);
        large.drawCentred(g2, "Host Direct Game", WIDTH / 2, 34, GameFont.Ink.YELLOW);
        font.drawCentred(g2, font.fitted(map, WIDTH - 120), WIDTH / 2, 76,
                GameFont.Ink.WHITE);

        PanelArt.sunken(g2, 72, 116, WIDTH - 144, 222, StoneTexture.Tint.STONE);
        small.drawCentred(g2, "No ChonkCraft account or online relay is used.", WIDTH / 2, 126,
                GameFont.Ink.GREY);
        font.draw(g2, "UDP Port", 128, 150, GameFont.Ink.YELLOW);
        PanelArt.sunken(g2, PORT_FIELD.x, PORT_FIELD.y, PORT_FIELD.width, PORT_FIELD.height,
                StoneTexture.Tint.SLATE);
        font.drawCentred(g2, port + (editing ? "|" : ""), PORT_FIELD.x + PORT_FIELD.width / 2,
                PORT_FIELD.y + (PORT_FIELD.height - font.height()) / 2, GameFont.Ink.WHITE);
        hits.add(new Hit(PORT_FIELD, () -> {
            editing = true;
            replaceOnType = true;
        }));

        int chosen = safePort();
        List<String> local = endpoints(chosen);
        String destination = local.isEmpty() ? "this computer" : local.get(0);
        small.draw(g2, "1. Allow ChonkCraft through this computer's firewall.", 102, 204,
                GameFont.Ink.WHITE);
        small.draw(g2, small.fitted("2. In your router, forward UDP "
                + (chosen < 0 ? "PORT" : chosen) + " to " + destination + ".", 432),
                102, 234, GameFont.Ink.WHITE);
        small.draw(g2, small.fitted("3. Share your public IP as public-ip:"
                + (chosen < 0 ? "PORT" : chosen) + ".", 432), 102, 264, GameFont.Ink.WHITE);
        small.draw(g2, small.fitted("Only the host forwards a port. CGNAT connections need"
                + " Online mode.", 432), 102, 304, GameFont.Ink.GREY);

        if (!notice.isEmpty()) {
            small.drawCentred(g2, small.fitted(notice, WIDTH - 150), WIDTH / 2, 350,
                    notice.startsWith("Opening") ? GameFont.Ink.GREY : GameFont.Ink.RED);
        }
        button(g2, HOST_BUTTON, "Open Direct Lobby", this::begin);
        button(g2, BACK_BUTTON, "Back (Esc)", listener::onCancel);
    }

    private int safePort() {
        try {
            return DirectAddress.parsePort(port);
        } catch (IllegalArgumentException invalid) {
            return -1;
        }
    }

    private List<String> endpoints(int chosen) {
        if (chosen < 0) {
            return List.of();
        }
        if (chosen != endpointPort) {
            endpointPort = chosen;
            endpoints = DirectAddress.localEndpoints(chosen);
        }
        return endpoints;
    }

    private void button(Graphics2D g2, Rectangle where, String caption, Runnable action) {
        PanelArt.panel(g2, where.x, where.y, where.width, where.height, StoneTexture.Tint.SLATE);
        font.drawCentred(g2, caption, where.x + where.width / 2,
                where.y + (where.height - font.height()) / 2, GameFont.Ink.WHITE);
        hits.add(new Hit(where, action));
    }

    private Point toDesign(Point screen) {
        Rectangle fitted = PixelScaler.fit(WIDTH, HEIGHT, getWidth(), getHeight(), false);
        if (fitted.width <= 0 || fitted.height <= 0) {
            return screen;
        }
        return new Point((int) ((screen.x - fitted.x) * (double) WIDTH / fitted.width),
                (int) ((screen.y - fitted.y) * (double) HEIGHT / fitted.height));
    }

    BufferedImage render() {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = image.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        draw(g2);
        g2.dispose();
        return image;
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

    static Rectangle portFieldBounds() {
        return new Rectangle(PORT_FIELD);
    }
}
