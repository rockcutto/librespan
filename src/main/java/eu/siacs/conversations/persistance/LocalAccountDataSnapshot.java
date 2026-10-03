package eu.siacs.conversations.persistance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable ownership snapshot captured before local account message data is removed. */
public final class LocalAccountDataSnapshot {

    public static final class ConversationClearMarker {
        private final String conversationUuid;
        private final long clearTimestamp;
        private final String serverMessageId;

        public ConversationClearMarker(
                final String conversationUuid,
                final long clearTimestamp,
                final String serverMessageId) {
            this.conversationUuid = conversationUuid;
            this.clearTimestamp = clearTimestamp;
            this.serverMessageId = serverMessageId;
        }

        public String getConversationUuid() {
            return conversationUuid;
        }

        public long getClearTimestamp() {
            return clearTimestamp;
        }

        public String getServerMessageId() {
            return serverMessageId;
        }
    }

    private final String accountUuid;
    private final List<ConversationClearMarker> conversations;
    private final List<String> messageUuids;
    private final List<String> legacyMediaPaths;

    public LocalAccountDataSnapshot(
            final String accountUuid,
            final List<ConversationClearMarker> conversations,
            final List<String> messageUuids,
            final List<String> legacyMediaPaths) {
        this.accountUuid = accountUuid;
        this.conversations = Collections.unmodifiableList(new ArrayList<>(conversations));
        this.messageUuids = Collections.unmodifiableList(new ArrayList<>(messageUuids));
        this.legacyMediaPaths = Collections.unmodifiableList(new ArrayList<>(legacyMediaPaths));
    }

    public String getAccountUuid() {
        return accountUuid;
    }

    public List<ConversationClearMarker> getConversations() {
        return conversations;
    }

    public List<String> getMessageUuids() {
        return messageUuids;
    }

    public List<String> getLegacyMediaPaths() {
        return legacyMediaPaths;
    }
}
