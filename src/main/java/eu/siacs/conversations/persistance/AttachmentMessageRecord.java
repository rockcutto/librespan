package eu.siacs.conversations.persistance;

import androidx.annotation.Nullable;

/** Lightweight persisted message facts used by the attachment browser. */
public final class AttachmentMessageRecord {

    public final String messageUuid;
    public final String conversationUuid;
    @Nullable public final String relativeFilePath;
    @Nullable public final String body;
    public final int type;
    public final long timeSent;

    public AttachmentMessageRecord(
            final String messageUuid,
            final String conversationUuid,
            @Nullable final String relativeFilePath,
            @Nullable final String body,
            final int type,
            final long timeSent) {
        this.messageUuid = messageUuid;
        this.conversationUuid = conversationUuid;
        this.relativeFilePath = relativeFilePath;
        this.body = body;
        this.type = type;
        this.timeSent = timeSent;
    }
}
