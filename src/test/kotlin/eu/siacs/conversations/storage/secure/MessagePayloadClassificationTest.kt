package eu.siacs.conversations.storage.secure

import org.junit.Assert.assertEquals
import org.junit.Test

class MessagePayloadClassificationTest {

    @Test
    fun durableTextBodyWithoutSecureModeIsLegacyPlaintext() {
        assertEquals(
            MessagePayloadClassification.LEGACY_PLAINTEXT,
            MessagePayloadClassification.classify(
                secureMode = null,
                isTextPayload = true,
                hasDurablePlaintextBody = true,
            ),
        )
    }

    @Test
    fun emptyOrNonTextBodyIsNotLegacyPlaintext() {
        assertEquals(
            MessagePayloadClassification.NOT_APPLICABLE,
            MessagePayloadClassification.classify(null, true, false),
        )
        assertEquals(
            MessagePayloadClassification.NOT_APPLICABLE,
            MessagePayloadClassification.classify(null, false, true),
        )
    }

    @Test
    fun secureModeAlwaysOverridesLegacyBodyFact() {
        assertEquals(
            MessagePayloadClassification.PENDING,
            MessagePayloadClassification.classify(
                SecureMessagePayloadMode.PENDING,
                true,
                true,
            ),
        )
        assertEquals(
            MessagePayloadClassification.PROTECTED,
            MessagePayloadClassification.classify(
                SecureMessagePayloadMode.PROTECTED,
                true,
                true,
            ),
        )
        assertEquals(
            MessagePayloadClassification.UNAVAILABLE,
            MessagePayloadClassification.classify(
                SecureMessagePayloadMode.UNAVAILABLE,
                true,
                true,
            ),
        )
    }
}
