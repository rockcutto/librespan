package eu.siacs.conversations.http

import eu.siacs.conversations.storage.secure.SecureContentReadSession
import java.io.IOException
import java.io.InputStream

/**
 * Controlled wire representation for one future Secure Content upload.
 *
 * It owns neither account authorization, Store lifecycle, slot allocation nor message status.
 * Implementations must not expose a path, File, URI, BlobStore reference or plaintext cache.
 */
interface SecureContentWireSource : AutoCloseable {
    /** Exact number of bytes that the HTTP body will transmit. */
    val wireSizeBytes: Long

    /** Opens the only controlled wire stream for this attempt. */
    @Throws(IOException::class)
    fun openWireInputStream(): InputStream

    /**
     * Confirms terminal integrity for this wire source after it has reached EOF.
     * A successful stream close alone is insufficient.
     */
    @Throws(IOException::class)
    fun verifyTerminal()

    override fun close()
}

fun interface SecureContentWireProgressListener {
    /** Receives monotonic bytes actually written to the HTTP request body. */
    fun onWireBytesTransferred(transferredBytes: Long)
}

/**
 * Optional wire-protection boundary (for example the existing XEP-0454 compatibility adapter).
 *
 * The caller supplies a fresh Store-controlled reader only after preflight verification. The
 * adapter must return the exact wire size before slot allocation and must not publish anything.
 */
interface SecureContentWireProtectionAdapter {
    @Throws(IOException::class)
    fun prepare(
        descriptor: SecureContentUploadDescriptor,
        source: SecureContentReadSession,
    ): SecureContentWireSource
}
