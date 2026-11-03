package eu.siacs.conversations.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AudioAttachmentVoiceCardTest {
    @Test
    public void appRecorderNamesAreRecognizedForBothVoiceFormats() {
        assertTrue(AudioAttachmentPresentation.isAppVoiceRecordingName(
                "RECORDING_20261103_122345678.m4a"));
        assertTrue(AudioAttachmentPresentation.isAppVoiceRecordingName(
                "RECORDING_20261103_122345678.oga"));
    }

    @Test
    public void voiceCardHidesGeneratedNameWithoutEmbeddedTags() {
        final AudioAttachmentPresentation.Metadata voice = new AudioAttachmentPresentation.Metadata(
                AudioAttachmentPresentation.Tracks.AUDIO, null, null, 1300);
        assertNull(voice.titleOrFileNameForChat("RECORDING_20261103_122345678.m4a"));
        assertNull(voice.titleOrFileNameForChat("RECORDING_20261103_122345678.oga"));
        assertEquals("song.mp3", voice.titleOrFileNameForChat("song.mp3"));
    }

    @Test
    public void embeddedTitleStillWinsEvenWithVoiceStyleFilename() {
        final AudioAttachmentPresentation.Metadata tagged = new AudioAttachmentPresentation.Metadata(
                AudioAttachmentPresentation.Tracks.AUDIO, "Track", "Artist", 2500);
        assertEquals("Track", tagged.titleOrFileNameForChat("RECORDING_20261103_122345678.m4a"));
        assertEquals("Track", tagged.titleOrFileNameForChat("song.mp3"));
        assertEquals("Artist", tagged.artist);
    }

    @Test
    public void musicAndArbitraryAudioNamesDoNotLookLikeAppVoice() {
        assertFalse(AudioAttachmentPresentation.isAppVoiceRecordingName("song.mp3"));
        assertFalse(AudioAttachmentPresentation.isAppVoiceRecordingName("voice.m4a"));
        assertFalse(AudioAttachmentPresentation.isAppVoiceRecordingName("recording.oga"));
        assertFalse(AudioAttachmentPresentation.isAppVoiceRecordingName(
                "RECORDING_20261103_122345678.mp3"));
        assertFalse(AudioAttachmentPresentation.isAppVoiceRecordingName(
                "RECORDING_20261103_122345678x.m4a"));
        assertFalse(AudioAttachmentPresentation.isAppVoiceRecordingName(
                "RECORDING_foo.m4a"));
        assertFalse(AudioAttachmentPresentation.isAppVoiceRecordingName(null));
    }
}
