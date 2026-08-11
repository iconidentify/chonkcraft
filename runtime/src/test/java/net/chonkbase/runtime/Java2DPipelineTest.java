package net.chonkbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class Java2DPipelineTest {
    @Test
    void choosesExpectedOsDefaults() {
        String original = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Mac OS X");
            assertEquals(Java2DPipeline.Choice.METAL, Java2DPipeline.defaultForOs());
            System.setProperty("os.name", "Windows 11");
            assertEquals(Java2DPipeline.Choice.D3D, Java2DPipeline.defaultForOs());
            System.setProperty("os.name", "Linux");
            assertEquals(Java2DPipeline.Choice.OPENGL, Java2DPipeline.defaultForOs());
        } finally {
            if (original == null) {
                System.clearProperty("os.name");
            } else {
                System.setProperty("os.name", original);
            }
        }
    }
}

