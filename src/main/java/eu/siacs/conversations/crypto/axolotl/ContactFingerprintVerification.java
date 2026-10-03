package eu.siacs.conversations.crypto.axolotl;

import java.util.Locale;

/** Validates the address and identity encoded in a contact's OMEMO QR code. */
public final class ContactFingerprintVerification {
    private ContactFingerprintVerification() {}

    public static String normalizedQrFingerprint(String value) {
        if (value == null) {
            return null;
        }
        final String hex = value.replaceAll("\\s", "").toLowerCase(Locale.US);
        return hex.matches("[0-9a-f]{64}") ? "05" + hex : null;
    }

    public static boolean matchesTarget(String expectedJid, String scannedJid,
                                        int scannedDeviceId, String scannedFingerprint,
                                        int selectedDeviceId, String selectedFingerprint) {
        final String normalized = normalizedQrFingerprint(scannedFingerprint);
        return expectedJid != null && expectedJid.equals(scannedJid)
                && scannedDeviceId > 0 && normalized != null
                && (selectedDeviceId == 0 || selectedDeviceId == scannedDeviceId)
                && (selectedFingerprint == null || selectedFingerprint.equals(normalized));
    }
}
