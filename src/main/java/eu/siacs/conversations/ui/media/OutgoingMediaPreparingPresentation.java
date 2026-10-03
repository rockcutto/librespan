package eu.siacs.conversations.ui.media;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;

import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.ui.util.Attachment;

/**
 * UI-only metadata for outgoing media that is still being committed to Secure Content.
 *
 * <p>Entries deliberately key on the in-memory placeholder {@link Message}; neither the
 * placeholder nor this presentation is persisted.</p>
 */
public final class OutgoingMediaPreparingPresentation {

    private final IdentityHashMap<Message, Entry> entries = new IdentityHashMap<>();

    public synchronized void add(
            final Message placeholder,
            @Nullable final String caption,
            final List<Attachment> attachments) {
        entries.put(
                placeholder,
                new Entry(
                        caption == null ? "" : caption,
                        Collections.unmodifiableList(new ArrayList<>(attachments))));
    }

    public synchronized boolean contains(@Nullable final Message placeholder) {
        return placeholder != null && entries.containsKey(placeholder);
    }

    @Nullable
    public synchronized String getCaption(@Nullable final Message placeholder) {
        final Entry entry = placeholder == null ? null : entries.get(placeholder);
        return entry == null ? null : entry.caption;
    }

    public synchronized List<Attachment> getAttachments(@Nullable final Message placeholder) {
        final Entry entry = placeholder == null ? null : entries.get(placeholder);
        return entry == null ? Collections.emptyList() : entry.attachments;
    }

    public synchronized void remove(@Nullable final Message placeholder) {
        if (placeholder != null) {
            entries.remove(placeholder);
        }
    }

    private static final class Entry {
        private final String caption;
        private final List<Attachment> attachments;

        private Entry(final String caption, final List<Attachment> attachments) {
            this.caption = caption;
            this.attachments = attachments;
        }
    }
}
