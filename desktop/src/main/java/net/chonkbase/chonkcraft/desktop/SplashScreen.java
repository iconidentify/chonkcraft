package net.chonkbase.chonkcraft.desktop;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.swing.JPanel;
import javax.swing.Timer;

/**
 * A still title screen, shown for a while and then gone.
 *
 * <p>The still half of the native title sequence. Two entries are Smacker
 * videos and go to {@link VideoScreen}; this shows the title picture and the
 * Java-painted black background.
 *
 * <p>The picture is drawn to fill the window, keeping its own proportions when
 * the script asks for that, and scaled with nearest-neighbour so a 640 by 480
 * title stays crisp instead of turning to mush on a large display.
 *
 * <p>It carries a sound as well, because an act card does: every
 * {@code CreatePictureStep} in the four campaign scripts names
 * {@code sounds/human/act.wav} or {@code sounds/orc/act.wav} beside the
 * picture. The launcher used to start that fanfare itself and drop the voice
 * the mixer handed back, so nothing could stop it. It runs five seconds and the
 * card stays up for six, which is why waiting the card out never showed the
 * fault -- but a card is a still picture and a player clicks past it, and then
 * the brass plays on into the cutscene or the briefing behind it.
 */
final class SplashScreen extends JPanel {

    private final BufferedImage picture;
    private final boolean stretch;
    private final Runnable onFinished;
    private final Timer timer;
    private boolean finished;

    /** The fanfare, owned so it can be silenced when the card goes. */
    private final ScreenAudio fanfare;

    /** The fanfare itself, kept until the card is put up. */
    private final net.chonkbase.runtime.audio.PcmClip sound;

    /** The scaler's intermediate, kept so a resize does not reallocate it. */
    private BufferedImage cache;

    /**
     * @param seconds how long to stay up; zero waits for a key
     */
    SplashScreen(BufferedImage picture, boolean stretch, int seconds, int width, int height,
            Runnable onFinished) {
        this(picture, stretch, seconds, width, height, onFinished, null, null);
    }

    /**
     * @param audio where the card's fanfare plays, or null for a silent card
     * @param sound the fanfare itself, or null
     */
    SplashScreen(BufferedImage picture, boolean stretch, int seconds, int width, int height,
            Runnable onFinished, net.chonkbase.chonkcraft.engine.sound.GameAudio audio,
            net.chonkbase.runtime.audio.PcmClip sound) {
        this.picture = picture;
        this.stretch = stretch;
        this.onFinished = onFinished;
        this.fanfare = new ScreenAudio(audio, ScreenAudio.Kind.MUSIC);
        this.sound = sound;

        setPreferredSize(new Dimension(width, height));
        setBackground(Color.BLACK);
        setDoubleBuffered(true);
        setFocusable(true);

        // Any key or click moves on, as the original allows.
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                finish();
            }
        });
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                finish();
            }
        });

        timer = new Timer(Math.max(1, seconds) * 1000, event -> finish());
        timer.setRepeats(false);
    }

    void begin() {
        fanfare.play(sound);
        timer.start();
    }

    /** Passes the card as a key press or a click does. */
    void skip() {
        finish();
    }

    private void finish() {
        if (finished) {
            return;
        }
        finished = true;
        timer.stop();
        // Before whatever follows starts: the card is being passed, and passing
        // it means the fanfare goes with it.
        fanfare.silence();
        if (onFinished != null) {
            onFinished.run();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (picture == null) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g;
        cache = PixelScaler.draw(g2, picture, getWidth(), getHeight(), stretch, cache);
    }
}
