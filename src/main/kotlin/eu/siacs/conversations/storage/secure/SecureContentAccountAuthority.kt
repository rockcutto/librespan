// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-CORE

package eu.siacs.conversations.storage.secure

import java.util.concurrent.atomic.AtomicReference

/**
 * Decides whether an account UUID is currently retained by the application account layer.
 *
 * Connection state is deliberately irrelevant: disabled and soft-logged-out accounts remain
 * registered owners until the local account itself is deleted. This is an ownership boundary, not
 * a human-authentication or key-material API. Implementations must fail closed and must not derive
 * membership from content, metadata, blobs or key records.
 */
fun interface SecureContentAccountAuthority {
    fun isRegisteredAccount(accountUuid: String): Boolean
}

/**
 * Process-owned, atomic membership snapshot supplied by the account service.
 *
 * It deliberately contains only UUIDs. An empty snapshot denies every Store operation until the
 * account service has restored its authoritative account set.
 */
class SecureContentAccountRegistry : SecureContentAccountAuthority {
    private val registeredAccountUuids = AtomicReference<Set<String>>(emptySet())

    fun replaceRegisteredAccountUuids(accountUuids: Collection<String>) {
        val replacement = accountUuids.mapTo(linkedSetOf()) { accountUuid ->
            require(accountUuid.isNotBlank()) { "accountUuid must not be blank" }
            accountUuid
        }
        registeredAccountUuids.set(replacement)
    }

    override fun isRegisteredAccount(accountUuid: String): Boolean =
        accountUuid.isNotBlank() && accountUuid in registeredAccountUuids.get()
}
