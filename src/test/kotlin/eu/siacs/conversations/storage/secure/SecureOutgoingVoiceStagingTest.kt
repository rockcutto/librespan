package eu.siacs.conversations.storage.secure

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SecureOutgoingVoiceStagingTest {
    @Test
    fun retiresStagingOnlyAfterCommittedPublicationBoundary() {
        var retired = false

        SecureOutgoingVoiceStaging.retireAfterCommittedPublication {
            retired = true
            true
        }

        assertTrue(retired)
    }

    @Test
    fun deletionFailureFailsClosedBeforeDispatch() {
        val error =
            assertThrows(IOException::class.java) {
                SecureOutgoingVoiceStaging.retireAfterCommittedPublication { false }
            }

        assertFalse(error.message.isNullOrBlank())
    }
}
