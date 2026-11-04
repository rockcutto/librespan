package eu.siacs.conversations.http

import eu.siacs.conversations.storage.secure.*
import java.io.ByteArrayInputStream
import java.io.IOException

/** Fixed committed Store fixture for the real secure upload callback. */
class UploadPublicationStore(account: String, message: String) : SecureContentStore {
    private val snapshot = SecureContentObject(SecureContentMetadata(
        accountUuid = account,
        contentId = "upload-content",
        namespace = SecureMessageMediaCoordinator.NAMESPACE,
        messageUuid = message,
        mimeType = "application/octet-stream",
        fileName = "attachment.bin",
        sizeBytes = 4,
        state = SecureContentState.COMMITTED,
        cryptoVersion = 1,
    ))
    var available = true
    override fun find(accountUuid: String, contentId: String) = snapshot.takeIf {
        available && it.accountUuid == accountUuid && it.contentId == contentId
    }
    override fun findByMessage(accountUuid: String, messageUuid: String) = listOfNotNull(
        snapshot.takeIf { available && it.accountUuid == accountUuid && it.messageUuid == messageUuid })
    override fun allocate(metadata: SecureContentMetadata): SecureContentHandle = error("unexpected allocation")
    override fun beginWrite(handle: SecureContentHandle): SecureContentWriteSession = error("unexpected write")
    override fun delete(accountUuid: String, contentId: String) { available = false }
    override fun open(accountUuid: String, contentId: String): SecureContentReadSession {
        val value = find(accountUuid, contentId) ?: throw IOException("missing committed object")
        return object : SecureContentReadSession {
            override val handle = value.handle
            override var state = SecureContentReadState.OPEN
            private val input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
            override fun openPlaintextInputStream() = input
            override fun verifyTerminal(): SecureContentObject {
                if (input.available() != 0) throw IOException("incomplete read")
                state = SecureContentReadState.TERMINAL_VERIFIED
                return value
            }
            override fun close() { state = SecureContentReadState.CLOSED }
        }
    }
}
