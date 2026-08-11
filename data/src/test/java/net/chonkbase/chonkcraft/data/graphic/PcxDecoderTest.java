package net.chonkbase.chonkcraft.data.graphic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PcxDecoderTest {

    @Test
    void decodesRunsPaddingAndTheEightBitPalette() {
        byte[] pcx = new byte[128 + 7 + 1 + 768];
        pcx[0] = 0x0A;
        pcx[2] = 1;
        pcx[3] = 8;
        little16(pcx, 8, 2);
        little16(pcx, 10, 1);
        pcx[65] = 1;
        little16(pcx, 66, 4);
        // Three visible pixels and one padded byte per row.
        byte[] encoded = {1, (byte) 0xC2, 2, 9, (byte) 0xC3, 3, 9};
        System.arraycopy(encoded, 0, pcx, 128, encoded.length);
        int marker = pcx.length - 769;
        pcx[marker] = 0x0C;
        pcx[marker + 1 + 3] = (byte) 252;
        pcx[marker + 1 + 4] = (byte) 128;
        pcx[marker + 1 + 5] = (byte) 4;

        PcxDecoder.Pcx decoded = PcxDecoder.decode(pcx);

        assertEquals(3, decoded.image().width());
        assertEquals(2, decoded.image().height());
        assertArrayEquals(new byte[] {1, 2, 2, 3, 3, 3}, decoded.image().pixels());
        assertArrayEquals(new byte[] {3, 0, 2, 0, 1, 2, 2, 3, 3, 3},
                PcxDecoder.imageEntry(pcx));
        assertEquals(63, decoded.vgaPalette()[3]);
        assertEquals(32, decoded.vgaPalette()[4]);
        assertEquals(1, decoded.vgaPalette()[5]);
    }

    private static void little16(byte[] data, int at, int value) {
        data[at] = (byte) value;
        data[at + 1] = (byte) (value >>> 8);
    }
}
