package eu.siacs.conversations.ui.adapter;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import eu.siacs.conversations.entities.Message;

/**
 * Direction-neutral source of an already validated media album.
 *
 * <p>Implementations return an empty list when no album is available. They do not parse protocol
 * metadata or mutate message state.</p>
 */
@FunctionalInterface
public interface MediaAlbumSource {

    List<Message> getAlbum(@Nullable Message message);

    default boolean isContinuation(@Nullable final Message message) {
        final List<Message> album = getAlbum(message);
        return album.size() > 1 && album.get(0) != message;
    }

    static MediaAlbumSource empty() {
        return ignored -> Collections.emptyList();
    }

    static MediaAlbumSource select(
            final Predicate<Message> predicate,
            final MediaAlbumSource selected,
            final MediaAlbumSource fallback) {
        Objects.requireNonNull(predicate);
        Objects.requireNonNull(selected);
        Objects.requireNonNull(fallback);
        return message -> {
            final List<Message> album =
                    message != null && predicate.test(message)
                            ? selected.getAlbum(message)
                            : fallback.getAlbum(message);
            return album == null ? Collections.emptyList() : album;
        };
    }
}
