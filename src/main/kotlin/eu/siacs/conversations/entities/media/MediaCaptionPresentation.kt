package eu.siacs.conversations.entities.media

import java.util.ArrayList
import java.util.Collections
import java.util.IdentityHashMap
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.xml.Element

class MediaCaptionPresentation private constructor(
    private val conversation: Conversation,
    private val snapshot: List<Message>,
    private val relationStates: List<String>,
    private val captionsByAnchor: Map<Message, Message>,
    private val captionChildren: Set<Message>,
) {
    fun getCaption(anchor: Message?): Message? =
        if (anchor == null) null else captionsByAnchor[anchor]

    fun isCaptionChild(message: Message?): Boolean =
        message != null && captionChildren.contains(message)

    fun matchesSnapshot(conversation: Conversation, snapshot: List<Message>): Boolean =
        this.conversation === conversation &&
            this.snapshot == snapshot &&
            relationStates == relationStates(snapshot)

    companion object {
        @JvmStatic
        fun forSnapshot(conversation: Conversation, snapshot: List<Message>): MediaCaptionPresentation {
            val messages = Collections.unmodifiableList(ArrayList(snapshot))
            val candidatesByAnchor = IdentityHashMap<Message, MutableList<Message>>()

            for (candidate in messages) {
                if (candidate.getConversation() !== conversation) {
                    continue
                }
                val anchor = MediaCaptionResolver.resolveAnchor(candidate, messages) ?: continue
                candidatesByAnchor.getOrPut(anchor) { ArrayList() }.add(candidate)
            }

            val captionsByAnchor = IdentityHashMap<Message, Message>()
            val captionChildren = Collections.newSetFromMap(IdentityHashMap<Message, Boolean>())
            for ((anchor, candidates) in candidatesByAnchor) {
                if (candidates.size == 1) {
                    val caption = candidates[0]
                    captionsByAnchor[anchor] = caption
                    captionChildren.add(caption)
                }
            }

            return MediaCaptionPresentation(
                conversation = conversation,
                snapshot = messages,
                relationStates = relationStates(messages),
                captionsByAnchor = Collections.unmodifiableMap(captionsByAnchor),
                captionChildren = Collections.unmodifiableSet(captionChildren),
            )
        }

        private fun relationStates(messages: List<Message>): List<String> {
            val states = ArrayList<String>(messages.size)
            for (message in messages) {
                val state = StringBuilder()
                state.append(message.getUuid()).append('\u0000')
                state.append(message.getRemoteMsgId()).append('\u0000')
                state.append(message.getCounterpart()).append('\u0000')
                state.append(message.getStatus()).append('\u0000')
                state.append(message.getType()).append('\u0000')
                state.append(message.getBody()).append('\u0000')
                state.append(message.isPrivateMessage()).append('\u0000')
                state.append(message.isFileOrImage()).append('\u0000')
                state.append(message.treatAsDownloadable()).append('\u0000')
                state.append(message.isOOb()).append('\u0000')
                state.append(message.isGeoUri()).append('\u0000')
                state.append(message.getReplyOrReaction() != null).append('\u0000')
                state.append(message.edited()).append('\u0000')
                state.append(message.getMimeType()).append('\u0000')
                for (payload: Element in message.getPayloads()) {
                    state.append(payload).append('\u0000')
                }
                states.add(state.toString())
            }
            return Collections.unmodifiableList(states)
        }
    }
}
