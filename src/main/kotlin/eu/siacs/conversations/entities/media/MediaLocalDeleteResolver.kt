package eu.siacs.conversations.entities.media

import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.MediaGalleryPresentation
import eu.siacs.conversations.entities.Message
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Resolves the locally rendered media presentation that should disappear as one unit.
 *
 * A single media item with a caption, an XEP-0367 album, and a local outgoing media group are all
 * presented as one visual entity. Local deletion therefore removes the presentation members
 * together instead of leaving an orphan caption or a broken partial album.
 */
object MediaLocalDeleteResolver {
    @JvmStatic
    fun resolveDeleteTargets(
        conversation: Conversation,
        selected: Message,
        snapshot: List<Message>,
    ): List<Message> {
        if (selected.conversation !== conversation || snapshot.none { it === selected }) {
            return listOf(selected)
        }

        val captionAnchor = MediaCaptionResolver.resolveAnchor(selected, snapshot)
        val mediaSeed =
            captionAnchor
                ?: selected.takeIf { it.isFileOrImage() || it.treatAsDownloadable() }
                ?: return listOf(selected)

        val related =
            Collections.newSetFromMap(IdentityHashMap<Message, Boolean>())
        related.add(mediaSeed)

        val mediaGroupId = mediaSeed.mediaGroupId
        if (!mediaGroupId.isNullOrBlank()) {
            snapshot.forEach { candidate ->
                if (candidate.conversation === conversation &&
                    candidate.mediaGroupId == mediaGroupId &&
                    (candidate.isFileOrImage() || candidate.treatAsDownloadable())
                ) {
                    related.add(candidate)
                }
            }
        }

        MediaGalleryPresentation
            .forSnapshot(conversation, 0L, snapshot)
            .getAlbum(mediaSeed)
            .forEach(related::add)

        related.toList().forEach { media ->
            MediaCaptionResolver.getCaption(media, snapshot)?.let(related::add)
        }

        if (captionAnchor != null) {
            related.add(selected)
        }

        val ordered = snapshot.filter { related.contains(it) }
        return if (ordered.isEmpty()) listOf(selected) else ordered
    }
}
