// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-ANDROID

package eu.siacs.conversations.storage.secure

import com.google.crypto.tink.Aead

/**
 * Operation-scoped reuse of account metadata AEADs.
 *
 * The scope is created for one authenticated metadata scan and is discarded when that scan
 * returns. It deliberately does not extend key/primitive lifetime to the Store or process.
 * Unavailable account material is memoized too so malformed/stale records cannot repeatedly
 * trigger the same Android Keystore lookup within one scan.
 */
internal class SecureContentMetadataAeadReadScope(
    private val resolve: (String) -> Aead?,
) {
    private val resolved = HashMap<String, Aead>()
    private val unavailable = HashSet<String>()
    var resolveCount: Int = 0
        private set

    fun forAccount(accountUuid: String): Aead? {
        resolved[accountUuid]?.let { return it }
        if (accountUuid in unavailable) return null

        resolveCount++
        val aead = resolve(accountUuid)
        if (aead == null) {
            unavailable.add(accountUuid)
            return null
        }
        resolved[accountUuid] = aead
        return aead
    }

    val resolvedAccountCount: Int
        get() = resolved.size
}
