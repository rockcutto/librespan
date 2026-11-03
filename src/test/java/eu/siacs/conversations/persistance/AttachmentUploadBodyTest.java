package eu.siacs.conversations.persistance;

import static org.junit.Assert.*;

import org.junit.Test;

public class AttachmentUploadBodyTest {
    @Test public void imageVideoAudioAndGenericParamsAreAcceptedWithoutText() {
        for (String params : new String[] {"|23", "|23|320|240", "|23|0|0|45000"}) {
            assertTrue(AttachmentUploadBody.isPending(params));
            assertTrue(AttachmentUploadBody.isComplete("https://files.example.test/f" + params,
                    "https://files.example.test/f"));
        }
    }

    @Test public void encryptedLocatorRetainsItsExactWireFragment() {
        String url = "aesgcm://files.example.test/f#" + "ab".repeat(44);
        assertTrue(AttachmentUploadBody.isComplete(url + "|23|0|0|100", url));
        assertFalse(AttachmentUploadBody.isComplete(url + "|23", url.replace("ab", "cd")));
        assertFalse(AttachmentUploadBody.isComplete("aesgcm://files.example.test/f#abc|23",
                "aesgcm://files.example.test/f#abc"));
    }

    @Test public void plaintextAndMalformedOrUnrelatedLocatorsAreRejected() {
        for (String body : new String[] {null, "private message", "|23\nprivate", "|23|secret",
                "|23|0|0|1|private", "|-1", "|23|-1|0", "|23|2147483648|0",
                "|9223372036854775808", "https://files.example.test/f|23"}) {
            assertFalse(String.valueOf(body), AttachmentUploadBody.isPending(body));
        }
        for (String url : new String[] {"file:///private", "data:text/plain,private",
                "https://user:password@files.example.test/f", "https://files.example.test/f\nprivate"}) {
            assertFalse(AttachmentUploadBody.isComplete(url + "|23", url));
        }
        assertFalse(AttachmentUploadBody.isComplete("https://a.example.test/f|23",
                "https://b.example.test/f"));
    }
}
