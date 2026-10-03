package eu.siacs.conversations.ui.util

import eu.siacs.conversations.storage.secure.SecureOutgoingAttachmentStagingRetirer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageAttachmentStagingTest {
    @Test
    fun acceptsOnlyBoundedImageCacheProviderRoots() {
        assertTrue(
            ImageAttachmentStaging.isControlledProviderPath(
                listOf("secure_outgoing_images", "message.jpg"),
            ),
        )
        assertTrue(
            ImageAttachmentStaging.isControlledProviderPath(
                listOf("camera", "IMG_20260919_120000.jpg"),
            ),
        )
        assertFalse(
            ImageAttachmentStaging.isControlledProviderPath(
                listOf("external", "Pictures"),
            ),
        )
        assertFalse(
            ImageAttachmentStaging.isControlledProviderPath(
                listOf("secure_outgoing_images", ".."),
            ),
        )
        assertFalse(
            ImageAttachmentStaging.isControlledProviderPath(
                listOf("secure_outgoing_images", "nested", "message.jpg"),
            ),
        )
    }

    @Test
    fun combinedRetirementRemovesDerivedThenOwnedSource() {
        val order = mutableListOf<String>()
        val combined =
            ImageAttachmentStaging.combine(
                SecureOutgoingAttachmentStagingRetirer {
                    order += "derived"
                    true
                },
                SecureOutgoingAttachmentStagingRetirer {
                    order += "source"
                    true
                },
            )

        assertTrue(combined!!.retire())
        assertEquals(listOf("derived", "source"), order)
    }

    @Test
    fun derivedRetirementFailureKeepsSourceForRetry() {
        var sourceRetired = false
        val combined =
            ImageAttachmentStaging.combine(
                SecureOutgoingAttachmentStagingRetirer { false },
                SecureOutgoingAttachmentStagingRetirer {
                    sourceRetired = true
                    true
                },
            )

        assertFalse(combined!!.retire())
        assertFalse(sourceRetired)
    }
}
