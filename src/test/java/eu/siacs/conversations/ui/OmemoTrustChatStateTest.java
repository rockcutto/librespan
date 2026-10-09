package eu.siacs.conversations.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class OmemoTrustChatStateTest {

    @Test
    public void noWarningOutsideOmemoDirectChat() {
        assertEquals(
                OmemoTrustChatState.HIDDEN, OmemoTrustChatState.resolve(false, false, true, false));
    }

    @Test
    public void trustedSessionNeedsNoWarning() {
        assertEquals(
                OmemoTrustChatState.HIDDEN, OmemoTrustChatState.resolve(true, true, true, false));
    }

    @Test
    public void activeUntrustedSessionRequiresTrust() {
        assertEquals(
                OmemoTrustChatState.TRUST_REQUIRED,
                OmemoTrustChatState.resolve(true, false, true, false));
    }

    @Test
    public void pendingKeysAreNotMisreportedAsUntrusted() {
        assertEquals(
                OmemoTrustChatState.FETCHING, OmemoTrustChatState.resolve(true, false, true, true));
    }

    @Test
    public void unknownKeysAreNotMisreportedAsUntrusted() {
        assertEquals(
                OmemoTrustChatState.HIDDEN, OmemoTrustChatState.resolve(true, false, false, false));
    }

    @Test
    public void pendingInitialKeyFetchShowsProgress() {
        assertEquals(
                OmemoTrustChatState.FETCHING,
                OmemoTrustChatState.resolve(true, false, false, true));
    }
}
