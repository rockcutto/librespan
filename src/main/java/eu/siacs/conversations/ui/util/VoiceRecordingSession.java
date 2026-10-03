package eu.siacs.conversations.ui.util;

import androidx.annotation.Nullable;

import eu.siacs.conversations.entities.Message;

/**
 * Process-memory owner for the one active inline voice recorder.
 *
 * <p>The recorder itself already uses the application context. Keeping this narrow session separate
 * from a ConversationFragment lets a theme/configuration recreation replace the View without
 * cancelling audio. Process death is intentionally outside this holder's scope.</p>
 */
public final class VoiceRecordingSession {

    private static final Object LOCK = new Object();

    @Nullable private static Session activeSession;

    private VoiceRecordingSession() {}

    @Nullable
    public static Session start(
            final String conversationUuid,
            @Nullable final Message replyTo,
            final VoiceRecorder recorder) {
        synchronized (LOCK) {
            if (activeSession != null) {
                return null;
            }
            activeSession = new Session(conversationUuid, replyTo, recorder);
            return activeSession;
        }
    }

    @Nullable
    public static Session getForConversation(final String conversationUuid) {
        synchronized (LOCK) {
            if (activeSession == null
                    || !activeSession.getConversationUuid().equals(conversationUuid)) {
                return null;
            }
            return activeSession;
        }
    }

    @Nullable
    public static Session takeForConversation(final String conversationUuid) {
        synchronized (LOCK) {
            if (activeSession == null
                    || !activeSession.getConversationUuid().equals(conversationUuid)) {
                return null;
            }
            final Session session = activeSession;
            activeSession = null;
            return session;
        }
    }

    public static final class Session {

        private final String conversationUuid;
        @Nullable private final Message replyTo;
        private final VoiceRecorder recorder;
        private boolean locked;

        private Session(
                final String conversationUuid,
                @Nullable final Message replyTo,
                final VoiceRecorder recorder) {
            this.conversationUuid = conversationUuid;
            this.replyTo = replyTo;
            this.recorder = recorder;
        }

        public String getConversationUuid() {
            return conversationUuid;
        }

        @Nullable
        public Message getReplyTo() {
            return replyTo;
        }

        public VoiceRecorder getRecorder() {
            return recorder;
        }

        public synchronized boolean isLocked() {
            return locked;
        }

        public synchronized void setLocked(final boolean locked) {
            this.locked = locked;
        }
    }
}
