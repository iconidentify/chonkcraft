package net.chonkbase.chonkcraft.data.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.graphic.IndexedImage;
import net.chonkbase.chonkcraft.data.source.ArchiveIds;
import net.chonkbase.chonkcraft.data.source.EntryArchive;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Decoding the cutscenes off the Warcraft II CD.
 *
 * <p>These need the disc, not just an installation: the DOS release leaves the
 * videos on it, so a hard-disk install has no {@code muddat.cud} and these skip.
 */
class SmackerVideoTest {

    /** A source and its video archive, kept together so an MPQ stays open. */
    private record Videos(InstallSource source, EntryArchive archive)
            implements AutoCloseable {

        @Override
        public void close() {
            source.close();
        }
    }

    /** The video archive, extracted from a disc image or BNE's installer. */
    private static Videos videos() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        EntryArchive archive = install.archive(ArchiveIds.MUDDAT);
        Assumptions.assumeTrue(archive != null,
                "no movies: neither muddat.cud nor Battle.net Edition media was found");
        return new Videos(install, archive);
    }

    @Test
    @DisplayName("the archive holds Smacker videos with sensible headers")
    void theVideosAreThere() {
        try (Videos videos = videos()) {
            EntryArchive archive = videos.archive();
            int found = 0;
            for (int i = 0; i < archive.entryCount(); i++) {
                byte[] entry = archive.entry(i);
                if (!SmackerVideo.looksLikeSmacker(entry)) {
                    continue;
                }
                SmackerVideo video = SmackerVideo.read(entry);
                assertTrue(video.width() > 0 && video.height() > 0,
                        "entry " + i + " has no extent");
                assertTrue(video.frameCount() > 0, "entry " + i + " has no frames");
                assertTrue(video.frameMillis() > 0, "entry " + i + " has no frame duration");
                found++;
            }
            assertTrue(found >= 9, "expected the campaign cutscenes, found " + found);
        }
    }

    @Test
    @DisplayName("the first human cutscene decodes to a real picture")
    void theFirstCutsceneDecodes() {
        try (Videos videos = videos()) {
            // Entry 11 is videos/human-1 in the conversion table.
            byte[] entry = videos.archive().entry(11);
            Assumptions.assumeTrue(SmackerVideo.looksLikeSmacker(entry),
                    "entry 11 is not a video on this release");

            SmackerVideo video = SmackerVideo.read(entry);
            assertEquals(320, video.width());
            assertEquals(videos.source().isBattleNetEdition() ? 288 : 144, video.height());
            assertEquals(videos.source().isBattleNetEdition() ? 111 : 110, video.frameCount());

            IndexedImage picture = video.decodeFrame(0);
            assertNotNull(picture);
            assertEquals(video.width() * video.height(), picture.pixels().length);
            if (videos.source().isBattleNetEdition()) {
                // The remastered cut starts with one intentional black frame.
                picture = video.decodeFrame(1);
            }

            // A decoded picture is not a flat fill: the opening shot is sky
            // over grass and uses a good part of the palette.
            boolean[] seen = new boolean[256];
            int distinct = 0;
            for (byte pixel : picture.pixels()) {
                int index = pixel & 0xFF;
                if (!seen[index]) {
                    seen[index] = true;
                    distinct++;
                }
            }
            assertTrue(distinct > 40, "only " + distinct + " colours: that is not a picture");

            // The palette is the video's own, and index 255 is a colour rather
            // than the transparent slot it means in a sprite.
            assertNotNull(video.palette());
        }
    }

    @Test
    @DisplayName("Battle.net Edition's 16-bit stereo predictor is decoded in sample order")
    void battleNetStereoAudioUsesTheRightByteOrder() {
        try (Videos videos = videos()) {
            Assumptions.assumeTrue(videos.source().isBattleNetEdition(),
                    "the original release has eight-bit mono movie audio");
            SmackerVideo video = SmackerVideo.read(videos.archive().entry(11));
            SmackerVideo.Audio audio = video.decodeAudio(0);
            assertNotNull(audio);
            assertEquals(22_050, audio.sampleRate());
            assertEquals(2, audio.channels());
            assertTrue(audio.samples().length > 16);
            assertArrayEquals(new short[] {
                10, 10, -38, -38, -118, -118, -118, -118
            }, java.util.Arrays.copyOf(audio.samples(), 8));
        }
    }

    @Test
    @DisplayName("every frame of every cutscene decodes completely")
    void everyFrameIsComplete() {
        try (Videos fixture = videos()) {
            EntryArchive archive = fixture.archive();
            int videos = 0;
            long frames = 0;
            for (int i = 0; i < archive.entryCount(); i++) {
                byte[] entry = archive.entry(i);
                if (!SmackerVideo.looksLikeSmacker(entry)) {
                    continue;
                }
                SmackerVideo video = SmackerVideo.read(entry);
                videos++;
                for (int frame = 0; frame < video.frameCount(); frame++) {
                    video.decodeFrame(frame);
                    // A frame that runs out of bits before covering its blocks
                    // leaves the rest of the picture stale, which is how a
                    // decoding fault shows: not as an error but as a frame
                    // that is partly the one before it.
                    assertEquals(video.blockCount(), video.lastBlockReached(),
                            "entry " + i + " frame " + frame + " covered only "
                                    + video.lastBlockReached() + " blocks");
                }
                frames += video.frameCount();
            }
            assertTrue(videos >= 9, "expected the campaign cutscenes, found " + videos);
            assertTrue(frames > 5000,
                    "only " + frames + " frames across " + videos + " videos");
        }
    }

    @Test
    @DisplayName("the cutscenes run at twelve frames a second")
    void theFrameRateIsRight() {
        try (Videos videos = videos()) {
            byte[] entry = videos.archive().entry(11);
            Assumptions.assumeTrue(SmackerVideo.looksLikeSmacker(entry),
                    "no video at entry 11");

            SmackerVideo video = SmackerVideo.read(entry);
            // The header carries -8333. Read as microseconds that is 120 frames
            // a second; the unit is ten microseconds and the answer is twelve.
            assertEquals(83330, video.frameMicros());
            assertEquals(83, video.frameMillis());
        }
    }

    @Test
    @DisplayName("bytes that are not a Smacker file are refused rather than misread")
    void rubbishIsRefused() {
        assertFalse(SmackerVideo.looksLikeSmacker(new byte[0]));
        assertFalse(SmackerVideo.looksLikeSmacker(new byte[200]));
        byte[] wrong = new byte[200];
        wrong[0] = 'S';
        wrong[1] = 'M';
        wrong[2] = 'K';
        wrong[3] = '9';
        assertFalse(SmackerVideo.looksLikeSmacker(wrong));
    }
}
