package eu.siacs.conversations.storage.secure

import java.io.IOException
import java.io.InputStream

/**
 * First runtime seam for outgoing attachment creation migration.
 *
 * This class intentionally does not replace AttachFileToConversationRunnable yet.
 * It introduces the Secure Content ownership boundary that future attachment producers must use.
 *
 * Input sources may be URI readers, generated media streams, recordings or transcoder outputs.
 * The caller owns opening the source stream; this seam owns publishing the bytes into Secure
 * Content and returning the committed logical binding.
 */
class SecureOutgoingAttachmentIngress(
    private val mediaCoordinator: SecureMessageMediaCoordinator,
) {

    /**
     * Publishes one outgoing attachment source into Secure Content.
     *
     * The returned binding is valid only after the Store commit succeeds. No FileBackend path,
     * URI, blob location or plaintext attachment authority is created by this operation.
     */
    @Throws(IOException::class)
    fun ingest(
        accountUuid: String,
        messageUuid: String,
        source: InputStream,
        mimeType: String? = null,
        fileName: String? = null,
        expectedSizeBytes: Long? = null,
    ): SecureContentTransferBinding =
        mediaCoordinator.stageNew(
            accountUuid = accountUuid,
            messageUuid = messageUuid,
            mimeType = mimeType,
            fileName = fileName,
            expectedSizeBytes = expectedSizeBytes,
            source = source,
        )
}
