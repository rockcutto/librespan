// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-ANDROID

package eu.siacs.conversations.storage.secure

import android.content.Context

/**
 * Process-scoped lazy composition point for the Secure Content runtime.
 *
 * Construction is cheap and does not touch Tink, Android Keystore, metadata, blobs or recovery.
 * The first [get] creates [RuntimeSecureContentStore], which may perform recovery and must
 * therefore be invoked from a background/I/O worker rather than a UI path.
 *
 * This provider is not a media migration switch. It exposes only [SecureContentStore], never a
 * path, BlobStore, KeyMaterialStore, CryptoEngine, plaintext bytes or key material.
 */
class SecureContentStoreProvider(
    context: Context,
    private val accountAuthority: SecureContentAccountAuthority,
) {
    private val applicationContext = context.applicationContext

    @Volatile
    private var runtimeStore: SecureContentStore? = null

    fun get(): SecureContentStore =
        runtimeStore ?: synchronized(this) {
            runtimeStore ?: run {
                val providerStarted = System.nanoTime()
                try {
                    val stagingStarted = System.nanoTime()
                    SecureOutgoingAttachmentStagingRecovery.sweepOrphans(applicationContext)
                    SecureColdStartPerfTrace.stage(
                        "store_staging_recovery",
                        System.nanoTime() - stagingStarted,
                    )
                    RuntimeSecureContentStore(applicationContext, accountAuthority).also {
                        runtimeStore = it
                    }
                } finally {
                    SecureColdStartPerfTrace.stage(
                        "store_provider_first_get",
                        System.nanoTime() - providerStarted,
                    )
                }
            }
        }
    
    /**
     * Runs the one-time pre-index compatibility scan outside the critical startup path.
     * Returns true only when a legacy store was actually indexed.
     */
    fun backfillMessageRelationIndexIfNeeded(): Boolean {
        val store = get() as? RuntimeSecureContentStore ?: return false
        return store.backfillMessageRelationIndexIfNeeded()
    }
}
