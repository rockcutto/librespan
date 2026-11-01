package eu.siacs.conversations.crypto.axolotl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import eu.siacs.conversations.xmpp.Jid;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class OmemoMinimalCompletionTest {

    @Test
    public void coalescedWaitersClaimOnceAndOldResponseCannotStealRetry() {
        final Jid jid = Jid.of("peer@example.test");
        final Map<Jid, List<AxolotlService.OnDeviceIdsFetched>> pending = new HashMap<>();
        final List<AxolotlService.OnDeviceIdsFetched> first = new ArrayList<>();
        final AtomicInteger delivered = new AtomicInteger();
        first.add((j, ids) -> delivered.incrementAndGet());
        first.add((j, ids) -> delivered.incrementAndGet());
        pending.put(jid, first);
        final List<AxolotlService.OnDeviceIdsFetched> claimed =
                AxolotlService.claimDeviceIdsCallbacks(pending, jid, first);
        assertSame(first, claimed);
        for (AxolotlService.OnDeviceIdsFetched callback : claimed) {
            callback.fetched(jid, null);
        }
        final List<AxolotlService.OnDeviceIdsFetched> retry = new ArrayList<>();
        pending.put(jid, retry);
        assertNull(AxolotlService.claimDeviceIdsCallbacks(pending, jid, first));
        assertSame(retry, AxolotlService.claimDeviceIdsCallbacks(pending, jid, retry));
        assertEquals(2, delivered.get());
        assertNull(AxolotlService.claimDeviceIdsCallbacks(pending, jid, retry));
    }

    @Test
    public void otherJidDoesNotAffectPendingRequest() {
        final Jid firstJid = Jid.of("first@example.test");
        final Jid secondJid = Jid.of("second@example.test");
        final Map<Jid, List<AxolotlService.OnDeviceIdsFetched>> pending = new HashMap<>();
        final List<AxolotlService.OnDeviceIdsFetched> first = new ArrayList<>();
        final List<AxolotlService.OnDeviceIdsFetched> second = new ArrayList<>();
        pending.put(firstJid, first);
        pending.put(secondJid, second);
        assertSame(second, AxolotlService.claimDeviceIdsCallbacks(pending, secondJid, second));
        assertSame(first, AxolotlService.claimDeviceIdsCallbacks(pending, firstJid, first));
    }

    @Test
    public void mixedOutcomesNeverReportAllVerifiedOrHideFailure() {
        final Map<Integer, AxolotlService.FetchStatus> own = new HashMap<>();
        final Map<Integer, AxolotlService.FetchStatus> remote = new HashMap<>();
        own.put(1, AxolotlService.FetchStatus.SUCCESS_VERIFIED);
        remote.put(2, AxolotlService.FetchStatus.SUCCESS);
        assertEquals(AxolotlService.FetchStatus.SUCCESS,
                AxolotlService.completedFetchReport(own, remote));
        remote.put(2, AxolotlService.FetchStatus.PENDING);
        assertNull(AxolotlService.completedFetchReport(own, remote));
        remote.put(2, AxolotlService.FetchStatus.TIMEOUT);
        assertEquals(AxolotlService.FetchStatus.TIMEOUT,
                AxolotlService.completedFetchReport(own, remote));
        remote.put(2, AxolotlService.FetchStatus.ERROR);
        assertEquals(AxolotlService.FetchStatus.ERROR,
                AxolotlService.completedFetchReport(own, remote));
        remote.put(2, AxolotlService.FetchStatus.SUCCESS_VERIFIED);
        assertEquals(AxolotlService.FetchStatus.SUCCESS_VERIFIED,
                AxolotlService.completedFetchReport(own, remote));
    }
}
