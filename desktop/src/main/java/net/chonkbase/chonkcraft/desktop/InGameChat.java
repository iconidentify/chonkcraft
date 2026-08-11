package net.chonkbase.chonkcraft.desktop;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.KeyEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.chonkbase.chonkcraft.engine.network.NetworkGame;
import net.chonkbase.chonkcraft.engine.network.NetworkSession;

/**
 * Warcraft II's in-game message line, plus the roster needed to control it.
 *
 * <p>The original interaction remains the centre of it: Enter opens the line,
 * Enter sends, Escape cancels, and the Messages control chooses which players
 * receive the next line. The roster makes those recipients legible and adds a
 * local mute; muting never changes network or simulation state for anybody
 * else.
 */
final class InGameChat {

    private static final int BUTTON_WIDTH = 96;
    private static final int BUTTON_HEIGHT = 23;
    private static final int PANEL_WIDTH = 248;
    private static final int ROW_HEIGHT = 24;
    private static final long MESSAGE_MILLIS = 12_000L;
    private static final int MAX_VISIBLE_LINES = 6;

    private final NetworkGame network;
    private final GameFont font;
    private final net.chonkbase.chonkcraft.engine.sound.GameAudio audio;
    private final Set<Integer> muted = new HashSet<>();
    private final ArrayDeque<Line> lines = new ArrayDeque<>();
    private final StringBuilder typed = new StringBuilder();
    private final List<Hit> hits = new ArrayList<>();

    private boolean rosterOpen;
    private boolean typing;
    private int recipientMask;
    private Rectangle button = new Rectangle();
    private Rectangle panel = new Rectangle();

    private record Line(int player, String name, String text, boolean local, long at) {}

    private record Hit(Rectangle bounds, Runnable action) {}

    InGameChat(NetworkGame network, GameFont font,
            net.chonkbase.chonkcraft.engine.sound.GameAudio audio) {
        this.network = network;
        this.font = font;
        this.audio = audio;
        this.recipientMask = network.everyoneChatMask();
    }

    /** Takes a network event, unless that player has been muted locally. */
    void accept(NetworkGame.ChatEvent event) {
        if (!event.local() && muted.contains(event.player())) {
            return;
        }
        lines.addLast(new Line(event.player(), event.playerName(), event.text(), event.local(),
                System.currentTimeMillis()));
        while (lines.size() > 40) {
            lines.removeFirst();
        }
        if (!event.local() && audio != null) {
            audio.playUi("chat-message");
        }
    }

    /** Enter/typing owns the keyboard until the line is sent or cancelled. */
    boolean keyPressed(KeyEvent event) {
        int code = event.getKeyCode();
        if (!typing) {
            if (code != KeyEvent.VK_ENTER) {
                return false;
            }
            if (availableRecipients() == 0) {
                rosterOpen = true;
                return true;
            }
            typing = true;
            typed.setLength(0);
            return true;
        }

        if (code == KeyEvent.VK_ESCAPE) {
            typing = false;
            typed.setLength(0);
            return true;
        }
        if (code == KeyEvent.VK_ENTER) {
            String message = NetworkSession.sanitizeChat(typed.toString());
            if (!message.isEmpty()) {
                network.sendChat(availableRecipients(), message);
            }
            typed.setLength(0);
            typing = false;
            return true;
        }
        if (code == KeyEvent.VK_BACK_SPACE) {
            if (!typed.isEmpty()) {
                typed.setLength(typed.offsetByCodePoints(typed.length(), -1));
            }
            return true;
        }
        boolean command = event.isControlDown() || event.isMetaDown();
        if (command && code == KeyEvent.VK_V) {
            paste();
            return true;
        }
        if (command || event.isAltDown()) {
            return true;
        }
        char character = event.getKeyChar();
        if (!Character.isISOControl(character)) {
            append(String.valueOf(character));
        }
        return true;
    }

    private void paste() {
        try {
            Object value = Toolkit.getDefaultToolkit().getSystemClipboard()
                    .getData(DataFlavor.stringFlavor);
            if (value instanceof String text) {
                append(text);
            }
        } catch (Exception ignored) {
            // A locked clipboard should not close the line or the match.
        }
    }

    private void append(String addition) {
        if (addition == null) {
            return;
        }
        for (int offset = 0; offset < addition.length();) {
            int codePoint = addition.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint) && codePoint != '\t'
                    && codePoint != '\n' && codePoint != '\r') {
                continue;
            }
            if (Character.isWhitespace(codePoint)) {
                if (typed.isEmpty() || Character.isWhitespace(
                        typed.codePointBefore(typed.length()))) {
                    continue;
                }
                codePoint = ' ';
            }
            int before = typed.length();
            typed.appendCodePoint(codePoint);
            if (typed.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    > NetworkSession.MAX_CHAT_UTF8_BYTES) {
                typed.setLength(before);
                break;
            }
        }
    }

    /** Handles the Messages control and the recipient/mute roster. */
    boolean click(int designX, int designY) {
        if (button.contains(designX, designY)) {
            rosterOpen = !rosterOpen;
            return true;
        }
        if (!rosterOpen) {
            return false;
        }
        for (Hit hit : List.copyOf(hits)) {
            if (hit.bounds().contains(designX, designY)) {
                hit.action().run();
                return true;
            }
        }
        if (!panel.contains(designX, designY)) {
            rosterOpen = false;
        }
        return true;
    }

    void draw(Graphics2D g2, int mapX, int mapY, int mapWidth, int mapHeight) {
        hits.clear();
        recipientMask &= network.everyoneChatMask();

        int buttonX = mapX + mapWidth - BUTTON_WIDTH - 7;
        int buttonY = mapY + 7;
        button = new Rectangle(buttonX, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT);
        PanelArt.panel(g2, buttonX, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT,
                StoneTexture.Tint.SLATE);
        font.drawCentred(g2, "MESSAGES", buttonX + BUTTON_WIDTH / 2,
                buttonY + (BUTTON_HEIGHT - font.height()) / 2, GameFont.Ink.WHITE);

        int lineWidth = mapWidth - BUTTON_WIDTH - 38;
        if (rosterOpen) {
            int panelX = Math.max(mapX + 6, buttonX + BUTTON_WIDTH - PANEL_WIDTH);
            lineWidth = panelX - (mapX + 10) - 8;
        }
        drawLines(g2, mapX + 10, mapY + 11, Math.max(80, lineWidth));
        if (typing) {
            drawInput(g2, mapX + 10, mapY + mapHeight - 42, mapWidth - 20);
        }
        if (rosterOpen) {
            drawRoster(g2, Math.max(mapX + 6, buttonX + BUTTON_WIDTH - PANEL_WIDTH),
                    buttonY + BUTTON_HEIGHT + 4, mapY + mapHeight - 6);
        }
    }

    private void drawLines(Graphics2D g2, int x, int y, int width) {
        long now = System.currentTimeMillis();
        lines.removeIf(line -> !rosterOpen && !typing && now - line.at() > MESSAGE_MILLIS);
        List<Line> visible = lines.stream().skip(Math.max(0, lines.size() - MAX_VISIBLE_LINES))
                .toList();
        if (visible.isEmpty()) {
            return;
        }
        int height = visible.size() * (font.height() + 3) + 8;
        g2.setColor(new Color(0, 0, 0, 154));
        g2.fillRoundRect(x - 5, y - 5, width + 10, height, 6, 6);
        int lineY = y;
        for (Line line : visible) {
            g2.setColor(PlayerColours.of(line.player()));
            g2.fillRect(x, lineY + 4, 7, 7);
            String prefix = line.name() + ": ";
            font.draw(g2, font.fitted(prefix + line.text(), width - 14), x + 12, lineY,
                    line.local() ? GameFont.Ink.YELLOW : GameFont.Ink.WHITE);
            lineY += font.height() + 3;
        }
    }

    private void drawInput(Graphics2D g2, int x, int y, int width) {
        PanelArt.sunken(g2, x, y, width, 31, StoneTexture.Tint.SLATE);
        String audience = audienceName();
        String prefix = "To " + audience + ": ";
        font.draw(g2, prefix, x + 9, y + (31 - font.height()) / 2, GameFont.Ink.YELLOW);
        int left = x + 13 + font.widthOf(prefix);
        String value = font.fitted(typed + "|", Math.max(1, x + width - left - 9));
        font.draw(g2, value, left, y + (31 - font.height()) / 2, GameFont.Ink.WHITE);
    }

    private void drawRoster(Graphics2D g2, int x, int y, int bottom) {
        List<NetworkGame.PlayerPresence> players = network.connectedPlayers();
        int height = 72 + players.size() * ROW_HEIGHT + 28;
        height = Math.min(height, Math.max(100, bottom - y));
        panel = new Rectangle(x, y, PANEL_WIDTH, height);
        PanelArt.panel(g2, x, y, PANEL_WIDTH, height, StoneTexture.Tint.SLATE);
        font.draw(g2, "CONNECTED PLAYERS", x + 12, y + 10, GameFont.Ink.YELLOW);

        int controlsY = y + 33;
        int half = (PANEL_WIDTH - 30) / 2;
        audienceButton(g2, new Rectangle(x + 10, controlsY, half, 23), "EVERYONE",
                () -> recipientMask = network.everyoneChatMask());
        audienceButton(g2, new Rectangle(x + 20 + half, controlsY, half, 23), "ALLIES",
                () -> recipientMask = network.alliesChatMask());

        int rowY = controlsY + 31;
        for (NetworkGame.PlayerPresence player : players) {
            if (rowY + ROW_HEIGHT > y + height - 24) {
                break;
            }
            g2.setColor(new Color(8, 13, 18, 150));
            g2.fillRect(x + 10, rowY, PANEL_WIDTH - 20, ROW_HEIGHT - 2);
            g2.setColor(PlayerColours.of(player.player()));
            g2.fillOval(x + 16, rowY + 7, 9, 9);

            boolean selected = (recipientMask & (1 << player.player())) != 0;
            String marker = player.local() ? "" : selected ? "[X]" : "[ ]";
            if (!marker.isEmpty()) {
                font.draw(g2, marker, x + 31, rowY + 4,
                        selected ? GameFont.Ink.YELLOW : GameFont.Ink.GREY);
            }
            String suffix = player.host() ? " (host)" : player.allied() && !player.local()
                    ? " (ally)" : "";
            String label = player.name() + (player.local() ? " (you)" : "") + suffix;
            int nameX = x + (player.local() ? 32 : 61);
            int muteWidth = player.local() ? 0 : 52;
            font.draw(g2, font.fitted(label,
                    PANEL_WIDTH - (nameX - x) - muteWidth - 15), nameX, rowY + 4,
                    GameFont.Ink.WHITE);

            if (!player.local()) {
                Rectangle select = new Rectangle(x + 10, rowY, PANEL_WIDTH - 78,
                        ROW_HEIGHT - 2);
                hits.add(new Hit(select,
                        () -> recipientMask ^= 1 << player.player()));
                Rectangle mute = new Rectangle(x + PANEL_WIDTH - 66, rowY + 2, 54,
                        ROW_HEIGHT - 6);
                PanelArt.sunken(g2, mute.x, mute.y, mute.width, mute.height,
                        StoneTexture.Tint.SLATE);
                boolean isMuted = muted.contains(player.player());
                font.drawCentred(g2, isMuted ? "MUTED" : "MUTE", mute.x + mute.width / 2,
                        mute.y + (mute.height - font.height()) / 2,
                        isMuted ? GameFont.Ink.RED : GameFont.Ink.GREY);
                hits.add(new Hit(mute, () -> {
                    if (!muted.add(player.player())) {
                        muted.remove(player.player());
                    }
                }));
            }
            rowY += ROW_HEIGHT;
        }
        font.drawCentred(g2, "ENTER TO CHAT", x + PANEL_WIDTH / 2, y + height - 20,
                GameFont.Ink.GREY);
    }

    private void audienceButton(Graphics2D g2, Rectangle bounds, String caption, Runnable action) {
        PanelArt.sunken(g2, bounds.x, bounds.y, bounds.width, bounds.height,
                StoneTexture.Tint.SLATE);
        font.drawCentred(g2, caption, bounds.x + bounds.width / 2,
                bounds.y + (bounds.height - font.height()) / 2, GameFont.Ink.WHITE);
        hits.add(new Hit(bounds, action));
    }

    private int availableRecipients() {
        return recipientMask & network.everyoneChatMask();
    }

    private String audienceName() {
        int selected = availableRecipients();
        if (selected == network.everyoneChatMask()) {
            return "Everyone";
        }
        if (selected != 0 && selected == network.alliesChatMask()) {
            return "Allies";
        }
        int count = Integer.bitCount(selected);
        return count == 1 ? "1 player" : count + " players";
    }

    boolean isTyping() {
        return typing;
    }

    boolean isMuted(int player) {
        return muted.contains(player);
    }

    int recipientMask() {
        return recipientMask;
    }

    int lineCount() {
        return lines.size();
    }
}
