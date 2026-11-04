package eu.siacs.conversations.storage.secure

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureMediaTemporaryFileRevocationInstrumentationTest {
    @Test
    fun revocationDeletesOnlyAppOwnedTemporaryMedia() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val exportScope = File(context.cacheDir,
            AndroidSecureMessageMediaExporter.EXPORT_DIRECTORY + "/" + UUID.randomUUID())
        val readScope = File(context.cacheDir,
            AndroidSecureMessageMediaReadCache.CACHE_DIRECTORY + "/" + UUID.randomUUID())
        val unrelated = File(context.cacheDir, "unrelated-scs-audit-" + UUID.randomUUID())
        try {
            assertTrue(exportScope.mkdirs())
            assertTrue(readScope.mkdirs())
            val exported = File(exportScope, "view.bin")
            val cached = File(readScope, "playback.bin")
            exported.writeText("view plaintext")
            cached.writeText("playback plaintext")
            unrelated.writeText("must remain")

            AndroidSecureMessageMediaExporter.clearAllTemporaryFiles(context)
            AndroidSecureMessageMediaReadCache.clearAllTemporaryFiles(context)

            assertFalse(exported.exists())
            assertFalse(cached.exists())
            assertTrue(unrelated.exists())
        } finally {
            exportScope.deleteRecursively()
            readScope.deleteRecursively()
            unrelated.delete()
        }
    }
}
