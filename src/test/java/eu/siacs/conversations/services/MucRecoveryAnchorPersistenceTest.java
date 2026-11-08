package eu.siacs.conversations.services;

import static org.junit.Assert.*;
import android.content.ContentValues;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.mam.MamReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Existing row serialization, no new table or DB version; full SQLite/SCS is a separate boundary. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class MucRecoveryAnchorPersistenceTest {
    private Account account = new Account(Jid.of("owner@example.test"), "");
    private Conversation room() {
        return new Conversation("room", account, Jid.of("room@conference.example"), Conversation.MODE_MULTI, null);
    }
    private Conversation restore(Conversation original) {
        ContentValues row = original.getContentValues();
        Conversation restored = new Conversation(row.getAsString(Conversation.UUID), row.getAsString(Conversation.NAME),
                row.getAsString(Conversation.CONTACT), row.getAsString(Conversation.ACCOUNT),
                Jid.of(row.getAsString(Conversation.CONTACTJID)), row.getAsLong(Conversation.CREATED),
                row.getAsInteger(Conversation.STATUS), row.getAsInteger(Conversation.MODE),
                row.getAsString(Conversation.ATTRIBUTES), null);
        restored.setAccount(account);
        return restored;
    }
    @Test public void opaqueArchiveIdsRoundTripWithoutColonTruncation() {
        Conversation first = room(); first.setMucRecoveryAnchor(new MamReference(123456L, "id:with:colons/and-unicode-λ"));
        Conversation second = restore(first);
        assertEquals(123456L, second.getMucRecoveryAnchor().getTimestamp());
        assertEquals("id:with:colons/and-unicode-λ", second.getMucRecoveryAnchor().getReference());
        second.setMucRecoveryAnchor(null); assertNull(restore(second).getMucRecoveryAnchor());
    }
    @Test public void floorCannotBeReusedForAnotherAccountOrRoom() {
        Conversation first = room(); first.setMucRecoveryAnchor(new MamReference(123456L, "A"));
        Conversation second = restore(first);
        second.setAccount(new Account(Jid.of("other@example.test"), ""));
        assertNull(second.getMucRecoveryAnchor());
        ContentValues row = first.getContentValues();
        Conversation other = new Conversation("other", "other", null, account.getUuid(),
                Jid.of("other@conference.example"), 1, Conversation.STATUS_AVAILABLE, Conversation.MODE_MULTI,
                row.getAsString(Conversation.ATTRIBUTES), null);
        other.setAccount(account); assertNull(other.getMucRecoveryAnchor());
    }
    @Test public void explicitHistoryClearIsALowerBoundEvenWithAnOlderRecoveryMarker() {
        Conversation first = room(); first.setMucRecoveryAnchor(new MamReference(1000L, "A"));
        first.setLastClearHistory(2000L, "clear");
        assertEquals(2000L, restore(first).getMucRecoveryAnchor().getTimestamp());
        assertEquals("clear", restore(first).getMucRecoveryAnchor().getReference());
    }
    @Test public void malformedFloorFailsConservativelyInsteadOfUsingNewLiveAnchor() {
        Conversation first = room(); first.setAttribute("muc_recovery_anchor", "broken-json");
        assertNotNull(restore(first).getMucRecoveryAnchor());
        assertEquals(0L, restore(first).getMucRecoveryAnchor().getTimestamp());
    }
}
