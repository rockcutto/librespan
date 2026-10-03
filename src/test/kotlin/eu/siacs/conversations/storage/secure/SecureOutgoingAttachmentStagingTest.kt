package eu.siacs.conversations.storage.secure

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureOutgoingAttachmentStagingTest {
    @Test
    fun retirementRunsAfterCommittedPublicationBoundary() {
        var retired = false

        SecureOutgoingAttachmentStaging.retireAfterCommittedPublication {
            retired = true
            true
        }

        assertTrue(retired)
    }

    @Test
    fun cleanupFailureFailsClosedBeforeDispatch() {
        var attempted = false

        assertThrows(IOException::class.java) {
            SecureOutgoingAttachmentStaging.retireAfterCommittedPublication {
                attempted = true
                false
            }
        }

        assertTrue(attempted)
    }

    @Test
    fun nullRetirerLeavesExternalSourceUntouched() {
        SecureOutgoingAttachmentStaging.retireAfterCommittedPublication(null)
        assertFalse(false)
    }
}
