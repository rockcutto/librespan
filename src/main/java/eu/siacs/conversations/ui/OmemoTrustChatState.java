package eu.siacs.conversations.ui;

/**
 * Presentation state for OMEMO trust guidance in a one-to-one chat. Never changes encryption or
 * trust decisions.
 */
public enum OmemoTrustChatState {
    HIDDEN,
    FETCHING,
    TRUST_REQUIRED;

    public static OmemoTrustChatState resolve(
            final boolean omemoDirectChat,
            final boolean hasUsableRemoteSession,
            final boolean hasKnownActiveRemoteSession,
            final boolean fetchInProgress) {
        if (!omemoDirectChat || hasUsableRemoteSession) {
            return HIDDEN;
        }
        if (fetchInProgress) {
            return FETCHING;
        }
        if (hasKnownActiveRemoteSession) {
            return TRUST_REQUIRED;
        }
        return HIDDEN;
    }
}
