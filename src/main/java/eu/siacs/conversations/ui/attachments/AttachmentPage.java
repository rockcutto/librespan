package eu.siacs.conversations.ui.attachments;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

public final class AttachmentPage {

    public final List<AttachmentEntry> entries;
    @Nullable public final Cursor nextCursor;
    public final boolean hasMore;

    public AttachmentPage(
            final List<AttachmentEntry> entries,
            @Nullable final Cursor nextCursor,
            final boolean hasMore) {
        this.entries = Collections.unmodifiableList(entries);
        this.nextCursor = nextCursor;
        this.hasMore = hasMore;
    }

    public static final class Cursor {
        public final long timeSent;
        public final String messageUuid;

        public Cursor(final long timeSent, final String messageUuid) {
            this.timeSent = timeSent;
            this.messageUuid = messageUuid;
        }
    }
}
