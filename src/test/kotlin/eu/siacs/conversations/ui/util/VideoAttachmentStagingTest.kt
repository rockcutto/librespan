package eu.siacs.conversations.ui.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoAttachmentStagingTest {
    @Test
    fun acceptsOnlyPrivateVideoStagingProviderRoot() {
        assertTrue(
            VideoAttachmentStaging.isControlledProviderPath(
                listOf("secure_outgoing_videos", "message.mp4"),
            ),
        )
        assertFalse(VideoAttachmentStaging.isControlledProviderPath(listOf("camera", "video.mp4")))
        assertFalse(VideoAttachmentStaging.isControlledProviderPath(listOf("external", "Movies")))
        assertFalse(
            VideoAttachmentStaging.isControlledProviderPath(
                listOf("secure_outgoing_videos", "nested", "message.mp4"),
            ),
        )
        assertFalse(VideoAttachmentStaging.isControlledProviderPath(listOf("secure_outgoing_videos", "..")))
    }
}
