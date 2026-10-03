package eu.siacs.conversations.ui.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;

import org.junit.Test;

public class VoiceRecordingStagingTest {

    @Test
    public void retiresTemporaryVoiceFile() throws IOException {
        final File staging = File.createTempFile("RECORDING_", ".m4a");

        assertTrue(VoiceRecordingStaging.retire(staging));
        assertFalse(staging.exists());
        assertTrue(VoiceRecordingStaging.retire(staging));
    }

    @Test
    public void reportsFailureForNonEmptyStagingDirectory() throws IOException {
        final File staging = File.createTempFile("RECORDING_", ".oga");
        assertTrue(staging.delete());
        assertTrue(staging.mkdir());
        final File child = new File(staging, "source");
        assertTrue(child.createNewFile());

        assertFalse(VoiceRecordingStaging.retire(staging));

        assertTrue(child.delete());
        assertTrue(staging.delete());
    }
}
