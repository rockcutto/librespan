package eu.siacs.conversations.services;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public class MucPasswordChangeTest {
    private final MemoryVault vault = new MemoryVault();
    private final AtomicBoolean current = new AtomicBoolean(true);
    private final MucPasswordChange change = new MucPasswordChange(vault, current::get);

    @Test public void enablingStagesWithoutReplacingWorkingAccessBeforeAck() throws Exception {
        assertTrue(change.prepare(true, "new password", false));
        assertEquals("old password", vault.muc);
        assertEquals("old password", vault.bookmark);
        assertFalse(vault.record.attempted);
        assertNotNull(change.markSent());
        assertEquals(MucPasswordChange.Outcome.SUCCESS, change.finish(MucPasswordChange.Reply.RESULT));
        assertEquals("new password", vault.muc);
        assertEquals("new password", vault.bookmark);
        assertNull(vault.record);
    }

    @Test public void disableDeletesBothSecretsOnlyAfterServerResultAndKeepsTombstone() throws Exception {
        assertTrue(change.prepare(false, null, false));
        change.markSent();
        assertEquals("old password", vault.muc);
        assertEquals(MucPasswordChange.Outcome.SUCCESS, change.finish(MucPasswordChange.Reply.RESULT));
        assertNull(vault.muc); assertNull(vault.bookmark);
        assertTrue(vault.record.disabledConfirmed);
        assertNull(vault.record.password);
        assertFalse(vault.record.pending());
    }

    @Test public void explicitServerRefusalPreservesWorkingPassword() throws Exception {
        change.prepare(true, "new password", false); change.markSent();
        assertEquals(MucPasswordChange.Outcome.REJECTED, change.finish(MucPasswordChange.Reply.ERROR));
        assertEquals("old password", vault.muc); assertEquals("old password", vault.bookmark);
        assertNull(vault.record);
    }

    @Test public void timeoutRetainsBothOldAccessAndDurableCandidate() throws Exception {
        change.prepare(true, "new password", false); change.markSent();
        assertEquals(MucPasswordChange.Outcome.UNCERTAIN, change.finish(MucPasswordChange.Reply.TIMEOUT));
        assertEquals("old password", vault.muc);
        assertEquals("new password", vault.record.password);
        assertEquals("old password", vault.record.previousPassword);
        assertTrue(vault.record.attempted);
    }

    @Test public void refusalOfRetryCannotErasePriorTimedOutCandidate() throws Exception {
        change.prepare(true, "new password", false); change.markSent();
        change.finish(MucPasswordChange.Reply.TIMEOUT);
        MucPasswordChange retry = new MucPasswordChange(vault, current::get);
        assertTrue(retry.prepare(false, null, true));
        retry.markSent();
        assertEquals(MucPasswordChange.Outcome.UNCERTAIN, retry.finish(MucPasswordChange.Reply.ERROR));
        assertEquals("new password", vault.record.password);
        assertEquals("old password", vault.muc);
    }

    @Test public void retryAfterRestartCanFinishOriginalOperation() throws Exception {
        change.prepare(true, "new password", false); change.markSent();
        vault.record = MucPasswordChange.Record.decode(vault.record.encode());
        MucPasswordChange restarted = new MucPasswordChange(vault, current::get);
        assertTrue(restarted.prepare(false, null, true));
        restarted.markSent();
        assertEquals(MucPasswordChange.Outcome.SUCCESS, restarted.finish(MucPasswordChange.Reply.RESULT));
        assertEquals("new password", vault.muc);
    }

    @Test public void preparedButUnsentCandidateCanBeRetriedAfterUnlock() throws Exception {
        change.prepare(true, "new password", false);
        MucPasswordChange retry = new MucPasswordChange(vault, current::get);
        assertTrue(retry.prepare(false, null, true));
        retry.markSent();
        assertEquals(MucPasswordChange.Outcome.REJECTED, retry.finish(MucPasswordChange.Reply.ERROR));
        assertNull(vault.record);
        assertEquals("old password", vault.muc);
    }

    @Test public void vaultWriteFailureCannotAuthorizeSend() throws Exception {
        vault.failRecord = true;
        assertFalse(change.prepare(true, "new password", false));
        assertNull(change.markSent());
        assertEquals("old password", vault.muc);
    }

    @Test public void unavailableOrCorruptPendingReadIsNeverTreatedAsAbsent() {
        vault.failRead = true;
        assertThrows(IOException.class, () -> change.prepare(true, "new password", false));
        assertEquals("old password", vault.muc);
    }

    @Test public void partialLocalCommitKeepsCandidateForRecovery() throws Exception {
        change.prepare(true, "new password", false); change.markSent();
        vault.failBookmark = true;
        assertEquals(MucPasswordChange.Outcome.UNCERTAIN, change.finish(MucPasswordChange.Reply.RESULT));
        assertEquals("new password", vault.muc);
        assertEquals("old password", vault.bookmark);
        assertEquals("new password", vault.record.password);
        vault.failBookmark = false;
        MucPasswordChange retry = new MucPasswordChange(vault, current::get);
        assertTrue(retry.prepare(false, null, true)); retry.markSent();
        assertEquals(MucPasswordChange.Outcome.SUCCESS, retry.finish(MucPasswordChange.Reply.RESULT));
    }

    @Test public void journalCleanupFailureAfterAckRetainsCandidateAndPreviousAccess() throws Exception {
        change.prepare(true, "new password", false); change.markSent();
        vault.failRecord = true;
        assertEquals(MucPasswordChange.Outcome.UNCERTAIN, change.finish(MucPasswordChange.Reply.RESULT));
        assertEquals("new password", vault.muc);
        assertEquals("new password", vault.bookmark);
        assertEquals("new password", vault.record.password);
        assertEquals("old password", vault.record.previousPassword);
    }

    @Test public void failureToPersistSendIntentCannotAuthorizeSet() throws Exception {
        change.prepare(true, "new password", false);
        vault.failRecord = true;
        assertNull(change.markSent());
        assertFalse(vault.record.attempted);
        assertEquals("old password", vault.muc);
    }

    @Test public void lockBeforePrepareOrSendBlocksChanges() throws Exception {
        current.set(false);
        assertFalse(change.prepare(true, "new password", false));
        assertNull(vault.record);
        current.set(true); change.prepare(true, "new password", false);
        current.set(false); assertNull(change.markSent());
        assertFalse(vault.record.attempted);
    }

    @Test public void lockDuringIqDoesNotPublishSuccessOrRollback() throws Exception {
        change.prepare(true, "new password", false); change.markSent(); current.set(false);
        assertEquals(MucPasswordChange.Outcome.LOCKED, change.finish(MucPasswordChange.Reply.RESULT));
        assertEquals("old password", vault.muc);
        assertEquals("new password", vault.record.password);
    }

    @Test public void epochChangeDuringCommitRetainsJournalAndNeverSaysSuccess() throws Exception {
        change.prepare(true, "new password", false); change.markSent();
        vault.onMucWrite = () -> current.set(false);
        assertEquals(MucPasswordChange.Outcome.UNCERTAIN, change.finish(MucPasswordChange.Reply.RESULT));
        assertNotNull(vault.record);
        assertEquals("old password", vault.bookmark);
    }

    @Test public void secondOperationCannotOverwritePendingAndRoomsRemainIndependent() throws Exception {
        assertTrue(change.prepare(true, "first", false));
        assertFalse(new MucPasswordChange(vault, current::get).prepare(true, "second", false));
        assertEquals("first", vault.record.password);
        MemoryVault otherRoom = new MemoryVault();
        MucPasswordChange other = new MucPasswordChange(otherRoom, current::get);
        assertTrue(other.prepare(true, "other", false)); other.markSent();
        assertEquals(MucPasswordChange.Outcome.SUCCESS, other.finish(MucPasswordChange.Reply.RESULT));
        assertEquals("old password", vault.muc);
        assertEquals("other", otherRoom.muc);
    }

    @Test public void newPasswordAfterDisableRestoresDisabledMarkerOnRefusal() throws Exception {
        vault.record = MucPasswordChange.Record.disabled(); vault.muc = null; vault.bookmark = null;
        change.prepare(true, "new password", false); change.markSent();
        assertEquals(MucPasswordChange.Outcome.REJECTED, change.finish(MucPasswordChange.Reply.ERROR));
        assertTrue(vault.record.disabledConfirmed); assertNull(vault.muc);
    }

    @Test public void unsupportedFormOrGetFailureCanRollbackOnlyUnsentPreparation() throws Exception {
        change.prepare(true, "new password", false);
        assertTrue(change.rollbackUnsent()); assertNull(vault.record);
        change.prepare(true, "new password", false); change.markSent();
        assertFalse(change.rollbackUnsent()); assertEquals("new password", vault.record.password);
    }

    @Test public void disablingWithVaultFailureRetainsRecoveryIntent() throws Exception {
        change.prepare(false, null, false); change.markSent(); vault.failBookmark = true;
        assertEquals(MucPasswordChange.Outcome.UNCERTAIN, change.finish(MucPasswordChange.Reply.RESULT));
        assertNotNull(vault.record); assertFalse(vault.record.enabled); assertTrue(vault.record.attempted);
    }

    @Test public void opaquePasswordIsNotTrimmedAndBadXmlTextIsRejected() throws Exception {
        assertFalse(MucPasswordChange.validPassword(""));
        assertFalse(MucPasswordChange.validPassword("  \t"));
        assertFalse(MucPasswordChange.validPassword("a\u0000b"));
        assertFalse(MucPasswordChange.validPassword("\ud800"));
        assertFalse(MucPasswordChange.validPassword("x".repeat(1025)));
        assertTrue(MucPasswordChange.validPassword("  secret  "));
        change.prepare(true, "  secret  ", false); change.markSent();
        change.finish(MucPasswordChange.Reply.RESULT);
        assertEquals("  secret  ", vault.muc);
    }

    @Test public void recordRoundTripPreservesCandidateAndManualSelectionWithoutPublicToString() throws Exception {
        MucPasswordChange.Record record = new MucPasswordChange.Record(true, "opaque secret", true, false, false).withPrevious("previous secret").manual();
        MucPasswordChange.Record decoded = MucPasswordChange.Record.decode(record.encode());
        assertEquals("opaque secret", decoded.password); assertTrue(decoded.manualSelection);
        assertEquals("previous secret", decoded.previousPassword);
        assertFalse(decoded.toString().contains("opaque secret"));
        assertFalse(decoded.sent().manualSelection);
        assertTrue(MucPasswordChange.Record.decode(MucPasswordChange.Record.disabled().encode()).disabledConfirmed);
        assertThrows(IOException.class, () -> MucPasswordChange.Record.decode(new byte[] {1, 2}));
    }

    private static final class MemoryVault implements MucPasswordChange.Vault {
        MucPasswordChange.Record record;
        String muc = "old password", bookmark = "old password";
        boolean failRecord, failRead, failBookmark;
        Runnable onMucWrite = () -> {};
        public String previousPassword() { return muc == null ? bookmark : muc; }
        public MucPasswordChange.Record read() throws IOException {
            if (failRead) throw new IOException("vault unavailable");
            return record;
        }
        public boolean write(MucPasswordChange.Record value) { if (failRecord) return false; record = value; return true; }
        public boolean mucPassword(String value) { muc = value; onMucWrite.run(); return true; }
        public boolean bookmarkPassword(String value) { if (failBookmark) return false; bookmark = value; return true; }
    }
}
