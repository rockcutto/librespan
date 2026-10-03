package eu.siacs.conversations.entities.media

import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.MediaGalleryPresentation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.storage.secure.SecureMessagePayloadMode
import eu.siacs.conversations.xml.Element
import eu.siacs.conversations.xml.Namespace
import eu.siacs.conversations.xmpp.Jid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCaptionPresentationTest {
    @Test
    fun incomingCaptionUsesRemoteMessageIdentity() {
        val conversation = conversation("incoming")
        val anchor = media(conversation, "anchor", ALICE)
        val caption = text(conversation, "caption", ALICE)
        attach(caption, "anchor")

        val presentation = presentation(conversation, anchor, caption)

        assertSame(caption, presentation.getCaption(anchor))
        assertTrue(presentation.isCaptionChild(caption))
        assertSame(anchor, MediaCaptionResolver.resolveAnchor(caption, listOf(anchor, caption)))
    }

    @Test
    fun protectedCaptionKeepsRelationWhileVerifiedBodyIsTemporarilyUnavailable() {
        val conversation = conversation("protected-caption")
        val anchor = media(conversation, "anchor", ALICE)
        val caption = text(conversation, "caption", ALICE)
        attach(caption, "anchor")
        caption.setSecureMessagePayloadMode(SecureMessagePayloadMode.PROTECTED)

        val beforeHydration = presentation(conversation, anchor, caption)

        assertSame(caption, beforeHydration.getCaption(anchor))
        assertTrue(beforeHydration.isCaptionChild(caption))
        assertTrue(caption.getBody().isEmpty())

        caption.setVerifiedProtectedBody("caption")
        val afterHydration = presentation(conversation, anchor, caption)

        assertSame(caption, afterHydration.getCaption(anchor))
        assertTrue(afterHydration.isCaptionChild(caption))
        assertEquals("caption", caption.getBody())
    }

    @Test
    fun incomingCaptionCanBindBeforeMediaMimeHydration() {
        val conversation = conversation("pending-mime")
        val anchor =
            Message(
                conversation,
                "https://upload.example/download",
                Message.ENCRYPTION_NONE,
                Message.STATUS_RECEIVED,
            ).apply {
                setType(Message.TYPE_FILE)
                setRemoteMsgId("anchor")
                setCounterpart(ALICE)
            }
        val caption = text(conversation, "caption", ALICE)
        attach(caption, "anchor")

        val presentation = presentation(conversation, anchor, caption)

        assertNull(anchor.getMimeType())
        assertSame(caption, presentation.getCaption(anchor))
        assertTrue(presentation.isCaptionChild(caption))
    }

    @Test
    fun knownNonMediaMimeStillRejectsCaptionPresentation() {
        val conversation = conversation("known-non-media")
        val anchor =
            Message(
                conversation,
                "https://upload.example/file.pdf",
                Message.ENCRYPTION_NONE,
                Message.STATUS_RECEIVED,
            ).apply {
                setType(Message.TYPE_FILE)
                setRemoteMsgId("anchor")
                setCounterpart(ALICE)
            }
        val caption = text(conversation, "caption", ALICE)
        attach(caption, "anchor")

        val presentation = presentation(conversation, anchor, caption)

        assertEquals("application/pdf", anchor.getMimeType())
        assertNull(presentation.getCaption(anchor))
        assertFalse(presentation.isCaptionChild(caption))
    }

    @Test
    fun outgoingCaptionUsesAnchorUuidWithoutRemoteEcho() {
        val conversation = conversation("outgoing")
        val anchor = outgoingMedia(conversation, "anchor-uuid", ALICE)
        val caption = outgoingText(conversation, ALICE)
        attach(caption, anchor.getUuid())

        val presentation = presentation(conversation, anchor, caption)

        assertNull(anchor.getRemoteMsgId())
        assertSame(caption, presentation.getCaption(anchor))
        assertTrue(presentation.isCaptionChild(caption))
        assertSame(anchor, MediaCaptionResolver.resolveAnchor(caption, listOf(anchor, caption)))
    }

    @Test
    fun editedCaptionKeepsItsMediaRelation() {
        val conversation = conversation("edited-caption")
        val anchor = outgoingMedia(conversation, "anchor-uuid", ALICE)
        val caption = outgoingText(conversation, ALICE)
        attach(caption, anchor.getUuid())
        caption.putEdited("previous-caption-uuid", null)

        val presentation = presentation(conversation, anchor, caption)

        assertSame(caption, presentation.getCaption(anchor))
        assertTrue(presentation.isCaptionChild(caption))
    }

    @Test
    fun outgoingAlbumCaptionSharesOnlyTheAnchorIdentity() {
        val conversation = conversation("outgoing-album")
        val anchor = outgoingMedia(conversation, "anchor-uuid", ALICE)
        val child = outgoingMedia(conversation, "child-uuid", ALICE)
        val caption = outgoingText(conversation, ALICE)
        attach(child, anchor.getUuid())
        attach(caption, anchor.getUuid())

        val presentation = presentation(conversation, anchor, child, caption)

        assertSame(caption, presentation.getCaption(anchor))
        assertTrue(presentation.isCaptionChild(caption))
        assertFalse(presentation.isCaptionChild(child))
    }

    @Test
    fun captionNeverBecomesAnAlbumMember() {
        val conversation = conversation("album-membership")
        val anchor = media(conversation, "anchor", ALICE)
        val child = media(conversation, "child", ALICE)
        val caption = text(conversation, "caption", ALICE)
        attach(child, "anchor")
        attach(caption, "anchor")
        conversation.add(anchor)
        conversation.add(child)
        conversation.add(caption)

        val captions = presentation(conversation, anchor, child, caption)
        val gallery = MediaGalleryPresentation.forSnapshot(conversation, 1, listOf(anchor, child, caption))

        assertSame(caption, captions.getCaption(anchor))
        assertEquals(listOf(anchor, child), gallery.getAlbum(anchor))
        assertFalse(gallery.getAlbum(anchor).contains(caption))
    }

    @Test
    fun duplicateCaptionsAndAmbiguousAnchorIdentityAreRejected() {
        val duplicateConversation = conversation("duplicate-captions")
        val duplicateAnchor = media(duplicateConversation, "anchor", ALICE)
        val firstCaption = text(duplicateConversation, "first", ALICE)
        val secondCaption = text(duplicateConversation, "second", ALICE)
        attach(firstCaption, "anchor")
        attach(secondCaption, "anchor")

        val duplicatePresentation = presentation(duplicateConversation, duplicateAnchor, firstCaption, secondCaption)

        assertNull(duplicatePresentation.getCaption(duplicateAnchor))
        assertFalse(duplicatePresentation.isCaptionChild(firstCaption))
        assertFalse(duplicatePresentation.isCaptionChild(secondCaption))

        val ambiguousConversation = conversation("ambiguous-anchor")
        val firstAnchor = media(ambiguousConversation, "same", ALICE)
        val secondAnchor = media(ambiguousConversation, "same", ALICE)
        val caption = text(ambiguousConversation, "caption", ALICE)
        attach(caption, "same")

        val ambiguousPresentation = presentation(ambiguousConversation, firstAnchor, secondAnchor, caption)

        assertNull(ambiguousPresentation.getCaption(firstAnchor))
        assertNull(ambiguousPresentation.getCaption(secondAnchor))
        assertFalse(ambiguousPresentation.isCaptionChild(caption))
    }

    @Test
    fun outgoingCaptionsAllowIndependentDeliveryStates() {
        val statusPairs = listOf(
            Message.STATUS_SEND to Message.STATUS_SEND,
            Message.STATUS_SEND_RECEIVED to Message.STATUS_SEND,
            Message.STATUS_SEND_DISPLAYED to Message.STATUS_SEND_RECEIVED,
        )

        for ((anchorStatus, captionStatus) in statusPairs) {
            val conversation = conversation("outgoing-$anchorStatus-$captionStatus")
            val anchor = outgoingMedia(conversation, "anchor-$anchorStatus-$captionStatus", ALICE)
            val caption = outgoingText(conversation, ALICE)
            anchor.setStatus(anchorStatus)
            caption.setStatus(captionStatus)
            attach(caption, anchor.getUuid())

            val presentation = presentation(conversation, anchor, caption)

            assertSame(caption, presentation.getCaption(anchor))
            assertTrue(presentation.isCaptionChild(caption))
        }
    }

    @Test
    fun captionsRejectCrossDirectionStatuses() {
        val incomingConversation = conversation("cross-incoming")
        val incomingAnchor = media(incomingConversation, "incoming-anchor", ALICE)
        val outgoingCaption = outgoingText(incomingConversation, ALICE)
        attach(outgoingCaption, "incoming-anchor")

        assertNull(presentation(incomingConversation, incomingAnchor, outgoingCaption).getCaption(incomingAnchor))
        assertFalse(presentation(incomingConversation, incomingAnchor, outgoingCaption).isCaptionChild(outgoingCaption))

        val outgoingConversation = conversation("cross-outgoing")
        val outgoingAnchor = outgoingMedia(outgoingConversation, "outgoing-anchor", ALICE)
        val incomingCaption = text(outgoingConversation, "incoming-caption", ALICE)
        attach(incomingCaption, outgoingAnchor.getUuid())

        assertNull(presentation(outgoingConversation, outgoingAnchor, incomingCaption).getCaption(outgoingAnchor))
        assertFalse(presentation(outgoingConversation, outgoingAnchor, incomingCaption).isCaptionChild(incomingCaption))
    }

    @Test
    fun rebuildingAfterDeliveryStateChangesKeepsCaptionRelation() {
        val conversation = conversation("status-rebuild")
        val anchor = outgoingMedia(conversation, "anchor-uuid", ALICE)
        val caption = outgoingText(conversation, ALICE)
        attach(caption, anchor.getUuid())
        val snapshot = listOf(anchor, caption)

        val initial = MediaCaptionPresentation.forSnapshot(conversation, snapshot)
        anchor.setStatus(Message.STATUS_SEND_RECEIVED)

        assertFalse(initial.matchesSnapshot(conversation, snapshot))
        val afterAnchorStatus = MediaCaptionPresentation.forSnapshot(conversation, snapshot)
        assertSame(caption, afterAnchorStatus.getCaption(anchor))

        caption.setStatus(Message.STATUS_SEND_DISPLAYED)

        assertFalse(afterAnchorStatus.matchesSnapshot(conversation, snapshot))
        assertSame(caption, MediaCaptionPresentation.forSnapshot(conversation, snapshot).getCaption(anchor))
    }

    @Test
    fun wrongCounterpartConversationAndDirectionAreRejected() {
        val conversation = conversation("invalid")
        val anchor = media(conversation, "anchor", ALICE)
        val wrongCounterpart = text(conversation, "caption", BOB)
        attach(wrongCounterpart, "anchor")
        assertNull(presentation(conversation, anchor, wrongCounterpart).getCaption(anchor))

        val otherConversation = conversation("other")
        val foreignCaption = text(otherConversation, "caption", ALICE)
        attach(foreignCaption, "anchor")
        assertNull(presentation(conversation, anchor, foreignCaption).getCaption(anchor))

        val outgoingCaption = outgoingText(conversation, ALICE)
        attach(outgoingCaption, "anchor")
        assertNull(presentation(conversation, anchor, outgoingCaption).getCaption(anchor))
    }

    @Test
    fun captionsCannotAttachToMediaChildrenOrSpecialText() {
        val conversation = conversation("special")
        val anchor = media(conversation, "anchor", ALICE)
        val mediaChild = media(conversation, "child", ALICE)
        val captionOnChild = text(conversation, "caption", ALICE)
        attach(mediaChild, "anchor")
        attach(captionOnChild, "child")
        assertNull(presentation(conversation, anchor, mediaChild, captionOnChild).getCaption(anchor))

        val reply = text(conversation, "reply", ALICE)
        attach(reply, "anchor")
        reply.addPayload(Element("reply", "urn:xmpp:reply:0"))
        assertFalse(presentation(conversation, anchor, reply).isCaptionChild(reply))

        val reaction = text(conversation, "reaction", ALICE)
        attach(reaction, "anchor")
        reaction.addPayload(Element("reactions", "urn:xmpp:reactions:0"))
        assertFalse(presentation(conversation, anchor, reaction).isCaptionChild(reaction))

        val empty = text(conversation, "empty", ALICE, "  ")
        attach(empty, "anchor")
        assertFalse(presentation(conversation, anchor, empty).isCaptionChild(empty))
    }

    @Test
    fun mucAndPrivateCaptionsAreRejected() {
        val muc = conversation("muc", Conversation.MODE_MULTI)
        val mucAnchor = media(muc, "anchor", ALICE)
        val mucCaption = text(muc, "caption", ALICE)
        attach(mucCaption, "anchor")
        assertNull(presentation(muc, mucAnchor, mucCaption).getCaption(mucAnchor))

        val direct = conversation("private")
        val privateAnchor = media(direct, "anchor", ALICE)
        val privateCaption = text(direct, "caption", ALICE)
        privateCaption.setType(Message.TYPE_PRIVATE)
        attach(privateCaption, "anchor")
        assertNull(presentation(direct, privateAnchor, privateCaption).getCaption(privateAnchor))
    }

    private fun presentation(conversation: Conversation, vararg messages: Message): MediaCaptionPresentation =
        MediaCaptionPresentation.forSnapshot(conversation, messages.toList())

    private fun conversation(id: String, mode: Int = Conversation.MODE_SINGLE): Conversation =
        Conversation(id, "test", null, "account", Jid.of("contact@example.test"), 0,
            Conversation.STATUS_AVAILABLE, mode, "", null)

    private fun media(conversation: Conversation, remoteId: String, counterpart: Jid): Message =
        Message(conversation, "https://upload.example/$remoteId.jpg", Message.ENCRYPTION_NONE,
            Message.STATUS_RECEIVED).apply {
            setType(Message.TYPE_IMAGE)
            setRemoteMsgId(remoteId)
            setCounterpart(counterpart)
        }

    private fun outgoingMedia(conversation: Conversation, uuid: String, counterpart: Jid): Message =
        Message(conversation, "https://upload.example/$uuid.jpg", Message.ENCRYPTION_NONE,
            Message.STATUS_SEND).apply {
            setType(Message.TYPE_IMAGE)
            setUuid(uuid)
            setRemoteMsgId(null)
            setCounterpart(counterpart)
        }

    private fun text(conversation: Conversation, remoteId: String, counterpart: Jid, body: String = "caption"): Message =
        Message(conversation, body, Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED).apply {
            setRemoteMsgId(remoteId)
            setCounterpart(counterpart)
        }

    private fun outgoingText(conversation: Conversation, counterpart: Jid): Message =
        Message(conversation, "caption", Message.ENCRYPTION_NONE, Message.STATUS_SEND).apply {
            setRemoteMsgId(null)
            setCounterpart(counterpart)
        }

    private fun attach(message: Message, anchorId: String) {
        message.addPayload(Element("attach-to", Namespace.MESSAGE_ATTACHING).setAttribute("id", anchorId))
    }

    private companion object {
        val ALICE: Jid = Jid.of("alice@example.test")
        val BOB: Jid = Jid.of("bob@example.test")
    }
}
