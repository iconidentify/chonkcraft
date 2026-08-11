package net.chonkbase.chonkcraft.desktop;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
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
import net.chonkbase.chonkcraft.data.graphic.IndexedImage;
import net.chonkbase.chonkcraft.data.graphic.Palette;
import net.chonkbase.chonkcraft.engine.GameData;

/**
 * The mission briefing, shown before a campaign mission and after it ends.
 *
 * <p>Implements {@code Briefing} in {@code scripts/menus/objectives.legacy-declaration},
 * reduced to the part that matters: the prose Blizzard wrote, on the
 * background the game shipped, with a way onward.
 *
 * <p>The text comes out of {@code strdat.war} rather than from anything
 * written here, which is the whole point. A briefing that says the outpost
 * needs four farms is the same sentence the mission's victory condition
 * checks, and the two agree because neither was retyped.
 *
 * <p>So does the picture it is read off, and so does the voice reading it.
 * Every mission script names a background and a list of voice-over files in
 * the same {@code Briefing} call as the prose, and for a long time this screen
 * took the prose and left the other two behind: every briefing in the game
 * came up on the main menu's blank scroll, in silence.
 */
final class BriefingScreen extends JPanel {

    private static final int DESIGN_WIDTH = 640;
    private static final int DESIGN_HEIGHT = 480;

    /**
     * The column the prose is set in.
     *
     * <p>Upstream puts a 320-wide scrolling widget at (70, 80), which is the
     * left-hand page of the illustrated book the briefings are drawn on. This
     * does not scroll, so it takes the same column and lets it run further
     * down; the width is what matters, because it is what keeps the prose off
     * the picture's right-hand half.
     */
    private static final int TEXT_LEFT = 62;
    private static final int TEXT_TOP = 78;
    private static final int TEXT_WIDTH = 320;

    /** Kept so the voice-over can be looked up while the screen is up. */
    private final GameData data;

    /**
     * The game's lettering, as every other screen uses.
     *
     * <p>This screen used to set its heading and its prose in
     * {@code getFont()}: the look and feel's own face, which is a sans on most
     * machines and a different sans on the rest. It was the one screen in the
     * program whose text was not the shipped serif, and going from the menu to
     * a briefing changed typeface mid-sentence.
     */
    private final GameFont headingFont;

    private final GameFont bodyFont;

    private final BufferedImage background;
    private final BufferedImage button;

    private final String heading;
    private final String body;
    private final String caption;
    private final Runnable onContinue;

    private Rectangle continueBounds;

    /** The briefing drawn at its own size, and the scaler's working copy. */
    private java.awt.image.BufferedImage design;
    private java.awt.image.BufferedImage scaleCache;

    /**
     * The wash behind the prose.
     *
     * <p>Dark rather than the pale tan it used to be. The backgrounds are a
     * candlelit book and a scroll on a table, drawn in browns at the same
     * weight as lettering, and nothing legible sits directly on them. A dark
     * plate under warm white reads on all ten of them and on the menu scroll
     * this falls back to.
     */
    private static final Color PLATE = new Color(18, 12, 6, 205);

    /**
     * @param backgroundPath the picture the script names, or null for the
     *                       menu's scroll
     * @param heading    the line above the prose, such as the mission's name
     * @param body       the briefing itself
     * @param caption    what the button says
     * @param onContinue what pressing it does
     */
    BriefingScreen(GameData data, String race, int width, int height,
            String backgroundPath, String heading, String body, String caption,
            Runnable onContinue) {
        this.data = data;
        this.headingFont = GameFont.load(data, GameFont.Face.LARGE);
        this.bodyFont = GameFont.load(data, GameFont.Face.GAME);
        this.heading = heading == null ? "" : heading;
        this.body = body == null ? "" : body;
        this.caption = caption;
        this.onContinue = onContinue;
        BufferedImage named = backgroundPath == null || backgroundPath.isBlank()
                ? null
                : load(data, backgroundPath);
        // The menu's scroll only when the script named nothing, or named
        // something this installation does not have. It is a fallback, not the
        // briefing's background: it was the background for every mission in the
        // game until the third argument of Briefing was read.
        this.background = named != null ? named : load(data, "ui/Menu_background_without_title");
        this.button = load(data, "ui/" + race + "/menubutton");

        setPreferredSize(new Dimension(width, height));
        setBackground(Color.BLACK);
        setDoubleBuffered(true);
        setFocusable(true);
        installInput();
    }

    /** The narration, in the order the script lists it. */
    private List<String> voices = List.of();

    /**
     * The line being read, owned so that pressing on silences it.
     *
     * <p>On the voice bus rather than the music bus, which is where this used
     * to go. Upstream reads a briefing with {@code PlaySoundFile}
     * ({@code scripts/database.legacy-declaration:544}, {@code scripts/menus/campaign.legacy-declaration:99}),
     * which takes a sound channel and so the effects volume; sending speech to
     * the music bus means a player who has turned the music off has silenced
     * the narrator as well.
     */
    private ScreenAudio narration = new ScreenAudio(null, ScreenAudio.Kind.VOICE);
    private int voiceAt;
    private javax.swing.Timer voiceTimer;

    /** Which voice files were resolved and handed to the mixer, for tests. */
    private final List<String> played = new ArrayList<>();

    /**
     * Starts the narration.
     *
     * <p>One file at a time and in order, as {@code PlayNextVoice} does in
     * {@code scripts/menus/campaign.legacy-declaration}: the briefings are two takes of one
     * speech, and played together they are two people talking at once. The
     * mixer does not report when a voice ends, so the next one is timed off the
     * length of the clip that is playing, which is the same thing arrived at by
     * arithmetic.
     *
     * @param audio  the open device, or null when there is none
     * @param voices the files the mission script names
     */
    void speak(net.chonkbase.chonkcraft.engine.sound.GameAudio audio, List<String> voices) {
        this.narration = new ScreenAudio(audio, ScreenAudio.Kind.VOICE);
        this.voices = voices == null ? List.of() : List.copyOf(voices);
        this.voiceAt = 0;
        nextVoice();
    }

    /** Plays the next line that resolves, and schedules the one after it. */
    private void nextVoice() {
        while (voiceAt < voices.size()) {
            String path = voices.get(voiceAt++);
            var clip = data == null ? null : data.sounds().clip(path);
            if (clip == null) {
                continue;
            }
            played.add(path);
            narration.play(clip);
            int millis = (int) Math.max(1L,
                    clip.frameCount() * 1000L / Math.max(1, clip.sampleRate()));
            voiceTimer = new javax.swing.Timer(millis, event -> nextVoice());
            voiceTimer.setRepeats(false);
            voiceTimer.start();
            return;
        }
        narration.silence();
    }

    /**
     * Stops the narration.
     *
     * <p>Pressing on has to silence it. Upstream calls {@code StopChannel} and
     * runs the voice counter off the end of the list in the same breath;
     * without both, a mission opens with the briefing still being read over it.
     */
    private void silence() {
        if (voiceTimer != null) {
            voiceTimer.stop();
            voiceTimer = null;
        }
        voiceAt = voices.size();
        narration.silence();
    }

    /** The voice files that resolved to a clip and were played, in order. */
    List<String> playedVoicesForTest() {
        return List.copyOf(played);
    }

    private static BufferedImage load(GameData data, String path) {
        IndexedImage image = data.image(path);
        if (image == null) {
            return null;
        }
        Palette palette = data.paletteFor(path);
        return palette == null ? null : image.toBufferedImage(palette);
    }

    private void installInput() {
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (continueBounds != null && continueBounds.contains(toDesign(event.getPoint()))) {
                    press();
                }
            }
        });
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                // Any of the keys a player reaches for when they have finished
                // reading. Making them hunt for the one that works would be a
                // worse fault than having too many.
                switch (event.getKeyCode()) {
                    case KeyEvent.VK_ENTER, KeyEvent.VK_SPACE, KeyEvent.VK_ESCAPE,
                            KeyEvent.VK_C -> press();
                    default -> { }
                }
            }
        });
    }

    /**
     * Whether the player has already gone on.
     *
     * <p>{@link #press} is reached from the mouse listener and from four keys,
     * and had no guard, though {@code SplashScreen} has had one since it was
     * written and {@code VideoScreen} has just been given one. It matters more
     * here than in either of those, because {@code onContinue} for a mission
     * briefing closes the cutscene device and starts loading the map on a
     * background thread. Two presses start two loads: two {@code GameAudio}
     * devices, two {@code CdMusic}s and two soundtracks playing over one
     * another on the same map, with only one shutdown hook between them and
     * the in-game menu holding whichever sound server was built last -- so one
     * of the two soundtracks answers the music slider and the other does not.
     *
     * <p>That is the reported bug arriving by a second route: "the music from
     * the last video / cutscene was still playing, causing confusion... the
     * music volume control has no effect". A player reaches it by pressing
     * Enter twice on a briefing they have already read, which is what pressing
     * Enter on a page of text looks like when the next screen takes a moment
     * to appear.
     */
    private boolean pressed;

    /** Going on: the narration stops first, then whatever comes next runs. */
    private void press() {
        if (pressed) {
            return;
        }
        pressed = true;
        silence();
        onContinue.run();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        // Drawn at its own size and enlarged to the window, so the briefing
        // fills the screen instead of sitting in the middle of it.
        if (design == null) {
            design = new java.awt.image.BufferedImage(DESIGN_WIDTH, DESIGN_HEIGHT,
                    java.awt.image.BufferedImage.TYPE_INT_RGB);
        }
        Graphics2D into = design.createGraphics();
        into.setColor(java.awt.Color.BLACK);
        into.fillRect(0, 0, DESIGN_WIDTH, DESIGN_HEIGHT);
        paintDesign(into);
        into.dispose();
        scaleCache = PixelScaler.draw((Graphics2D) g, design,
                getWidth(), getHeight(), false, scaleCache);
    }

    /** The briefing at its own size; everything here is in design pixels. */
    private void paintDesign(Graphics2D g2) {
g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int offsetX = 0;
        int offsetY = 0;
        if (background != null) {
            g2.drawImage(background, offsetX, offsetY, null);
        }

        if (!heading.isEmpty() && headingFont != null) {
            headingFont.drawCentred(g2, heading, offsetX + DESIGN_WIDTH / 2,
                    offsetY + HEADING_TOP, GameFont.Ink.YELLOW);
        }

        int buttonWidth = button != null ? button.getWidth() : 176;
        int buttonHeight = button != null ? button.getHeight() : 24;
        int buttonX = offsetX + (DESIGN_WIDTH - buttonWidth) / 2;
        int buttonY = offsetY + DESIGN_HEIGHT - buttonHeight - 40;

        Layout laid = fit(buttonY - 14 - TEXT_TOP);
        List<String> lines = laid.lines();
        int lineHeight = laid.font().height() + LINE_GAP;

        // A plate behind the prose. Every one of these backgrounds is a
        // candlelit drawing in browns -- an open book, a scroll on a table --
        // and the strokes run at the weight of lettering, so nothing laid
        // straight on them can be read.
        if (!lines.isEmpty()) {
            g2.setColor(PLATE);
            g2.fillRect(offsetX + TEXT_LEFT - 14, offsetY + TEXT_TOP - 10,
                    laid.width() + 28, lines.size() * lineHeight + 20);
        }

        int y = offsetY + TEXT_TOP;
        for (String line : lines) {
            laid.font().draw(g2, line, offsetX + TEXT_LEFT, y, GameFont.Ink.WHITE);
            y += lineHeight;
        }
        proseBottom = y;

        continueBounds = new Rectangle(buttonX, buttonY, buttonWidth, buttonHeight);
        if (button != null) {
            g2.drawImage(button, buttonX, buttonY, null);
        } else {
            g2.setColor(new Color(60, 40, 20));
            g2.fill(continueBounds);
        }
        if (bodyFont != null) {
            bodyFont.drawCentred(g2, caption, buttonX + buttonWidth / 2,
                    buttonY + (buttonHeight - bodyFont.height()) / 2, GameFont.Ink.YELLOW);
        }
    }

    /** Where the heading's own line begins. */
    private static final int HEADING_TOP = 26;

    /** Air between the lines of prose, over and above the face's own box. */
    private static final int LINE_GAP = 2;

    /** The prose as it will be set: a face at a size, its column, its lines. */
    private record Layout(GameFont font, int width, List<String> lines) {}

    /**
     * Sets the prose small enough to fit above the button.
     *
     * <p>The briefings are not all one length. Hillsbrad is four short
     * paragraphs and the opening of the orc expansion is five long ones, and a
     * single size that suits the first runs the second off the bottom of the
     * page and behind the button -- which is what it did, because the size was
     * a constant and the difference is nearly double.
     *
     * <p>So the size comes down until the whole briefing fits, and only then
     * does the column widen. Narrow and small keeps the prose on the left-hand
     * page where the picture leaves room for it; widening is the last resort,
     * for the one or two that will not fit any other way.
     *
     * @param available how much height there is between the prose's top and
     *                  the button
     */
    private Layout fit(int available) {
        Layout widest = null;
        for (int width : new int[] {TEXT_WIDTH, WIDE_TEXT_WIDTH}) {
            for (float size = 14f; size >= 9f; size -= 1f) {
                GameFont face = bodyFont.atSize(size);
                List<String> lines = wrap(body, face, width);
                widest = new Layout(face, width, lines);
                if (lines.size() * (face.height() + LINE_GAP) <= available) {
                    return widest;
                }
            }
        }
        // Nothing fits: the smallest and widest setting is the least bad, and
        // it is better to run long than to draw a briefing nobody can read.
        return widest;
    }

    /** The column a briefing that will not fit the page's own is allowed. */
    private static final int WIDE_TEXT_WIDTH = 430;

    /**
     * Breaks the prose to the column it is set in.
     *
     * <p>The archive's text carries its own paragraph breaks, which are kept:
     * the briefings are written as paragraphs and reflowing them into one
     * block would lose the shape Blizzard gave them.
     */
    List<String> wrap(String text, GameFont face, int width) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\r?\n")) {
            if (paragraph.isBlank()) {
                lines.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.trim().split("\\s+")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (face.widthOf(candidate) > width && !line.isEmpty()) {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
            }
        }
        return lines;
    }

    /** A click, in the design's own coordinates. */
    private java.awt.Point toDesign(java.awt.Point at) {
        Rectangle shown = PixelScaler.fit(DESIGN_WIDTH, DESIGN_HEIGHT,
                getWidth(), getHeight(), false);
        if (shown.width <= 0 || shown.height <= 0) {
            return at;
        }
        return new java.awt.Point(
                (at.x - shown.x) * DESIGN_WIDTH / shown.width,
                (at.y - shown.y) * DESIGN_HEIGHT / shown.height);
    }

    /** The families this screen letters with, so a test can prove they match. */
    java.util.List<String> faceFamiliesForTest() {
        return java.util.List.of(headingFont.family(), bodyFont.family(),
                bodyFont.atSize(9f).family());
    }

    /** Where the button landed, for tests. */
    Rectangle continueBoundsForTest() {
        return continueBounds;
    }

    /** How far down the design's page the last line of prose reached. */
    private int proseBottom;

    /** The same, for tests: a briefing that runs past the button is unread. */
    int proseBottomForTest() {
        return proseBottom;
    }

    /** Presses it, as clicking would. */
    void pressForTest() {
        press();
    }
}
