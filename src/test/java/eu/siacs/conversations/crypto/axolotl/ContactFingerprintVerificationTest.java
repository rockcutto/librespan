package eu.siacs.conversations.crypto.axolotl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ContactFingerprintVerificationTest {
    private static final String KEY =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    public void qrMatchesOnlySelectedContactAndDeviceIdentity() {
        assertTrue(ContactFingerprintVerification.matchesTarget(
                "alice@example.org", "alice@example.org", 17, KEY, 17, "05" + KEY));
        assertFalse(ContactFingerprintVerification.matchesTarget(
                "alice@example.org", "mallory@example.org", 17, KEY, 17, "05" + KEY));
        assertFalse(ContactFingerprintVerification.matchesTarget(
                "alice@example.org", "alice@example.org", 18, KEY, 17, "05" + KEY));
        assertFalse(ContactFingerprintVerification.matchesTarget(
                "alice@example.org", "alice@example.org", 17, KEY, 17, "05" + KEY.replace('0', 'f')));
    }

    @Test
    public void rejectsMissingDeviceIdAndMalformedFingerprint() {
        assertFalse(ContactFingerprintVerification.matchesTarget(
                "alice@example.org", "alice@example.org", 0, KEY, 0, null));
        assertFalse(ContactFingerprintVerification.matchesTarget(
                "alice@example.org", "alice@example.org", 17, "bad", 0, null));
        assertEquals("05" + KEY, ContactFingerprintVerification.normalizedQrFingerprint(KEY.toUpperCase()));
    }

    @Test
    public void verificationSurvivesDeviceListActivationTransitions() {
        final FingerprintStatus verified = FingerprintStatus.createActiveUndecided().toVerified();
        assertTrue(verified.isVerified());
        assertTrue(verified.isTrustedAndActive());
        final FingerprintStatus absentFromDeviceList = verified.toInactive();
        assertTrue(absentFromDeviceList.isVerified());
        assertTrue(absentFromDeviceList.toActive().isTrustedAndActive());
    }
}
