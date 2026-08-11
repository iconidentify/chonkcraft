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
import javax.swing.Timer;
import net.chonkbase.chonkcraft.engine.GameData;

/** Honest progress and recovery while an online room is being allocated. */
final class OnlineHostScreen extends JPanel {

    interface Listener {
        void onRetry();

        void onLocal();

        void onCancel();
    }

    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    /** Longer than the API and relay timeouts combined, but never an endless spinner. */
    private static final int CONNECTION_DEADLINE_MILLIS = 20_000;
    private final GameFont large;
    private final GameFont font;
    private final GameFont small;
    private final String map;
    private final Listener listener;
    private final List<Hit> hits = new ArrayList<>();
    private boolean failed;
    private String detail = "Opening a secure room and relay...";
    private long attemptSequence;
    private long activeAttempt;
    private boolean attemptPending;
    private Timer deadline;
    private BufferedImage design;
    private BufferedImage scaleCache;

    private record Hit(Rectangle where, Runnable action) {
    }

    OnlineHostScreen(GameData data, String map, Listener listener) {
        large = data == null ? null : GameFont.load(data, GameFont.Face.LARGE);
        font = data == null ? null : GameFont.load(data, GameFont.Face.GAME);
        small = data == null ? null : GameFont.load(data, GameFont.Face.SMALL);
        this.map = map;
        this.listener = listener;
        setFocusable(true);
        setBackground(Color.BLACK);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                Point at = toDesign(event.getPoint());
                for (Hit hit : List.copyOf(hits)) {
                    if (hit.where().contains(at)) {
                        hit.action().run();
                        return;
                    }
                }
            }
        });
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    listener.onCancel();
                }
            }
        });
    }

    /** Starts a uniquely identified attempt so stale workers cannot replace a newer screen. */
    long connecting() {
        stopDeadline();
        activeAttempt = ++attemptSequence;
        attemptPending = true;
        failed = false;
        detail = "Creating a room...";
        long attempt = activeAttempt;
        deadline = new Timer(CONNECTION_DEADLINE_MILLIS, event -> failed(attempt,
                "The online service did not answer within 20 seconds."));
        deadline.setRepeats(false);
        deadline.start();
        repaint();
        return attempt;
    }

    /** Advances only the attempt still represented by this screen. */
    void progress(long attempt, String message) {
        if (activeAttempt != attempt || !attemptPending) {
            return;
        }
        detail = message;
        repaint();
    }

    /** Claims the screen for the lobby transition, rejecting cancelled or timed-out workers. */
    boolean complete(long attempt) {
        if (activeAttempt != attempt || !attemptPending) {
            return false;
        }
        attemptPending = false;
        stopDeadline();
        return true;
    }

    /** Invalidates an in-flight worker before leaving this screen. */
    void cancelAttempt() {
        attemptPending = false;
        activeAttempt = ++attemptSequence;
        stopDeadline();
    }

    void failed(String message) {
        attemptPending = false;
        stopDeadline();
        showFailure(message);
    }

    /** Reports a failure only when it belongs to the attempt the player still sees. */
    void failed(long attempt, String message) {
        if (activeAttempt != attempt) {
            return;
        }
        attemptPending = false;
        stopDeadline();
        showFailure(message);
    }

    private void showFailure(String message) {
        failed = true;
        detail = message == null || message.isBlank()
                ? "The online service could not be reached." : message;
        repaint();
    }

    private void stopDeadline() {
        if (deadline != null) {
            deadline.stop();
            deadline = null;
        }
    }

    boolean failedForTest() {
        return failed;
    }

    String detailForTest() {
        return detail;
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
        large.drawCentred(g2, failed ? "Online Service Unavailable" : "Creating Online Game",
                WIDTH / 2, 40, failed ? GameFont.Ink.RED : GameFont.Ink.YELLOW);
        font.drawCentred(g2, font.fitted(map, WIDTH - 120), WIDTH / 2, 82,
                GameFont.Ink.WHITE);
        PanelArt.sunken(g2, 72, 132, WIDTH - 144, 150, StoneTexture.Tint.STONE);
        font.drawCentred(g2, failed ? "Your game was not published." : "Contacting ChonkCraft",
                WIDTH / 2, 170, failed ? GameFont.Ink.RED : GameFont.Ink.YELLOW);
        small.drawCentred(g2, small.fitted(detail, WIDTH - 190), WIDTH / 2, 214,
                GameFont.Ink.GREY);
        if (failed) {
            small.drawCentred(g2, "You can retry or host directly without the service.",
                    WIDTH / 2, 244, GameFont.Ink.GREY);
            button(g2, 72, 318, 150, 34, "Try Again", listener::onRetry);
            button(g2, 245, 318, 150, 34, "Host Direct", listener::onLocal);
        }
        button(g2, 418, 318, 150, 34, "Back (Esc)", listener::onCancel);
    }

    private void button(Graphics2D g2, int x, int y, int width, int height, String text,
            Runnable action) {
        PanelArt.panel(g2, x, y, width, height, StoneTexture.Tint.SLATE);
        font.drawCentred(g2, text, x + width / 2, y + (height - font.height()) / 2,
                GameFont.Ink.WHITE);
        hits.add(new Hit(new Rectangle(x, y, width, height), action));
    }

    private Point toDesign(Point screen) {
        Rectangle fitted = PixelScaler.fit(WIDTH, HEIGHT, getWidth(), getHeight(), false);
        if (fitted.width <= 0 || fitted.height <= 0) {
            return screen;
        }
        return new Point((int) ((screen.x - fitted.x) * (double) WIDTH / fitted.width),
                (int) ((screen.y - fitted.y) * (double) HEIGHT / fitted.height));
    }
}
