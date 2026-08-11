package net.chonkbase.chonkcraft.desktop;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.swing.JPanel;
import javax.swing.Timer;
import net.chonkbase.chonkcraft.data.video.SmackerVideo;

/**
 * Plays a Warcraft II cutscene.
 *
 * <p>Frames are decoded as they are shown rather than up front. A cutscene
 * runs to thirteen hundred frames of 320 by 200, which is eighty megabytes
 * held as images and a second or two of stall before anything appears; decoded
 * on the clock it is a few milliseconds a frame and starts at once.
 *
 * <p>Decoding must stay in order regardless, because most frames are deltas
 * against the one before. That is why there is no seeking here: a cutscene is
 * watched or skipped, and skipping means leaving.
 */
final class VideoScreen extends JPanel {

    private final SmackerVideo video;
    private final Runnable onFinished;
    private final Timer timer;

    /** The intermediate the scaler works through, kept between frames. */
    private BufferedImage scaleCache;

    /** The soundtrack, once decoded, or null when there is none. */
    private final ScreenAudio track;
    private final net.chonkbase.runtime.audio.PcmClip soundtrack;

    /**
     * Whether this has already finished.
     *
     * <p>{@code finish} is reached from five places -- the key listener, the
     * mouse listener, both ends of {@code step}, and {@code skip} -- and had no
     * guard, while {@code SplashScreen} beside it has had one all along. A
     * second key event already in the queue when the first one is dispatched
     * runs {@code onFinished} twice, and {@code onFinished} is the link in the
     * chain that shows the next screen: two runs step over one. In the title
     * sequence that skips a title, and in a campaign it skips a cutscene or a
     * briefing.
     */
    private boolean finished;

    /** When the first frame went up, so the picture follows the sound. */
    private long startedAt;

    private BufferedImage current;
    private int frame;

    VideoScreen(SmackerVideo video, int width, int height, Runnable onFinished) {
        this(video, width, height, onFinished, null);
    }

    /**
     * @param audio where to play the cutscene's own soundtrack, or null for a
     *              silent one
     */
    VideoScreen(SmackerVideo video, int width, int height, Runnable onFinished,
            net.chonkbase.chonkcraft.engine.sound.GameAudio audio) {
        this.video = video;
        this.onFinished = onFinished;
        this.track = new ScreenAudio(audio, ScreenAudio.Kind.MUSIC);
        this.soundtrack = audio == null ? null : soundtrackOf(video);

        setPreferredSize(new Dimension(width, height));
        setBackground(Color.BLACK);
        setDoubleBuffered(true);
        setFocusable(true);

        // Any key or click leaves, as the original allows.
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

        timer = new Timer(Math.max(1, video.frameMillis()), event -> step());
        timer.setCoalesce(true);
    }

    /** Starts playing. */
    void play() {
        startedAt = System.nanoTime();
        track.play(soundtrack);
        step();
        timer.start();
    }

    /** The voice the soundtrack is playing on, for the test that skips. */
    long voice() {
        return track.voice();
    }

    /** Ends playback as a key press or a click does. */
    void skip() {
        finish();
    }

    /**
     * Runs the clock past the last frame, so the cutscene ends of its own
     * accord.
     *
     * <p>For the test that watches one to the end rather than skipping it.
     * Once the soundtrack is playing the frame to show is worked out from the
     * wall clock rather than counted, which is what keeps the picture with the
     * sound over a two minute film, and it is also what makes watching one in a
     * test take as long as watching one.
     */
    void endForTest() {
        // Do not race Swing's timer through every delta frame on the test
        // thread. Natural completion reaches step with this exact state; the
        // helper supplies that state directly and exercises the same finish
        // path deterministically.
        timer.stop();
        frame = video.frameCount();
        step();
    }

    /**
     * The cutscene's soundtrack, at the rate the mixer wants.
     *
     * <p>Smacker carries it at 22050 and the engine runs at 48000, so it is
     * resampled on the way through. Linear interpolation between neighbouring
     * samples: the source is eight bit and already grainy, and anything
     * cleverer would be polishing noise.
     */
    private static net.chonkbase.runtime.audio.PcmClip soundtrackOf(SmackerVideo video) {
        var track = video.decodeAudio(0);
        if (track == null || track.samples().length == 0) {
            return null;
        }
        int channels = track.channels();
        int sourceFrames = track.samples().length / channels;
        int rate = net.chonkbase.runtime.audio.PcmFormat.GAME_SAMPLE_RATE;
        long targetFrames = (long) sourceFrames * rate / track.sampleRate();
        if (targetFrames <= 0 || targetFrames > Integer.MAX_VALUE / channels) {
            return null;
        }
        short[] out = new short[(int) targetFrames * channels];
        double step = track.sampleRate() / (double) rate;
        for (int i = 0; i < targetFrames; i++) {
            double at = i * step;
            int left = (int) at;
            int right = Math.min(sourceFrames - 1, left + 1);
            double blend = at - left;
            for (int channel = 0; channel < channels; channel++) {
                short a = track.samples()[left * channels + channel];
                short b = track.samples()[right * channels + channel];
                out[i * channels + channel] = (short) Math.round(a + (b - a) * blend);
            }
        }
        return new net.chonkbase.runtime.audio.PcmClip("cutscene", channels, out);
    }

    /**
     * The shape in which a movie was meant to be shown, independently of the
     * number of samples stored in its frame.
     *
     * <p>BNE's replacement movies use pixels that are 6:5 wider than they are
     * tall. That turns the main movies' 320 by 288 raster into a 4:3 picture
     * and also widens the separate Blizzard film without making it fill the
     * screen. BNE is identified by its 16-bit soundtrack; the original
     * movies' sound is 8-bit and their stored shape remains unchanged.
     */
    static Dimension displayAspect(int width, int height, boolean battleNetPixels) {
        return battleNetPixels
                ? new Dimension(Math.multiplyExact(width, 6),
                        Math.multiplyExact(height, 5))
                : new Dimension(width, height);
    }

    private void step() {
        // Which frame the clock says we should be on, rather than one more
        // than last time. A Swing timer that runs a shade slow would otherwise
        // walk the picture steadily behind the sound over a two minute
        // cutscene, and the intro is exactly that long.
        if (soundtrack != null && startedAt != 0) {
            long elapsed = (System.nanoTime() - startedAt) / 1_000_000L;
            int wanted = (int) Math.min(video.frameCount(),
                    elapsed / Math.max(1, video.frameMillis()));
            while (frame < wanted && frame < video.frameCount()) {
                current = video.decodeFrame(frame++).toOpaqueBufferedImage(video.palette());
            }
            if (frame >= video.frameCount()) {
                finish();
                return;
            }
            repaint();
            return;
        }
        if (frame >= video.frameCount()) {
            finish();
            return;
        }
        current = video.decodeFrame(frame++).toOpaqueBufferedImage(video.palette());
        repaint();
    }

    private void finish() {
        if (finished) {
            return;
        }
        finished = true;
        if (timer.isRunning()) {
            timer.stop();
        }
        // Before the pictures stop, and before whatever follows starts its own
        // music: skipping means leaving, and leaving means silence.
        track.silence();
        onFinished.run();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        BufferedImage image = current;
        if (image == null) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        // Filled out to the window by PixelScaler, which keeps the hard pixel
        // edges these were drawn with instead of blurring them away.
        Dimension aspect = displayAspect(video.width(), video.height(),
                video.audioBitsPerSample(0) == 16);
        scaleCache = PixelScaler.drawAtAspect(g2, image, getWidth(), getHeight(),
                aspect.width, aspect.height, scaleCache);
    }
}
