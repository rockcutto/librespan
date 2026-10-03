package eu.siacs.conversations.http

import java.io.IOException
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer

/**
 * Counts bytes that the delegated request body actually writes to the network sink.
 *
 * The wrapper is transport-only: it does not open files, resolve Store ownership or bypass the
 * SecureContentUploadRequestBody preflight/terminal-verification policy.
 */
class SecureContentProgressRequestBody(
    private val delegate: RequestBody,
    private val listener: SecureContentWireProgressListener,
) : RequestBody() {
    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    @Throws(IOException::class)
    override fun writeTo(sink: BufferedSink) {
        var transferred = 0L
        val countingSink = object : ForwardingSink(sink) {
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                transferred += byteCount
                listener.onWireBytesTransferred(transferred)
            }
        }.buffer()
        try {
            delegate.writeTo(countingSink)
            countingSink.flush()
        } finally {
            // The caller owns the OkHttp sink. Closing this wrapper would close the network body.
        }
    }
}
