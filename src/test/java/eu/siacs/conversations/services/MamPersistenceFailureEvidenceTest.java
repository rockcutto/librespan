package eu.siacs.conversations.services;

import static org.junit.Assert.*;
import static eu.siacs.conversations.services.MamTestHarness.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Regression for the originally characterized unsafe baseline. Original evidence remains
 * in the independent-review archive; every assertion here now requires safe completion. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class MamPersistenceFailureEvidenceTest {
    @Test public void keepsFloorAfterRealScsFailureAtFirstMiddleAndLast() throws Exception {
        for (int failure : new int[]{1, 2, 3}) {
            var h = new MamTestHarness(); h.join();
            h.service.failureAt = failure; h.service.realScsFailure = true;
            for (int n = 1; n <= 3; n++) h.result(0, n);
            assertEquals(1, h.service.failures); assertEquals(2, h.f.room.countMessages());
            assertEquals(2, h.service.rows.size()); // failed parser plaintext never becomes visible
            assertEquals(3, h.query(0).getTotalCount());
            h.finish(0, true, "f", "l");
            assertNotNull("failed publication must preserve recovery floor", h.f.room.getMucRecoveryAnchor());
        }
    }
    @Test public void keepsFloorAfterDbExceptionBeforeFin() throws Exception {
        var h = new MamTestHarness(); h.join(); h.service.failureAt = 2; h.service.throwWrite = true;
        h.result(0, 1); assertThrows(IllegalStateException.class, () -> h.result(0, 2));
        // A real parser exception may instead tear down transport. The parser must latch failure even if transport later supplies fin.
        h.finish(0, true, "f", "l"); assertNotNull(h.f.room.getMucRecoveryAnchor());
    }
    @Test public void doesNotContinuePastFailedPage() throws Exception {
        var h = new MamTestHarness(); h.join(); h.service.failureAt = 1;
        h.result(0, 1); h.finish(0, false, "f", "page-1");
        assertEquals(1, h.service.mamRequests.size());
        assertNotNull(h.f.room.getMucRecoveryAnchor()); assertEquals(0, h.service.rows.size());
    }
    @Test public void keepsFloorAfterProtectedMetadataDbRejection() throws Exception {
        var h = new MamTestHarness(); h.join(); h.service.failureAt = 1;
        h.service.realScsFailure = true; h.service.dropProtectedMetadata = true;
        h.result(0, 1); assertEquals(0, h.f.room.countMessages());
        h.finish(0, true, "f", "l"); assertNotNull(h.f.room.getMucRecoveryAnchor());
    }
    @Test public void metadataOnlyDuplicateDoesNotProvePersistence() throws Exception {
        var h = new MamTestHarness(); h.join();
        var metadataOnly = new eu.siacs.conversations.entities.Message(h.f.room, "", 0);
        metadataOnly.setServerMsgId("archive-1");
        metadataOnly.setSecureMessagePayloadMode(eu.siacs.conversations.storage.secure.SecureMessagePayloadMode.UNAVAILABLE);
        h.service.rows.put("archive-1", metadataOnly);
        h.result(0, 1); assertEquals(0, h.service.calls);
        h.finish(0, true, "f", "l"); assertNotNull(h.f.room.getMucRecoveryAnchor());
    }
    @Test public void emptyCursorDoesNotStartAnotherPage() throws Exception {
        var h = new MamTestHarness(); h.join(); h.result(0, 1);
        h.finish(0, false, "f", "");
        assertEquals(1, h.service.mamRequests.size()); assertNotNull(h.f.room.getMucRecoveryAnchor());
    }
}
